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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
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
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            error = {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${song.artist} • ${song.album}",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Metadata tag editor & context actions trigger
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { expanded = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "More options",
                                tint = Color.White.copy(alpha = 0.6f)
                            )
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            containerColor = Color(0xFF1E213A)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Play Next", color = Color.White) },
                                onClick = {
                                    expanded = false
                                    onPlayNextClick(song)
                                },
                                leadingIcon = { Icon(Icons.Rounded.QueuePlayNext, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            )
                            DropdownMenuItem(
                                text = { Text("Add to Queue", color = Color.White) },
                                onClick = {
                                    expanded = false
                                    onAddToQueueClick(song)
                                },
                                leadingIcon = { Icon(Icons.Rounded.AddToPhotos, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            )
                            DropdownMenuItem(
                                text = { Text("Add to Playlist", color = Color.White) },
                                onClick = {
                                    expanded = false
                                    onAddToPlaylistClick(song)
                                },
                                leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            )
                            if (playlistContextName != null) {
                                DropdownMenuItem(
                                    text = { Text("Remove from Playlist", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        expanded = false
                                        onRemoveFromPlaylistClick(song)
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.PlaylistRemove, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Edit Metadata", color = Color.White) },
                                onClick = {
                                    expanded = false
                                    onEditTagsClick(song)
                                },
                                leadingIcon = { Icon(Icons.Rounded.EditNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            )
                        }
                    }
                }
            }
        }

        // Fast Scroll Alphabet Overlay Scrollbar
        if (alphabet != null) {
            val density = LocalDensity.current
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(28.dp)
                    .padding(vertical = 16.dp)
                    .background(Color.Black.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                    .pointerInput(alphabet, totalHeight) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                dragY = offset.y
                                showAlphabetPopup = true
                                val percent = (offset.y / totalHeight).coerceIn(0f, 1f)
                                val index = (percent * alphabet.size).toInt().coerceIn(0, alphabet.lastIndex)
                                currentLetter = alphabet[index]
                                val targetIndex = songs.indexOfFirst {
                                    val char = it.title.firstOrNull()?.uppercaseChar() ?: '#'
                                    if (currentLetter == '#') char.isDigit()
                                    else if (currentLetter == '?') !char.isLetterOrDigit()
                                    else char == currentLetter
                                }
                                if (targetIndex != -1) {
                                    coroutineScope.launch {
                                        listState.scrollToItem(targetIndex)
                                    }
                                }
                            },
                            onDragEnd = {
                                showAlphabetPopup = false
                            },
                            onDragCancel = {
                                showAlphabetPopup = false
                            },
                            onVerticalDrag = { change, dragAmount ->
                                dragY = (dragY + dragAmount).coerceIn(0f, totalHeight)
                                val percent = (dragY / totalHeight).coerceIn(0f, 1f)
                                val index = (percent * alphabet.size).toInt().coerceIn(0, alphabet.lastIndex)
                                currentLetter = alphabet[index]
                                val targetIndex = songs.indexOfFirst {
                                    val char = it.title.firstOrNull()?.uppercaseChar() ?: '#'
                                    if (currentLetter == '#') char.isDigit()
                                    else if (currentLetter == '?') !char.isLetterOrDigit()
                                    else char == currentLetter
                                }
                                if (targetIndex != -1) {
                                    coroutineScope.launch {
                                        listState.scrollToItem(targetIndex)
                                    }
                                }
                            }
                        )
                    }
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    alphabet.forEach { letter ->
                        Text(
                            text = letter.toString(),
                            color = if (currentLetter == letter && showAlphabetPopup) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                            fontWeight = if (currentLetter == letter && showAlphabetPopup) FontWeight.Black else FontWeight.Bold,
                            fontSize = if (currentLetter == letter && showAlphabetPopup) 12.sp else 10.sp
                        )
                    }
                }
            }

            // Big Center Popup
            if (showAlphabetPopup) {
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
    var shuffleModeEnabled by remember { mutableStateOf(player?.shuffleModeEnabled ?: false) }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose {}

        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                isPlaying = player.isPlaying
                currentSong = player.currentMediaItem
                duration = player.duration.coerceAtLeast(0L)
                shuffleModeEnabled = player.shuffleModeEnabled
            }
        }
        player.addListener(listener)
        isPlaying = player.isPlaying
        currentSong = player.currentMediaItem
        duration = player.duration.coerceAtLeast(0L)
        shuffleModeEnabled = player.shuffleModeEnabled
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

    return PlayerStateInfo(isPlaying, currentSong, position, duration, shuffleModeEnabled)
}

data class PlayerStateInfo(
    val isPlaying: Boolean,
    val currentSong: MediaItem?,
    val position: Long,
    val duration: Long,
    val shuffleModeEnabled: Boolean
)

// ---------------- ALBUM ART CACHE & ASYNC LOADER ----------------
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
                    label = { Text("Nombre de la lista", color = Color.White.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            onCreatePlaylist(newPlaylistName)
                            newPlaylistName = ""
                            showCreateDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Crear", color = Color.Black, fontWeight = FontWeight.Bold)
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
                            imageVector = Icons.Rounded.PlaylistAdd,
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

        items(playlists.keys.toList()) { name ->
            val listSongs = playlists[name] ?: emptyList()
            val coverPath = playlistCovers[name]
            
            // Context menu state for playlist card
            var expandedMenu by remember { mutableStateOf(false) }

            Box {
                Card(
                    onClick = { onPlaylistClick(name) },
                    shape = RoundedCornerShape(20.dp),
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

                        // Top right quick options button
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

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${listSongs.size} Tracks",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // Dropdown menu overlay
                DropdownMenu(
                    expanded = expandedMenu,
                    onDismissRequest = { expandedMenu = false },
                    containerColor = Color(0xFF1E213A)
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
