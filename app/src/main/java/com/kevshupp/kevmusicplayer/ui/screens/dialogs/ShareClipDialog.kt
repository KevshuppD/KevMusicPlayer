package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.kevshupp.kevmusicplayer.data.LyricsRepository
import com.kevshupp.kevmusicplayer.playback.managers.ClipTemplateStyle
import com.kevshupp.kevmusicplayer.playback.managers.ShareClipConfig
import com.kevshupp.kevmusicplayer.playback.managers.VideoClipGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareClipDialog(
    songTitle: String,
    artistName: String,
    albumTitle: String? = null,
    songDurationMs: Long,
    currentPositionMs: Long,
    audioUriString: String,
    albumArtBitmap: Bitmap?,
    lyrics: String?,
    translatedLyrics: Map<Long, String>? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val parsedLyrics = remember(lyrics) { LyricsRepository.parseLrc(lyrics) }

    // Selection mode: 0 = Por Letras, 1 = Manual
    var selectedTab by remember { mutableIntStateOf(if (parsedLyrics.isNotEmpty()) 0 else 1) }

    // Selected lyrics indices
    val initialIdx = remember {
        if (parsedLyrics.isNotEmpty()) {
            parsedLyrics.indexOfLast { it.timeMs <= currentPositionMs }.coerceAtLeast(0)
        } else 0
    }
    var selectedLyricStartIdx by remember { mutableIntStateOf(initialIdx) }
    var selectedLyricEndIdx by remember {
        mutableIntStateOf(
            if (parsedLyrics.isNotEmpty()) min(parsedLyrics.size - 1, initialIdx + 2) else 0
        )
    }

    // Range state
    var startMs by remember {
        mutableLongStateOf(
            if (parsedLyrics.isNotEmpty()) {
                parsedLyrics[selectedLyricStartIdx].timeMs
            } else {
                currentPositionMs.coerceIn(0L, max(0L, songDurationMs - 15000L))
            }
        )
    }

    var endMs by remember {
        mutableLongStateOf(
            if (parsedLyrics.isNotEmpty()) {
                val nextAfterEnd = selectedLyricEndIdx + 1
                if (nextAfterEnd in parsedLyrics.indices) {
                    parsedLyrics[nextAfterEnd].timeMs
                } else {
                    min(songDurationMs, parsedLyrics[selectedLyricEndIdx].timeMs + 4000L)
                }
            } else {
                min(songDurationMs, startMs + 15000L)
            }
        )
    }

    // Video visual configuration
    var isVertical by remember { mutableStateOf(true) } // true: 9:16 Story, false: 1:1 Feed
    var templateStyle by remember {
        mutableStateOf(if (parsedLyrics.isNotEmpty()) ClipTemplateStyle.LYRICS_STORY else ClipTemplateStyle.PLAYER_CANVAS)
    }
    var isAmoledDark by remember { mutableStateOf(false) }
    var showWaveform by remember { mutableStateOf(true) }
    var showTranslation by remember { mutableStateOf(!translatedLyrics.isNullOrEmpty()) }
    var dedicationText by remember { mutableStateOf("") }
    var showDedicationDialog by remember { mutableStateOf(false) }

    // Generation state
    var isGenerating by remember { mutableStateOf(false) }
    var generationProgress by remember { mutableFloatStateOf(0f) }

    // Preview Player for pre-listening the snippet
    val previewPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            try {
                setMediaItem(MediaItem.fromUri(Uri.parse(audioUriString)))
                prepare()
            } catch (e: Exception) {}
        }
    }
    var isPreviewPlaying by remember { mutableStateOf(false) }
    var previewCurrentPositionMs by remember { mutableLongStateOf(startMs) }

    DisposableEffect(Unit) {
        onDispose {
            previewPlayer.release()
        }
    }

    // Preview playback loop within startMs and endMs
    LaunchedEffect(isPreviewPlaying, startMs, endMs) {
        if (isPreviewPlaying) {
            previewPlayer.seekTo(startMs)
            previewPlayer.play()
            while (isPreviewPlaying) {
                val pos = previewPlayer.currentPosition
                previewCurrentPositionMs = pos
                if (pos >= endMs || pos < startMs) {
                    previewPlayer.seekTo(startMs)
                }
                delay(40)
            }
        } else {
            previewPlayer.pause()
            previewCurrentPositionMs = startMs
        }
    }

    // Lyrics selection handler
    fun lineEndMs(index: Int): Long =
        if (index + 1 < parsedLyrics.size) parsedLyrics[index + 1].timeMs
        else min(songDurationMs, parsedLyrics[index].timeMs + 4000L)

    // Toque: amplía la selección hasta la línea tocada (en cualquier dirección). Dentro de la
    // selección, tocar un extremo lo quita y tocar una línea intermedia la fija como final.
    // Mantener pulsado empieza una selección nueva en esa línea.
    fun handleLyricClick(index: Int, startNew: Boolean = false) {
        val start = selectedLyricStartIdx
        val end = selectedLyricEndIdx
        when {
            startNew -> { selectedLyricStartIdx = index; selectedLyricEndIdx = index }
            index < start || index > end -> {
                val newStart = min(start, index)
                val newEnd = max(end, index)
                if (lineEndMs(newEnd) - parsedLyrics[newStart].timeMs <= 60000L) {
                    selectedLyricStartIdx = newStart
                    selectedLyricEndIdx = newEnd
                } else {
                    android.widget.Toast.makeText(context, "Máximo 60 s: se empezó una selección nueva", android.widget.Toast.LENGTH_SHORT).show()
                    selectedLyricStartIdx = index
                    selectedLyricEndIdx = index
                }
            }
            start == end -> Unit
            index == start -> selectedLyricStartIdx = start + 1
            index == end -> selectedLyricEndIdx = end - 1
            else -> selectedLyricEndIdx = index
        }

        // Recalculate startMs and endMs
        startMs = parsedLyrics[selectedLyricStartIdx].timeMs
        val nextAfterEnd = selectedLyricEndIdx + 1
        endMs = if (nextAfterEnd in parsedLyrics.indices) {
            parsedLyrics[nextAfterEnd].timeMs
        } else {
            min(songDurationMs, parsedLyrics[selectedLyricEndIdx].timeMs + 4000L)
        }
        isPreviewPlaying = false
        if (endMs - startMs > 60000L) {
            endMs = startMs + 60000L
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    Dialog(
        onDismissRequest = {
            if (!isGenerating) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0B10))
                .systemBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                // 1. Header Toolbar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onDismiss,
                            enabled = !isGenerating,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Crear Historia",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Format Toggle: 9:16 Story vs 1:1 Feed
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (isVertical) primaryColor else Color.Transparent)
                                .clickable { isVertical = true }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "9:16 Story",
                                color = if (isVertical) Color.Black else Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (!isVertical) primaryColor else Color.Transparent)
                                .clickable { isVertical = false }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "1:1 Feed",
                                color = if (!isVertical) Color.Black else Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2. Style Segmented Selector (3 distinct tabs)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF161824))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val styles = listOf(
                        Triple(ClipTemplateStyle.LYRICS_STORY, "Letras", Icons.Rounded.FormatQuote),
                        Triple(ClipTemplateStyle.PLAYER_CANVAS, "Canvas", Icons.Rounded.Image),
                        Triple(ClipTemplateStyle.VINYL_VIBES, "Vinilo", Icons.Rounded.Album)
                    )

                    styles.forEach { (style, name, icon) ->
                        val isSelected = templateStyle == style
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) primaryColor else Color.Transparent)
                                .clickable { templateStyle = style }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = if (isSelected) Color.Black else Color.White.copy(alpha = 0.65f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = name,
                                color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 3. Live Animated Preview Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (isAmoledDark) {
                                androidx.compose.ui.graphics.SolidColor(Color.Black)
                            } else {
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF1B1D30),
                                        Color(0xFF0F101C)
                                    )
                                )
                            }
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = templateStyle,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.94f))
                                .togetherWith(fadeOut(animationSpec = tween(180)))
                        },
                        label = "TemplateTransition"
                    ) { targetStyle ->
                        when (targetStyle) {
                            ClipTemplateStyle.LYRICS_STORY -> {
                                LyricsStoryPreview(
                                    songTitle = songTitle,
                                    artistName = artistName,
                                    albumArtBitmap = albumArtBitmap,
                                    parsedLyrics = parsedLyrics,
                                    translatedLyrics = translatedLyrics,
                                    showTranslation = showTranslation,
                                    selectedStartIdx = selectedLyricStartIdx,
                                    selectedEndIdx = selectedLyricEndIdx,
                                    previewPosMs = previewCurrentPositionMs,
                                    primaryColor = primaryColor
                                )
                            }
                            ClipTemplateStyle.PLAYER_CANVAS -> {
                                CanvasStoryPreview(
                                    songTitle = songTitle,
                                    artistName = artistName,
                                    albumArtBitmap = albumArtBitmap,
                                    parsedLyrics = parsedLyrics,
                                    translatedLyrics = translatedLyrics,
                                    showTranslation = showTranslation,
                                    previewPosMs = previewCurrentPositionMs,
                                    isPlaying = isPreviewPlaying,
                                    primaryColor = primaryColor
                                )
                            }
                            ClipTemplateStyle.VINYL_VIBES -> {
                                VinylStoryPreview(
                                    songTitle = songTitle,
                                    artistName = artistName,
                                    albumArtBitmap = albumArtBitmap,
                                    isPlaying = isPreviewPlaying,
                                    primaryColor = primaryColor
                                )
                            }
                        }
                    }

                    // Floating Dedication Badge at Top of Preview
                    if (dedicationText.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 10.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.7f))
                                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                                .clickable { showDedicationDialog = true }
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (dedicationText.startsWith("💌") || dedicationText.startsWith("✨") || dedicationText.startsWith("💖")) dedicationText else "💌 $dedicationText",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Floating Pre-Listen Pill at Bottom of Preview
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                            .clickable { isPreviewPlaying = !isPreviewPlaying }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            if (isPreviewPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Preescuchar",
                            tint = primaryColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (isPreviewPlaying) "Pausar" else "Preescuchar (${((endMs - startMs) / 1000)}s)",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 4. Fragment Selector (Letras vs Manual)
                if (parsedLyrics.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF161824))
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selectedTab == 0) primaryColor.copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable { selectedTab = 0 }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "Por Letras",
                                    color = if (selectedTab == 0) primaryColor else Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selectedTab == 1) primaryColor.copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable { selectedTab = 1 }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "Tiempo Manual",
                                    color = if (selectedTab == 1) primaryColor else Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            text = "${((endMs - startMs) / 1000)}s seleccionados",
                            color = primaryColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (selectedTab == 0 && parsedLyrics.isNotEmpty()) {
                        Text(
                            text = "Toca una línea para ampliar hasta ella · mantén pulsado para empezar de nuevo",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Fragment Selector Body
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF141624))
                        .padding(8.dp)
                ) {
                    if (selectedTab == 0 && parsedLyrics.isNotEmpty()) {
                        val listState = rememberLazyListState(
                            initialFirstVisibleItemIndex = max(0, selectedLyricStartIdx - 1)
                        )
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(parsedLyrics) { index, line ->
                                val isSelected = index in selectedLyricStartIdx..selectedLyricEndIdx

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) primaryColor.copy(alpha = 0.18f) else Color.Transparent
                                        )
                                        .combinedClickable(
                                            onClick = { handleLyricClick(index) },
                                            onLongClick = { handleLyricClick(index, startNew = true) }
                                        )
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Selection Circle Icon
                                    Icon(
                                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSelected) primaryColor else Color.White.copy(alpha = 0.3f),
                                        modifier = Modifier.size(16.dp)
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Text(
                                        text = formatTime(line.timeMs),
                                        color = if (isSelected) primaryColor else Color.White.copy(alpha = 0.4f),
                                        fontSize = 11.sp,
                                        modifier = Modifier.width(38.dp)
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = line.text,
                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.65f),
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (showTranslation && translatedLyrics != null) {
                                            val tText = translatedLyrics[line.timeMs]
                                            if (!tText.isNullOrBlank()) {
                                                Text(
                                                    text = tText,
                                                    color = if (isSelected) primaryColor.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.45f),
                                                    fontSize = 11.sp,
                                                    fontStyle = FontStyle.Italic,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Manual Slider Selector
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Inicio: ${formatTime(startMs)}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "Fin: ${formatTime(endMs)}",
                                    color = primaryColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Slider(
                                value = startMs.toFloat(),
                                onValueChange = { newStart ->
                                    val duration = endMs - startMs
                                    startMs = newStart.toLong()
                                    endMs = min(songDurationMs, startMs + duration)
                                },
                                valueRange = 0f..max(0L, songDurationMs - 5000L).toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = primaryColor,
                                    activeTrackColor = primaryColor,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                )
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(15, 30, 45, 60).forEach { sec ->
                                    val isCurrentDuration = ((endMs - startMs) / 1000L).toInt() == sec
                                    FilterChip(
                                        selected = isCurrentDuration,
                                        onClick = {
                                            endMs = min(songDurationMs, startMs + sec * 1000L)
                                        },
                                        label = { Text("${sec}s", fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = primaryColor,
                                            selectedLabelColor = Color.Black
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 5. Options Chips: Dedication, Translation, AMOLED & Waveform
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        FilterChip(
                            selected = dedicationText.isNotBlank(),
                            onClick = { showDedicationDialog = true },
                            label = {
                                Text(
                                    if (dedicationText.isNotBlank()) "💌 Dedicatoria" else "Dedicar",
                                    fontSize = 11.sp
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(13.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = primaryColor.copy(alpha = 0.25f),
                                selectedLabelColor = primaryColor,
                                selectedLeadingIconColor = primaryColor
                            )
                        )
                    }

                    if (!translatedLyrics.isNullOrEmpty()) {
                        item {
                            FilterChip(
                                selected = showTranslation,
                                onClick = { showTranslation = !showTranslation },
                                label = { Text("Traducción", fontSize = 11.sp) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Translate, contentDescription = null, modifier = Modifier.size(13.dp))
                                }
                            )
                        }
                    }

                    item {
                        FilterChip(
                            selected = isAmoledDark,
                            onClick = { isAmoledDark = !isAmoledDark },
                            label = { Text(if (isAmoledDark) "Fondo: AMOLED" else "Fondo: Color", fontSize = 11.sp) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Palette, contentDescription = null, modifier = Modifier.size(13.dp))
                            }
                        )
                    }

                    item {
                        FilterChip(
                            selected = showWaveform,
                            onClick = { showWaveform = !showWaveform },
                            label = { Text("Ondas", fontSize = 11.sp) },
                            leadingIcon = {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(13.dp))
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 6. Action Button: Generate & Share Video
                Button(
                    onClick = {
                        if (isGenerating) return@Button
                        isGenerating = true
                        isPreviewPlaying = false

                        coroutineScope.launch {
                            val artBitmap = albumArtBitmap
                            val config = ShareClipConfig(
                                songTitle = songTitle,
                                artistName = artistName,
                                albumTitle = albumTitle,
                                albumArtBitmap = artBitmap,
                                startMs = startMs,
                                endMs = endMs,
                                audioUri = Uri.parse(audioUriString),
                                lyricsLines = parsedLyrics,
                                translatedLyrics = translatedLyrics,
                                showTranslation = showTranslation,
                                dedicationText = dedicationText.takeIf { it.isNotBlank() },
                                isVertical = isVertical,
                                templateStyle = templateStyle,
                                isAmoledDark = isAmoledDark,
                                showWaveform = showWaveform,
                                primaryColor = primaryColor.toArgb(),
                                accentColor = secondaryColor.toArgb()
                            )

                            val result = VideoClipGenerator.generateClip(context, config) { progress ->
                                generationProgress = progress
                            }

                            isGenerating = false

                            result.onSuccess { generatedVideoFile ->
                                Toast.makeText(context, "¡Video generado con éxito!", Toast.LENGTH_SHORT).show()
                                shareVideoFile(context, generatedVideoFile, songTitle, artistName, dedicationText)
                                onDismiss()
                            }.onFailure { err ->
                                val msg = err.message ?: err.localizedMessage ?: err.javaClass.simpleName
                                Toast.makeText(context, "Error al generar video: $msg", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isGenerating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Renderizando historia... ${(generationProgress * 100).toInt()}%",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    } else {
                        Icon(Icons.Rounded.MovieFilter, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (dedicationText.isNotBlank()) "Generar y Dedicar Historia" else "Generar y Compartir Historia",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Dedication Dialog Modal
        if (showDedicationDialog) {
            var tempDedication by remember { mutableStateOf(dedicationText) }
            AlertDialog(
                onDismissRequest = { showDedicationDialog = false },
                containerColor = Color(0xFF181A28),
                shape = RoundedCornerShape(20.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Favorite, contentDescription = null, tint = primaryColor, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Dedicar este fragmento", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column {
                        Text(
                            "Agrega un mensaje o dedicatoria personalizada. Aparecerá en el encabezado del video y en el mensaje al compartir.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Suggestion Pills
                        Text("Sugerencias rápidas:", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val suggestions = listOf("Para ti ❤️", "Te dedico esta parte ✨", "Mi favorita 🎧", "Escucha esto 💫", "Con amor 💖")
                            items(suggestions) { tag ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .clickable { tempDedication = tag }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(tag, color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = tempDedication,
                            onValueChange = { if (it.length <= 45) tempDedication = it },
                            placeholder = { Text("Escribe un mensaje o para quién es...", fontSize = 13.sp, color = Color.White.copy(alpha = 0.4f)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedContainerColor = Color.Black.copy(alpha = 0.3f),
                                unfocusedContainerColor = Color.Black.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            dedicationText = tempDedication.trim()
                            showDedicationDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Guardar", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    if (dedicationText.isNotBlank()) {
                        TextButton(
                            onClick = {
                                dedicationText = ""
                                tempDedication = ""
                                showDedicationDialog = false
                            }
                        ) {
                            Text("Quitar", color = Color(0xFFFF5252))
                        }
                    } else {
                        TextButton(onClick = { showDedicationDialog = false }) {
                            Text("Cancelar", color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            )
        }
    }
}

// ==========================================
// PREVIEWS FOR EACH TEMPLATE STYLE
// ==========================================

@Composable
private fun LyricsStoryPreview(
    songTitle: String,
    artistName: String,
    albumArtBitmap: Bitmap?,
    parsedLyrics: List<com.kevshupp.kevmusicplayer.data.LyricLine>,
    translatedLyrics: Map<Long, String>?,
    showTranslation: Boolean,
    selectedStartIdx: Int,
    selectedEndIdx: Int,
    previewPosMs: Long,
    primaryColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Spotify Style Frosted Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.White.copy(alpha = 0.08f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Lyric Quotes
                Column {
                    if (parsedLyrics.isNotEmpty() && selectedStartIdx in parsedLyrics.indices) {
                        val endIdx = min(parsedLyrics.size - 1, selectedEndIdx)
                        val activeLineIdx = parsedLyrics.indexOfLast { it.timeMs <= previewPosMs }.coerceAtLeast(0)

                        for (i in selectedStartIdx..endIdx) {
                            val line = parsedLyrics[i]
                            val isCurrentSinging = i == activeLineIdx
                            Column(modifier = Modifier.padding(vertical = 3.dp)) {
                                Text(
                                    text = line.text,
                                    color = if (isCurrentSinging) Color.White else Color.White.copy(alpha = 0.5f),
                                    fontSize = if (isCurrentSinging) 15.sp else 13.sp,
                                    fontWeight = if (isCurrentSinging) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (showTranslation && translatedLyrics != null) {
                                    val trans = translatedLyrics[line.timeMs]
                                    if (!trans.isNullOrBlank()) {
                                        Text(
                                            text = trans,
                                            color = if (isCurrentSinging) primaryColor else Color.White.copy(alpha = 0.4f),
                                            fontSize = 11.sp,
                                            fontStyle = FontStyle.Italic,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "♫ Fragmento de música seleccionado",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Footer Song Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF282838))
                    ) {
                        if (albumArtBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = albumArtBitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = songTitle,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = artistName,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(36.dp))
    }
}

@Composable
private fun CanvasStoryPreview(
    songTitle: String,
    artistName: String,
    albumArtBitmap: Bitmap?,
    parsedLyrics: List<com.kevshupp.kevmusicplayer.data.LyricLine>,
    translatedLyrics: Map<Long, String>?,
    showTranslation: Boolean,
    previewPosMs: Long,
    isPlaying: Boolean,
    primaryColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(2.dp))

        // Large Artwork Card
        Box(
            modifier = Modifier
                .size(105.dp)
                .shadow(16.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF282838)),
            contentAlignment = Alignment.Center
        ) {
            if (albumArtBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = albumArtBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }

        // Title and Artist
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = songTitle,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artistName,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Active Singing Lyric
        if (parsedLyrics.isNotEmpty()) {
            val currentLine = parsedLyrics.findLast { it.timeMs <= previewPosMs } ?: parsedLyrics.first()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 14.dp)
            ) {
                Text(
                    text = "“${currentLine.text}”",
                    color = primaryColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
                if (showTranslation && translatedLyrics != null) {
                    val t = translatedLyrics[currentLine.timeMs]
                    if (!t.isNullOrBlank()) {
                        Text(
                            text = t,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            fontStyle = FontStyle.Italic,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                    }
                }
            }
        }

        // Waveform preview animation
        AnimatedWaveformBar(isPlaying = isPlaying, primaryColor = primaryColor)

        Spacer(modifier = Modifier.height(36.dp))
    }
}

@Composable
private fun VinylStoryPreview(
    songTitle: String,
    artistName: String,
    albumArtBitmap: Bitmap?,
    isPlaying: Boolean,
    primaryColor: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "VinylSpin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isPlaying) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "VinylRotation"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // Vinyl + Cover Sleeve Composite
        Box(
            modifier = Modifier
                .height(100.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Spinning Vinyl Disc popping out to the right
            Box(
                modifier = Modifier
                    .offset(x = 32.dp)
                    .size(90.dp)
                    .rotate(rotation)
                    .clip(CircleShape)
                    .background(Color(0xFF111116))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Vinyl grooves
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val radius = size.minDimension / 2f
                    drawCircle(Color.White.copy(alpha = 0.08f), radius * 0.8f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                    drawCircle(Color.White.copy(alpha = 0.08f), radius * 0.6f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                    drawCircle(Color.White.copy(alpha = 0.08f), radius * 0.4f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                }
                // Center Label
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(primaryColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (albumArtBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = albumArtBitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.Black))
                }
            }

            // Cover Sleeve Box (Left)
            Box(
                modifier = Modifier
                    .offset(x = (-24).dp)
                    .size(92.dp)
                    .shadow(12.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF282838)),
                contentAlignment = Alignment.Center
            ) {
                if (albumArtBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = albumArtBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
        }

        // Title and Artist
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = songTitle,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artistName,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(36.dp))
    }
}

@Composable
private fun AnimatedWaveformBar(isPlaying: Boolean, primaryColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "WaveformPulse")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isPlaying) 6.28f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WaveformPhase"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(20.dp)
    ) {
        for (i in 0 until 18) {
            val barHeight = if (isPlaying) {
                (4 + (sin(phase + i * 0.45f) + 1f) * 6).dp
            } else {
                5.dp
            }
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(primaryColor.copy(alpha = 0.75f))
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

private fun shareVideoFile(
    context: Context,
    videoFile: File,
    songTitle: String,
    artistName: String,
    dedicationText: String
) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            videoFile
        )
        val shareMessage = buildString {
            if (dedicationText.isNotBlank()) {
                val prefix = if (dedicationText.startsWith("💌") || dedicationText.startsWith("✨") || dedicationText.startsWith("💖")) "" else "💌 "
                appendLine("$prefix$dedicationText")
                appendLine()
            }
            appendLine("🎵 $songTitle - $artistName")
            append("✨ Compartido desde KevMusicPlayer")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, shareMessage)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, if (dedicationText.isNotBlank()) "Dedicar historia" else "Compartir historia"))
    } catch (e: Exception) {
        Toast.makeText(context, "No se pudo compartir el archivo: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
