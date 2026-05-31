package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.compose.ui.layout.ContentScale
import android.util.LruCache
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.activity.compose.BackHandler
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Beautiful vibrant gradients for placeholders
private val GradientPairs = listOf(
    listOf(Color(0xFFFF3366), Color(0xFF7C4DFF)),
    listOf(Color(0xFF00E5FF), Color(0xFF7C4DFF)),
    listOf(Color(0xFFFF5252), Color(0xFFFFEB3B)),
    listOf(Color(0xFF00E676), Color(0xFF00B0FF)),
    listOf(Color(0xFF7C4DFF), Color(0xFFE040FB)),
    listOf(Color(0xFFFF6E40), Color(0xFFFF1744)),
    listOf(Color(0xFF3F51B5), Color(0xFF00BCD4))
)

fun getGradientForString(name: String): Brush {
    val index = java.lang.Math.abs(name.hashCode()) % GradientPairs.size
    return Brush.linearGradient(GradientPairs[index])
}

sealed interface SubView {
    data class AlbumDetail(val albumName: String) : SubView
    data class ArtistDetail(val artistName: String) : SubView
    data class GenreDetail(val genreName: String) : SubView
    data class FolderDetail(val folderName: String) : SubView
    data class PlaylistDetail(val playlistName: String) : SubView
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    audioFiles: List<AudioFile>,
    onFileClick: (AudioFile, List<AudioFile>?) -> Unit,
    player: Player?,
    onMiniPlayerClick: () -> Unit,
    onSettingsClick: () -> Unit,
    enabledTabs: Set<String>,
    sortBy: String,
    viewModel: MediaBrowserViewModel? = null,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf("Songs") }
    var currentSubView by remember { mutableStateOf<SubView?>(null) }

    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedSongs = remember { mutableStateListOf<AudioFile>() }

    // Intercept system back gestures to exit active subview details or multi-select first
    BackHandler(enabled = isMultiSelectMode || currentSubView != null) {
        if (isMultiSelectMode) {
            isMultiSelectMode = false
            selectedSongs.clear()
        } else {
            currentSubView = null
        }
    }

    var songToDelete by remember { mutableStateOf<AudioFile?>(null) }
    var songForPlaylist by remember { mutableStateOf<AudioFile?>(null) }

    if (viewModel != null) {
        LaunchedEffect(viewModel.requestedTab.value, viewModel.requestedSubViewType.value, viewModel.requestedSubViewName.value) {
            val reqTab = viewModel.requestedTab.value
            val reqType = viewModel.requestedSubViewType.value
            val reqName = viewModel.requestedSubViewName.value
            if (reqTab != null) {
                selectedTab = reqTab
                viewModel.requestedTab.value = null
            }
            if (reqType != null && reqName != null) {
                currentSubView = when (reqType) {
                    "Album" -> SubView.AlbumDetail(reqName)
                    "Artist" -> SubView.ArtistDetail(reqName)
                    "Genre" -> SubView.GenreDetail(reqName)
                    "Folder" -> SubView.FolderDetail(reqName)
                    else -> null
                }
                viewModel.requestedSubViewType.value = null
                viewModel.requestedSubViewName.value = null
            }
        }
    }
    
    // Enforce selectedTab is always visible
    val activeTab = remember(selectedTab, enabledTabs) {
        if (selectedTab in enabledTabs) selectedTab else enabledTabs.firstOrNull() ?: "Songs"
    }

    // Filter audio files by search query
    val filteredFiles = remember(audioFiles.toList(), searchQuery, sortBy) {
        val filtered = audioFiles.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true) ||
            it.album.contains(searchQuery, ignoreCase = true) ||
            it.genre.contains(searchQuery, ignoreCase = true)
        }
        when (sortBy) {
            "Alphabetical" -> filtered.sortedBy { it.title.lowercase() }
            "Artist" -> filtered.sortedBy { it.artist.lowercase() }
            "Duration" -> filtered.sortedByDescending { it.duration }
            else -> filtered
        }
    }

    // Grouping
    val albums = remember(filteredFiles) {
        filteredFiles.groupBy { it.album }
    }
    val artists = remember(filteredFiles) {
        filteredFiles.groupBy { it.artist }
    }
    val genres = remember(filteredFiles) {
        filteredFiles.groupBy { it.genre }
    }
    val folders = remember(filteredFiles) {
        filteredFiles.groupBy { it.folderName }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(bottom = if (player?.currentMediaItem != null) 92.dp else 0.dp)
        ) {
            if (currentSubView == null) {
                // Primary Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Kev Music",
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-1).sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Discover your premium sounds",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }

                    IconButton(
                        onClick = onSettingsClick,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                // Custom Sleek Search Bar & Shuffle Play Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search songs...") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                  IconButton(onClick = { searchQuery = "" }) {
                                      Icon(
                                          imageVector = Icons.Rounded.Clear,
                                          contentDescription = "Clear",
                                          tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                          modifier = Modifier.size(20.dp)
                                      )
                                  }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    )

                    IconButton(
                        onClick = {
                            if (filteredFiles.isNotEmpty()) {
                                val shuffled = filteredFiles.shuffled()
                                onFileClick(shuffled.first(), shuffled)
                            }
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = "Shuffle Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Category Selection Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tabs = listOf("Songs", "Albums", "Artists", "Genres", "Folders", "Playlists").filter { it in enabledTabs }
                    tabs.forEach { tab ->
                        val isSelected = activeTab == tab
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedTab = tab },
                            label = {
                                Text(
                                    text = tab.uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    letterSpacing = 0.5.sp
                                )
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.White,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                labelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                selectedBorderColor = Color.Transparent,
                                borderColor = Color.Transparent
                            )
                        )
                    }
                }

                // Dynamic Main Library Lists
                if (audioFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "No music files found.", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        AnimatedContent(
                            targetState = activeTab,
                            transitionSpec = {
                                fadeIn() togetherWith fadeOut()
                            },
                            label = "library_tab_transition"
                        ) { tab ->
                            when (tab) {
                                "Songs" -> {
                                    SongListView(
                                        songs = filteredFiles,
                                        onSongClick = { song -> onFileClick(song, filteredFiles) },
                                        onSongLongClick = { song ->
                                            if (!isMultiSelectMode) {
                                                isMultiSelectMode = true
                                            }
                                            if (selectedSongs.contains(song)) {
                                                selectedSongs.remove(song)
                                                if (selectedSongs.isEmpty()) {
                                                    isMultiSelectMode = false
                                                }
                                            } else {
                                                selectedSongs.add(song)
                                            }
                                        },
                                        onPlayNextClick = { viewModel?.playNext(it) },
                                        onAddToQueueClick = { viewModel?.addToQueue(it) },
                                        onAddToPlaylistClick = { songForPlaylist = it },
                                        selectedSongs = selectedSongs.toSet(),
                                        isMultiSelectMode = isMultiSelectMode,
                                        onSongSelectToggle = { song ->
                                            if (selectedSongs.contains(song)) {
                                                selectedSongs.remove(song)
                                                if (selectedSongs.isEmpty()) {
                                                    isMultiSelectMode = false
                                                }
                                            } else {
                                                selectedSongs.add(song)
                                            }
                                        }
                                    )
                                }
                                "Albums" -> {
                                    AlbumGridView(
                                        albums = albums,
                                        onAlbumClick = { currentSubView = SubView.AlbumDetail(it) }
                                    )
                                }
                                "Artists" -> {
                                    ArtistListView(
                                        artists = artists,
                                        onArtistClick = { currentSubView = SubView.ArtistDetail(it) }
                                    )
                                }
                                "Genres" -> {
                                    GenreGridView(
                                        genres = genres,
                                        onGenreClick = { currentSubView = SubView.GenreDetail(it) }
                                    )
                                }
                                "Folders" -> {
                                    FolderGridView(
                                        folders = folders,
                                        onFolderClick = { currentSubView = SubView.FolderDetail(it) }
                                    )
                                }
                                "Playlists" -> {
                                    PlaylistGridView(
                                        playlists = viewModel?.playlists ?: emptyMap(),
                                        onCreatePlaylist = { viewModel?.createPlaylist(it) },
                                        onPlaylistClick = { currentSubView = SubView.PlaylistDetail(it) },
                                        onDeletePlaylist = { viewModel?.deletePlaylist(it) }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Sub-View Detail Mode (e.g. Songs inside album/artist/genre/folder)
                val subView = currentSubView!!
                val title = when (subView) {
                    is SubView.AlbumDetail -> subView.albumName
                    is SubView.ArtistDetail -> subView.artistName
                    is SubView.GenreDetail -> subView.genreName
                    is SubView.FolderDetail -> subView.folderName
                    is SubView.PlaylistDetail -> subView.playlistName
                }
                val typeLabel = when (subView) {
                    is SubView.AlbumDetail -> "Album"
                    is SubView.ArtistDetail -> "Artist"
                    is SubView.GenreDetail -> "Genre"
                    is SubView.FolderDetail -> "Folder"
                    is SubView.PlaylistDetail -> "Playlist"
                }
                val subSongs = remember(filteredFiles, subView, viewModel?.playlists) {
                    when (subView) {
                        is SubView.AlbumDetail -> filteredFiles.filter { it.album == subView.albumName }
                        is SubView.ArtistDetail -> filteredFiles.filter { it.artist == subView.artistName }
                        is SubView.GenreDetail -> filteredFiles.filter { it.genre == subView.genreName }
                        is SubView.FolderDetail -> filteredFiles.filter { it.folderName == subView.folderName }
                        is SubView.PlaylistDetail -> viewModel?.playlists?.get(subView.playlistName) ?: emptyList()
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    // Sub-Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { currentSubView = null }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = typeLabel.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        if (subView is SubView.PlaylistDetail) {
                            var showAddSongsDialog by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { showAddSongsDialog = true },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                ),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "Add Songs",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (showAddSongsDialog) {
                                AddSongsToPlaylistDialog(
                                    playlistName = subView.playlistName,
                                    audioFiles = audioFiles,
                                    viewModel = viewModel,
                                    onDismiss = { showAddSongsDialog = false }
                                )
                            }
                        }
                    }

                    // Songs listing under the sub-view
                    SongListView(
                        songs = subSongs,
                        onSongClick = { song -> onFileClick(song, subSongs) },
                        onSongLongClick = { song ->
                            if (!isMultiSelectMode) {
                                isMultiSelectMode = true
                            }
                            if (selectedSongs.contains(song)) {
                                selectedSongs.remove(song)
                                if (selectedSongs.isEmpty()) {
                                    isMultiSelectMode = false
                                }
                            } else {
                                selectedSongs.add(song)
                            }
                        },
                        playlistContextName = if (subView is SubView.PlaylistDetail) subView.playlistName else null,
                        onPlayNextClick = { viewModel?.playNext(it) },
                        onAddToQueueClick = { viewModel?.addToQueue(it) },
                        onAddToPlaylistClick = { songForPlaylist = it },
                        onRemoveFromPlaylistClick = { viewModel?.removeSongFromPlaylist((subView as? SubView.PlaylistDetail)?.playlistName ?: "", it.id) },
                        selectedSongs = selectedSongs.toSet(),
                        isMultiSelectMode = isMultiSelectMode,
                        onSongSelectToggle = { song ->
                            if (selectedSongs.contains(song)) {
                                selectedSongs.remove(song)
                                if (selectedSongs.isEmpty()) {
                                    isMultiSelectMode = false
                                }
                            } else {
                                selectedSongs.add(song)
                            }
                        }
                    )
                }
            }
        }

        // Persistent Glassmorphic Floating Mini-player
        if (player != null) {
            val playerState = rememberPlayerState(player)
            if (playerState.currentSong != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        .fillMaxWidth()
                ) {
                    MiniPlayer(
                        player = player,
                        playerState = playerState,
                        onClick = onMiniPlayerClick
                    )
                }
            }
        }

        // Settings are now handled by full screen SettingsScreen

        // Floating glassmorphic multi-select bar
        val context = LocalContext.current
        AnimatedVisibility(
            visible = isMultiSelectMode,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (player?.currentMediaItem != null) 100.dp else 24.dp)
                .padding(horizontal = 24.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedSongs.size} seleccionadas",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Action 1: Add to Queue
                        IconButton(
                            onClick = {
                                selectedSongs.forEach { viewModel?.addToQueue(it) }
                                isMultiSelectMode = false
                                selectedSongs.clear()
                            }
                        ) {
                            Icon(Icons.Rounded.Queue, contentDescription = "Cola", tint = MaterialTheme.colorScheme.primary)
                        }

                        // Action 2: Add to Playlist
                        IconButton(
                            onClick = {
                                if (selectedSongs.isNotEmpty()) {
                                    songForPlaylist = selectedSongs.first()
                                }
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = "Playlist", tint = MaterialTheme.colorScheme.primary)
                        }

                        // Action 3: Delete
                        IconButton(
                            onClick = {
                                selectedSongs.forEach { viewModel?.deleteSong(context, it.id) }
                                isMultiSelectMode = false
                                selectedSongs.clear()
                            }
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }

                        // Action 4: Cancel
                        IconButton(
                            onClick = {
                                isMultiSelectMode = false
                                selectedSongs.clear()
                            }
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (songToDelete != null) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { songToDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete Track?", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text("Are you sure you want to permanently delete this track from your device?", color = Color.White.copy(alpha = 0.8f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(songToDelete!!.title, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(songToDelete!!.artist, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = songToDelete!!
                        songToDelete = null
                        viewModel?.deleteSong(context, toDelete.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { songToDelete = null }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF161829),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    if (songForPlaylist != null && viewModel != null) {
        AlertDialog(
            onDismissRequest = { songForPlaylist = null },
            title = { Text("Agregar a playlist", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                val list = viewModel.playlists.keys.toList()
                if (list.isEmpty()) {
                    Text("No tienes listas de reproducción creadas. Crea una primero en la pestaña 'PLAYLISTS'.", color = Color.White.copy(alpha = 0.7f))
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                    ) {
                        items(list) { name ->
                            Card(
                                onClick = {
                                    if (isMultiSelectMode) {
                                        selectedSongs.forEach { viewModel.addSongToPlaylist(name, it.id) }
                                        isMultiSelectMode = false
                                        selectedSongs.clear()
                                    } else {
                                        viewModel.addSongToPlaylist(name, songForPlaylist!!.id)
                                    }
                                    songForPlaylist = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(name, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { songForPlaylist = null }) {
                    Text("Cancelar", color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF161829)
        )
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongListView(
    songs: List<AudioFile>,
    onSongClick: (AudioFile) -> Unit,
    onSongLongClick: (AudioFile) -> Unit = {},
    playlistContextName: String? = null,
    onPlayNextClick: (AudioFile) -> Unit = {},
    onAddToQueueClick: (AudioFile) -> Unit = {},
    onAddToPlaylistClick: (AudioFile) -> Unit = {},
    onRemoveFromPlaylistClick: (AudioFile) -> Unit = {},
    selectedSongs: Set<AudioFile> = emptySet(),
    isMultiSelectMode: Boolean = false,
    onSongSelectToggle: ((AudioFile) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    // Fast scroll logic: Numbers -> #, A-Z -> letter, Everything else -> ?
    val alphabet = remember(songs) { 
        songs.map { 
            val firstChar = it.title.firstOrNull()?.uppercaseChar() ?: '#'
            when {
                firstChar.isDigit() -> '#'
                firstChar in 'A'..'Z' -> firstChar
                else -> '?' // All special chars or non-standard letters go to ?
            }
        }
        .distinct()
        .sortedWith { a, b ->
            when {
                a == b -> 0
                a == '#' -> -1
                b == '#' -> 1
                a == '?' -> 1
                b == '?' -> -1
                else -> a.compareTo(b)
            }
        }
        .takeIf { it.size > 5 }
    }
    
    var showAlphabetPopup by remember { mutableStateOf(false) }
    var currentLetter by remember { mutableStateOf(' ') }

    // Logic to calculate visible portion of the alphabet based on scroll
    val visibleAlphabetRange = remember(alphabet, listState.firstVisibleItemIndex, songs.size) {
        if (alphabet == null || songs.isEmpty()) {
            null
        } else {
            val totalLetters = alphabet.size
            if (totalLetters <= 15) {
                alphabet 
            } else {
                // Find starting letter index based on current scroll position
                val progress = (listState.firstVisibleItemIndex.toFloat() / songs.size).coerceIn(0f, 1f)
                val centerIndex = (progress * totalLetters).toInt().coerceIn(0, totalLetters - 1)
                
                // Show a window of letters around the current scroll position
                val windowSize = 12
                val start = (centerIndex - windowSize / 2).coerceIn(0, (totalLetters - windowSize).coerceAtLeast(0))
                val end = (start + windowSize).coerceAtMost(totalLetters)
                
                alphabet.subList(start, end)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, 
                end = 16.dp,
                top = 8.dp, 
                bottom = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(songs) { song ->
                val isSelected = selectedSongs.contains(song)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickable(
                            onClick = {
                                if (isMultiSelectMode) {
                                    onSongSelectToggle?.invoke(song)
                                } else {
                                    onSongClick(song)
                                }
                            },
                            onLongClick = {
                                onSongLongClick(song)
                            }
                        )
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                        )
                        .then(
                            if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                            else Modifier
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Multi-select Indicator
                    if (isMultiSelectMode) {
                        Icon(
                            imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = "Selection",
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.padding(end = 12.dp).size(24.dp)
                        )
                    }

                    // Sleek Gradient Song Icon
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(getGradientForString(song.title)),
                        contentAlignment = Alignment.Center
                    ) {
                        val artBytes = rememberAlbumArt(song.uriString)
                        SubcomposeAsyncImage(
                            model = artBytes,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            loading = {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            error = {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${song.artist} • ${song.album}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = formatDuration(song.duration),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    if (!isMultiSelectMode) {
                        // 3-dots Menu for queues and playlists!
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = "Options",
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                properties = androidx.compose.ui.window.PopupProperties(focusable = true)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Reproducir siguiente") },
                                    onClick = {
                                        showMenu = false
                                        onPlayNextClick(song)
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.QueuePlayNext, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Agregar a la cola") },
                                    onClick = {
                                        showMenu = false
                                        onAddToQueueClick(song)
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Queue, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Agregar a Playlist...") },
                                    onClick = {
                                        showMenu = false
                                        onAddToPlaylistClick(song)
                                    },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) }
                                )
                                if (playlistContextName != null) {
                                    DropdownMenuItem(
                                        text = { Text("Quitar de esta Playlist") },
                                        onClick = {
                                            showMenu = false
                                            onRemoveFromPlaylistClick(song)
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.PlaylistRemove, contentDescription = null) }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Eliminar canción", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showMenu = false
                                        onSongLongClick(song)
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Invisible Touch Area for Fast Scroll (Right edge)
        if (alphabet != null && songs.size > 10) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(44.dp) // Wide touch target
                    .pointerInput(alphabet) {
                        detectVerticalDragGestures(
                            onDragStart = { showAlphabetPopup = true },
                            onDragEnd = { showAlphabetPopup = false },
                            onDragCancel = { showAlphabetPopup = false },
                            onVerticalDrag = { change, _ ->
                                val y = change.position.y
                                val height = this.size.height
                                
                                // Mapping Y position to FULL alphabet for accurate seeking
                                val totalIndex = ((y / height) * alphabet.size).toInt().coerceIn(0, alphabet.size - 1)
                                val letter = alphabet[totalIndex]
                                
                                if (currentLetter != letter) {
                                    currentLetter = letter
                                    val targetIndex = songs.indexOfFirst { s ->
                                        val c = s.title.firstOrNull()?.uppercaseChar() ?: '#'
                                        when {
                                            letter == '#' -> c.isDigit()
                                            letter == '?' -> (c !in 'A'..'Z' && !c.isDigit())
                                            else -> c == letter
                                        }
                                    }
                                    if (targetIndex != -1) {
                                        coroutineScope.launch {
                                            listState.scrollToItem(targetIndex)
                                        }
                                    }
                                }
                            }
                        )
                    }
            ) {
                // The visible bar inside the touch area
                AnimatedVisibility(
                    visible = showAlphabetPopup,
                    enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
                    exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it }),
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(36.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
                            )
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.SpaceEvenly,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        visibleAlphabetRange?.forEach { letter ->
                            val isCurrent = currentLetter == letter
                            Text(
                                text = letter.toString(),
                                fontSize = if (isCurrent) 14.sp else 11.sp,
                                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Bold,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary 
                                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Floating Letter Popup (Internal)
        AnimatedVisibility(
            visible = showAlphabetPopup,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = currentLetter.toString(),
                        style = MaterialTheme.typography.displayMedium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

// ---------------- ALBUMS GRID VIEW ----------------
@Composable
fun AlbumGridView(
    albums: Map<String, List<AudioFile>>,
    onAlbumClick: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(albums.keys.toList()) { albumName ->
            val albumSongs = albums[albumName] ?: emptyList()
            Card(
                onClick = { onAlbumClick(albumName) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.82f)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(getGradientForString(albumName)),
                        contentAlignment = Alignment.Center
                    ) {
                        val firstSongUri = albumSongs.firstOrNull()?.uriString
                        val artBytes = rememberAlbumArt(firstSongUri)
                        SubcomposeAsyncImage(
                            model = artBytes,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            error = {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Album,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.9f),
                                        modifier = Modifier.size(54.dp)
                                    )
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(
                            text = albumName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "${albumSongs.size} Tracks",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

// ---------------- ARTISTS LIST VIEW ----------------
@Composable
fun ArtistListView(
    artists: Map<String, List<AudioFile>>,
    onArtistClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(artists.keys.toList()) { artistName ->
            val artistSongs = artists[artistName] ?: emptyList()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onArtistClick(artistName) }
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circle Profile style for Artists
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(getGradientForString(artistName)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = artistName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${artistSongs.size} Songs",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
        }
    }
}

// ---------------- GENRES GRID VIEW ----------------
@Composable
fun GenreGridView(
    genres: Map<String, List<AudioFile>>,
    onGenreClick: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(genres.keys.toList()) { genreName ->
            val genreSongs = genres[genreName] ?: emptyList()
            Card(
                onClick = { onGenreClick(genreName) },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.2f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(getGradientForString(genreName))
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Piano,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.2f),
                        modifier = Modifier
                            .size(72.dp)
                            .align(Alignment.BottomEnd)
                            .offset(x = 12.dp, y = 12.dp)
                    )
                    
                    Column(modifier = Modifier.align(Alignment.TopStart)) {
                        Text(
                            text = genreName,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                        Text(
                            text = "${genreSongs.size} Tracks",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

// ---------------- FLOATING GLASSMORPHIC MINI PLAYER ----------------
@Composable
fun MiniPlayer(
    player: Player,
    playerState: PlayerStateInfo,
    onClick: () -> Unit
) {
    val metadata = playerState.currentSong?.mediaMetadata
    val title = metadata?.title?.toString() ?: "Unknown Song"
    val artist = metadata?.artist?.toString() ?: "Unknown Artist"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .shadow(16.dp, shape = RoundedCornerShape(20.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album Art thumbnail
                val currentSongId = playerState.currentSong?.mediaId
                val currentSongUriString = remember(currentSongId) {
                    currentSongId?.let { "content://media/external/audio/media/$it" }
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(getGradientForString(title)),
                    contentAlignment = Alignment.Center
                ) {
                    val artBytes = rememberAlbumArt(currentSongUriString)
                    SubcomposeAsyncImage(
                        model = artBytes,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        loading = {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        error = {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = artist,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Mini controls
                IconButton(
                    onClick = {
                        if (playerState.isPlaying) player.pause() else player.play()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(
                    onClick = { player.seekToNext() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Glowing Bottom Mini-Progress Bar
            if (playerState.duration > 0) {
                val progress = playerState.position.toFloat() / playerState.duration.toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                        .align(Alignment.BottomStart)
                )
            }
        }
    }
}

// SettingsContent was removed because Settings are now managed in full screen SettingsScreen.kt

// ---------------- FOLDERS GRID VIEW ----------------
@Composable
fun FolderGridView(
    folders: Map<String, List<AudioFile>>,
    onFolderClick: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(folders.keys.toList()) { folderName ->
            val folderSongs = folders[folderName] ?: emptyList()
            Card(
                onClick = { onFolderClick(folderName) },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.2f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(getGradientForString(folderName))
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.2f),
                        modifier = Modifier
                            .size(72.dp)
                            .align(Alignment.BottomEnd)
                            .offset(x = 12.dp, y = 12.dp)
                    )
                    
                    Column(modifier = Modifier.align(Alignment.TopStart)) {
                        Text(
                            text = folderName,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 18.sp,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${folderSongs.size} Tracks",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

// ---------------- UTILS ----------------
fun formatDuration(ms: Long): String {
    val sec = (ms / 1000) % 60
    val min = (ms / (1000 * 60)) % 60
    val hr = (ms / (1000 * 60 * 60))
    return if (hr > 0) {
        String.format("%d:%02d:%02d", hr, min, sec)
    } else {
        String.format("%d:%02d", min, sec)
    }
}

@Composable
fun rememberPlayerState(player: Player?): PlayerStateInfo {
    var isPlaying by remember { mutableStateOf(player?.isPlaying ?: false) }
    var currentSong by remember { mutableStateOf(player?.currentMediaItem) }
    var position by remember { mutableLongStateOf(player?.currentPosition ?: 0L) }
    var duration by remember { mutableLongStateOf(player?.duration ?: 0L) }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}

        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                isPlaying = player.isPlaying
                currentSong = player.currentMediaItem
                duration = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        isPlaying = player.isPlaying
        currentSong = player.currentMediaItem
        duration = player.duration.coerceAtLeast(0L)
        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(player, isPlaying) {
        if (player == null || !isPlaying) return@LaunchedEffect
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    return PlayerStateInfo(isPlaying, currentSong, position, duration)
}

data class PlayerStateInfo(
    val isPlaying: Boolean,
    val currentSong: MediaItem?,
    val position: Long,
    val duration: Long
)

// ---------------- ALBUUM ART CACHE & ASYNC LOADER ----------------
val albumArtCache = LruCache<String, ByteArray>(100) // Cache up to 100 album arts in memory for blazing fast startup!

@Composable
fun rememberAlbumArt(uriString: String?): ByteArray? {
    if (uriString == null) return null
    val context = LocalContext.current
    var artBytes by remember(uriString) { mutableStateOf(albumArtCache.get(uriString)) }

    if (artBytes == null) {
        LaunchedEffect(uriString) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(uriString))
                    val picture = retriever.embeddedPicture
                    if (picture != null) {
                        albumArtCache.put(uriString, picture)
                        artBytes = picture
                    }
                } catch (e: Exception) {
                    // Ignore
                } finally {
                    try {
                        retriever.release()
                    } catch (e: Exception) {}
                }
            }
        }
    }
    return artBytes
}

// ---------------- PLAYLIST GRID VIEW ----------------
@Composable
fun PlaylistGridView(
    playlists: Map<String, List<AudioFile>>,
    onCreatePlaylist: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Nueva lista de reproducción", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Nombre de la lista") },
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
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            onCreatePlaylist(newPlaylistName)
                            newPlaylistName = ""
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text("Crear", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancelar", color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF161829)
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Create Playlist Card button
        item {
            Card(
                onClick = { showCreateDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                ),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "New Playlist",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Nueva lista",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }

        // Existing playlists list
        items(playlists.keys.toList()) { name ->
            val songs = playlists[name] ?: emptyList()
            var showDeleteConfirm by remember { mutableStateOf(false) }

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Eliminar lista", color = Color.White, fontWeight = FontWeight.Bold) },
                    text = { Text("¿Estás seguro de que deseas eliminar la lista '$name'?", color = Color.White.copy(alpha = 0.8f)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onDeletePlaylist(name)
                                showDeleteConfirm = false
                            }
                        ) {
                            Text("Eliminar", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text("Cancelar", color = Color.White.copy(alpha = 0.6f))
                        }
                    },
                    containerColor = Color(0xFF161829)
                )
            }

            Card(
                onClick = { onPlaylistClick(name) },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(getGradientForString(name))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                            IconButton(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = "Delete Playlist",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        
                        Column {
                            Text(
                                text = name,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${songs.size} canciones",
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddSongsToPlaylistDialog(
    playlistName: String,
    audioFiles: List<AudioFile>,
    viewModel: MediaBrowserViewModel?,
    onDismiss: () -> Unit
) {
    if (viewModel == null) return
    var activeSubTab by remember { mutableStateOf("Canción") }
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
                    .heightIn(max = 400.dp)
            ) {
                // Category tabs Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
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

                // Items list
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (activeSubTab) {
                        "Canción" -> {
                            val availableSongs = audioFiles.filter { !currentSongIds.contains(it.id) }
                            if (availableSongs.isEmpty()) {
                                item {
                                    Text("No hay canciones disponibles para agregar.", color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 16.dp))
                                }
                            } else {
                                items(availableSongs) { song ->
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
                            items(albums.keys.toList()) { album ->
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
                        "Artista" -> {
                            val artists = audioFiles.groupBy { it.artist }
                            items(artists.keys.toList()) { artist ->
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
                        "Género" -> {
                            val genres = audioFiles.groupBy { it.genre }
                            items(genres.keys.toList()) { genre ->
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Listo", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF161829)
    )
}
