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
    // Artista = principal + colaboradores ("Polima WestCoast, Pablo Chill-E"), editados por separado
    val splitter = remember(viewModel) {
        com.kevshupp.kevmusicplayer.data.ArtistSplitter(viewModel?.localAudioFiles?.map { it.artist }?.distinct().orEmpty())
    }
    var mainArtist by remember { mutableStateOf(splitter.split(song.artist).first()) }
    var guests by remember { mutableStateOf(splitter.split(song.artist).drop(1)) }
    var newGuest by remember { mutableStateOf("") }
    var guestStatus by remember { mutableStateOf<String?>(null) }
    var guestSearching by remember { mutableStateOf(false) }
    fun setArtist(field: String) {
        val parts = splitter.split(field)
        mainArtist = parts.first(); guests = parts.drop(1)
    }
    val artist = com.kevshupp.kevmusicplayer.data.LibraryNormalizer.withGuests(mainArtist.trim(), guests)
    var album by remember { mutableStateOf(song.album) }
    var genre by remember { mutableStateOf(song.genre) }

    // Online metadata search state
    var searchQuery by remember { mutableStateOf("${song.artist} ${song.title}") }
    // Solo para este diálogo: al volver a abrirlo manda el proveedor de Ajustes
    var searchProvider by remember { mutableStateOf(com.kevshupp.kevmusicplayer.data.MetadataProvider.current(context)) }
    var isSearchingOnline by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<com.kevshupp.kevmusicplayer.data.MetadataResult>>(emptyList()) }
    var searchStatus by remember { mutableStateOf("") }

    var isSaving by remember { mutableStateOf(false) }

    var selectedCoverBytes by remember { mutableStateOf<ByteArray?>(null) }
    var selectedCoverUrl by remember { mutableStateOf<String?>(null) }
    var applyCoverToEntireAlbum by remember { mutableStateOf(false) }
    
    // Cover search state
    var showCoverSearchSection by remember { mutableStateOf(false) }
    var coverSearchType by remember { mutableStateOf(com.kevshupp.kevmusicplayer.data.CoverSearchType.AUTO) }
    var coverSearchQuery by remember { mutableStateOf("${song.artist} ${song.title}".trim()) }
    var isSearchingCover by remember { mutableStateOf(false) }
    var coverResults by remember { mutableStateOf<List<com.kevshupp.kevmusicplayer.data.CoverSearchResult>>(emptyList()) }
    var coverSearchStatus by remember { mutableStateOf("") }

    val performSearch: (String, com.kevshupp.kevmusicplayer.data.CoverSearchType) -> Unit = { query, type ->
        scope.launch {
            isSearchingCover = true
            coverSearchStatus = getLocalized("Buscando portadas en Deezer...", "Searching covers on Deezer...")
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
                    // Search mode & query suggestions chips
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FilterChip(
                                    selected = coverSearchType == com.kevshupp.kevmusicplayer.data.CoverSearchType.AUTO,
                                    onClick = {
                                        coverSearchType = com.kevshupp.kevmusicplayer.data.CoverSearchType.AUTO
                                        val q = coverSearchQuery.ifBlank { "$title $artist".trim() }
                                        performSearch(q, com.kevshupp.kevmusicplayer.data.CoverSearchType.AUTO)
                                    },
                                    label = { Text(getLocalized("🌐 Todo", "🌐 All"), fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                                FilterChip(
                                    selected = coverSearchType == com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM,
                                    onClick = {
                                        coverSearchType = com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM
                                        val q = if (album.isNotBlank() && !album.contains("Unknown", ignoreCase = true)) {
                                            "$album $artist".trim()
                                        } else {
                                            coverSearchQuery.ifBlank { "$title $artist".trim() }
                                        }
                                        coverSearchQuery = q
                                        performSearch(q, com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM)
                                    },
                                    label = { Text(getLocalized("💿 Por Álbum", "💿 By Album"), fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                                FilterChip(
                                    selected = coverSearchType == com.kevshupp.kevmusicplayer.data.CoverSearchType.SONG,
                                    onClick = {
                                        coverSearchType = com.kevshupp.kevmusicplayer.data.CoverSearchType.SONG
                                        val q = "$title $artist".trim().ifBlank { coverSearchQuery }
                                        coverSearchQuery = q
                                        performSearch(q, com.kevshupp.kevmusicplayer.data.CoverSearchType.SONG)
                                    },
                                    label = { Text(getLocalized("🎵 Por Canción", "🎵 By Track"), fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                item {
                                    SuggestionChip(
                                        onClick = {
                                            val q = "$title $artist".trim()
                                            coverSearchQuery = q
                                            coverSearchType = com.kevshupp.kevmusicplayer.data.CoverSearchType.SONG
                                            performSearch(q, com.kevshupp.kevmusicplayer.data.CoverSearchType.SONG)
                                        },
                                        label = { Text("🎵 $title + $artist", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)) },
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                                if (album.isNotBlank() && !album.contains("Unknown", ignoreCase = true)) {
                                    item {
                                        SuggestionChip(
                                            onClick = {
                                                val q = "$album $artist".trim()
                                                coverSearchQuery = q
                                                coverSearchType = com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM
                                                performSearch(q, com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM)
                                            },
                                            label = { Text("💿 Álbum: $album", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)) },
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                    }
                                }
                                if (artist.isNotBlank() && !artist.contains("Unknown", ignoreCase = true)) {
                                    item {
                                        SuggestionChip(
                                            onClick = {
                                                coverSearchQuery = artist
                                                performSearch(artist, coverSearchType)
                                            },
                                            label = { Text("👤 $artist", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)) },
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = coverSearchQuery,
                            onValueChange = { coverSearchQuery = it },
                            label = { Text(getLocalized("Buscar en Deezer", "Search on Deezer"), color = Color.White.copy(alpha = 0.5f)) },
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
                                                    coverSearchStatus = getLocalized("Descargando portada en alta resolución...", "Downloading HD cover...")
                                                    val bytes = com.kevshupp.kevmusicplayer.data.CoverArtRepository.downloadCoverBytes(result.coverUrl)
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
                        value = mainArtist,
                        onValueChange = { mainArtist = it },
                        label = { Text(getLocalized("Artista principal", "Main artist"), color = Color.White.copy(alpha = 0.5f)) },
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
                    Column(Modifier.fillMaxWidth()) {
                        Text(getLocalized("Colaboradores", "Featured artists"), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                        if (guests.isNotEmpty()) {
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                guests.forEach { g ->
                                    InputChip(
                                        selected = false, onClick = { guests = guests - g },
                                        label = { Text(g, color = Color.White) },
                                        trailingIcon = { Icon(Icons.Rounded.Close, getLocalized("Quitar", "Remove"), Modifier.size(16.dp), tint = Color.White) }
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newGuest, onValueChange = { newGuest = it }, singleLine = true,
                                label = { Text(getLocalized("Añadir colaborador", "Add featured artist"), color = Color.White.copy(alpha = 0.5f)) },
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                                    cursorColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            IconButton(onClick = {
                                val g = newGuest.trim()
                                if (g.isNotEmpty() && guests.none { it.equals(g, true) } && !g.equals(mainArtist.trim(), true)) guests = guests + g
                                newGuest = ""
                            }) { Icon(Icons.Rounded.Add, getLocalized("Añadir", "Add"), tint = Color.White) }
                            IconButton(enabled = !guestSearching && title.isNotBlank(), onClick = {
                            guestSearching = true; guestStatus = null
                            scope.launch {
                                val found = runCatching {
                                    val match = com.kevshupp.kevmusicplayer.data.telegram.SongRepair.findMatch(song.copy(title = title, artist = artist))
                                        ?: return@runCatching null
                                    val a = com.kevshupp.kevmusicplayer.data.telegram.DeezerTrackSearch.trackDetails(match.id).optJSONArray("contributors")
                                    val names = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                                    match.artist to names
                                }.getOrNull()
                                guestSearching = false
                                if (found == null) {
                                    guestStatus = getLocalized("No se encontró en Deezer", "Not found on Deezer")
                                } else {
                                    val (main, names) = found
                                    mainArtist = main
                                    guests = names.filter { !it.equals(main, true) }.distinct()
                                    guestStatus = if (guests.isEmpty()) getLocalized("Deezer no lista colaboradores", "No featured artists on Deezer")
                                        else getLocalized("Colaboradores de Deezer aplicados", "Featured artists applied from Deezer")
                                }
                            }
                            }) {
                                if (guestSearching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Rounded.Search, getLocalized("Buscar colaboradores en Deezer", "Find featured artists on Deezer"), tint = Color.White)
                            }
                        }
                        guestStatus?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp) }
                    }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.kevshupp.kevmusicplayer.data.MetadataProvider.entries.forEach { provider ->
                            FilterChip(
                                selected = searchProvider == provider,
                                onClick = {
                                    searchProvider = provider
                                    searchResults = emptyList()
                                    searchStatus = ""
                                },
                                label = { Text(provider.label, fontSize = 12.sp) }
                            )
                        }
                    }
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
                                        searchStatus = getLocalized("Buscando en ${searchProvider.label}...", "Searching ${searchProvider.label}...")
                                        var failed = false
                                        val results = try {
                                            com.kevshupp.kevmusicplayer.data.MetadataSearch.search(searchProvider, searchQuery.trim(), artist, song.duration.toLong())
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            failed = true
                                            emptyList()
                                        }
                                        searchResults = results.distinct() // la clave de la lista no admite repetidos
                                        isSearchingOnline = false
                                        if (failed) {
                                            searchStatus = getLocalized("No se pudo conectar con ${searchProvider.label}. Inténtalo más tarde o cambia de proveedor en Ajustes.", "Could not reach ${searchProvider.label}. Try later or change provider in Settings.")
                                        } else if (results.isEmpty()) {
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
                    items(searchResults, key = { "${it.title}|${it.artist}|${it.album}" }) { result ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    title = result.title
                                    setArtist(result.artist)
                                    if (result.deezerId > 0) {
                                        guestSearching = true; guestStatus = null
                                        scope.launch {
                                            val names = runCatching {
                                                val a = com.kevshupp.kevmusicplayer.data.telegram.DeezerTrackSearch.trackDetails(result.deezerId).optJSONArray("contributors")
                                                (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                                            }.getOrNull()
                                            guestSearching = false
                                            if (names != null) {
                                                guests = (guests + names).filter { !it.equals(mainArtist.trim(), true) }.distinctBy { it.lowercase() }
                                                if (guests.isNotEmpty()) guestStatus = getLocalized("Colaboradores de Deezer aplicados", "Featured artists applied from Deezer")
                                            }
                                        }
                                    }
                                    if (result.album.isNotBlank()) album = result.album
                                    searchResults = emptyList()
                                    searchStatus = getLocalized("Metadatos aplicados desde la búsqueda en línea", "Metadata applied from online search")
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(text = result.title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                Text(text = "${result.artist} • ${result.album}", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
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
                    com.kevshupp.kevmusicplayer.data.KnownArtists.add(listOf(mainArtist) + guests)
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
