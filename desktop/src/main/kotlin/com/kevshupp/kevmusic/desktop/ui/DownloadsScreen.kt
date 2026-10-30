package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.*
import kotlinx.coroutines.launch

private enum class DzMode(val label: String, val key: String) { Tracks("Canciones", "tracks"), Albums("Álbumes", "albums"), Artists("Artistas", "artists") }

private sealed interface DzPage {
    data class AlbumPage(val album: DzAlbum) : DzPage
    data class ArtistPage(val artist: DzArtist) : DzPage
}

/** Dos fuentes: el bot de Telegram del teléfono y Soulseek en el propio PC. */
@Composable
fun DownloadsScreen() {
    var soulseek by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SectionTitle(
            "Descargar",
            if (soulseek) "Red Soulseek · se descarga en el PC y se envía al teléfono"
            else "Con el bot de Telegram del teléfono · se guarda en su carpeta de música"
        ) {
            FilterChip(selected = !soulseek, onClick = { soulseek = false }, label = { Text("Telegram") }, leadingIcon = { Icon(Icons.Rounded.Send, null, Modifier.size(16.dp)) })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = soulseek, onClick = { soulseek = true }, label = { Text("Soulseek") }, leadingIcon = { Icon(Icons.Rounded.Hub, null, Modifier.size(16.dp)) })
        }
        if (soulseek) SoulseekScreen() else TelegramDownloads()
    }
}

/**
 * Descargas con el bot de Telegram del teléfono: el PC solo busca y pide; el teléfono descarga y guarda
 * en su carpeta principal (Artista/Álbum), como desde la app.
 */
