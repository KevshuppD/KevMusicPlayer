package com.kevshupp.kevmusic.desktop.soulseek

import com.kevshupp.kevmusic.desktop.DzAlbum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Reconoce álbumes en los resultados de Soulseek: busca el mismo texto en Deezer (desde el PC, la API es
 * pública) y empareja cada carpeta con el álbum cuyo título y artista aparecen en su ruta.
 */
object AlbumMatcher {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    // Palabras que no distinguen un álbum: "WINGS (Deluxe Edition)" debe casar con "BTS - Wings [FLAC]"
    private val NOISE = setOf(
        "deluxe", "edition", "version", "remastered", "remaster", "expanded", "anniversary", "explicit", "clean",
        "bonus", "track", "tracks", "special", "the", "a", "an", "ep", "single", "album", "lp", "vol"
    )

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

    fun tokens(text: String): Set<String> =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    /** Palabras del título sin lo que va entre paréntesis o corchetes ni las de [NOISE]. */
    private fun coreTitle(title: String): Set<String> {
        val base = tokens(title.replace(Regex("[(\\[].*?[)\\]]"), " ")) - NOISE
        return base.ifEmpty { tokens(title) }
    }

    /** Canciones del teléfono agrupadas por el título "núcleo" de su álbum, para [ownedCount]. */
    class LocalIndex(songs: List<com.kevshupp.kevmusic.desktop.Song>) {
        val byCore: Map<Set<String>, List<Pair<Set<String>, Long>>> =
            songs.filter { it.album.isNotBlank() }.groupBy({ coreTitle(it.album) }, { tokens(it.artist) to it.id })
    }

    /**
     * Cuántas canciones de ese álbum hay ya en el teléfono: mismo título núcleo ("WINGS (Deluxe)" = "Wings") y
     * artista que contiene al de Deezer o al revés (cubre "BTS, …" y artistas en otra escritura dentro del álbum).
     */
    fun ownedCount(album: DzAlbum, index: LocalIndex): Int {
        val songs = index.byCore[coreTitle(album.title)] ?: return 0
        val artist = tokens(album.artist)
        // Basta con que una pista sea del artista: en WINGS alguna figura como "방탄소년단"
        if (songs.none { (a, _) -> a.containsAll(artist) || (a.isNotEmpty() && artist.containsAll(a)) }) return 0
        return songs.size
    }

    class Prepared(val album: DzAlbum, val core: Set<String>, val artist: Set<String>)

    fun prepare(albums: List<DzAlbum>) = albums.map { Prepared(it, coreTitle(it.title), tokens(it.artist)) }

    /**
     * El álbum de [albums] que corresponde a la carpeta, o null. El título debe estar entero en la ruta y el
     * artista en la ruta o en los nombres de archivo; entre varios gana el título más largo y después el
     * número de pistas más parecido.
     */
    fun match(albums: List<Prepared>, folder: String, fileNames: List<String>, fileCount: Int): DzAlbum? {
        if (albums.isEmpty()) return null
        val path = tokens(folder)
        var names: Set<String>? = null
        return albums.filter { a ->
            a.core.isNotEmpty() && path.containsAll(a.core) &&
                (path.containsAll(a.artist) || (names ?: fileNames.flatMap { tokens(it) }.toSet().also { names = it }).containsAll(a.artist))
        }.maxWithOrNull(
            compareBy<Prepared> { it.core.size }.thenByDescending { kotlin.math.abs(it.album.tracks - fileCount) }
        )?.album
    }
}
