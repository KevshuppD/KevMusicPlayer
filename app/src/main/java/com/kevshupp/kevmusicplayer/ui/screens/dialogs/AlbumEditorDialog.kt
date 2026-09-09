package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import kotlinx.coroutines.launch

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
    
    // Cover search state
    var showCoverSearchSection by remember { mutableStateOf(false) }
    var coverSearchQuery by remember { 
        mutableStateOf(if (albumArtistInput.isNotBlank() && !albumArtistInput.contains("Unknown", ignoreCase = true)) "$albumName $albumArtistInput" else albumName) 
    }
    var isSearchingCover by remember { mutableStateOf(false) }
    var coverResults by remember { mutableStateOf<List<com.kevshupp.kevmusicplayer.data.CoverSearchResult>>(emptyList()) }
    var coverSearchStatus by remember { mutableStateOf("") }

    val performSearch: (String) -> Unit = { query ->
        scope.launch {
            isSearchingCover = true
            coverSearchStatus = getLocalized("Buscando portadas de álbum en Deezer e iTunes...", "Searching album covers on Deezer & iTunes...")
            val results = com.kevshupp.kevmusicplayer.data.CoverArtRepository.searchCovers(
                query = query,
                type = com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM
            )
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
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                        ) {
                            if (albumArtistInput.isNotBlank() && !albumArtistInput.contains("Unknown", ignoreCase = true)) {
                                item {
                                    SuggestionChip(
                                        onClick = {
                                            val q = "$albumTitleInput $albumArtistInput".trim()
                                            coverSearchQuery = q
                                            performSearch(q)
                                        },
                                        label = { Text("$albumTitleInput + $albumArtistInput", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)) },
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                            item {
                                SuggestionChip(
                                    onClick = {
                                        coverSearchQuery = albumTitleInput
                                        performSearch(albumTitleInput)
                                    },
                                    label = { Text(getLocalized("Solo Álbum: $albumTitleInput", "Album only: $albumTitleInput"), fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)) },
                                    shape = RoundedCornerShape(8.dp)
                                )
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
                                    onClick = { performSearch(coverSearchQuery) },
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
                                                    val bytes = com.kevshupp.kevmusicplayer.data.CoverArtRepository.downloadCoverBytes(result.coverUrl)
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
                                            Box(
                                                modifier = Modifier.size(60.dp),
                                                contentAlignment = Alignment.BottomEnd
                                            ) {
                                                coil.compose.SubcomposeAsyncImage(
                                                    model = result.coverUrl,
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .clip(if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(4.dp) else androidx.compose.ui.graphics.RectangleShape),
                                                    loading = {
                                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                                        }
                                                    },
                                                    error = {
                                                        Icon(Icons.Rounded.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                                                    }
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(3.dp),
                                                    color = if (result.source == "Deezer") Color(0xFF9B51E0) else Color(0xFFFF2D55),
                                                    modifier = Modifier.padding(2.dp)
                                                ) {
                                                    Text(
                                                        text = result.source,
                                                        fontSize = 7.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
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
