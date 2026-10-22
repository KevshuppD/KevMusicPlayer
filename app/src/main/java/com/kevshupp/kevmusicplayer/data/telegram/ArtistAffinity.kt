package com.kevshupp.kevmusicplayer.data.telegram

import com.kevshupp.kevmusicplayer.data.AudioFile

/**
 * Ordena resultados de Deezer poniendo primero a los artistas que ya escuchas, luego a los que tienes en la
 * biblioteca sin escuchar y al final al resto. Dentro de cada grupo se respeta el orden de relevancia de Deezer.
 * Los artistas de varias voces ("A, B", "A feat. B", "A & B") cuentan para cada uno.
 */
class ArtistAffinity(songs: List<AudioFile>) {
    private val plays = HashMap<String, Int>()

    init {
        for (song in songs) {
            for (name in split(song.artist)) {
                plays[name] = (plays[name] ?: 0) + song.playCount
            }
        }
    }

    /** 0 = escuchado, 1 = en la biblioteca sin escuchar, 2 = desconocido. */
    fun tier(artist: String): Int {
        val counts = split(artist).mapNotNull { plays[it] }
        return when {
            counts.any { it > 0 } -> 0
            counts.isNotEmpty() -> 1
            else -> 2
        }
    }

    fun <T> rank(items: List<T>, artistOf: (T) -> String): List<T> =
        if (plays.isEmpty()) items else items.sortedBy { tier(artistOf(it)) }

    companion object {
        private val separators = Regex("\\s*(,|;|&|/|\\bfeat\\.?|\\bft\\.?|\\bx\\b|\\bwith\\b|\\by\\b)\\s*", RegexOption.IGNORE_CASE)

        fun split(artist: String): List<String> =
            (listOf(artist) + artist.split(separators))
                .map { LibraryMatcher.normalize(it) }
                .filter { it.isNotEmpty() }
                .distinct()
    }
}
