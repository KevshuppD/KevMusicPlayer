package com.kevshupp.kevmusicplayer.data

import android.content.Context
import java.util.TimeZone
import kotlin.random.Random

/** Una mezcla automática de Inicio ("Hecho para ti"): título, descripción corta y canciones en orden. */
data class HomeMix(val id: String, val titleEs: String, val titleEn: String, val subtitleEs: String, val subtitleEn: String, val songs: List<AudioFile>)

/**
 * Mezclas automáticas de Inicio, a partir de la biblioteca con estadísticas: Mix de tus 3 artistas más escuchados,
 * de tus 2 géneros más escuchados, "Redescubre" (canciones que escuchabas y dejaste) y "Sin escuchar".
 *
 * Las mezclas se renuevan una vez al día: [forToday] guarda las del día y las reutiliza aunque cambien las
 * reproducciones o se cierre la app.
 */
object HomeMixes {
    private const val MIX_SIZE = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MIN_MIX_SONGS = 3
    private const val MAX_FILLER_PER_ARTIST = 4
    private const val CORE_GENRE_SHARE = 0.3

    /** Sube si cambia la forma de elegir las canciones, para que las mezclas guardadas se rehagan al actualizar. */
    private const val STORE_VERSION = 2
    private const val PREFS = "home_mixes_prefs"
    private const val KEY_STAMP = "stamp"
    private const val KEY_MIXES = "mixes"

    /** Géneros que abarcan estilos muy distintos: compartirlos no significa que dos artistas se parezcan. */
    private val broadGenres = setOf(
        "pop", "rock", "alternative", "alternativo", "dance", "electro", "electronic", "electronica", "electrónica",
        "latin", "latino", "latina", "international", "internacional", "world", "world music", "music", "música", "musica",
        "soundtrack", "soundtracks", "bandas sonoras", "films/games", "film scores", "asian music", "música asiática",
    )

    private fun genreKey(genre: String) = genre.trim().lowercase()

    /** Las mezclas de hoy: las guardadas si son de este día y siguen siendo válidas; si no, unas nuevas. */
    fun forToday(context: Context, songs: List<AudioFile>, now: Long = System.currentTimeMillis()): List<HomeMix> {
        if (songs.isEmpty()) return emptyList()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = "$STORE_VERSION:${localDay(now)}"
        if (prefs.getString(KEY_STAMP, null) == stamp) {
            resolve(prefs.getString(KEY_MIXES, "").orEmpty(), songs)?.let { return it }
        }
        return build(songs, now).also {
            prefs.edit().putString(KEY_STAMP, stamp).putString(KEY_MIXES, encode(it)).apply()
        }
    }

    /** Día según la hora del teléfono: la renovación ocurre a medianoche local, no a la de UTC. */
    internal fun localDay(now: Long) = (now + TimeZone.getDefault().getOffset(now)) / DAY_MS

    internal fun encode(mixes: List<HomeMix>): String = mixes.joinToString("\n") { m ->
        listOf(m.id, m.titleEs, m.titleEn, m.subtitleEs, m.subtitleEn, m.songs.joinToString(",") { it.id.toString() })
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
    }

    /**
     * Reconstruye las mezclas guardadas con los datos actuales de cada canción. Las que ya no existen se quitan.
     * Devuelve null si hay que rehacerlas: se perdió más de la mitad (MediaStore cambió los ids) o se guardaron
     * sin historial y ahora ya lo hay (escucha o restauración de reproducciones el mismo día).
     */
    internal fun resolve(stored: String, songs: List<AudioFile>): List<HomeMix>? {
        if (stored.isBlank()) return null
        val byId = songs.associateBy { it.id }
        var total = 0
        var found = 0
        val mixes = stored.lines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != 6) return null
            val ids = f[5].split(',').mapNotNull { it.toLongOrNull() }
            val present = ids.mapNotNull { byId[it] }
            total += ids.size
            found += present.size
            if (present.size < MIN_MIX_SONGS) null else HomeMix(f[0], f[1], f[2], f[3], f[4], present)
        }
        if (found * 2 < total) return null
        if (mixes.none { it.id.startsWith("artist:") } && songs.any { it.playCount > 0 }) return null
        return mixes
    }

    /**
     * Canciones de otros artistas parecidos para completar un Mix de artista. Los géneros núcleo del artista son los
     * de al menos un 30 % de sus canciones (una pista suelta de banda sonora no cuenta) y no valen los muy amplios.
     * El otro artista debe tener como género principal uno de esos: no basta con que una canción suya coincida.
     */
    private fun fillerFor(artist: String, byArtist: Map<String, List<AudioFile>>): List<AudioFile> {
        val own = byArtist.getValue(artist)
        val known = own.map { genreKey(it.genre) }.filterNot { LibraryNormalizer.isUnknown(it) }
        val core = known.groupingBy { it }.eachCount()
            .filter { (genre, n) -> n >= known.size * CORE_GENRE_SHARE && genre !in broadGenres }.keys
        if (core.isEmpty()) return emptyList()
        val similarArtists = byArtist.filter { (name, list) ->
            name != artist && mainGenre(list) in core
        }.keys
        val ownIds = own.mapTo(HashSet()) { it.id }
        return similarArtists.sorted()
            .flatMap { name ->
                byArtist.getValue(name)
                    .filter { it.id !in ownIds && genreKey(it.genre) in core }
                    .sortedWith(compareByDescending<AudioFile> { it.playCount }.thenBy { it.id })
                    .take(MAX_FILLER_PER_ARTIST)
            }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<AudioFile> { it.playCount }.thenBy { it.id })
    }

    private fun mainGenre(songs: List<AudioFile>): String? =
        songs.map { genreKey(it.genre) }.filterNot { LibraryNormalizer.isUnknown(it) }
            .groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key

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
            .sortedWith(compareByDescending<Map.Entry<String, MutableList<AudioFile>>> { (_, list) -> list.sumOf { it.playCount } }.thenBy { it.key })
            .take(3)
            .forEach { (artist, own) ->
                // Sobre todo sus canciones; de otros artistas, como mucho un tercio y nunca más que las suyas
                val ownCount = minOf(own.size, MIX_SIZE)
                val filler = fillerFor(artist, byArtist)
                    .take(MIX_SIZE / 3).take(ownCount).shuffled(random)
                val picked = (own.shuffled(random).take(MIX_SIZE - filler.size) + filler).shuffled(random)
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
