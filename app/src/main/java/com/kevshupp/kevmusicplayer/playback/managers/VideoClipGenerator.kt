package com.kevshupp.kevmusicplayer.playback.managers

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLongArray
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext

private data class EncodedAudioSample(
    val data: ByteArray,
    val size: Int,
    val presentationTimeUs: Long,
    val flags: Int
)

/**
 * Genera el MP4 del fragmento (H.264 + AAC). Los cuadros los dibuja [ClipRenderer].
 *
 * Camino rápido: el codificador recibe los cuadros por su Surface de entrada y se dibujan con
 * `lockHardwareCanvas()` (GPU, sin pasar por un Bitmap ni convertir a YUV en Kotlin). Un hilo aparte vacía el
 * codificador mientras se dibuja (si no, con sus búferes llenos el dibujo se bloquea esperando) y el audio se
 * extrae en paralelo. El tiempo de cada cuadro lo pone el contador de salida, no el reloj de la Surface.
 * Si el camino rápido falla o le faltan cuadros, se repite con el de antes (Bitmap + YUV por CPU).
 */
object VideoClipGenerator {

    private const val TAG = "VideoClipGenerator"
    /** Cuadros que el dibujo puede ir por delante del codificador en el camino rápido. */
    private const val MAX_FRAMES_AHEAD = 3

    private const val I_FRAME_INTERVAL = 1
    private const val AUDIO_SAMPLE_RATE = 44100
    private const val AUDIO_CHANNEL_COUNT = 2
    private const val AUDIO_BIT_RATE = 192000

    /** Resolución real del video para esta configuración (múltiplos de 2). */
    fun outputSize(config: ShareClipConfig): Pair<Int, Int> {
        val s = config.quality.scale
        fun even(v: Float) = (v / 2f).toInt() * 2
        return even(config.format.logicalWidth * s) to even(config.format.logicalHeight * s)
    }

    /** Si el codificador H.264 del teléfono admite esa resolución a [fps] (60 fps a 1080p no lo tienen todos). */
    fun supportsFrameRate(config: ShareClipConfig, fps: Int): Boolean = runCatching {
        val (w, h) = outputSize(config)
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .any { info -> info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities?.areSizeAndRateSupported(w, h, fps.toDouble()) == true }
    }.getOrDefault(fps <= 30)

    /** Un cuadro para la vista previa del diálogo, dibujado por el mismo [ClipRenderer] que el video. */
    fun renderPreviewFrame(renderer: ClipRenderer, target: Bitmap, timeMs: Long, frame: Float) {
        val canvas = Canvas(target)
        canvas.save()
        canvas.scale(target.width / renderer.width, target.height / renderer.height)
        renderer.draw(canvas, timeMs, frame)
        canvas.restore()
    }

    suspend fun generateClip(
        context: Context,
        config: ShareClipConfig,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.Default) {
        val outputDir = File(context.cacheDir, "story_clips").apply { mkdirs() }
        // Los clips de antes ya se compartieron: se borran al pasar una hora para no llenar la caché
        cleanOldClips(context)
        val outputFile = File(outputDir, "Story_${System.currentTimeMillis()}.mp4")
        val renderer = ClipRenderer(context, config)
        try {
            onProgress(0.02f)
            // El audio se extrae mientras se dibujan los cuadros
            val audio = AudioJob(context, config)
            val ok = try {
                encodeWithSurface(config, renderer, audio, outputFile, onProgress)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Surface encoding failed, falling back to CPU", e)
                false
            }
            if (!ok) {
                outputFile.delete()
                onProgress(0.02f)
                encodeWithBitmap(config, renderer, audio, outputFile, onProgress)
            }
            onProgress(1f)
            Result.success(outputFile)
        } catch (e: Throwable) {
            Log.e(TAG, "Error generating clip", e)
            outputFile.delete()
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        } finally {
            renderer.release()
        }
    }

    /** Extracción del audio en un hilo propio; [await] espera el resultado. */
    private class AudioJob(context: Context, config: ShareClipConfig) {
        private val latch = CountDownLatch(1)
        @Volatile var result: Pair<MediaFormat?, List<EncodedAudioSample>> = null to emptyList()
        val done get() = latch.count == 0L
        init {
            thread(name = "clip-audio") {
                try { result = extractAudioToMemory(context, config.audioUri, config.startMs, config.endMs) }
                catch (e: Throwable) { Log.e(TAG, "Audio extraction failed", e) }
                finally { latch.countDown() }
            }
        }
        fun await() = latch.await().let { result }
    }

