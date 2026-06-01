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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.graphics.SolidColor
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import com.kevshupp.kevmusicplayer.R
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
    enabledTabs: List<String>,
    sortBy: String,
    viewModel: MediaBrowserViewModel? = null,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val systemLang = remember { context.resources.configuration.locales[0].language }
    val getLocalized = { es: String, en: String ->
        if (systemLang == "es") es else en
    }
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
    var songForTagEditing by remember { mutableStateOf<AudioFile?>(null) }
    var playlistSortBy by remember { mutableStateOf("Date") }

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
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                  IconButton(onClick = { searchQuery = "" }) {
                                      Icon(
                                          imageVector = Icons.Rounded.Clear,
                                          contentDescription = "Clear",
                                          tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                          modifier = Modifier.size(18.dp)
                                      )
                                  }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            unfocusedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
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
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = "Shuffle Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
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
                    val tabLabelMap = mapOf(
                        "Songs" to stringResource(R.string.category_songs),
                        "Albums" to stringResource(R.string.category_albums),
                        "Artists" to stringResource(R.string.category_artists),
                        "Genres" to stringResource(R.string.category_genres),
                        "Folders" to stringResource(R.string.category_folders),
                        "Playlists" to stringResource(R.string.category_playlists)
                    )
                    val allTabs = listOf("Songs", "Albums", "Artists", "Genres", "Folders", "Playlists")
                    val tabs = enabledTabs.filter { it in allTabs }
                    tabs.forEach { tab ->
                        val isSelected = activeTab == tab
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedTab = tab },
                            label = {
                                Text(
                                    text = (tabLabelMap[tab] ?: tab).uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    letterSpacing = 0.5.sp
                                )
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
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
                        Text(text = getLocalized("No se encontraron archivos de música.", "No music files found."), style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        val disableAnimations = com.kevshupp.kevmusicplayer.ui.theme.LocalDisableAnimations.current
                        AnimatedContent(
                            targetState = activeTab,
                            transitionSpec = {
                                if (disableAnimations) {
                                    ContentTransform(EnterTransition.None, ExitTransition.None)
                                } else {
                                    fadeIn() togetherWith fadeOut()
                                }
                            },
                            label = "library_tab_transition"
                        ) { tab ->
                            when (tab) {
                                "Songs" -> {
                                    SongListView(
                                        songs = filteredFiles,
                                        onSongClick = { song -> onFileClick(song, filteredFiles) },
                                        onEditTagsClick = { songForTagEditing = it },
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
                                        playlistCovers = viewModel?.playlistCovers ?: emptyMap(),
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
                val subSongs = remember(filteredFiles, subView, viewModel?.playlists, playlistSortBy) {
                    val baseList = when (subView) {
                        is SubView.AlbumDetail -> filteredFiles.filter { it.album == subView.albumName }
                        is SubView.ArtistDetail -> filteredFiles.filter { it.artist == subView.artistName }
                        is SubView.GenreDetail -> filteredFiles.filter { it.genre == subView.genreName }
                        is SubView.FolderDetail -> filteredFiles.filter { it.folderName == subView.folderName }
                        is SubView.PlaylistDetail -> viewModel?.playlists?.get(subView.playlistName) ?: emptyList()
                    }
                    if (subView is SubView.PlaylistDetail) {
                        when (playlistSortBy) {
                            "Name" -> baseList.sortedBy { it.title.lowercase() }
                            "Artist" -> baseList.sortedBy { it.artist.lowercase() }
                            else -> baseList
                        }
                    } else {
                        baseList
                    }
                }

                val coverPickerLauncher = rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
                ) { uri ->
                    if (uri != null && subView is SubView.PlaylistDetail) {
                        val savedPath = savePlaylistCoverLocally(context, subView.playlistName, uri)
                        if (savedPath != null) {
                            viewModel?.setPlaylistCover(subView.playlistName, savedPath)
                        }
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
                        
                        if (subView is SubView.PlaylistDetail) {
                            val currentCover = viewModel?.playlistCovers?.get(subView.playlistName)
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (currentCover != null) SolidColor(Color.Transparent) else getGradientForString(subView.playlistName))
                                    .clickable {
                                        coverPickerLauncher.launch("image/*")
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (currentCover != null) {
                                    androidx.compose.ui.platform.LocalContext.current.let { _ ->
                                        coil.compose.SubcomposeAsyncImage(
                                            model = java.io.File(currentCover),
                                            contentDescription = "Playlist Cover",
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                            error = {
                                                Icon(Icons.Rounded.Image, contentDescription = null, tint = Color.White)
                                            }
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = "Edit Cover",
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                        }

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
                            var showSortMenu by remember { mutableStateOf(false) }
                            
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Shuffle Playback Button
                                IconButton(
                                    onClick = {
                                        if (subSongs.isNotEmpty()) {
                                            val shuffled = subSongs.shuffled()
                                            onFileClick(shuffled.first(), shuffled)
                                        }
                                    },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    ),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Shuffle,
                                        contentDescription = "Shuffle Playlist",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // 2. Sort Button & Dropdown Menu
                                Box {
                                    IconButton(
                                        onClick = { showSortMenu = true },
                                        colors = IconButtonDefaults.iconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        ),
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Sort,
                                            contentDescription = "Sort Playlist",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showSortMenu,
                                        onDismissRequest = { showSortMenu = false },
                                        containerColor = Color(0xFF1E213A)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Fecha de agregado", color = Color.White) },
                                            onClick = {
                                                playlistSortBy = "Date"
                                                showSortMenu = false
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.CalendarToday,
                                                    contentDescription = null,
                                                    tint = if (playlistSortBy == "Date") MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f)
                                                )
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Nombre", color = Color.White) },
                                            onClick = {
                                                playlistSortBy = "Name"
                                                showSortMenu = false
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.SortByAlpha,
                                                    contentDescription = null,
                                                    tint = if (playlistSortBy == "Name") MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f)
                                                )
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Artista", color = Color.White) },
                                            onClick = {
                                                playlistSortBy = "Artist"
                                                showSortMenu = false
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.Person,
                                                    contentDescription = null,
                                                    tint = if (playlistSortBy == "Artist") MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f)
                                                )
                                            }
                                        )
                                    }
                                }

                                // 3. Add Songs Button
                                IconButton(
                                    onClick = { showAddSongsDialog = true },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    ),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = "Add Songs",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
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
                        onEditTagsClick = { songForTagEditing = it },
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
        val disableAnims = com.kevshupp.kevmusicplayer.ui.theme.LocalDisableAnimations.current
        AnimatedVisibility(
            visible = isMultiSelectMode,
            enter = if (disableAnims) EnterTransition.None else (slideInVertically(initialOffsetY = { it }) + fadeIn()),
            exit = if (disableAnims) ExitTransition.None else (slideOutVertically(targetOffsetY = { it }) + fadeOut()),
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
                    Text(getLocalized("¿Eliminar canción?", "Delete Track?"), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(getLocalized("¿Estás seguro de que deseas eliminar permanentemente esta canción de tu dispositivo?", "Are you sure you want to permanently delete this track from your device?"), color = Color.White.copy(alpha = 0.8f))
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
                    Text(getLocalized("Eliminar", "Delete"), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { songToDelete = null }) {
                    Text(getLocalized("Cancelar", "Cancel"), color = Color.White.copy(alpha = 0.6f))
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

    if (songForTagEditing != null) {
        TagEditorDialog(
            song = songForTagEditing!!,
            viewModel = viewModel,
            onDismiss = { songForTagEditing = null }
        )
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongListView(
    songs: List<AudioFile>,
    onSongClick: (AudioFile) -> Unit,
    onSongLongClick: (AudioFile) -> Unit = {},
    onEditTagsClick: (AudioFile) -> Unit = {},
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
    var dragY by remember { mutableStateOf(0f) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)) {
        val totalHeight = constraints.maxHeight.toFloat()
        val constraintsMaxHeight = maxHeight
        
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, 
                end = 24.dp, // Extra space for the sidebar
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
                                    text = { Text("Editar información") },
                                    onClick = {
                                        showMenu = false
                                        onEditTagsClick(song)
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) }
                                )
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
                    .width(44.dp)
                    .pointerInput(alphabet) {
                        detectVerticalDragGestures(
                            onDragStart = { showAlphabetPopup = true },
                            onDragEnd = { showAlphabetPopup = false },
                            onDragCancel = { showAlphabetPopup = false },
                            onVerticalDrag = { change, _ ->
                                val y = change.position.y
                                dragY = y.coerceIn(0f, totalHeight)
                                
                                val totalIndex = ((dragY / totalHeight) * alphabet.size).toInt().coerceIn(0, alphabet.size - 1)
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
                // The visible track (like Spotify green line)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp, top = 20.dp, bottom = 20.dp)
                        .fillMaxHeight()
                        .width(2.dp)
                        .background(
                            if (showAlphabetPopup) MaterialTheme.colorScheme.primary 
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f),
                            CircleShape
                        )
                )
            }

            // The letter popup that follows the finger
            if (showAlphabetPopup) {
                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 60.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .size(72.dp)
                            .align(Alignment.TopEnd)
                            .offset(y = with(density) { 
                                (dragY - 36.dp.toPx()).toDp().coerceIn(0.dp, constraintsMaxHeight - 72.dp)
                            }),
                        shape = RoundedCornerShape(topStart = 36.dp, bottomStart = 36.dp, topEnd = 36.dp, bottomEnd = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 16.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = currentLetter.toString(),
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Black,
                                color = Color.Black
                            )
                        }
                    }
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

fun savePlaylistCoverLocally(context: android.content.Context, playlistName: String, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val cleanName = playlistName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val coverFile = java.io.File(context.filesDir, "playlist_cover_$cleanName.jpg")
        val outputStream = java.io.FileOutputStream(coverFile)
        inputStream.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
        coverFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// ---------------- PLAYLIST GRID VIEW ----------------
@Composable
fun PlaylistGridView(
    playlists: Map<String, List<AudioFile>>,
    playlistCovers: Map<String, String>,
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

            val currentCover = playlistCovers[name]
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
                        .background(if (currentCover != null) SolidColor(Color.Transparent) else getGradientForString(name))
                ) {
                    if (currentCover != null) {
                        coil.compose.SubcomposeAsyncImage(
                            model = java.io.File(currentCover),
                            contentDescription = "Playlist Cover",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            error = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(getGradientForString(name))
                                )
                            }
                        )
                    }
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.1f),
                                        Color.Black.copy(alpha = 0.6f)
                                    )
                                )
                            )
                    )

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
                                        val results = searchLyricsOptionsFromLrcLib("", searchQuery)
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
