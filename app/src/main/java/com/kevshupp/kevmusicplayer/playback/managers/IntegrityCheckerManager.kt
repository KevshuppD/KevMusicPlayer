package com.kevshupp.kevmusicplayer.playback.managers

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.kevshupp.kevmusicplayer.data.AudioFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IntegrityCheckerManager(
    private val localAudioFiles: SnapshotStateList<AudioFile>,
    private val scope: CoroutineScope
) {
    var isVerifyingIntegrity = mutableStateOf(false)
        private set
    var verifyIntegrityCurrent = mutableStateOf(0)
        private set
    var verifyIntegrityTotal = mutableStateOf(0)
        private set
    var verifyIntegrityCurrentName = mutableStateOf("")
        private set

    fun verifySongsIntegrity(
        context: Context,
        onComplete: (List<Pair<AudioFile, String>>) -> Unit
    ) {
        if (isVerifyingIntegrity.value) return
        isVerifyingIntegrity.value = true
        verifyIntegrityCurrent.value = 0
        verifyIntegrityTotal.value = 0
        verifyIntegrityCurrentName.value = ""
        
        scope.launch(Dispatchers.IO) {
            val songsToVerify = localAudioFiles.toList()
            val totalSize = songsToVerify.size
            withContext(Dispatchers.Main) {
                verifyIntegrityTotal.value = totalSize
            }
            val damaged = java.util.Collections.synchronizedList(mutableListOf<Pair<AudioFile, String>>())
            val isEs = java.util.Locale.getDefault().language == "es"
            val processedCount = java.util.concurrent.atomic.AtomicInteger(0)
            var lastUiUpdateTime = 0L

            // 4 a la vez: con 16 en paralelo (cada una con su extractor y su buffer) la memoria llegaba al límite
            val limiter = kotlinx.coroutines.sync.Semaphore(4)
            try {
                coroutineScope {
                    songsToVerify.map { song ->
                        async {
                            limiter.acquire()
                            try {
                                // Un archivo dañado puede bloquear la lectura nativa sin responder a la cancelación:
                                // se analiza aparte y, si no termina, se informa en lugar de esperar para siempre
                                val check = checkerScope.async { checkSongIntegrity(context, song, isEs) }
                                val reason = kotlinx.coroutines.withTimeoutOrNull(SONG_TIMEOUT_MS) { check.await() }
                                    ?: if (check.isCompleted) null
                                    else if (isEs) "El archivo no responde al leerlo" else "The file does not respond when read"
                                if (reason != null) damaged.add(Pair(song, reason))
                            } finally {
                                limiter.release()
                            }
                            val count = processedCount.incrementAndGet()
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdateTime > 80L || count == totalSize) {
                                lastUiUpdateTime = now
                                withContext(Dispatchers.Main) {
                                    verifyIntegrityCurrent.value = count
                                    verifyIntegrityCurrentName.value = song.title
                                }
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(context, "Integrity", "El verificador falló", e)
            } finally {
                // Pase lo que pase el diálogo termina: antes un error dejaba "Analizando…" para siempre
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    isVerifyingIntegrity.value = false
                    onComplete(damaged.toList())
                }
            }
        }
    }

    private val checkerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Motivo por el que la canción está dañada, o null si está bien. Nunca lanza: un fallo cuenta como daño. */
    private fun checkSongIntegrity(context: Context, song: AudioFile, isEs: Boolean): String? = try {
        checkSongIntegrityUnsafe(context, song, isEs)
    } catch (e: Throwable) {
        if (isEs) "No se pudo analizar el archivo" else "The file could not be analyzed"
    }

    private fun checkSongIntegrityUnsafe(context: Context, song: AudioFile, isEs: Boolean): String? {
        val uri = Uri.parse(song.uriString)
        // 1. Check if the URI is readable
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { }
                ?: return if (isEs) "Archivo inaccesible o eliminado" else "File inaccessible or deleted"
        } catch (e: Exception) {
            return if (isEs) "No se puede abrir el archivo" else "Cannot open file"
        }

        // 2. If it is readable, try parsing metadata to see if it's corrupted/damaged
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == null) {
                return if (isEs) "Archivo de audio sin pistas válidas" else "Audio file has no valid tracks"
            }
        } catch (e: Exception) {
            return if (isEs) "Archivo de audio dañado o corrupto" else "Corrupted or damaged audio file"
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }

        // 3. A file can open fine and still be cut short: check that the audio really reaches the end
        findAudioTruncation(context, uri)?.let { truncation ->
            val endsAt = formatIntegrityTime(truncation.audioEndsAtMs)
            val total = formatIntegrityTime(truncation.declaredDurationMs)
            return if (isEs) "Archivo incompleto: el audio termina en $endsAt de $total"
            else "Incomplete file: audio ends at $endsAt of $total"
        }

        // 4. Blocks lost while copying: same size, but part of the audio reads as zeros
        findZeroGap(context, uri, song.duration)?.let { gap ->
            val kb = gap.lengthBytes / 1024
            return if (isEs) "Audio perdido: faltan $kb KB cerca de ${formatIntegrityTime(gap.atMs)}"
            else "Lost audio: $kb KB missing near ${formatIntegrityTime(gap.atMs)}"
        }
        return null
    }

    private companion object {
        const val SONG_TIMEOUT_MS = 60_000L
    }
}

