package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.Library
import com.kevshupp.kevmusic.desktop.QueueState
import com.kevshupp.kevmusic.desktop.Song
import com.kevshupp.kevmusic.desktop.formatTime
import java.text.Normalizer

/** Sin mayúsculas ni tildes, como el buscador de la app. */
fun normalize(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** Artistas compuestos ("A, B", "A feat. B", "A & B") cuentan para cada uno, como en la app. */
private val artistSplit = Regex("""\s*(?:,|;|&|/|\bfeat\.?|\bft\.?|\bx\b)\s*""", RegexOption.IGNORE_CASE)
fun splitArtists(artist: String): List<String> =
    artist.split(artistSplit).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(artist) }

data class Album(val name: String, val artist: String, val songs: List<Song>)

fun albumsOf(lib: Library): List<Album> =
    lib.songs.groupBy { normalize(it.album) to normalize(splitArtists(it.artist).first()) }
        .map { (_, songs) ->
            val sorted = songs.sortedWith(compareBy({ it.track.takeIf { t -> t > 0 } ?: Int.MAX_VALUE }, { it.title }))
            Album(sorted.first().album, splitArtists(sorted.first().artist).first(), sorted)
        }
        .sortedBy { normalize(it.name) }

@Composable
fun SearchField(query: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, null) } },
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    )
}

@Composable
private fun ScrollableList(state: LazyListState, modifier: Modifier = Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Box(modifier) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(end = 12.dp), content = content)
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

private enum class SongSort(val label: String) { Title("Título"), Artist("Artista"), Album("Álbum"), Added("Recientes"), Plays("Más escuchadas") }

@Composable
fun SongsScreen(lib: Library, currentId: Long?) {
    val client = LocalClient.current
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SongSort.Title) }
    val songs = remember(lib, query, sort) {
        val q = normalize(query.trim())
        lib.songs
            .filter { q.isEmpty() || normalize(it.title).contains(q) || normalize(it.artist).contains(q) || normalize(it.album).contains(q) }
            .let { list ->
                when (sort) {
                    SongSort.Title -> list.sortedBy { normalize(it.title) }
                    SongSort.Artist -> list.sortedWith(compareBy({ normalize(it.artist) }, { normalize(it.album) }, { it.track }))
                    SongSort.Album -> list.sortedWith(compareBy({ normalize(it.album) }, { it.track }))
                    SongSort.Added -> list.sortedByDescending { it.added }
                    SongSort.Plays -> list.sortedByDescending { it.plays }
                }
            }
    }
    Column(Modifier.fillMaxSize()) {
        SectionTitle("Canciones", "${songs.size} de ${lib.songs.size}") {
            PlayShuffleButtons(songs.map { it.id })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(query, { query = it }, "Buscar canción, artista o álbum", Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(sort.label)
                }
                DropdownMenu(open, { open = false }) {
                    SongSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; open = false }) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SongList(songs, currentId, numbered = false)
    }
}

@Composable
fun PlayShuffleButtons(ids: List<Long>) {
    val client = LocalClient.current
    Button(onClick = { client.playSongs(ids) }, enabled = ids.isNotEmpty(), shape = RoundedCornerShape(14.dp)) {
        Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Reproducir")
    }
    Spacer(Modifier.width(8.dp))
    OutlinedButton(onClick = { client.playSongs(ids, shuffle = true) }, enabled = ids.isNotEmpty(), shape = RoundedCornerShape(14.dp)) {
        Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Aleatorio")
    }
}

@Composable
fun SongList(songs: List<Song>, currentId: Long?, numbered: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    val client = LocalClient.current
    val state = rememberLazyListState()
    ScrollableList(state, modifier) {
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            SongRow(
                song, if (numbered) i + 1 else null, song.id == currentId,
                onPlay = { client.playSongs(songs.map { it.id }, i) },
                onPlayNext = { client.enqueue(listOf(song.id), playNext = true) },
                onEnqueue = { client.enqueue(listOf(song.id), playNext = false) }
            )
        }
    }
}

