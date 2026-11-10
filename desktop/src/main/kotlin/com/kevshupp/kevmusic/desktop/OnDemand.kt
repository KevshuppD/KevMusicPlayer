package com.kevshupp.kevmusic.desktop

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Bajo demanda del modo PC: el archivo de una canción del teléfono que el PC no tiene, por orden:
 * 1. **El teléfono conectado** (`/audio/<id>`, el archivo original).
 * 2. **Telegram del PC** ([TelegramPc], el mismo bot que la app): la canción en Deezer por título, artista y duración.
 * 3. **YouTube Music** ([YtMusic], yt-dlp): la primera con el mismo título y ±5 s.
 *
 * Se guarda en `<caché>/ondemand/` (máx. [MAX_CACHE_BYTES], se borran las usadas hace más tiempo) con el id del
 * teléfono, así una canción ya escuchada vuelve a sonar sin red.
 */
object OnDemand {
    private const val MAX_CACHE_BYTES = 1024L * 1024 * 1024
    private val dir = File(Platform.cacheDir, "ondemand")
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    // Una descarga por canción aunque se pida dos veces (la actual y la precarga)
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun resolve(client: RemoteClient, song: Song): Result<File> = locks.getOrPut(song.id) { Mutex() }.withLock {
        cached(song.id)?.let { it.setLastModified(System.currentTimeMillis()); return Result.success(it) }
        dir.mkdirs()
        val reasons = mutableListOf<String>()

        val fromPhone = File(dir, "${song.id}.audio")
        if (client.downloadFromPhone(song.id, fromPhone)) return Result.success(done(fromPhone))

        if (Settings.odTelegram && TelegramPc.ready) {
            runCatching {
                val dz = deezerTrack(song) ?: throw NoSuchElementException("no está en Deezer")
                val got = TelegramPc.request(dz.first, dz.second)
                val dest = File(dir, "${song.id}.${got.extension.ifBlank { "mp3" }}")
                got.copyTo(dest, overwrite = true)
                got.delete()
                return Result.success(done(dest))
            }.onFailure { reasons += "Telegram: ${it.message}" }
        } else if (Settings.odTelegram) reasons += "Telegram sin sesión en el PC"

        if (Settings.odYoutube) {
            runCatching {
                val q = "${song.artist.substringBefore(",").trim()} ${song.title}"
                val results = YtMusic.search(q, 5).take(5).toList()
                val pick = results.firstOrNull { YtMusic.sameText(it.title, song.title) && kotlin.math.abs(it.durationSec * 1000L - song.duration) <= 5000 }
                    ?: results.firstOrNull { YtMusic.sameText(it.title, song.title) }
                    ?: throw NoSuchElementException("no se encontró")
                val got = YtMusic.download(pick) {}
                val dest = File(dir, "${song.id}.m4a")
                got.copyTo(dest, overwrite = true)
                got.delete()
                return Result.success(done(dest))
            }.onFailure { reasons += "YouTube: ${it.message}" }
        }
        Result.failure(Exception(if (reasons.isEmpty()) "Conecta el teléfono o activa una fuente bajo demanda en Ajustes" else reasons.joinToString(" · ")))
    }

    private fun cached(id: Long): File? = dir.listFiles { f -> f.name.substringBefore('.') == id.toString() && !f.name.endsWith(".part") && f.length() > 0 }?.firstOrNull()

    private fun done(f: File): File {
        trim()
        return f
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES) break
            total -= f.length(); f.delete()
        }
    }

    fun clearCache() { dir.deleteRecursively() }

    /** (id de Deezer, duración en s) de la misma canción: título y artista iguales, la de duración más parecida. */
    private fun deezerTrack(song: Song): Pair<Long, Int>? {
        val artist = song.artist.substringBefore(",").trim()
        for (q in listOf("$artist ${song.title}", "artist:\"$artist\" track:\"${song.title}\"")) {
            val url = "https://api.deezer.com/search".toHttpUrl().newBuilder().addQueryParameter("q", q).addQueryParameter("limit", "15").build()
            val data = http.newCall(Request.Builder().url(url).build()).execute().use { JSONObject(it.body!!.string()).optJSONArray("data") } ?: continue
            val best = (0 until data.length()).map { data.getJSONObject(it) }
                .filter { o ->
                    YtMusic.sameText(o.optString("title_short").ifBlank { o.optString("title") }, song.title) ||
                        YtMusic.sameText(o.optString("title"), song.title)
                }
                .filter { o -> YtMusic.sameText(o.optJSONObject("artist")?.optString("name").orEmpty(), artist) }
                .minByOrNull { o -> kotlin.math.abs(o.optInt("duration") * 1000L - song.duration) }
            if (best != null && (song.duration <= 0 || kotlin.math.abs(best.optInt("duration") * 1000L - song.duration) <= 8000))
                return best.optLong("id") to best.optInt("duration")
        }
        return null
    }
}
