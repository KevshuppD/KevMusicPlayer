package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.RemoteClient
import com.kevshupp.kevmusic.desktop.Song
import com.kevshupp.kevmusic.desktop.formatTime

val LocalClient = staticCompositionLocalOf<RemoteClient> { error("sin cliente") }

/** Abrir el panel de un artista o un álbum desde cualquier pantalla (lo provee MainScreen). */
class Navigation(val openArtist: (String) -> Unit, val openAlbum: (album: String, artist: String) -> Unit)
val LocalNav = staticCompositionLocalOf { Navigation({}, { _, _ -> }) }

/**
 * El texto del artista tal cual ("A feat. B", "A, B") con cada nombre como enlace a su panel; subrayado al pasar
 * el ratón. Fuera de MainScreen (sin [LocalNav]) los enlaces no hacen nada.
 */
@Composable
fun ArtistLinks(
    artist: String, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign: androidx.compose.ui.text.style.TextAlign? = null
) {
    val nav = LocalNav.current
    val text = remember(artist, nav) {
        androidx.compose.ui.text.buildAnnotatedString {
            append(artist)
            var from = 0
            splitArtists(artist).forEach { name ->
                val start = artist.indexOf(name, from)
                if (start < 0) return@forEach
                addLink(
                    androidx.compose.ui.text.LinkAnnotation.Clickable(
                        name,
                        androidx.compose.ui.text.TextLinkStyles(
                            hoveredStyle = androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                        )
                    ) { nav.openArtist(name) },
                    start, start + name.length
                )
                from = start + name.length
            }
        }
    }
    Text(text, modifier = modifier, fontSize = fontSize, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
}

private val placeholderBrushes = listOf(
    Brush.linearGradient(listOf(Color(0xFFFF4081), Color(0xFF7C4DFF))),
    Brush.linearGradient(listOf(Color(0xFF00E5FF), Color(0xFF2962FF))),
    Brush.linearGradient(listOf(Color(0xFF00F5D4), Color(0xFF00897B))),
    Brush.linearGradient(listOf(Color(0xFFB388FF), Color(0xFF4A148C))),
    Brush.linearGradient(listOf(Color(0xFFFFAB40), Color(0xFFD81B60)))
)

@Composable
fun AlbumArt(songId: Long?, size: Dp, small: Boolean = true, corner: Dp = 8.dp, seed: String = "") {
    val client = LocalClient.current
    // Filas de lista (≤ 48 dp): versión reducida, más barata de dibujar al desplazarse
    val tiny = small && size <= 48.dp
    var bitmap by remember(songId, small, tiny) { mutableStateOf<ImageBitmap?>(songId?.let { client.cachedArt(it, small, tiny) }) }
    LaunchedEffect(songId, small, tiny) {
        if (songId != null && songId != 0L && bitmap == null) bitmap = client.loadArt(songId, small, tiny)
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner))
            .background(placeholderBrushes[(seed.hashCode() and 0x7fffffff) % placeholderBrushes.size]),
        contentAlignment = Alignment.Center
    ) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(size * 0.45f))
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    number: Int?,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    extraItems: () -> List<ContextMenuItem> = { emptyList() }
) {
    ContextMenuArea(items = {
        listOf(
            ContextMenuItem("Reproducir") { onPlay() },
            ContextMenuItem("Reproducir a continuación") { onPlayNext() },
            ContextMenuItem("Añadir a la cola") { onEnqueue() }
        ) + extraItems()
    }) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    when {
                        isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        hovered -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f)
                        else -> Color.Transparent
                    }
                )
                .hoverable(interaction)
                .clickable(onClick = onPlay)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (number != null) {
                Text(
                    "$number", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp)
                )
            }
            AlbumArt(song.id, 40.dp, seed = song.album)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                ArtistLinks(song.artist)
            }
            Text(
                song.album, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.7f).padding(horizontal = 12.dp)
            )
            Text(formatTime(song.duration), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionTitle(text: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}
