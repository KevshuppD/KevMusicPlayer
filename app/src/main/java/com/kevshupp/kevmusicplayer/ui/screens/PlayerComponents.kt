package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import com.kevshupp.kevmusicplayer.R
import com.kevshupp.kevmusicplayer.data.LyricLine

fun getAudioFileInfo(context: android.content.Context, uriString: String?): Pair<String, String> {
    if (uriString.isNullOrEmpty()) return Pair("MP3", "320 kbps")
    var extension = "MP3"
    var bitrate = "320 kbps"
    try {
        val uri = Uri.parse(uriString)
        context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val dataIndex = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.DATA)
                if (dataIndex != -1) {
                    val path = cursor.getString(dataIndex)
                    if (!path.isNullOrEmpty()) {
                        extension = path.substringAfterLast('.', "mp3").uppercase()
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    try {
        val uri = Uri.parse(uriString)
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val bitrateStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
        if (!bitrateStr.isNullOrEmpty()) {
            val bitrateKbps = bitrateStr.toInt() / 1000
            bitrate = "$bitrateKbps kbps"
        }
        retriever.release()
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    return Pair(extension, bitrate)
}

@Composable
fun ScrollingLyricsView(
    lyricLines: List<LyricLine>,
    currentPositionMs: Long,
    songTitle: String = "",
    songArtist: String = "",
    translatedLines: Map<Long, String>? = null,
    isTranslating: Boolean = false,
    onTranslateClick: () -> Unit = {},
    onTranslateLongClick: () -> Unit = {},
    onLineClick: (Long) -> Unit,
    onEditClick: () -> Unit,
    onSearchOnlineClick: () -> Unit,
    isSearchingOnline: Boolean,
    isInstrumental: Boolean = false,
    onMarkInstrumentalClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = remember(songTitle + songArtist) { LazyListState() }
    val context = LocalContext.current
    val systemLang = remember { context.resources.configuration.locales[0].language }
    val getLocalized = { es: String, en: String ->
        if (systemLang == "es") es else en
    }
    
    val activeIndex = remember(lyricLines, currentPositionMs) {
        lyricLines.indexOfLast { currentPositionMs >= it.timeMs }.coerceAtLeast(0)
    }

    val disableAnimations = com.kevshupp.kevmusicplayer.ui.theme.LocalDisableAnimations.current
    LaunchedEffect(activeIndex) {
        if (lyricLines.isNotEmpty() && activeIndex >= 0) {
            if (disableAnimations) {
                listState.scrollToItem(activeIndex)
            } else {
                listState.animateScrollToItem(activeIndex)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(32.dp))
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center
    ) {
        if (lyricLines.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                if (isSearchingOnline) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = getLocalized("Cargando letras...", "Loading lyrics..."),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                } else if (isInstrumental) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = getLocalized("Pista Instrumental", "Instrumental Track"),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = getLocalized("Esta canción ha sido marcada como instrumental (sin letra).", "This song has been marked as instrumental (no lyrics)."),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onSearchOnlineClick,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(getLocalized("Buscar Letras de nuevo", "Search Lyrics Again"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                } else {
                    Icon(
                        imageVector = Icons.Rounded.InterpreterMode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_lyrics),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onSearchOnlineClick,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.find_lyrics), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = onMarkInstrumentalClick,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(getLocalized("Marcar como Instrumental", "Mark as Instrumental"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 150.dp, bottom = 300.dp, start = 24.dp, end = 24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                itemsIndexed(lyricLines, key = { index, line -> "${line.timeMs}_$index" }) { index, line ->
                    val isActive = index == activeIndex
                    val translatedText = translatedLines?.get(line.timeMs)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLineClick(line.timeMs) }
                            .graphicsLayer {
                                val distance = Math.abs(index - activeIndex)
                                alpha = if (isActive) 1f else (0.4f - (distance * 0.05f)).coerceAtLeast(0.1f)
                                scaleX = if (isActive) 1.05f else 1f
                                scaleY = if (isActive) 1.05f else 1f
                            },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                            color = if (isActive) MaterialTheme.colorScheme.primary else Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (translatedText != null) {
                            Text(
                                text = translatedText,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Buttons Layer (Floating)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isTranslating) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (translatedLines != null) MaterialTheme.colorScheme.primary 
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                            .combinedClickable(
                                onClick = onTranslateClick,
                                onLongClick = onTranslateLongClick
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Translate,
                            contentDescription = stringResource(R.string.translate_lyrics),
                            tint = if (translatedLines != null) Color.Black else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onEditClick,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Edit Lyrics",
                        tint = Color.White
                    )
                }

                IconButton(
                    onClick = onSearchOnlineClick,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = "Search Online",
                        tint = Color.White
                    )
                }
            }
        }

        // Song Title and Artist Header overlay (drawn on top of everything inside the Box)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.9f),
                            Color.Black.copy(alpha = 0.7f),
                            Color.Transparent
                        )
                    )
                )
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = songTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = songArtist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

fun detectLanguage(text: String): String {
    // 1. Instant check for Korean Hangul characters
    if (text.contains(Regex("[\\uAC00-\\uD7A3\\u1100-\\u11FF\\u3130-\\u318F]"))) {
        return "ko"
    }
    // 2. Instant check for Japanese characters (Hiragana and Katakana)
    if (text.contains(Regex("[\\u3040-\\u309F\\u30A0-\\u30FF]"))) {
        return "ja"
    }

    val words = text.lowercase(java.util.Locale.ROOT)
        .split(Regex("[^a-záéíóúüñâêîôûàèìòùçãõ]"))
        .filter { it.isNotBlank() }
    
    val esWords = setOf("el", "la", "los", "las", "un", "una", "y", "en", "que", "de", "con", "por", "para", "como", "me", "te", "se", "lo", "mi", "tu")
    val enWords = setOf("the", "and", "of", "to", "a", "in", "is", "you", "that", "it", "he", "was", "for", "on", "are", "as", "with", "his", "they", "i", "your", "my", "me")
    val frWords = setOf("le", "la", "les", "et", "en", "un", "une", "des", "que", "qui", "dans", "pour", "par", "avec", "je", "tu", "il", "elle", "nous", "vous", "ils", "elles", "est", "sont")
    val ptWords = setOf("o", "a", "os", "as", "e", "em", "um", "uma", "que", "com", "por", "para", "como", "eu", "tu", "ele", "ela", "nós", "vós", "eles", "elas", "é", "são")
    
    var esScore = 0
    var enScore = 0
    var frScore = 0
    var ptScore = 0
    
    words.forEach { word ->
        if (esWords.contains(word)) esScore++
        if (enWords.contains(word)) enScore++
        if (frWords.contains(word)) frScore++
        if (ptWords.contains(word)) ptScore++
    }
    
    val max = maxOf(esScore, enScore, frScore, ptScore)
    return when {
        max == 0 -> "en" // Default to english if no words match
        max == esScore -> "es"
        max == enScore -> "en"
        max == frScore -> "fr"
        max == ptScore -> "pt"
        else -> "en"
    }
}
