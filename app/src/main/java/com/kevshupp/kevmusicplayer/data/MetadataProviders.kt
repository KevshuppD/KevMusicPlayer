package com.kevshupp.kevmusicplayer.data

import android.content.Context
import com.kevshupp.kevmusicplayer.BuildConfig
import com.kevshupp.kevmusicplayer.data.telegram.DeezerTrackSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * [deezerId] > 0 si viene de Deezer: el editor pide con él los colaboradores (`contributors`). [genre] y [genreId]
 * son los del álbum en Deezer (solo Deezer los da).
 */
data class MetadataResult(val title: String, val artist: String, val album: String, val deezerId: Long = 0, val genre: String = "", val genreId: Long = 0)

data class MbRecording(val title: String, val artist: String, val album: String, val lengthSec: Int, val year: String)

/** De dónde saca el editor de etiquetas título, artista y álbum (`settings_prefs/metadata_provider`). */
enum class MetadataProvider(val key: String, val label: String) {
    Deezer("deezer", "Deezer"),
    MusicBrainz("musicbrainz", "MusicBrainz"),
    LrcLib("lrclib", "LRCLIB");

    companion object {
        const val PREF = "metadata_provider"
        fun current(context: Context): MetadataProvider {
            val key = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).getString(PREF, null)
            return entries.firstOrNull { it.key == key } ?: Deezer
        }
    }
}

object MetadataSearch {
    /** [artistHint] es el artista del editor: si la búsqueda empieza por él, MusicBrainz puede buscar por campos. */
    suspend fun search(
        provider: MetadataProvider, query: String, artistHint: String, durationMs: Long = 0L,
        albumHints: List<String> = emptyList(), titleHint: String = ""
    ): List<MetadataResult> = when (provider) {
        MetadataProvider.Deezer -> {
            val found = DeezerTrackSearch.search(query, 15, includeUnavailable = true)
            val fromAlbum = runCatching { ownAlbumTracks(found, artistHint, albumHints, titleHint, durationMs) }.getOrDefault(emptyList())
            val ids = fromAlbum.mapTo(HashSet()) { it.id }
            deezerWithGenres(fromAlbum + found.filter { it.id !in ids })
        }
        MetadataProvider.MusicBrainz -> musicBrainz(query, artistHint, durationMs)
        MetadataProvider.LrcLib -> {
            val failed = java.util.concurrent.atomic.AtomicBoolean(false)
            val found = LyricsRepository.searchLyricsOptionsFromLrcLib("", query, failed)
            if (found.isEmpty() && failed.get()) throw IOException("LRCLIB no responde")
            found.map { MetadataResult(it.trackName, it.artistName, it.albumName) }
        }
    }

