package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import com.kevshupp.kevmusicplayer.playback.DuplicateGroup
import com.kevshupp.kevmusicplayer.playback.DuplicateItem
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextAlign
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

    var selectedCoverBytes by remember { mutableStateOf<ByteArray?>(null) }
    var selectedCoverUrl by remember { mutableStateOf<String?>(null) }
    var applyCoverToEntireAlbum by remember { mutableStateOf(false) }
    
    // iTunes search state
    var showCoverSearchSection by remember { mutableStateOf(false) }
    var coverSearchQuery by remember { mutableStateOf("${song.artist} ${song.title}") }
    var isSearchingCover by remember { mutableStateOf(false) }
    var coverResults by remember { mutableStateOf<List<com.kevshupp.kevmusicplayer.data.ITunesCoverSearchResult>>(emptyList()) }
    var coverSearchStatus by remember { mutableStateOf("") }

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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Card with current artwork
                        val initialArtBytes = rememberAlbumArt(song.uriString)
                        val displayBytes = selectedCoverBytes ?: initialArtBytes
                        
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(90.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (displayBytes != null) {
                                    androidx.compose.ui.platform.LocalContext.current.let { _ ->
                                        coil.compose.SubcomposeAsyncImage(
                                            model = displayBytes,
                                            contentDescription = "Cover Art",
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                            loading = {
                                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                            },
                                            error = {
                                                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                                            }
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.4f),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Button(
                                onClick = { showCoverSearchSection = !showCoverSearchSection },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    contentColor = MaterialTheme.colorScheme.primary
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.ImageSearch, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (showCoverSearchSection) getLocalized("Ocultar Buscador", "Hide Search") else getLocalized("Buscar Portada", "Search Cover"),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            if (selectedCoverBytes != null) {
                                TextButton(
                                    onClick = {
                                        selectedCoverBytes = null
                                        selectedCoverUrl = null
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Rounded.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(getLocalized("Restablecer", "Reset"), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { applyCoverToEntireAlbum = !applyCoverToEntireAlbum }
                                        .padding(vertical = 4.dp)
                                ) {
                                    Checkbox(
                                        checked = applyCoverToEntireAlbum,
                                        onCheckedChange = { applyCoverToEntireAlbum = it },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = MaterialTheme.colorScheme.primary,
                                            uncheckedColor = Color.White.copy(alpha = 0.6f)
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = getLocalized("Aplicar portada al álbum entero", "Apply cover to entire album"),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                if (showCoverSearchSection) {
                    item {
                        OutlinedTextField(
                            value = coverSearchQuery,
                            onValueChange = { coverSearchQuery = it },
                            label = { Text(getLocalized("Buscar portada en iTunes", "Search cover on iTunes"), color = Color.White.copy(alpha = 0.5f)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            isSearchingCover = true
                                            coverSearchStatus = getLocalized("Buscando portadas...", "Searching covers...")
                                            val results = com.kevshupp.kevmusicplayer.data.LyricsRepository.searchCoversFromITunes(coverSearchQuery)
                                            coverResults = results
                                            isSearchingCover = false
                                            if (results.isEmpty()) {
                                                coverSearchStatus = getLocalized("No se encontraron portadas.", "No covers found.")
                                            } else {
                                                coverSearchStatus = getLocalized("Se encontraron ${results.size} portadas.", "Found ${results.size} covers.")
                                            }
                                        }
                                    },
                                    enabled = coverSearchQuery.isNotBlank() && !isSearchingCover
                                ) {
                                    if (isSearchingCover) {
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

                    if (coverSearchStatus.isNotEmpty()) {
                        item {
                            Text(
                                text = coverSearchStatus,
                                color = if (coverResults.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (coverResults.isNotEmpty()) {
                        item {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                items(coverResults) { result ->
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                        modifier = Modifier
                                            .width(120.dp)
                                            .clickable {
                                                scope.launch {
                                                    coverSearchStatus = getLocalized("Descargando portada...", "Downloading cover...")
                                                    val bytes = com.kevshupp.kevmusicplayer.data.LyricsRepository.downloadCoverBytes(result.coverUrl)
                                                    if (bytes != null) {
                                                        selectedCoverBytes = bytes
                                                        selectedCoverUrl = result.coverUrl
                                                        coverSearchStatus = getLocalized("Portada descargada y lista", "Cover downloaded and ready")
                                                        showCoverSearchSection = false
                                                        coverResults = emptyList()
                                                    } else {
                                                        coverSearchStatus = getLocalized("Error al descargar la portada.", "Failed to download cover.")
                                                    }
                                                }
                                            }
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                                            Card(
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.size(90.dp)
                                            ) {
                                                androidx.compose.ui.platform.LocalContext.current.let { _ ->
                                                    coil.compose.SubcomposeAsyncImage(
                                                        model = result.coverUrl,
                                                        contentDescription = null,
                                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize(),
                                                        loading = {
                                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = result.albumName.takeIf { it.isNotEmpty() } ?: result.trackName,
                                                fontSize = 9.sp,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Text(
                                                text = result.artistName,
                                                fontSize = 8.sp,
                                                color = Color.White.copy(alpha = 0.6f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
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
                    items(searchResults, key = { it.id }) { result ->
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
                        coverBytes = selectedCoverBytes,
                        onSuccess = {
                            if (applyCoverToEntireAlbum && selectedCoverBytes != null && viewModel != null) {
                                viewModel.updateAlbumCover(
                                    context = context,
                                    albumName = album,
                                    coverBytes = selectedCoverBytes!!,
                                    onSuccess = {
                                        isSaving = false
                                        android.widget.Toast.makeText(
                                            context,
                                            getLocalized("Etiquetas y portada del álbum actualizadas con éxito", "Metadata and album cover updated successfully"),
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                        onDismiss()
                                    },
                                    onError = { error ->
                                        isSaving = false
                                        android.widget.Toast.makeText(
                                            context,
                                            "${getLocalized("Error al guardar portada del álbum:", "Failed to save album cover:")} ${error.localizedMessage}",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                        onDismiss()
                                    }
                                )
                            } else {
                                isSaving = false
                                android.widget.Toast.makeText(
                                    context,
                                    getLocalized("Etiquetas actualizadas con éxito", "Metadata tags updated successfully"),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                onDismiss()
                            }
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

@Composable
fun AlbumCoverEditorDialog(
    albumName: String,
    viewModel: MediaBrowserViewModel?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val systemLang = remember { context.resources.configuration.locales[0].language }
    val getLocalized = { es: String, en: String ->
        if (systemLang == "es") es else en
    }

    var isSaving by remember { mutableStateOf(false) }

    var selectedCoverBytes by remember { mutableStateOf<ByteArray?>(null) }
    var selectedCoverUrl by remember { mutableStateOf<String?>(null) }
    
    // iTunes search state
    var showCoverSearchSection by remember { mutableStateOf(true) } // Open by default for album covers
    var coverSearchQuery by remember { mutableStateOf(albumName) }
    var isSearchingCover by remember { mutableStateOf(false) }
    var coverResults by remember { mutableStateOf<List<com.kevshupp.kevmusicplayer.data.ITunesCoverSearchResult>>(emptyList()) }
    var coverSearchStatus by remember { mutableStateOf("") }

    val coverPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        selectedCoverBytes = bytes
                        selectedCoverUrl = null
                        coverSearchStatus = getLocalized("Portada local seleccionada", "Local cover selected")
                    }
                } catch (e: Exception) {
                    coverSearchStatus = getLocalized("Error al cargar la imagen local", "Error loading local image")
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = getLocalized("Editar Portada del Álbum", "Edit Album Cover"),
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
                        text = getLocalized("Álbum: $albumName", "Album: $albumName"),
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Card with current/selected artwork
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(90.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (selectedCoverBytes != null) {
                                    coil.compose.SubcomposeAsyncImage(
                                        model = selectedCoverBytes,
                                        contentDescription = "Cover Art",
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                        loading = {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                        },
                                        error = {
                                            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                                        }
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Image,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.4f),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Button(
                                onClick = { coverPickerLauncher.launch("image/*") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    contentColor = MaterialTheme.colorScheme.primary
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = getLocalized("Elegir de Galería", "Choose from Gallery"),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            if (selectedCoverBytes != null) {
                                TextButton(
                                    onClick = {
                                        selectedCoverBytes = null
                                        selectedCoverUrl = null
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Rounded.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(getLocalized("Restablecer", "Reset"), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = coverSearchQuery,
                        onValueChange = { coverSearchQuery = it },
                        label = { Text(getLocalized("Buscar portada en iTunes", "Search cover on iTunes"), color = Color.White.copy(alpha = 0.5f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        isSearchingCover = true
                                        coverSearchStatus = getLocalized("Buscando portadas...", "Searching covers...")
                                        val results = com.kevshupp.kevmusicplayer.data.LyricsRepository.searchCoversFromITunes(coverSearchQuery)
                                        coverResults = results
                                        isSearchingCover = false
                                        if (results.isEmpty()) {
                                            coverSearchStatus = getLocalized("No se encontraron portadas.", "No covers found.")
                                        } else {
                                            coverSearchStatus = getLocalized("Se encontraron ${results.size} portadas.", "Found ${results.size} covers.")
                                        }
                                    }
                                },
                                enabled = coverSearchQuery.isNotBlank() && !isSearchingCover
                            ) {
                                if (isSearchingCover) {
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

                if (coverSearchStatus.isNotEmpty()) {
                    item {
                        Text(
                            text = coverSearchStatus,
                            color = if (coverResults.isNotEmpty() || selectedCoverBytes != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (coverResults.isNotEmpty()) {
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            items(coverResults) { result ->
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                    modifier = Modifier
                                        .width(120.dp)
                                        .clickable {
                                            scope.launch {
                                                coverSearchStatus = getLocalized("Descargando portada...", "Downloading cover...")
                                                val bytes = com.kevshupp.kevmusicplayer.data.LyricsRepository.downloadCoverBytes(result.coverUrl)
                                                if (bytes != null) {
                                                    selectedCoverBytes = bytes
                                                    selectedCoverUrl = result.coverUrl
                                                    coverSearchStatus = getLocalized("Portada descargada y lista", "Cover downloaded and ready")
                                                    coverResults = emptyList()
                                                } else {
                                                    coverSearchStatus = getLocalized("Error al descargar la portada.", "Failed to download cover.")
                                                }
                                            }
                                        }
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                                        Card(
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.size(90.dp)
                                        ) {
                                            coil.compose.SubcomposeAsyncImage(
                                                model = result.coverUrl,
                                                contentDescription = null,
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                                loading = {
                                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = result.albumName.takeIf { it.isNotEmpty() } ?: result.trackName,
                                            fontSize = 9.sp,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            text = result.artistName,
                                            fontSize = 8.sp,
                                            color = Color.White.copy(alpha = 0.6f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
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
                onClick = {
                    val bytes = selectedCoverBytes
                    if (bytes != null && viewModel != null) {
                        isSaving = true
                        coverSearchStatus = getLocalized("Guardando portada para todo el álbum...", "Saving cover for the entire album...")
                        viewModel.updateAlbumCover(
                            context = context,
                            albumName = albumName,
                            coverBytes = bytes,
                            onSuccess = {
                                isSaving = false
                                android.widget.Toast.makeText(
                                    context,
                                    getLocalized("Portada del álbum actualizada con éxito", "Album cover updated successfully"),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                onDismiss()
                            },
                            onError = { err ->
                                isSaving = false
                                coverSearchStatus = getLocalized("Error al guardar: ${err.message}", "Save error: ${err.message}")
                            }
                        )
                    }
                },
                enabled = selectedCoverBytes != null && !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text(getLocalized("Aplicar al Álbum", "Apply to Album"), fontWeight = FontWeight.Bold, color = Color.Black)
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

@Composable
fun AlbumEditorDialog(
    albumName: String,
    viewModel: MediaBrowserViewModel?,
    onDismiss: (String?) -> Unit
) {
    if (viewModel == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val systemLang = remember { context.resources.configuration.locales[0].language }
    val getLocalized = { es: String, en: String ->
        if (systemLang == "es") es else en
    }

    // Find the first song belonging to this album to pre-fill the artist
    val firstSongInAlbum = remember {
        derivedStateOf {
            viewModel.localAudioFiles.find { it.album.trim().equals(albumName.trim(), ignoreCase = true) }
        }
    }.value

    var albumTitleInput by remember { mutableStateOf(albumName) }
    var albumArtistInput by remember { mutableStateOf(firstSongInAlbum?.artist ?: "") }

    var isSaving by remember { mutableStateOf(false) }

    var selectedCoverBytes by remember { mutableStateOf<ByteArray?>(null) }
    var selectedCoverUrl by remember { mutableStateOf<String?>(null) }
    
    // iTunes search state
    var showCoverSearchSection by remember { mutableStateOf(false) }
    var coverSearchQuery by remember { mutableStateOf(albumName) }
    var isSearchingCover by remember { mutableStateOf(false) }
    var coverResults by remember { mutableStateOf<List<com.kevshupp.kevmusicplayer.data.ITunesCoverSearchResult>>(emptyList()) }
    var coverSearchStatus by remember { mutableStateOf("") }

    val coverPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        selectedCoverBytes = bytes
                        selectedCoverUrl = null
                        coverSearchStatus = getLocalized("Portada local seleccionada", "Local cover selected")
                    }
                } catch (e: Exception) {
                    coverSearchStatus = getLocalized("Error al cargar la imagen local", "Error loading local image")
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss(null) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = getLocalized("Editar Información del Álbum", "Edit Album Info"),
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
                    OutlinedTextField(
                        value = albumTitleInput,
                        onValueChange = { albumTitleInput = it },
                        label = { Text(getLocalized("Título del Álbum", "Album Title"), color = Color.White.copy(alpha = 0.5f)) },
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
                        value = albumArtistInput,
                        onValueChange = { albumArtistInput = it },
                        label = { Text(getLocalized("Artista del Álbum", "Album Artist"), color = Color.White.copy(alpha = 0.5f)) },
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
                        text = getLocalized("Portada del Álbum", "Album Cover"),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                }

                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = { coverPickerLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                                contentColor = MaterialTheme.colorScheme.secondary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(getLocalized("Galería", "Gallery"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { showCoverSearchSection = !showCoverSearchSection },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.ImageSearch, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (showCoverSearchSection) getLocalized("Ocultar Buscador", "Hide Search") else getLocalized("Buscar Portada", "Search Cover"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (selectedCoverBytes != null) {
                    item {
                        TextButton(
                            onClick = {
                                selectedCoverBytes = null
                                selectedCoverUrl = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(getLocalized("Restablecer portada", "Reset cover"), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (showCoverSearchSection) {
                    item {
                        OutlinedTextField(
                            value = coverSearchQuery,
                            onValueChange = { coverSearchQuery = it },
                            label = { Text(getLocalized("Buscar portada en iTunes", "Search cover on iTunes"), color = Color.White.copy(alpha = 0.5f)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            isSearchingCover = true
                                            coverSearchStatus = getLocalized("Buscando portadas...", "Searching covers...")
                                            val results = com.kevshupp.kevmusicplayer.data.LyricsRepository.searchCoversFromITunes(coverSearchQuery)
                                            coverResults = results
                                            isSearchingCover = false
                                            if (results.isEmpty()) {
                                                coverSearchStatus = getLocalized("No se encontraron portadas.", "No covers found.")
                                            } else {
                                                coverSearchStatus = getLocalized("Se encontraron ${results.size} portadas.", "Found ${results.size} covers.")
                                            }
                                        }
                                    },
                                    enabled = coverSearchQuery.isNotBlank() && !isSearchingCover
                                ) {
                                    if (isSearchingCover) {
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

                    if (coverSearchStatus.isNotEmpty()) {
                        item {
                            Text(
                                text = coverSearchStatus,
                                color = if (coverResults.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (coverResults.isNotEmpty()) {
                        item {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp)
                            ) {
                                items(coverResults) { result ->
                                    val isSelected = selectedCoverUrl == result.coverUrl
                                    val border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                                    Card(
                                        shape = RoundedCornerShape(8.dp),
                                        border = border,
                                        modifier = Modifier
                                            .width(90.dp)
                                            .fillMaxHeight()
                                            .clickable {
                                                selectedCoverUrl = result.coverUrl
                                                scope.launch {
                                                    isSaving = true
                                                    coverSearchStatus = getLocalized("Descargando imagen...", "Downloading image...")
                                                    val bytes = com.kevshupp.kevmusicplayer.data.LyricsRepository.downloadCoverBytes(result.coverUrl)
                                                    selectedCoverBytes = bytes
                                                    isSaving = false
                                                    if (bytes != null) {
                                                        coverSearchStatus = getLocalized("Portada descargada y seleccionada", "Cover downloaded and selected")
                                                    } else {
                                                        coverSearchStatus = getLocalized("Error al descargar la imagen", "Failed to download image")
                                                    }
                                                }
                                            }
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.padding(4.dp)
                                        ) {
                                            coil.compose.SubcomposeAsyncImage(
                                                model = result.coverUrl,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(60.dp)
                                                    .clip(RoundedCornerShape(4.dp)),
                                                loading = {
                                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                                    }
                                                },
                                                error = {
                                                    Icon(Icons.Rounded.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                                                }
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = result.albumName.takeIf { it.isNotEmpty() } ?: result.trackName,
                                                fontSize = 9.sp,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Text(
                                                text = result.artistName,
                                                fontSize = 8.sp,
                                                color = Color.White.copy(alpha = 0.6f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
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
            Button(
                onClick = {
                    isSaving = true
                    viewModel.updateAlbumMetadata(
                        context = context,
                        oldAlbumName = albumName,
                        newAlbumName = albumTitleInput,
                        newArtist = albumArtistInput,
                        coverBytes = selectedCoverBytes,
                        onSuccess = {
                            isSaving = false
                            android.widget.Toast.makeText(
                                context,
                                getLocalized("Álbum actualizado con éxito", "Album updated successfully"),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            onDismiss(albumTitleInput)
                        },
                        onError = { error ->
                            isSaving = false
                            android.widget.Toast.makeText(
                                context,
                                "${getLocalized("Error al guardar álbum:", "Failed to save album:")} ${error.localizedMessage}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                enabled = !isSaving && albumTitleInput.isNotBlank()
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Black)
                } else {
                    Text(getLocalized("Guardar", "Save"), fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onDismiss(null) },
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

@Composable
fun DuplicateFinderDialog(
    viewModel: MediaBrowserViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val systemLang = remember { context.resources.configuration.locales[0].language }
    val getLocalized = { es: String, en: String ->
        if (systemLang == "es") es else en
    }

    var scanState by remember { mutableStateOf<DuplicateScanState>(DuplicateScanState.Scanning) }
    val selectedIds = remember { mutableStateMapOf<Long, Boolean>() }

    LaunchedEffect(Unit) {
        val dupGroups = viewModel.findDuplicates(context)
        scanState = DuplicateScanState.Success(dupGroups)
    }

    LaunchedEffect(scanState) {
        if (scanState is DuplicateScanState.Success) {
            val groups = (scanState as DuplicateScanState.Success).groups
            groups.forEach { group ->
                group.duplicates.forEach { dup ->
                    selectedIds[dup.file.id] = true
                }
            }
        }
    }

    val selectedCount = selectedIds.values.count { it }
    val sizeSavedBytes = remember(selectedIds.toMap(), scanState) {
        if (scanState is DuplicateScanState.Success) {
            val groups = (scanState as DuplicateScanState.Success).groups
            groups.flatMap { it.duplicates }
                .filter { selectedIds[it.file.id] == true }
                .sumOf { it.sizeBytes }
        } else {
            0L
        }
    }
    val sizeSavedMB = sizeSavedBytes / (1024f * 1024f)
    val sizeSavedText = String.format("%.2f MB", sizeSavedMB)

    var showConfirmDelete by remember { mutableStateOf(false) }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = {
                Text(
                    text = getLocalized("Confirmar Eliminación", "Confirm Deletion"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = getLocalized(
                        "Se eliminarán físicamente $selectedCount archivos seleccionados de tu almacenamiento ($sizeSavedText).\n\nEsta acción no se puede deshacer. ¿Estás seguro?",
                        "This will physically delete $selectedCount selected files from your storage ($sizeSavedText).\n\nThis action cannot be undone. Are you sure?"
                    ),
                    color = Color.White.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDelete = false
                        scanState = DuplicateScanState.Deleting(selectedCount)
                        val toDeleteList = selectedIds.filter { it.value }.keys.toList()
                        viewModel.deleteSongs(context, toDeleteList) {
                            scanState = DuplicateScanState.Complete(selectedCount, sizeSavedBytes)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(getLocalized("Eliminar", "Delete"), color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) {
                    Text(getLocalized("Cancelar", "Cancel"), color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF1E213A),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    if (scanState is DuplicateScanState.Deleting) {
        Dialog(onDismissRequest = {}) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161829)),
                modifier = Modifier.padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = getLocalized("Eliminando archivos duplicados...", "Deleting duplicate files..."),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (scanState is DuplicateScanState.Complete) {
        val completeState = scanState as DuplicateScanState.Complete
        AlertDialog(
            onDismissRequest = {
                scanState = DuplicateScanState.Scanning
                onDismiss()
            },
            title = {
                Text(
                    text = getLocalized("¡Eliminación Completada!", "Deletion Complete!"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = getLocalized(
                        "Se han eliminado correctamente ${completeState.deletedCount} archivos duplicados.\n\nEspacio liberado: ${String.format("%.2f", completeState.sizeSavedBytes / (1024.0 * 1024.0))} MB.",
                        "Successfully deleted ${completeState.deletedCount} duplicate files.\n\nSpace freed: ${String.format("%.2f", completeState.sizeSavedBytes / (1024.0 * 1024.0))} MB."
                    ),
                    color = Color.White.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scanState = DuplicateScanState.Scanning
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(getLocalized("Aceptar", "OK"), color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF161829),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    AlertDialog(
        onDismissRequest = {
            if (scanState !is DuplicateScanState.Deleting) {
                onDismiss()
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = getLocalized("Buscador de Duplicados", "Duplicate Finder"),
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
                    .heightIn(max = 480.dp)
            ) {
                when (val state = scanState) {
                    is DuplicateScanState.Scanning -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = getLocalized("Analizando biblioteca...", "Analyzing library..."),
                                color = Color.White
                            )
                        }
                    }
                    is DuplicateScanState.Success -> {
                        val groups = state.groups
                        if (groups.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LibraryMusic,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.4f),
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = getLocalized("No se encontraron canciones duplicadas.", "No duplicate songs found."),
                                    color = Color.White.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = getLocalized(
                                        "Se conservará el archivo original y se eliminarán las copias seleccionadas.",
                                        "The original file will be kept and selected copies will be deleted."
                                    ),
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )

                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(groups) { group ->
                                        Card(
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Text(
                                                    text = group.original.file.title,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = group.original.file.artist,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))

                                                // Original to keep
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.CheckCircle,
                                                        contentDescription = null,
                                                        tint = Color(0xFF00E676),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = getLocalized("Conservar original:", "Keep original:"),
                                                            fontSize = 10.sp,
                                                            color = Color(0xFF00E676),
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            text = group.original.path,
                                                            fontSize = 9.sp,
                                                            color = Color.White.copy(alpha = 0.5f),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }

                                                HorizontalDivider(
                                                    color = Color.White.copy(alpha = 0.08f),
                                                    modifier = Modifier.padding(vertical = 6.dp)
                                                )

                                                // Duplicates to delete
                                                group.duplicates.forEach { duplicate ->
                                                    val isChecked = selectedIds[duplicate.file.id] ?: false
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { selectedIds[duplicate.file.id] = !isChecked }
                                                            .padding(vertical = 2.dp)
                                                    ) {
                                                        Checkbox(
                                                            checked = isChecked,
                                                            onCheckedChange = { selectedIds[duplicate.file.id] = it },
                                                            colors = CheckboxDefaults.colors(
                                                                checkedColor = MaterialTheme.colorScheme.error,
                                                                uncheckedColor = Color.White.copy(alpha = 0.4f)
                                                            ),
                                                            modifier = Modifier.scale(0.85f)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                modifier = Modifier.fillMaxWidth()
                                                            ) {
                                                                Text(
                                                                    text = getLocalized("Eliminar copia:", "Delete copy:"),
                                                                    fontSize = 10.sp,
                                                                    color = MaterialTheme.colorScheme.error,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                                val sizeMB = duplicate.sizeBytes / (1024.0 * 1024.0)
                                                                Text(
                                                                    text = String.format("%.2f MB", sizeMB),
                                                                    fontSize = 10.sp,
                                                                    color = Color.White.copy(alpha = 0.7f),
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                            Text(
                                                                text = duplicate.path,
                                                                fontSize = 9.sp,
                                                                color = Color.White.copy(alpha = 0.5f),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = getLocalized("Seleccionados: $selectedCount", "Selected: $selectedCount"),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = getLocalized("Liberar: $sizeSavedText", "Free: $sizeSavedText"),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            val state = scanState
            if (state is DuplicateScanState.Success && state.groups.isNotEmpty()) {
                Button(
                    onClick = { showConfirmDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = selectedCount > 0
                ) {
                    Text(getLocalized("Eliminar", "Delete"), color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else if (state is DuplicateScanState.Success && state.groups.isEmpty()) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(getLocalized("Aceptar", "OK"), color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            val state = scanState
            if (state is DuplicateScanState.Success && state.groups.isNotEmpty()) {
                TextButton(onClick = onDismiss) {
                    Text(getLocalized("Cancelar", "Cancel"), color = Color.White.copy(alpha = 0.6f))
                }
            }
        },
        containerColor = Color(0xFF161829),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}

sealed class DuplicateScanState {
    object Scanning : DuplicateScanState()
    data class Success(val groups: List<DuplicateGroup>) : DuplicateScanState()
    data class Deleting(val count: Int) : DuplicateScanState()
    data class Complete(val deletedCount: Int, val sizeSavedBytes: Long) : DuplicateScanState()
}
