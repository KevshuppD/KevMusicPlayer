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
    // "Hecho para ti": las mismas mezclas que el teléfono, calculadas fuera de la interfaz
    val mixes by produceState(emptyList<com.kevshupp.kevmusic.desktop.HomeMix>(), lib.songs) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.kevshupp.kevmusic.desktop.HomeMixes.build(lib.songs) }
    }
    val smart = lib.playlists.filter { it.smart && it.songIds.isNotEmpty() }
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
                // Con Pixel Retro, colores planos de la paleta (como en la app)
                val pixel = LocalPixelTheme.current
                val cs = MaterialTheme.colorScheme
                fun c(default: Color, themed: Color) = if (pixel) themed else default
                QuickCard("Modo aleatorio", "Toda la biblioteca", Icons.Rounded.Shuffle, c(Color(0xFFFF4081), cs.primary), c(Color(0xFF7C4DFF), cs.primary), mod) {
                    client.playSongs(lib.songs.map { it.id }, shuffle = true)
                }
                QuickCard("Favoritas", "${favorites?.songIds?.size ?: 0} canciones", Icons.Rounded.Favorite, c(Color(0xFFFF1744), cs.secondary), c(Color(0xFFFF4081), cs.secondary), mod) {
                    onOpenPlaylist(FAVORITES)
                }
                QuickCard("Historial", "Lo último que sonó", Icons.Rounded.History, c(Color(0xFF00B8D4), cs.tertiary), c(Color(0xFF2962FF), cs.tertiary), mod, onOpenHistory)
                QuickCard("Nuevas", "Agregadas hace poco", Icons.Rounded.NewReleases, c(Color(0xFF00BFA5), cs.onSurfaceVariant), c(Color(0xFF00E676), cs.onSurfaceVariant), mod, onOpenNew)
            }

            SongShelf("Escuchado recientemente", "Aquí aparecerán tus últimas reproducciones", recent, currentId)
            if (mixes.isNotEmpty() || smart.isNotEmpty()) {
                Column {
                    ShelfTitle("Hecho para ti")
                    val cards = mixes.map { Triple(it.title, it.subtitle, it.songs) to { client.playSongs(it.songs.map { s -> s.id }) } } +
                        smart.map { pl ->
                            Triple(pl.name, "Lista inteligente · ${pl.songIds.size}", pl.songIds.mapNotNull { lib.byId[it] }) to { onOpenPlaylist(pl.name) }
                        }
                    HorizontalShelf(cards) { (info, open) ->
                        val (title, subtitle, songs) = info
                        Column(Modifier.width(160.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = open).padding(8.dp)) {
                            Mosaic(songs, 144.dp, seed = title)
                            Spacer(Modifier.height(8.dp))
                            Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
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
                            Box(Modifier.clip(CircleShape)) { AlbumArt(sampleId, 110.dp, small = !com.kevshupp.kevmusic.desktop.Settings.hdGridArt, corner = 55.dp, seed = name) }
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
    val pixel = LocalPixelTheme.current
    // Sobre los colores claros de una paleta pixel el blanco no se lee: se usa el contraste de la paleta
    val ink = if (pixel) MaterialTheme.colorScheme.background else Color.White
    Row(
        modifier.height(76.dp).clip(RoundedCornerShape(20.dp))
            .background(if (pixel) Brush.linearGradient(listOf(from, to)) else Brush.linearGradient(listOf(from.copy(alpha = 0.85f), to.copy(alpha = 0.55f))))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(ink.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = ink)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
            Text(subtitle, fontSize = 12.sp, color = ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        AlbumArt(song.id, 144.dp, small = !com.kevshupp.kevmusic.desktop.Settings.hdGridArt, corner = 14.dp, seed = song.album)
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

/** Portada de mezcla o lista: 2×2 con cuatro álbumes distintos de sus canciones, o la carátula de la primera. */
@Composable
fun Mosaic(songs: List<Song>, size: androidx.compose.ui.unit.Dp, seed: String) {
    val covers = remember(songs) { songs.distinctBy { normalize(it.album) }.take(4) }
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp))) {
        if (covers.size < 4) {
            AlbumArt(covers.firstOrNull()?.id, size, small = size <= 160.dp, corner = 0.dp, seed = seed)
        } else Column {
            for (row in 0..1) Row {
                for (col in 0..1) AlbumArt(covers[row * 2 + col].id, size / 2, small = true, corner = 0.dp, seed = seed)
            }
        }
    }
}
