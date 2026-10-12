package com.kevshupp.kevmusicplayer

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevshupp.kevmusicplayer.playback.createJaudiotaggerArtwork
import com.kevshupp.kevmusicplayer.playback.writeMetadataWithTempFile
import com.kevshupp.kevmusicplayer.playback.writeMp3TagsWithMp3Agic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Device test: rewriting tags must never change or cut the audio of a song.
 * It works on a folder of throwaway copies passed with `-e testDir <path>`; without it the test is skipped.
 */
@RunWith(AndroidJUnit4::class)
class AudioWriteSafetyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testDir = File(InstrumentationRegistry.getArguments().getString("testDir") ?: "/nonexistent")

    private fun songs(): List<File> =
        testDir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("mp3", "flac", "m4a") }
            ?.sortedBy { it.name } ?: emptyList()

    // Hash of every decoded-container sample: it only depends on the audio, not on tags or covers
    private fun audioHash(file: File): String {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(0)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            var samples = 0
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                digest.update(buffer.array(), 0, size)
                samples++
                extractor.advance()
            }
            "$samples:" + digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "unreadable:${e.javaClass.simpleName}"
        } finally {
            extractor.release()
        }
    }

    private fun cover(seed: Int): ByteArray {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(1000 * 1000) { random.nextInt() or (0xFF shl 24) }
        bitmap.setPixels(pixels, 0, 1000, 0, 0, 1000, 1000)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    // Same sequence the app runs when it embeds a cover (TagEditorManager.updateAlbumCover)
    private fun writeCover(file: File, coverBytes: ByteArray): Boolean {
        val uriString = Uri.fromFile(file).toString()
        if (file.extension.equals("mp3", ignoreCase = true) &&
            writeMp3TagsWithMp3Agic(context, -1L, uriString, file.absolutePath, coverBytes = coverBytes)
        ) return true
        return writeMetadataWithTempFile(context, -1L, uriString) { audioFile ->
            val tag = audioFile.getTagOrCreateAndSetDefault()
            // As in the app, a format that rejects the artwork still gets its file rewritten
            try {
                val artwork = createJaudiotaggerArtwork(coverBytes)
                if (artwork != null) {
                    try { tag.deleteArtworkField() } catch (e: Throwable) {}
                    try {
                        tag.setField(artwork)
                    } catch (e: Throwable) {
                        try {
                            tag.addField(artwork)
                        } catch (e2: Throwable) {
                            tag.createField(artwork)?.let { tag.setField(it) }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Artwork rejected for ${file.name}: $e")
            }
            audioFile.tag = tag
        }
    }

    @Test
    fun concurrentWritesKeepAudioIntact() {
        val files = songs()
        assumeTrue("No test songs in $testDir", files.isNotEmpty())
        val baseline = files.associateWith { audioHash(it) }
        baseline.forEach { (f, h) -> assertTrue("Baseline unreadable: ${f.name}", !h.startsWith("unreadable") && !h.startsWith("0:")) }
        val covers = listOf(cover(1), cover(2), cover(3))
        Log.i(TAG, "files=${files.size} coverBytes=${covers.map { it.size }}")

        val readerMismatches = Collections.synchronizedList(mutableListOf<String>())
        val writeResults = Collections.synchronizedList(mutableListOf<Boolean>())
        val pool = Executors.newFixedThreadPool(8)
        repeat(4) { round ->
            val tasks = mutableListOf<Callable<Unit>>()
            for (file in files) {
                tasks += Callable { writeResults += writeCover(file, covers[round % 3]) }
                tasks += Callable { writeResults += writeCover(file, covers[(round + 1) % 3]) }
                tasks += Callable {
                    val seen = audioHash(file)
                    if (seen != baseline[file]) readerMismatches += "round $round ${file.name}: $seen"
                }
            }
            pool.invokeAll(tasks.shuffled(Random(round))).forEach { it.get() }
        }
        pool.shutdown()

        val damaged = files.filter { audioHash(it) != baseline[it] }.map { "${it.name} (${it.length()} bytes)" }
        val leftovers = testDir.listFiles { f -> f.name.startsWith(".kmp_write_") }?.map { it.name } ?: emptyList()
        Log.i(TAG, "writes ok=${writeResults.count { it }}/${writeResults.size} readerMismatches=${readerMismatches.size} damaged=${damaged.size}")
        readerMismatches.take(10).forEach { Log.i(TAG, "reader saw: $it") }
        damaged.forEach { Log.i(TAG, "damaged: $it") }

        assertEquals("Songs with changed or cut audio", emptyList<String>(), damaged)
        assertEquals("A reader saw a partial file while it was being rewritten", emptyList<String>(), readerMismatches.take(5))
        assertEquals("Temporary files left behind", emptyList<String>(), leftovers)
        assertTrue("No write succeeded", writeResults.any { it })
    }

    @Test
    fun coverIsEmbeddedInEveryFormat() {
        val files = songs()
        assumeTrue("No test songs in $testDir", files.isNotEmpty())
        val coverBytes = cover(11)
        val missing = mutableListOf<String>()
        for (file in files) {
            val baseline = audioHash(file)
            val written = writeCover(file, coverBytes)
            val retriever = android.media.MediaMetadataRetriever()
            val embedded = try {
                retriever.setDataSource(file.absolutePath)
                retriever.embeddedPicture
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
            Log.i(TAG, "${file.name}: written=$written embedded=${embedded?.size} expected=${coverBytes.size}")
            if (!written || embedded == null || !embedded.contentEquals(coverBytes)) missing += file.name
            assertEquals("Audio changed in ${file.name}", baseline, audioHash(file))
        }
        assertEquals("Songs without the new cover embedded", emptyList<String>(), missing)
    }

    @Test
    fun fullStorageLeavesSongUntouched() {
        val file = songs().firstOrNull { it.extension.equals("mp3", ignoreCase = true) }
        assumeTrue("No test mp3 in $testDir", file != null)
        file!!
        val baseline = audioHash(file)
        val sizeBefore = file.length()
        val bigCover = cover(7)
        val filler = File(testDir, "filler.bin")
        try {
            filler.outputStream().use { out ->
                val block = ByteArray(1024 * 1024)
                try {
                    while (testDir.usableSpace > 64 * 1024) {
                        out.write(block, 0, minOf(block.size.toLong(), maxOf(4096L, testDir.usableSpace - 64 * 1024)).toInt())
                    }
                } catch (e: java.io.IOException) {
                    // Storage is full: exactly the state under test
                }
            }
            Log.i(TAG, "free after filling: ${testDir.usableSpace} bytes, cover=${bigCover.size}")
            val result = writeCover(file, bigCover)
            Log.i(TAG, "write on full storage returned $result, size $sizeBefore -> ${file.length()}")
            assertEquals("Song audio changed after a write on full storage", baseline, audioHash(file))
            assertTrue("Song got smaller after a write on full storage", file.length() >= sizeBefore)
        } finally {
            filler.delete()
        }
        assertTrue("Write should succeed once there is space again", writeCover(file, bigCover))
        assertEquals(baseline, audioHash(file))
    }

    private companion object {
        const val TAG = "AudioWriteSafetyTest"
    }
}
