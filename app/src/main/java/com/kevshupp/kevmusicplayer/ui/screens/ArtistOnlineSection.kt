package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MusicNote
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.telegram.DeezerAlbum
import com.kevshupp.kevmusicplayer.data.telegram.DeezerTrack
import com.kevshupp.kevmusicplayer.data.telegram.DeezerTrackSearch
import com.kevshupp.kevmusicplayer.data.telegram.LibraryMatcher
import com.kevshupp.kevmusicplayer.data.telegram.OnDemandTracks
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel

/** Perfil "en línea" de un artista en modo bajo demanda: sus populares y su discografía de Deezer. */
class ArtistOnlineState(matcher: LibraryMatcher) {
    var topTracks by mutableStateOf<List<DeezerTrack>>(emptyList())
    var albums by mutableStateOf<List<DeezerAlbum>>(emptyList())
    var loading by mutableStateOf(false)
    /** Se actualiza al cambiar la biblioteca (descargas, borrados) sin cerrar el álbum abierto. */
    var matcher by mutableStateOf(matcher)
    var openAlbum by mutableStateOf<DeezerAlbum?>(null)
    var showAllTop by mutableStateOf(false)
    /** Tipo elegido en la pestaña Discografía (`album`, `ep`, `single`, `compile`), null = todos. */
    var discoFilter by mutableStateOf<String?>(null)
}

/**
 * Matcher de la biblioteca que se rehace cuando la lista cambia. `localAudioFiles` es una
 * `SnapshotStateList` que se modifica en su sitio: con `remember(lista)` el matcher nunca se rehacía y una
 * canción recién descargada seguía saliendo como "en línea".
 */
@Composable
fun rememberLibraryMatcher(librarySongs: List<AudioFile>): LibraryMatcher {
    val matcher by remember(librarySongs) { derivedStateOf { LibraryMatcher(librarySongs.toList()) } }
    return matcher
}

/** Null si no hay artista o el modo bajo demanda no está listo. Las respuestas de Deezer quedan en caché 7 días. */
@Composable
fun rememberArtistOnline(artistName: String?, librarySongs: List<AudioFile>): ArtistOnlineState? {
    val ready = rememberOnDemandReady(artistName)
    val matcher = rememberLibraryMatcher(librarySongs)
    val state = remember(artistName) { ArtistOnlineState(matcher) }
    SideEffect { state.matcher = matcher }
    LaunchedEffect(artistName, ready) {
        if (!ready || artistName.isNullOrBlank()) return@LaunchedEffect
        state.loading = true
        try {
            val artist = DeezerTrackSearch.findArtist(artistName)
            if (artist != null) {
                state.topTracks = DeezerTrackSearch.artistTopTracks(artist.id, 10, cached = true)
                state.albums = DeezerTrackSearch.artistAlbums(artist.id, artist.name, cached = true)
                    .distinctBy { LibraryMatcher.normalizeTitle(it.title) + "|" + it.recordType }
            }
        } catch (e: Exception) {
            // Sin red y sin caché: solo se ve lo local
        }
        state.loading = false
    }
    if (!ready || artistName.isNullOrBlank()) return null
    return state
}

/** Pestañas del perfil de artista en modo bajo demanda (como en Spotify). */
enum class ArtistTab { OVERVIEW, LOCAL, DISCOGRAPHY }

/** Cuántas populares se ven en Resumen antes de "Ver más". */
private const val TOP_PREVIEW = 5

/**
 * Fila de pestañas bajo la cabecera del artista. Antes todo iba en una sola lista (populares, discografía y
 * todas las canciones locales) y las populares que ya tienes salían dos veces.
 */
fun LazyListScope.artistTabsRow(
    selected: ArtistTab,
    localCount: Int,
    onSelect: (ArtistTab) -> Unit,
    getLocalized: (String, String) -> String
) {
    item(key = "artist_tabs") {
        val tabs = listOf(
            ArtistTab.OVERVIEW to getLocalized("Resumen", "Overview"),
            ArtistTab.LOCAL to getLocalized("En tu biblioteca", "In your library") + " ($localCount)",
            ArtistTab.DISCOGRAPHY to getLocalized("Discografía", "Discography")
        )
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEach { (tab, label) ->
                FilterChip(
                    selected = selected == tab,
                    onClick = { onSelect(tab) },
                    label = { Text(label, fontWeight = if (selected == tab) FontWeight.Bold else FontWeight.Normal) },
                    shape = RoundedCornerShape(50)
                )
            }
        }
    }
}

/**
 * Contenido en línea de la pestaña elegida: Resumen = populares (5, "Ver más" hasta 10) y una fila de la
 * discografía; Discografía = todos los lanzamientos en cuadrícula con filtro por tipo. En "En tu biblioteca"
 * no se dibuja nada aquí (son las canciones locales de la lista).
 */
