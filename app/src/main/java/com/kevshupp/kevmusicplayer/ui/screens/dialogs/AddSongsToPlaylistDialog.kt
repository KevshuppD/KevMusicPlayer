package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel

@Composable
fun AddSongsToPlaylistDialog(
    playlistName: String,
    audioFiles: List<AudioFile>,
    viewModel: MediaBrowserViewModel?,
    onDismiss: () -> Unit
) {
    if (viewModel == null) return
    var activeSubTab by remember { mutableStateOf("Canción") }
    var searchQuery by remember { mutableStateOf("") }
    val currentSongsInPlaylist = remember(viewModel.playlists[playlistName]) {
        viewModel.playlists[playlistName] ?: emptyList()
    }
    val currentSongIds = remember(currentSongsInPlaylist) {
        currentSongsInPlaylist.map { it.id }.toSet()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Agregar a: $playlistName",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp)
            ) {
                // Category tabs Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Canción", "Álbum", "Artista", "Género").forEach { tab ->
                        val isSel = activeSubTab == tab
                        Surface(
                            onClick = { activeSubTab = tab },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            border = BorderStroke(1.5.dp, if (isSel) Color.Transparent else Color.White.copy(alpha = 0.15f))
                        ) {
                            Text(
                                text = tab,
                                color = if (isSel) Color.White else Color.White.copy(alpha = 0.7f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search Bar inside Dialog
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar...", color = Color.White.copy(alpha = 0.5f)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "Search", tint = Color.White.copy(alpha = 0.6f)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.6f))
                            }
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF1E213A),
                        unfocusedContainerColor = Color(0xFF1E213A),
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.White.copy(alpha = 0.6f)
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Items list
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (activeSubTab) {
                        "Canción" -> {
                            val availableSongs = audioFiles.filter { !currentSongIds.contains(it.id) }
                            val filteredSongs = if (searchQuery.isBlank()) {
                                availableSongs
                            } else {
                                availableSongs.filter {
                                    it.title.contains(searchQuery, ignoreCase = true) ||
                                    it.artist.contains(searchQuery, ignoreCase = true)
                                }
                            }

                            if (filteredSongs.isEmpty()) {
                                item {
                                    Text("No hay canciones disponibles.", color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 16.dp))
                                }
                            } else {
                                items(filteredSongs, key = { it.id }) { song ->
                                    Card(
                                        onClick = {
                                            viewModel.addSongToPlaylist(playlistName, song.id)
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(song.title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(song.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Icon(Icons.Rounded.AddCircle, contentDescription = "Add", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                        "Álbum" -> {
                            val albums = audioFiles.groupBy { it.album }
                            val filteredAlbumKeys = if (searchQuery.isBlank()) {
                                albums.keys.toList()
                            } else {
                                albums.keys.filter { it.contains(searchQuery, ignoreCase = true) }
                            }

                            if (filteredAlbumKeys.isEmpty()) {
                                item {
                                    Text("No hay álbumes disponibles.", color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 16.dp))
                                }
                            } else {
                                items(filteredAlbumKeys, key = { it }) { album ->
                                    val albumSongs = albums[album] ?: emptyList()
                                    val containsAll = albumSongs.all { currentSongIds.contains(it.id) }
                                    Card(
                                        onClick = {
                                            albumSongs.forEach { viewModel.addSongToPlaylist(playlistName, it.id) }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(album, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("${albumSongs.size} canciones", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                                            }
                                            Icon(
                                                imageVector = if (containsAll) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircle,
                                                contentDescription = "Add",
                                                tint = if (containsAll) Color(0xFF00E676) else MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        "Artista" -> {
                            val artists = audioFiles.groupBy { it.artist }
                            val filteredArtistKeys = if (searchQuery.isBlank()) {
                                artists.keys.toList()
                            } else {
                                artists.keys.filter { it.contains(searchQuery, ignoreCase = true) }
                            }

                            if (filteredArtistKeys.isEmpty()) {
                                item {
                                    Text("No hay artistas disponibles.", color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 16.dp))
                                }
                            } else {
                                items(filteredArtistKeys, key = { it }) { artist ->
                                    val artistSongs = artists[artist] ?: emptyList()
                                    val containsAll = artistSongs.all { currentSongIds.contains(it.id) }
                                    Card(
                                        onClick = {
                                            artistSongs.forEach { viewModel.addSongToPlaylist(playlistName, it.id) }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(artist, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("${artistSongs.size} canciones", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                                            }
                                            Icon(
                                                imageVector = if (containsAll) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircle,
                                                contentDescription = "Add",
                                                tint = if (containsAll) Color(0xFF00E676) else MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        "Género" -> {
                            val genres = audioFiles.groupBy { it.genre }
                            val filteredGenreKeys = if (searchQuery.isBlank()) {
                                genres.keys.toList()
                            } else {
                                genres.keys.filter { it.contains(searchQuery, ignoreCase = true) }
                            }

                            if (filteredGenreKeys.isEmpty()) {
                                item {
                                    Text("No hay géneros disponibles.", color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 16.dp))
                                }
                            } else {
                                items(filteredGenreKeys, key = { it }) { genre ->
                                    val genreSongs = genres[genre] ?: emptyList()
                                    val containsAll = genreSongs.all { currentSongIds.contains(it.id) }
                                    Card(
                                        onClick = {
                                            genreSongs.forEach { viewModel.addSongToPlaylist(playlistName, it.id) }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(genre, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("${genreSongs.size} canciones", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                                            }
                                            Icon(
                                                imageVector = if (containsAll) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircle,
                                                contentDescription = "Add",
                                                tint = if (containsAll) Color(0xFF00E676) else MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Listo", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF161829)
    )
}
