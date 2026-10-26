package com.kevshupp.kevmusicplayer

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kevshupp.kevmusicplayer.data.LyricLine
import com.kevshupp.kevmusicplayer.playback.managers.ClipFormat
import com.kevshupp.kevmusicplayer.playback.managers.ClipRenderer
import com.kevshupp.kevmusicplayer.playback.managers.ClipTemplateStyle
import com.kevshupp.kevmusicplayer.playback.managers.PixelScene
import com.kevshupp.kevmusicplayer.playback.managers.ShareClipConfig
import com.kevshupp.kevmusicplayer.playback.managers.VideoClipGenerator
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Guarda un cuadro de cada escena Pixel art en `files/scene_previews/` para revisarlas a ojo (vertical y horizontal). */
@RunWith(AndroidJUnit4::class)
class PixelScenePreviewTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun renderScenes() {
        val out = File(context.filesDir, "scene_previews").apply { deleteRecursively(); mkdirs() }
        val lyrics = listOf(LyricLine(0, "Bailando bajo luces de neón"), LyricLine(4000, "Hasta que salga el sol"))
        for (format in listOf(ClipFormat.STORY, ClipFormat.LANDSCAPE)) for (scene in PixelScene.entries) {
            val config = ShareClipConfig(
                songTitle = "Canción de prueba", artistName = "KevMusic", startMs = 0, endMs = 8000,
                audioUri = Uri.EMPTY, lyricsLines = lyrics, templateStyle = ClipTemplateStyle.PIXEL_ART,
                format = format, pixelScene = scene
            )
            val renderer = ClipRenderer(context, config)
            val bmp = Bitmap.createBitmap(renderer.width.toInt(), renderer.height.toInt(), Bitmap.Config.ARGB_8888)
            VideoClipGenerator.renderPreviewFrame(renderer, bmp, 1500, 45f)
            File(out, "${format.name.lowercase()}_${scene.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            renderer.release()
        }
    }
}
