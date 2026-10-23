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

    fun load() {
        runCatching {
            val j = JSONObject(file.readText())
            theme = j.optString("theme", THEME_PHONE)
            showTranslation = j.optBoolean("showTranslation", true)
            lyricsSize = j.optInt("lyricsSize", 1).coerceIn(0, 2)
            autoCheckUpdates = j.optBoolean("autoCheckUpdates", true)
            hdGridArt = j.optBoolean("hdGridArt", false)
        }
    }

    fun save() {
        runCatching {
            Config.dir.mkdirs()
            file.writeText(
                JSONObject().put("theme", theme).put("showTranslation", showTranslation).put("lyricsSize", lyricsSize)
                    .put("autoCheckUpdates", autoCheckUpdates).put("hdGridArt", hdGridArt).toString(2)
            )
        }
    }
}
