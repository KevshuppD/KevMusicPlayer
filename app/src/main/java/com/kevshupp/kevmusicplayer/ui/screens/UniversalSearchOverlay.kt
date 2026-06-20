package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import com.kevshupp.kevmusicplayer.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniversalSearchOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    audioFiles: List<AudioFile>,
    onSongClick: (AudioFile, List<AudioFile>) -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    viewModel: MediaBrowserViewModel?,
    getLocalized: (String, String) -> String
) {
    if (!visible) return

    var query by remember { mutableStateOf("") }
    var selectedCategoryTab by remember { mutableStateOf("All") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(visible) {
        if (visible) {
            focusRequester.requestFocus()
        }
    }

    // Process search results
    val searchResults = remember(query, audioFiles, viewModel?.playlists, viewModel?.smartPlaylists) {
        if (query.isBlank()) {
            return@remember SearchResults(emptyList(), emptyList(), emptyList(), emptyList())
        }

        val songs = audioFiles.filter {
            it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
        }
        val albums = audioFiles.filter {
            it.album.contains(query, ignoreCase = true)
        }.map { it.album }.distinct()

        val artists = audioFiles.filter {
            it.artist.contains(query, ignoreCase = true)
        }.map { it.artist }.distinct()

        val playlists = mutableListOf<String>()
        viewModel?.playlists?.keys?.forEach { name ->
            if (name.contains(query, ignoreCase = true)) playlists.add(name)
        }
        viewModel?.smartPlaylists?.keys?.forEach { name ->
            if (name.contains(query, ignoreCase = true)) playlists.add(name)
        }

        SearchResults(songs, albums, artists, playlists.distinct())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search Input Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(
                            getLocalized(
                                "Buscar canciones, álbumes, artistas...",
                                "Search songs, albums, artists..."
                            )
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Clear,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                )
            }

            if (query.isBlank()) {
                // Empty search prompt with premium touch
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Text(
                            text = getLocalized("Busca tu música favorita", "Search your favorite music"),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                        )
                        Text(
                            text = getLocalized(
                                "Encuentra canciones, álbumes, artistas y listas al instante",
                                "Find songs, albums, artists, and playlists instantly"
                            ),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                // Results TabRow
                ScrollableTabRow(
                    selectedTabIndex = when (selectedCategoryTab) {
                        "All" -> 0
                        "Songs" -> 1
                        "Artists" -> 2
                        "Albums" -> 3
                        "Playlists" -> 4
                        else -> 0
                    },
                    edgePadding = 16.dp,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = {},
                    indicator = { tabPositions ->
                        val currentTab = when (selectedCategoryTab) {
                            "All" -> 0
                            "Songs" -> 1
                            "Artists" -> 2
                            "Albums" -> 3
                            "Playlists" -> 4
                            else -> 0
                        }
                        if (currentTab < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[currentTab]),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                ) {
                    val tabs = listOf(
                        "All" to getLocalized("Todo", "All"),
                        "Songs" to getLocalized("Canciones", "Songs"),
                        "Artists" to getLocalized("Artistas", "Artists"),
                        "Albums" to getLocalized("Álbumes", "Albums"),
                        "Playlists" to getLocalized("Playlists", "Playlists")
                    )
                    tabs.forEach { (tag, label) ->
                        Tab(
                            selected = selectedCategoryTab == tag,
                            onClick = { selectedCategoryTab = tag },
                            text = { Text(label, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                // Results list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Check if everything is empty
                    if (searchResults.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = getLocalized(
                                        "No se encontraron resultados.",
                                        "No results found."
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                )
                            }
                        }
                    } else {
                        when (selectedCategoryTab) {
                            "All" -> {
                                // Group results in "All" view
                                if (searchResults.songs.isNotEmpty()) {
                                    item {
                                        SearchHeader(getLocalized("Canciones", "Songs"))
                                    }
                                    items(searchResults.songs.take(3), key = { it.id }) { song ->
                                        SongSearchResultItem(song, onSongClick, searchResults.songs)
                                    }
                                }

                                if (searchResults.artists.isNotEmpty()) {
                                    item {
                                        SearchHeader(getLocalized("Artistas", "Artists"))
                                    }
                                    items(searchResults.artists.take(3), key = { it }) { artist ->
                                        SimpleSearchResultItem(
                                            title = artist,
                                            subtitle = getLocalized("Artista", "Artist"),
                                            icon = Icons.Rounded.Person,
                                            onClick = {
                                                onArtistClick(artist)
                                                onDismiss()
                                            }
                                        )
                                    }
                                }

                                if (searchResults.albums.isNotEmpty()) {
                                    item {
                                        SearchHeader(getLocalized("Álbumes", "Albums"))
                                    }
                                    items(searchResults.albums.take(3), key = { it }) { album ->
                                        val songSample = audioFiles.firstOrNull { it.album == album }
                                        val artUri = songSample?.uriString
                                        AlbumSearchResultItem(
                                            album = album,
                                            artist = songSample?.artist ?: "",
                                            artUriString = artUri,
                                            onClick = {
                                                onAlbumClick(album)
                                                onDismiss()
                                            }
                                        )
                                    }
                                }

                                if (searchResults.playlists.isNotEmpty()) {
                                    item {
                                        SearchHeader(getLocalized("Playlists", "Playlists"))
                                    }
                                    items(searchResults.playlists.take(3), key = { it }) { playlist ->
                                        SimpleSearchResultItem(
                                            title = playlist,
                                            subtitle = getLocalized("Lista de reproducción", "Playlist"),
                                            icon = Icons.AutoMirrored.Rounded.QueueMusic,
                                            onClick = {
                                                onPlaylistClick(playlist)
                                                onDismiss()
                                            }
                                        )
                                    }
                                }
                            }
                            "Songs" -> {
                                items(searchResults.songs, key = { it.id }) { song ->
                                    SongSearchResultItem(song, onSongClick, searchResults.songs)
                                }
                            }
                            "Artists" -> {
                                items(searchResults.artists, key = { it }) { artist ->
                                    SimpleSearchResultItem(
                                        title = artist,
                                        subtitle = getLocalized("Artista", "Artist"),
                                        icon = Icons.Rounded.Person,
                                        onClick = {
                                            onArtistClick(artist)
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                            "Albums" -> {
                                items(searchResults.albums, key = { it }) { album ->
                                    val songSample = audioFiles.firstOrNull { it.album == album }
                                    val artUri = songSample?.uriString
                                    AlbumSearchResultItem(
                                        album = album,
                                        artist = songSample?.artist ?: "",
                                        artUriString = artUri,
                                        onClick = {
                                            onAlbumClick(album)
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                            "Playlists" -> {
                                items(searchResults.playlists, key = { it }) { playlist ->
                                    SimpleSearchResultItem(
                                        title = playlist,
                                        subtitle = getLocalized("Lista de reproducción", "Playlist"),
                                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                                        onClick = {
                                            onPlaylistClick(playlist)
                                            onDismiss()
                                        }
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

@Composable
fun SearchHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
fun SongSearchResultItem(
    song: AudioFile,
    onSongClick: (AudioFile, List<AudioFile>) -> Unit,
    queue: List<AudioFile>
) {
    val artBytes = rememberAlbumArt(song.uriString)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onSongClick(song, queue) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(
            shape = if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(12.dp) else androidx.compose.ui.graphics.RectangleShape,
            modifier = Modifier.size(50.dp)
        ) {
            SubcomposeAsyncImage(
                model = artBytes,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.secondaryContainer
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun AlbumSearchResultItem(
    album: String,
    artist: String,
    artUriString: String?,
    onClick: () -> Unit
) {
    val artBytes = rememberAlbumArt(artUriString)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(
            shape = if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(12.dp) else androidx.compose.ui.graphics.RectangleShape,
            modifier = Modifier.size(50.dp)
        ) {
            SubcomposeAsyncImage(
                model = artBytes,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.secondaryContainer
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Album,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = artist,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun SimpleSearchResultItem(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

data class SearchResults(
    val songs: List<AudioFile>,
    val albums: List<String>,
    val artists: List<String>,
    val playlists: List<String>
) {
    fun isEmpty() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}
