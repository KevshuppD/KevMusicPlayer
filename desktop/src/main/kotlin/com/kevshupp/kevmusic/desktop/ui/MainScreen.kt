package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.ConnStatus
import com.kevshupp.kevmusic.desktop.PlayerState
import com.kevshupp.kevmusic.desktop.formatTime
import kotlinx.coroutines.delay

enum class Section(val label: String, val icon: ImageVector) {
    Home("Inicio", Icons.Rounded.Home),
    NowPlaying("Reproduciendo", Icons.Rounded.GraphicEq),
    Songs("Canciones", Icons.Rounded.MusicNote),
    Artists("Artistas", Icons.Rounded.Person),
    Albums("Álbumes", Icons.Rounded.Album),
    Playlists("Listas", Icons.Rounded.QueueMusic),
    Queue("Cola", Icons.Rounded.FormatListNumbered),
    Download("Descargar", Icons.Rounded.CloudDownload)
}

/** Vista de detalle abierta encima de una sección (artista, álbum o lista). */
sealed interface Detail {
    data class Artist(val name: String) : Detail
    data class AlbumDetail(val album: Album) : Detail
    data class PlaylistDetail(val name: String) : Detail
    data object History : Detail
    data object NewSongs : Detail
}

@Composable
fun MainScreen(status: ConnStatus, section: Section, onSection: (Section) -> Unit) {
    val client = LocalClient.current
    val state by client.state.collectAsState()
    val queue by client.queue.collectAsState()
    val lyrics by client.lyrics.collectAsState()
    val library by client.library.collectAsState()
    val loading by client.libraryLoading.collectAsState()
    var detail by remember { mutableStateOf<Detail?>(null) }

    // Reloj para interpolar la posición entre los mensajes del teléfono
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state?.playing) {
        while (state?.playing == true) { now = System.currentTimeMillis(); delay(200) }
        now = System.currentTimeMillis()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.weight(1f)) {
            Sidebar(status, section) { detail = null; onSection(it) }
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp, end = 28.dp, top = 24.dp)) {
                val lib = library
                val currentId = state?.id
                if (section != Section.NowPlaying && section != Section.Queue && section != Section.Download && lib == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (loading) CircularProgressIndicator()
                        else TextButton(onClick = { client.refreshLibrary() }) { Text("Cargar la biblioteca del teléfono") }
                    }
                    return@Box
                }
                when (val d = detail) {
                    is Detail.Artist -> {
                        val songs = remember(lib, d) {
                            lib!!.songs.filter { s -> splitArtists(s.artist).any { it == d.name } }
                                .sortedWith(compareBy({ normalize(it.album) }, { it.track }, { normalize(it.title) }))
                        }
                        SongCollectionScreen(d.name, "${songs.size} canciones", songs.firstOrNull()?.id, songs, currentId, false) { detail = null }
                    }
                    is Detail.AlbumDetail -> SongCollectionScreen(
                        d.album.name,
                        listOfNotNull(d.album.artist, d.album.songs.first().year.takeIf { it.isNotBlank() }, "${d.album.songs.size} canciones").joinToString(" · "),
                        d.album.songs.first().id, d.album.songs, currentId, true
                    ) { detail = null }
                    is Detail.PlaylistDetail -> {
                        val pl = lib!!.playlists.firstOrNull { it.name == d.name }
                        val songs = pl?.songIds?.mapNotNull { lib.byId[it] } ?: emptyList()
                        SongCollectionScreen(d.name, "${songs.size} canciones · ${formatTime(songs.sumOf { it.duration })}", songs.firstOrNull()?.id, songs, currentId, true) { detail = null }
                    }
                    Detail.History -> {
                        val songs = remember(lib) { lib!!.songs.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(200) }
                        SongCollectionScreen("Historial", "Últimas ${songs.size} canciones escuchadas", songs.firstOrNull()?.id, songs, currentId, true) { detail = null }
                    }
                    Detail.NewSongs -> {
                        val songs = remember(lib) { lib!!.songs.sortedByDescending { it.added }.take(100) }
                        SongCollectionScreen("Nuevas", "Las ${songs.size} canciones agregadas más recientemente", songs.firstOrNull()?.id, songs, currentId, true) { detail = null }
                    }
                    null -> when (section) {
                        Section.Home -> HomeScreen(
                            lib!!, currentId,
                            onOpenPlaylist = { detail = Detail.PlaylistDetail(it) },
                            onOpenArtist = { detail = Detail.Artist(it) },
                            onOpenHistory = { detail = Detail.History },
                            onOpenNew = { detail = Detail.NewSongs }
                        )
                        Section.NowPlaying -> NowPlayingScreen(state, lyrics, now)
                        Section.Songs -> SongsScreen(lib!!, currentId)
                        Section.Artists -> ArtistsScreen(lib!!) { detail = Detail.Artist(it) }
                        Section.Albums -> AlbumsScreen(lib!!) { detail = Detail.AlbumDetail(it) }
                        Section.Playlists -> PlaylistsScreen(lib!!) { detail = Detail.PlaylistDetail(it) }
                        Section.Queue -> QueueScreen(queue, lib)
                        Section.Download -> DownloadsScreen()
                    }
                }
            }
        }
        PlayerBar(state, now) { detail = null; onSection(Section.NowPlaying) }
    }
}

