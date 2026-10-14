package com.kevshupp.kevmusicplayer.playback.managers

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.util.Log
import androidx.palette.graphics.Palette
import com.kevshupp.kevmusicplayer.data.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

enum class ClipTemplateStyle {
    LYRICS_STORY,      // Estilo Letras (Spotify / Apple Music Lyrics Share)
    PLAYER_CANVAS,     // Estilo Reproductor con Carátula Grande
    VINYL_VIBES        // Estilo Vinilo Giratorio
}

data class ShareClipConfig(
    val songTitle: String,
    val artistName: String,
    val albumTitle: String? = null,
    val albumArtBitmap: Bitmap? = null,
    val startMs: Long,
    val endMs: Long,
    val audioUri: Uri,
    val lyricsLines: List<LyricLine> = emptyList(),
    val translatedLyrics: Map<Long, String>? = null,
    val showTranslation: Boolean = false,
    val dedicationText: String? = null,
    val isVertical: Boolean = true, // 9:16 vs 1:1
    val templateStyle: ClipTemplateStyle = ClipTemplateStyle.LYRICS_STORY,
    val isAmoledDark: Boolean = false,
    val showWaveform: Boolean = true,
    val primaryColor: Int = 0xFFFF4081.toInt(),
    val accentColor: Int = 0xFFB388FF.toInt()
)

private data class EncodedAudioSample(
    val data: ByteArray,
    val size: Int,
    val presentationTimeUs: Long,
    val flags: Int
)

object VideoClipGenerator {

    private const val TAG = "VideoClipGenerator"
    private const val FRAME_RATE = 30
    private const val I_FRAME_INTERVAL = 1
    private const val VIDEO_BIT_RATE = 3_500_000 // 3.5 Mbps
    private const val AUDIO_SAMPLE_RATE = 44100
    private const val AUDIO_CHANNEL_COUNT = 2
    private const val AUDIO_BIT_RATE = 192000

