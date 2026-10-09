package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel

@Composable
fun ShortSongsDialog(
    viewModel: MediaBrowserViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val systemLang = remember { context.resources.configuration.locales[0].language }
    val getLocalized = { es: String, en: String -> if (systemLang == "es") es else en }

    var thresholdSeconds by remember { mutableIntStateOf(60) } // Default threshold: 60s
    val allSongs = viewModel.localAudioFiles.toList()

    val shortSongs = remember(allSongs, thresholdSeconds) {
        allSongs.filter { it.duration in 1000L..(thresholdSeconds * 1000L) }
            .sortedBy { it.duration }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.HourglassBottom,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = getLocalized("Canciones Cortas / Incompletas", "Short / Incomplete Songs"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                Text(
                    text = getLocalized(
                        "Encuentra canciones con duración inusualmente corta (posibles descargas incompletas o audios truncados).",
                        "Find songs with unusually short duration (possible incomplete downloads or truncated audio)."
                    ),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Threshold Selector Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = getLocalized("Límite:", "Threshold:"),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    listOf(30, 60, 90, 120).forEach { seconds ->
                        FilterChip(
                            selected = thresholdSeconds == seconds,
                            onClick = { thresholdSeconds = seconds },
                            label = { Text("< ${seconds}s", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.Black,
                                containerColor = Color.White.copy(alpha = 0.08f),
                                labelColor = Color.White
                            ),
                            border = null
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (shortSongs.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = getLocalized("¡Excelente! No hay canciones menores a ${thresholdSeconds}s.", "Great! No songs under ${thresholdSeconds}s found."),
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = getLocalized("${shortSongs.size} encontradas:", "${shortSongs.size} found:"),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        TextButton(
                            onClick = {
                                val textToCopy = shortSongs.joinToString("\n") { "${it.title} - ${it.artist}" }
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Short Songs List", textToCopy)
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(
                                    context,
                                    getLocalized("Lista copiada al portapapeles (${shortSongs.size} canciones)", "List copied to clipboard (${shortSongs.size} songs)"),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(getLocalized("Copiar lista", "Copy list"), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shortSongs, key = { it.id }) { song ->
                            val durationSec = (song.duration / 1000).toInt()
                            val minutes = durationSec / 60
                            val seconds = durationSec % 60
                            val durationFormatted = String.format("%d:%02d", minutes, seconds)

                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = song.artist,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            text = durationFormatted,
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))

                                    IconButton(
                                        onClick = {
                                            val query = "${song.title} ${song.artist}".trim()
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("Song Name", query)
                                            clipboard.setPrimaryClip(clip)
                                            android.widget.Toast.makeText(
                                                context,
                                                getLocalized("Copiado: $query", "Copied: $query"),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ContentCopy,
                                            contentDescription = "Copy Song",
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(getLocalized("Cerrar", "Close"), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF161829),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}
