package com.kevshupp.kevmusic.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class MbArtist(val id: String, val name: String, val note: String)
data class MbGroup(val id: String, val title: String, val type: String, val date: String) {
    val year get() = date.take(4)
    val coverUrl get() = "https://coverartarchive.org/release-group/$id/front-500"
}
data class MbTrack(val position: Int, val title: String, val lengthSec: Int)
data class MbRelease(val id: String, val title: String, val date: String, val official: Boolean, val tracks: List<MbTrack>)

/**
 * Catálogo de discos que Deezer no tiene (mixtapes, por ejemplo). MusicBrainz admite ~1 consulta por segundo con
 * un User-Agent propio y responde 503 si se pasa o está saturado: una consulta a la vez, reintentos y 7 días de caché.
 */
object MusicBrainz {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    private val pace = Mutex()
    private var lastCall = 0L
    private val cacheDir = File(Platform.cacheDir, "musicbrainz")
    private const val CACHE_MS = 7 * 24 * 3600_000L
    private val userAgent = "KevMusicDesktop/${BuildInfo.VERSION} ( https://github.com/KevshuppD )"

    private suspend fun get(path: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val url = "https://musicbrainz.org/ws/2/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("fmt", "json")
        }.build().toString()
        val key = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val cached = File(cacheDir, "$key.json")
        if (cached.exists() && System.currentTimeMillis() - cached.lastModified() < CACHE_MS) return@withContext JSONObject(cached.readText())
        var failure: IOException? = null
        for (wait in listOf(0L, 2000L, 5000L, 10_000L, 20_000L)) {
            delay(wait)
            val text = pace.withLock {
                val gap = 1100 - (System.currentTimeMillis() - lastCall)
                if (gap > 0) delay(gap)
                lastCall = System.currentTimeMillis()
                try {
                    http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build()).execute().use { r ->
                        when {
                            r.isSuccessful -> r.body!!.string()
                            r.code == 503 -> { failure = IOException("MusicBrainz está saturado, inténtalo en un momento"); null }
                            else -> throw IOException("MusicBrainz respondió ${r.code}")
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    failure = IOException("MusicBrainz no responde"); null
                }
            }
            if (text != null) {
                cacheDir.mkdirs()
                cached.writeText(text)
                return@withContext JSONObject(text)
            }
        }
        // Mejor una copia caducada que nada
        if (cached.exists()) JSONObject(cached.readText()) else throw failure ?: IOException("MusicBrainz no responde")
    }

    private fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    suspend fun searchArtists(name: String): List<MbArtist> =
        parseArtists(get("artist", mapOf("query" to "artist:${quoted(name)}", "limit" to "10")))

    /** Discografía, de lo más antiguo a lo más nuevo (como mucho 300 lanzamientos). */
    suspend fun releaseGroups(artistId: String): List<MbGroup> {
        val all = ArrayList<MbGroup>()
        var offset = 0
        while (offset < 300) {
            val page = get("release-group", mapOf("artist" to artistId, "limit" to "100", "offset" to "$offset"))
            all += parseGroups(page)
            offset += 100
            if (offset >= page.optInt("release-group-count")) break
        }
        return all.sortedBy { it.date.ifBlank { "9" } }
    }

    suspend fun bestRelease(groupId: String): MbRelease? =
        chooseRelease(parseReleases(get("release", mapOf("release-group" to groupId, "inc" to "recordings", "limit" to "25"))))

    internal fun parseArtists(o: JSONObject) = o.optJSONArray("artists").objects().map {
        MbArtist(it.optString("id"), it.optString("name"), listOf(it.optString("disambiguation"), it.optString("country")).filter(String::isNotBlank).joinToString(" · "))
    }

    internal fun parseGroups(o: JSONObject) = o.optJSONArray("release-groups").objects().map { g ->
        val secondary = (0 until (g.optJSONArray("secondary-types")?.length() ?: 0)).map { g.getJSONArray("secondary-types").getString(it) }
        val type = (listOf(typeLabel(g.optString("primary-type"))) + secondary.map(::typeLabel)).filter(String::isNotBlank).joinToString(" · ")
        MbGroup(g.optString("id"), g.optString("title"), type, g.optString("first-release-date"))
    }

    private fun typeLabel(t: String) = when (t) {
        "Album" -> "Álbum"; "Single" -> "Sencillo"; "Mixtape/Street" -> "Mixtape"; "Compilation" -> "Recopilatorio"; "Live" -> "En vivo"; else -> t
    }

    internal fun parseReleases(o: JSONObject) = o.optJSONArray("releases").objects().map { r ->
        var position = 0
        val tracks = r.optJSONArray("media").objects().flatMap { m ->
            m.optJSONArray("tracks").objects().map { t ->
                position++
                val ms = if (t.isNull("length")) t.optJSONObject("recording")?.optInt("length") ?: 0 else t.optInt("length")
                MbTrack(position, t.optString("title"), ms / 1000)
            }
        }
        MbRelease(r.optString("id"), r.optString("title"), r.optString("date"), r.optString("status") == "Official", tracks)
    }

    /** Entre las ediciones, la de títulos más legibles: `冗談` no sirve para buscar "Joke" en SoundCloud. */
    internal fun chooseRelease(releases: List<MbRelease>): MbRelease? = releases.filter { it.tracks.isNotEmpty() }.maxWithOrNull(
        compareBy<MbRelease> { r -> r.tracks.count { isLatin(it.title) } * 100 / r.tracks.size }
            .thenBy { it.official }
            .thenBy { it.tracks.size }
            .thenByDescending { it.date.ifBlank { "9" } }
    )

    private fun isLatin(s: String) = s.none { Character.UnicodeScript.of(it.code).let { u -> u == Character.UnicodeScript.HAN || u == Character.UnicodeScript.HIRAGANA || u == Character.UnicodeScript.KATAKANA } }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
