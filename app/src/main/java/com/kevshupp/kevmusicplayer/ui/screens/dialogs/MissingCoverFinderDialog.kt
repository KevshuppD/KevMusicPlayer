package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.ITunesCoverSearchResult
import com.kevshupp.kevmusicplayer.data.LyricsRepository
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import com.kevshupp.kevmusicplayer.playback.getPhysicalPath
import com.kevshupp.kevmusicplayer.ui.screens.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume

data class MissingAlbumItem(
    val albumName: String,
    val artistName: String,
    val songs: List<AudioFile>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissingCoverFinderDialog(
    viewModel: MediaBrowserViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val systemLang = remember { context.resources.configuration.locales[0].language }
    val isEs = systemLang == "es"
    val getLocalized = { es: String, en: String -> if (isEs) es else en }

    var isScanning by remember { mutableStateOf(true) }
    var scanProgress by remember { mutableFloatStateOf(0f) }
    var scanStatusText by remember { mutableStateOf("") }

    val missingAlbums = remember { mutableStateListOf<MissingAlbumItem>() }
    val missingSongs = remember { mutableStateListOf<AudioFile>() }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Albums, 1: Songs
    var searchQuery by remember { mutableStateOf("") }

    // Batch Auto-Download state
    var isAutoDownloading by remember { mutableStateOf(false) }
    var autoDownloadProgress by remember { mutableFloatStateOf(0f) }
    var autoDownloadStatusText by remember { mutableStateOf("") }
    var autoDownloadSuccessCount by remember { mutableIntStateOf(0) }

    // Online search modal state
    var activeSearchTargetAlbum by remember { mutableStateOf<MissingAlbumItem?>(null) }
    var activeSearchTargetSong by remember { mutableStateOf<AudioFile?>(null) }
    var onlineSearchQuery by remember { mutableStateOf("") }
    var isSearchingOnline by remember { mutableStateOf(false) }
    var onlineSearchResults by remember { mutableStateOf<List<ITunesCoverSearchResult>>(emptyList()) }
    var onlineSearchStatus by remember { mutableStateOf("") }

    // Local file picker state
    var targetAlbumForPicker by remember { mutableStateOf<MissingAlbumItem?>(null) }
    var targetSongForPicker by remember { mutableStateOf<AudioFile?>(null) }

    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val albumTarget = targetAlbumForPicker
                        val songTarget = targetSongForPicker
                        if (albumTarget != null) {
                            viewModel.updateAlbumCover(
                                context = context,
                                albumName = albumTarget.albumName,
                                coverBytes = bytes,
                                targetSongIds = albumTarget.songs.map { it.id },
                                onSuccess = {
                                    scope.launch(Dispatchers.Main) {
                                        missingAlbums.removeAll { it.albumName.equals(albumTarget.albumName, ignoreCase = true) }
                                        val songIdsToRemove = albumTarget.songs.map { it.id }.toSet()
                                        missingSongs.removeAll { songIdsToRemove.contains(it.id) }
                                        Toast.makeText(context, getLocalized("Portada de álbum actualizada", "Album cover updated"), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onError = { err ->
                                    scope.launch(Dispatchers.Main) {
                                        Toast.makeText(context, getLocalized("Error: ${err.message}", "Error: ${err.message}"), Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        } else if (songTarget != null) {
                            viewModel.updateSongMetadata(
                                context = context,
                                songId = songTarget.id,
                                title = songTarget.title,
                                artist = songTarget.artist,
                                album = songTarget.album,
                                genre = songTarget.genre,
                                coverBytes = bytes,
                                onSuccess = {
                                    scope.launch(Dispatchers.Main) {
                                        missingSongs.removeAll { it.id == songTarget.id }
                                        val albumName = songTarget.album.trim()
                                        if (albumName.isNotBlank()) {
                                            val remaining = missingSongs.filter { it.album.trim().equals(albumName, ignoreCase = true) }
                                            if (remaining.isEmpty()) {
                                                missingAlbums.removeAll { it.albumName.equals(albumName, ignoreCase = true) }
                                            }
                                        }
                                        Toast.makeText(context, getLocalized("Portada de canción actualizada", "Song cover updated"), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onError = { err ->
                                    scope.launch(Dispatchers.Main) {
                                        Toast.makeText(context, getLocalized("Error: ${err.message}", "Error: ${err.message}"), Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    targetAlbumForPicker = null
                    targetSongForPicker = null
                }
            }
        } else {
            targetAlbumForPicker = null
            targetSongForPicker = null
        }
    }

    // Function to run discovery scan
    val runScan: () -> Unit = {
        scope.launch {
            isScanning = true
            scanProgress = 0f
            missingAlbums.clear()
            missingSongs.clear()

            withContext(Dispatchers.IO) {
                val allSongs = viewModel.localAudioFiles.toList()
                val total = allSongs.size
                if (total == 0) {
                    isScanning = false
                    return@withContext
                }

                val noCoverSongsList = mutableListOf<AudioFile>()
                val albumCoverMap = mutableMapOf<String, Boolean>() // albumName -> hasCover

                for (i in allSongs.indices) {
                    val song = allSongs[i]
                    scanProgress = (i + 1).toFloat() / total
                    scanStatusText = "${i + 1} / $total"

                    // Check if song has album art
                    val hasArt = checkSongHasCover(context, song.uriString)
                    if (!hasArt) {
                        noCoverSongsList.add(song)
                    }

                    val albumKey = song.album.trim()
                    if (albumKey.isNotBlank()) {
                        val currentAlbumStatus = albumCoverMap[albumKey] ?: false
                        albumCoverMap[albumKey] = currentAlbumStatus || hasArt
                    }
                }

                // Identify albums without any cover
                val missingAlbumsList = mutableListOf<MissingAlbumItem>()
                val songsByAlbum = allSongs.groupBy { it.album.trim() }
                for ((album, hasCover) in albumCoverMap) {
                    if (!hasCover && album.isNotBlank()) {
                        val albumSongs = songsByAlbum[album] ?: emptyList()
                        val artist = albumSongs.firstOrNull()?.artist ?: "Unknown Artist"
                        missingAlbumsList.add(MissingAlbumItem(album, artist, albumSongs))
                    }
                }

                withContext(Dispatchers.Main) {
                    missingSongs.addAll(noCoverSongsList)
                    missingAlbums.addAll(missingAlbumsList.sortedBy { it.albumName.lowercase() })
                    isScanning = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        runScan()
    }

    // Batch Auto-Download function (tab-aware: downloads albums on Tab 0, individual songs on Tab 1)
    val startBatchAutoDownload = {
        scope.launch {
            isAutoDownloading = true
            autoDownloadProgress = 0f
            autoDownloadSuccessCount = 0

            withContext(Dispatchers.IO) {
                if (selectedTab == 0) {
                    // Download for ALBUMS
                    val albumsToProcess = missingAlbums.toList()
                    val total = albumsToProcess.size
                    var processed = 0

                    for (item in albumsToProcess) {
                        processed++
                        autoDownloadProgress = processed.toFloat() / total.coerceAtLeast(1)
                        autoDownloadStatusText = getLocalized(
                            "Buscando ($processed/$total): ${item.albumName}",
                            "Searching ($processed/$total): ${item.albumName}"
                        )

                        try {
                            val searchQuery1 = "${item.albumName} ${item.artistName}".trim()
                            var searchResults = com.kevshupp.kevmusicplayer.data.CoverArtRepository.searchCovers(
                                query = searchQuery1,
                                type = com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM
                            )
                            if (searchResults.isEmpty()) {
                                searchResults = com.kevshupp.kevmusicplayer.data.CoverArtRepository.searchCovers(
                                    query = item.albumName.trim(),
                                    type = com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM
                                )
                            }
                            val topMatch = searchResults.firstOrNull()

                            if (topMatch != null && topMatch.coverUrl.isNotBlank()) {
                                val bytes = com.kevshupp.kevmusicplayer.data.CoverArtRepository.downloadCoverBytes(topMatch.coverUrl)
                                if (bytes != null && bytes.isNotEmpty()) {
                                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                                        viewModel.updateAlbumCover(
                                            context = context,
                                            albumName = item.albumName,
                                            coverBytes = bytes,
                                            targetSongIds = item.songs.map { it.id },
                                            onSuccess = { if (cont.isActive) cont.resume(true) },
                                            onError = { if (cont.isActive) cont.resume(false) }
                                        )
                                    }
                                    if (success) {
                                        autoDownloadSuccessCount++
                                        withContext(Dispatchers.Main) {
                                            missingAlbums.removeAll { it.albumName.equals(item.albumName, ignoreCase = true) }
                                            val songIdsToRemove = item.songs.map { it.id }.toSet()
                                            missingSongs.removeAll { songIdsToRemove.contains(it.id) }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        delay(150) // Rate limit prevention
                    }
                } else {
                    // Download for INDIVIDUAL SONGS (Tab 1)
                    val songsToProcess = missingSongs.toList()
                    val total = songsToProcess.size
                    var processed = 0

                    for (song in songsToProcess) {
                        processed++
                        autoDownloadProgress = processed.toFloat() / total.coerceAtLeast(1)
                        autoDownloadStatusText = getLocalized(
                            "Buscando ($processed/$total): ${song.title}",
                            "Searching ($processed/$total): ${song.title}"
                        )

                        try {
                            // 1. Try track search
                            var searchResults = com.kevshupp.kevmusicplayer.data.CoverArtRepository.searchCovers(
                                query = "${song.title} ${song.artist}".trim(),
                                type = com.kevshupp.kevmusicplayer.data.CoverSearchType.SONG
                            )
                            // 2. Try album search if song belongs to an album
                            if (searchResults.isEmpty() && song.album.isNotBlank() && !song.album.contains("Unknown", ignoreCase = true)) {
                                searchResults = com.kevshupp.kevmusicplayer.data.CoverArtRepository.searchCovers(
                                    query = "${song.album} ${song.artist}".trim(),
                                    type = com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM
                                )
                            }
                            // 3. Try general query with song title
                            if (searchResults.isEmpty()) {
                                searchResults = com.kevshupp.kevmusicplayer.data.CoverArtRepository.searchCovers(
                                    query = song.title.trim(),
                                    type = com.kevshupp.kevmusicplayer.data.CoverSearchType.AUTO
                                )
                            }

                            val topMatch = searchResults.firstOrNull()
                            if (topMatch != null && topMatch.coverUrl.isNotBlank()) {
                                val bytes = com.kevshupp.kevmusicplayer.data.CoverArtRepository.downloadCoverBytes(topMatch.coverUrl)
                                if (bytes != null && bytes.isNotEmpty()) {
                                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                                        viewModel.updateSongMetadata(
                                            context = context,
                                            songId = song.id,
                                            title = song.title,
                                            artist = song.artist,
                                            album = song.album,
                                            genre = song.genre,
                                            coverBytes = bytes,
                                            onSuccess = { if (cont.isActive) cont.resume(true) },
                                            onError = { if (cont.isActive) cont.resume(false) }
                                        )
                                    }
                                    if (success) {
                                        autoDownloadSuccessCount++
                                        withContext(Dispatchers.Main) {
                                            missingSongs.removeAll { it.id == song.id }
                                            val albumName = song.album.trim()
                                            if (albumName.isNotBlank()) {
                                                val remaining = missingSongs.filter { it.album.trim().equals(albumName, ignoreCase = true) }
                                                if (remaining.isEmpty()) {
                                                    missingAlbums.removeAll { it.albumName.equals(albumName, ignoreCase = true) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        delay(150)
                    }
                }

                withContext(Dispatchers.Main) {
                    isAutoDownloading = false
                    autoDownloadStatusText = getLocalized(
                        "Completado: $autoDownloadSuccessCount portadas actualizadas",
                        "Completed: $autoDownloadSuccessCount covers updated"
                    )
                }
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isScanning && !isAutoDownloading) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF0F111A),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddPhotoAlternate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = getLocalized("Portadas Faltantes", "Missing Album Art"),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            Text(
                                text = if (isScanning) {
                                    getLocalized("Escaneando biblioteca...", "Scanning library...")
                                } else {
                                    getLocalized(
                                        "${missingAlbums.size} álbumes · ${missingSongs.size} canciones",
                                        "${missingAlbums.size} albums · ${missingSongs.size} tracks"
                                    )
                                },
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }

                    IconButton(
                        onClick = { if (!isScanning && !isAutoDownloading) onDismiss() },
                        enabled = !isScanning && !isAutoDownloading
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isScanning) {
                    // Scanning State View
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            CircularProgressIndicator(
                                progress = { scanProgress },
                                strokeWidth = 4.dp,
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.White.copy(alpha = 0.1f),
                                modifier = Modifier.size(64.dp)
                            )
                            Text(
                                text = getLocalized("Analizando carátulas...", "Analyzing cover artwork..."),
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = scanStatusText,
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    // Controls: Tabs & Batch Action
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            label = {
                                Text(
                                    getLocalized("Álbumes (${missingAlbums.size})", "Albums (${missingAlbums.size})"),
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.Black,
                                containerColor = Color.White.copy(alpha = 0.08f),
                                labelColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        FilterChip(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            label = {
                                Text(
                                    getLocalized("Canciones (${missingSongs.size})", "Songs (${missingSongs.size})"),
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.Black,
                                containerColor = Color.White.copy(alpha = 0.08f),
                                labelColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Batch Auto-Download Card / Action
                    val hasItemsInCurrentTab = if (selectedTab == 0) missingAlbums.isNotEmpty() else missingSongs.isNotEmpty()
                    if (hasItemsInCurrentTab || isAutoDownloading) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (isAutoDownloading) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = autoDownloadStatusText,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        LinearProgressIndicator(
                                            progress = { autoDownloadProgress },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            color = MaterialTheme.colorScheme.primary,
                                            trackColor = Color.White.copy(alpha = 0.1f)
                                        )
                                    }
                                } else {
                                    val count = if (selectedTab == 0) missingAlbums.size else missingSongs.size
                                    val titleText = if (selectedTab == 0) {
                                        getLocalized("Auto-descarga de Álbumes", "Batch Download Albums")
                                    } else {
                                        getLocalized("Auto-descarga de Canciones", "Batch Download Songs")
                                    }
                                    val subText = if (selectedTab == 0) {
                                        getLocalized("Descarga portadas para $count álbumes desde iTunes", "Download covers for $count albums from iTunes")
                                    } else {
                                        getLocalized("Descarga portadas para $count canciones desde iTunes", "Download covers for $count tracks from iTunes")
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = titleText,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = subText,
                                                color = Color.White.copy(alpha = 0.6f),
                                                fontSize = 11.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = { startBatchAutoDownload() },
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.CloudDownload,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = Color.Black
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = getLocalized("Descargar ($count)", "Download ($count)"),
                                                color = Color.Black,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Search / Filter Input
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                getLocalized("Filtrar por nombre...", "Filter by name..."),
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 13.sp
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = Color.White.copy(alpha = 0.6f))
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                            focusedContainerColor = Color.White.copy(alpha = 0.04f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.04f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Content List
                    if (selectedTab == 0) {
                        // Albums Tab
                        val filteredAlbums = remember(missingAlbums.toList(), searchQuery) {
                            if (searchQuery.isBlank()) missingAlbums.toList()
                            else missingAlbums.filter {
                                it.albumName.contains(searchQuery, ignoreCase = true) ||
                                it.artistName.contains(searchQuery, ignoreCase = true)
                            }
                        }

                        if (filteredAlbums.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        text = if (missingAlbums.isEmpty()) {
                                            getLocalized("¡Todos los álbumes tienen portada!", "All albums have cover art!")
                                        } else {
                                            getLocalized("No se encontraron coincidencias", "No matches found")
                                        },
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                items(filteredAlbums, key = { it.albumName }) { item ->
                                    Card(
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color.White.copy(alpha = 0.08f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Album,
                                                    contentDescription = null,
                                                    tint = Color.White.copy(alpha = 0.4f),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(12.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.albumName,
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${item.artistName} · ${item.songs.size} ${if (isEs) "canciones" else "tracks"}",
                                                    color = Color.White.copy(alpha = 0.5f),
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(6.dp))

                                            // Online Search Button
                                            IconButton(
                                                onClick = {
                                                    activeSearchTargetAlbum = item
                                                    activeSearchTargetSong = null
                                                    onlineSearchQuery = "${item.albumName} ${item.artistName}".trim()
                                                    onlineSearchResults = emptyList()
                                                    onlineSearchStatus = ""
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Search,
                                                    contentDescription = "Search Online",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }

                                            // Gallery Picker Button
                                            IconButton(
                                                onClick = {
                                                    targetAlbumForPicker = item
                                                    targetSongForPicker = null
                                                    coverPickerLauncher.launch("image/*")
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.AddPhotoAlternate,
                                                    contentDescription = "Pick Local Image",
                                                    tint = Color.White.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Songs Tab
                        val filteredSongs = remember(missingSongs.toList(), searchQuery) {
                            if (searchQuery.isBlank()) missingSongs.toList()
                            else missingSongs.filter {
                                it.title.contains(searchQuery, ignoreCase = true) ||
                                it.artist.contains(searchQuery, ignoreCase = true) ||
                                it.album.contains(searchQuery, ignoreCase = true)
                            }
                        }

                        if (filteredSongs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        text = if (missingSongs.isEmpty()) {
                                            getLocalized("¡Todas las canciones tienen portada!", "All songs have cover art!")
                                        } else {
                                            getLocalized("No se encontraron coincidencias", "No matches found")
                                        },
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                items(filteredSongs, key = { it.id }) { song ->
                                    Card(
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color.White.copy(alpha = 0.08f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.MusicNote,
                                                    contentDescription = null,
                                                    tint = Color.White.copy(alpha = 0.4f),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(12.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = song.title,
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${song.artist} · ${song.album}",
                                                    color = Color.White.copy(alpha = 0.5f),
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(6.dp))

                                            // Online Search Button
                                            IconButton(
                                                onClick = {
                                                    activeSearchTargetSong = song
                                                    activeSearchTargetAlbum = null
                                                    onlineSearchQuery = "${song.title} ${song.artist}".trim()
                                                    onlineSearchResults = emptyList()
                                                    onlineSearchStatus = ""
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Search,
                                                    contentDescription = "Search Online",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }

                                            // Gallery Picker Button
                                            IconButton(
                                                onClick = {
                                                    targetSongForPicker = song
                                                    targetAlbumForPicker = null
                                                    coverPickerLauncher.launch("image/*")
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.AddPhotoAlternate,
                                                    contentDescription = "Pick Local Image",
                                                    tint = Color.White.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Sub-modal: Online Cover Search Dialog
    if (activeSearchTargetAlbum != null || activeSearchTargetSong != null) {
        val isAlbumSearch = activeSearchTargetAlbum != null
        val targetName = activeSearchTargetAlbum?.albumName ?: activeSearchTargetSong?.title ?: ""
        val targetArtist = activeSearchTargetAlbum?.artistName ?: activeSearchTargetSong?.artist ?: ""
        val targetAlbumForSong = activeSearchTargetSong?.album?.trim() ?: ""

        var modalSearchType by remember {
            mutableStateOf(if (isAlbumSearch) com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM else com.kevshupp.kevmusicplayer.data.CoverSearchType.AUTO)
        }

        val executeOnlineSearch: (String, com.kevshupp.kevmusicplayer.data.CoverSearchType) -> Unit = { query, type ->
            if (query.isNotBlank() && !isSearchingOnline) {
                scope.launch {
                    isSearchingOnline = true
                    onlineSearchStatus = getLocalized("Buscando en Deezer e iTunes...", "Searching Deezer & iTunes...")
                    var results = com.kevshupp.kevmusicplayer.data.CoverArtRepository.searchCovers(query.trim(), type = type)
                    if (results.isEmpty() && query.contains(" ")) {
                        // Fallback: search just targetName
                        results = com.kevshupp.kevmusicplayer.data.CoverArtRepository.searchCovers(targetName.trim(), type = type)
                    }
                    onlineSearchResults = results
                    isSearchingOnline = false
                    onlineSearchStatus = if (results.isEmpty()) {
                        getLocalized("No se encontraron resultados en Deezer ni iTunes.", "No results found on Deezer or iTunes.")
                    } else {
                        getLocalized("${results.size} portadas encontradas. Toca una para aplicar.", "Found ${results.size} covers. Tap one to apply.")
                    }
                }
            }
        }

        LaunchedEffect(activeSearchTargetAlbum, activeSearchTargetSong) {
            if (onlineSearchResults.isEmpty() && onlineSearchQuery.isNotBlank()) {
                executeOnlineSearch(onlineSearchQuery, modalSearchType)
            }
        }

        AlertDialog(
            onDismissRequest = {
                activeSearchTargetAlbum = null
                activeSearchTargetSong = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("Buscar Portada en Línea", "Search Artwork Online"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = Color.White
                    )
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "$targetName — $targetArtist",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Medium
                    )

                    // Suggestion chips
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isAlbumSearch) {
                            item {
                                SuggestionChip(
                                    onClick = {
                                        val q = "$targetName $targetArtist".trim()
                                        onlineSearchQuery = q
                                        modalSearchType = com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM
                                        executeOnlineSearch(q, com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM)
                                    },
                                    label = { Text("💿 $targetName + $targetArtist", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                            item {
                                SuggestionChip(
                                    onClick = {
                                        onlineSearchQuery = targetName
                                        modalSearchType = com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM
                                        executeOnlineSearch(targetName, com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM)
                                    },
                                    label = { Text(getLocalized("Solo Álbum: $targetName", "Album only: $targetName"), fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        } else {
                            item {
                                SuggestionChip(
                                    onClick = {
                                        val q = "$targetName $targetArtist".trim()
                                        onlineSearchQuery = q
                                        modalSearchType = com.kevshupp.kevmusicplayer.data.CoverSearchType.SONG
                                        executeOnlineSearch(q, com.kevshupp.kevmusicplayer.data.CoverSearchType.SONG)
                                    },
                                    label = { Text("🎵 $targetName + $targetArtist", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                            if (targetAlbumForSong.isNotBlank() && !targetAlbumForSong.contains("Unknown", ignoreCase = true)) {
                                item {
                                    SuggestionChip(
                                        onClick = {
                                            val q = "$targetAlbumForSong $targetArtist".trim()
                                            onlineSearchQuery = q
                                            modalSearchType = com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM
                                            executeOnlineSearch(q, com.kevshupp.kevmusicplayer.data.CoverSearchType.ALBUM)
                                        },
                                        label = { Text("💿 Álbum: $targetAlbumForSong", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)) },
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                            if (targetArtist.isNotBlank() && !targetArtist.contains("Unknown", ignoreCase = true)) {
                                item {
                                    SuggestionChip(
                                        onClick = {
                                            onlineSearchQuery = targetArtist
                                            modalSearchType = com.kevshupp.kevmusicplayer.data.CoverSearchType.AUTO
                                            executeOnlineSearch(targetArtist, com.kevshupp.kevmusicplayer.data.CoverSearchType.AUTO)
                                        },
                                        label = { Text("👤 $targetArtist", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f)) },
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = onlineSearchQuery,
                        onValueChange = { onlineSearchQuery = it },
                        placeholder = { Text(getLocalized("Término de búsqueda...", "Search query..."), color = Color.White.copy(alpha = 0.4f)) },
                        trailingIcon = {
                            IconButton(
                                onClick = { executeOnlineSearch(onlineSearchQuery, modalSearchType) },
                                enabled = onlineSearchQuery.isNotBlank() && !isSearchingOnline
                            ) {
                                if (isSearchingOnline) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    Icon(Icons.Rounded.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (onlineSearchStatus.isNotEmpty()) {
                        Text(
                            text = onlineSearchStatus,
                            color = if (onlineSearchResults.isNotEmpty()) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (onlineSearchResults.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        ) {
                            items(onlineSearchResults) { res ->
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                    modifier = Modifier
                                        .width(130.dp)
                                        .clickable {
                                            scope.launch(Dispatchers.IO) {
                                                val bytes = com.kevshupp.kevmusicplayer.data.CoverArtRepository.downloadCoverBytes(res.coverUrl)
                                                if (bytes != null && bytes.isNotEmpty()) {
                                                    val albumTarget = activeSearchTargetAlbum
                                                    val songTarget = activeSearchTargetSong

                                                    if (albumTarget != null) {
                                                        viewModel.updateAlbumCover(
                                                            context = context,
                                                            albumName = albumTarget.albumName,
                                                            coverBytes = bytes,
                                                            targetSongIds = albumTarget.songs.map { it.id },
                                                            onSuccess = {
                                                                scope.launch(Dispatchers.Main) {
                                                                    missingAlbums.removeAll { it.albumName.equals(albumTarget.albumName, ignoreCase = true) }
                                                                    val songIdsToRemove = albumTarget.songs.map { it.id }.toSet()
                                                                    missingSongs.removeAll { songIdsToRemove.contains(it.id) }
                                                                    activeSearchTargetAlbum = null
                                                                    Toast.makeText(context, getLocalized("Portada de álbum actualizada", "Album cover updated"), Toast.LENGTH_SHORT).show()
                                                                }
                                                            },
                                                            onError = { err ->
                                                                scope.launch(Dispatchers.Main) {
                                                                    Toast.makeText(context, getLocalized("Error: ${err.message}", "Error: ${err.message}"), Toast.LENGTH_LONG).show()
                                                                }
                                                            }
                                                        )
                                                    } else if (songTarget != null) {
                                                        viewModel.updateSongMetadata(
                                                            context = context,
                                                            songId = songTarget.id,
                                                            title = songTarget.title,
                                                            artist = songTarget.artist,
                                                            album = songTarget.album,
                                                            genre = songTarget.genre,
                                                            coverBytes = bytes,
                                                            onSuccess = {
                                                                scope.launch(Dispatchers.Main) {
                                                                    missingSongs.removeAll { it.id == songTarget.id }
                                                                    val albumName = songTarget.album.trim()
                                                                    if (albumName.isNotBlank()) {
                                                                        val remaining = missingSongs.filter { it.album.trim().equals(albumName, ignoreCase = true) }
                                                                        if (remaining.isEmpty()) {
                                                                            missingAlbums.removeAll { it.albumName.equals(albumName, ignoreCase = true) }
                                                                        }
                                                                    }
                                                                    activeSearchTargetSong = null
                                                                    Toast.makeText(context, getLocalized("Portada de canción actualizada", "Song cover updated"), Toast.LENGTH_SHORT).show()
                                                                }
                                                            },
                                                            onError = { err ->
                                                                scope.launch(Dispatchers.Main) {
                                                                    Toast.makeText(context, getLocalized("Error: ${err.message}", "Error: ${err.message}"), Toast.LENGTH_LONG).show()
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(6.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(118.dp),
                                            contentAlignment = Alignment.BottomEnd
                                        ) {
                                            SubcomposeAsyncImage(
                                                model = res.coverUrl,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                loading = {
                                                    Box(
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                                    }
                                                },
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(8.dp))
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (res.source == "Deezer") Color(0xFF9B51E0) else Color(0xFFFF2D55),
                                                modifier = Modifier.padding(4.dp)
                                            ) {
                                                Text(
                                                    text = res.source,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = res.albumName.ifBlank { res.trackName },
                                            fontSize = 11.sp,
                                            color = Color.White,
                                            maxLines = 1,
                                            fontWeight = FontWeight.Bold,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = res.artistName,
                                            fontSize = 10.sp,
                                            color = Color.White.copy(alpha = 0.6f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        activeSearchTargetAlbum = null
                        activeSearchTargetSong = null
                    }
                ) {
                    Text(getLocalized("Cerrar", "Close"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF161926),
            shape = RoundedCornerShape(18.dp)
        )
    }
}

/**
 * Helper to check if a song has cover art in memory, disk cache, or physical file.
 */
private fun checkSongHasCover(context: Context, uriString: String): Boolean {
    // 1. Check RAM Cache
    if (albumArtCache.get(uriString) != null) return true

    // 2. Check Disk Cache
    val res = try {
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).getInt("art_resolution", 500)
    } catch (e: Exception) { 500 }

    val diskFile = getDiskCacheFile(context, uriString, res)
    if (diskFile.exists() && diskFile.isFile) {
        return diskFile.length() > 0L
    }

    // 3. Fast physical / media retriever check
    val retriever = MediaMetadataRetriever()
    var pfd: ParcelFileDescriptor? = null
    try {
        val songId = uriString.substringAfterLast("/").toLongOrNull()
        val physicalPath = getPhysicalPath(context, songId ?: 0L, uriString)
        if (!physicalPath.isNullOrBlank()) {
            val file = File(physicalPath)
            if (file.exists() && file.isFile) {
                retriever.setDataSource(physicalPath)
                if (retriever.embeddedPicture != null) {
                    return true
                }
            }
        }

        pfd = context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
        if (pfd != null) {
            retriever.setDataSource(pfd.fileDescriptor)
            if (retriever.embeddedPicture != null) {
                return true
            }
        }

        // Check folder cover
        if (!physicalPath.isNullOrBlank()) {
            val audioFile = File(physicalPath)
            val parentDir = audioFile.parentFile
            if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
                val coverNames = listOf("cover.jpg", "folder.jpg", "album.jpg", "front.jpg", "Cover.jpg", "Folder.jpg", "Album.jpg", "Front.jpg")
                if (coverNames.map { File(parentDir, it) }.any { it.exists() && it.isFile && it.length() > 0 }) {
                    return true
                }
            }
        }
    } catch (e: Exception) {
        // Ignored
    } finally {
        try { pfd?.close() } catch (e: Exception) {}
        try { retriever.release() } catch (e: Exception) {}
    }

    return false
}
