package com.kevshupp.kevmusicplayer.data.telegram

import com.kevshupp.kevmusicplayer.data.AudioFile
import java.text.Normalizer
import kotlin.math.abs

/**
 * Detecta qué resultados de Deezer ya están en la biblioteca aunque los nombres no coincidan letra por letra:
 * mayúsculas, tildes, puntuación ("Intro : X" / "Intro: X"), sufijos "(feat. …)" o "- Remastered".
 * Si el título no coincide (p. ej. el archivo tiene el título en coreano y Deezer en inglés) se acepta
 * una canción del mismo álbum con la misma duración (±4 s).
 */
class LibraryMatcher(songs: List<AudioFile>) {
    private val titleArtist = HashSet<String>()
    private val titlesByAlbum = HashMap<String, MutableSet<String>>()
    private val durationsByAlbum = HashMap<String, MutableList<Int>>()
    private val artistsByAlbum = HashMap<String, MutableSet<String>>()
    private val songsByAlbum = HashMap<String, Int>()

    init {
        for (song in songs) {
            val title = normalizeTitle(song.title)
            val artist = normalize(song.artist)
            val album = normalizeTitle(song.album)
            titleArtist += "$title|$artist"
            if (album.isNotEmpty()) {
                titlesByAlbum.getOrPut(album) { HashSet() } += title
                durationsByAlbum.getOrPut(album) { ArrayList() } += (song.duration / 1000).toInt()
                artistsByAlbum.getOrPut(album) { HashSet() } += artist
                songsByAlbum[album] = (songsByAlbum[album] ?: 0) + 1
            }
        }
    }

    fun hasTrack(track: DeezerTrack): Boolean {
        val title = normalizeTitle(track.title)
        if ("$title|${normalize(track.artist)}" in titleArtist) return true
        val album = normalizeTitle(track.album)
        if (album.isEmpty() || !albumBelongsTo(album, track.artist)) return false
        if (titlesByAlbum[album]?.contains(title) == true) return true
        return track.durationSec > 0 && durationsByAlbum[album]?.any { abs(it - track.durationSec) <= 4 } == true
    }

    /** Canciones de la biblioteca en un álbum con ese nombre y artista (0 si no lo tienes). */
    fun albumSongCount(album: DeezerAlbum): Int {
        val key = normalizeTitle(album.title)
        return if (albumBelongsTo(key, album.artist)) songsByAlbum[key] ?: 0 else 0
    }

    /**
     * El álbum local es del mismo artista. Basta con que una de sus canciones lo tenga
     * (en WINGS una pista figura como "방탄소년단" y el resto como "BTS").
     */
    private fun albumBelongsTo(albumKey: String, artist: String): Boolean {
        val artists = artistsByAlbum[albumKey] ?: return false
        val wanted = normalize(artist)
        return wanted.isEmpty() || artists.any { it == wanted || it.contains(wanted) || wanted.contains(it) }
    }

    companion object {
        private val marks = Regex("\\p{Mn}+")
        private val nonAlnum = Regex("[^\\p{L}\\p{N}]+")
        private val extras = Regex(
            "\\s*[(\\[]\\s*(feat|ft|with|remaster|remastered|deluxe|explicit|clean|radio edit|bonus|version|live|en vivo)\\b[^)\\]]*[)\\]]" +
                "|\\s+-\\s+(remaster|remastered|radio edit|live|en vivo|bonus)\\b.*$" +
                "|\\s+(feat|ft)\\.?\\s+.*$",
            RegexOption.IGNORE_CASE
        )

        fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(marks, "").replace(nonAlnum, "")

        fun normalizeTitle(text: String): String = normalize(text.replace(extras, ""))
    }
}
