package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.Player
import coil.compose.SubcomposeAsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.activity.compose.BackHandler
import com.kevshupp.kevmusicplayer.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    player: Player?,
    viewModel: MediaBrowserViewModel? = null,
    onBack: () -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToAlbum: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (player == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }
        return
    }

    val playerState = rememberPlayerState(player)
    var showMoreOptions by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showFileInfoDialog by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showEditLyricsDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editLyricsText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var isSearchingOnline by remember { mutableStateOf(false) }

    var showSearchLyricsDialog by remember { mutableStateOf(false) }
    var searchArtist by remember { mutableStateOf("") }
    var searchTitle by remember { mutableStateOf("") }
    var searchStatusMessage by remember { mutableStateOf("") }

    BackHandler(enabled = showLyrics) {
        showLyrics = false
    }

    val currentSongFile = remember(playerState.currentSong?.mediaId, viewModel?.localAudioFiles?.toList()) {
        viewModel?.localAudioFiles?.find { it.id.toString() == playerState.currentSong?.mediaId }
    }
    val lyricsText = currentSongFile?.lyrics
    val lyricLines = remember(lyricsText) { parseLrc(lyricsText) }
    var translatedLyricLines by remember { mutableStateOf<Map<Long, String>?>(null) }
    var isTranslating by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val targetLang = locale.language // "es" or "en"
    
    val currentSongUriString = remember(playerState.currentSong?.mediaId) {
        playerState.currentSong?.mediaId?.let { "content://media/external/audio/media/$it" }
    }
    val fileInfo by produceState(initialValue = Pair("MP3", "320 kbps"), key1 = playerState.currentSong?.mediaId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            getAudioFileInfo(context, currentSongUriString)
        }
    }

    val metadata = playerState.currentSong?.mediaMetadata
    val title = metadata?.title?.toString() ?: "Kev Music Player"
    val artist = metadata?.artist?.toString() ?: "Select a song"
    
    // Custom controls state
    var shuffleEnabled by remember(playerState.currentSong) {
        mutableStateOf(player.shuffleModeEnabled)
    }
    var repeatMode by remember(playerState.currentSong) {
        mutableStateOf(player.repeatMode)
    }

    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val background = MaterialTheme.colorScheme.background

    // Dynamic background gradient based on the song's title
    val backgroundBrush = remember(title, surfaceVariant, background) {
        Brush.verticalGradient(
            colors = listOf(
                surfaceVariant,
                background
            )
        )
    }

    // Curated gradient pairs for the massive artwork card
    val artGradient = remember(title) {
        getGradientForString(title)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        // Translation Logic
        val translateLyrics: suspend () -> Unit = {
            if (lyricLines.isNotEmpty()) {
                isTranslating = true
                val results = mutableMapOf<Long, String>()
                try {
                    withContext(Dispatchers.IO) {
                        val client = okhttp3.OkHttpClient()
                        // Translating in batches or one by one? 
                        // To keep it simple and free-tier friendly, let's do it individually with a small delay or batching if possible.
                        // MyMemory allows batching with "|" but it's limited.
                        lyricLines.forEach { line ->
                            if (line.text.isNotBlank()) {
                                val encodedText = java.net.URLEncoder.encode(line.text, "UTF-8")
                                val url = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=AUTO|$targetLang"
                                val request = okhttp3.Request.Builder().url(url).build()
                                client.newCall(request).execute().use { response ->
                                    if (response.isSuccessful) {
                                        val body = response.body?.string() ?: ""
                                        val json = org.json.JSONObject(body)
                                        val translated = json.getJSONObject("responseData").getString("translatedText")
                                        results[line.timeMs] = translated
                                    }
                                }
                            }
                        }
                    }
                    translatedLyricLines = results
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isTranslating = false
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Collapse",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                Text(
                    text = "NOW PLAYING",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showLyrics) {
                        IconButton(
                            onClick = { showLyrics = false },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.close_lyrics),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { showQueueSheet = true },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = "Queue",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        IconButton(
                            onClick = { showMoreOptions = true },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "Options",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }

            if (showLyrics) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    ScrollingLyricsView(
                        lyricLines = lyricLines,
                        currentPositionMs = playerState.position,
                        translatedLines = translatedLyricLines,
                        isTranslating = isTranslating,
                        onTranslateClick = {
                            scope.launch { translateLyrics() }
                        },
                        onLineClick = { timeMs -> player.seekTo(timeMs) },
                        onEditClick = {
                            editLyricsText = lyricsText ?: ""
                            showEditLyricsDialog = true
                        },
                        onSearchOnlineClick = {
                            if (currentSongFile != null) {
                                searchArtist = currentSongFile.artist
                                searchTitle = currentSongFile.title
                                searchStatusMessage = ""
                                showSearchLyricsDialog = true
                            }
                        },
                        isSearchingOnline = isSearchingOnline,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Spacer(modifier = Modifier.weight(0.2f))

                // Premium Album Art Container with rich shadow and organic roundings
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .aspectRatio(1f)
                        .clickable { showLyrics = !showLyrics }
                        .shadow(
                            elevation = 32.dp,
                            shape = RoundedCornerShape(32.dp),
                            clip = false,
                            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            spotColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                        ),
                    shape = RoundedCornerShape(32.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    val currentSongId = playerState.currentSong?.mediaId
                    val currentSongUriString = remember(currentSongId) {
                        currentSongId?.let { "content://media/external/audio/media/$it" }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(artGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        val artBytes = rememberAlbumArt(currentSongUriString)
                        SubcomposeAsyncImage(
                            model = artBytes,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            loading = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize(0.92f)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.08f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(110.dp),
                                        tint = Color.White.copy(alpha = 0.95f)
                                    )
                                }
                            },
                            error = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize(0.92f)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.08f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(110.dp),
                                        tint = Color.White.copy(alpha = 0.95f)
                                    )
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(0.3f))

                // Title and Artist with clean spacing
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-0.5).sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = artist,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Extension Badge
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(20.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = fileInfo.first,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        // Bitrate Badge
                        Surface(
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(20.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = fileInfo.second,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(0.2f))
            }

            // Premium Custom Seeking Slider
            Column(modifier = Modifier.fillMaxWidth()) {
                var sliderPosition by remember { mutableFloatStateOf(0f) }
                var isDragging by remember { mutableStateOf(false) }

                LaunchedEffect(playerState.position, isDragging) {
                    if (!isDragging) {
                        sliderPosition = if (playerState.duration > 0) {
                            playerState.position.toFloat() / playerState.duration.toFloat()
                        } else {
                            0f
                        }
                    }
                }

                Slider(
                    value = sliderPosition,
                    onValueChange = {
                        isDragging = true
                        sliderPosition = it
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        if (playerState.duration > 0) {
                            val newPos = (sliderPosition * playerState.duration).toLong()
                            player.seekTo(newPos)
                        }
                    },
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                        thumbColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDuration(playerState.position),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                    Text(
                        text = formatDuration(playerState.duration),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Playback Controls Row (Fully Custom High-Fidelity Buttons)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shuffle Button
                IconButton(
                    onClick = {
                        val newShuffle = !shuffleEnabled
                        shuffleEnabled = newShuffle
                        player.shuffleModeEnabled = newShuffle
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Previous Button
                IconButton(
                    onClick = { player.seekToPrevious() },
                    modifier = Modifier.size(54.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // Play / Pause Fab circle
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        )
                        .clickable {
                            if (playerState.isPlaying) player.pause() else player.play()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                // Next Button
                IconButton(
                    onClick = { player.seekToNext() },
                    modifier = Modifier.size(54.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // Repeat Button
                IconButton(
                    onClick = {
                        val nextMode = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        repeatMode = nextMode
                        player.repeatMode = nextMode
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    val repeatIcon = when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne
                        else -> Icons.Rounded.Repeat
                    }
                    val isRepeatActive = repeatMode != Player.REPEAT_MODE_OFF
                    Icon(
                        imageVector = repeatIcon,
                        contentDescription = "Repeat",
                        tint = if (isRepeatActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // Gorgeous Modal Bottom Sheet for Sharing & Audio Details
        if (showMoreOptions) {
            ModalBottomSheet(
                onDismissRequest = { showMoreOptions = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF161829),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header of Bottom Sheet
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val currentSongUriString = remember(playerState.currentSong?.mediaId) {
                            playerState.currentSong?.mediaId?.let { "content://media/external/audio/media/$it" }
                        }
                        val artBytes = rememberAlbumArt(currentSongUriString)
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(artGradient),
                            contentAlignment = Alignment.Center
                        ) {
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
                                text = title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = artist,
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    
                    val context = LocalContext.current
                    val currentSongUriString = remember(playerState.currentSong?.mediaId) {
                        playerState.currentSong?.mediaId?.let { "content://media/external/audio/media/$it" }
                    }



                    // Option 1: File Information Card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                showFileInfoDialog = true 
                                showMoreOptions = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Technical Audio Info", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }

                    // Option 2: Share Song File
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showMoreOptions = false
                                try {
                                    val uri = Uri.parse(currentSongUriString ?: "")
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "audio/*"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Music Track"))
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Share Audio File", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }

                    // Option 3: Go to Artist
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showMoreOptions = false
                                onNavigateToArtist(artist)
                                onBack()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Go to Artist", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }

                    // Option 4: Go to Album
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showMoreOptions = false
                                val albumName = playerState.currentSong?.mediaMetadata?.albumTitle?.toString() ?: "Unknown Album"
                                onNavigateToAlbum(albumName)
                                onBack()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Album,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Go to Album", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }

                    // Option 5: Delete Track
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showMoreOptions = false
                                showDeleteDialog = true
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Delete Track", color = MaterialTheme.colorScheme.error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        if (showQueueSheet) {
            ModalBottomSheet(
                onDismissRequest = { showQueueSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF161829),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val queueSongs = remember(playerState.currentSong, showQueueSheet) {
                        viewModel?.getPlayerQueue() ?: emptyList()
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cola de reproducción",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (queueSongs.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    viewModel?.clearQueue()
                                    showQueueSheet = false
                                }
                            ) {
                                Text("Limpiar cola", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (queueSongs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No hay canciones en la cola", color = Color.White.copy(alpha = 0.6f))
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                        ) {
                            itemsIndexed(queueSongs) { index, song ->
                                val isCurrent = playerState.currentSong?.mediaId == song.id.toString()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                                        )
                                        .clickable {
                                            player?.seekToDefaultPosition(index)
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isCurrent) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                                            contentDescription = "Playing",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Text(
                                            text = "${index + 1}",
                                            color = Color.White.copy(alpha = 0.5f),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.width(20.dp),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = song.artist,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.6f),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (!isCurrent) {
                                        IconButton(
                                            onClick = {
                                                viewModel?.removeFromQueue(index)
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Remove",
                                                tint = Color.White.copy(alpha = 0.6f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // Custom dialogs for lyrics edit and song deletion
        if (showEditLyricsDialog && currentSongFile != null) {
            AlertDialog(
                onDismissRequest = { showEditLyricsDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Edit Lyrics", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    OutlinedTextField(
                        value = editLyricsText,
                        onValueChange = { editLyricsText = it },
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                        placeholder = { Text("Enter plain text or synchronized [00:12.34] LRC lyrics...") },
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
                    TextButton(
                        onClick = {
                            showEditLyricsDialog = false
                            viewModel?.updateSongLyrics(currentSongFile.id, editLyricsText.ifBlank { null })
                        }
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showEditLyricsDialog = false }) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                    }
                },
                containerColor = Color(0xFF161829),
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }

        if (showSearchLyricsDialog && currentSongFile != null) {
            AlertDialog(
                onDismissRequest = { 
                    if (!isSearchingOnline) showSearchLyricsDialog = false 
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Search Lyrics Online", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Correct the artist or title below to query online synchronized LRC lyrics databases:",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        OutlinedTextField(
                            value = searchArtist,
                            onValueChange = { searchArtist = it },
                            label = { Text("Artist", color = Color.White.copy(alpha = 0.5f)) },
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
                        OutlinedTextField(
                            value = searchTitle,
                            onValueChange = { searchTitle = it },
                            label = { Text("Song Title", color = Color.White.copy(alpha = 0.5f)) },
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
                        if (searchStatusMessage.isNotEmpty()) {
                            Text(
                                text = searchStatusMessage,
                                color = if (searchStatusMessage.contains("Success")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                searchStatusMessage = "Searching LRCLIB database..."
                                isSearchingOnline = true
                                val fetched = fetchLyricsFromLrcLib(searchArtist, searchTitle)
                                if (!fetched.isNullOrEmpty()) {
                                    viewModel?.updateSongLyrics(currentSongFile.id, fetched)
                                    searchStatusMessage = "Success! Lyrics synchronized."
                                    kotlinx.coroutines.delay(1000)
                                    showSearchLyricsDialog = false
                                } else {
                                    searchStatusMessage = "No lyrics found. Try refining the query!"
                                }
                                isSearchingOnline = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        enabled = !isSearchingOnline
                    ) {
                        if (isSearchingOnline) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Search", fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showSearchLyricsDialog = false },
                        enabled = !isSearchingOnline
                    ) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                    }
                },
                containerColor = Color(0xFF161829),
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }

        if (showDeleteDialog && currentSongFile != null) {
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
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
                        Text(currentSongFile.title, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(currentSongFile.artist, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteDialog = false
                            viewModel?.deleteSong(context, currentSongFile.id)
                            onBack()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                    }
                },
                containerColor = Color(0xFF161829),
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }

        // Technical Audio Info Dialog
        if (showFileInfoDialog) {
            val currentSongUriString = remember(playerState.currentSong?.mediaId) {
                playerState.currentSong?.mediaId?.let { "content://media/external/audio/media/$it" }
            }
            AlertDialog(
                onDismissRequest = { showFileInfoDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Audio Specifications", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Title: $title", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Artist: $artist", color = Color.White.copy(alpha = 0.8f))
                        Text("Format: ${fileInfo.first}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("Bitrate: ${fileInfo.second}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("Source URI: ${currentSongUriString ?: "Unknown"}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                        Text("Decoder: Media3 ExoPlayer", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showFileInfoDialog = false }) {
                        Text("Close", fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF161829),
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }
    }
}

private fun getAudioFileInfo(context: android.content.Context, uriString: String?): Pair<String, String> {
    if (uriString.isNullOrEmpty()) return Pair("MP3", "320 kbps")
    var extension = "MP3"
    var bitrate = "320 kbps"
    try {
        val uri = Uri.parse(uriString)
        context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val dataIndex = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.DATA)
                if (dataIndex != -1) {
                    val path = cursor.getString(dataIndex)
                    if (!path.isNullOrEmpty()) {
                        extension = path.substringAfterLast('.', "mp3").uppercase()
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    try {
        val uri = Uri.parse(uriString)
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val bitrateStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
        if (!bitrateStr.isNullOrEmpty()) {
            val bitrateKbps = bitrateStr.toInt() / 1000
            bitrate = "$bitrateKbps kbps"
        }
        retriever.release()
    } catch (e: Exception) {
        e.printStackTrace()
    }
    
    return Pair(extension, bitrate)
}

data class LyricLine(
    val timeMs: Long,
    val text: String
)

fun parseLrc(lrcText: String?): List<LyricLine> {
    if (lrcText.isNullOrBlank()) return emptyList()
    val lines = mutableListOf<LyricLine>()
    val pattern = Regex("\\[(\\d+):(\\d+)(?:\\.(\\d+))?]\\s*(.*)")
    lrcText.lines().forEach { rawLine ->
        val match = pattern.find(rawLine)
        if (match != null) {
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            val msPart = match.groupValues[3]
            val ms = if (msPart.isNotEmpty()) {
                val padded = msPart.padEnd(3, '0').take(3)
                padded.toLong()
            } else 0L
            val timeMs = (min * 60 + sec) * 1000 + ms
            val text = match.groupValues[4].trim()
            lines.add(LyricLine(timeMs, text))
        } else if (rawLine.isNotBlank() && !rawLine.startsWith("[")) {
            lines.add(LyricLine(0L, rawLine.trim()))
        }
    }
    return lines.sortedBy { it.timeMs }
}

suspend fun fetchLyricsFromLrcLib(artist: String, title: String): String? {
    return withContext(Dispatchers.IO) {
        val client = okhttp3.OkHttpClient()
        val query = java.net.URLEncoder.encode("$artist $title", "UTF-8")
        val request = okhttp3.Request.Builder()
            .url("https://lrclib.net/api/search?q=$query")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val jsonArray = org.json.JSONArray(body)
                    if (jsonArray.length() > 0) {
                        val bestMatch = jsonArray.getJSONObject(0)
                        val syncedLyrics = bestMatch.optString("syncedLyrics")
                        if (!syncedLyrics.isNullOrEmpty() && syncedLyrics != "null") {
                            return@withContext syncedLyrics
                        }
                        val plainLyrics = bestMatch.optString("plainLyrics")
                        if (!plainLyrics.isNullOrEmpty() && plainLyrics != "null") {
                            return@withContext plainLyrics
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}

@Composable
private fun ScrollingLyricsView(
    lyricLines: List<LyricLine>,
    currentPositionMs: Long,
    translatedLines: Map<Long, String>? = null,
    isTranslating: Boolean = false,
    onTranslateClick: () -> Unit = {},
    onLineClick: (Long) -> Unit,
    onEditClick: () -> Unit,
    onSearchOnlineClick: () -> Unit,
    isSearchingOnline: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    
    val activeIndex = remember(lyricLines, currentPositionMs) {
        lyricLines.indexOfLast { currentPositionMs >= it.timeMs }.coerceAtLeast(0)
    }

    LaunchedEffect(activeIndex) {
        if (lyricLines.isNotEmpty() && activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(32.dp))
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center
    ) {
        if (lyricLines.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.InterpreterMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(54.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.no_lyrics),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                if (isSearchingOnline) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onSearchOnlineClick,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.find_lyrics), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 150.dp, bottom = 300.dp, start = 24.dp, end = 24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                itemsIndexed(lyricLines) { index, line ->
                    val isActive = index == activeIndex
                    val translatedText = translatedLines?.get(line.timeMs)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLineClick(line.timeMs) }
                            .graphicsLayer {
                                val distance = Math.abs(index - activeIndex)
                                alpha = if (isActive) 1f else (0.4f - (distance * 0.05f)).coerceAtLeast(0.1f)
                                scaleX = if (isActive) 1.05f else 1f
                                scaleY = if (isActive) 1.05f else 1f
                            },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                            color = if (isActive) MaterialTheme.colorScheme.primary else Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (translatedText != null) {
                            Text(
                                text = translatedText,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Buttons Layer (Floating)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isTranslating) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    IconButton(
                        onClick = onTranslateClick,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (translatedLines != null) MaterialTheme.colorScheme.primary 
                                           else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Translate,
                            contentDescription = stringResource(R.string.translate_lyrics),
                            tint = if (translatedLines != null) Color.Black else Color.White
                        )
                    }
                }

                IconButton(
                    onClick = onEditClick,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Edit Lyrics",
                        tint = Color.White
                    )
                }

                IconButton(
                    onClick = onSearchOnlineClick,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = "Search Online",
                        tint = Color.White
                    )
                }
            }
        }
    }
}