@Composable
private fun TelegramDownloads() {
    val client = LocalClient.current
    val tg by client.telegram.collectAsState()
    val downloads by client.downloads.collectAsState()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(DzMode.Tracks) }
    var showQueue by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DzResult?>(null) }
    val pages = remember { mutableStateListOf<DzPage>() }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        showQueue = false
        pages.clear()
        loading = true
        scope.launch {
            result = client.tgSearch(q, mode.key)
            loading = false
        }
    }
    LaunchedEffect(mode) { if (query.isNotBlank()) search() }

    val statusById = downloads.associateBy { it.track.id }
    val active = downloads.count { it.status != DlStatus.Done && it.status != DlStatus.Failed }

    Column(Modifier.fillMaxSize()) {
        if (!tg.ready) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text(tg.reason.ifBlank { "Telegram no está listo en el teléfono." })
                }
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(
                query, { query = it }, "Buscar en Deezer y pulsa Enter",
                Modifier.weight(1f).onPreviewKeyEvent { if (it.key == Key.Enter) { search(); true } else false }
            )
            Spacer(Modifier.width(10.dp))
            Button(onClick = { search() }, enabled = query.isNotBlank(), shape = RoundedCornerShape(14.dp)) { Text("Buscar") }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DzMode.entries.forEach { m ->
                FilterChip(selected = !showQueue && mode == m, onClick = { showQueue = false; pages.clear(); mode = m }, label = { Text(m.label) })
            }
            Spacer(Modifier.width(12.dp))
            FilterChip(
                selected = showQueue, onClick = { showQueue = !showQueue },
                label = { Text(if (active > 0) "Cola ($active)" else "Cola") },
                leadingIcon = { if (active > 0) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Download, null, Modifier.size(16.dp)) }
            )
        }
        Spacer(Modifier.height(12.dp))

        when {
            showQueue -> DownloadQueue(downloads)
            pages.isNotEmpty() -> when (val page = pages.last()) {
                is DzPage.AlbumPage -> AlbumPage(page.album, statusById, onBack = { pages.removeAt(pages.lastIndex) })
                is DzPage.ArtistPage -> ArtistPage(page.artist, statusById, onBack = { pages.removeAt(pages.lastIndex) }) { pages.add(DzPage.AlbumPage(it)) }
            }
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            result?.error != null -> Text(result!!.error!!, color = MaterialTheme.colorScheme.error)
            result != null -> {
                val r = result!!
                ResultList {
                    when (mode) {
                        DzMode.Tracks -> {
                            if (r.tracks.isNotEmpty()) item { DownloadAllButton(r.tracks, statusById) }
                            items(r.tracks, key = { it.id }) { TrackRow(it, statusById[it.id]) }
                        }
                        DzMode.Albums -> items(r.albums, key = { it.id }) { a -> AlbumRow(a) { pages.add(DzPage.AlbumPage(a)) } }
                        DzMode.Artists -> items(r.artists, key = { it.id }) { a -> ArtistRow(a) { pages.add(DzPage.ArtistPage(a)) } }
                    }
                    if (r.tracks.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty()) item { Text("Sin resultados", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            else -> Text(
                "Busca canciones, álbumes o artistas. Las que ya tienes salen marcadas, y lo que descargues aparecerá solo en la biblioteca.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ResultList(content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
fun UrlImage(url: String, size: Dp, corner: Dp = 8.dp, round: Boolean = false) {
    val client = LocalClient.current
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(client.cachedUrlImage(url)) }
    LaunchedEffect(url) { if (bitmap == null) bitmap = client.loadUrlImage(url) }
    Box(
        Modifier.size(size).clip(if (round) CircleShape else RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bitmap
        if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(if (round) Icons.Rounded.Person else Icons.Rounded.Album, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.45f))
    }
}

/** "Descargar N canciones": solo las que no tienes ni están ya en la cola. */
@Composable
private fun DownloadAllButton(tracks: List<DzTrack>, statusById: Map<Long, DownloadItem>) {
    val client = LocalClient.current
    val missing = tracks.filter { t -> !t.owned && (statusById[t.id] == null || statusById[t.id]?.status == DlStatus.Failed) }
    if (missing.isEmpty()) return
    OutlinedButton(onClick = { client.tgDownload(missing) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp))
        Text(if (missing.size == 1) "Descargar 1 canción" else "Descargar ${missing.size} canciones")
    }
}

@Composable
internal fun TrackRow(track: DzTrack, item: DownloadItem?) {
    val client = LocalClient.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Reproducir: desde la biblioteca si ya la tienes; si no, bajo demanda en el teléfono
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).clickable {
            if (track.localId > 0) client.playSongs(listOf(track.localId)) else client.playOnDemand(track)
        }, contentAlignment = Alignment.Center) {
            UrlImage(track.cover, 44.dp)
            Icon(Icons.Rounded.PlayArrow, "Reproducir", tint = Color.White,
                modifier = Modifier.size(26.dp).background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape).padding(3.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${track.artist} · ${track.album}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item?.status == DlStatus.Failed) Text(item.reason, fontSize = 11.sp, color = MaterialTheme.colorScheme.error, maxLines = 1)
        }
        Text(formatTime(track.durationSec * 1000L), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (track.localId == 0L && !track.owned) {
            IconButton(onClick = { client.queueOnDemand(track, next = false) }) { Icon(Icons.Rounded.QueueMusic, "Añadir a la cola (bajo demanda)") }
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.width(120.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                item?.status == DlStatus.Done -> StatusLabel(Icons.Rounded.CheckCircle, "Descargada", Color(0xFF00E676))
                item?.status == DlStatus.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(progress = { item.progress }, modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${(item.progress * 100).toInt()} %", fontSize = 12.sp)
                }
                item?.status == DlStatus.Requesting -> StatusLabel(Icons.Rounded.Send, "Pidiendo…", MaterialTheme.colorScheme.secondary)
                item?.status == DlStatus.Queued -> StatusLabel(Icons.Rounded.Schedule, "En cola", MaterialTheme.colorScheme.onSurfaceVariant)
                track.owned && item == null -> StatusLabel(Icons.Rounded.LibraryMusic, "La tienes", MaterialTheme.colorScheme.onSurfaceVariant)
                else -> FilledTonalButton(onClick = { client.tgDownload(listOf(track)) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(if (item?.status == DlStatus.Failed) Icons.Rounded.Refresh else Icons.Rounded.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (item?.status == DlStatus.Failed) "Reintentar" else "Descargar", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = color)
    }
}

@Composable
private fun AlbumRow(album: DzAlbum, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UrlImage(album.cover, 56.dp, corner = 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(album.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(album.artist, album.year, recordTypeLabel(album.type), if (album.tracks > 0) "${album.tracks} canciones" else "")
                    .filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
            )
        }
        if (album.owned > 0) Text("Tienes ${album.owned}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ArtistRow(artist: DzArtist, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UrlImage(artist.picture, 56.dp, round = true)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, fontWeight = FontWeight.Medium)
            Text("${artist.albums} álbumes · ${"%,d".format(artist.fans)} fans", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun recordTypeLabel(type: String) = when (type) { "single" -> "Sencillo"; "ep" -> "EP"; "compile" -> "Recopilatorio"; "album" -> "Álbum"; else -> "" }

@Composable
internal fun AlbumPage(album: DzAlbum, statusById: Map<Long, DownloadItem>, onBack: () -> Unit) {
    val client = LocalClient.current
    var result by remember(album.id) { mutableStateOf<DzResult?>(null) }
    LaunchedEffect(album.id) { result = client.tgAlbum(album) }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Volver") }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 14.dp)) {
            UrlImage(album.cover, 130.dp, corner = 16.dp)
            Spacer(Modifier.width(20.dp))
            Column {
                Text(album.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(album.artist, album.year, recordTypeLabel(album.type)).filter { it.isNotBlank() }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                result?.tracks?.let { Spacer(Modifier.height(8.dp)); DownloadAllButton(it, statusById) }
            }
        }
        val r = result
        when {
            r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            r.error != null -> Text(r.error, color = MaterialTheme.colorScheme.error)
            else -> ResultList { items(r.tracks, key = { it.id }) { TrackRow(it, statusById[it.id]) } }
        }
    }
}

@Composable
private fun ArtistPage(artist: DzArtist, statusById: Map<Long, DownloadItem>, onBack: () -> Unit, onOpenAlbum: (DzAlbum) -> Unit) {
    val client = LocalClient.current
    var result by remember(artist.id) { mutableStateOf<DzResult?>(null) }
    LaunchedEffect(artist.id) { result = client.tgArtist(artist) }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Volver") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
            UrlImage(artist.picture, 110.dp, round = true)
            Spacer(Modifier.width(20.dp))
            Column {
                Text(artist.name, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("${"%,d".format(artist.fans)} fans", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val r = result
        when {
            r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            r.error != null -> Text(r.error, color = MaterialTheme.colorScheme.error)
            else -> ResultList {
                item { Text("Populares", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp)) }
                item { DownloadAllButton(r.tracks, statusById) }
                items(r.tracks, key = { "t${it.id}" }) { TrackRow(it, statusById[it.id]) }
                item { Text("Discografía", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)) }
                items(r.albums, key = { "a${it.id}" }) { a -> AlbumRow(a) { onOpenAlbum(a) } }
            }
        }
    }
}

@Composable
private fun DownloadQueue(downloads: List<DownloadItem>) {
    val client = LocalClient.current
    // Como en la app: descargando, en espera, fallidas y terminadas
    val order = listOf(DlStatus.Downloading, DlStatus.Requesting, DlStatus.Queued, DlStatus.Failed, DlStatus.Done)
    val sorted = downloads.sortedBy { order.indexOf(it.status) }
    val done = downloads.count { it.status == DlStatus.Done }
    val failed = downloads.filter { it.status == DlStatus.Failed }
    Column(Modifier.fillMaxSize()) {
        if (downloads.isEmpty()) {
            Text("No hay descargas en esta sesión del teléfono.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text(
                "${downloads.size} en total · $done listas" + if (failed.isNotEmpty()) " · ${failed.size} fallidas" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
            )
            if (failed.isNotEmpty()) TextButton(onClick = { client.tgDownload(failed.map { it.track }) }) { Text("Reintentar las fallidas") }
            TextButton(onClick = { client.tgClearFinished() }, enabled = done + failed.size > 0) { Text("Limpiar terminadas") }
        }
        ResultList { items(sorted, key = { it.track.id }) { TrackRow(it.track, it) } }
    }
}
