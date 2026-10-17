package com.kevshupp.kevmusicplayer.data.telegram

import com.kevshupp.kevmusicplayer.data.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class DeezerTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val coverSmall: String,
    val coverXl: String,
    val link: String
)

data class DeezerAlbum(
    val id: Long,
    val title: String,
    val artist: String,
    val cover: String,
    val trackCount: Int,
    val recordType: String,
    val year: String = ""
)

data class DeezerArtist(
    val id: Long,
    val name: String,
    val picture: String,
    val albumCount: Int,
    val fans: Int
)

/** Búsqueda de canciones, álbumes y artistas en la API pública de Deezer (la misma fuente que usa el bot). */
object DeezerTrackSearch {
    private const val API = "https://api.deezer.com"
    private const val ARTIST_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** Carpeta de la caché en disco de los perfiles de artista; la asigna la Application al arrancar. */
    @Volatile
    var cacheDir: java.io.File? = null

    /** Artista de Deezer con ese nombre (normalizado), o null. Nunca toma "el primer resultado" sin más. */
    suspend fun findArtist(name: String): DeezerArtist? {
        if (name.isBlank()) return null
        val data = getData("$API/search/artist", "q" to name.trim(), "limit" to "10", cacheTtlMs = ARTIST_CACHE_TTL_MS)
        val wanted = LibraryMatcher.normalize(name)
        return (0 until data.length()).mapNotNull { i -> data.optJSONObject(i) }
            .filter { LibraryMatcher.normalize(it.optString("name")) == wanted }
            .maxByOrNull { it.optInt("nb_fan") }
            ?.let { a ->
                DeezerArtist(
                    id = a.optLong("id"),
                    name = a.optString("name"),
                    picture = a.optString("picture_medium"),
                    albumCount = a.optInt("nb_album"),
                    fans = a.optInt("nb_fan")
                )
            }
    }

    suspend fun search(query: String, limit: Int = 30): List<DeezerTrack> =
        if (query.isBlank()) emptyList()
        else getData("$API/search", "q" to query.trim(), "limit" to "$limit").let { parseTracks(it) }

    suspend fun searchAlbums(query: String, limit: Int = 30): List<DeezerAlbum> {
        if (query.isBlank()) return emptyList()
        val data = getData("$API/search/album", "q" to query.trim(), "limit" to "$limit")
        return (0 until data.length()).mapNotNull { i -> data.optJSONObject(i)?.let(::parseAlbum) }
    }

    suspend fun searchArtists(query: String, limit: Int = 20): List<DeezerArtist> {
        if (query.isBlank()) return emptyList()
        val data = getData("$API/search/artist", "q" to query.trim(), "limit" to "$limit")
        return (0 until data.length()).mapNotNull { i ->
            val a = data.optJSONObject(i) ?: return@mapNotNull null
            DeezerArtist(
                id = a.optLong("id"),
                name = a.optString("name"),
                picture = a.optString("picture_medium"),
                albumCount = a.optInt("nb_album"),
                fans = a.optInt("nb_fan")
            )
        }
    }

    /** Pistas de un álbum; las de `/album/{id}` no traen todos los datos del álbum, se completan con los del padre. */
    suspend fun albumTracks(album: DeezerAlbum, cached: Boolean = false): List<DeezerTrack> {
        val json = getObject("$API/album/${album.id}", cacheTtlMs = if (cached) ARTIST_CACHE_TTL_MS else 0)
        val cover = json.optString("cover_medium").ifBlank { album.cover }
        val coverXl = json.optString("cover_xl")
        val title = json.optString("title").ifBlank { album.title }
        val data = json.optJSONObject("tracks")?.optJSONArray("data") ?: JSONArray()
        return parseTracks(data).map { it.copy(album = title, coverSmall = cover, coverXl = coverXl.ifBlank { it.coverXl }) }
    }