@Composable
fun ArtistsScreen(lib: Library, onOpen: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val artists = remember(lib) {
        lib.songs.flatMap { s -> splitArtists(s.artist).map { it to s } }
            .groupBy({ it.first }, { it.second })
            .map { (name, songs) -> Triple(name, songs.size, songs.first().id) }
            .sortedBy { normalize(it.first) }
    }
    val shown = remember(artists, query) { normalize(query.trim()).let { q -> artists.filter { q.isEmpty() || normalize(it.first).contains(q) } } }
    Column(Modifier.fillMaxSize()) {
        SectionTitle("Artistas", "${artists.size} artistas")
        SearchField(query, { query = it }, "Buscar artista", Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        val state = rememberLazyListState()
        ScrollableList(state, Modifier.fillMaxSize()) {
            items(shown, key = { it.first }) { (name, count, sampleId) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(name) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.clip(CircleShape)) { AlbumArt(sampleId, 44.dp, corner = 22.dp, seed = name) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(name, fontWeight = FontWeight.Medium)
                        Text("$count canciones", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumsScreen(lib: Library, onOpen: (Album) -> Unit) {
    val client = LocalClient.current
    var query by remember { mutableStateOf("") }
    val albums = remember(lib) { albumsOf(lib) }
    val shown = remember(albums, query) {
        normalize(query.trim()).let { q -> albums.filter { q.isEmpty() || normalize(it.name).contains(q) || normalize(it.artist).contains(q) } }
    }
    Column(Modifier.fillMaxSize()) {
        SectionTitle("Álbumes", "${albums.size} álbumes")
        SearchField(query, { query = it }, "Buscar álbum o artista", Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        LazyVerticalGrid(
            GridCells.Adaptive(170.dp), state = rememberLazyGridState(),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items(shown, key = { it.name + "|" + it.artist }) { album ->
                ContextMenuArea(items = {
                    listOf(
                        ContextMenuItem("Reproducir") { client.playSongs(album.songs.map { it.id }) },
                        ContextMenuItem("Reproducir a continuación") { client.enqueue(album.songs.map { it.id }, true) },
                        ContextMenuItem("Añadir a la cola") { client.enqueue(album.songs.map { it.id }, false) }
                    )
                }) {
                    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { onOpen(album) }.padding(6.dp)) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            AlbumArt(album.songs.first().id, maxWidth, small = !com.kevshupp.kevmusic.desktop.Settings.hdGridArt, corner = 14.dp, seed = album.name)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(album.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(album.artist, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistsScreen(lib: Library, onOpen: (String) -> Unit) {
    val client = LocalClient.current
    Column(Modifier.fillMaxSize()) {
        SectionTitle("Listas", "${lib.playlists.size} listas")
        if (lib.playlists.isEmpty()) {
            Text("No hay listas en el teléfono.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        // Favoritos primero, como en la app; las inteligentes después de las normales
        val ordered = lib.playlists.sortedWith(compareBy({ it.name != "Favoritos" }, { it.smart }, { normalize(it.name) }))
        val state = rememberLazyListState()
        ScrollableList(state, Modifier.fillMaxSize()) {
            items(ordered, key = { it.name }) { pl ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpen(pl.name) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pl.name == "Favoritos") {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.Favorite, null, tint = MaterialTheme.colorScheme.primary) }
                    } else if (pl.smart) {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.secondary) }
                    } else {
                        AlbumArt(pl.songIds.firstOrNull(), 52.dp, corner = 12.dp, seed = pl.name)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pl.name, fontWeight = FontWeight.Medium)
                        Text(
                            (if (pl.smart) "Inteligente · " else "") + "${pl.songIds.size} canciones",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { client.playSongs(pl.songIds) }, enabled = pl.songIds.isNotEmpty()) {
                        Icon(Icons.Rounded.PlayCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** Detalle de artista, álbum o lista: cabecera con carátula y botones, y sus canciones. */
@Composable
fun SongCollectionScreen(title: String, subtitle: String, artId: Long?, songs: List<Song>, currentId: Long?, numbered: Boolean, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Volver") }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 18.dp)) {
            AlbumArt(artId, 160.dp, small = false, corner = 18.dp, seed = title)
            Spacer(Modifier.width(24.dp))
            Column {
                Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row { PlayShuffleButtons(songs.map { it.id }) }
            }
        }
        SongList(songs, currentId, numbered)
    }
}

@Composable
fun QueueScreen(queue: QueueState, lib: Library?) {
    val client = LocalClient.current
    val state = rememberLazyListState()
    LaunchedEffect(queue.index, queue.items.size) {
        if (queue.index > 2) state.scrollToItem(queue.index - 2)
    }
    val remaining = lib?.let { l ->
        queue.items.drop(queue.index + 1).sumOf { l.byId[it.id]?.duration ?: 0L }
    } ?: 0L
    Column(Modifier.fillMaxSize()) {
        SectionTitle("Cola", "${queue.items.size} canciones" + if (remaining > 0) " · quedan ${formatTime(remaining)}" else "")
        ScrollableList(state, Modifier.fillMaxSize()) {
            itemsIndexed(queue.items) { i, item ->
                val isCurrent = i == queue.index
                ContextMenuArea(items = {
                    buildList {
                        add(ContextMenuItem("Reproducir") { client.jumpTo(i) })
                        if (!isCurrent && i > queue.index + 1) add(ContextMenuItem("Reproducir a continuación") { client.moveInQueue(i, queue.index + 1) })
                        if (!isCurrent) add(ContextMenuItem("Quitar de la cola") { client.removeFromQueue(i) })
                    }
                }) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable { client.jumpTo(i) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${i + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp))
                        AlbumArt(item.id, 40.dp, seed = item.title)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else if (i < queue.index) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                            )
                            Text(item.artist, maxLines = 1, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        lib?.byId?.get(item.id)?.let { Text(formatTime(it.duration), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (!isCurrent) {
                            IconButton(onClick = { client.removeFromQueue(i) }) {
                                Icon(Icons.Rounded.Close, "Quitar", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        } else Spacer(Modifier.width(48.dp))
                    }
                }
            }
        }
    }
}
