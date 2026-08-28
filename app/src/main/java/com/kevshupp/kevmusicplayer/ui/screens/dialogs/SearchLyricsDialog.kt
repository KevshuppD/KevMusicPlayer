package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.LyricsRepository
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import kotlinx.coroutines.launch

@Composable
fun SearchLyricsDialog(
    song: AudioFile,
    viewModel: MediaBrowserViewModel?,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var searchArtist by remember(song) { mutableStateOf(song.artist) }
    var searchTitle by remember(song) { mutableStateOf(song.title) }
    var isSearchingOnline by remember { mutableStateOf(false) }
    var searchLyricsResults by remember { mutableStateOf<List<com.kevshupp.kevmusicplayer.data.LrcLibSearchResult>>(emptyList()) }
    var searchStatusMessage by remember { mutableStateOf("") }

    val locale = java.util.Locale.getDefault().language
    val getLocalized = { es: String, en: String -> if (locale == "es") es else en }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                getLocalized("Buscar Letras en Línea", "Search Lyrics Online"),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = searchArtist,
                    onValueChange = { searchArtist = it },
                    label = { Text(getLocalized("Artista", "Artist"), color = Color.White.copy(alpha = 0.5f)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
                OutlinedTextField(
                    value = searchTitle,
                    onValueChange = { searchTitle = it },
                    label = { Text(getLocalized("Título de Canción", "Song Title"), color = Color.White.copy(alpha = 0.5f)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
                
                Button(
                    onClick = {
                        scope.launch {
                            searchStatusMessage = getLocalized("Buscando en la base de datos...", "Searching LRCLIB database...")
                            isSearchingOnline = true
                            val results = LyricsRepository.searchLyricsOptionsFromLrcLib(searchArtist, searchTitle)
                            searchLyricsResults = results
                            if (results.isNotEmpty()) {
                                searchStatusMessage = getLocalized("Se encontraron ${results.size} resultados.", "Found ${results.size} results.")
                            } else {
                                searchStatusMessage = getLocalized("No se encontraron letras. Intenta refinar la búsqueda.", "No lyrics found. Try refining the query!")
                            }
                            isSearchingOnline = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSearchingOnline
                ) {
                    if (isSearchingOnline) {
                        CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(getLocalized("Buscar Coincidencias", "Search Matches"), fontWeight = FontWeight.Bold)
                    }
                }

                if (searchStatusMessage.isNotEmpty()) {
                    Text(
                        text = searchStatusMessage,
                        color = if (searchLyricsResults.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                if (searchLyricsResults.isNotEmpty()) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = getLocalized("SELECCIONA UNA LETRA:", "SELECT LYRICS TO APPLY:"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(searchLyricsResults, key = { it.id }) { result ->
                            val isSynced = result.syncedLyrics != null
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.White.copy(alpha = 0.05f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val lyricsToApply = result.syncedLyrics ?: result.plainLyrics
                                        if (!lyricsToApply.isNullOrEmpty()) {
                                            viewModel?.updateSongLyrics(song.id, lyricsToApply)
                                            onDismiss()
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = result.trackName,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "${result.artistName} • ${result.albumName}",
                                            color = Color.White.copy(alpha = 0.5f),
                                            fontSize = 11.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSynced) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                else MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = if (isSynced) getLocalized("Sincro", "Synced") else getLocalized("Texto", "Plain"),
                                            color = if (isSynced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSearchingOnline
            ) {
                Text(getLocalized("Cancelar", "Cancel"), color = Color.White.copy(alpha = 0.6f))
            }
        },
        containerColor = Color(0xFF161829),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}
