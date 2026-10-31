package com.kevshupp.kevmusicplayer.data.telegram

import com.kevshupp.kevmusicplayer.data.LibraryNormalizer
import android.content.Context
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.playback.getPhysicalPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Reparación de canciones dañadas o cortadas: busca la misma canción en Deezer por su título y artista
 * y la vuelve a pedir al bot, reemplazando el archivo en su sitio.
 */
object SongRepair {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** El descargador está listo: sesión iniciada y bot configurado. */
    fun isAvailable(): Boolean =
        TelegramMusicClient.authState.value == TelegramAuthState.Ready && TelegramMusicClient.bot.value.isNotBlank()

    fun repair(context: Context, songs: List<AudioFile>) {
        val app = context.applicationContext
        songs.filterNot { TelegramDownloadQueue.repairInProgress(it.id) }.forEach { song ->
            TelegramDownloadQueue.markRepairSearching(song.id)
            scope.launch {
                val path = getPhysicalPath(app, song.id, song.uriString)
                if (path.isNullOrBlank()) {
                    TelegramDownloadQueue.markRepairFailed(song.id, "No se encontró el archivo")
                    return@launch
                }
                // Un error de red con Deezer no impide probar la búsqueda propia del bot
                val match = try { findMatch(song) } catch (e: Exception) { null }
                if (match != null) {
                    TelegramDownloadQueue.enqueueReplacement(app, song, match, path)
                } else {
                    // No está en Deezer (p. ej. álbumes exclusivos de otras plataformas): se busca en el bot
                    val query = "${song.artist} ${song.title}".trim()
                    TelegramDownloadQueue.enqueueReplacementByText(app, song, query, { results -> chooseBotResult(song, results) }, path)
                }
            }
        }
    }

    /**
     * Elige entre los botones del bot ("3. José José - Desesperado (Mix 2024)") el de mismo título y artista;
     * primero el título exacto, luego uno que contenga al otro. Null si ninguno es esta canción.
     */
    fun chooseBotResult(song: AudioFile, results: List<String>): Int? {
        val title = LibraryMatcher.normalizeTitle(song.title)
        val rawTitle = LibraryMatcher.normalize(song.title)
        val artist = LibraryMatcher.normalize(song.artist)
        if (title.isEmpty()) return null
        var best: Int? = null
        var bestScore = Int.MAX_VALUE
        results.forEachIndexed { i, text ->
            val clean = text.replace(Regex("^\\s*\\d+\\.\\s*"), "")
            val bArtist = LibraryMatcher.normalize(clean.substringBefore(" - ", ""))
            val bTitleRaw = clean.substringAfter(" - ", clean)
            val bTitle = LibraryMatcher.normalizeTitle(bTitleRaw)
            val artistOk = artist.isEmpty() || bArtist.isEmpty() || bArtist == artist || bArtist.contains(artist) || artist.contains(bArtist)
            if (!artistOk) return@forEachIndexed
            val score = when {
                LibraryMatcher.normalize(bTitleRaw) == rawTitle -> 0
                bTitle == title -> 1
                bTitle.length >= 4 && title.length >= 4 && (bTitle.contains(title) || title.contains(bTitle)) -> 2
                else -> return@forEachIndexed
            }
            if (score < bestScore) { bestScore = score; best = i }
        }
        return best
    }

    /**
     * Mismo título y artista (normalizados, ver [LibraryMatcher]); entre varias versiones, la de duración
     * más parecida a la de la biblioteca, preferentemente del mismo álbum.
     */
    /** Coincidencia para etiquetar; [doubt] dice por qué no es segura, o null si título y artista son los mismos. */
    class TagMatch(val track: DeezerTrack, val doubt: String?)

    suspend fun findMatch(song: AudioFile): DeezerTrack? = findMatchDetailed(song, false)?.track

    suspend fun findMatchForTags(song: AudioFile): TagMatch? = findMatchDetailed(song, true)

