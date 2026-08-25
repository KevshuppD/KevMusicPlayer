package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.kevshupp.kevmusicplayer.ui.screens.rememberAlbumArt
import kotlinx.coroutines.launch

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
                            shape = if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(12.dp) else androidx.compose.ui.graphics.RectangleShape,
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
                                    text = if (showCoverSearchSection) getLocalized("Ocultar Buscador", "Hide Search") else getLocalized("Buscar en Internet", "Search Online"),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

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
                                    text = getLocalized("Elegir de Galería", "Select from Gallery"),
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
                                                shape = if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(8.dp) else androidx.compose.ui.graphics.RectangleShape,
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
                                        val results = LyricsRepository.searchLyricsOptionsFromLrcLib(artist, searchQuery)
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
                            if (applyCoverToEntireAlbum && selectedCoverBytes != null) {
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
