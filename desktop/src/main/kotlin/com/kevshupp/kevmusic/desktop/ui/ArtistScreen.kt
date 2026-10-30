package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.DzAlbum
import com.kevshupp.kevmusic.desktop.DzResult
import com.kevshupp.kevmusic.desktop.Library
import com.kevshupp.kevmusic.desktop.Song
import com.kevshupp.kevmusic.desktop.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Foto del artista desde Deezer (directo desde el PC). Solo un nombre igual normalizado, el de más fans:
 * mejor el icono genérico que la foto de otro artista, como en la app.
 */
object ArtistPhotos {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun url(name: String): String? {
        val key = normalize(name)
        cache[key]?.let { return it.ifEmpty { null } }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.deezer.com/search/artist".toHttpUrl().newBuilder()
                    .addQueryParameter("q", name).addQueryParameter("limit", "10").build()
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val data = JSONObject(resp.body!!.string()).optJSONArray("data") ?: return@use null
                    (0 until data.length()).map { data.getJSONObject(it) }
                        .filter { normalize(it.optString("name")).replace(" ", "") == key.replace(" ", "") }
                        .maxByOrNull { it.optInt("nb_fan") }
                        ?.optString("picture_xl")
                        // Deezer devuelve una silueta genérica cuando no tiene foto
                        ?.takeIf { it.isNotBlank() && !it.contains("/artist//") }
                }
            }.getOrNull()
        }
        // Sin red no se recuerda el fallo: se reintenta la próxima vez
        if (found != null) cache[key] = found
        return found
    }
}

private enum class ArtistTab(val label: String) { Overview("Resumen"), Local("En tu biblioteca"), Discography("Discografía") }

/**
 * Panel de artista como en el teléfono, con pestañas: Resumen (lo que más escuchas, populares de Deezer, tus
 * álbumes y una fila de la discografía), En tu biblioteca (álbumes y canciones) y Discografía (todo lo publicado,
 * con "Tienes N"). Las populares y la discografía las pide el teléfono a Deezer y marca lo que ya tienes; lo que no,
 * se escucha bajo demanda o se descarga. Antes las populares que ya tenías salían repetidas con tus canciones.
 */