    private suspend fun findMatchDetailed(song: AudioFile, allowSameDuration: Boolean): TagMatch? {
        val songTitle = LibraryMatcher.fixMojibake(song.title)
        val songArtist = LibraryMatcher.fixMojibake(song.artist)
        val title = LibraryMatcher.normalizeTitle(songTitle)
        val artist = LibraryMatcher.normalize(songArtist)
        if (title.isEmpty()) return null
        val titleOnly = LibraryMatcher.normalizeTitle(songTitle).let { songTitle.replace(Regex("\\s*[(\\[].*$"), "").trim().ifEmpty { songTitle } }
        val albumText = LibraryMatcher.fixMojibake(song.album)
        val mainArtist = songArtist.split(Regex("\\s*(?:&|,|\\bfeat\\.?|\\bft\\.?|\\bx\\b)\\s*"), limit = 2).first().trim()
        // Texto libre primero: hoy `artist:"X" track:"Y"` responde vacío casi siempre y solo gasta consultas
        val queries = listOfNotNull(
            "$songArtist $songTitle".trim(),
            if (mainArtist != songArtist || titleOnly != songTitle) "$mainArtist $titleOnly".trim() else null,
            if (songArtist.isNotBlank()) "artist:\"$songArtist\" track:\"$songTitle\"" else null
        ).plus(
            // Al etiquetar, el disco ayuda: "STARGAZING" solo devolvía covers, y sus pistas sirven para títulos en coreano
            if (allowSameDuration && albumText.isNotBlank() && !LibraryNormalizer.isUnknown(albumText)) {
                listOf("$mainArtist $albumText $titleOnly", "$mainArtist $albumText", "$albumText $titleOnly")
            } else emptyList()
        ).distinct()
        val mainNorm = LibraryMatcher.normalize(mainArtist)
        var similar: DeezerTrack? = null
        val durationSec = (song.duration / 1000).toInt()
        val album = LibraryMatcher.normalizeTitle(LibraryMatcher.fixMojibake(song.album))
        var sameDuration: DeezerTrack? = null
        for (q in queries) {
            // Solo para etiquetar: reparar necesita que el bot pueda bajarla
            val found = DeezerTrackSearch.search(q, limit = 25, includeUnavailable = allowSameDuration)
            val candidates = found.filter { t ->
                val tTitle = LibraryMatcher.normalizeTitle(t.title)
                val tArtist = LibraryMatcher.normalize(t.artist)
                // Título exacto: "Desesperado (Mix 2024)" no debe tomarse por "Desesperado" (otra versión)
                val titleOk = tTitle == title
                val artistOk = artist.isEmpty() || tArtist == artist || tArtist.contains(artist) || artist.contains(tArtist)
                titleOk && artistOk
            }
            if (candidates.isNotEmpty()) {
                return candidates.minByOrNull { t ->
                    val durationPenalty = if (durationSec > 0) abs(t.durationSec - durationSec) else 0
                    val albumBonus = if (album.isNotEmpty() && LibraryMatcher.normalizeTitle(t.album) == album) -10 else 0
                    durationPenalty + albumBonus
                }?.let { TagMatch(it, null) }
            }
            if (allowSameDuration && album.isNotEmpty() && durationSec > 0) {
                // El artista con otro nombre (たかやん = takayan): mismo título, mismo disco y misma duración bastan
                found.firstOrNull { t ->
                    LibraryMatcher.normalizeTitle(t.title) == title && LibraryMatcher.normalizeTitle(t.album) == album &&
                        abs(t.durationSec - durationSec) <= 3
                }?.let { return TagMatch(it, "Artista con otro nombre") }
            }
            if (allowSameDuration && artist.isNotEmpty() && durationSec > 0) {
                // Invitados: "Luciérnagas" figura en Deezer como de Milo j, con Silvio Rodríguez entre sus colaboradores.
                // Los colaboradores solo vienen en /track, así que se piden para los pocos de igual título y duración
                found.filter { LibraryMatcher.normalizeTitle(it.title) == title && abs(it.durationSec - durationSec) <= 3 }
                    .sortedBy { abs(it.durationSec - durationSec) }
                    .take(3)
                    .firstOrNull { t ->
                        val people = DeezerTrackSearch.trackDetails(t.id).optJSONArray("contributors")
                        people != null && (0 until people.length()).any { i ->
                            LibraryMatcher.normalize(people.optJSONObject(i)?.optString("name").orEmpty()).let { it == artist || it == mainNorm }
                        }
                    }?.let { return TagMatch(it, "Figura como colaboración") }
            }
            if (allowSameDuration && similar == null && durationSec > 0 && isLatin(songTitle)) {
                // "Tear" = "Outro: Tear", "Dracula (JENNIE Remix)" = "Dracula (with JENNIE)": mismo artista y casi igual duración
                similar = found.filter { t ->
                    val tArtist = LibraryMatcher.normalize(t.artist)
                    (tArtist == artist || tArtist.contains(artist) || artist.contains(tArtist) || tArtist == mainNorm) &&
                        // En el mismo disco hay más margen: dos copias de una pista en vivo difieren en el silencio final
                        abs(t.durationSec - durationSec) <= (if (album.isNotEmpty() && LibraryMatcher.normalizeTitle(t.album) == album) 8 else 2) &&
                        isSimilarTitle(songTitle, t.title)
                }.minByOrNull { t ->
                    abs(t.durationSec - durationSec) + if (LibraryMatcher.normalizeTitle(t.album) == album) -10 else 0
                }
            }
            if (allowSameDuration && sameDuration == null && durationSec > 0 && !isLatin(songTitle)) {
                // 피 땀 눈물 = Blood Sweat & Tears: mismo artista y casi la misma duración, sin versiones en vivo
                sameDuration = closestByDuration(found.filter { t ->
                    val tArtist = LibraryMatcher.normalize(t.artist)
                    (tArtist == artist || tArtist.contains(artist) || artist.contains(tArtist)) && artist.isNotEmpty() && !isOtherVersion(t.title)
                }, durationSec)
            }
        }
        similar?.let { return TagMatch(it, "Título parecido, misma duración") }
        sameDuration?.let { return TagMatch(it, "Título en otro idioma, misma duración") }
        // Título en otro idioma en un disco que tampoco se encuentra por su nombre (Adoの歌ってみたアルバム = Ado's Utattemita
        // Album): entre las más escuchadas del artista, la de duración casi exacta. Si hay empate, ninguna
        if (allowSameDuration && durationSec > 0 && !isLatin(songTitle) && mainArtist.isNotBlank()) {
            val id = DeezerTrackSearch.findArtist(mainArtist)?.id ?: return null
            return closestByDuration(DeezerTrackSearch.artistTopTracks(id, limit = 100, cached = true).filterNot { isOtherVersion(it.title) }, durationSec, maxDiff = 1)
                ?.let { TagMatch(it, "Solo coincide la duración") }
        }
        return null
    }