    suspend fun artistTopTracks(artistId: Long, limit: Int = 25, cached: Boolean = false): List<DeezerTrack> =
        parseTracks(getData("$API/artist/$artistId/top", "limit" to "$limit", cacheTtlMs = if (cached) ARTIST_CACHE_TTL_MS else 0))

    suspend fun artistAlbums(artistId: Long, artistName: String, limit: Int = 100, cached: Boolean = false): List<DeezerAlbum> {
        val data = getData("$API/artist/$artistId/albums", "limit" to "$limit", cacheTtlMs = if (cached) ARTIST_CACHE_TTL_MS else 0)
        return (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.let(::parseAlbum)?.let { if (it.artist.isBlank()) it.copy(artist = artistName) else it }
        }
    }

    private fun parseAlbum(a: JSONObject) = DeezerAlbum(
        id = a.optLong("id"),
        title = a.optString("title"),
        artist = a.optJSONObject("artist")?.optString("name").orEmpty(),
        cover = a.optString("cover_medium"),
        trackCount = a.optInt("nb_tracks"),
        recordType = a.optString("record_type"),
        year = a.optString("release_date").take(4)
    )

    private fun parseTracks(data: JSONArray): List<DeezerTrack> = (0 until data.length()).mapNotNull { i ->
        val t = data.optJSONObject(i) ?: return@mapNotNull null
        if (t.optString("type", "track") != "track" || !t.optBoolean("readable", true)) return@mapNotNull null
        val album = t.optJSONObject("album")
        DeezerTrack(
            id = t.optLong("id"),
            title = t.optString("title"),
            artist = t.optJSONObject("artist")?.optString("name").orEmpty(),
            album = album?.optString("title").orEmpty(),
            durationSec = t.optInt("duration"),
            coverSmall = album?.optString("cover_medium").orEmpty(),
            coverXl = album?.optString("cover_xl").orEmpty(),
            link = t.optString("link").ifBlank { "https://www.deezer.com/track/${t.optLong("id")}" }
        )
    }

    private suspend fun getData(url: String, vararg params: Pair<String, String>, cacheTtlMs: Long = 0): JSONArray =
        getObject(url, *params, cacheTtlMs = cacheTtlMs).optJSONArray("data") ?: JSONArray()

    /**
     * Con [cacheTtlMs] > 0 la respuesta se guarda en `cacheDir/deezer_cache/` y se reutiliza durante ese tiempo;
     * si la red falla se usa la copia caducada antes que no mostrar nada.
     */
    private suspend fun getObject(url: String, vararg params: Pair<String, String>, cacheTtlMs: Long = 0): JSONObject = withContext(Dispatchers.IO) {
        val builder = url.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val httpUrl = builder.build()
        val cacheFile = if (cacheTtlMs > 0) cacheDir?.let { dir -> java.io.File(dir, "deezer_cache/${sha1(httpUrl.toString())}.json") } else null
        if (cacheFile != null && cacheFile.length() > 0 && System.currentTimeMillis() - cacheFile.lastModified() < cacheTtlMs) {
            runCatching { return@withContext JSONObject(cacheFile.readText()) }
        }
        try {
            HttpClients.shared.newCall(Request.Builder().url(httpUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("Deezer HTTP ${response.code}")
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                json.optJSONObject("error")?.let { throw java.io.IOException(it.optString("message", "Deezer")) }
                if (cacheFile != null) runCatching {
                    cacheFile.parentFile?.mkdirs()
                    val tmp = java.io.File(cacheFile.parentFile, cacheFile.name + ".tmp")
                    tmp.writeText(body)
                    if (!tmp.renameTo(cacheFile)) tmp.delete()
                }
                json
            }
        } catch (e: java.io.IOException) {
            if (cacheFile != null && cacheFile.length() > 0) {
                runCatching { return@withContext JSONObject(cacheFile.readText()) }
            }
            throw e
        }
    }

    private fun sha1(text: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
