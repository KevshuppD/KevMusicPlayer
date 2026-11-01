package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.YtAlbum
import com.kevshupp.kevmusic.desktop.YtArtist
import com.kevshupp.kevmusic.desktop.YtJob
import com.kevshupp.kevmusic.desktop.YtMusic
import com.kevshupp.kevmusic.desktop.YtStage
import com.kevshupp.kevmusic.desktop.YtTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private enum class YtMode(val label: String) { Songs("Canciones"), Albums("Álbumes"), Artists("Artistas"), Videos("Videos") }

private sealed interface YtPage {
    data class AlbumPage(val album: YtAlbum) : YtPage
    data class ArtistPage(val artist: YtArtist) : YtPage
}

/** Búsqueda y descargas de YouTube Music: pensado para lo que no aparece en Telegram ni en Soulseek. */
@Composable
fun YtMusicScreen() {
    val client = LocalClient.current
    val library by client.library.collectAsState()
    val jobs by YtMusic.jobs.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(YtMode.Songs) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val songs = remember { mutableStateListOf<YtTrack>() }
    var albums by remember { mutableStateOf(listOf<Pair<Int, YtAlbum>>()) }
    var artists by remember { mutableStateOf(listOf<Pair<Int, YtArtist>>()) }
    val pages = remember { mutableStateListOf<YtPage>() }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        songs.clear(); albums = emptyList(); artists = emptyList(); pages.clear()
        error = null
        searching = true
        searched = true
        searchJob = scope.launch {
            val fail: (Throwable) -> Unit = { error = it.message ?: "No se pudo buscar" }
            when (if (YtMusic.videoIdFrom(q) != null) YtMode.Songs else mode) {
                YtMode.Videos -> YtMusic.searchVideos(q).catch { fail(it) }.collect { t -> if (songs.none { it.id == t.id }) songs.add(t) }
                YtMode.Songs -> YtMusic.search(q).catch { fail(it) }.collect { t -> if (songs.none { it.id == t.id }) songs.add(t) }
                YtMode.Albums -> YtMusic.searchAlbums(q).catch { fail(it) }.collect { r -> albums = (albums + r).sortedBy { it.first } }
                YtMode.Artists -> YtMusic.searchArtists(q).catch { fail(it) }.collect { r -> artists = (artists + r).sortedBy { it.first } }
            }
            searching = false
        }
    }
    LaunchedEffect(mode) { if (query.isNotBlank()) search() }

    val owned = remember(library) {
        library?.songs.orEmpty().mapTo(HashSet()) { ownedKey(it.title, it.artist) }
    }
    val isOwned = { t: YtTrack -> ownedKey(t.title, t.artist) in owned }
    val jobById = jobs.associateBy { it.track.id }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(
                query, { query = it }, "Busca o pega un enlace de YouTube y pulsa Enter",
                Modifier.weight(1f).onPreviewKeyEvent { if (it.key == Key.Enter) { search(); true } else false }
            )
            Spacer(Modifier.width(10.dp))
            Button(onClick = { search() }, enabled = query.isNotBlank(), shape = RoundedCornerShape(14.dp)) { Text("Buscar") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            YtMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { pages.clear(); mode = m }, label = { Text(m.label) }) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Audio AAC de unos 128 kbps: para la mejor calidad usa Soulseek. La primera búsqueda instala yt-dlp.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        val page = pages.lastOrNull()
        Box(Modifier.weight(1f)) {
            when (page) {
                is YtPage.AlbumPage -> YtAlbumPage(page.album, jobById, isOwned, { pages.removeAt(pages.lastIndex) }) { YtMusic.enqueue(it, client) }
                is YtPage.ArtistPage -> YtArtistPage(page.artist, jobById, isOwned, { pages.removeAt(pages.lastIndex) }, { pages.add(YtPage.AlbumPage(it)) }) { YtMusic.enqueue(it, client) }
                null -> Column {
                    if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val empty = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()
                    if (!searching && error == null && empty && searched) Text("Sin resultados.")
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(songs, key = { "r" + it.id }) { t -> YtRow(t, jobById[t.id], isOwned(t)) { YtMusic.enqueue(t, client) } }
                        items(albums, key = { "a" + it.second.id }) { (_, a) -> YtAlbumRow(a) { pages.add(YtPage.AlbumPage(a)) } }
                        items(artists, key = { "ar" + it.second.id }) { (_, a) -> YtArtistRow(a) { pages.add(YtPage.ArtistPage(a)) } }
                        val shown = songs.map { it.id }.toSet()
                        val others = jobs.filter { it.track.id !in shown }
                        if (jobs.isNotEmpty()) item(key = "header") {
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Descargas", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { YtMusic.clearFinished() }) { Text("Limpiar terminadas") }
                            }
                        }
                        items(others, key = { "j" + it.track.id }) { j -> YtRow(j.track, j, false) { YtMusic.enqueue(j.track, client) } }
                    }
                }
            }
        }
    }
}

