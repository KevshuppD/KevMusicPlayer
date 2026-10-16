package com.kevshupp.kevmusicplayer.data.telegram

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
    suspend fun findMatch(song: AudioFile): DeezerTrack? {
        val title = LibraryMatcher.normalizeTitle(song.title)
        val artist = LibraryMatcher.normalize(song.artist)
        if (title.isEmpty()) return null
        val queries = listOfNotNull(
            if (song.artist.isNotBlank()) "artist:\"${song.artist}\" track:\"${song.title}\"" else null,
            "${song.artist} ${song.title}".trim()
        )
        for (q in queries) {
            val candidates = DeezerTrackSearch.search(q, limit = 25).filter { t ->
                val tTitle = LibraryMatcher.normalizeTitle(t.title)
                val tArtist = LibraryMatcher.normalize(t.artist)
                // Título exacto: "Desesperado (Mix 2024)" no debe tomarse por "Desesperado" (otra versión)
                val titleOk = tTitle == title
                val artistOk = artist.isEmpty() || tArtist == artist || tArtist.contains(artist) || artist.contains(tArtist)
                titleOk && artistOk
            }
            if (candidates.isEmpty()) continue
            val durationSec = (song.duration / 1000).toInt()
            val album = LibraryMatcher.normalizeTitle(song.album)
            return candidates.minByOrNull { t ->
                val durationPenalty = if (durationSec > 0) abs(t.durationSec - durationSec) else 0
                val albumBonus = if (album.isNotEmpty() && LibraryMatcher.normalizeTitle(t.album) == album) -10 else 0
                val exactBonus = if (LibraryMatcher.normalizeTitle(t.title) == title) -5 else 0
                durationPenalty + albumBonus + exactBonus
            }
        }
        return null
    }
}
