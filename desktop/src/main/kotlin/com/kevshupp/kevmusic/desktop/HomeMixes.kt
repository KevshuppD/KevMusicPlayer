package com.kevshupp.kevmusic.desktop

import com.kevshupp.kevmusic.desktop.ui.splitArtists
import kotlin.random.Random

/** Una mezcla de "Hecho para ti": título, descripción y canciones en orden. */
data class HomeMix(val id: String, val title: String, val subtitle: String, val songs: List<Song>)

/**
 * Las mismas mezclas que el Inicio del teléfono (`data/HomeMixes.kt`): Mix de tus 3 artistas más escuchados, de tus
 * 2 géneros más escuchados, "Redescubre" y "Sin escuchar". El orden cambia una vez al día (semilla = día).
 */
object HomeMixes {
    private const val MIX_SIZE = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val unknownGenres = setOf("", "unknown", "unknown genre", "<unknown>", "desconocido", "other", "otro")

    private fun isUnknown(genre: String) = genre.trim().lowercase() in unknownGenres || genre.trim().matches(Regex("\\(?\\d+\\)?"))

    fun build(songs: List<Song>, now: Long = System.currentTimeMillis()): List<HomeMix> {
        if (songs.isEmpty()) return emptyList()
        val random = Random(now / DAY_MS)
        val mixes = mutableListOf<HomeMix>()

        val byArtist = HashMap<String, MutableList<Song>>()
        songs.forEach { s -> splitArtists(s.artist).forEach { a -> if (a.isNotBlank()) byArtist.getOrPut(a) { mutableListOf() } += s } }
        byArtist.entries
            .filter { (_, list) -> list.size >= 3 && list.sumOf { it.plays } > 0 }
            .sortedByDescending { (_, list) -> list.sumOf { it.plays } }
            .take(3)
            .forEach { (artist, own) ->
                val genres = own.map { it.genre }.filterNot(::isUnknown).toSet()
                val ownIds = own.mapTo(HashSet()) { it.id }
                val similar = songs.filter { it.id !in ownIds && it.genre in genres }
                    .sortedByDescending { it.plays }.take(MIX_SIZE).shuffled(random)
                val picked = (own.shuffled(random).take(MIX_SIZE * 2 / 3) + similar).take(MIX_SIZE).shuffled(random)
                mixes += HomeMix("artist:$artist", "Mix de $artist", "$artist y más", picked)
            }

        songs.filterNot { isUnknown(it.genre) }
            .groupBy { it.genre.trim() }
            .filter { (_, list) -> list.size >= 8 }
            .entries.sortedByDescending { (_, list) -> list.sumOf { it.plays } }
            .take(2)
            .forEach { (genre, list) ->
                mixes += HomeMix(
                    "genre:$genre", "Mix $genre", "Lo mejor de $genre",
                    list.sortedByDescending { it.plays }.take(MIX_SIZE * 2).shuffled(random).take(MIX_SIZE)
                )
            }

        val monthAgo = now - 30 * DAY_MS
        songs.filter { it.plays >= 2 && it.lastPlayed in 1 until monthAgo }
            .sortedByDescending { it.plays }.take(MIX_SIZE * 2).shuffled(random).take(MIX_SIZE)
            .takeIf { it.size >= 5 }
            ?.let { mixes += HomeMix("rediscover", "Redescubre", "Hace tiempo que no suenan", it) }

        songs.filter { it.plays == 0 }.shuffled(random).take(MIX_SIZE)
            .takeIf { it.size >= 5 }
            ?.let { mixes += HomeMix("unplayed", "Sin escuchar", "Canciones que aún no suenan", it) }

        return mixes
    }
}
