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
import androidx.compose.runtime.saveable.Saver
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

sealed interface SubView {
    data class AlbumDetail(val albumName: String) : SubView
    data class ArtistDetail(val artistName: String) : SubView
    data class GenreDetail(val genreName: String) : SubView
    data class FolderDetail(val folderName: String) : SubView
    data class PlaylistDetail(val playlistName: String) : SubView
}

val SubViewSaver = Saver<SubView?, Any>(
    save = { subView ->
        when (subView) {
            is SubView.AlbumDetail -> listOf("Album", subView.albumName)
            is SubView.ArtistDetail -> listOf("Artist", subView.artistName)
            is SubView.GenreDetail -> listOf("Genre", subView.genreName)
            is SubView.FolderDetail -> listOf("Folder", subView.folderName)
            is SubView.PlaylistDetail -> listOf("Playlist", subView.playlistName)
            null -> null
        }
    },
    restore = { value ->
        val list = value as? List<*> ?: return@Saver null
        val type = list.getOrNull(0) as? String
        val name = list.getOrNull(1) as? String
        if (type != null && name != null) {
            when (type) {
                "Album" -> SubView.AlbumDetail(name)
                "Artist" -> SubView.ArtistDetail(name)
                "Genre" -> SubView.GenreDetail(name)
                "Folder" -> SubView.FolderDetail(name)
                "Playlist" -> SubView.PlaylistDetail(name)
                else -> null
            }
        } else null
    }
)

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
    isActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val songsListState = rememberLazyListState()
    val subViewSongsListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val context = LocalContext.current
    val systemLang = remember { context.resources.configuration.locales[0].language }
    val getLocalized = { es: String, en: String ->
        if (systemLang == "es") es else en
    }
    var selectedTab by rememberSaveable { mutableStateOf("Songs") }
    var currentSubView by rememberSaveable(stateSaver = SubViewSaver) { mutableStateOf<SubView?>(null) }

    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedSongs = remember { mutableStateListOf<AudioFile>() }

    // Intercept system back gestures to exit active subview details or multi-select first
    BackHandler(enabled = isActive && (isMultiSelectMode || currentSubView != null)) {
        if (isMultiSelectMode) {
            isMultiSelectMode = false
            selectedSongs.clear()
        } else {
            currentSubView = null
        }
    }

    LaunchedEffect(Unit) {
        if (audioFiles.isEmpty()) {
            viewModel?.scanFiles(isManual = false)
        }
    }

    var songToDelete by remember { mutableStateOf<AudioFile?>(null) }
    var songForPlaylist by remember { mutableStateOf<AudioFile?>(null) }
    var songForTagEditing by remember { mutableStateOf<AudioFile?>(null) }
    var albumForCoverEditing by remember { mutableStateOf<String?>(null) }
    var albumForEditing by remember { mutableStateOf<String?>(null) }
    var songForOptionsSheet by remember { mutableStateOf<AudioFile?>(null) }
    var playlistContextForOptionsSheet by remember { mutableStateOf<String?>(null) }
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
            "Alphabetical" -> filtered.sortedWith { songA, songB ->
                val charA = songA.title.firstOrNull()?.uppercaseChar() ?: '#'
                val charB = songB.title.firstOrNull()?.uppercaseChar() ?: '#'
                
                val typeA = when {
                    charA.isDigit() -> 0  // '#'
                    charA in 'A'..'Z' -> 1 // Letters
                    else -> 2             // '?'
                }
                val typeB = when {
                    charB.isDigit() -> 0
                    charB in 'A'..'Z' -> 1
                    else -> 2
                }
                
                if (typeA != typeB) {
                    typeA.compareTo(typeB)
                } else {
                    songA.title.lowercase().compareTo(songB.title.lowercase())
                }
            }
            "Artist" -> filtered.sortedBy { it.artist.lowercase() }
            "Duration" -> filtered.sortedByDescending { it.duration }
            else -> filtered
        }
    }

    // Active song target index calculations for scrolling
    val currentPlayingMediaId = player?.currentMediaItem?.mediaId?.toLongOrNull()
    val scrollTargetIndex = remember(currentPlayingMediaId, selectedTab, currentSubView, filteredFiles, audioFiles, viewModel?.playlists, viewModel?.smartPlaylists) {
        if (currentPlayingMediaId == null) return@remember -1

        if (currentSubView == null) {
            if (selectedTab == "Songs") {
                filteredFiles.indexOfFirst { it.id == currentPlayingMediaId }
            } else -1
        } else {
            val subSongs = when (val sv = currentSubView) {
                is SubView.AlbumDetail -> filteredFiles.filter { it.album == sv.albumName }
                is SubView.ArtistDetail -> filteredFiles.filter { it.artist == sv.artistName }
                is SubView.GenreDetail -> filteredFiles.filter { it.genre == sv.genreName }
                is SubView.FolderDetail -> filteredFiles.filter { it.folderName == sv.folderName }
                is SubView.PlaylistDetail -> {
                    viewModel?.smartPlaylists?.get(sv.playlistName)
                        ?: viewModel?.playlists?.get(sv.playlistName) ?: emptyList()
                }
                else -> emptyList()
            }
            subSongs.indexOfFirst { it.id == currentPlayingMediaId }
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

                    val showLocateButtonMain = activeTab == "Songs" && scrollTargetIndex != -1
                    AnimatedVisibility(
                        visible = showLocateButtonMain,
                        enter = expandHorizontally() + fadeIn(),
                        exit = shrinkHorizontally() + fadeOut()
                    ) {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    songsListState.animateScrollToItem(scrollTargetIndex)
                                }
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = "Ir a canción actual",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

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
                        if (viewModel?.isScanning?.value == true) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = getLocalized("Buscando música en el dispositivo...", "Scanning device for music..."),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                                )
                            }
                        } else {
                            Text(
                                text = getLocalized("No se encontraron archivos de música.", "No music files found."),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
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
                                        listState = songsListState,
                                        onSongClick = { song ->
                                            songForOptionsSheet = song
                                            playlistContextForOptionsSheet = null
                                        },
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
                                        },
                                        onSelectionChanged = { newSelection ->
                                            selectedSongs.clear()
                                            selectedSongs.addAll(newSelection)
                                            isMultiSelectMode = newSelection.isNotEmpty()
                                        },
                                        onPlayDirectly = { song ->
                                            onFileClick(song, filteredFiles)
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
                                    val allPlaylists = remember(viewModel?.playlists?.keys?.toList(), viewModel?.smartPlaylists?.keys?.toList()) {
                                        val map = mutableMapOf<String, List<AudioFile>>()
                                        viewModel?.smartPlaylists?.forEach { (name, list) ->
                                            map[name] = list
                                        }
                                        viewModel?.playlists?.forEach { (name, list) ->
                                            map[name] = list
                                        }
                                        map
                                    }
                                    PlaylistGridView(
                                        viewModel = viewModel,
                                        playlists = allPlaylists,
                                        playlistCovers = viewModel?.playlistCovers ?: emptyMap(),
                                        onCreatePlaylist = { viewModel?.createPlaylist(it) },
                                        onCreateSmartPlaylist = { name, rule, limit, isAdvanced, advRule ->
                                            viewModel?.createSmartPlaylist(name, rule, limit, isAdvanced, advRule)
                                        },
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
                 val subSongs = remember(filteredFiles, subView, viewModel?.playlists, viewModel?.smartPlaylists, playlistSortBy) {
                    val baseList = when (subView) {
                        is SubView.AlbumDetail -> filteredFiles.filter { it.album == subView.albumName }
                        is SubView.ArtistDetail -> filteredFiles.filter { it.artist == subView.artistName }
                        is SubView.GenreDetail -> filteredFiles.filter { it.genre == subView.genreName }
                        is SubView.FolderDetail -> filteredFiles.filter { it.folderName == subView.folderName }
                        is SubView.PlaylistDetail -> {
                            viewModel?.smartPlaylists?.get(subView.playlistName)
                                ?: viewModel?.playlists?.get(subView.playlistName) ?: emptyList()
                        }
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
                        val isSmart = viewModel?.smartPlaylists?.containsKey(subView.playlistName) == true || subView.playlistName == "Más Escuchadas" || subView.playlistName.startsWith("Recomendaciones")
                        if (!isSmart) {
                            val savedPath = savePlaylistCoverLocally(context, subView.playlistName, uri)
                            if (savedPath != null) {
                                viewModel?.setPlaylistCover(subView.playlistName, savedPath)
                            }
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    val isSmart = subView is SubView.PlaylistDetail && (viewModel?.smartPlaylists?.containsKey(subView.playlistName) == true || subView.playlistName == "Más Escuchadas" || subView.playlistName.startsWith("Recomendaciones"))
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
                            val isSmart = viewModel?.smartPlaylists?.containsKey(subView.playlistName) == true || subView.playlistName == "Más Escuchadas" || subView.playlistName.startsWith("Recomendaciones")
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (currentCover != null) SolidColor(Color.Transparent) else getGradientForString(subView.playlistName))
                                    .clickable(enabled = !isSmart) {
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
                                        imageVector = if (isSmart) Icons.Rounded.AutoAwesome else Icons.Rounded.Edit,
                                        contentDescription = if (isSmart) "Smart Playlist" else "Edit Cover",
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        } else if (subView is SubView.AlbumDetail) {
                            val firstSong = subSongs.firstOrNull()
                            val artBytes = rememberAlbumArt(firstSong?.uriString)
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(getGradientForString(subView.albumName))
                                    .clickable { albumForCoverEditing = subView.albumName },
                                contentAlignment = Alignment.Center
                            ) {
                                coil.compose.SubcomposeAsyncImage(
                                    model = artBytes,
                                    contentDescription = "Album Cover",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                    loading = {
                                        Icon(
                                            imageVector = Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    error = {
                                        Icon(
                                            imageVector = Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                )
                                // Sleek edit overlay icon
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = "Edit Album Cover",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isSmart) "LISTA INTELIGENTE" else typeLabel.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        val showLocateButtonSub = scrollTargetIndex != -1
                        AnimatedVisibility(
                            visible = showLocateButtonSub,
                            enter = expandHorizontally() + fadeIn(),
                            exit = shrinkHorizontally() + fadeOut()
                        ) {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        subViewSongsListState.animateScrollToItem(scrollTargetIndex)
                                    }
                                },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                ),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = "Ir a canción actual",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        if (showLocateButtonSub && (subView is SubView.PlaylistDetail || subView is SubView.AlbumDetail)) {
                            Spacer(modifier = Modifier.width(8.dp))
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
                                            imageVector = Icons.AutoMirrored.Rounded.Sort,
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
                                if (!isSmart) {
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

                        if (subView is SubView.AlbumDetail) {
                            IconButton(
                                onClick = { albumForEditing = subView.albumName },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                ),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = "Edit Album",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Songs listing under the sub-view
                    SongListView(
                        songs = subSongs,
                        listState = subViewSongsListState,
                        onSongClick = { song ->
                            songForOptionsSheet = song
                            playlistContextForOptionsSheet = if (subView is SubView.PlaylistDetail) {
                                val isSmart = viewModel?.smartPlaylists?.containsKey(subView.playlistName) == true || subView.playlistName == "Más Escuchadas" || subView.playlistName.startsWith("Recomendaciones")
                                if (isSmart) null else subView.playlistName
                            } else null
                        },
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
                        },
                        onSelectionChanged = { newSelection ->
                            selectedSongs.clear()
                            selectedSongs.addAll(newSelection)
                            isMultiSelectMode = newSelection.isNotEmpty()
                        },
                        onPlayDirectly = { song ->
                            onFileClick(song, subSongs)
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
                        items(list, key = { it }) { name ->
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


    if (songForOptionsSheet != null) {
        SongOptionsBottomSheet(
            song = songForOptionsSheet!!,
            playlistContextName = playlistContextForOptionsSheet,
            onDismissRequest = { songForOptionsSheet = null },
            onPlayNextClick = {
                val s = songForOptionsSheet!!
                songForOptionsSheet = null
                viewModel?.playNext(s)
            },
            onAddToQueueClick = {
                val s = songForOptionsSheet!!
                songForOptionsSheet = null
                viewModel?.addToQueue(s)
            },
            onAddToPlaylistClick = {
                val s = songForOptionsSheet!!
                songForOptionsSheet = null
                songForPlaylist = s
            },
            onRemoveFromPlaylistClick = {
                val s = songForOptionsSheet!!
                val ctx = playlistContextForOptionsSheet!!
                songForOptionsSheet = null
                viewModel?.removeSongFromPlaylist(ctx, s.id)
            },
            onEditMetadataClick = {
                val s = songForOptionsSheet!!
                songForOptionsSheet = null
                songForTagEditing = s
            },
            onDeleteClick = {
                val s = songForOptionsSheet!!
                songForOptionsSheet = null
                songToDelete = s
            }
        )
    }

    if (albumForCoverEditing != null) {
        AlbumCoverEditorDialog(
            albumName = albumForCoverEditing!!,
            viewModel = viewModel,
            onDismiss = { albumForCoverEditing = null }
        )
    }

    if (albumForEditing != null) {
        AlbumEditorDialog(
            albumName = albumForEditing!!,
            viewModel = viewModel,
            onDismiss = { newName ->
                if (newName != null && currentSubView is SubView.AlbumDetail) {
                    currentSubView = SubView.AlbumDetail(newName)
                }
                albumForEditing = null
            }
        )
    }

}
