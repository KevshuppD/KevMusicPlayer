package com.kevshupp.kevmusicplayer.data

import kotlin.random.Random

/** Una mezcla automática de Inicio ("Hecho para ti"): título, descripción corta y canciones en orden. */
data class HomeMix(val id: String, val titleEs: String, val titleEn: String, val subtitleEs: String, val subtitleEn: String, val songs: List<AudioFile>)

/**
 * Mezclas automáticas de Inicio, a partir de la biblioteca con estadísticas: Mix de tus 3 artistas más escuchados,
 * de tus 2 géneros más escuchados, "Redescubre" (canciones que escuchabas y dejaste) y "Sin escuchar".
 *
 * El orden cambia una vez al día (semilla = día), no en cada recomposición: abrir Inicio dos veces seguidas
 * muestra lo mismo.
 */
object HomeMixes {
    private const val MIX_SIZE = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun build(songs: List<AudioFile>, now: Long = System.currentTimeMillis()): List<HomeMix> {
        if (songs.isEmpty()) return emptyList()
        val random = Random(now / DAY_MS)
        val splitter = ArtistSplitter(songs.map { it.artist })
        val mixes = mutableListOf<HomeMix>()

        // Artistas: reproducciones sumadas de todas sus canciones (también colaboraciones)
        val byArtist = HashMap<String, MutableList<AudioFile>>()
        songs.forEach { s -> splitter.split(s.artist).forEach { a -> if (a.isNotBlank()) byArtist.getOrPut(a) { mutableListOf() } += s } }
        byArtist.entries
            .filter { (_, list) -> list.size >= 3 && list.sumOf { it.playCount } > 0 }
            .sortedByDescending { (_, list) -> list.sumOf { it.playCount } }
            .take(3)
            .forEach { (artist, own) ->
                // Sus canciones y, para completar, de sus géneros (las más escuchadas primero)
                val genres = own.map { it.genre }.filterNot { LibraryNormalizer.isUnknown(it) }.toSet()
                val ownIds = own.mapTo(HashSet()) { it.id }
                val similar = songs.filter { it.id !in ownIds && it.genre in genres }
                    .sortedByDescending { it.playCount }.take(MIX_SIZE).shuffled(random)
                val picked = (own.shuffled(random).take(MIX_SIZE * 2 / 3) + similar).take(MIX_SIZE).shuffled(random)
                mixes += HomeMix(
                    "artist:$artist", "Mix de $artist", "$artist Mix",
                    "$artist y más", "$artist and more", picked
                )
            }

        // Géneros más escuchados
        songs.filterNot { LibraryNormalizer.isUnknown(it.genre) }
            .groupBy { it.genre.trim() }
            .filter { (_, list) -> list.size >= 8 }
            .entries.sortedByDescending { (_, list) -> list.sumOf { it.playCount } }
            .take(2)
            .forEach { (genre, list) ->
                mixes += HomeMix(
                    "genre:$genre", "Mix $genre", "$genre Mix",
                    "Lo mejor de $genre", "The best of $genre",
                    list.sortedByDescending { it.playCount }.take(MIX_SIZE * 2).shuffled(random).take(MIX_SIZE)
                )
            }

        // Redescubre: las escuchabas (2+ veces) pero no en el último mes
        val monthAgo = now - 30 * DAY_MS
        songs.filter { it.playCount >= 2 && it.lastPlayed in 1 until monthAgo }
            .sortedByDescending { it.playCount }.take(MIX_SIZE * 2).shuffled(random).take(MIX_SIZE)
            .takeIf { it.size >= 5 }
            ?.let { mixes += HomeMix("rediscover", "Redescubre", "Rediscover", "Hace tiempo que no suenan", "Haven't played in a while", it) }

        // Sin escuchar nunca
        songs.filter { it.playCount == 0 }.shuffled(random).take(MIX_SIZE)
            .takeIf { it.size >= 5 }
            ?.let { mixes += HomeMix("unplayed", "Sin escuchar", "Never played", "Canciones que aún no suenan", "Songs you haven't played yet", it) }

        return mixes
    }
}
