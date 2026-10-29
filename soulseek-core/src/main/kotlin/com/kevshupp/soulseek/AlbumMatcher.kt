package com.kevshupp.soulseek

import java.text.Normalizer

/**
 * Reconoce álbumes en los resultados de Soulseek: quien lo usa busca el mismo texto en Deezer y aquí se
 * empareja cada carpeta con el álbum cuyo título y artista aparecen en su ruta.
 */
object AlbumMatcher {
    /** Lo que hace falta de un álbum de Deezer (DzAlbum en el PC, DeezerAlbum en el teléfono). */
    class Album<T>(val value: T, val title: String, val artist: String, val tracks: Int)

    /** Una canción de la biblioteca, para [LocalIndex]. */
    class LocalSong(val album: String, val artist: String, val id: Long)

    // Palabras que no distinguen un álbum: "WINGS (Deluxe Edition)" debe casar con "BTS - Wings [FLAC]"
    private val NOISE = setOf(
        "deluxe", "edition", "version", "remastered", "remaster", "expanded", "anniversary", "explicit", "clean",
        "bonus", "track", "tracks", "special", "the", "a", "an", "ep", "single", "album", "lp", "vol"
    )

    fun tokens(text: String): Set<String> =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    /** Palabras del título sin lo que va entre paréntesis o corchetes ni las de [NOISE]. */
    private fun coreTitle(title: String): Set<String> {
        val base = tokens(title.replace(Regex("[(\\[].*?[)\\]]"), " ")) - NOISE
        return base.ifEmpty { tokens(title) }
    }

    /** Canciones del teléfono agrupadas por el título "núcleo" de su álbum, para [ownedCount]. */
    class LocalIndex(songs: List<LocalSong>) {
        val byCore: Map<Set<String>, List<Pair<Set<String>, Long>>> =
            songs.filter { it.album.isNotBlank() }.groupBy({ coreTitle(it.album) }, { tokens(it.artist) to it.id })
    }

    /**
     * Cuántas canciones de ese álbum hay ya en el teléfono: mismo título núcleo ("WINGS (Deluxe)" = "Wings") y
     * artista que contiene al de Deezer o al revés (cubre "BTS, …" y artistas en otra escritura dentro del álbum).
     */
    fun ownedCount(title: String, artistName: String, index: LocalIndex): Int {
        val songs = index.byCore[coreTitle(title)] ?: return 0
        val artist = tokens(artistName)
        // Basta con que una pista sea del artista: en WINGS alguna figura como "방탄소년단"
        if (songs.none { (a, _) -> a.containsAll(artist) || (a.isNotEmpty() && artist.containsAll(a)) }) return 0
        return songs.size
    }

    class Prepared<T>(val album: Album<T>, val core: Set<String>, val artist: Set<String>)

    fun <T> prepare(albums: List<Album<T>>) = albums.map { Prepared(it, coreTitle(it.title), tokens(it.artist)) }

    /**
     * El álbum de [albums] que corresponde a la carpeta, o null. El título debe estar entero en la ruta y el
     * artista en la ruta o en los nombres de archivo; entre varios gana el título más largo y después el
     * número de pistas más parecido.
     */
    fun <T> match(albums: List<Prepared<T>>, folder: String, fileNames: List<String>, fileCount: Int): T? {
        if (albums.isEmpty()) return null
        val path = tokens(folder)
        var names: Set<String>? = null
        return albums.filter { a ->
            a.core.isNotEmpty() && path.containsAll(a.core) &&
                (path.containsAll(a.artist) || (names ?: fileNames.flatMap { tokens(it) }.toSet().also { names = it }).containsAll(a.artist))
        }.maxWithOrNull(
            compareBy<Prepared<T>> { it.core.size }.thenByDescending { kotlin.math.abs(it.album.tracks - fileCount) }
        )?.album?.value
    }
}
