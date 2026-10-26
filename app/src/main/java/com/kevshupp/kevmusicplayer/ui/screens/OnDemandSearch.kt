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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Refresh
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
import com.kevshupp.kevmusicplayer.data.telegram.TelegramDownloadQueue
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
    // Se vuelve a leer al cambiar la búsqueda, para notar el interruptor de Ajustes sin reiniciar
    val ready = rememberOnDemandReady(query.isBlank())
    val statuses by OnDemandTracks.statuses.collectAsState()
    val matcher = rememberLibraryMatcher(librarySongs)
    val affinity = remember(matcher) { com.kevshupp.kevmusicplayer.data.telegram.ArtistAffinity(librarySongs.toList()) }
    var tracks by remember { mutableStateOf<List<DeezerTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query, ready, matcher) {
        tracks = emptyList()
        loading = false
        if (!ready || query.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        loading = true
        tracks = try {
            affinity.rank(DeezerTrackSearch.search(query.trim(), 25).filterNot { matcher.hasTrack(it) }) { it.artist }
        } catch (e: Exception) {
            emptyList()
        }
        loading = false
    }
    return OnDemandSearchState(tracks, loading, statuses)
}

/** El modo bajo demanda está activo y Telegram listo; el interruptor de Ajustes se vuelve a leer cuando cambia [key]. */
@Composable
fun rememberOnDemandReady(key: Any?): Boolean {
    val context = LocalContext.current
    val enabled = remember(key) { OnDemandTracks.isEnabled(context) }
    LaunchedEffect(enabled) { if (enabled) TelegramMusicClient.start(context) }
    val auth by TelegramMusicClient.authState.collectAsState()
    val bot by TelegramMusicClient.bot.collectAsState()
    val telegramEnabled by TelegramMusicClient.enabled.collectAsState()
    return enabled && telegramEnabled && auth == TelegramAuthState.Ready && bot.isNotBlank()
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

/** Dónde está una canción bajo demanda en el teléfono: biblioteca, caché temporal o descargándose. */
internal class OnDemandOffline(val inLibrary: Boolean, val cached: Boolean, val libraryDownload: DownloadStatus?)

@Composable
internal fun rememberOnDemandOffline(track: DeezerTrack): OnDemandOffline {
    val context = LocalContext.current
    val onDemand by OnDemandTracks.statuses.collectAsState()
    val downloads by TelegramDownloadQueue.statuses.collectAsState()
    val od = onDemand[track.id]
    val dl = downloads[track.id]
    return remember(track.id, od, dl) {
        OnDemandOffline(
            inLibrary = OnDemandTracks.localIdFor(context, track.id) != null || dl is DownloadStatus.Done,
            cached = OnDemandTracks.isCached(context, track.id),
            libraryDownload = dl
        )
    }
}

/**
 * Fila de una canción de Deezer en modo bajo demanda: tocar la reproduce; el botón la pone a continuación y
 * el menú de tres puntos la añade a la cola o la descarga a la biblioteca. El icono indica si ya está
 * en la biblioteca (descargada), en la caché temporal o solo en línea.
 */
@Composable
internal fun OnDemandSearchResultItem(
    track: DeezerTrack,
    status: DownloadStatus?,
    getLocalized: (String, String) -> String,
    onPlay: () -> Unit,
    onQueue: (next: Boolean) -> Unit
) {
    val context = LocalContext.current
    val offline = rememberOnDemandOffline(track)
    val dl = offline.libraryDownload
    val subtitle = when {
        dl == DownloadStatus.Queued || dl == DownloadStatus.Requesting -> getLocalized("Descarga en espera…", "Download queued…")
        dl is DownloadStatus.Downloading -> getLocalized("Descargando", "Downloading") + " ${(dl.progress * 100).toInt()} %"
        offline.inLibrary -> getLocalized("En tu biblioteca · ", "In your library · ") + track.artist
        status == DownloadStatus.Queued || status == DownloadStatus.Requesting -> getLocalized("Pidiendo al bot…", "Asking the bot…")
        status is DownloadStatus.Downloading -> getLocalized("Cargando", "Loading") + " ${(status.progress * 100).toInt()} %"
        offline.cached -> getLocalized("En caché · ", "Cached · ") + track.artist
        dl is DownloadStatus.Failed -> dl.reason
        status is DownloadStatus.Failed -> status.reason
        else -> "${track.artist} · ${track.album}"
    }
    val failed = (dl is DownloadStatus.Failed || status is DownloadStatus.Failed) && !offline.inLibrary && !offline.cached
    val stateIcon = when {
        offline.inLibrary -> Icons.Rounded.DownloadDone
        offline.cached -> Icons.Rounded.OfflinePin
        else -> Icons.Rounded.Cloud
    }
    var menuOpen by remember { mutableStateOf(false) }
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
                Icon(stateIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }
        // Descarga fallida: el reintento queda a la vista, sin tener que abrir el menú
        if (dl is DownloadStatus.Failed && !offline.inLibrary) {
            IconButton(onClick = { OnDemandTracks.saveToLibrary(context, track) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = getLocalized("Reintentar descarga", "Retry download"),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
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
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = getLocalized("Más opciones", "More options"),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    modifier = Modifier.size(22.dp)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(getLocalized("Añadir a la cola", "Add to queue")) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onQueue(false)
                    }
                )
                val downloading = dl != null && dl !is DownloadStatus.Done && dl !is DownloadStatus.Failed
                DropdownMenuItem(
                    text = {
                        Text(
                            when {
                                offline.inLibrary -> getLocalized("Ya está en tu biblioteca", "Already in your library")
                                downloading -> getLocalized("Descargando…", "Downloading…")
                                dl is DownloadStatus.Failed -> getLocalized("Reintentar descarga", "Retry download")
                                else -> getLocalized("Descargar a la biblioteca", "Download to library")
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            when {
                                offline.inLibrary -> Icons.Rounded.DownloadDone
                                dl is DownloadStatus.Failed -> Icons.Rounded.Refresh
                                else -> Icons.Rounded.Download
                            },
                            contentDescription = null
                        )
                    },
                    enabled = !offline.inLibrary && !downloading,
                    onClick = {
                        menuOpen = false
                        OnDemandTracks.saveToLibrary(context, track)
                        android.widget.Toast.makeText(
                            context,
                            getLocalized("Descargando a la biblioteca", "Downloading to library"),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }
    }
}
