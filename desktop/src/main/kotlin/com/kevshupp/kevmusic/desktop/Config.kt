package com.kevshupp.kevmusic.desktop

import org.json.JSONObject
import java.io.File

/** Último teléfono emparejado, en ~/.config/kevmusic-desktop/config.json (el token da control del teléfono). */
data class SavedDevice(val host: String, val port: Int, val token: String, val name: String)

object Config {
    val dir = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config"), "kevmusic-desktop")
    private val file = File(dir, "config.json")

    fun load(): SavedDevice? = runCatching {
        val j = JSONObject(file.readText())
        SavedDevice(j.getString("host"), j.getInt("port"), j.getString("token"), j.optString("name", "Teléfono"))
    }.getOrNull()

    fun save(device: SavedDevice?) {
        runCatching {
            dir.mkdirs()
            if (device == null) { file.delete(); return }
            file.writeText(
                JSONObject().put("host", device.host).put("port", device.port)
                    .put("token", device.token).put("name", device.name).toString(2)
            )
            // Solo legible por el usuario
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
        }
    }
}
