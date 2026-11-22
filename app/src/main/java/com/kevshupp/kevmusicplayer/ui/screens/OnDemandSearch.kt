package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import androidx.compose.material.icons.rounded.Close
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

/** Resultados ya buscados en esta sesión (consulta → pistas de Deezer): repetir o ampliar una búsqueda es instantáneo. */
private val searchMemory = object : java.util.LinkedHashMap<String, Pair<Long, List<DeezerTrack>>>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<DeezerTrack>>>?) = size > 40
}
private const val MEMORY_TTL_MS = 15 * 60_000L

private fun remembered(key: String): List<DeezerTrack>? = synchronized(searchMemory) {
    searchMemory[key]?.takeIf { System.currentTimeMillis() - it.first < MEMORY_TTL_MS }?.second
}

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
        loading = false
        if (!ready || query.isBlank()) {
            tracks = emptyList()
            return@LaunchedEffect
        }
        val key = query.trim().lowercase()
        val hit = remembered(key)
        // Con la búsqueda ya guardada no se vacía la lista antes de rellenarla: sin parpadeo al volver a la pestaña
        if (hit == null) tracks = emptyList()
        // Ya buscada: se muestra al momento y sin esperar los 600 ms que evitan una petición por tecla
        if (hit == null) kotlinx.coroutines.delay(600)
        loading = hit == null
        tracks = try {
            val found = hit ?: DeezerTrackSearch.search(query.trim(), 25).also { synchronized(searchMemory) { searchMemory[key] = System.currentTimeMillis() to it } }
            affinity.rank(found.filterNot { matcher.hasTrack(it) }) { it.artist }
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
    getLocalized: (String, String) -> String,
    isCompact: Boolean = false,
    songStyle: String = "card",
    tracks: List<DeezerTrack> = state.tracks,
    showLoading: Boolean = true
) {
    if (tracks.isEmpty() && !(showLoading && state.loading)) return
    if (showLoading && state.loading) {
        item(key = "ondemand_loading") {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
    items(tracks.take(limit), key = { "ondemand_${it.id}" }) { track ->
        val context = LocalContext.current
        OnDemandSearchResultItem(
            track = track,
            status = state.statuses[track.id],
            getLocalized = getLocalized,
            isCompact = isCompact,
            songStyle = songStyle,
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
    // Cada fila observa solo su canción: el progreso de una descarga no redibuja las demás
    val od by remember(track.id) { OnDemandTracks.statuses.map { it[track.id] }.distinctUntilChanged() }
        .collectAsState(OnDemandTracks.statuses.value[track.id])
    val dl by remember(track.id) { TelegramDownloadQueue.statuses.map { it[track.id] }.distinctUntilChanged() }
        .collectAsState(TelegramDownloadQueue.statuses.value[track.id])
    // Mira el disco fuera del hilo principal, y solo cuando cambia el tipo de estado (no con cada % de progreso)
    val files by produceState(offlineFilesSeen[track.id] ?: (false to false), track.id, od?.javaClass, dl?.javaClass) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Pair(OnDemandTracks.localIdFor(context, track.id) != null, OnDemandTracks.isCached(context, track.id))
                .also { offlineFilesSeen[track.id] = it }
        }
    }
    return OnDemandOffline(inLibrary = files.first || dl is DownloadStatus.Done, cached = files.second, libraryDownload = dl)
}

/** Lo último visto de cada canción (en biblioteca, en caché), para no parpadear al volver a entrar en pantalla con el scroll. */
private val offlineFilesSeen = java.util.concurrent.ConcurrentHashMap<Long, Pair<Boolean, Boolean>>()

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
    onQueue: (next: Boolean) -> Unit,
    isCompact: Boolean = false,
    songStyle: String = "card"
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
    // Con el estilo "modern" de la biblioteca la fila es idéntica a la de una canción local (mismo tamaño, sin tarjeta)
    val modern = songStyle == "modern"
    val artSize = if (modern) (if (isCompact) 44.dp else 52.dp) else 48.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (modern) 0.dp else 3.dp)
            .clip(RoundedCornerShape(if (modern) 12.dp else 16.dp))
            .background(if (modern) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
            .clickable(onClick = onPlay)
            .padding(horizontal = if (modern) (if (isCompact) 8.dp else 10.dp) else 8.dp, vertical = if (modern) (if (isCompact) 4.dp else 7.dp) else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(artSize)
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
        // Petición al bot en curso (cola, reproductor o TV): cancelar para no quedar esperando; fallida: reintentar
        val odBusy = status == DownloadStatus.Queued || status == DownloadStatus.Requesting || status is DownloadStatus.Downloading
        if (odBusy && !offline.inLibrary && !offline.cached) {
            IconButton(onClick = { OnDemandTracks.cancel(track.id) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = getLocalized("Cancelar", "Cancel"),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
            }
        } else if (status is DownloadStatus.Failed && !offline.inLibrary && !offline.cached) {
            IconButton(onClick = { OnDemandTracks.retry(context, track.id) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = getLocalized("Reintentar", "Retry"),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
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
                    text = { Text(getLocalized("Reproducir a continuación", "Play next")) },
                    leadingIcon = { Icon(Icons.Rounded.QueuePlayNext, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onQueue(true)
                    }
                )
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

@Composable
fun SearchHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp)
    )
}


private fun normalizeForSearch(text: String) =
    java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

/** Qué tan bien responde una canción a la búsqueda: 3 título exacto, 2 empieza igual, 1 contiene todas las palabras, 0 nada. */
internal fun searchRelevance(title: String, artist: String, query: String): Int {
    val q = normalizeForSearch(query)
    if (q.isEmpty()) return 0
    val t = normalizeForSearch(title)
    val full = "${normalizeForSearch(artist)} $t"
    val both = "$t ${normalizeForSearch(artist)}"
    return when {
        t == q || full == q || both == q -> 3
        t.startsWith(q) || full.startsWith(q) -> 2
        q.split(Regex("\\s+")).all { it in full } -> 1
        else -> 0
    }
}

/**
 * Reparte los resultados en línea: los que responden a la búsqueda mejor que cualquier canción de tu biblioteca
 * van arriba de la lista; el resto, al final.
 */
internal fun splitOnDemandByRelevance(tracks: List<DeezerTrack>, local: List<AudioFile>, query: String): Pair<List<DeezerTrack>, List<DeezerTrack>> {
    if (tracks.isEmpty() || query.isBlank()) return emptyList<DeezerTrack>() to tracks
    val bestLocal = local.maxOfOrNull { searchRelevance(it.title, it.artist, query) } ?: 0
    val (top, rest) = tracks.partition { searchRelevance(it.title, it.artist, query) > bestLocal }
    return top.sortedByDescending { searchRelevance(it.title, it.artist, query) } to rest
}