/** Where the audio of a cut file really stops, against the length its header declares. */
data class AudioTruncation(val audioEndsAtMs: Long, val declaredDurationMs: Long)

private fun formatIntegrityTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun mp3DeclaresLength(context: Context, uri: Uri): Boolean {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val id3 = ByteArray(10)
            var read = input.read(id3)
            var head = ByteArray(0)
            if (read == 10 && id3[0] == 'I'.code.toByte() && id3[1] == 'D'.code.toByte() && id3[2] == '3'.code.toByte()) {
                // Skip the ID3v2 tag (syncsafe size), which can hold a cover of several megabytes
                var toSkip = ((id3[6].toLong() and 0x7F) shl 21) or ((id3[7].toLong() and 0x7F) shl 14) or
                    ((id3[8].toLong() and 0x7F) shl 7) or (id3[9].toLong() and 0x7F)
                if ((id3[5].toInt() and 0x10) != 0) toSkip += 10
                while (toSkip > 0) {
                    val skipped = input.skip(toSkip)
                    if (skipped <= 0) break
                    toSkip -= skipped
                }
            } else if (read > 0) {
                head = id3.copyOf(read)
            }
            // The header lives inside the first audio frame
            val block = ByteArray(4096)
            var filled = 0
            while (filled < block.size) {
                read = input.read(block, filled, block.size - filled)
                if (read <= 0) break
                filled += read
            }
            val text = String(head + block.copyOf(filled), Charsets.ISO_8859_1)
            text.contains("Xing") || text.contains("Info") || text.contains("VBRI")
        } ?: false
    } catch (e: Exception) {
        false
    }
}

/**
 * Detects a truncated audio file. The header of such a file still declares the full length, so players show the
 * whole duration and then stop early. Reading the last seconds tells them apart: a complete file has samples
 * there, a cut one has none. Returns null when the file is complete or its length can not be determined.
 */
