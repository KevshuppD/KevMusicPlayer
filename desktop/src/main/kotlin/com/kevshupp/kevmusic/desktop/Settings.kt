package com.kevshupp.kevmusic.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File

/** Preferencias del PC (~/.config/kevmusic-desktop/settings.json), como estado de Compose. */
object Settings {
    /** Igual que el teléfono. */
    const val THEME_PHONE = "phone"

    private val file = File(Config.dir, "settings.json")

    var theme by mutableStateOf(THEME_PHONE)
    var showTranslation by mutableStateOf(true)
    /** 0 pequeña, 1 normal, 2 grande. */
    var lyricsSize by mutableStateOf(1)
    var autoCheckUpdates by mutableStateOf(true)
    /** Carátulas grandes en cuadrículas e Inicio: más nítidas, más trabajo para la gráfica. */
    var hdGridArt by mutableStateOf(false)
    /** Botón X de la ventana: [CLOSE_EXIT], [CLOSE_MINIMIZE] o [CLOSE_TRAY] (seguir en la bandeja del sistema). */
    var closeAction by mutableStateOf(CLOSE_EXIT)
    const val CLOSE_EXIT = "exit"
    const val CLOSE_MINIMIZE = "minimize"
    const val CLOSE_TRAY = "tray"
    /** Cómo dibuja Skiko la ventana: [RENDER_AUTO], [RENDER_HARDWARE] (OpenGL / Direct3D) o [RENDER_SOFTWARE]. Se aplica al abrir. */
    var renderer by mutableStateOf(RENDER_AUTO)
    const val RENDER_AUTO = "auto"
    const val RENDER_HARDWARE = "hardware"
    const val RENDER_SOFTWARE = "software"

    /** Contador de cuadros por segundo de Skiko en una esquina de la ventana (para medir la fluidez). */
    var showFps by mutableStateOf(false)

    /** Antes de crear la ventana: Skiko lee `skiko.renderApi` y `skiko.fps.enabled` una sola vez. */
    fun applyRenderer() {
        if (showFps) System.setProperty("skiko.fps.enabled", "true")
        when (renderer) {
            RENDER_HARDWARE -> System.setProperty("skiko.renderApi", if (Platform.isWindows) "DIRECT3D" else "OPENGL")
            RENDER_SOFTWARE -> System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
        }
    }

    fun load() {
        runCatching {
            val j = JSONObject(file.readText())
            theme = j.optString("theme", THEME_PHONE)
            showTranslation = j.optBoolean("showTranslation", true)
            lyricsSize = j.optInt("lyricsSize", 1).coerceIn(0, 2)
            autoCheckUpdates = j.optBoolean("autoCheckUpdates", true)
            hdGridArt = j.optBoolean("hdGridArt", false)
            closeAction = j.optString("closeAction", CLOSE_EXIT).takeIf { it in setOf(CLOSE_EXIT, CLOSE_MINIMIZE, CLOSE_TRAY) } ?: CLOSE_EXIT
            showFps = j.optBoolean("showFps", false)
            renderer = j.optString("renderer", RENDER_AUTO).takeIf { it in setOf(RENDER_AUTO, RENDER_HARDWARE, RENDER_SOFTWARE) } ?: RENDER_AUTO
        }
    }

    fun save() {
        runCatching {
            Config.dir.mkdirs()
            file.writeText(
                JSONObject().put("theme", theme).put("showTranslation", showTranslation).put("lyricsSize", lyricsSize)
                    .put("autoCheckUpdates", autoCheckUpdates).put("hdGridArt", hdGridArt).put("closeAction", closeAction)
                    .put("renderer", renderer).put("showFps", showFps).toString(2)
            )
        }
    }
}
