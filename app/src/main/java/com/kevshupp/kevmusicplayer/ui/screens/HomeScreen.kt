package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.R
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    audioFiles: List<AudioFile>,
    player: Player?,
    onFileClick: (AudioFile, List<AudioFile>?) -> Unit,
    onMiniPlayerClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    viewModel: MediaBrowserViewModel? = null,
    isActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val systemLang = remember { context.resources.configuration.locales[0].language }
    val isEs = systemLang == "es"

    // Dynamic greeting based on time of day
    val greeting = remember(systemLang) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (isEs) {
            when (hour) {
                in 6..11 -> "Buenos días"
                in 12..18 -> "Buenas tardes"
                else -> "Buenas noches"
            }
        } else {
            when (hour) {
                in 6..11 -> "Good morning"
                in 12..18 -> "Good afternoon"
                else -> "Good evening"
            }
        }
    }

    var showInsights by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // 1. Recently Played
    val recentlyPlayed by remember {
        derivedStateOf {
            audioFiles.filter { it.lastPlayed > 0L }
                .sortedByDescending { it.lastPlayed }
                .take(10)
        }
    }

    // 2. Most Played
    val mostPlayed by remember {
        derivedStateOf {
            audioFiles.filter { it.playCount > 0 }
                .sortedByDescending { it.playCount }
                .take(10)
        }
    }

    // 3. Recently Added
    val recentlyAdded by remember {
        derivedStateOf {
            audioFiles.sortedByDescending { it.dateAdded }
                .take(10)
        }
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
        ) {
            // Header row with Greeting, Insights, Settings and Refresh
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = greeting,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-1).sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (isEs) "Tu música favorita te espera" else "Your favorite music awaits",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isScanning = viewModel?.isScanning?.value == true
                    IconButton(
                        onClick = { viewModel?.scanFiles(isManual = true) },
                        enabled = !isScanning,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Reload Library",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    IconButton(
                        onClick = { showInsights = true },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Insights,
                            contentDescription = "Estadísticas",
                            tint = MaterialTheme.colorScheme.onBackground
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
            }

            // Scrollable Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = if (player?.currentMediaItem != null) 164.dp else 88.dp)
            ) {
                // Quick Actions Grid (Shuffle, Favorites, History, Recently Added)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        QuickActionCard(
                            title = if (isEs) "Modo Aleatorio" else "Shuffle All",
                            icon = Icons.Rounded.Shuffle,
                            gradient = Brush.linearGradient(listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0))),
                            onClick = {
                                if (audioFiles.isNotEmpty()) {
                                    val randomSong = audioFiles.random()
                                    onFileClick(randomSong, audioFiles)
                                    player?.shuffleModeEnabled = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionCard(
                            title = if (isEs) "Favoritas" else "Favorites",
                            icon = Icons.Rounded.Favorite,
                            gradient = Brush.linearGradient(listOf(Color(0xFFFF007F), Color(0xFFE0115F))),
                            onClick = {
                                if (viewModel != null) {
                                    val favExists = viewModel.playlists.containsKey("Favoritos")
                                    if (!favExists) {
                                        viewModel.createPlaylist("Favoritos")
                                    }
                                    viewModel.requestedTab.value = "Playlists"
                                    viewModel.requestedSubViewType.value = "Playlist"
                                    viewModel.requestedSubViewName.value = "Favoritos"
                                    viewModel.returnToHomeScreenOnDetailBack.value = true
                                    onNavigateToLibrary()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        QuickActionCard(
                            title = if (isEs) "Historial" else "History",
                            icon = Icons.Rounded.History,
                            gradient = Brush.linearGradient(listOf(Color(0xFF00F0FF), Color(0xFF0083B0))),
                            onClick = {
                                if (viewModel != null) {
                                    viewModel.requestedTab.value = "Songs"
                                    viewModel.requestedSubViewType.value = "History"
                                    viewModel.requestedSubViewName.value = "History"
                                    viewModel.returnToHomeScreenOnDetailBack.value = true
                                    onNavigateToLibrary()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionCard(
                            title = if (isEs) "Nuevas" else "New Added",
                            icon = Icons.Rounded.NewReleases,
                            gradient = Brush.linearGradient(listOf(Color(0xFF11998E), Color(0xFF38EF7D))),
                             onClick = {
                                 if (viewModel != null) {
                                     viewModel.requestedTab.value = "Songs"
                                     viewModel.requestedSubViewType.value = "RecentlyAdded"
                                     viewModel.requestedSubViewName.value = "RecentlyAdded"
                                     viewModel.returnToHomeScreenOnDetailBack.value = true
                                     onNavigateToLibrary()
                                 }
                             },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Section: Recently Played
                HomeHorizontalSection(
                    title = if (isEs) "Escuchado recientemente" else "Recently played",
                    items = recentlyPlayed,
                    placeholderText = if (isEs) "Aquí aparecerán tus últimas reproducciones" else "Your recent plays will appear here",
                    onItemClick = { song -> onFileClick(song, recentlyPlayed) }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Section: Most Played
                HomeHorizontalSection(
                    title = if (isEs) "Lo que más escuchas" else "Most played",
                    items = mostPlayed,
                    placeholderText = if (isEs) "Tus canciones favoritas saldrán aquí" else "Your top hits will appear here",
                    onItemClick = { song -> onFileClick(song, mostPlayed) }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Section: Recently Added
                HomeHorizontalSection(
                    title = if (isEs) "Agregado recientemente" else "Recently added",
                    items = recentlyAdded,
                    placeholderText = if (isEs) "No se encontraron canciones locales" else "No local songs found",
                    onItemClick = { song -> onFileClick(song, recentlyAdded) }
                )
            }
        }

        // MiniPlayer Overlay
        if (player != null) {
            val playerState = rememberPlayerState(player)
            if (playerState.currentSong != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 92.dp) // Offset above the BottomNavBar
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

        // Glassmorphic Bottom Navigation Bar
        BottomNavBar(
            currentScreen = "home",
            onTabSelected = { tab ->
                if (tab == "library") {
                    onNavigateToLibrary()
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    val getLocalized = remember(isEs) {
        { es: String, en: String -> if (isEs) es else en }
    }

    // Interactive Insights Screen Overlay Dialog
    MusicInsightsScreen(
        visible = showInsights,
        onDismiss = { showInsights = false },
        audioFiles = audioFiles,
        getLocalized = getLocalized,
        onPlaySongs = { songs, startIndex ->
            val first = songs.getOrNull(startIndex) ?: songs.firstOrNull()
            if (first != null) {
                onFileClick(first, songs)
            }
        },
        onCreatePlaylist = { name, songs ->
            if (viewModel != null) {
                viewModel.createPlaylist(name)
                songs.forEach { s ->
                    viewModel.addSongToPlaylist(name, s.id)
                }
                android.widget.Toast.makeText(
                    context,
                    if (isEs) "Playlist '$name' creada" else "Playlist '$name' created",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    )
}

@Composable
fun QuickActionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    gradient: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        ),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(gradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun HomeHorizontalSection(
    title: String,
    items: List<AudioFile>,
    placeholderText: String,
    onItemClick: (AudioFile) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        if (items.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(100.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.08f)
                ),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = placeholderText,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp), // Leaves 24dp padding on edges considering item padding
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Spacer at start to give 24dp edge padding
                Spacer(modifier = Modifier.width(8.dp))

                items.forEach { song ->
                    HomeSongCard(
                        song = song,
                        onClick = { onItemClick(song) }
                    )
                }

                // Spacer at end
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

@Composable
fun HomeSongCard(
    song: AudioFile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val roundedShape = if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) {
        RoundedCornerShape(16.dp)
    } else {
        androidx.compose.ui.graphics.RectangleShape
    }

    Column(
        modifier = modifier
            .width(130.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Song Artwork
        Box(
            modifier = Modifier
                .size(114.dp)
                .clip(roundedShape)
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
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Song details
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = song.title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
