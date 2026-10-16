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
    suspend fun albumTracks(album: DeezerAlbum): List<DeezerTrack> {
        val json = getObject("$API/album/${album.id}")
        val cover = json.optString("cover_medium").ifBlank { album.cover }
        val coverXl = json.optString("cover_xl")
        val title = json.optString("title").ifBlank { album.title }
        val data = json.optJSONObject("tracks")?.optJSONArray("data") ?: JSONArray()
        return parseTracks(data).map { it.copy(album = title, coverSmall = cover, coverXl = coverXl.ifBlank { it.coverXl }) }
    }

    suspend fun artistTopTracks(artistId: Long, limit: Int = 25): List<DeezerTrack> =
        parseTracks(getData("$API/artist/$artistId/top", "limit" to "$limit"))

    suspend fun artistAlbums(artistId: Long, artistName: String, limit: Int = 100): List<DeezerAlbum> {
        val data = getData("$API/artist/$artistId/albums", "limit" to "$limit")
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

    private suspend fun getData(url: String, vararg params: Pair<String, String>): JSONArray =
        getObject(url, *params).optJSONArray("data") ?: JSONArray()

    private suspend fun getObject(url: String, vararg params: Pair<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val builder = url.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        HttpClients.shared.newCall(Request.Builder().url(builder.build()).build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Deezer HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: "{}")
            json.optJSONObject("error")?.let { throw java.io.IOException(it.optString("message", "Deezer")) }
            json
        }
    }
}