@Composable
private fun Sidebar(status: ConnStatus, section: Section, onSection: (Section) -> Unit) {
    val client = LocalClient.current
    val loading by client.libraryLoading.collectAsState()
    Column(
        Modifier.width(220.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("KevMusic", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp, 6.dp, 8.dp, 18.dp))
        Section.entries.forEach { s ->
            val selected = s == section
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSection(s) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(s.icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(s.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        Spacer(Modifier.weight(1f))
        val (dot, label) = when (status) {
            is ConnStatus.Connected -> Color(0xFF00E676) to status.device
            is ConnStatus.Connecting -> Color(0xFFFFC400) to "Conectando…"
            is ConnStatus.Failed -> Color(0xFFFF5252) to "Reconectando…"
            ConnStatus.Disconnected -> Color.Gray to "Desconectado"
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (status is ConnStatus.Failed) {
            Text(status.message, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        }
        Row {
            TextButton(onClick = { client.refreshLibrary() }, enabled = !loading) { Text(if (loading) "Cargando…" else "Recargar", fontSize = 12.sp) }
            TextButton(onClick = { client.disconnect(forget = true) }) { Text("Desvincular", fontSize = 12.sp) }
        }
        Text(
            "Versión ${com.kevshupp.kevmusic.desktop.BuildInfo.VERSION}" + if (com.kevshupp.kevmusic.desktop.Updater.isInstalledBuild) "" else " (desarrollo)",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerBar(state: PlayerState?, now: Long, onOpenNowPlaying: () -> Unit) {
    val client = LocalClient.current
    Row(
        Modifier.fillMaxWidth().height(92.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Canción actual
        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenNowPlaying).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(state?.id?.takeIf { it != 0L }, 60.dp, corner = 10.dp, seed = state?.album ?: "")
            Spacer(Modifier.width(12.dp))
            Column {
                Text(state?.title?.ifBlank { null } ?: "Sin reproducción", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state?.artist ?: "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // Controles y progreso
        Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val repeat = state?.repeat ?: 0
                IconButton(onClick = { client.cycleRepeat() }) {
                    Icon(
                        if (repeat == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repetir",
                        tint = if (repeat == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { client.previous() }) { Icon(Icons.Rounded.SkipPrevious, "Anterior", Modifier.size(30.dp)) }
                FilledIconButton(onClick = { client.toggle() }, modifier = Modifier.size(48.dp)) {
                    if (state?.buffering == true) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(if (state?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Reproducir/Pausa", Modifier.size(30.dp))
                }
                IconButton(onClick = { client.next() }) { Icon(Icons.Rounded.SkipNext, "Siguiente", Modifier.size(30.dp)) }
                Spacer(Modifier.width(48.dp))
            }
            val duration = state?.duration ?: 0L
            var dragging by remember { mutableStateOf<Float?>(null) }
            val pos = state?.positionAt(now) ?: 0L
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(dragging?.let { (it * duration).toLong() } ?: pos), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
                Slider(
                    value = dragging ?: if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { client.seek((it * duration).toLong()) }; dragging = null },
                    enabled = duration > 0,
                    modifier = Modifier.weight(1f).height(20.dp)
                )
                Text(formatTime(duration), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp).padding(start = 6.dp))
            }
        }

        // Volumen del teléfono
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val max = state?.volumeMax ?: 15
            val vol = state?.volume ?: 0
            Icon(if (vol == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Slider(
                value = vol.toFloat(), onValueChange = { client.setVolume(it.toInt()) },
                valueRange = 0f..max.toFloat(), steps = (max - 1).coerceAtLeast(0),
                modifier = Modifier.width(160.dp)
            )
        }
    }
}
