package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.LyricsRepository
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import com.kevshupp.kevmusicplayer.playback.managers.ClipEntry
import com.kevshupp.kevmusicplayer.ui.screens.loadAlbumArtBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Datos para editar un video de "Mis videos": su entrada y la canción con letra y carátula. */
private class EditingClip(
    val entry: ClipEntry,
    val song: AudioFile,
    val uri: String,
    val art: Bitmap?,
    val lyrics: String?,
    val translations: Map<Long, String>?
)

/**
 * "Mis videos" y su "Editar", para el reproductor y el widget. Al editar se cargan la letra y la carátula de la
 * canción del video (puede no ser la que suena). Desde el editor, "Mis videos" se abre encima y atrás vuelve a él.
 *
 * [onEditStarted] avisa a quien lo muestra (p. ej. para cerrar su propio creador antes de abrir el de la edición).
 */
@Composable
fun ClipHistoryHost(
    viewModel: MediaBrowserViewModel?,
    show: Boolean,
    onDismiss: () -> Unit,
    onEditStarted: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<EditingClip?>(null) }
    var historyOverEditor by remember { mutableStateOf(false) }

    fun startEdit(entry: ClipEntry) {
        scope.launch {
            val song = viewModel?.audioFileMap?.get(entry.songId.toString())
            if (song == null) {
                Toast.makeText(context, "La canción de este video ya no está en la biblioteca", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val stored = viewModel.ensureLyricsLoaded(song.id)
            val uri = "content://media/external/audio/media/${song.id}"
            val art = withContext(Dispatchers.IO) { loadAlbumArtBitmap(context, uri) }
            editing = EditingClip(entry, song, uri, art, stored.lyrics, LyricsRepository.deserializeTranslations(stored.translatedLyrics))
        }
    }

    if (show) {
        ClipHistoryDialog(
            onEdit = { entry -> onEditStarted(); onDismiss(); startEdit(entry) },
            onDismiss = onDismiss
        )
    }

    editing?.let { edit ->
        // La clave fuerza un editor nuevo al editar otro video desde el historial abierto encima
        key(edit.entry.id) {
            ShareClipDialog(
                songTitle = edit.song.title,
                artistName = edit.song.artist,
                albumTitle = edit.song.album,
                songDurationMs = edit.song.duration,
                currentPositionMs = edit.entry.startMs,
                audioUriString = edit.uri,
                albumArtBitmap = edit.art,
                lyrics = edit.lyrics,
                translatedLyrics = edit.translations,
                songId = edit.song.id,
                initial = edit.entry,
                onOpenHistory = { historyOverEditor = true },
                onDismiss = { editing = null }
            )
        }
    }

    if (historyOverEditor) {
        ClipHistoryDialog(
            onEdit = { entry -> historyOverEditor = false; editing = null; startEdit(entry) },
            onDismiss = { historyOverEditor = false }
        )
    }
}
