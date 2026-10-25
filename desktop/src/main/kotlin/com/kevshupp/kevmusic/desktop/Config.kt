package com.kevshupp.kevmusic.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Teléfono emparejado (el token da control del teléfono). */
data class SavedDevice(val host: String, val port: Int, val token: String, val name: String) {
    /** La IP puede cambiar (DHCP); nombre y puerto distinguen el teléfono y la app (release 47820, debug 47821). */
    val key get() = "$name|$port"
}

/**
 * Teléfonos emparejados en ~/.config/kevmusic-desktop/config.json: todos los que se emparejaron, y el último usado
 * primero. Antes solo se guardaba uno y emparejar la app debug (u otro teléfono) hacía olvidar el anterior.
 */
object Config {
    val dir = File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config"), "kevmusic-desktop")
    private val file = File(dir, "config.json")

    /** El último teléfono usado, o null si no hay ninguno. */
    fun load(): SavedDevice? = all().firstOrNull()

    /** Todos, el último usado primero. */
    fun all(): List<SavedDevice> = runCatching {
        val j = JSONObject(file.readText())
        val arr = j.optJSONArray("devices")
        if (arr == null) {
            // Formato antiguo: un solo teléfono en la raíz
            listOf(SavedDevice(j.getString("host"), j.getInt("port"), j.getString("token"), j.optString("name", "Teléfono")))
        } else {
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                SavedDevice(it.getString("host"), it.getInt("port"), it.getString("token"), it.optString("name", "Teléfono"))
            }
        }
    }.getOrDefault(emptyList())

    fun find(name: String, port: Int): SavedDevice? = all().firstOrNull { it.name == name && it.port == port }

    /** Guarda (o actualiza) el teléfono y lo pone como último usado. */
    fun save(device: SavedDevice) = write(listOf(device) + all().filterNot { it.key == device.key })

    /** Olvida solo ese teléfono (token rechazado o "Desvincular"). */
    fun forget(device: SavedDevice) = write(all().filterNot { it.key == device.key })

    private fun write(devices: List<SavedDevice>) {
        runCatching {
            dir.mkdirs()
            if (devices.isEmpty()) { file.delete(); return }
            val arr = JSONArray()
            devices.forEach {
                arr.put(JSONObject().put("host", it.host).put("port", it.port).put("token", it.token).put("name", it.name))
            }
            file.writeText(JSONObject().put("devices", arr).toString(2))
            // Solo legible por el usuario
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
        }
    }
}