    suspend fun generateClip(
        context: Context,
        config: ShareClipConfig,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.Default) {
        val outputDir = File(context.cacheDir, "story_clips").apply { mkdirs() }
        val outputFile = File(outputDir, "Story_${System.currentTimeMillis()}.mp4")

        val width = 720
        val height = if (config.isVertical) 1280 else 720
        val durationMs = (config.endMs - config.startMs).coerceAtLeast(1000L)
        val totalFrames = ((durationMs / 1000.0) * FRAME_RATE).toInt().coerceAtLeast(FRAME_RATE)

        var muxer: MediaMuxer? = null
        var videoEncoder: MediaCodec? = null

        try {
            Log.d(TAG, "Starting clip generation: $width x $height, $durationMs ms, totalFrames=$totalFrames")
            onProgress(0.05f)

            // Extract dynamic palette colors from artwork
            val (dominantColor, vibrantAccentColor) = extractArtworkColors(config.albumArtBitmap, config.primaryColor, config.accentColor)

            // 1. Extract and transcode audio segment into memory
            val (audioFormat, audioSamples) = extractAudioToMemory(
                context = context,
                audioUri = config.audioUri,
                startMs = config.startMs,
                endMs = config.endMs
            )
            onProgress(0.15f)

            // 2. Setup Video Encoder (H.264 / AVC)
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var muxerStarted = false

            val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frameBitmap)
            val bufferInfo = MediaCodec.BufferInfo()
            val yuvBuffer = ByteArray(width * height * 3 / 2)
            val argbPixels = IntArray(width * height)

            var frameCount = 0
            var isInputDone = false
            var audioSampleIndex = 0

            fun writeAudioUpTo(targetPtsUs: Long) {
                if (!muxerStarted || audioTrackIndex < 0) return
                while (audioSampleIndex < audioSamples.size) {
                    val sample = audioSamples[audioSampleIndex]
                    if (sample.presentationTimeUs > targetPtsUs) break

                    val byteBuffer = ByteBuffer.wrap(sample.data, 0, sample.size)
                    val info = MediaCodec.BufferInfo().apply {
                        set(0, sample.size, sample.presentationTimeUs, sample.flags)
                    }
                    muxer.writeSampleData(audioTrackIndex, byteBuffer, info)
                    audioSampleIndex++
                }
            }

            // Main Encoding Loop
            while (frameCount < totalFrames || !isInputDone) {
                if (frameCount < totalFrames) {
                    val inputBufferIndex = videoEncoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = videoEncoder.getInputBuffer(inputBufferIndex) ?: continue
                        inputBuffer.clear()

                        val currentFrameMs = config.startMs + ((frameCount.toDouble() / totalFrames) * durationMs).toLong()
                        val frameProgress = frameCount.toFloat() / totalFrames.toFloat()

                        renderAuthenticStoryFrame(
                            canvas = canvas,
                            width = width,
                            height = height,
                            config = config,
                            dominantColor = dominantColor,
                            accentColor = vibrantAccentColor,
                            currentTimeMs = currentFrameMs,
                            clipProgress = frameProgress,
                            frameCount = frameCount
                        )

                        frameBitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
                        encodeYUV420SP(yuvBuffer, argbPixels, width, height)
                        val bytesToPut = kotlin.math.min(yuvBuffer.size, inputBuffer.remaining())
                        inputBuffer.put(yuvBuffer, 0, bytesToPut)

                        val ptsUs = (frameCount * 1_000_000L) / FRAME_RATE
                        videoEncoder.queueInputBuffer(inputBufferIndex, 0, bytesToPut, ptsUs, 0)
                        frameCount++

                        onProgress(0.15f + 0.75f * (frameCount.toFloat() / totalFrames.toFloat()))
                    }
                } else if (!isInputDone) {
                    val inputBufferIndex = videoEncoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val ptsUs = (totalFrames * 1_000_000L) / FRAME_RATE
                        videoEncoder.queueInputBuffer(inputBufferIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isInputDone = true
                    }
                }

                // Drain video encoder
                var outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000)
                while (outputBufferIndex >= 0) {
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = videoEncoder.outputFormat
                        videoTrackIndex = muxer.addTrack(newFormat)
                        if (audioFormat != null) {
                            audioTrackIndex = muxer.addTrack(audioFormat)
                        }
                        muxer.start()
                        muxerStarted = true
                    } else {
                        val outputBuffer = videoEncoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted) {
                            writeAudioUpTo(bufferInfo.presentationTimeUs)
                            if (bufferInfo.size > 0) {
                                muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                            }
                        }
                        videoEncoder.releaseOutputBuffer(outputBufferIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                    outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 0)
                }

                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = videoEncoder.outputFormat
                    videoTrackIndex = muxer.addTrack(newFormat)
                    if (audioFormat != null) {
                        audioTrackIndex = muxer.addTrack(audioFormat)
                    }
                    muxer.start()
                    muxerStarted = true
                }
            }

            // Drain remaining video encoder frames
            var encoderDone = false
            while (!encoderDone) {
                val outputBufferIndex = videoEncoder.dequeueOutputBuffer(bufferInfo, 20000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = videoEncoder.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && muxerStarted && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        writeAudioUpTo(bufferInfo.presentationTimeUs)
                        muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                    }
                    videoEncoder.releaseOutputBuffer(outputBufferIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (isInputDone) break
                }
            }

            // Write remaining audio samples
            if (muxerStarted && audioTrackIndex >= 0) {
                while (audioSampleIndex < audioSamples.size) {
                    val sample = audioSamples[audioSampleIndex]
                    val byteBuffer = ByteBuffer.wrap(sample.data, 0, sample.size)
                    val info = MediaCodec.BufferInfo().apply {
                        set(0, sample.size, sample.presentationTimeUs, sample.flags)
                    }
                    muxer.writeSampleData(audioTrackIndex, byteBuffer, info)
                    audioSampleIndex++
                }
            }

            try { videoEncoder.stop(); videoEncoder.release() } catch (e: Exception) {}
            try {
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (e: Exception) {}

            frameBitmap.recycle()

            onProgress(1.0f)
            Log.d(TAG, "Story clip generated successfully: ${outputFile.absolutePath}")
            Result.success(outputFile)
        } catch (e: Throwable) {
            Log.e(TAG, "Error generating clip", e)
            try { videoEncoder?.stop(); videoEncoder?.release() } catch (ex: Exception) {}
            try { muxer?.release() } catch (ex: Exception) {}
            outputFile.delete()
            Result.failure(e)
        }
    }

    private fun renderAuthenticStoryFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        config: ShareClipConfig,
        dominantColor: Int,
        accentColor: Int,
        currentTimeMs: Long,
        clipProgress: Float,
        frameCount: Int
    ) {
        // 1. Organic Ambient Background (Cinematic Lighting)
        if (config.isAmoledDark) {
            canvas.drawColor(Color.BLACK)
        } else {
            drawOrganicAmbientBackground(canvas, width, height, dominantColor, accentColor, frameCount)
        }

        when (config.templateStyle) {
            ClipTemplateStyle.LYRICS_STORY -> {
                renderSpotifyLyricsStory(canvas, width, height, config, dominantColor, accentColor, currentTimeMs, clipProgress, frameCount)
            }
            ClipTemplateStyle.PLAYER_CANVAS -> {
                renderPlayerCanvasStory(canvas, width, height, config, dominantColor, accentColor, currentTimeMs, clipProgress, frameCount)
            }
            ClipTemplateStyle.VINYL_VIBES -> {
                renderVinylVibesStory(canvas, width, height, config, dominantColor, accentColor, currentTimeMs, clipProgress, frameCount)
            }
        }

        // Dedication Badge at the Top
        if (!config.dedicationText.isNullOrBlank()) {
            val dedicationY = if (config.isVertical) 95f else 40f
            drawDedicationBadge(canvas, width, dedicationY, config.dedicationText)
        }
    }

    private fun drawDedicationBadge(
        canvas: Canvas,
        width: Int,
        yPos: Float,
        text: String
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 23f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val fullText = if (text.startsWith("💌") || text.startsWith("✨") || text.startsWith("💖")) text else "💌 $text"
        val textWidth = paint.measureText(fullText)
        val pillWidth = (textWidth + 48f).coerceAtMost(width - 80f)
        val pillHeight = 44f
        val pillLeft = (width - pillWidth) / 2f
        val pillTop = yPos - pillHeight / 2f
        val pillRect = RectF(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(100, 0, 0, 0)
            setShadowLayer(14f, 0f, 6f, Color.argb(120, 0, 0, 0))
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 18, 20, 32)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawRoundRect(pillRect, 22f, 22f, shadowPaint)
        canvas.drawRoundRect(pillRect, 22f, 22f, bgPaint)
        canvas.drawRoundRect(pillRect, 22f, 22f, borderPaint)

        canvas.drawText(truncateText(fullText, paint, pillWidth - 24f), width / 2f, yPos + 8f, paint)
    }

    // TEMPLATE 1: Spotify-style Authentic Lyrics Story Card
    private fun renderSpotifyLyricsStory(
        canvas: Canvas,
        width: Int,
        height: Int,
        config: ShareClipConfig,
        dominantColor: Int,
        accentColor: Int,
        currentTimeMs: Long,
        clipProgress: Float,
        frameCount: Int
    ) {
        val cardMargin = 40f
        val cardTop = if (config.isVertical) 160f else 60f
        val cardBottom = if (config.isVertical) height - 180f else height - 70f
        val cardRect = RectF(cardMargin, cardTop, width - cardMargin, cardBottom)

        // Glassmorphism Main Card
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 255, 255, 255)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 0, 0, 0)
            setShadowLayer(30f, 0f, 15f, Color.argb(160, 0, 0, 0))
        }
        canvas.drawRoundRect(cardRect, 32f, 32f, shadowPaint)
        canvas.drawRoundRect(cardRect, 32f, 32f, cardPaint)
        canvas.drawRoundRect(cardRect, 32f, 32f, borderPaint)

        // Lyric lines display (Authentic typography, displaying selected lines)
        if (config.lyricsLines.isNotEmpty()) {
            val selectedLines = config.lyricsLines.filter { it.timeMs in (config.startMs - 500)..config.endMs }
            val linesToDisplay = if (selectedLines.isNotEmpty()) selectedLines else config.lyricsLines.take(4)
            val currentLineIndex = linesToDisplay.indexOfLast { it.timeMs <= currentTimeMs }

            val lyricsStartY = cardTop + if (config.isVertical) 90f else 50f
            val baseSize = when {
                linesToDisplay.size <= 2 -> if (config.isVertical) 42f else 32f
                linesToDisplay.size <= 4 -> if (config.isVertical) 34f else 26f
                else -> if (config.isVertical) 26f else 20f
            }
            val activeSize = baseSize * 1.15f

            var lineY = lyricsStartY
            for ((idx, line) in linesToDisplay.withIndex()) {
                val isActive = idx == currentLineIndex

                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (isActive) Color.WHITE else Color.argb(130, 255, 255, 255)
                    textSize = if (isActive) activeSize else baseSize
                    typeface = Typeface.create(Typeface.DEFAULT, if (isActive) Typeface.BOLD else Typeface.NORMAL)
                    textAlign = Paint.Align.LEFT
                    if (isActive) {
                        setShadowLayer(16f, 0f, 0f, Color.argb(180, 255, 255, 255))
                    }
                }

                // Word wrap long lines cleanly
                val words = line.text.split(" ")
                var currentLine = ""
                for (word in words) {
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (textPaint.measureText(testLine) < (cardRect.width() - 70f)) {
                        currentLine = testLine
                    } else {
                        canvas.drawText(currentLine, cardMargin + 35f, lineY, textPaint)
                        lineY += (if (isActive) activeSize else baseSize) * 1.35f
                        currentLine = word
                    }
                }
                if (currentLine.isNotEmpty()) {
                    canvas.drawText(currentLine, cardMargin + 35f, lineY, textPaint)
                    lineY += (if (isActive) activeSize else baseSize) * 1.25f
                }

                // Translation Subtitle Line
                if (config.showTranslation && config.translatedLyrics != null) {
                    val trans = config.translatedLyrics[line.timeMs]
                    if (!trans.isNullOrBlank()) {
                        val transPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = if (isActive) Color.argb(235, 220, 230, 255) else Color.argb(110, 200, 210, 235)
                            textSize = baseSize * 0.72f
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                            textAlign = Paint.Align.LEFT
                        }
                        val transWords = trans.split(" ")
                        var curTrans = ""
                        for (w in transWords) {
                            val testT = if (curTrans.isEmpty()) w else "$curTrans $w"
                            if (transPaint.measureText(testT) < (cardRect.width() - 70f)) {
                                curTrans = testT
                            } else {
                                canvas.drawText(curTrans, cardMargin + 35f, lineY, transPaint)
                                lineY += baseSize * 0.95f
                                curTrans = w
                            }
                        }
                        if (curTrans.isNotEmpty()) {
                            canvas.drawText(curTrans, cardMargin + 35f, lineY, transPaint)
                            lineY += baseSize * 1.15f
                        }
                    }
                }
                lineY += baseSize * 0.35f
            }
        }

        // Bottom Song Badge inside Card
        val badgeHeight = 90f
        val badgeTop = cardBottom - badgeHeight - 24f
        val badgeLeft = cardMargin + 28f
        val artSize = 80f

        // Cover thumbnail
        val thumbRect = RectF(badgeLeft, badgeTop, badgeLeft + artSize, badgeTop + artSize)
        if (config.albumArtBitmap != null && !config.albumArtBitmap.isRecycled) {
            val thumbPath = Path().apply { addRoundRect(thumbRect, 16f, 16f, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(thumbPath)
            canvas.drawBitmap(config.albumArtBitmap, null, thumbRect, null)
            canvas.restore()
        } else {
            canvas.drawRoundRect(thumbRect, 16f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 35, 40, 60) })
        }

        // Title and Artist text
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 220, 225, 240)
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }

        val textLeft = badgeLeft + artSize + 20f
        val titleY = badgeTop + 36f
        val artistY = badgeTop + 70f
        val maxTextWidth = cardRect.width() - (badgeLeft - cardMargin) - artSize - 40f

        canvas.drawText(truncateText(config.songTitle, titlePaint, maxTextWidth), textLeft, titleY, titlePaint)
        canvas.drawText(truncateText(config.artistName, artistPaint, maxTextWidth), textLeft, artistY, artistPaint)

        // Waveform & Progress Bar at bottom of screen
        if (config.showWaveform) {
            drawSoundWaveform(canvas, width, height, config, accentColor, frameCount)
        }
        drawBottomProgressBar(canvas, width, height, config, dominantColor, clipProgress)
    }

    // TEMPLATE 2: Clean Player Canvas (Large Art + Lyrics + Waveform)
    private fun renderPlayerCanvasStory(
        canvas: Canvas,
        width: Int,
        height: Int,
        config: ShareClipConfig,
        dominantColor: Int,
        accentColor: Int,
        currentTimeMs: Long,
        clipProgress: Float,
        frameCount: Int
    ) {
        val artSize = (width * if (config.isVertical) 0.62f else 0.42f).toInt()
        val artTop = if (config.isVertical) 160f else 90f
        val artLeft = (width - artSize) / 2f

        // Artwork with 3D drop shadow
        val artRect = RectF(artLeft, artTop, artLeft + artSize, artTop + artSize)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 0, 0, 0)
            setShadowLayer(40f, 0f, 20f, Color.argb(200, 0, 0, 0))
        }
        canvas.drawRoundRect(artRect, 28f, 28f, shadowPaint)

        if (config.albumArtBitmap != null && !config.albumArtBitmap.isRecycled) {
            val clipPath = Path().apply { addRoundRect(artRect, 28f, 28f, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(config.albumArtBitmap, null, artRect, null)
            canvas.restore()
            
            // Refined border
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(50, 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            canvas.drawRoundRect(artRect, 28f, 28f, borderPaint)
        } else {
            canvas.drawRoundRect(artRect, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 30, 35, 55) })
        }

        // Title and Artist
        val textTop = artTop + artSize + 48f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 220, 225, 245)
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText(truncateText(config.songTitle, titlePaint, width - 80f), width / 2f, textTop, titlePaint)
        canvas.drawText(truncateText(config.artistName, artistPaint, width - 80f), width / 2f, textTop + 40f, artistPaint)

        // Active Lyric Highlight
        if (config.lyricsLines.isNotEmpty()) {
            val currentLineIndex = config.lyricsLines.indexOfLast { it.timeMs <= currentTimeMs }.coerceAtLeast(0)
            if (currentLineIndex in config.lyricsLines.indices) {
                val currentLine = config.lyricsLines[currentLineIndex]
                val lyricPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 28f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                    setShadowLayer(16f, 0f, 0f, accentColor)
                }
                canvas.drawText(truncateText("“${currentLine.text}”", lyricPaint, width - 100f), width / 2f, textTop + 105f, lyricPaint)

                // Translation if enabled
                if (config.showTranslation && config.translatedLyrics != null) {
                    val trans = config.translatedLyrics[currentLine.timeMs]
                    if (!trans.isNullOrBlank()) {
                        val transPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.argb(200, 220, 230, 255)
                            textSize = 20f
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                            textAlign = Paint.Align.CENTER
                        }
                        canvas.drawText(truncateText(trans, transPaint, width - 110f), width / 2f, textTop + 138f, transPaint)
                    }
                }
            }
        }

        if (config.showWaveform) {
            drawSoundWaveform(canvas, width, height, config, accentColor, frameCount)
        }
        drawBottomProgressBar(canvas, width, height, config, dominantColor, clipProgress)
    }

    // TEMPLATE 3: Vinyl Disc Vibes
    private fun renderVinylVibesStory(
        canvas: Canvas,
        width: Int,
        height: Int,
        config: ShareClipConfig,
        dominantColor: Int,
        accentColor: Int,
        currentTimeMs: Long,
        clipProgress: Float,
        frameCount: Int
    ) {
        val artSize = (width * if (config.isVertical) 0.52f else 0.38f).toInt()
        val artTop = if (config.isVertical) 160f else 90f
        val artLeft = (width - artSize) / 2f - 45f

        // Rotating vinyl disc popping out
        val vinylSize = (artSize * 0.94f)
        val vinylAngle = (frameCount * 3.5f) % 360f
        val vinylCenterY = artTop + artSize / 2f
        val vinylCenterX = artLeft + artSize * 0.90f

        canvas.save()
        canvas.rotate(vinylAngle, vinylCenterX, vinylCenterY)
        val vinylPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(245, 16, 16, 20)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(vinylCenterX, vinylCenterY, vinylSize / 2f, vinylPaint)

        // Grooves
        val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 255, 255, 255)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawCircle(vinylCenterX, vinylCenterY, vinylSize * 0.40f, groovePaint)
        canvas.drawCircle(vinylCenterX, vinylCenterY, vinylSize * 0.30f, groovePaint)
        canvas.drawCircle(vinylCenterX, vinylCenterY, vinylSize * 0.20f, groovePaint)

        // Vinyl center label
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dominantColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(vinylCenterX, vinylCenterY, vinylSize * 0.12f, labelPaint)
        canvas.drawCircle(vinylCenterX, vinylCenterY, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        canvas.restore()

        // Artwork box
        val artRect = RectF(artLeft, artTop, artLeft + artSize, artTop + artSize)
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 0, 0, 0)
            setShadowLayer(30f, 0f, 15f, Color.argb(200, 0, 0, 0))
        }
        canvas.drawRoundRect(artRect, 22f, 22f, shadowPaint)

        if (config.albumArtBitmap != null && !config.albumArtBitmap.isRecycled) {
            val clipPath = Path().apply { addRoundRect(artRect, 22f, 22f, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(config.albumArtBitmap, null, artRect, null)
            canvas.restore()
        } else {
            canvas.drawRoundRect(artRect, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 30, 35, 55) })
        }

        // Title and Artist
        val textTop = artTop + artSize + 48f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 220, 225, 245)
            textSize = 23f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText(truncateText(config.songTitle, titlePaint, width - 80f), width / 2f, textTop, titlePaint)
        canvas.drawText(truncateText(config.artistName, artistPaint, width - 80f), width / 2f, textTop + 38f, artistPaint)

        if (config.lyricsLines.isNotEmpty()) {
            val currentLineIndex = config.lyricsLines.indexOfLast { it.timeMs <= currentTimeMs }.coerceAtLeast(0)
            if (currentLineIndex in config.lyricsLines.indices) {
                val lineText = config.lyricsLines[currentLineIndex].text
                val lyricPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 28f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                    setShadowLayer(16f, 0f, 0f, accentColor)
                }
                canvas.drawText(truncateText(lineText, lyricPaint, width - 100f), width / 2f, textTop + 110f, lyricPaint)
            }
        }

        if (config.showWaveform) {
            drawSoundWaveform(canvas, width, height, config, accentColor, frameCount)
        }
        drawBottomProgressBar(canvas, width, height, config, dominantColor, clipProgress)
    }

    private fun drawOrganicAmbientBackground(
        canvas: Canvas,
        width: Int,
        height: Int,
        dominantColor: Int,
        accentColor: Int,
        frameCount: Int
    ) {
        val shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(
                Color.argb(255, 10, 11, 20),
                Color.argb(255, 18, 14, 28),
                Color.argb(255, 6, 7, 14)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        val bgPaint = Paint().apply { this.shader = shader }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Soft animated breathing glow spots matching song's cover art
        val breathe = (sin(frameCount * 0.06) * 24.0).toFloat()
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, Color.red(dominantColor), Color.green(dominantColor), Color.blue(dominantColor))
            maskFilter = BlurMaskFilter(220f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(width * 0.25f, height * 0.28f, 220f + breathe, glowPaint)

        glowPaint.color = Color.argb(50, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawCircle(width * 0.75f, height * 0.68f, 240f - breathe, glowPaint)
    }

    private fun drawSoundWaveform(
        canvas: Canvas,
        width: Int,
        height: Int,
        config: ShareClipConfig,
        accentColor: Int,
        frameCount: Int
    ) {
        val waveBottom = if (config.isVertical) height - 85f else height - 50f
        val barCount = 32
        val barWidth = 9f
        val spacing = (width - (barCount * barWidth)) / (barCount + 1)
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        for (i in 0 until barCount) {
            val x = spacing + i * (barWidth + spacing)
            val phase = frameCount * 0.16f + i * 0.38f
            val dynamicHeight = 10f + (24f * (sin(phase.toDouble()) * cos((phase * 0.7).toDouble()) + 1.0)).toFloat()

            val colorFactor = i.toFloat() / barCount
            val r = Color.red(config.primaryColor) + ((Color.red(accentColor) - Color.red(config.primaryColor)) * colorFactor).toInt()
            val g = Color.green(config.primaryColor) + ((Color.green(accentColor) - Color.green(config.primaryColor)) * colorFactor).toInt()
            val b = Color.blue(config.primaryColor) + ((Color.blue(accentColor) - Color.blue(config.primaryColor)) * colorFactor).toInt()
            barPaint.color = Color.rgb(r, g, b)

            val barRect = RectF(x, waveBottom - dynamicHeight, x + barWidth, waveBottom)
            canvas.drawRoundRect(barRect, 4.5f, 4.5f, barPaint)
        }
    }

    private fun drawBottomProgressBar(
        canvas: Canvas,
        width: Int,
        height: Int,
        config: ShareClipConfig,
        dominantColor: Int,
        clipProgress: Float
    ) {
        val progressY = if (config.isVertical) height - 35f else height - 16f
        val trackStart = 60f
        val trackEnd = width - 60f

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 255, 255, 255)
            strokeWidth = 4f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dominantColor
            strokeWidth = 4f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            setShadowLayer(8f, 0f, 0f, dominantColor)
        }

        canvas.drawLine(trackStart, progressY, trackEnd, progressY, trackPaint)
        val fillEnd = trackStart + (trackEnd - trackStart) * clipProgress
        if (fillEnd > trackStart) {
            canvas.drawLine(trackStart, progressY, fillEnd, progressY, fillPaint)
        }
    }

    private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var truncated = text
        while (truncated.isNotEmpty() && paint.measureText("$truncated...") > maxWidth) {
            truncated = truncated.dropLast(1)
        }
        return "$truncated..."
    }

    private fun extractArtworkColors(bitmap: Bitmap?, defaultPrimary: Int, defaultAccent: Int): Pair<Int, Int> {
        if (bitmap == null || bitmap.isRecycled) return Pair(defaultPrimary, defaultAccent)
        return try {
            val small = Bitmap.createScaledBitmap(bitmap, 48, 48, false)
            val palette = Palette.from(small).generate()
            small.recycle()
            val dominant = palette.getVibrantColor(palette.getDominantColor(defaultPrimary))
            val accent = palette.getLightVibrantColor(palette.getMutedColor(defaultAccent))
            Pair(dominant, accent)
        } catch (e: Exception) {
            Pair(defaultPrimary, defaultAccent)
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
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val srcMime = inputFormat.getString(MediaFormat.KEY_MIME)!!
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
                    if (outputBuffer != null && bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= startUs) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val pcmChunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(pcmChunk)
                        pcmOutputStream.write(pcmChunk)
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
