package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.Library
import com.kevshupp.kevmusic.desktop.Song
import java.time.LocalTime

private const val FAVORITES = "Favoritos"

/** Inicio, como el de la app: accesos rápidos, recientes, más escuchadas, nuevas y artistas favoritos. */
@Composable
fun HomeScreen(
    lib: Library,
    currentId: Long?,
    onOpenPlaylist: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenNew: () -> Unit
) {
    val client = LocalClient.current
    val recent = remember(lib) { lib.songs.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }.take(20) }
    val top = remember(lib) { lib.songs.filter { it.plays > 0 }.sortedByDescending { it.plays }.take(20) }
    val added = remember(lib) { lib.songs.sortedByDescending { it.added }.take(20) }
    val topArtists = remember(lib) {
        lib.songs.filter { it.plays > 0 }
            .flatMap { s -> splitArtists(s.artist).map { it to s } }
            .groupBy({ it.first }, { it.second })
            .map { (name, songs) -> Triple(name, songs.sumOf { it.plays }, songs.maxBy { it.plays }.id) }
            .sortedByDescending { it.second }
            .take(12)
    }
    val favorites = lib.playlists.firstOrNull { it.name == FAVORITES }
    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> "Buenos días"
        in 12..19 -> "Buenas tardes"
        else -> "Buenas noches"
    }

    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(end = 12.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Column {
                Text(greeting, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text("Tu música favorita te espera · ${lib.songs.size} canciones en ${lib.device}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val mod = Modifier.weight(1f)
                QuickCard("Modo aleatorio", "Toda la biblioteca", Icons.Rounded.Shuffle, Color(0xFFFF4081), Color(0xFF7C4DFF), mod) {
                    client.playSongs(lib.songs.map { it.id }, shuffle = true)
                }
                QuickCard("Favoritas", "${favorites?.songIds?.size ?: 0} canciones", Icons.Rounded.Favorite, Color(0xFFFF1744), Color(0xFFFF4081), mod) {
                    onOpenPlaylist(FAVORITES)
                }
                QuickCard("Historial", "Lo último que sonó", Icons.Rounded.History, Color(0xFF00B8D4), Color(0xFF2962FF), mod, onOpenHistory)
                QuickCard("Nuevas", "Agregadas hace poco", Icons.Rounded.NewReleases, Color(0xFF00BFA5), Color(0xFF00E676), mod, onOpenNew)
            }

            SongShelf("Escuchado recientemente", "Aquí aparecerán tus últimas reproducciones", recent, currentId)
            SongShelf("Lo que más escuchas", "Tus canciones favoritas saldrán aquí", top, currentId, showPlays = true)
            SongShelf("Agregado recientemente", "No hay canciones", added, currentId)

            if (topArtists.isNotEmpty()) {
                Column {
                    ShelfTitle("Tus artistas")
                    HorizontalShelf(topArtists) { (name, plays, sampleId) ->
                        Column(
                            Modifier.width(130.dp).clip(RoundedCornerShape(14.dp)).clickable { onOpenArtist(name) }.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(Modifier.clip(CircleShape)) { AlbumArt(sampleId, 110.dp, small = false, corner = 55.dp, seed = name) }
                            Spacer(Modifier.height(8.dp))
                            Text(name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$plays reproducciones", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun QuickCard(title: String, subtitle: String, icon: ImageVector, from: Color, to: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(76.dp).clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(from.copy(alpha = 0.85f), to.copy(alpha = 0.55f))))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            Text(subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ShelfTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun <T> HorizontalShelf(items: List<T>, item: @Composable (T) -> Unit) {
    val state = rememberLazyListState()
    Column {
        LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(items) { item(it) } }
        HorizontalScrollbar(rememberScrollbarAdapter(state), Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
private fun SongShelf(title: String, empty: String, songs: List<Song>, currentId: Long?, showPlays: Boolean = false) {
    val client = LocalClient.current
    Column {
        ShelfTitle(title)
        if (songs.isEmpty()) {
            Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            return@Column
        }
        HorizontalShelf(songs) { song ->
            val i = songs.indexOf(song)
            ContextMenuArea(items = {
                listOf(
                    ContextMenuItem("Reproducir") { client.playSongs(songs.map { it.id }, i) },
                    ContextMenuItem("Reproducir a continuación") { client.enqueue(listOf(song.id), true) },
                    ContextMenuItem("Añadir a la cola") { client.enqueue(listOf(song.id), false) }
                )
            }) {
                Column(Modifier.width(160.dp).clip(RoundedCornerShape(16.dp)).clickable { client.playSongs(songs.map { it.id }, i) }.padding(8.dp)) {
                    Box {
                        AlbumArt(song.id, 144.dp, small = false, corner = 14.dp, seed = song.album)
                        if (song.id == currentId) {
                            Box(
                                Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        song.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (song.id == currentId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (showPlays) "${song.artist} · ${song.plays}×" else song.artist,
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
