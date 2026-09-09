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
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import kotlinx.coroutines.launch

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
    
    // Cover search state
    var showCoverSearchSection by remember { mutableStateOf(true) }
    var coverSearchType by remember { mutableStateOf(com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM) }
    
    val albumArtist = remember {
        viewModel?.localAudioFiles?.find { it.album.trim().equals(albumName.trim(), ignoreCase = true) }?.artist ?: ""
    }
    
    var coverSearchQuery by remember { 
        mutableStateOf(if (albumArtist.isNotBlank() && !albumArtist.contains("Unknown", ignoreCase = true)) "$albumName $albumArtist" else albumName) 
    }
    var isSearchingCover by remember { mutableStateOf(false) }
    var coverResults by remember { mutableStateOf<List<com.kevshupp.kevmusicplayer.data.CoverSearchResult>>(emptyList()) }
    var coverSearchStatus by remember { mutableStateOf("") }

    val performSearch: (String, com.kevshupp.kevmusicplayer.data.CoverSearchType) -> Unit = { query, type ->
        scope.launch {
            isSearchingCover = true
            coverSearchStatus = getLocalized("Buscando portadas en Deezer e iTunes...", "Searching covers on Deezer & iTunes...")
            val results = com.kevshupp.kevmusicplayer.data.CoverArtRepository.searchCovers(query, type = type)
            coverResults = results
            isSearchingCover = false
            if (results.isEmpty()) {
                coverSearchStatus = getLocalized("No se encontraron portadas.", "No covers found.")
            } else {
                coverSearchStatus = getLocalized("Se encontraron ${results.size} portadas.", "Found ${results.size} covers.")
            }
        }
    }

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
                            shape = if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(12.dp) else androidx.compose.ui.graphics.RectangleShape,
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

                // Quick suggestions & type chips
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = coverSearchType == com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM,
                                onClick = {
                                    coverSearchType = com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM
                                    if (coverSearchQuery.isNotBlank()) {
                                        performSearch(coverSearchQuery, com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM)
                                    }
                                },
                                label = { Text(getLocalized("💿 Modo Álbum", "💿 Album Mode"), fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            FilterChip(
                                selected = coverSearchType == com.kevshupp.kevmusicplayer.data.CoverSearchType.AUTO,
                                onClick = {
                                    coverSearchType = com.kevshupp.kevmusicplayer.data.CoverSearchType.AUTO
                                    if (coverSearchQuery.isNotBlank()) {
                                        performSearch(coverSearchQuery, com.kevshupp.kevmusicplayer.data.CoverSearchType.AUTO)
                                    }
                                },
                                label = { Text(getLocalized("🌐 Todo (Deezer+iTunes)", "🌐 All"), fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                        // Suggestion buttons
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (albumArtist.isNotBlank() && !albumArtist.contains("Unknown", ignoreCase = true)) {
                                item {
                                    SuggestionChip(
                                        onClick = {
                                            val q = "$albumName $albumArtist"
                                            coverSearchQuery = q
                                            performSearch(q, coverSearchType)
                                        },
                                        label = { Text("$albumName + $albumArtist", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)) },
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                            item {
                                SuggestionChip(
                                    onClick = {
                                        coverSearchQuery = albumName
                                        performSearch(albumName, coverSearchType)
                                    },
                                    label = { Text(getLocalized("Solo Álbum: $albumName", "Album only: $albumName"), fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = coverSearchQuery,
                        onValueChange = { coverSearchQuery = it },
                        label = { Text(getLocalized("Buscar en Deezer / iTunes", "Search on Deezer / iTunes"), color = Color.White.copy(alpha = 0.5f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(
                                onClick = { performSearch(coverSearchQuery, coverSearchType) },
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
                                                coverSearchStatus = getLocalized("Descargando portada en alta resolución...", "Downloading HD cover...")
                                                val bytes = com.kevshupp.kevmusicplayer.data.CoverArtRepository.downloadCoverBytes(result.coverUrl)
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
                                        Box(
                                            modifier = Modifier.size(90.dp),
                                            contentAlignment = Alignment.BottomEnd
                                        ) {
                                            Card(
                                                shape = if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(8.dp) else androidx.compose.ui.graphics.RectangleShape,
                                                modifier = Modifier.fillMaxSize()
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
                                            // Source badge
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (result.source == "Deezer") Color(0xFF9B51E0) else Color(0xFFFF2D55),
                                                modifier = Modifier.padding(4.dp)
                                            ) {
                                                Text(
                                                    text = result.source,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
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