internal fun ownedKey(title: String, artist: String) = normalize(title) + "|" + normalize(splitArtists(artist).firstOrNull().orEmpty())

@Composable
private fun YtAlbumRow(album: YtAlbum, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        UrlImage(album.thumbnail, 56.dp, corner = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                listOf(album.artist, album.kind, "${album.tracks.size} canciones").filter { it.isNotBlank() }.joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun YtArtistRow(artist: YtArtist, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Person, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun YtAlbumPage(album: YtAlbum, jobs: Map<String, YtJob>, isOwned: (YtTrack) -> Boolean, onBack: () -> Unit, onDownload: (YtTrack) -> Unit) {
    val missing = album.tracks.filter { it.id !in jobs && !isOwned(it) }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← Volver") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            UrlImage(album.thumbnail, 96.dp, corner = 14.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(album.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(album.artist, album.kind).filter { it.isNotBlank() }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { missing.forEach(onDownload) }, enabled = missing.isNotEmpty(), shape = RoundedCornerShape(14.dp)) {
                Text(if (missing.isEmpty()) "Nada que descargar" else "Descargar (${missing.size})")
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(album.tracks, key = { it.id }) { t -> YtRow(t, jobs[t.id], isOwned(t)) { onDownload(t) } }
        }
    }
}

/**
 * yt-dlp no expone la discografía de un artista: son los álbumes de buscar su nombre con ese mismo artista,
 * y las canciones son las últimas que subió su canal.
 */
@Composable
private fun YtArtistPage(artist: YtArtist, jobs: Map<String, YtJob>, isOwned: (YtTrack) -> Boolean, onBack: () -> Unit, onOpenAlbum: (YtAlbum) -> Unit, onDownload: (YtTrack) -> Unit) {
    var albums by remember(artist.id) { mutableStateOf(listOf<Pair<Int, YtAlbum>>()) }
    var loading by remember(artist.id) { mutableStateOf(true) }
    LaunchedEffect(artist.id) {
        YtMusic.searchAlbums(artist.name, 20).catch { }.collect { r ->
            if (YtMusic.sameText(r.second.artist, artist.name)) albums = (albums + r).sortedBy { it.first }
        }
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← Volver") }
        Text(artist.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (albums.isNotEmpty()) item(key = "h1") { Text("Álbumes", fontWeight = FontWeight.Bold) }
            items(albums, key = { "a" + it.second.id }) { (_, a) -> YtAlbumRow(a) { onOpenAlbum(a) } }
            if (artist.songs.isNotEmpty()) item(key = "h2") { Text("Canciones recientes", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp)) }
            items(artist.songs, key = { "s" + it.id }) { t -> YtRow(t, jobs[t.id], isOwned(t)) { onDownload(t) } }
        }
    }
}

@Composable
private fun YtRow(track: YtTrack, job: YtJob?, have: Boolean, onDownload: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        UrlImage(track.thumbnail, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val info = listOf(track.artist, track.album, "%d:%02d".format(track.durationSec / 60, track.durationSec % 60)).filter { it.isNotBlank() }
            Text(info.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (job != null) Text(
                stageText(job), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                color = if (job.stage == YtStage.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            else if (have) Text("La tienes", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        when (job?.stage) {
            null -> IconButton(onClick = onDownload) { Icon(Icons.Rounded.Download, "Descargar") }
            YtStage.Failed -> IconButton(onClick = onDownload) { Icon(Icons.Rounded.Refresh, "Reintentar") }
            YtStage.Done -> {}
            else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

private fun stageText(j: YtJob) = when (j.stage) {
    YtStage.Queued -> "En espera"
    YtStage.Installing -> "Instalando ffmpeg, solo esta vez · ${j.percent} %"
    YtStage.Downloading -> "Descargando · ${j.percent} %"
    YtStage.Tagging -> "Poniendo etiquetas y carátula"
    YtStage.WaitingPhone -> j.message
    YtStage.Sending -> "Enviando al teléfono · ${j.percent} %"
    YtStage.Done -> "En el teléfono"
    YtStage.Failed -> "Falló: ${j.message}"
}
