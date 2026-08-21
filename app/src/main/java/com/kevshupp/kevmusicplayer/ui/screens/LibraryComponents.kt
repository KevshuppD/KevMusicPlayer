package com.kevshupp.kevmusicplayer.ui.screens

import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import com.kevshupp.kevmusicplayer.playback.getPhysicalPath
import com.kevshupp.kevmusicplayer.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
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
    val colors = GradientPairs[index]
    return Brush.linearGradient(colors)
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
    onSongSelectToggle: ((AudioFile) -> Unit)? = null,
    onSelectionChanged: ((Set<AudioFile>) -> Unit)? = null,
    onPlayDirectly: ((AudioFile) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    showTrackNumbers: Boolean = false,
    headerContent: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    

    


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
            if (headerContent != null) {
                headerContent()
            }
            itemsIndexed(
                items = songs,
                key = { _, song -> song.id },
                contentType = { _, _ -> "song_item" }
            ) { index, song ->
                val isSelected = selectedSongs.contains(song)
                
                val currentSong by rememberUpdatedState(song)
                val currentIndex by rememberUpdatedState(index)
                val currentSongsList by rememberUpdatedState(songs)
                val currentSelectedSongsSet by rememberUpdatedState(selectedSongs)
                val currentIsMultiSelectModeVal by rememberUpdatedState(isMultiSelectMode)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .pointerInput(song) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = true)
                                var hasDragged = false
                                var longPressTriggered = false
                                val longPressTimeout = 400L
                                val startTime = System.currentTimeMillis()
                                val touchSlop = viewConfiguration.touchSlop
                                
                                var currentY = down.position.y
                                var targetIndex = currentIndex
                                
                                val startSelection = currentSelectedSongsSet.toSet()
                                val startSelecting = !startSelection.contains(currentSong)
                                
                                var isDraggingActive = true
                                var dragViewportY: Float? = null
                                var scrollJob: Job? = null
                                
                                fun updateSelectionAtY(viewportY: Float) {
                                    val pressedItemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == currentIndex }
                                    if (pressedItemInfo != null) {
                                        val hoverItem = listState.layoutInfo.visibleItemsInfo.find { itemInfo ->
                                            viewportY.toInt() in itemInfo.offset..(itemInfo.offset + itemInfo.size)
                                        }
                                        val sList = currentSongsList
                                        if (hoverItem != null && hoverItem.index in sList.indices) {
                                            val hoverIndex = hoverItem.index
                                            if (hoverIndex != targetIndex) {
                                                targetIndex = hoverIndex
                                                
                                                val start = minOf(currentIndex, hoverIndex)
                                                val end = maxOf(currentIndex, hoverIndex)
                                                val rangeSongs = sList.subList(start, end + 1)
                                                
                                                val newSelection = startSelection.toMutableSet()
                                                if (startSelecting) {
                                                    newSelection.addAll(rangeSongs)
                                                } else {
                                                    newSelection.removeAll(rangeSongs)
                                                }
                                                onSelectionChanged?.invoke(newSelection)
                                            }
                                        }
                                    }
                                }
                                
                                fun startScrollIfNeeded() {
                                    if (scrollJob == null) {
                                        scrollJob = coroutineScope.launch {
                                            while (isDraggingActive) {
                                                val y = dragViewportY
                                                if (y != null) {
                                                    val viewportHeight = listState.layoutInfo.viewportSize.height
                                                    val threshold = 120f
                                                    var scrollAmount = 0f
                                                    
                                                    if (y < threshold) {
                                                        val factor = (threshold - y) / threshold
                                                        scrollAmount = -18f * factor.coerceIn(0.2f, 1.0f)
                                                    } else if (y > viewportHeight - threshold) {
                                                        val factor = (y - (viewportHeight - threshold)) / threshold
                                                        scrollAmount = 18f * factor.coerceIn(0.2f, 1.0f)
                                                    }
                                                    
                                                    if (scrollAmount != 0f) {
                                                        listState.scrollBy(scrollAmount)
                                                        updateSelectionAtY(y)
                                                    }
                                                }
                                                delay(16)
                                            }
                                        }
                                    }
                                }
                                
                                try {
                                    while (true) {
                                        val elapsed = System.currentTimeMillis() - startTime
                                        val remaining = longPressTimeout - elapsed
                                        
                                        val event = if (remaining > 0 && !longPressTriggered) {
                                            withTimeoutOrNull(remaining) {
                                                awaitPointerEvent(PointerEventPass.Main)
                                            }
                                        } else {
                                            awaitPointerEvent(PointerEventPass.Main)
                                        }
                                        
                                        if (event == null) {
                                            // Timeout reached! Long press triggers selection mode directly
                                            longPressTriggered = true
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            onSongLongClick(currentSong)
                                            continue
                                        }
                                        
                                        val changes = event.changes
                                        val anyPressed = changes.any { it.pressed }
                                        
                                        if (!anyPressed) {
                                            // Released!
                                            if (elapsed < longPressTimeout) {
                                                // Tap / Click!
                                                if (currentIsMultiSelectModeVal) {
                                                    onSongSelectToggle?.invoke(currentSong)
                                                } else {
                                                    onPlayDirectly?.invoke(currentSong)
                                                }
                                            }
                                            break
                                        } else {
                                            // Still holding
                                            val change = changes.firstOrNull()
                                            if (change != null) {
                                                if (change.isConsumed) {
                                                    break
                                                }
                                                currentY = change.position.y
                                                val dragDistanceX = Math.abs(change.position.x - down.position.x)
                                                val dragDistanceY = Math.abs(currentY - down.position.y)
                                                val dragDistance = Math.max(dragDistanceX, dragDistanceY)
                                                
                                                // If they scroll/drag too much BEFORE long press, cancel and let parent handle scrolling
                                                if (!longPressTriggered && dragDistance > touchSlop) {
                                                    break
                                                }
                                                
                                                if (longPressTriggered) {
                                                    change.consume()
                                                    val pressedItemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == currentIndex }
                                                    if (pressedItemInfo != null) {
                                                        val viewportY = pressedItemInfo.offset + currentY
                                                        dragViewportY = viewportY
                                                        hasDragged = true
                                                        
                                                        updateSelectionAtY(viewportY)
                                                        startScrollIfNeeded()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    isDraggingActive = false
                                    scrollJob?.cancel()
                                }
                            }
                        }
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
                    if (isMultiSelectMode) {
                        Icon(
                            imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = "Selection",
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(end = 12.dp).size(24.dp)
                        )
                    }
                    if (showTrackNumbers) {
                        Box(
                            modifier = Modifier
                                .size(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val trackNum = if (song.track > 0) (song.track % 1000) else (index + 1)
                            Text(
                                text = trackNum.toString(),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontSize = 15.sp
                            )
                        }
                    } else {
                        // Sleek Gradient Song Icon
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(12.dp) else androidx.compose.ui.graphics.RectangleShape)
                                .background(getGradientForString(song.title)),
                            contentAlignment = Alignment.Center
                        ) {
                            val artBytes = rememberAlbumArt(song.uriString)
                            if (artBytes != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = artBytes.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${song.artist} • ${song.album}",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    if (!isMultiSelectMode) {
                        IconButton(
                            onClick = { onSongClick(song) }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "Options",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
            }
        }
    }
        val songTitles = remember(songs) { songs.map { it.title } }
        FastScrollSidebar(
            items = songTitles,
            listState = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AlbumGridView(
    albums: Map<String, List<AudioFile>>,
    onAlbumClick: (String) -> Unit,
    onDeleteAlbum: (String, List<AudioFile>) -> Unit,
    onAddAlbumToPlaylist: (String, List<AudioFile>) -> Unit,
    onEditAlbum: (String, List<AudioFile>) -> Unit,
    onShowAlbumInfo: (String, List<AudioFile>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val systemLang = remember { context.resources.configuration.locales[0].language }
    val getLocalized = { es: String, en: String ->
        if (systemLang == "es") es else en
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(albums.keys.toList(), key = { it }) { albumName ->
            val albumSongs = albums[albumName] ?: emptyList()
            var showMenu by remember { mutableStateOf(false) }

            Box {
                Card(
                    shape = if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(20.dp) else androidx.compose.ui.graphics.RectangleShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.82f)
                        .combinedClickable(
                            onClick = { onAlbumClick(albumName) },
                            onLongClick = { showMenu = true }
                        )
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(20.dp) else androidx.compose.ui.graphics.RectangleShape)
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
                                text = "${albumSongs.size} ${if (albumSongs.size == 1) getLocalized("Tema", "Track") else getLocalized("Temas", "Tracks")}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = Color(0xFF161829)
                ) {
                    DropdownMenuItem(
                        text = { Text(getLocalized("Eliminar Álbum", "Delete Album"), color = Color.White) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDeleteAlbum(albumName, albumSongs)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(getLocalized("Agregar a Playlist", "Add to Playlist"), color = Color.White) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = {
                            showMenu = false
                            onAddAlbumToPlaylist(albumName, albumSongs)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(getLocalized("Editar Metadatos", "Edit Metadata"), color = Color.White) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = {
                            showMenu = false
                            onEditAlbum(albumName, albumSongs)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(getLocalized("Mostrar Información", "Show Info"), color = Color.White) },
                        leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = {
                            showMenu = false
                            onShowAlbumInfo(albumName, albumSongs)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ArtistListView(
    artists: Map<String, List<AudioFile>>,
    onArtistClick: (String) -> Unit,
    listState: LazyListState = rememberLazyListState()
) {
    val artistNames = remember(artists) { artists.keys.toList() }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 28.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(artistNames, key = { it }) { artistName ->
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
                    ArtistImage(
                        artist = artistName,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                    )

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

        FastScrollSidebar(
            items = artistNames,
            listState = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

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
        items(genres.keys.toList(), key = { it }) { genreName ->
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
                        .clip(if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(12.dp) else androidx.compose.ui.graphics.RectangleShape)
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
        items(folders.keys.toList(), key = { it }) { folderName ->
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
    var shuffleModeEnabled by remember { mutableStateOf(player?.shuffleModeEnabled ?: false) }
    var mediaItemCount by remember { mutableStateOf(player?.mediaItemCount ?: 0) }
    var currentMediaItemIndex by remember { mutableStateOf(player?.currentMediaItemIndex ?: 0) }
    var playlistVersion by remember { mutableStateOf(0) }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}

        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                isPlaying = player.isPlaying
                currentSong = player.currentMediaItem
                position = player.currentPosition
                duration = player.duration.coerceAtLeast(0L)
                shuffleModeEnabled = player.shuffleModeEnabled
                mediaItemCount = player.mediaItemCount
                currentMediaItemIndex = player.currentMediaItemIndex
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                    playlistVersion++
                }
            }
        }
        player.addListener(listener)
        isPlaying = player.isPlaying
        currentSong = player.currentMediaItem
        position = player.currentPosition
        duration = player.duration.coerceAtLeast(0L)
        shuffleModeEnabled = player.shuffleModeEnabled
        mediaItemCount = player.mediaItemCount
        currentMediaItemIndex = player.currentMediaItemIndex
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

    return PlayerStateInfo(isPlaying, currentSong, position, duration, shuffleModeEnabled, mediaItemCount, currentMediaItemIndex, playlistVersion)
}

data class PlayerStateInfo(
    val isPlaying: Boolean,
    val currentSong: MediaItem?,
    val position: Long,
    val duration: Long,
    val shuffleModeEnabled: Boolean,
    val mediaItemCount: Int,
    val currentMediaItemIndex: Int,
    val playlistVersion: Int
)

// ---------------- ALBUM ART CACHE & ASYNC LOADER ----------------
private val defaultCacheBudgetKb = ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceIn(16 * 1024, 48 * 1024)

val albumArtCache = object : LruCache<String, android.graphics.Bitmap>(defaultCacheBudgetKb) {
    override fun sizeOf(key: String, bitmap: android.graphics.Bitmap): Int {
        return bitmap.byteCount / 1024
    }
}

var albumArtVersion by androidx.compose.runtime.mutableStateOf(0)

fun updateAlbumArtCacheSize(maxSizeItems: Int) {
    try {
        val estimatedSizeKb = (maxSizeItems * 300).coerceIn(16 * 1024, 64 * 1024)
        albumArtCache.resize(estimatedSizeKb)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
    val options = android.graphics.BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    return try {
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (e: OutOfMemoryError) {
        System.gc()
        try {
            options.inSampleSize *= 2
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (t: Throwable) {
            null
        }
    } catch (e: Exception) {
        null
    }
}

private fun decodeSampledBitmapFromFile(path: String, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
    val options = android.graphics.BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    android.graphics.BitmapFactory.decodeFile(path, options)
    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    return try {
        android.graphics.BitmapFactory.decodeFile(path, options)
    } catch (e: OutOfMemoryError) {
        System.gc()
        try {
            options.inSampleSize *= 2
            android.graphics.BitmapFactory.decodeFile(path, options)
        } catch (t: Throwable) {
            null
        }
    } catch (e: Exception) {
        null
    }
}

fun preloadAlbumArt(context: android.content.Context, uriString: String) {
    if (albumArtCache.get(uriString) != null) return
    loadAlbumArtBitmap(context, uriString)
}

fun getDiskCacheFile(context: android.content.Context, uriString: String, res: Int): java.io.File {
    val dir = java.io.File(context.cacheDir, "album_art_thumbnails")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val md5Key = try {
        val bytes = java.security.MessageDigest.getInstance("MD5").digest("$uriString-$res".toByteArray())
        bytes.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        uriString.hashCode().toString() + "_$res"
    }
    return java.io.File(dir, "$md5Key.webp")
}

private fun saveBitmapToDiskCache(context: android.content.Context, file: java.io.File, bitmap: android.graphics.Bitmap) {
    val quality = try {
        context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE).getInt("disk_cache_quality", 85)
    } catch (e: Exception) {
        85
    }
    try {
        java.io.FileOutputStream(file).use { out ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val format = if (quality >= 100) android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS else android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
                bitmap.compress(format, quality, out)
            } else {
                @Suppress("DEPRECATION")
                bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, quality, out)
            }
        }
    } catch (e: Exception) {
        file.delete()
    }
}

fun clearDiskAlbumArtCache(context: android.content.Context) {
    try {
        val dir = java.io.File(context.cacheDir, "album_art_thumbnails")
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { it.delete() }
        }
    } catch (e: Exception) {}
}

fun deleteDiskAlbumArtCacheForUri(context: android.content.Context, uriString: String) {
    try {
        val res = try {
            context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE).getInt("art_resolution", 500)
        } catch (e: Exception) { 500 }
        val diskFile = getDiskCacheFile(context, uriString, res)
        if (diskFile.exists()) {
            diskFile.delete()
        }
    } catch (e: Exception) {}
}

fun getDiskAlbumArtCacheSizeBytes(context: android.content.Context): Long {
    return try {
        val dir = java.io.File(context.cacheDir, "album_art_thumbnails")
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.sumOf { it.length() } ?: 0L
        } else {
            0L
        }
    } catch (e: Exception) {
        0L
    }
}

fun loadAlbumArtBitmapSync(context: android.content.Context, uriString: String): android.graphics.Bitmap? {
    if (albumArtCache.get(uriString) != null) return albumArtCache.get(uriString)
    val res = try {
        context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE).getInt("art_resolution", 500)
    } catch (e: Exception) { 500 }

    val diskFile = getDiskCacheFile(context, uriString, res)
    if (diskFile.exists() && diskFile.isFile) {
        if (diskFile.length() == 0L) {
            return null
        }
        val bmp = decodeSampledBitmapFromFile(diskFile.absolutePath, res, res)
        if (bmp != null) {
            albumArtCache.put(uriString, bmp)
            return bmp
        }
    }
    return null
}

fun loadAlbumArtBitmap(context: android.content.Context, uriString: String): android.graphics.Bitmap? {
    val cachedRam = albumArtCache.get(uriString)
    if (cachedRam != null) return cachedRam

    val res = try {
        context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE).getInt("art_resolution", 500)
    } catch (e: Exception) { 500 }

    val diskFile = getDiskCacheFile(context, uriString, res)
    if (diskFile.exists() && diskFile.isFile) {
        if (diskFile.length() == 0L) {
            return null
        }
        val bmp = decodeSampledBitmapFromFile(diskFile.absolutePath, res, res)
        if (bmp != null) {
            albumArtCache.put(uriString, bmp)
            return bmp
        }
    }

    val retriever = android.media.MediaMetadataRetriever()
    var pfd: android.os.ParcelFileDescriptor? = null
    var decodedResult: android.graphics.Bitmap? = null

    // 1. Try reading directly from physical path
    try {
        val songId = uriString.substringAfterLast("/").toLongOrNull()
        val physicalPath = getPhysicalPath(context, songId ?: 0L, uriString)
        if (!physicalPath.isNullOrBlank()) {
            val file = java.io.File(physicalPath)
            if (file.exists() && file.isFile) {
                retriever.setDataSource(physicalPath)
                val picture = retriever.embeddedPicture
                if (picture != null) {
                    decodedResult = decodeSampledBitmap(picture, res, res)
                }
            }
        }
    } catch (e: Exception) {}

    // 2. Fallback to ParcelFileDescriptor / Uri
    if (decodedResult == null) {
        try {
            pfd = context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
            if (pfd != null) {
                retriever.setDataSource(pfd.fileDescriptor)
                val picture = retriever.embeddedPicture
                if (picture != null) {
                    decodedResult = decodeSampledBitmap(picture, res, res)
                }
            }
        } catch (e: Exception) {
            try {
                retriever.setDataSource(context, Uri.parse(uriString))
                val picture = retriever.embeddedPicture
                if (picture != null) {
                    decodedResult = decodeSampledBitmap(picture, res, res)
                }
            } catch (ex: Exception) {}
        } finally {
            try { pfd?.close() } catch (e: Exception) {}
        }
    }

    // 3. Fallback to folder cover file
    if (decodedResult == null) {
        try {
            val songId = uriString.substringAfterLast("/").toLongOrNull()
            val physicalPath = getPhysicalPath(context, songId ?: 0L, uriString)
            if (!physicalPath.isNullOrBlank()) {
                val audioFile = java.io.File(physicalPath)
                val parentDir = audioFile.parentFile
                if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
                    val coverNames = listOf("cover.jpg", "folder.jpg", "album.jpg", "front.jpg", "Cover.jpg", "Folder.jpg", "Album.jpg", "Front.jpg")
                    val foundCover = coverNames.map { java.io.File(parentDir, it) }.firstOrNull { it.exists() && it.isFile && it.length() > 0 }
                    if (foundCover != null) {
                        decodedResult = decodeSampledBitmapFromFile(foundCover.absolutePath, res, res)
                    }
                }
            }
        } catch (e: Exception) {}
    }

    try { retriever.release() } catch (e: Exception) {}

    if (decodedResult != null) {
        albumArtCache.put(uriString, decodedResult)
        saveBitmapToDiskCache(context, diskFile, decodedResult)
        return decodedResult
    } else {
        try { diskFile.createNewFile() } catch (e: Exception) {}
        return null
    }
}

@Composable
fun rememberAlbumArt(uriString: String?): android.graphics.Bitmap? {
    if (uriString == null) return null
    val context = LocalContext.current
    val version = albumArtVersion

    val initialBitmap = remember(uriString, version) {
        albumArtCache.get(uriString) ?: loadAlbumArtBitmapSync(context, uriString)
    }
    var bitmap by remember(uriString, version) { mutableStateOf(initialBitmap) }

    if (bitmap == null) {
        LaunchedEffect(uriString, version) {
            val loadedBmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loadAlbumArtBitmap(context, uriString)
            }
            if (loadedBmp != null) {
                bitmap = loadedBmp
            }
        }
    }
    return bitmap
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
class UiCondition(
    initialField: com.kevshupp.kevmusicplayer.playback.RuleField = com.kevshupp.kevmusicplayer.playback.RuleField.GENRE,
    initialOperator: com.kevshupp.kevmusicplayer.playback.RuleOperator = com.kevshupp.kevmusicplayer.playback.RuleOperator.CONTAINS,
    initialValue: String = ""
) {
    var field by androidx.compose.runtime.mutableStateOf(initialField)
    var operator by androidx.compose.runtime.mutableStateOf(initialOperator)
    var value by androidx.compose.runtime.mutableStateOf(initialValue)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConditionRow(
    condition: UiCondition,
    onDelete: () -> Unit,
    showDelete: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Regla:",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Field Selector
                var fieldExpanded by remember { mutableStateOf(false) }
                val fieldNames = mapOf(
                    com.kevshupp.kevmusicplayer.playback.RuleField.GENRE to "Género musical",
                    com.kevshupp.kevmusicplayer.playback.RuleField.ARTIST to "Artista / Cantante",
                    com.kevshupp.kevmusicplayer.playback.RuleField.ALBUM to "Nombre del Álbum",
                    com.kevshupp.kevmusicplayer.playback.RuleField.TITLE to "Título de la canción",
                    com.kevshupp.kevmusicplayer.playback.RuleField.YEAR to "Año de lanzamiento",
                    com.kevshupp.kevmusicplayer.playback.RuleField.PLAY_COUNT to "Nº reproducciones",
                    com.kevshupp.kevmusicplayer.playback.RuleField.DURATION_SECONDS to "Duración (segundos)",
                    com.kevshupp.kevmusicplayer.playback.RuleField.LAST_PLAYED_DAYS to "Días desde última repro",
                    com.kevshupp.kevmusicplayer.playback.RuleField.DATE_ADDED_DAYS to "Días desde que la agregaste"
                )
                
                Box(modifier = Modifier.weight(1.3f)) {
                    OutlinedButton(
                        onClick = { fieldExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(fieldNames[condition.field] ?: "", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    }
                    DropdownMenu(expanded = fieldExpanded, onDismissRequest = { fieldExpanded = false }) {
                        fieldNames.forEach { (field, name) ->
                            DropdownMenuItem(
                                text = { Text(name, fontSize = 12.sp) },
                                onClick = { condition.field = field; fieldExpanded = false }
                            )
                        }
                    }
                }

                // Operator Selector
                var operatorExpanded by remember { mutableStateOf(false) }
                val operatorNames = mapOf(
                    com.kevshupp.kevmusicplayer.playback.RuleOperator.CONTAINS to "contiene",
                    com.kevshupp.kevmusicplayer.playback.RuleOperator.EQUALS to "es igual a",
                    com.kevshupp.kevmusicplayer.playback.RuleOperator.STARTS_WITH to "empieza con",
                    com.kevshupp.kevmusicplayer.playback.RuleOperator.ENDS_WITH to "termina en",
                    com.kevshupp.kevmusicplayer.playback.RuleOperator.GREATER_THAN to "es mayor a",
                    com.kevshupp.kevmusicplayer.playback.RuleOperator.LESS_THAN to "es menor a"
                )

                Box(modifier = Modifier.weight(1.1f)) {
                    OutlinedButton(
                        onClick = { operatorExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(operatorNames[condition.operator] ?: "", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    }
                    DropdownMenu(expanded = operatorExpanded, onDismissRequest = { operatorExpanded = false }) {
                        operatorNames.forEach { (operator, name) ->
                            DropdownMenuItem(
                                text = { Text(name, fontSize = 12.sp) },
                                onClick = { condition.operator = operator; operatorExpanded = false }
                            )
                        }
                    }
                }

                if (showDelete) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = condition.value,
                onValueChange = { condition.value = it },
                placeholder = { Text("Escribe el texto o número...", fontSize = 11.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun PlaylistGridView(
    viewModel: MediaBrowserViewModel?,
    playlists: Map<String, List<AudioFile>>,
    playlistCovers: Map<String, String>,
    onCreatePlaylist: (String) -> Unit,
    onCreateSmartPlaylist: (String, com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule, Int, Boolean, com.kevshupp.kevmusicplayer.playback.SmartRuleNode?) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var isSmartDialog by remember { mutableStateOf(false) }
    var selectedRule by remember { mutableStateOf(com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.MOST_PLAYED) }
    var limitInput by remember { mutableStateOf("50") }
    val selectedSongIds = remember { mutableStateListOf<Long>() }
    var songSearchQuery by remember { mutableStateOf("") }

    var isAdvancedRules by remember { mutableStateOf(false) }
    val conditions = remember { mutableStateListOf<UiCondition>(UiCondition()) }
    var topLevelOperator by remember { mutableStateOf(com.kevshupp.kevmusicplayer.playback.LogicalOperator.AND) }
    
    var hasNestedGroup by remember { mutableStateOf(false) }
    var nestedOperator by remember { mutableStateOf(com.kevshupp.kevmusicplayer.playback.LogicalOperator.OR) }
    val nestedConditions = remember { mutableStateListOf<UiCondition>() }

    androidx.compose.runtime.LaunchedEffect(isSmartDialog, selectedRule, limitInput, isAdvancedRules) {
        if (isSmartDialog) {
            if (isAdvancedRules) {
                val cleanLimit = limitInput.toIntOrNull() ?: 50
                newPlaylistName = "Lista Inteligente ($cleanLimit)"
            } else {
                val ruleNames = mapOf(
                    com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.MOST_PLAYED to "Lo más escuchado",
                    com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.RECENTLY_ADDED to "Recién añadidas",
                    com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.PLAYBACK_HISTORY to "Historial de reproducción",
                    com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.LONGEST_SONGS to "Canciones más largas",
                    com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.SHORTEST_SONGS to "Canciones más cortas",
                    com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.NEVER_PLAYED to "Nunca escuchadas",
                    com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.RANDOM_MIX to "Mezcla aleatoria"
                )
                val cleanLimit = limitInput.toIntOrNull() ?: 50
                newPlaylistName = "${ruleNames[selectedRule]} ($cleanLimit)"
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Nueva lista de reproducción", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text("Lista Normal", color = MaterialTheme.colorScheme.onSurface)
                        Switch(
                            checked = isSmartDialog,
                            onCheckedChange = { isSmartDialog = it },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Text("Inteligente", color = MaterialTheme.colorScheme.onSurface)
                    }

                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("Nombre de la lista", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!isSmartDialog) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Sugerencias rápidas:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("🚗 En el Auto", "💪 Gimnasio", "🎉 Fiesta", "🎧 Chill", "✈️ Viaje", "❤️ Favoritas", "⚡ Noche").forEach { preset ->
                                Surface(
                                    onClick = { newPlaylistName = preset },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                                ) {
                                    Text(
                                        preset, 
                                        fontSize = 11.sp, 
                                        fontWeight = FontWeight.Bold, 
                                        color = MaterialTheme.colorScheme.primary, 
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(modifier = Modifier.height(8.dp))

                        // Interactive Song Selector
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Agregar canciones iniciales:",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selectedSongIds.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    "${selectedSongIds.size} seleccionadas",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedSongIds.isNotEmpty()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = songSearchQuery,
                            onValueChange = { songSearchQuery = it },
                            placeholder = { Text("Buscar canción o artista...", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            trailingIcon = {
                                if (songSearchQuery.isNotEmpty()) {
                                    IconButton(onClick = { songSearchQuery = "" }) {
                                        Icon(Icons.Rounded.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        val availableSongs = viewModel?.localAudioFiles ?: emptyList()
                        val filteredSongs = remember(availableSongs, songSearchQuery) {
                            if (songSearchQuery.isBlank()) availableSongs.take(30)
                            else availableSongs.filter { it.title.contains(songSearchQuery, ignoreCase = true) || it.artist.contains(songSearchQuery, ignoreCase = true) }.take(50)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .padding(top = 6.dp)
                        ) {
                            androidx.compose.foundation.lazy.LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(filteredSongs) { song ->
                                    val isSelected = selectedSongIds.contains(song.id)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                if (isSelected) selectedSongIds.remove(song.id)
                                                else selectedSongIds.add(song.id)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = {
                                                if (it) selectedSongIds.add(song.id)
                                                else selectedSongIds.remove(song.id)
                                            },
                                            modifier = Modifier.scale(0.85f)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(song.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                                            Text(song.artist, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isSmartDialog) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            Text("Modo:", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                            FilterChip(
                                selected = !isAdvancedRules,
                                onClick = { isAdvancedRules = false },
                                label = { Text("Básico") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                            FilterChip(
                                selected = isAdvancedRules,
                                onClick = { isAdvancedRules = true },
                                label = { Text("Avanzado") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }

                        if (!isAdvancedRules) {
                            Text("Regla de generación:", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                            
                            var expanded by remember { mutableStateOf(false) }
                            val ruleNames = mapOf(
                                com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.MOST_PLAYED to "Lo más escuchado",
                                com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.RECENTLY_ADDED to "Recién añadidas",
                                com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.PLAYBACK_HISTORY to "Historial de reproducción",
                                com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.LONGEST_SONGS to "Canciones más largas",
                                com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.SHORTEST_SONGS to "Canciones más cortas",
                                com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.NEVER_PLAYED to "Nunca escuchadas",
                                com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.RANDOM_MIX to "Mezcla aleatoria"
                            )

                            Box {
                                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(ruleNames[selectedRule] ?: "", color = MaterialTheme.colorScheme.onSurface)
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    ruleNames.forEach { (rule, name) ->
                                        DropdownMenuItem(
                                            text = { Text(name) },
                                            onClick = { selectedRule = rule; expanded = false }
                                        )
                                    }
                                }
                            }
                        } else {
                            Text("Constructor de Reglas Personalizadas:", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Establece las reglas para filtrar tus canciones.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("¿Cómo deben combinarse las reglas?", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Surface(
                                    onClick = { topLevelOperator = com.kevshupp.kevmusicplayer.playback.LogicalOperator.AND },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (topLevelOperator == com.kevshupp.kevmusicplayer.playback.LogicalOperator.AND) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    border = BorderStroke(1.dp, if (topLevelOperator == com.kevshupp.kevmusicplayer.playback.LogicalOperator.AND) Color.Transparent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Cumplir TODAS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (topLevelOperator == com.kevshupp.kevmusicplayer.playback.LogicalOperator.AND) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                        Text("Todas las reglas a la vez", fontSize = 9.sp, color = if (topLevelOperator == com.kevshupp.kevmusicplayer.playback.LogicalOperator.AND) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    }
                                }

                                Surface(
                                    onClick = { topLevelOperator = com.kevshupp.kevmusicplayer.playback.LogicalOperator.OR },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (topLevelOperator == com.kevshupp.kevmusicplayer.playback.LogicalOperator.OR) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    border = BorderStroke(1.dp, if (topLevelOperator == com.kevshupp.kevmusicplayer.playback.LogicalOperator.OR) Color.Transparent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Cumplir AL MENOS UNA", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (topLevelOperator == com.kevshupp.kevmusicplayer.playback.LogicalOperator.OR) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                        Text("Basta con que cumpla una", fontSize = 9.sp, color = if (topLevelOperator == com.kevshupp.kevmusicplayer.playback.LogicalOperator.OR) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    }
                                }
                            }
                            
                            conditions.forEachIndexed { index, cond ->
                                ConditionRow(
                                    condition = cond,
                                    onDelete = { conditions.removeAt(index) },
                                    showDelete = conditions.size > 1
                                )
                            }
                            
                            TextButton(
                                onClick = { conditions.add(UiCondition()) },
                                modifier = Modifier.align(Alignment.Start)
                            ) {
                                Icon(Icons.Rounded.AddCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Agregar otra regla", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = hasNestedGroup,
                                    onCheckedChange = { 
                                        hasNestedGroup = it
                                        if (it && nestedConditions.isEmpty()) {
                                            nestedConditions.add(UiCondition())
                                        }
                                    }
                                )
                                Column {
                                    Text("Filtro secundario opcional", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text("Reglas adicionales combinadas", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                            }
                            
                            if (hasNestedGroup) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("Coincidencia para filtro secundario:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        ) {
                                            FilterChip(
                                                selected = nestedOperator == com.kevshupp.kevmusicplayer.playback.LogicalOperator.AND,
                                                onClick = { nestedOperator = com.kevshupp.kevmusicplayer.playback.LogicalOperator.AND },
                                                label = { Text("Cumplir Todas", fontSize = 11.sp) }
                                            )
                                            FilterChip(
                                                selected = nestedOperator == com.kevshupp.kevmusicplayer.playback.LogicalOperator.OR,
                                                onClick = { nestedOperator = com.kevshupp.kevmusicplayer.playback.LogicalOperator.OR },
                                                label = { Text("Al menos una", fontSize = 11.sp) }
                                            )
                                        }
                                        
                                        nestedConditions.forEachIndexed { index, cond ->
                                            ConditionRow(
                                                condition = cond,
                                                onDelete = { nestedConditions.removeAt(index) },
                                                showDelete = nestedConditions.size > 1
                                            )
                                        }
                                        
                                        TextButton(
                                            onClick = { nestedConditions.add(UiCondition()) },
                                            modifier = Modifier.align(Alignment.Start)
                                        ) {
                                            Icon(Icons.Rounded.AddCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Agregar regla secundaria", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = limitInput,
                            onValueChange = { if (it.all { char -> char.isDigit() }) limitInput = it },
                            label = { Text("Límite de canciones", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                cursorColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            if (isSmartDialog) {
                                val limit = limitInput.toIntOrNull() ?: 50
                                if (isAdvancedRules) {
                                    val topLevelChildren = mutableListOf<com.kevshupp.kevmusicplayer.playback.SmartRuleNode>()
                                    conditions.forEach { uiCond ->
                                        if (uiCond.value.isNotBlank()) {
                                            topLevelChildren.add(
                                                com.kevshupp.kevmusicplayer.playback.ConditionNode(uiCond.field, uiCond.operator, uiCond.value)
                                            )
                                        }
                                    }
                                    if (hasNestedGroup && nestedConditions.isNotEmpty()) {
                                        val nestedChildren = mutableListOf<com.kevshupp.kevmusicplayer.playback.SmartRuleNode>()
                                        nestedConditions.forEach { uiCond ->
                                            if (uiCond.value.isNotBlank()) {
                                                nestedChildren.add(
                                                    com.kevshupp.kevmusicplayer.playback.ConditionNode(uiCond.field, uiCond.operator, uiCond.value)
                                                )
                                            }
                                        }
                                        if (nestedChildren.isNotEmpty()) {
                                            topLevelChildren.add(
                                                com.kevshupp.kevmusicplayer.playback.GroupNode(nestedOperator, nestedChildren)
                                            )
                                        }
                                    }
                                    val finalRuleNode = com.kevshupp.kevmusicplayer.playback.GroupNode(topLevelOperator, topLevelChildren)
                                    onCreateSmartPlaylist(newPlaylistName, com.kevshupp.kevmusicplayer.playback.SmartPlaylistRule.MOST_PLAYED, limit, true, finalRuleNode)
                                } else {
                                    onCreateSmartPlaylist(newPlaylistName, selectedRule, limit, false, null)
                                }
                            } else {
                                onCreatePlaylist(newPlaylistName)
                                selectedSongIds.forEach { songId ->
                                    viewModel?.addSongToPlaylist(newPlaylistName, songId)
                                }
                            }
                            newPlaylistName = ""
                            selectedSongIds.clear()
                            songSearchQuery = ""
                            limitInput = "50"
                            isAdvancedRules = false
                            conditions.clear()
                            conditions.add(UiCondition())
                            hasNestedGroup = false
                            nestedConditions.clear()
                            showCreateDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Crear", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showCreateDialog = false
                    selectedSongIds.clear()
                    songSearchQuery = ""
                }) {
                    Text("Cancelar", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        )
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Option to create a new playlist
        item {
            Card(
                onClick = { showCreateDialog = true },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                ),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                            contentDescription = "New Playlist",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Crear Lista",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        items(playlists.keys.toList(), key = { it }) { name ->
            val listSongs = playlists[name] ?: emptyList()
            val coverPath = playlistCovers[name]
            val isSmart = viewModel?.smartPlaylists?.containsKey(name) == true || name.startsWith("Recomendaciones")
            
            // Context menu state for playlist card
            var expandedMenu by remember { mutableStateOf(false) }

            Box {
                Card(
                    onClick = { onPlaylistClick(name) },
                    shape = if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(20.dp) else androidx.compose.ui.graphics.RectangleShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Display background cover or gradient
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(getGradientForString(name))
                        ) {
                            if (coverPath != null) {
                                SubcomposeAsyncImage(
                                    model = coverPath,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        // Gradient protection overlay
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                                        startY = 100f
                                    )
                                )
                        )

                        // Top right quick options button or Smart Playlist badge
                        if (isSmart) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .size(28.dp)
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = "Lista Inteligente",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { expandedMenu = true },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(32.dp)
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = "Playlist Options",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 18.sp
                            )
                            Text(
                                text = "${listSongs.size} Tracks",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                if (!isSmart) {
                    DropdownMenu(
                        expanded = expandedMenu,
                        onDismissRequest = { expandedMenu = false },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        DropdownMenuItem(
                            text = { Text("Eliminar Lista", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                expandedMenu = false
                                onDeletePlaylist(name)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongOptionsBottomSheet(
    song: AudioFile,
    playlistContextName: String? = null,
    onDismissRequest: () -> Unit,
    onPlayNextClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onRemoveFromPlaylistClick: (() -> Unit)? = null,
    onEditMetadataClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Song info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(14.dp) else androidx.compose.ui.graphics.RectangleShape)
                        .background(getGradientForString(song.title)),
                    contentAlignment = Alignment.Center
                ) {
                    val artBytes = rememberAlbumArt(song.uriString)
                    SubcomposeAsyncImage(
                        model = artBytes,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        error = {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OptionItem(
                    icon = Icons.Rounded.QueuePlayNext,
                    text = "Reproducir a continuación",
                    onClick = onPlayNextClick
                )
                OptionItem(
                    icon = Icons.Rounded.AddToPhotos,
                    text = "Agregar a la cola",
                    onClick = onAddToQueueClick
                )
                OptionItem(
                    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    text = "Agregar a playlist",
                    onClick = onAddToPlaylistClick
                )
                if (playlistContextName != null && onRemoveFromPlaylistClick != null) {
                    OptionItem(
                        icon = Icons.Rounded.PlaylistRemove,
                        text = "Eliminar de la playlist",
                        iconColor = MaterialTheme.colorScheme.error,
                        textColor = MaterialTheme.colorScheme.error,
                        onClick = onRemoveFromPlaylistClick
                    )
                }
                OptionItem(
                    icon = Icons.Rounded.EditNote,
                    text = "Editar metadatos",
                    onClick = onEditMetadataClick
                )
                OptionItem(
                    icon = Icons.Rounded.Delete,
                    text = "Eliminar del dispositivo",
                    iconColor = MaterialTheme.colorScheme.error,
                    textColor = MaterialTheme.colorScheme.error,
                    onClick = onDeleteClick
                )
            }
        }
    }
}

@Composable
private fun OptionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    textColor: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = text, 
                color = if (textColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else textColor, 
                fontSize = 15.sp, 
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ArtistImage(
    artist: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val context = LocalContext.current
    var imageFile by remember(artist) {
        mutableStateOf(com.kevshupp.kevmusicplayer.data.ArtistImageHelper.getArtistImageFile(context, artist))
    }
    var triggerDownload by remember(artist) { mutableStateOf(!imageFile.exists()) }

    LaunchedEffect(artist, triggerDownload) {
        if (triggerDownload) {
            val file = com.kevshupp.kevmusicplayer.data.ArtistImageHelper.downloadArtistImage(context, artist)
            if (file != null && file.exists()) {
                imageFile = file
            }
        }
    }

    if (imageFile.exists() && imageFile.length() > 0) {
        SubcomposeAsyncImage(
            model = imageFile,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier,
            error = {
                ArtistPlaceholderIcon(artist, modifier)
            }
        )
    } else {
        ArtistPlaceholderIcon(artist, modifier)
    }
}

@Composable
private fun ArtistPlaceholderIcon(artist: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .background(getGradientForString(artist)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Person,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.fillMaxSize(0.5f)
        )
    }
}

@Composable
fun FastScrollSidebar(
    items: List<String>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    if (items.size < 5) return

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val hapticEnabled = remember {
        try {
            context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE).getBoolean("haptic_feedback_enabled", true)
        } catch (e: Exception) {
            true
        }
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = LocalDensity.current

    val alphabet = remember(items) {
        items.map { text ->
            val firstChar = text.trimStart().firstOrNull()?.uppercaseChar() ?: '#'
            when {
                firstChar.isDigit() -> '#'
                firstChar in 'A'..'Z' -> firstChar
                else -> '?'
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
    }

    if (alphabet.isEmpty()) return

    var isDragging by remember { mutableStateOf(false) }
    var currentLetter by remember { mutableStateOf(alphabet.first()) }
    var dragY by remember { mutableStateOf(0f) }

    val scrollPercent = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf 0f
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf 0f
            val firstIndex = visibleItems.first().index
            val lastIndex = visibleItems.last().index
            val visibleCount = lastIndex - firstIndex + 1
            if (visibleCount >= totalItems) return@derivedStateOf 0f
            val averageItemSize = visibleItems.map { it.size }.average()
            val currentScrollOffset = (firstIndex * averageItemSize) + listState.firstVisibleItemScrollOffset
            val totalScrollLength = (totalItems * averageItemSize) - layoutInfo.viewportSize.height
            if (totalScrollLength <= 0) 0f else (currentScrollOffset / totalScrollLength).coerceIn(0.0, 1.0).toFloat()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .padding(vertical = 16.dp)
            .pointerInput(alphabet, items) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        dragY = offset.y
                        isDragging = true
                        val containerHeight = size.height.toFloat()
                        val percent = (offset.y / containerHeight).coerceIn(0f, 1f)
                        val index = (percent * alphabet.size).toInt().coerceIn(0, alphabet.lastIndex)
                        val newLetter = alphabet[index]
                        if (newLetter != currentLetter) {
                            currentLetter = newLetter
                            if (hapticEnabled) {
                                try { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) } catch (e: Exception) {}
                            }
                        }
                        val targetIndex = items.indexOfFirst {
                            val firstChar = it.trimStart().firstOrNull()?.uppercaseChar() ?: '#'
                            val mappedChar = when {
                                firstChar.isDigit() -> '#'
                                firstChar in 'A'..'Z' -> firstChar
                                else -> '?'
                            }
                            mappedChar == currentLetter
                        }
                        if (targetIndex != -1) {
                            coroutineScope.launch { listState.scrollToItem(targetIndex) }
                        }
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onVerticalDrag = { _, dragAmount ->
                        val containerHeight = size.height.toFloat()
                        dragY = (dragY + dragAmount).coerceIn(0f, containerHeight)
                        val percent = (dragY / containerHeight).coerceIn(0f, 1f)
                        val index = (percent * alphabet.size).toInt().coerceIn(0, alphabet.lastIndex)
                        val newLetter = alphabet[index]
                        if (newLetter != currentLetter) {
                            currentLetter = newLetter
                            if (hapticEnabled) {
                                try { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) } catch (e: Exception) {}
                            }
                        }
                        val targetIndex = items.indexOfFirst {
                            val firstChar = it.trimStart().firstOrNull()?.uppercaseChar() ?: '#'
                            val mappedChar = when {
                                firstChar.isDigit() -> '#'
                                firstChar in 'A'..'Z' -> firstChar
                                else -> '?'
                            }
                            mappedChar == currentLetter
                        }
                        if (targetIndex != -1) {
                            coroutineScope.launch { listState.scrollToItem(targetIndex) }
                        }
                    }
                )
            }
    ) {
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val thumbHeight = 32.dp
        val thumbHeightPx = with(density) { thumbHeight.toPx() }
        val maxOffsetPx = (containerHeightPx - thumbHeightPx).coerceAtLeast(0f)
        val thumbOffsetDp = with(density) { (maxOffsetPx * scrollPercent.value).toDp() }

        // Track Line
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(1.dp))
                .align(Alignment.CenterEnd)
        )

        // Thumb Pill
        Box(
            modifier = Modifier
                .height(thumbHeight)
                .width(5.dp)
                .offset(y = thumbOffsetDp)
                .background(
                    color = if (isDragging) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(2.5.dp)
                )
                .align(Alignment.TopEnd)
        )

        // Alphabet Rail
        val disableAnimations = com.kevshupp.kevmusicplayer.ui.theme.LocalDisableAnimations.current
        val alphabetAlpha = if (disableAnimations) {
            if (isDragging) 1f else 0.45f
        } else {
            val animAlpha by animateFloatAsState(targetValue = if (isDragging) 1f else 0.45f, label = "alphabet_alpha")
            animAlpha
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = alphabetAlpha }
                .padding(end = 6.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            alphabet.forEach { letter ->
                val isActive = currentLetter == letter && isDragging
                Text(
                    text = letter.toString(),
                    color = if (isActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.65f),
                    fontWeight = if (isActive) FontWeight.Black else FontWeight.Bold,
                    fontSize = if (isActive) 11.sp else 8.5.sp
                )
            }
        }

        // Glowing Teardrop Fast Scroll Bubble
        AnimatedVisibility(
            visible = isDragging,
            enter = if (disableAnimations) EnterTransition.None else (fadeIn() + scaleIn()),
            exit = if (disableAnimations) ExitTransition.None else (fadeOut() + scaleOut()),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-46).dp, y = with(density) { (dragY - 32.dp.toPx()).toDp().coerceIn(0.dp, (maxHeight - 64.dp).coerceAtLeast(0.dp)) })
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 32.dp, bottomStart = 32.dp, topEnd = 32.dp, bottomEnd = 6.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 16.dp,
                modifier = Modifier.size(64.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = currentLetter.toString(),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

