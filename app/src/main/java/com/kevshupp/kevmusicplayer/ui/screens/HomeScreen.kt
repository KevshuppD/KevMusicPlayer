package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
    onShuffleClick: ((List<AudioFile>?, AudioFile?) -> Unit)? = null,
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
    var showDownloader by remember { mutableStateOf(false) }
    var showSoulseek by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    val cloudUser by (viewModel?.cloudUser ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // 1. Recently Played
    val recentlyPlayed by remember(audioFiles) {
        derivedStateOf {
            (viewModel?.audioFilesWithStats ?: audioFiles).filter { it.lastPlayed > 0L }
                .sortedByDescending { it.lastPlayed }
                .take(10)
        }
    }

    // 2. Most Played
    val mostPlayed by remember(audioFiles) {
        derivedStateOf {
            (viewModel?.audioFilesWithStats ?: audioFiles).filter { it.playCount > 0 }
                .sortedByDescending { it.playCount }
                .take(10)
        }
    }

    // "Hecho para ti": mezclas automáticas (artistas, géneros, Redescubre, Sin escuchar), calculadas fuera del
    // hilo principal y de nuevo solo cuando cambian la biblioteca o las reproducciones
    val statsVersion = viewModel?.statsVersion?.intValue ?: 0
    val homeMixes by produceState(emptyList<com.kevshupp.kevmusicplayer.data.HomeMix>(), audioFiles.size, statsVersion) {
        val songs = (viewModel?.audioFilesWithStats ?: audioFiles).toList()
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.kevshupp.kevmusicplayer.data.HomeMixes.build(songs)
        }
    }

    // 3. Recently Added
    val recentlyAdded by remember(audioFiles) {
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
            // Cabecera: perfil y botones arriba, el saludo debajo a todo el ancho (en una sola fila, con
            // Telegram y Soulseek activos, el saludo quedaba en "Bu…")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Profile / Cloud Sync Avatar Button
                    IconButton(
                        onClick = { showProfileDialog = true },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        if (cloudUser != null && !cloudUser?.photoUrl.isNullOrBlank()) {
                            Box(contentAlignment = Alignment.Center) {
                                SubcomposeAsyncImage(
                                    model = cloudUser?.photoUrl,
                                    contentDescription = "Perfil",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .border(
                                            1.5.dp,
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape
                                        ),
                                    error = {
                                        Icon(
                                            imageVector = Icons.Rounded.AccountCircle,
                                            contentDescription = "Perfil",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.AccountCircle,
                                contentDescription = "Perfil",
                                tint = if (cloudUser != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))
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

                    val telegramEnabled by com.kevshupp.kevmusicplayer.data.telegram.TelegramMusicClient.enabled.collectAsState()
                    LaunchedEffect(Unit) { com.kevshupp.kevmusicplayer.data.telegram.TelegramMusicClient.loadEnabled(context) }
                    if (telegramEnabled) IconButton(
                        onClick = { showDownloader = true },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDownload,
                            contentDescription = "Descargar música",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    val soulseekEnabled by com.kevshupp.kevmusicplayer.data.soulseek.SoulseekManager.enabled.collectAsState()
                    if (soulseekEnabled) IconButton(
                        onClick = { showSoulseek = true },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Hub,
                            contentDescription = "Soulseek",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
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
                Spacer(Modifier.height(12.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = greeting,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-1).sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isEs) "Tu música favorita te espera" else "Your favorite music awaits",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (showSoulseek) {
                com.kevshupp.kevmusicplayer.ui.screens.dialogs.SoulseekDialog(
                    librarySongs = audioFiles,
                    onDismiss = { showSoulseek = false }
                )
            }

            if (showDownloader) {
                com.kevshupp.kevmusicplayer.ui.screens.dialogs.TelegramDownloadDialog(
                    // Con las reproducciones al día, para ordenar los resultados por los artistas que escuchas
                    librarySongs = viewModel?.audioFilesWithStats ?: audioFiles,
                    onDismiss = { showDownloader = false }
                )
            }

            if (showProfileDialog && viewModel != null) {
                com.kevshupp.kevmusicplayer.ui.screens.dialogs.UserProfileDialog(
                    viewModel = viewModel,
                    onDismiss = { showProfileDialog = false }
                )
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
                    // Con Pixel Retro los accesos toman colores planos de la paleta (sin degradado, como píxeles)
                    val pixel = com.kevshupp.kevmusicplayer.ui.theme.LocalPixelPalette.current
                    val scheme = MaterialTheme.colorScheme
                    fun quick(default: Brush, color: () -> Color) = if (pixel != null) SolidColor(color()) else default
                    val iconTint = if (pixel != null) scheme.onPrimary else Color.White
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        QuickActionCard(
                            title = if (isEs) "Modo Aleatorio" else "Shuffle All",
                            icon = com.kevshupp.kevmusicplayer.ui.theme.px(Icons.Rounded.Shuffle),
                            gradient = quick(Brush.linearGradient(listOf(Color(0xFF8E2DE2), Color(0xFF4A00E0)))) { scheme.primary },
                            iconTint = iconTint,
                            onClick = {
                                if (audioFiles.isNotEmpty()) {
                                    if (onShuffleClick != null) {
                                        onShuffleClick(audioFiles, null)
                                    } else if (viewModel != null) {
                                        val started = viewModel.shuffleAll(audioFiles, null)
                                        if (started != null) {
                                            onFileClick(started, audioFiles)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionCard(
                            title = if (isEs) "Favoritas" else "Favorites",
                            icon = com.kevshupp.kevmusicplayer.ui.theme.px(Icons.Rounded.Favorite),
                            gradient = quick(Brush.linearGradient(listOf(Color(0xFFFF007F), Color(0xFFE0115F)))) { scheme.secondary },
                            iconTint = iconTint,
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
                                    viewModel.returnToPlayerOnDetailBack.value = false
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
                            icon = com.kevshupp.kevmusicplayer.ui.theme.px(Icons.Rounded.History),
                            gradient = quick(Brush.linearGradient(listOf(Color(0xFF00F0FF), Color(0xFF0083B0)))) { scheme.tertiary },
                            iconTint = iconTint,
                            onClick = {
                                if (viewModel != null) {
                                    viewModel.requestedTab.value = "Songs"
                                    viewModel.requestedSubViewType.value = "History"
                                    viewModel.requestedSubViewName.value = "History"
                                    viewModel.returnToHomeScreenOnDetailBack.value = true
                                    viewModel.returnToPlayerOnDetailBack.value = false
                                    onNavigateToLibrary()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionCard(
                            title = if (isEs) "Nuevas" else "New Added",
                            icon = com.kevshupp.kevmusicplayer.ui.theme.px(Icons.Rounded.NewReleases),
                            gradient = quick(Brush.linearGradient(listOf(Color(0xFF11998E), Color(0xFF38EF7D)))) { scheme.onSurfaceVariant },
                            iconTint = iconTint,
                             onClick = {
                                 if (viewModel != null) {
                                     viewModel.requestedTab.value = "Songs"
                                     viewModel.requestedSubViewType.value = "RecentlyAdded"
                                     viewModel.requestedSubViewName.value = "RecentlyAdded"
                                     viewModel.returnToHomeScreenOnDetailBack.value = true
                                     viewModel.returnToPlayerOnDetailBack.value = false
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

                // Últimos videos creados: solo aparece si hay alguno
                HomeClipsSection(isEs = isEs)

                // Mezclas automáticas y listas inteligentes, con portada de las carátulas de sus canciones
                val smartLists = viewModel?.smartPlaylists?.filterValues { it.isNotEmpty() }.orEmpty()
                if (homeMixes.isNotEmpty() || smartLists.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = if (isEs) "Hecho para ti" else "Made for you",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp)
                    ) {
                        items(homeMixes, key = { "mix_" + it.id }) { mix ->
                            HomeMixCard(
                                title = if (isEs) mix.titleEs else mix.titleEn,
                                subtitle = if (isEs) mix.subtitleEs else mix.subtitleEn,
                                songs = mix.songs,
                                smart = false,
                                onClick = { mix.songs.firstOrNull()?.let { onFileClick(it, mix.songs) } }
                            )
                        }
                        items(smartLists.keys.toList(), key = { "smart_$it" }) { name ->
                            val songs = smartLists[name].orEmpty()
                            HomeMixCard(
                                title = name,
                                subtitle = (if (isEs) "Lista inteligente · " else "Smart playlist · ") + songs.size,
                                songs = songs,
                                smart = true,
                                onClick = {
                                    if (viewModel != null) {
                                        viewModel.requestedTab.value = "Playlists"
                                        viewModel.requestedSubViewType.value = "Playlist"
                                        viewModel.requestedSubViewName.value = name
                                        viewModel.returnToHomeScreenOnDetailBack.value = true
                                        viewModel.returnToPlayerOnDetailBack.value = false
                                        onNavigateToLibrary()
                                    }
                                }
                            )
                        }
                    }
                }

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

        // Glassmorphic Unified Bottom Navigation & Player Bar
        BottomNavBar(
            currentScreen = "home",
            player = player,
            onMiniPlayerClick = onMiniPlayerClick,
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
        audioFiles = if (showInsights) viewModel?.audioFilesWithStats ?: audioFiles else audioFiles,
        getLocalized = getLocalized,
        onResetStats = viewModel?.let { vm -> { vm.resetPlayStats() } },
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
    iconTint: Color = Color.White,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enableCardTransparency = com.kevshupp.kevmusicplayer.ui.theme.LocalCardTransparencyEnabled.current
    Card(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enableCardTransparency) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
        ),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (enableCardTransparency) 0.08f else 0.15f))
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
                    tint = iconTint,
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
    val enableCardTransparency = com.kevshupp.kevmusicplayer.ui.theme.LocalCardTransparencyEnabled.current
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
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        if (items.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(100.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (enableCardTransparency) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
                ),
                border = BorderStroke(1.dp, Color.White.copy(alpha = if (enableCardTransparency) 0.05f else 0.12f))
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
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                items(
                    items = items,
                    key = { it.id }
                ) { song ->
                    HomeSongCard(
                        song = song,
                        onClick = { onItemClick(song) }
                    )
                }
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
            .width(136.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Song Artwork
        Box(
            modifier = Modifier
                .size(136.dp)
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
                    imageVector = com.kevshupp.kevmusicplayer.ui.theme.px(Icons.Rounded.MusicNote),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        // Song details
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
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


/**
 * "Tus videos": miniaturas de los últimos videos de Mis videos. Tocar uno lo reproduce y "Ver todos" abre
 * Mis videos (el mismo aviso que usa el widget), sin tener que entrar al reproductor de una canción.
 */
@Composable
private fun HomeClipsSection(isEs: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { com.kevshupp.kevmusicplayer.playback.managers.ClipHistory.load(context) }
    val entries by com.kevshupp.kevmusicplayer.playback.managers.ClipHistory.entries.collectAsState()
    if (entries.isEmpty()) return
    val openAll = { com.kevshupp.kevmusicplayer.MainActivity.openClipHistoryRequest.value = true }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEs) "Tus videos" else "Your videos",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (isEs) "Ver todos (${entries.size})" else "See all (${entries.size})",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { openAll() }.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(entries.take(10), key = { it.id }) { entry ->
                val thumb = remember(entry.id) { com.kevshupp.kevmusicplayer.playback.managers.ClipHistory.thumbFile(context, entry.id) }
                // Misma proporción que el video, con un alto fijo
                val ratio = (entry.width.toFloat() / entry.height.coerceAtLeast(1)).coerceIn(0.5f, 1.8f)
                Column(
                    modifier = Modifier
                        .width((150 * ratio).dp.coerceAtLeast(96.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            com.kevshupp.kevmusicplayer.ui.screens.dialogs.playVideo(
                                context, com.kevshupp.kevmusicplayer.playback.managers.ClipHistory.videoFile(context, entry)
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        coil.compose.AsyncImage(
                            model = thumb,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Icon(
                            com.kevshupp.kevmusicplayer.ui.theme.px(Icons.Rounded.PlayArrow),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                                .padding(6.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
                    Text(entry.artist, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }
            }
        }
    }
}

/** Tarjeta de "Hecho para ti": portada en mosaico con las carátulas de la mezcla o lista. */
@Composable
private fun HomeMixCard(title: String, subtitle: String, songs: List<AudioFile>, smart: Boolean, onClick: () -> Unit) {
    val shape = if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(16.dp)
    else androidx.compose.ui.graphics.RectangleShape
    Column(
        modifier = Modifier.width(150.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(150.dp).clip(shape).background(getGradientForString(title))) {
            SongMosaic(songs, Modifier.fillMaxSize())
            Icon(
                com.kevshupp.kevmusicplayer.ui.theme.px(if (smart) com.kevshupp.kevmusicplayer.ui.theme.px(Icons.Rounded.AutoAwesome) else com.kevshupp.kevmusicplayer.ui.theme.px(Icons.Rounded.PlayArrow)),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(6.dp)
            )
        }
        Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