    private fun closestByDuration(tracks: List<DeezerTrack>, durationSec: Int, maxDiff: Int = 2): DeezerTrack? {
        val near = tracks.filter { abs(it.durationSec - durationSec) <= maxDiff }.sortedBy { abs(it.durationSec - durationSec) }
        val best = near.firstOrNull() ?: return null
        val tied = near.count { abs(it.durationSec - durationSec) == abs(best.durationSec - durationSec) } > 1
        return best.takeUnless { tied }
    }

    private val otherVersion = Regex("instrumental|karaoke|\\blive\\b|acoustic|acústic|sped up|slowed|tribute|cover", RegexOption.IGNORE_CASE)

    private fun isOtherVersion(title: String) = otherVersion.containsMatchIn(title)

    /** Mismo título sin paréntesis, o uno dentro del otro; nunca una versión instrumental, en vivo o karaoke que el tuyo no sea. */
    internal fun isSimilarTitle(local: String, remote: String): Boolean {
        if (isOtherVersion(remote) && !isOtherVersion(local)) return false
        fun base(s: String) = LibraryMatcher.normalize(s.replace(Regex("\\s*[(\\[].*?[)\\]]"), ""))
        val a = base(local)
        val b = base(remote)
        return a.length >= 3 && b.length >= 3 && (a == b || a.contains(b) || b.contains(a))
    }

    internal fun isLatin(text: String) = text.any { it.isLetter() } &&
        text.filter { it.isLetter() }.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
}