fun findAudioTruncation(context: Context, uri: Uri): AudioTruncation? {
    val extractor = android.media.MediaExtractor()
    return try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null
        val format = extractor.getTrackFormat(track)
        if (!format.containsKey(android.media.MediaFormat.KEY_DURATION)) return null
        // An MP3 without a Xing/Info/VBRI header has no declared length: its duration is guessed from the file
        // size, which also counts trailing tags, so it says nothing about missing audio
        val mime = format.getString(android.media.MediaFormat.KEY_MIME)
        if (mime == android.media.MediaFormat.MIMETYPE_AUDIO_MPEG && !mp3DeclaresLength(context, uri)) return null
        val durationUs = format.getLong(android.media.MediaFormat.KEY_DURATION)
        // Too short to judge with a margin of a few seconds
        if (durationUs < 15_000_000L) return null
        extractor.selectTrack(track)

        // Una muestra de audio comprimido ocupa pocos KB; 1 MB sobra y no satura la memoria con varias a la vez
        val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
        // Time of the last sample readable from [fromUs] on, or -1 if there is nothing left to read there
        fun lastSampleTimeFrom(fromUs: Long, maxSamples: Int): Long {
            extractor.seekTo(fromUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var last = -1L
            var read = 0
            while (read < maxSamples) {
                buffer.clear()
                if (extractor.readSampleData(buffer, 0) < 0) break
                last = extractor.sampleTime
                read++
                if (!extractor.advance()) break
            }
            return last
        }

        // The declared length is only an estimate for some files (VBR MP3 without a header can be off by a few
        // seconds), so a file counts as cut only when a clear part of it is missing
        val toleranceUs = maxOf(8_000_000L, durationUs * 3 / 100)
        val tailStartUs = durationUs - toleranceUs - 2_000_000L
        val lastUs = lastSampleTimeFrom(tailStartUs, 20_000)
        if (lastUs >= durationUs - toleranceUs) return null

        // Cut file: narrow down where the audio stops, for the report
        var lowUs = 0L
        var highUs = tailStartUs
        repeat(14) {
            val midUs = (lowUs + highUs) / 2
            if (lastSampleTimeFrom(midUs, 1) >= 0) lowUs = midUs else highUs = midUs
        }
        val endUs = maxOf(lowUs, lastSampleTimeFrom(lowUs, 400))
        AudioTruncation(audioEndsAtMs = endUs / 1000, declaredDurationMs = durationUs / 1000)
    } catch (e: Exception) {
        null
    } finally {
        try { extractor.release() } catch (e: Exception) {}
    }
}

/** A run of zeros inside the audio, where the file lost blocks of data. */
data class AudioZeroGap(val atMs: Long, val lengthBytes: Long)

/**
 * Finds blocks of the file that read as zeros. They appear when a copy does not reach the storage intact: the file
 * keeps its size, but whole 4 KB blocks are lost, so the song plays shorter or jumps. Compressed audio never holds
 * 32 KB of zeros starting on a block boundary; uncompressed WAV/AIFF can (digital silence) and is skipped.
 */
fun findZeroGap(context: Context, uri: Uri, durationMs: Long): AudioZeroGap? {
    val mime = context.contentResolver.getType(uri).orEmpty()
    if (mime.contains("wav") || mime.contains("aiff")) return null
    val minRun = 32 * 1024L
    // The first 256 KB hold the tags and the cover, whose padding can be zeros
    val skip = 256 * 1024L
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val block = ByteArray(4096)
            var offset = 0L
            var runStart = -1L
            var found: Pair<Long, Long>? = null
            while (found == null) {
                var filled = 0
                while (filled < block.size) {
                    val read = input.read(block, filled, block.size - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled == 0) break
                val zero = filled == block.size && block.all { it == 0.toByte() }
                if (zero) {
                    if (runStart < 0) runStart = offset
                } else if (runStart >= 0) {
                    if (runStart >= skip && offset - runStart >= minRun) found = runStart to (offset - runStart)
                    runStart = -1
                }
                offset += filled
                if (filled < block.size) break
            }
            if (found == null && runStart >= skip && offset - runStart >= minRun) found = runStart to (offset - runStart)
            val (start, length) = found ?: return@use null
            val atMs = if (offset > 0) durationMs * start / offset else 0L
            AudioZeroGap(atMs, length)
        }
    } catch (e: Exception) {
        null
    }
}
