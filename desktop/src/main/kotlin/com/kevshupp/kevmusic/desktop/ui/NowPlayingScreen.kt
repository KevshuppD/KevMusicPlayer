package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.LyricLine
import com.kevshupp.kevmusic.desktop.Lyrics
import com.kevshupp.kevmusic.desktop.PlayerState
import com.kevshupp.kevmusic.desktop.Settings
import kotlinx.coroutines.delay

@Composable
fun NowPlayingScreen(state: PlayerState?, lyrics: Lyrics?) {
    if (state == null || state.id == 0L) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No hay nada sonando. Elige una canción en la biblioteca.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight, 480.dp)
                AlbumArt(state.id, side, small = false, corner = 24.dp, seed = state.album)
            }
            Spacer(Modifier.height(24.dp))
            Text(state.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state.artist, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, maxLines = 1)
            if (state.album.isNotBlank()) Text(state.album, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Box(Modifier.weight(1.1f).fillMaxHeight()) {
            LyricsPanel(lyrics?.takeIf { it.id == state.id }, state)
        }
    }
}

@Composable
private fun LyricsPanel(lyrics: Lyrics?, state: PlayerState) {
    val client = LocalClient.current
    if (lyrics == null || lyrics.lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Esta canción no tiene letra guardada", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val listState = rememberLazyListState()
    // La línea activa se recalcula justo cuando empieza la siguiente, no con un reloj fijo
    val active by produceState(-1, lyrics, state) {
        if (!lyrics.synced) return@produceState
        while (true) {
            val pos = state.positionAt(System.currentTimeMillis())
            val idx = lyrics.lines.indexOfLast { it.timeMs <= pos + 250 }
            value = idx
            if (!state.playing) break
            val next = lyrics.lines.getOrNull(idx + 1)?.timeMs ?: break
            delay((next - 250 - pos).coerceIn(40, 5000))
        }
    }
    val fontSize = when (Settings.lyricsSize) { 0 -> 18; 2 -> 32; else -> 24 }
    LaunchedEffect(active, lyrics.id) {
        if (active >= 0) listState.animateScrollToItem((active - 3).coerceAtLeast(0))
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 60.dp)) {
        itemsIndexed(lyrics.lines) { i, line ->
            val color by animateColorAsState(
                when {
                    !lyrics.synced -> MaterialTheme.colorScheme.onSurface
                    i == active -> MaterialTheme.colorScheme.onBackground
                    i < active -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                }
            )
            val translation = if (Settings.showTranslation) translationFor(lyrics, line) else null
            Column(
                Modifier.fillMaxWidth().then(
                    if (lyrics.synced && line.timeMs >= 0) Modifier.clickable { client.seek(line.timeMs) } else Modifier
                )
            ) {
                Text(
                    line.text.ifBlank { "♪" },
                    fontSize = if (lyrics.synced) fontSize.sp else (fontSize - 6).sp,
                    fontWeight = if (i == active) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = color
                )
                if (translation != null) {
                    Text(translation, fontSize = (fontSize * 0.62f).sp, color = MaterialTheme.colorScheme.secondary.copy(alpha = if (i == active) 1f else 0.6f))
                }
            }
        }
    }
}

private fun translationFor(lyrics: Lyrics, line: LyricLine): String? {
    if (lyrics.translated.isEmpty()) return null
    val match = if (line.timeMs >= 0) lyrics.translated.firstOrNull { it.timeMs == line.timeMs }
    else lyrics.translated.getOrNull(lyrics.lines.indexOf(line))
    return match?.text?.takeIf { it.isNotBlank() && it != line.text }
}
