package com.kevshupp.kevmusicplayer

import android.content.ContentUris
import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevshupp.kevmusicplayer.playback.managers.ClipTemplateStyle
import com.kevshupp.kevmusicplayer.playback.managers.ShareClipConfig
import com.kevshupp.kevmusicplayer.playback.managers.VideoClipGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device test: the fast (Surface) path must deliver every frame, so a clip is never encoded twice.
 * Uses the first song of the device longer than 70 s; reports time and frame count in logcat (VideoClipSpeedTest).
 */
@RunWith(AndroidJUnit4::class)
class VideoClipSpeedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun longSong(): android.net.Uri? {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DURATION} > 70000", null, null
        )?.use { c -> if (c.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) }
        return null
    }

    /** Cuadros del video y tiempo del último (µs). */
    private fun videoFrames(path: String): Pair<Int, Long> {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(track)
        var n = 0
        var last = 0L
        while (ex.sampleTime >= 0) { n++; last = maxOf(last, ex.sampleTime); ex.advance() }
        ex.release()
        return n to last
    }

    private fun run(name: String, config: ShareClipConfig) = runBlocking {
        val t0 = System.currentTimeMillis()
        val file = VideoClipGenerator.generateClip(context, config) {}.getOrThrow()
        val ms = System.currentTimeMillis() - t0
        val expected = ((config.endMs - config.startMs) / 1000.0 * config.fps).toInt()
        val (frames, lastUs) = videoFrames(file.absolutePath)
        val expectedLastUs = (expected - 1) * 1_000_000L / config.fps
        Log.i("VideoClipSpeedTest", "$name: $ms ms, $frames/$expected frames, last frame at ${lastUs / 1000} ms (expected ${expectedLastUs / 1000})")
        file.delete()
        // Sin desfase: el último cuadro está donde debe (a lo sumo 2 cuadros antes si se descartaron los finales)
        assertTrue("$name: last frame at $lastUs, expected $expectedLastUs", lastUs >= expectedLastUs - 2_000_000L / config.fps)
        assertTrue("$name: $frames/$expected frames", frames >= expected * 0.9)
    }

    @Test
    fun longVinyl60fps() {
        val uri = longSong()
        assumeTrue("no song longer than 70 s", uri != null)
        run("vinyl 720p60 57s", ShareClipConfig(
            songTitle = "Test", artistName = "Test", startMs = 5_000, endMs = 62_000, audioUri = uri!!,
            templateStyle = ClipTemplateStyle.VINYL_VIBES, fps = 60
        ))
    }

    @Test
    fun shortLyrics30fps() {
        val uri = longSong()
        assumeTrue("no song longer than 70 s", uri != null)
        run("lyrics 720p30 13s", ShareClipConfig(
            songTitle = "Test", artistName = "Test", startMs = 20_000, endMs = 33_000, audioUri = uri!!, fps = 30
        ))
    }
}