    /**
     * La búsqueda de Deezer agrupa la misma grabación y muestra una sola edición: "BTS Blood Sweat & Tears" solo
     * devuelve "You Never Walk Alone", nunca "Wings". Por eso se revisan también los discos del artista que tienes
     * ([albumHints]: el de la canción primero y los demás de tu biblioteca) y, si la canción está en alguno (mismo
     * título que algún resultado o que el tuyo, o misma duración ±2 s si tu título está en otro idioma), va primero.
     * Discografía y discos quedan en caché 7 días.
     */
    private suspend fun ownAlbumTracks(
        found: List<com.kevshupp.kevmusicplayer.data.telegram.DeezerTrack>,
        artistHint: String, albumHints: List<String>, titleHint: String, durationMs: Long
    ): List<com.kevshupp.kevmusicplayer.data.telegram.DeezerTrack> = kotlinx.coroutines.coroutineScope {
        val lm = com.kevshupp.kevmusicplayer.data.telegram.LibraryMatcher
        val wanted = albumHints.filterNot { LibraryNormalizer.isUnknown(it) }.map { lm.normalizeTitle(it) }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty() || artistHint.isBlank()) return@coroutineScope emptyList()
        val mainArtist = artistHint.split(Regex("\\s*(?:&|,|\\bfeat\\.?|\\bft\\.?)\\s*"), limit = 2).first().trim()
        val artistNorm = lm.normalize(mainArtist)
        val titles = (found.filter { lm.normalize(it.artist).contains(artistNorm) }.map { it.title } + titleHint)
            .map { lm.normalizeTitle(it) }.filter { it.isNotEmpty() }.toSet()
        val byDuration = durationMs > 0 && !com.kevshupp.kevmusicplayer.data.telegram.SongRepair.isLatin(titleHint)
        val durationSec = (durationMs / 1000).toInt()
        val artist = DeezerTrackSearch.findArtist(mainArtist) ?: return@coroutineScope emptyList()
        val discography = DeezerTrackSearch.artistAlbums(artist.id, artist.name, cached = true)
        // En el orden de [albumHints]; como mucho 8 discos para no tardar
        val albums = wanted.flatMap { w -> discography.filter { lm.normalizeTitle(it.title) == w } }.distinctBy { it.id }.take(8)
        albums.map { a ->
            async {
                runCatching { DeezerTrackSearch.albumTracks(a, cached = true) }.getOrDefault(emptyList()).map { it.copy(albumId = a.id) }.filter { t ->
                    lm.normalizeTitle(t.title) in titles || (byDuration && kotlin.math.abs(t.durationSec - durationSec) <= 2)
                }
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }

    /** El género es del álbum: se pide cada álbum una vez (en paralelo, con la caché de 7 días). Si falla, sin género. */
    private suspend fun deezerWithGenres(tracks: List<com.kevshupp.kevmusicplayer.data.telegram.DeezerTrack>): List<MetadataResult> = kotlinx.coroutines.coroutineScope {
        val genres = tracks.map { it.albumId }.filter { it > 0 }.distinct().map { id ->
            async {
                id to runCatching { DeezerTrackSearch.albumDetails(id).optJSONObject("genres")?.optJSONArray("data")?.optJSONObject(0) }.getOrNull()
            }
        }.awaitAll().toMap()
        tracks.map { t ->
            val g = genres[t.albumId]
            MetadataResult(t.title, t.artist, t.album, t.id, g?.optString("name").orEmpty(), g?.optLong("id") ?: 0L)
        }
    }

    /** Lucene: el texto libre devuelve sobre todo remixes y covers, así que se busca por título y artista si se puede. */
    internal fun musicBrainzQuery(query: String, artistHint: String): String {
        val q = query.trim()
        val artist = artistHint.trim()
        fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        if (artist.isNotEmpty() && q.length > artist.length && q.startsWith(artist, ignoreCase = true)) {
            return "recording:${quoted(q.substring(artist.length).trim())} AND artist:${quoted(artist)}"
        }
        return "recording:${quoted(q)}"
    }

    /**
     * Segundo intento con otro idioma: MusicBrainz guarda "농담", no "Joke" (ni como alias), y solo encuentra el título
     * original. Las canciones de ese artista con la misma duración (±3 s) lo hallan sin traducir nada.
     */
    internal fun musicBrainzDurationQuery(query: String, artistHint: String, durationMs: Long): String? {
        val artist = artistHint.trim()
        if (durationMs <= 0 || artist.isEmpty() || !query.trim().startsWith(artist, ignoreCase = true)) return null
        val quoted = "\"" + artist.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return "artist:$quoted AND dur:[${durationMs - 3000} TO ${durationMs + 3000}]"
    }

    internal fun parseMusicBrainz(json: String): List<MetadataResult> {
        val recordings = JSONObject(json).optJSONArray("recordings") ?: return emptyList()
        return (0 until recordings.length()).mapNotNull { recordings.optJSONObject(it) }.map { r ->
            val credits = r.optJSONArray("artist-credit")
            val artist = (0 until (credits?.length() ?: 0)).mapNotNull { credits?.optJSONObject(it) }
                .joinToString("") { it.optString("name") + it.optString("joinphrase") }
            MetadataResult(r.optString("title"), artist, r.optJSONArray("releases")?.optJSONObject(0)?.optString("title").orEmpty())
        }.distinct()
    }

    /** Grabaciones con duración y año, para el respaldo de Normalizar. */
    suspend fun musicBrainzRecordings(title: String, artist: String): List<MbRecording> =
        parseRecordings(musicBrainzBody(musicBrainzQuery("$artist $title", artist)))

    internal fun parseRecordings(json: String): List<MbRecording> {
        val recordings = JSONObject(json).optJSONArray("recordings") ?: return emptyList()
        return (0 until recordings.length()).mapNotNull { recordings.optJSONObject(it) }.map { r ->
            val credits = r.optJSONArray("artist-credit")
            val artist = (0 until (credits?.length() ?: 0)).mapNotNull { credits?.optJSONObject(it) }
                .joinToString("") { it.optString("name") + it.optString("joinphrase") }
            MbRecording(
                r.optString("title"), artist, r.optJSONArray("releases")?.optJSONObject(0)?.optString("title").orEmpty(),
                r.optInt("length") / 1000, r.optString("first-release-date").take(4)
            )
        }
    }

    /** ~1 consulta por segundo y User-Agent propio; responde 503 si está saturado, de ahí los reintentos. */
    private suspend fun musicBrainz(query: String, artistHint: String, durationMs: Long): List<MetadataResult> {
        val found = musicBrainzRequest(musicBrainzQuery(query, artistHint))
        if (found.isNotEmpty()) return found
        val byDuration = musicBrainzDurationQuery(query, artistHint, durationMs) ?: return found
        delay(1100) // límite de una consulta por segundo
        // La búsqueda también trae "sudo rm" o "RM Project": aquí solo sirve el mismo nombre
        return musicBrainzRequest(byDuration).filter { it.artist.trim().equals(artistHint.trim(), ignoreCase = true) }
    }

    private suspend fun musicBrainzRequest(lucene: String): List<MetadataResult> = parseMusicBrainz(musicBrainzBody(lucene))

    private suspend fun musicBrainzBody(lucene: String): String = withContext(Dispatchers.IO) {
        val url = "https://musicbrainz.org/ws/2/recording".toHttpUrl().newBuilder()
            .addQueryParameter("query", lucene)
            .addQueryParameter("limit", "15").addQueryParameter("fmt", "json").build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "KevMusicPlayer/${BuildConfig.VERSION_NAME} ( https://github.com/KevshuppD/KevMusicPlayer )")
            .header("Accept", "application/json").build()
        for (wait in listOf(0L, 2000L, 5000L)) {
            delay(wait)
            HttpClients.shared.newCall(request).execute().use { r ->
                if (r.isSuccessful) return@withContext r.body!!.string()
                if (r.code != 503) throw IOException("MusicBrainz respondió ${r.code}")
            }
        }
        throw IOException("MusicBrainz está saturado")
    }
}
