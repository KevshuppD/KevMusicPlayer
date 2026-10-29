package com.kevshupp.kevmusic.desktop.soulseek

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kevshupp.kevmusic.desktop.Config
import com.kevshupp.kevmusic.desktop.DzAlbum
import com.kevshupp.soulseek.SlskSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

// El protocolo y el cliente están en soulseek-core (com.kevshupp.soulseek), compartido con la app de Android

/**
 * Ajustes y cuenta de Soulseek en ~/.config/kevmusic-desktop/soulseek.json (solo legible por el usuario,
 * contiene la contraseña).
 */
object SoulseekConfig : SlskSettings {
    private val file = File(Config.dir, "soulseek.json")

    override var username by mutableStateOf("")
    override var password by mutableStateOf("")
    var autoConnect by mutableStateOf(true)
    override var sharedFolders by mutableStateOf<List<String>>(emptyList())
    override var downloadDir by mutableStateOf("")
    override var port by mutableStateOf(2234)
    override var useUpnp by mutableStateOf(true)
    override var uploadSlots by mutableStateOf(3)
    /** KB/s; 0 sin límite. */
    override var uploadLimit by mutableStateOf(0)
    override var sendToPhone by mutableStateOf(true)
    /** Conservar la copia del PC tras enviarla al teléfono (y seguir compartiéndola). */
    override var keepOnPc by mutableStateOf(true)

    fun load() {
        val music = xdgMusicDir()
        runCatching {
            val j = JSONObject(file.readText())
            username = j.optString("username")
            password = j.optString("password")
            autoConnect = j.optBoolean("autoConnect", true)
            sharedFolders = j.optJSONArray("shared")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf(music.path)
            downloadDir = j.optString("downloadDir").ifBlank { File(music, "Soulseek").path }
            port = j.optInt("port", 2234)
            useUpnp = j.optBoolean("upnp", true)
            uploadSlots = j.optInt("uploadSlots", 3).coerceIn(1, 10)
            uploadLimit = j.optInt("uploadLimit", 0).coerceAtLeast(0)
            sendToPhone = j.optBoolean("sendToPhone", true)
            keepOnPc = j.optBoolean("keepOnPc", true)
        }.onFailure {
            // Primera vez: se comparte la carpeta de música y las descargas van dentro (también se comparten)
            sharedFolders = if (music.isDirectory) listOf(music.path) else emptyList()
            downloadDir = File(music, "Soulseek").path
        }
    }

    fun save() {
        runCatching {
            Config.dir.mkdirs()
            file.writeText(
                JSONObject().put("username", username).put("password", password).put("autoConnect", autoConnect)
                    .put("shared", JSONArray(sharedFolders)).put("downloadDir", downloadDir).put("port", port)
                    .put("upnp", useUpnp).put("uploadSlots", uploadSlots).put("uploadLimit", uploadLimit)
                    .put("sendToPhone", sendToPhone).put("keepOnPc", keepOnPc).toString(2)
            )
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
        }
    }

    /** XDG_MUSIC_DIR de ~/.config/user-dirs.dirs (p. ej. ~/Música), o ~/Music. */
    private fun xdgMusicDir(): File {
        val home = System.getProperty("user.home")
        val line = runCatching { File(home, ".config/user-dirs.dirs").readLines() }.getOrDefault(emptyList())
            .firstOrNull { it.startsWith("XDG_MUSIC_DIR=") }
        val value = line?.substringAfter('=')?.trim('"')?.replace("\$HOME", home)
        return value?.let { File(it) }?.takeIf { it.path != home } ?: File(home, "Music")
    }
}

/** Álbumes de Deezer para reconocerlos en los resultados (la API es pública: va directo desde el PC). */
object DeezerAlbums {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    /** Álbumes de Deezer para la búsqueda, en su orden de relevancia. */
    suspend fun searchAlbums(query: String): List<DzAlbum> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.deezer.com/search/album".toHttpUrl().newBuilder()
                .addQueryParameter("q", query).addQueryParameter("limit", "25").build()
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val data = JSONObject(resp.body!!.string()).optJSONArray("data") ?: return@use emptyList()
                (0 until data.length()).map { data.getJSONObject(it) }.map { o ->
                    DzAlbum(
                        id = o.optLong("id"),
                        title = o.optString("title"),
                        artist = o.optJSONObject("artist")?.optString("name").orEmpty(),
                        cover = o.optString("cover_big").ifBlank { o.optString("cover_medium") },
                        tracks = o.optInt("nb_tracks"),
                        type = o.optString("record_type"),
                        year = "",
                        owned = 0
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

}