@Composable
fun ArtistScreen(name: String, lib: Library, currentId: Long?, onOpenAlbum: (Album) -> Unit, onBack: () -> Unit) {
    val client = LocalClient.current
    val songs = remember(lib, name) {
        lib.songs.filter { s -> splitArtists(s.artist).any { normalize(it) == normalize(name) } }
            .sortedWith(compareBy({ normalize(it.album) }, { it.track }, { normalize(it.title) }))
    }
    // Sus álbumes: los de la biblioteca en los que tiene alguna canción (también como invitado)
    val albums = remember(lib, songs) {
        val ids = songs.map { it.id }.toSet()
        albumsOf(lib).filter { a -> a.name.isNotBlank() && a.songs.any { it.id in ids } }
            .sortedByDescending { a -> a.songs.maxOfOrNull { it.year.toIntOrNull() ?: 0 } ?: 0 }
    }
    val top = remember(songs) { songs.filter { it.plays > 0 }.sortedByDescending { it.plays }.take(5) }
    val photo by produceState<String?>(null, name) { value = ArtistPhotos.url(name) }
    // Populares y discografía desde el teléfono (Deezer); null mientras carga
    val online by produceState<DzResult?>(null, name) { value = client.tgArtistByName(name) }
    val downloads by client.downloads.collectAsState()
    val statusById = remember(downloads) { downloads.associateBy { it.track.id } }

    var tab by remember(name) { mutableStateOf(ArtistTab.Overview) }
    var showAllTop by remember(name) { mutableStateOf(false) }
    var discoFilter by remember(name) { mutableStateOf<String?>(null) }
    var openOnline by remember(name) { mutableStateOf<DzAlbum?>(null) }

    openOnline?.let { album ->
        AlbumPage(album, statusById, onBack = { openOnline = null })
        return
    }
    // Un álbum de la discografía que ya tienes entero se abre como álbum de la biblioteca
    fun openDisco(a: DzAlbum) {
        val local = albums.firstOrNull { normalize(it.name) == normalize(a.title) }
        if (local != null && a.owned >= a.tracks) onOpenAlbum(local) else openOnline = a
    }

    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
            item {
                TextButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Volver") }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                    val p = photo
                    if (p != null) UrlImage(p, 168.dp, round = true)
                    else Box(Modifier.clip(androidx.compose.foundation.shape.CircleShape)) { AlbumArt(songs.firstOrNull()?.id, 168.dp, small = false, corner = 84.dp, seed = name) }
                    Spacer(Modifier.width(24.dp))
                    Column {
                        Text("ARTISTA", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(name, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                "${songs.size} canciones",
                                if (albums.isNotEmpty()) "${albums.size} ${if (albums.size == 1) "álbum" else "álbumes"}" else null,
                                formatTime(songs.sumOf { it.duration }),
                                songs.sumOf { it.plays }.takeIf { it > 0 }?.let { "$it reproducciones" }
                            ).joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Row { PlayShuffleButtons(songs.map { it.id }) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    ArtistTab.entries.forEach { t ->
                        FilterChip(
                            selected = tab == t, onClick = { tab = t },
                            label = { Text(if (t == ArtistTab.Local) "${t.label} (${songs.size})" else t.label) }
                        )
                    }
                }
            }
            val r = online
            when (tab) {
                ArtistTab.Overview -> {
                    if (top.isNotEmpty()) {
                        item { SubTitle("Lo que más escuchas") }
                        itemsIndexed(top, key = { _, s -> "top-" + s.id }) { i, song -> ArtistSongRow(song, i + 1, song.id == currentId, top, i) }
                    }
                    when {
                        r == null -> item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                        r.error == null && r.tracks.isNotEmpty() -> {
                            item { SubTitle("Populares") }
                            val shown = if (showAllTop) r.tracks else r.tracks.take(5)
                            items(shown, key = { "pop-" + it.id }) { TrackRow(it, statusById[it.id]) }
                            if (r.tracks.size > 5) item {
                                TextButton(onClick = { showAllTop = !showAllTop }) { Text(if (showAllTop) "Ver menos" else "Ver más") }
                            }
                        }
                    }
                    if (albums.isNotEmpty()) {
                        item { SubTitle("Tus álbumes") }
                        item { LocalAlbumsRow(albums, onOpenAlbum) }
                    }
                    if (r != null && r.albums.isNotEmpty()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { SubTitle("Discografía") }
                                TextButton(onClick = { tab = ArtistTab.Discography }) { Text("Ver todo") }
                            }
                        }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                                items(r.albums.take(12), key = { it.id }) { a -> DiscoCard(a, Modifier.width(160.dp)) { openDisco(a) } }
                            }
                        }
                    }
                    if (r?.error != null && top.isEmpty() && albums.isEmpty()) item {
                        Text(r.error, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                    }
                }
                ArtistTab.Local -> {
                    if (albums.isNotEmpty()) {
                        item { SubTitle("Álbumes") }
                        item { LocalAlbumsRow(albums, onOpenAlbum) }
                    }
                    item { SubTitle("Canciones") }
                    itemsIndexed(songs, key = { _, s -> "all-" + s.id }) { i, song -> ArtistSongRow(song, null, song.id == currentId, songs, i) }
                }
                ArtistTab.Discography -> when {
                    r == null -> item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                    r.error != null -> item { Text(r.error, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
                    else -> {
                        val types = r.albums.map { it.type }.distinct()
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                                FilterChip(selected = discoFilter == null, onClick = { discoFilter = null }, label = { Text("Todo") })
                                listOf("album" to "Álbumes", "ep" to "EPs", "single" to "Singles", "compile" to "Recopilatorios")
                                    .filter { it.first in types }
                                    .forEach { (key, label) ->
                                        FilterChip(selected = discoFilter == key, onClick = { discoFilter = if (discoFilter == key) null else key }, label = { Text(label) })
                                    }
                            }
                        }
                        val shown = r.albums.filter { discoFilter == null || it.type == discoFilter }
                        items(shown.chunked(5), key = { row -> "disco-" + row.joinToString("-") { it.id.toString() } }) { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                row.forEach { a -> DiscoCard(a, Modifier.weight(1f)) { openDisco(a) } }
                                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun LocalAlbumsRow(albums: List<Album>, onOpenAlbum: (Album) -> Unit) {
    val client = LocalClient.current
    val edits = LocalEdits.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        items(albums, key = { it.name + "|" + it.artist }) { album ->
            ContextMenuArea(items = {
                listOf(
                    ContextMenuItem("Reproducir") { client.playSongs(album.songs.map { it.id }) },
                    ContextMenuItem("Añadir a la cola") { client.enqueue(album.songs.map { it.id }, false) },
                    ContextMenuItem("Añadir a una lista…") { edits.addToPlaylist(album.songs.map { it.id }) }
                )
            }) {
                Column(Modifier.width(160.dp).clip(RoundedCornerShape(14.dp)).clickable { onOpenAlbum(album) }.padding(6.dp)) {
                    AlbumArt(album.songs.first().id, 148.dp, small = !com.kevshupp.kevmusic.desktop.Settings.hdGridArt, corner = 12.dp, seed = album.name)
                    Spacer(Modifier.height(8.dp))
                    Text(album.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(album.songs.first().year.takeIf { it.isNotBlank() }, "${album.songs.size} canciones").joinToString(" · "),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                    )
                }
            }
        }
    }
}

/** Álbum de Deezer: portada, título y "año · tipo · Tienes N". */
@Composable
private fun DiscoCard(album: DzAlbum, modifier: Modifier, onOpen: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen).padding(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) { UrlImage(album.cover, maxWidth, corner = 12.dp) }
        Spacer(Modifier.height(8.dp))
        Text(album.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val type = when (album.type) { "album" -> "Álbum"; "ep" -> "EP"; "single" -> "Single"; "compile" -> "Recopilatorio"; else -> null }
        Text(
            listOfNotNull(album.year.takeIf { it.isNotBlank() }, type, if (album.owned > 0) "Tienes ${album.owned}" else null).joinToString(" · "),
            fontSize = 12.sp, maxLines = 1,
            color = if (album.owned > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SubTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
}

@Composable
private fun ArtistSongRow(song: Song, number: Int?, isCurrent: Boolean, list: List<Song>, index: Int) {
    val client = LocalClient.current
    val edits = LocalEdits.current
    SongRow(
        song, number, isCurrent,
        onPlay = { client.playSongs(list.map { it.id }, index) },
        onPlayNext = { client.enqueue(listOf(song.id), playNext = true) },
        onEnqueue = { client.enqueue(listOf(song.id), playNext = false) },
        extraItems = { edits.songMenuItems(client, listOf(song), "esta canción") }
    )
}
