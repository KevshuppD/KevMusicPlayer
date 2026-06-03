package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.LyricsRepository
import com.kevshupp.kevmusicplayer.data.LrcLibSearchResult
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import kotlinx.coroutines.launch

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
                                items(filteredSongs) { song ->
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
                                items(filteredAlbumKeys) { album ->
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
                                items(filteredArtistKeys) { artist ->
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
                                items(filteredGenreKeys) { genre ->
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

@Composable
fun TagEditorDialog(
    song: AudioFile,
    viewModel: MediaBrowserViewModel?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val systemLang = remember { context.resources.configuration.locales[0].language }
    val getLocalized = { es: String, en: String ->
        if (systemLang == "es") es else en
    }

    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var album by remember { mutableStateOf(song.album) }
    var genre by remember { mutableStateOf(song.genre) }

    // Online metadata search state
    var searchQuery by remember { mutableStateOf("${song.artist} ${song.title}") }
    var isSearchingOnline by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<LrcLibSearchResult>>(emptyList()) }
    var searchStatus by remember { mutableStateOf("") }

    var isSaving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = getLocalized("Editar Etiquetas", "Edit Metadata Tags"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White
                )
            }
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    Text(
                        text = getLocalized("Información del archivo local", "Local file information"),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                }

                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(getLocalized("Título", "Title"), color = Color.White.copy(alpha = 0.5f)) },
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
                }

                item {
                    OutlinedTextField(
                        value = artist,
                        onValueChange = { artist = it },
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
                }

                item {
                    OutlinedTextField(
                        value = album,
                        onValueChange = { album = it },
                        label = { Text(getLocalized("Álbum", "Album"), color = Color.White.copy(alpha = 0.5f)) },
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
                }

                item {
                    OutlinedTextField(
                        value = genre,
                        onValueChange = { genre = it },
                        label = { Text(getLocalized("Género", "Genre"), color = Color.White.copy(alpha = 0.5f)) },
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
                }

                item {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = getLocalized("Buscador de Metadatos En Línea", "Online Metadata Searcher"),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                }

                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(getLocalized("Término de búsqueda", "Search query"), color = Color.White.copy(alpha = 0.5f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        isSearchingOnline = true
                                        searchStatus = getLocalized("Buscando en LRCLIB...", "Searching LRCLIB...")
                                        val results = LyricsRepository.searchLyricsOptionsFromLrcLib("", searchQuery)
                                        searchResults = results
                                        isSearchingOnline = false
                                        if (results.isEmpty()) {
                                            searchStatus = getLocalized("No se encontraron coincidencias.", "No matches found.")
                                        } else {
                                            searchStatus = getLocalized("Se encontraron ${results.size} coincidencias.", "Found ${results.size} matches.")
                                        }
                                    }
                                },
                                enabled = searchQuery.isNotBlank() && !isSearchingOnline
                            ) {
                                if (isSearchingOnline) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    Icon(Icons.Rounded.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                if (searchStatus.isNotEmpty()) {
                    item {
                        Text(
                            text = searchStatus,
                            color = if (searchResults.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (searchResults.isNotEmpty()) {
                    items(searchResults) { result ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    title = result.trackName
                                    artist = result.artistName
                                    album = result.albumName
                                    searchResults = emptyList()
                                    searchStatus = getLocalized("Metadatos aplicados desde la búsqueda en línea", "Metadata applied from online search")
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(text = result.trackName, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                Text(text = "${result.artistName} • ${result.albumName}", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isSaving = true
                    viewModel?.updateSongMetadata(
                        context = context,
                        songId = song.id,
                        title = title,
                        artist = artist,
                        album = album,
                        genre = genre,
                        onSuccess = {
                            isSaving = false
                            android.widget.Toast.makeText(
                                context,
                                getLocalized("Etiquetas actualizadas con éxito", "Metadata tags updated successfully"),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            onDismiss()
                        },
                        onError = { error ->
                            isSaving = false
                            android.widget.Toast.makeText(
                                context,
                                "${getLocalized("Error al guardar etiquetas:", "Failed to save tags:")} ${error.localizedMessage}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(getLocalized("Guardar", "Save"), fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving
            ) {
                Text(getLocalized("Cancelar", "Cancel"), color = Color.White.copy(alpha = 0.6f))
            }
        },
        containerColor = Color(0xFF161829),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}