    private fun videoFormat(config: ShareClipConfig, colorFormat: Int): MediaFormat {
        val (w, h) = outputSize(config)
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            // Más cuadros, más datos: a 60 fps 1,5× el bitrate, a 24 un poco menos
            setInteger(MediaFormat.KEY_BIT_RATE, (config.quality.bitRate * when (config.fps) { 60 -> 1.5f; 24 -> 0.85f; else -> 1f }).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
    }

    private fun totalFrames(config: ShareClipConfig): Int {
        val durationMs = (config.endMs - config.startMs).coerceAtLeast(1000L)
        return ((durationMs / 1000.0) * config.fps).toInt().coerceAtLeast(config.fps)
    }

    private fun frameTimeMs(config: ShareClipConfig, frame: Int, total: Int): Long {
        val durationMs = (config.endMs - config.startMs).coerceAtLeast(1000L)
        return config.startMs + (frame.toDouble() / total * durationMs).toLong()
    }

    /**
     * Escribe en el muxer: espera a tener el formato de video y el audio (o saber que no hay) antes de arrancarlo,
     * guardando mientras tanto los cuadros ya codificados. Solo lo usa el hilo que vacía el codificador.
     */
    private class MuxWriter(path: String, private val audio: AudioJob) {
        private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var videoFormat: MediaFormat? = null
        private var videoTrack = -1
        private var audioTrack = -1
        private var started = false
        private var audioIndex = 0
        private val pending = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        @Volatile var videoFrames = 0
            private set

        fun setVideoFormat(format: MediaFormat) { videoFormat = format; tryStart(block = false) }

        private fun tryStart(block: Boolean) {
            if (started || videoFormat == null) return
            if (!audio.done && !block) return
            val (aFormat, _) = audio.await()
            videoTrack = muxer.addTrack(videoFormat!!)
            if (aFormat != null) audioTrack = muxer.addTrack(aFormat)
            muxer.start()
            started = true
            pending.forEach { (data, info) -> writeVideoNow(ByteBuffer.wrap(data), info) }
            pending.clear()
        }

        fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            videoFrames++
            tryStart(block = false)
            if (started) writeVideoNow(buffer, info)
            else {
                val copy = ByteArray(info.size)
                buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(copy)
                pending += copy to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
            }
        }

        private fun writeVideoNow(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            writeAudioUpTo(info.presentationTimeUs)
            muxer.writeSampleData(videoTrack, buffer, info)
        }

        private fun writeAudioUpTo(ptsUs: Long) {
            if (audioTrack < 0) return
            val samples = audio.result.second
            while (audioIndex < samples.size && samples[audioIndex].presentationTimeUs <= ptsUs) {
                val s = samples[audioIndex++]
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data, 0, s.size),
                    MediaCodec.BufferInfo().apply { set(0, s.size, s.presentationTimeUs, s.flags) })
            }
        }

        fun finish() {
            tryStart(block = true)
            writeAudioUpTo(Long.MAX_VALUE)
            try { if (started) muxer.stop() } finally { muxer.release() }
        }

        fun abort() = runCatching { muxer.release() }
    }

    /**
     * Vacía el codificador en un hilo propio. El tiempo de cada cuadro es su número / fps. En el camino rápido el
     * número sale de [postTimes] (cuándo se envió cada cuadro a la Surface, que le pone esa hora): si la Surface
     * descarta un cuadro, el siguiente conserva su tiempo y el anterior dura un poco más, sin desfasar la letra.
     * Sin [postTimes], el número es el orden de salida.
     */
    private fun startDrainer(
        encoder: MediaCodec, writer: MuxWriter, fps: Int, postTimes: AtomicLongArray? = null,
        lastFrameOut: java.util.concurrent.atomic.AtomicInteger? = null
    ): Thread = thread(name = "clip-drain") {
        val info = MediaCodec.BufferInfo()
        var outIndex = 0L
        var frameIndex = -1
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> writer.setVideoFormat(encoder.outputFormat)
                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index)
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (buffer != null && !isConfig && info.size > 0) {
                        val n = if (postTimes == null) outIndex++ else {
                            // El primer cuadro enviado después de esta hora es el siguiente; este es el último anterior
                            val ptsNs = info.presentationTimeUs * 1000
                            var i = frameIndex + 1
                            while (i + 1 < postTimes.length() && postTimes[i + 1] != 0L && postTimes[i + 1] <= ptsNs) i++
                            frameIndex = i.coerceAtMost(postTimes.length() - 1)
                            lastFrameOut?.set(frameIndex)
                            frameIndex.toLong()
                        }
                        info.presentationTimeUs = n * 1_000_000L / fps
                        writer.writeVideo(buffer, info)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return@thread
                }
            }
        }
    }

    private suspend fun encodeWithSurface(
        config: ShareClipConfig, renderer: ClipRenderer, audio: AudioJob, outputFile: File, onProgress: (Float) -> Unit
    ): Boolean {
        val (w, h) = outputSize(config)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var surface: android.view.Surface? = null
        val writer = MuxWriter(outputFile.absolutePath, audio)
        var drainer: Thread? = null
        try {
            encoder.configure(videoFormat(config, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = encoder.createInputSurface()
            encoder.start()
            val total = totalFrames(config)
            val postTimes = AtomicLongArray(total)
            val lastFrameOut = java.util.concurrent.atomic.AtomicInteger(-1)
            drainer = startDrainer(encoder, writer, config.fps, postTimes, lastFrameOut)
            val sx = w / renderer.width
            val sy = h / renderer.height
            val t0 = System.nanoTime()
            var lockNs = 0L
            var drawNs = 0L
            var postNs = 0L
            var waitNs = 0L
            for (frame in 0 until total) {
                coroutineContext.ensureActive()
                // La Surface descarta cuadros si le llegan más rápido de lo que el codificador los toma (hasta un
                // 7 % en el moto g35). Se espera a que el codificador vaya a menos de MAX_FRAMES_AHEAD cuadros,
                // con un límite por si retiene cuadros hasta recibir más.
                val w0 = System.nanoTime()
                var waited = 0
                while (frame - lastFrameOut.get() > MAX_FRAMES_AHEAD && waited < 20) { Thread.sleep(1); waited++ }
                waitNs += System.nanoTime() - w0
                val a = System.nanoTime()
                val canvas = surface.lockHardwareCanvas()
                val b = System.nanoTime()
                try {
                    canvas.save()
                    canvas.scale(sx, sy)
                    renderer.draw(canvas, frameTimeMs(config, frame, total), frame * 30f / config.fps)
                    canvas.restore()
                } finally {
                    val c = System.nanoTime()
                    postTimes.set(frame, c)
                    surface.unlockCanvasAndPost(canvas)
                    postNs += System.nanoTime() - c
                    drawNs += c - b
                }
                lockNs += b - a
                onProgress(0.02f + 0.93f * (frame + 1) / total)
            }
            encoder.signalEndOfInputStream()
            drainer.join(15_000)
            writer.finish()
            Log.i(TAG, "Surface ${w}x$h ${config.templateStyle}: $total frames in ${(System.nanoTime() - t0) / 1_000_000} ms " +
                "(wait ${waitNs / 1_000_000}, lock ${lockNs / 1_000_000}, draw ${drawNs / 1_000_000}, post ${postNs / 1_000_000}); " +
                "audio ready=${audio.done}, drain done=${drainer?.isAlive == false}")
            // Unos pocos cuadros descartados no se notan (el anterior dura 1/fps más); solo se repite por CPU si
            // falta una parte apreciable del video
            if (writer.videoFrames < total) Log.w(TAG, "Surface encoder produced ${writer.videoFrames}/$total frames")
            if (writer.videoFrames < total * 0.9) {
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logInfo(
                    "VideoClip", "Camino rápido incompleto (${writer.videoFrames}/$total cuadros, ${w}x$h ${config.fps} fps): se repite por CPU"
                )
                return false
            }
            return true
        } catch (e: Throwable) {
            writer.abort()
            throw e
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            surface?.release()
            if (drainer?.isAlive == true) drainer.interrupt()
        }
    }

    /** Camino de respaldo: cuadro en Bitmap, conversión a YUV por CPU y búfer de entrada. */
    private suspend fun encodeWithBitmap(
        config: ShareClipConfig, renderer: ClipRenderer, audio: AudioJob, outputFile: File, onProgress: (Float) -> Unit
    ) {
        val (w, h) = outputSize(config)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val writer = MuxWriter(outputFile.absolutePath, audio)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var drainer: Thread? = null
        try {
            encoder.configure(videoFormat(config, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            drainer = startDrainer(encoder, writer, config.fps)
            val canvas = Canvas(bitmap)
            val yuv = ByteArray(w * h * 3 / 2)
            val argb = IntArray(w * h)
            val total = totalFrames(config)
            var frame = 0
            while (frame <= total) {
                coroutineContext.ensureActive()
                val inIndex = encoder.dequeueInputBuffer(10_000)
                if (inIndex < 0) continue
                if (frame == total) {
                    encoder.queueInputBuffer(inIndex, 0, 0, frame * 1_000_000L / config.fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                val input = encoder.getInputBuffer(inIndex) ?: continue
                input.clear()
                canvas.save()
                canvas.scale(w / renderer.width, h / renderer.height)
                renderer.draw(canvas, frameTimeMs(config, frame, total), frame * 30f / config.fps)
                canvas.restore()
                bitmap.getPixels(argb, 0, w, 0, 0, w, h)
                encodeYUV420SP(yuv, argb, w, h)
                val bytes = minOf(yuv.size, input.remaining())
                input.put(yuv, 0, bytes)
                encoder.queueInputBuffer(inIndex, 0, bytes, frame * 1_000_000L / config.fps, 0)
                frame++
                onProgress(0.02f + 0.93f * frame / total)
            }
            drainer.join(15_000)
            writer.finish()
        } catch (e: Throwable) {
            writer.abort()
            throw e
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            bitmap.recycle()
            if (drainer?.isAlive == true) drainer.interrupt()
        }
    }

    /** Borra los clips generados hace más de una hora (la app que los recibió ya los copió). */
    fun cleanOldClips(context: Context) {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        File(context.cacheDir, "story_clips").listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun extractAudioToMemory(
        context: Context,
        audioUri: Uri,
        startMs: Long,
        endMs: Long
    ): Pair<MediaFormat?, List<EncodedAudioSample>> {
        val samples = mutableListOf<EncodedAudioSample>()
        var outputFormat: MediaFormat? = null

        val extractor = MediaExtractor()
        var pfd: android.os.ParcelFileDescriptor? = null
        try {
            if (audioUri.scheme == "content") {
                pfd = context.contentResolver.openFileDescriptor(audioUri, "r")
                if (pfd != null) {
                    extractor.setDataSource(pfd.fileDescriptor)
                } else {
                    extractor.setDataSource(context, audioUri, null)
                }
            } else if (audioUri.scheme == "file" || audioUri.path?.startsWith("/") == true) {
                val path = audioUri.path ?: audioUri.toString()
                extractor.setDataSource(path)
            } else {
                extractor.setDataSource(context, audioUri, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setDataSource on MediaExtractor: $audioUri", e)
            try { pfd?.close() } catch (ex: Exception) {}
            return Pair(null, emptyList())
        }

        var audioTrackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                inputFormat = format
                extractor.selectTrack(i)
                break
            }
        }

        if (audioTrackIndex == -1 || inputFormat == null) {
            try { extractor.release() } catch (e: Exception) {}
            try { pfd?.close() } catch (e: Exception) {}
            return Pair(null, emptyList())
        }

        val startUs = startMs * 1000L
        val endUs = endMs * 1000L
        val srcMime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        if (srcMime == MediaFormat.MIMETYPE_AUDIO_MPEG) {
            // En MP3 (sobre todo VBR sin tabla Xing) seekTo() estima la posición por bitrate y cae
            // segundos antes o después: la letra quedaba desfasada del audio. Leyendo los frames en
            // orden, sampleTime es exacto. Se empieza 200 ms antes para que el decodificador se estabilice.
            while (extractor.sampleTime in 0 until (startUs - 200_000L)) {
                if (!extractor.advance()) break
            }
        } else {
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }
        var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else AUDIO_SAMPLE_RATE
        var channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else AUDIO_CHANNEL_COUNT

        // Phase 1: Decode source audio to PCM in memory
        val pcmOutputStream = java.io.ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 10000L

        try {
            val decoder = MediaCodec.createDecoderByType(srcMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var isExtractorEOS = false
            var isDecoderDone = false

            while (!isDecoderDone) {
                if (!isExtractorEOS) {
                    val inputIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            val sampleTime = extractor.sampleTime

                            if (sampleSize < 0 || sampleTime > (endUs + 500_000L)) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isExtractorEOS = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                var outputIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                while (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // El PCM decodificado puede tener otra frecuencia o canales que el contenedor (p. ej. HE-AAC)
                        val decodedFormat = decoder.outputFormat
                        if (decodedFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = decodedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (decodedFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channelCount = decodedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                        continue
                    }
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        // Recorte exacto a [startUs, endUs] dentro del buffer: descartar el buffer entero
                        // que cruza el inicio adelantaba el audio respecto a la letra
                        val frameBytes = channelCount * 2
                        val ptsUs = bufferInfo.presentationTimeUs
                        val skipFrames = if (ptsUs < startUs) ((startUs - ptsUs) * sampleRate / 1_000_000L) else 0L
                        val endFrames = ((endUs - ptsUs) * sampleRate / 1_000_000L)
                        val from = (skipFrames * frameBytes).coerceIn(0L, bufferInfo.size.toLong()).toInt()
                        val to = (endFrames * frameBytes).coerceIn(0L, bufferInfo.size.toLong()).toInt()
                        if (to > from) {
                            outputBuffer.position(bufferInfo.offset + from)
                            outputBuffer.limit(bufferInfo.offset + to)
                            val pcmChunk = ByteArray(to - from)
                            outputBuffer.get(pcmChunk)
                            pcmOutputStream.write(pcmChunk)
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderDone = true
                        break
                    }
                    outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            try { decoder.stop(); decoder.release() } catch (e: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio to PCM", e)
        } finally {
            try { extractor.release() } catch (e: Exception) {}
            try { pfd?.close() } catch (e: Exception) {}
        }

        val pcmData = pcmOutputStream.toByteArray()
        if (pcmData.isEmpty()) {
            Log.w(TAG, "No PCM data decoded for range $startMs..$endMs")
            return Pair(null, emptyList())
        }

        // Phase 2: Encode raw PCM to AAC in memory
        try {
            val encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            var pcmOffset = 0
            val bytesPerSec = sampleRate * channelCount * 2
            var isEncoderInputEOS = false
            var isEncoderOutputEOS = false

            while (!isEncoderOutputEOS) {
                // Feed PCM into encoder
                if (!isEncoderInputEOS) {
                    val inputIndex = encoder.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val remainingPcm = pcmData.size - pcmOffset
                            if (remainingPcm > 0) {
                                val chunkSize = kotlin.math.min(remainingPcm, inputBuffer.remaining())
                                inputBuffer.put(pcmData, pcmOffset, chunkSize)
                                val ptsUs = (pcmOffset.toLong() * 1_000_000L) / bytesPerSec
                                pcmOffset += chunkSize
                                val flags = if (pcmOffset >= pcmData.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                encoder.queueInputBuffer(inputIndex, 0, chunkSize, ptsUs, flags)
                                if (flags != 0) isEncoderInputEOS = true
                            } else {
                                val ptsUs = (pcmOffset.toLong() * 1_000_000L) / bytesPerSec
                                encoder.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEncoderInputEOS = true
                            }
                        }
                    }
                }

                // Drain AAC output
                // INFO_OUTPUT_FORMAT_CHANGED es negativo: sin incluirlo nunca se obtenía el formato
                // y el muxer no añadía la pista de audio (vídeo mudo)
                var outputIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                while (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outputFormat = encoder.outputFormat
                    } else {
                        val encodedBuffer = encoder.getOutputBuffer(outputIndex)
                        if (encodedBuffer != null && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            encodedBuffer.position(bufferInfo.offset)
                            encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val aacBytes = ByteArray(bufferInfo.size)
                            encodedBuffer.get(aacBytes)
                            samples.add(
                                EncodedAudioSample(
                                    data = aacBytes,
                                    size = bufferInfo.size,
                                    presentationTimeUs = bufferInfo.presentationTimeUs,
                                    flags = bufferInfo.flags
                                )
                            )
                        }
                        encoder.releaseOutputBuffer(outputIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isEncoderOutputEOS = true
                            break
                        }
                    }
                    outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            try { encoder.stop(); encoder.release() } catch (e: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding PCM to AAC", e)
        }

        Log.d(TAG, "Audio extraction complete: ${samples.size} AAC samples, format=$outputFormat")
        return Pair(outputFormat, samples)
    }

    private fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize

        var r: Int
        var g: Int
        var b: Int
        var y: Int
        var u: Int
        var v: Int
        var index = 0

        for (j in 0 until height) {
            for (i in 0 until width) {
                val color = argb[index++]
                r = (color and 0xff0000) shr 16
                g = (color and 0xff00) shr 8
                b = (color and 0xff)

                y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128

                yuv420sp[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && index % 2 == 0 && uvIndex < yuv420sp.size - 1) {
                    yuv420sp[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv420sp[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }
}