fun LazyListScope.artistOnlineSection(
    state: ArtistOnlineState?,
    tab: ArtistTab,
    viewModel: MediaBrowserViewModel?,
    onPlayLocal: (AudioFile) -> Unit,
    onSelectTab: (ArtistTab) -> Unit,
    getLocalized: (String, String) -> String
) {
    if (state == null || tab == ArtistTab.LOCAL) return
    if (state.loading && state.topTracks.isEmpty() && state.albums.isEmpty()) {
        item(key = "artist_online_loading") {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        return
    }
    if (!state.loading && state.topTracks.isEmpty() && state.albums.isEmpty()) {
        item(key = "artist_online_empty") {
            Text(
                getLocalized("No se encontró este artista en línea.", "This artist wasn't found online."),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
        }
        return
    }
    if (tab == ArtistTab.OVERVIEW) {
        if (state.topTracks.isNotEmpty()) {
            item(key = "artist_online_top_header") { OnlineSectionTitle(getLocalized("Populares", "Popular")) }
            val shown = if (state.showAllTop) state.topTracks else state.topTracks.take(TOP_PREVIEW)
            itemsIndexed(shown, key = { _, t -> "artist_online_top_${t.id}" }) { index, track ->
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${index + 1}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.width(22.dp)
                    )
                    Box(Modifier.weight(1f)) { OnlineTrackRow(track, state.matcher, viewModel, onPlayLocal, getLocalized) }
                }
            }
            if (state.topTracks.size > TOP_PREVIEW) {
                item(key = "artist_online_top_more") {
                    TextButton(onClick = { state.showAllTop = !state.showAllTop }, modifier = Modifier.padding(start = 24.dp)) {
                        Text(if (state.showAllTop) getLocalized("Ver menos", "Show less") else getLocalized("Ver más", "Show more"))
                    }
                }
            }
        }
        if (state.albums.isNotEmpty()) {
            item(key = "artist_online_albums") {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { OnlineSectionTitle(getLocalized("Discografía", "Discography")) }
                    TextButton(onClick = { onSelectTab(ArtistTab.DISCOGRAPHY) }) { Text(getLocalized("Ver todo", "See all")) }
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    items(state.albums.take(10), key = { it.id }) { album ->
                        OnlineAlbumCard(album, state, Modifier.width(120.dp), getLocalized)
                    }
                }
            }
        }
        return
    }
    // Discografía completa
    val types = state.albums.map { it.recordType }.distinct()
    item(key = "artist_disco_filters") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.discoFilter == null,
                onClick = { state.discoFilter = null },
                label = { Text(getLocalized("Todo", "All")) }
            )
            listOf("album", "ep", "single", "compile").filter { it in types }.forEach { type ->
                FilterChip(
                    selected = state.discoFilter == type,
                    onClick = { state.discoFilter = if (state.discoFilter == type) null else type },
                    label = { Text(recordTypePlural(type, getLocalized)) }
                )
            }
        }
    }
    val filtered = state.albums.filter { state.discoFilter == null || it.recordType == state.discoFilter }
    items(filtered.chunked(2), key = { row -> "artist_disco_" + row.joinToString("_") { it.id.toString() } }) { row ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            row.forEach { album -> OnlineAlbumCard(album, state, Modifier.weight(1f), getLocalized) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Portada cuadrada, título y "año · tipo · Tienes N"; abre [ArtistOnlineAlbumDialog]. */
@Composable
private fun OnlineAlbumCard(album: DeezerAlbum, state: ArtistOnlineState, modifier: Modifier, getLocalized: (String, String) -> String) {
    val owned = remember(album, state.matcher) { state.matcher.albumSongCount(album) }
    Column(modifier.clickable { state.openAlbum = album }) {
        SubcomposeAsyncImage(
            model = album.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            album.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            listOfNotNull(
                album.year.ifBlank { null },
                recordTypeLabel(album.recordType, getLocalized),
                if (owned > 0) getLocalized("Tienes $owned", "You have $owned") else null
            ).joinToString(" · "),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (owned > 0) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}

private fun recordTypePlural(type: String, getLocalized: (String, String) -> String): String = when (type) {
    "album" -> getLocalized("Álbumes", "Albums")
    "single" -> "Singles"
    "ep" -> "EPs"
    "compile" -> getLocalized("Recopilatorios", "Compilations")
    else -> type
}

/** Pistas de un álbum de Deezer: las que tienes suenan desde la biblioteca, el resto bajo demanda. */
@Composable
fun ArtistOnlineAlbumDialog(
    state: ArtistOnlineState?,
    viewModel: MediaBrowserViewModel?,
    onPlayLocal: (AudioFile) -> Unit,
    getLocalized: (String, String) -> String
) {
    val album = state?.openAlbum ?: return
    val context = LocalContext.current
    var tracks by remember(album) { mutableStateOf<List<DeezerTrack>?>(null) }
    LaunchedEffect(album) {
        tracks = try { DeezerTrackSearch.albumTracks(album, cached = true) } catch (e: Exception) { emptyList() }
    }
    Dialog(onDismissRequest = { state.openAlbum = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f)
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SubcomposeAsyncImage(
                        model = album.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(album.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
                        Text(
                            listOfNotNull(album.artist, album.year.ifBlank { null }).joinToString(" · "),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                    IconButton(onClick = { state.openAlbum = null }) {
                        Icon(Icons.Rounded.Close, contentDescription = getLocalized("Cerrar", "Close"), tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
                Spacer(Modifier.height(12.dp))
                val list = tracks
                if (list == null) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (list.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(getLocalized("No se pudo cargar el álbum", "Couldn't load the album"), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    }
                } else {
                    val missing = remember(list, state.matcher) { list.filter { state.matcher.findTrack(it) == null } }
                    val downloadStatuses by com.kevshupp.kevmusicplayer.data.telegram.TelegramDownloadQueue.statuses.collectAsState()
                    val onDemandStatuses by OnDemandTracks.statuses.collectAsState()
                    val toDownload = remember(missing, downloadStatuses, onDemandStatuses) {
                        missing.filter { t ->
                            val dl = downloadStatuses[t.id]
                            OnDemandTracks.localIdFor(context, t.id) == null && (dl == null || dl is com.kevshupp.kevmusicplayer.data.telegram.DownloadStatus.Failed)
                        }
                    }
                    val have = list.size - missing.count { t ->
                        OnDemandTracks.localIdFor(context, t.id) == null &&
                            downloadStatuses[t.id] !is com.kevshupp.kevmusicplayer.data.telegram.DownloadStatus.Done
                    }
                    Text(
                        getLocalized("Descargadas $have de ${list.size}", "Downloaded $have of ${list.size}"),
                        fontSize = 12.sp,
                        color = if (have == list.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                list.forEach { t ->
                                    val local = state.matcher.findTrack(t)
                                    if (local != null) viewModel?.addToQueue(local) else viewModel?.queueOnDemand(t, false)
                                }
                                android.widget.Toast.makeText(context, getLocalized("Álbum añadido a la cola", "Album added to queue"), android.widget.Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(50)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(getLocalized("A la cola", "Queue all"), fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                toDownload.forEach { OnDemandTracks.saveToLibrary(context, it) }
                                android.widget.Toast.makeText(
                                    context,
                                    getLocalized("Descargando ${toDownload.size} canciones", "Downloading ${toDownload.size} songs"),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            enabled = toDownload.isNotEmpty(),
                            shape = RoundedCornerShape(50)
                        ) {
                            Icon(if (have == list.size) Icons.Rounded.DownloadDone else Icons.Rounded.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    have == list.size -> getLocalized("Descargado", "Downloaded")
                                    toDownload.isEmpty() -> getLocalized("Descargando…", "Downloading…")
                                    toDownload.all { downloadStatuses[it.id] is com.kevshupp.kevmusicplayer.data.telegram.DownloadStatus.Failed } ->
                                        getLocalized("Reintentar (${toDownload.size})", "Retry (${toDownload.size})")
                                    else -> getLocalized("Descargar (${toDownload.size})", "Download (${toDownload.size})")
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(list, key = { it.id }) { track ->
                            OnlineTrackRow(track, state.matcher, viewModel, onPlayLocal, getLocalized)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
    )
}

/** Una pista de Deezer: si ya la tienes se muestra y reproduce la local; si no, la fila bajo demanda. */
@Composable
private fun OnlineTrackRow(
    track: DeezerTrack,
    matcher: LibraryMatcher,
    viewModel: MediaBrowserViewModel?,
    onPlayLocal: (AudioFile) -> Unit,
    getLocalized: (String, String) -> String
) {
    val local = remember(track, matcher) { matcher.findTrack(track) }
    if (local == null) {
        val context = LocalContext.current
        val statuses by OnDemandTracks.statuses.collectAsState()
        OnDemandSearchResultItem(
            track = track,
            status = statuses[track.id],
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
        return
    }
    val art = rememberAlbumArt(local.uriString, thumbnail = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
            .clickable { onPlayLocal(local) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SubcomposeAsyncImage(
            model = art ?: track.coverSmall,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            error = { Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.padding(12.dp)) }
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(local.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    getLocalized("En tu biblioteca", "In your library") + " · ${local.album}",
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }
    }
}

private fun recordTypeLabel(type: String, getLocalized: (String, String) -> String): String? = when (type) {
    "album" -> getLocalized("Álbum", "Album")
    "single" -> "Single"
    "ep" -> "EP"
    "compile" -> getLocalized("Recopilatorio", "Compilation")
    else -> null
}

/** Álbumes de Deezer para una búsqueda en la pestaña Álbumes (modo bajo demanda). */
class OnDemandAlbumSearchState(val albums: List<DeezerAlbum>, val loading: Boolean, val dialogState: ArtistOnlineState)

@Composable
fun rememberOnDemandAlbumSearch(query: String, librarySongs: List<AudioFile>): OnDemandAlbumSearchState {
    val ready = rememberOnDemandReady(query.isBlank())
    val matcher = rememberLibraryMatcher(librarySongs)
    // Reutiliza el diálogo de álbum del perfil de artista, que solo necesita el matcher y el álbum abierto
    val dialogState = remember { ArtistOnlineState(matcher) }
    SideEffect { dialogState.matcher = matcher }
    var albums by remember { mutableStateOf<List<DeezerAlbum>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query, ready) {
        albums = emptyList()
        loading = false
        if (!ready || query.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        loading = true
        albums = try { DeezerTrackSearch.searchAlbums(query.trim(), 20) } catch (e: Exception) { emptyList() }
        loading = false
    }
    return OnDemandAlbumSearchState(albums, loading, dialogState)
}

/** Sección "En línea · bajo demanda" al final de la cuadrícula de álbumes. */
fun androidx.compose.foundation.lazy.grid.LazyGridScope.onDemandAlbumSection(
    state: OnDemandAlbumSearchState,
    getLocalized: (String, String) -> String
) {
    if (state.albums.isEmpty() && !state.loading) return
    item(key = "ondemand_albums_header", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Rounded.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                getLocalized("En línea · bajo demanda", "Online · on demand"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
    if (state.loading) {
        item(key = "ondemand_albums_loading", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
    items(state.albums.size, key = { "ondemand_album_${state.albums[it].id}" }) { i ->
        val album = state.albums[i]
        val owned = remember(album, state.dialogState.matcher) { state.dialogState.matcher.albumSongCount(album) }
        Column(Modifier.clip(RoundedCornerShape(20.dp)).clickable { state.dialogState.openAlbum = album }) {
            Box {
                SubcomposeAsyncImage(
                    model = album.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Icon(
                    if (owned > 0) Icons.Rounded.DownloadDone else Icons.Rounded.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(26.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(4.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(album.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                listOfNotNull(
                    album.artist,
                    if (owned > 0) getLocalized("Tienes $owned", "You have $owned") else null
                ).joinToString(" · "),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (owned > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

/** Artistas de Deezer para una búsqueda en la pestaña Artistas que no están ya en la biblioteca. */
class OnDemandArtistSearchState(val artists: List<com.kevshupp.kevmusicplayer.data.telegram.DeezerArtist>, val loading: Boolean)

@Composable
fun rememberOnDemandArtistSearch(query: String, libraryArtists: Set<String>): OnDemandArtistSearchState {
    val ready = rememberOnDemandReady(query.isBlank())
    val known = remember(libraryArtists) { libraryArtists.map { LibraryMatcher.normalize(it) }.toSet() }
    var artists by remember { mutableStateOf<List<com.kevshupp.kevmusicplayer.data.telegram.DeezerArtist>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query, ready, known) {
        artists = emptyList()
        loading = false
        if (!ready || query.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        loading = true
        artists = try {
            DeezerTrackSearch.searchArtists(query.trim(), 15).filterNot { LibraryMatcher.normalize(it.name) in known }
        } catch (e: Exception) {
            emptyList()
        }
        loading = false
    }
    return OnDemandArtistSearchState(artists, loading)
}

/**
 * Sección "En línea · bajo demanda" al final de la lista de artistas. Tocar uno abre su perfil: sin canciones
 * locales, solo se ven sus populares y su discografía de Deezer ([artistOnlineSection]).
 */
fun LazyListScope.onDemandArtistSection(
    state: OnDemandArtistSearchState,
    getLocalized: (String, String) -> String,
    onOpen: (String) -> Unit
) {
    if (state.artists.isEmpty() && !state.loading) return
    item(key = "ondemand_artists_header") { SearchHeader(getLocalized("En línea · bajo demanda", "Online · on demand")) }
    if (state.loading) {
        item(key = "ondemand_artists_loading") {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
    items(state.artists, key = { "ondemand_artist_${it.id}" }) { artist ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                .clickable { onOpen(artist.name) }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SubcomposeAsyncImage(
                model = artist.picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(artist.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        listOfNotNull(
                            if (artist.albumCount > 0) getLocalized("${artist.albumCount} álbumes", "${artist.albumCount} albums") else null,
                            if (artist.fans > 0) getLocalized("${artist.fans} fans", "${artist.fans} fans") else null
                        ).joinToString(" · ").ifBlank { getLocalized("En línea", "Online") },
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
