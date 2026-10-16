package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.telegram.DeezerTrack
import com.kevshupp.kevmusicplayer.data.telegram.DeezerTrackSearch
import com.kevshupp.kevmusicplayer.data.telegram.DownloadStatus
import com.kevshupp.kevmusicplayer.data.telegram.LibraryMatcher
import com.kevshupp.kevmusicplayer.data.telegram.OnDemandTracks
import com.kevshupp.kevmusicplayer.data.telegram.TelegramAuthState
import com.kevshupp.kevmusicplayer.data.telegram.TelegramMusicClient
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel

/** Resultados "en línea" del modo bajo demanda para una búsqueda: canciones de Deezer que no tienes. */
class OnDemandSearchState(
    val tracks: List<DeezerTrack>,
    val loading: Boolean,
    val statuses: Map<Long, DownloadStatus>
)

@Composable
fun rememberOnDemandSearch(query: String, librarySongs: List<AudioFile>): OnDemandSearchState {
    val context = LocalContext.current
    // Se vuelve a leer al cambiar la búsqueda, para notar el interruptor de Ajustes sin reiniciar
    val enabled = remember(query.isBlank()) { OnDemandTracks.isEnabled(context) }
    LaunchedEffect(enabled) { if (enabled) TelegramMusicClient.start(context) }
    val auth by TelegramMusicClient.authState.collectAsState()
    val bot by TelegramMusicClient.bot.collectAsState()
    val telegramEnabled by TelegramMusicClient.enabled.collectAsState()
    val ready = enabled && telegramEnabled && auth == TelegramAuthState.Ready && bot.isNotBlank()
    val statuses by OnDemandTracks.statuses.collectAsState()
    val matcher = remember(librarySongs) { LibraryMatcher(librarySongs) }
    var tracks by remember { mutableStateOf<List<DeezerTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query, ready, matcher) {
        tracks = emptyList()
        loading = false
        if (!ready || query.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        loading = true
        tracks = try {
            DeezerTrackSearch.search(query.trim(), 25).filterNot { matcher.hasTrack(it) }
        } catch (e: Exception) {
            emptyList()
        }
        loading = false
    }
    return OnDemandSearchState(tracks, loading, statuses)
}

/** Sección "En línea · bajo demanda" al final de una lista de resultados. */
fun LazyListScope.onDemandSection(
    state: OnDemandSearchState,
    limit: Int,
    viewModel: MediaBrowserViewModel?,
    getLocalized: (String, String) -> String
) {
    if (state.tracks.isEmpty() && !state.loading) return
    item(key = "ondemand_header") { SearchHeader(getLocalized("En línea · bajo demanda", "Online · on demand")) }
    if (state.loading) {
        item(key = "ondemand_loading") {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
    items(state.tracks.take(limit), key = { "ondemand_${it.id}" }) { track ->
        val context = LocalContext.current
        OnDemandSearchResultItem(
            track = track,
            status = state.statuses[track.id],
            getLocalized = getLocalized,
            onPlay = { viewModel?.playOnDemand(track) },
            onQueue = { next ->
                viewModel?.queueOnDemand(track, next)
                android.widget.Toast.makeText(
                    context,
                    if (next) getLocalized("Sonará a continuación", "Playing next") else getLocalized("Añadida a la cola", "Added to queue"),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

/** Fila de una canción de Deezer en modo bajo demanda: tocar la reproduce; los botones la ponen en cola. */
@Composable
private fun OnDemandSearchResultItem(
    track: DeezerTrack,
    status: DownloadStatus?,
    getLocalized: (String, String) -> String,
    onPlay: () -> Unit,
    onQueue: (next: Boolean) -> Unit
) {
    val subtitle = when (status) {
        DownloadStatus.Queued, DownloadStatus.Requesting -> getLocalized("Pidiendo al bot…", "Asking the bot…")
        is DownloadStatus.Downloading -> getLocalized("Descargando", "Downloading") + " ${(status.progress * 100).toInt()} %"
        is DownloadStatus.Done -> getLocalized("Lista · ", "Ready · ") + track.artist
        is DownloadStatus.Failed -> status.reason
        null -> "${track.artist} · ${track.album}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
            .clickable(onClick = onPlay)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            SubcomposeAsyncImage(
                model = track.coverSmall,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (status is DownloadStatus.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }
        // Sin tinte explícito heredaban un color oscuro y no se veían sobre el fondo
        IconButton(onClick = { onQueue(true) }, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Rounded.QueuePlayNext,
                contentDescription = getLocalized("Reproducir a continuación", "Play next"),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        IconButton(onClick = { onQueue(false) }, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.AutoMirrored.Rounded.PlaylistAdd,
                contentDescription = getLocalized("Añadir a la cola", "Add to queue"),
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
