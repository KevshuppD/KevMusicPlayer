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

    private val broadGenres = setOf(
        "pop", "rock", "alternative", "alternativo", "dance", "electro", "electronic", "electronica", "electrónica",
        "latin", "latino", "latina", "international", "internacional", "world", "world music", "music", "música", "musica",
        "soundtrack", "soundtracks", "bandas sonoras", "films/games", "film scores", "asian music", "música asiática",
    )

    private fun genreKey(genre: String) = genre.trim().lowercase()

    private fun mainGenre(songs: List<Song>): String? =
        songs.map { genreKey(it.genre) }.filterNot(::isUnknown).groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key

    /** Igual que el teléfono: solo artistas cuyo género principal sea un género núcleo y no muy amplio del artista. */
    private fun fillerFor(artist: String, byArtist: Map<String, List<Song>>): List<Song> {
        val own = byArtist.getValue(artist)
        val known = own.map { genreKey(it.genre) }.filterNot(::isUnknown)
        val core = known.groupingBy { it }.eachCount()
            .filter { (genre, n) -> n >= known.size * 0.3 && genre !in broadGenres }.keys
        if (core.isEmpty()) return emptyList()
        val ownIds = own.mapTo(HashSet()) { it.id }
        val order = compareByDescending<Song> { it.plays }.thenBy { it.id }
        return byArtist.filter { (name, list) -> name != artist && mainGenre(list) in core }.keys.sorted()
            .flatMap { name ->
                byArtist.getValue(name).filter { it.id !in ownIds && genreKey(it.genre) in core }.sortedWith(order).take(4)
            }
            .distinctBy { it.id }.sortedWith(order)
    }

    private var cachedDay = -1L
    private var cached: List<HomeMix> = emptyList()

    /** Lo ya calculado hoy, para que volver a Inicio no deje la sección vacía un instante. */
    @Synchronized fun peek(): List<HomeMix> = cached

    /** Las mezclas se conservan todo el día en esta sesión; contar una reproducción no las cambia. */
    @Synchronized fun forToday(songs: List<Song>, now: Long = System.currentTimeMillis()): List<HomeMix> {
        val day = (now + java.util.TimeZone.getDefault().getOffset(now)) / DAY_MS
        if (day == cachedDay && cached.isNotEmpty()) {
            val byId = songs.associateBy { it.id }
            val total = cached.sumOf { it.songs.size }
            val updated = cached.map { m -> m.copy(songs = m.songs.mapNotNull { byId[it.id] }) }.filter { it.songs.size >= 3 }
            if (updated.sumOf { it.songs.size } * 2 >= total) { cached = updated; return updated }
        }
        cachedDay = day
        cached = build(songs, now)
        return cached
    }

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
                val filler = fillerFor(artist, byArtist)
                    .take(MIX_SIZE / 3).take(minOf(own.size, MIX_SIZE)).shuffled(random)
                val picked = (own.shuffled(random).take(MIX_SIZE - filler.size) + filler).shuffled(random)
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
