package com.kevshupp.kevmusic.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Carátulas y letras cuando el teléfono no las puede dar (sin conexión) o la canción del PC no las trae.
 * Letra: nube (`users/<uid>/songs/<docId>`, la que sincroniza el teléfono) y si no LrcLib.
 * Carátula: la incrustada en el archivo bajo demanda ya descargado y si no la del álbum en Deezer.
 * Lo encontrado (y la ausencia, por 7 días) se guarda en `<caché>/extras/`.
 */
object OfflineExtras {
    private val dir = File(Platform.cacheDir, "extras")
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private const val MISS_TTL = 7L * 24 * 3600 * 1000

    private fun key(prefix: String, vararg parts: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(parts.joinToString("|") { it.lowercase().trim() }.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        return File(dir, "${prefix}_$h")
    }

    /** true si hay una ausencia reciente guardada (archivo vacío); false si hay que buscar. */
    private fun recentMiss(f: File) = f.isFile && f.length() == 0L && System.currentTimeMillis() - f.lastModified() < MISS_TTL

    // ---------- Letras ----------

    /** (letra, traducción JSON) o null. */
    suspend fun lyrics(song: Song): Pair<String, String>? = withContext(Dispatchers.IO) {
        val f = key("lyr", song.artist, song.title)
        if (f.isFile && f.length() > 0) runCatching {
            val j = JSONObject(f.readText()); return@withContext j.getString("l") to j.optString("t")
        }
        if (recentMiss(f)) return@withContext null
        val found = runCatching { fromCloud(song) }.getOrNull() ?: runCatching { fromLrcLib(song) }.getOrNull()
        runCatching {
            dir.mkdirs()
            f.writeText(found?.let { JSONObject().put("l", it.first).put("t", it.second).toString() } ?: "")
        }
        found
    }

    private suspend fun fromCloud(song: Song): Pair<String, String>? {
        val k = CloudSession.songKey(song.artist, song.title).ifBlank { return null }
        val fields = CloudSession.getDocument("songs/" + CloudSession.docId(k))?.optJSONObject("fields") ?: return null
        val l = fields.optJSONObject("lyrics")?.optString("stringValue").orEmpty()
        if (l.isBlank()) return null
        return l to fields.optJSONObject("translatedLyrics")?.optString("stringValue").orEmpty()
    }

    private fun fromLrcLib(song: Song): Pair<String, String>? {
        val b = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", song.artist.substringBefore(",").trim())
            .addQueryParameter("track_name", song.title)
        if (song.album.isNotBlank()) b.addQueryParameter("album_name", song.album)
        if (song.duration > 0) b.addQueryParameter("duration", (song.duration / 1000).toString())
        val req = Request.Builder().url(b.build()).header("User-Agent", "KevMusic Desktop").build()
        val j = http.newCall(req).execute().use { r -> if (!r.isSuccessful) return null; JSONObject(r.body!!.string()) }
        val l = j.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            ?: j.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return l to ""
    }

    // ---------- Carátulas ----------

    /** Bytes de la carátula (JPEG/PNG) o null. */
    suspend fun cover(song: Song): ByteArray? = withContext(Dispatchers.IO) {
        // 1) El archivo bajo demanda ya descargado trae la suya
        if (song.id > 0) OnDemand.cached(song.id)?.let { file ->
            runCatching { org.jaudiotagger.audio.AudioFileIO.read(file).tag?.firstArtwork?.binaryData }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        }
        // 2) Deezer, por artista y álbum (o título)
        val f = key("art2", song.artist, song.album.ifBlank { song.title })
        if (f.isFile && f.length() > 0) return@withContext f.readBytes()
        if (recentMiss(f)) return@withContext null
        val bytes = runCatching { fromDeezer(song) }.getOrNull()
        runCatching { dir.mkdirs(); f.writeBytes(bytes ?: ByteArray(0)) }
        bytes
    }

    private val featPart = Regex("""\s*[(\[]?\s*(feat|ft|with)\.?\s.*$""", RegexOption.IGNORE_CASE)

    /** Búsqueda simple de Deezer: la avanzada (`artist:"…" album:"…"`) no encuentra títulos con paréntesis. */
    private fun search(q: String): List<JSONObject> {
        val url = "https://api.deezer.com/search".toHttpUrl().newBuilder().addQueryParameter("q", q).addQueryParameter("limit", "25").build()
        val data = http.newCall(Request.Builder().url(url).build()).execute().use { JSONObject(it.body!!.string()).optJSONArray("data") }
            ?: return emptyList()
        return (0 until data.length()).map { data.getJSONObject(it) }
    }

    private fun fromDeezer(song: Song): ByteArray? {
        val artist = song.artist.substringBefore(",").replace(featPart, "").trim()
        val album = song.album.takeIf { it.isNotBlank() && !it.equals("<unknown>", true) }
        fun sameArtist(o: JSONObject) = YtMusic.sameText(o.optJSONObject("artist")?.optString("name").orEmpty(), artist)
        fun albumOf(o: JSONObject) = o.optJSONObject("album")?.optString("title").orEmpty()
        fun titleOf(o: JSONObject) = o.optString("title_short").ifBlank { o.optString("title") }
        val best = album?.let { a -> search("$artist ${a.replace(featPart, "")}").firstOrNull { sameArtist(it) && YtMusic.sameText(albumOf(it), a) } }
            ?: search("$artist ${song.title.replace(featPart, "")}").let { items ->
                items.firstOrNull { sameArtist(it) && YtMusic.sameText(titleOf(it), song.title) && album != null && YtMusic.sameText(albumOf(it), album) }
                    ?: items.firstOrNull { sameArtist(it) && YtMusic.sameText(titleOf(it), song.title) }
            }
            ?: return null
        val cover = best.optJSONObject("album")?.optString("cover_xl")?.takeIf { it.startsWith("http") } ?: return null
        return http.newCall(Request.Builder().url(cover).build()).execute().use { r -> if (r.isSuccessful) r.body!!.bytes() else null }
    }
}
