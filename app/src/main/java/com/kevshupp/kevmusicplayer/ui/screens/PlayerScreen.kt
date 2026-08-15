package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.res.stringResource
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.activity.compose.BackHandler
import com.kevshupp.kevmusicplayer.R
import com.kevshupp.kevmusicplayer.data.LyricLine
import com.kevshupp.kevmusicplayer.data.LyricsRepository
import com.kevshupp.kevmusicplayer.data.LrcLibSearchResult
import androidx.palette.graphics.Palette
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode

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
    val disableAnimations = com.kevshupp.kevmusicplayer.ui.theme.LocalDisableAnimations.current
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

    if (playerState.mediaItemCount == 0) {
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
        LaunchedEffect(playerState.mediaItemCount) {
            if (playerState.mediaItemCount == 0) {
                kotlinx.coroutines.delay(200)
                if (playerState.mediaItemCount == 0) {
                    onBack()
                }
            }
        }
        return
    }
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val settingsPrefs = remember(context) { context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE) }
    val targetLang = remember(settingsPrefs, locale) {
        val langPref = settingsPrefs.getString("language", locale.language) ?: locale.language
        if (langPref.startsWith("es")) "es" else if (langPref.startsWith("en")) "en" else langPref
    }
    val rememberLyricsOpen = remember(settingsPrefs) { settingsPrefs.getBoolean("remember_lyrics_open", true) }

    var showMoreOptions by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showFileInfoDialog by remember { mutableStateOf(false) }
    var showLyrics by rememberSaveable {
        mutableStateOf(if (rememberLyricsOpen) settingsPrefs.getBoolean("lyrics_view_active", false) else false)
    }

    val setLyricsVisible: (Boolean) -> Unit = { visible ->
        showLyrics = visible
        if (rememberLyricsOpen) {
            settingsPrefs.edit().putBoolean("lyrics_view_active", visible).apply()
        }
    }

    val onSkipNext = {
        val crossfadeSeconds = settingsPrefs.getInt("crossfade_duration", 0)
        val controller = player as? androidx.media3.session.MediaController
        if (controller != null && crossfadeSeconds > 0) {
            controller.sendCustomCommand(
                androidx.media3.session.SessionCommand("ACTION_SKIP_NEXT", android.os.Bundle.EMPTY),
                android.os.Bundle.EMPTY
            )
        } else {
            player.seekToNext()
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
                player.play()
            }
        }
    }

    val onSkipPrevious = {
        val crossfadeSeconds = settingsPrefs.getInt("crossfade_duration", 0)
        val controller = player as? androidx.media3.session.MediaController
        if (controller != null && crossfadeSeconds > 0) {
            controller.sendCustomCommand(
                androidx.media3.session.SessionCommand("ACTION_SKIP_PREV", android.os.Bundle.EMPTY),
                android.os.Bundle.EMPTY
            )
        } else {
            player.seekToPrevious()
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
                player.play()
            }
        }
    }

    var showEditLyricsDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showTagEditorDialog by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var sleepTimerMinutes by remember { mutableIntStateOf(0) }
    var showSaveQueueDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var editLyricsText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var isSearchingOnline by remember { mutableStateOf(false) }
    var isAutoSearchingLyrics by remember { mutableStateOf(false) }

    var showSearchLyricsDialog by remember { mutableStateOf(false) }
    var searchArtist by remember { mutableStateOf("") }
    var searchTitle by remember { mutableStateOf("") }
    var searchStatusMessage by remember { mutableStateOf("") }
    var searchLyricsResults by remember { mutableStateOf<List<LrcLibSearchResult>>(emptyList()) }

    BackHandler(enabled = showLyrics) {
        setLyricsVisible(false)
    }

    val currentSongFile = remember(playerState.currentSong?.mediaId) {
        derivedStateOf {
            viewModel?.localAudioFiles?.find { it.id.toString() == playerState.currentSong?.mediaId }
        }
    }.value
    val lyricsText = currentSongFile?.lyrics
    val lyricLines = remember(lyricsText) { LyricsRepository.parseLrc(lyricsText) }
    var translatedLyricLines by remember { mutableStateOf<Map<Long, String>?>(null) }
    var isTranslating by remember { mutableStateOf(false) }
    var showTranslation by remember { mutableStateOf(true) }

    var lastSongId by remember { mutableStateOf<Long?>(null) }
    var lastLyricsText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentSongFile?.id, lyricsText, currentSongFile?.translatedLyrics) {
        val songId = currentSongFile?.id
        val cached = currentSongFile?.translatedLyrics
        if (songId != lastSongId || lyricsText != lastLyricsText) {
            lastSongId = songId
            lastLyricsText = lyricsText
            translatedLyricLines = if (!cached.isNullOrBlank()) {
                LyricsRepository.deserializeTranslations(cached)
            } else {
                null
            }
            showTranslation = true
        } else {
            if (!cached.isNullOrBlank()) {
                translatedLyricLines = LyricsRepository.deserializeTranslations(cached)
            }
        }
    }

    LaunchedEffect(playerState.currentSong?.mediaId, currentSongFile?.id) {
        val songId = playerState.currentSong?.mediaId
        if (songId != null && currentSongFile != null) {
            val hasLyrics = !currentSongFile.lyrics.isNullOrBlank()
            if (!hasLyrics) {
                isAutoSearchingLyrics = true
                kotlinx.coroutines.delay(600)
                try {
                    val fetched = LyricsRepository.fetchLyricsFromLrcLib(currentSongFile.artist, currentSongFile.title)
                    if (!fetched.isNullOrEmpty()) {
                        viewModel?.updateSongLyrics(currentSongFile.id, fetched)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isAutoSearchingLyrics = false
                }
            }
        }
    }
    
    var isVisualizerEnabled by remember {
        mutableStateOf(settingsPrefs.getBoolean("show_visualizer", false))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                isVisualizerEnabled = true
                settingsPrefs.edit().putBoolean("show_visualizer", true).apply()
            } else {
                isVisualizerEnabled = true
                settingsPrefs.edit().putBoolean("show_visualizer", true).apply()
                android.widget.Toast.makeText(
                    context,
                    if (targetLang == "es") "Visualizador en modo simulación (sin permiso de audio)" else "Visualizer in simulation mode (no audio permission)",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    )

    var heartPosition by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var showHeartAnimation by remember { mutableStateOf(false) }

    val currentSongUriString = remember(playerState.currentSong?.mediaId) {
        val mediaId = playerState.currentSong?.mediaId
        if (mediaId != null) "content://media/external/audio/media/$mediaId" else null
    }

    val isFavorite = remember(currentSongFile?.id, viewModel?.playlists?.get("Favoritos")) {
        val favList = viewModel?.playlists?.get("Favoritos") ?: emptyList()
        val songId = currentSongFile?.id
        songId != null && favList.any { it.id == songId }
    }

    val hasAudioPermission = remember(isVisualizerEnabled) {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val playbackPrefs = remember(context) { context.getSharedPreferences("playback_prefs", android.content.Context.MODE_PRIVATE) }
    val audioSessionId by produceState(initialValue = 0, key1 = playerState.isPlaying) {
        value = playbackPrefs.getInt("audio_session_id", 0)
    }

    var fftData by remember { mutableStateOf(FloatArray(20) { 0f }) }

    // Real Visualizer binding
    if (isVisualizerEnabled && hasAudioPermission && audioSessionId != 0 && playerState.isPlaying) {
        DisposableEffect(audioSessionId) {
            val visualizer = try {
                android.media.audiofx.Visualizer(audioSessionId).apply {
                    captureSize = 128
                    setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer?, waveform: ByteArray?, rate: Int) {}
                        override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, rate: Int) {
                            if (fft != null) {
                                val magnitudes = FloatArray(20)
                                val size = minOf(fft.size / 2, magnitudes.size)
                                for (i in 0 until size) {
                                    val r = fft[2 * i].toFloat()
                                    val im = fft[2 * i + 1].toFloat()
                                    val mag = Math.hypot(r.toDouble(), im.toDouble()).toFloat()
                                    magnitudes[i] = mag
                                }
                                fftData = magnitudes
                            }
                        }
                    }, android.media.audiofx.Visualizer.getMaxCaptureRate() / 2, false, true)
                    enabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            onDispose {
                try {
                    visualizer?.enabled = false
                    visualizer?.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Simulated visualizer wave fallback
    if (!isVisualizerEnabled || !hasAudioPermission || audioSessionId == 0 || !playerState.isPlaying) {
        LaunchedEffect(playerState.isPlaying, isVisualizerEnabled) {
            if (isVisualizerEnabled && playerState.isPlaying) {
                while (true) {
                    val t = System.currentTimeMillis() / 250.0
                    fftData = FloatArray(20) { index ->
                        val base = Math.sin(t + index * 0.4).toFloat() * 0.4f + 0.5f
                        val noise = (Math.random().toFloat() * 0.2f)
                        (base + noise).coerceIn(0.1f, 1.0f) * 70f
                    }
                    kotlinx.coroutines.delay(50)
                }
            } else {
                fftData = FloatArray(20) { 3f }
            }
        }
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
    val shuffleEnabled = playerState.shuffleModeEnabled
    var repeatMode by remember(playerState.currentSong) {
        mutableStateOf(player.repeatMode)
    }

    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val background = MaterialTheme.colorScheme.background

    // Load album art bytes for Palette extraction
    val artBytes = rememberAlbumArt(currentSongUriString)
    val dominantColor = rememberDominantColor(artBytes)
    val animatedColor = if (disableAnimations) dominantColor else animateColorAsState(
        targetValue = dominantColor,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 1000),
        label = "DominantColor"
    ).value

    val glowEnabled = remember(settingsPrefs, playerState.currentSong?.mediaId) { settingsPrefs.getBoolean("ambient_glow_enabled", true) }
    val glowIntensity = remember(settingsPrefs, playerState.currentSong?.mediaId) { settingsPrefs.getString("ambient_glow_intensity", "normal") ?: "normal" }

    // Dynamic background gradient based on the animated extracted cover color
    val backgroundBrush = remember(animatedColor, background, glowEnabled, glowIntensity) {
        if (glowEnabled) {
            val alphaVal = if (glowIntensity == "strong") 0.85f else 0.35f
            Brush.verticalGradient(
                colors = listOf(
                    animatedColor.copy(alpha = alphaVal),
                    background
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    background,
                    background
                )
            )
        }
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
        val pulseScale = if (disableAnimations) 1f else {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.85f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 4000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseScale"
            )
            scale
        }

        if (glowEnabled) {
            val pulseAlpha = if (glowIntensity == "strong") 0.85f else 0.45f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = 0.5f
                        translationY = -120f
                    }
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                animatedColor.copy(alpha = pulseAlpha),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        suspend fun translateLyrics(isAuto: Boolean = false) {
            val getLocalized: (String, String) -> String = { es, en ->
                if (targetLang == "es") es else en
            }
            if (lyricLines.isNotEmpty()) {
                isTranslating = true
                val results = mutableMapOf<Long, String>()
                try {
                    withContext(Dispatchers.IO) {
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val linesToTranslate = lyricLines.filter { it.text.isNotBlank() }
                        
                        // Chunking into batches of at most 15 lines or 600 characters
                        val batches = mutableListOf<List<LyricLine>>()
                        var currentBatch = mutableListOf<LyricLine>()
                        var currentBatchLength = 0
                        
                        linesToTranslate.forEach { line ->
                            if (currentBatchLength + line.text.length > 600 || currentBatch.size >= 15) {
                                batches.add(currentBatch)
                                currentBatch = mutableListOf()
                                currentBatchLength = 0
                            }
                            currentBatch.add(line)
                            currentBatchLength += line.text.length + 1
                        }
                        if (currentBatch.isNotEmpty()) {
                            batches.add(currentBatch)
                        }
                        
                        // 1. Try Google Translate API first with newline separation
                        var googleTranslateSuccess = false
                        try {
                            val resultsTemp = mutableMapOf<Long, String>()
                            
                            for (batch in batches) {
                                val joinedText = batch.joinToString("\n") { it.text }
                                val encodedText = java.net.URLEncoder.encode(joinedText, "UTF-8")
                                val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encodedText"
                                val request = okhttp3.Request.Builder()
                                    .url(url)
                                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                                    .build()
                                
                                var batchTranslated = false
                                client.newCall(request).execute().use { response ->
                                    if (response.isSuccessful) {
                                        val body = response.body?.string() ?: ""
                                        val jsonArray = org.json.JSONArray(body)
                                        val segments = jsonArray.optJSONArray(0)
                                        if (segments != null) {
                                            val sb = java.lang.StringBuilder()
                                            for (i in 0 until segments.length()) {
                                                val segment = segments.optJSONArray(i)
                                                if (segment != null) {
                                                    sb.append(segment.optString(0, ""))
                                                }
                                            }
                                            val translatedBlock = sb.toString()
                                            val translatedLines = translatedBlock.split(Regex("\\r?\\n"))
                                            if (translatedLines.size == batch.size) {
                                                batch.forEachIndexed { index, line ->
                                                    val t = translatedLines.getOrNull(index)?.trim()
                                                    if (!t.isNullOrBlank()) {
                                                        resultsTemp[line.timeMs] = t
                                                    }
                                                }
                                                batchTranslated = true
                                            }
                                        }
                                    }
                                }
                                
                                if (!batchTranslated) {
                                    // Fallback: translate line-by-line for this batch
                                    for (line in batch) {
                                        val encLine = java.net.URLEncoder.encode(line.text, "UTF-8")
                                        val lUrl = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encLine"
                                        val lRequest = okhttp3.Request.Builder()
                                            .url(lUrl)
                                            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                                            .build()
                                        try {
                                            client.newCall(lRequest).execute().use { lResp ->
                                                if (lResp.isSuccessful) {
                                                    val lBody = lResp.body?.string() ?: ""
                                                    val lArr = org.json.JSONArray(lBody)
                                                    val lSegs = lArr.optJSONArray(0)
                                                    if (lSegs != null) {
                                                        val lSb = java.lang.StringBuilder()
                                                        for (j in 0 until lSegs.length()) {
                                                            val s = lSegs.optJSONArray(j)
                                                            if (s != null) {
                                                                lSb.append(s.optString(0, ""))
                                                            }
                                                        }
                                                        val resText = lSb.toString().trim()
                                                        if (resText.isNotBlank()) {
                                                            resultsTemp[line.timeMs] = resText
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (ex: Exception) {
                                            // Continue with other lines
                                        }
                                    }
                                }
                            }
                            
                            val nonBlankCount = linesToTranslate.size
                            val translatedCount = resultsTemp.keys.size
                            if (translatedCount >= nonBlankCount * 0.7) {
                                results.putAll(resultsTemp)
                                googleTranslateSuccess = true
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            e.printStackTrace()
                            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                                "Translation_Google",
                                "Google Translate failed",
                                e
                            )
                        }
                        
                        // 2. Fall back to MyMemory ONLY if Google Translate failed!
                        if (!googleTranslateSuccess) {
                            for (batch in batches) {
                                val joinedText = batch.joinToString("\n") { it.text }
                                val encodedText = java.net.URLEncoder.encode(joinedText, "UTF-8")
                                val url = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=autodetect|$targetLang&de=kevshupp.musicplayer@gmail.com"
                                val request = okhttp3.Request.Builder()
                                    .url(url)
                                    .header("User-Agent", "KevMusicPlayer/1.5.4")
                                    .build()
                                
                                var success = false
                                try {
                                    client.newCall(request).execute().use { response ->
                                        if (response.isSuccessful) {
                                            val body = response.body?.string() ?: ""
                                            val json = org.json.JSONObject(body)
                                            val status = json.optInt("responseStatus", 200)
                                            if (status == 200) {
                                                val translatedText = json.getJSONObject("responseData").getString("translatedText")
                                                val translatedLines = translatedText.split(Regex("\\r?\\n"))
                                                if (translatedLines.size == batch.size) {
                                                    batch.forEachIndexed { index, line ->
                                                        val t = translatedLines.getOrNull(index)?.trim()
                                                        if (!t.isNullOrBlank()) {
                                                            results[line.timeMs] = t
                                                        }
                                                    }
                                                    success = true
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                                        "Translation_MyMemory_Batch",
                                        "MyMemory Batch failed",
                                        e
                                    )
                                }
                                
                                if (!success) {
                                    for (line in batch) {
                                        val encodedLine = java.net.URLEncoder.encode(line.text, "UTF-8")
                                        val fallbackUrl = "https://api.mymemory.translated.net/get?q=$encodedLine&langpair=autodetect|$targetLang&de=kevshupp.musicplayer@gmail.com"
                                        val fallbackRequest = okhttp3.Request.Builder().url(fallbackUrl).build()
                                        try {
                                            client.newCall(fallbackRequest).execute().use { resp ->
                                                if (resp.isSuccessful) {
                                                    val b = resp.body?.string() ?: ""
                                                    val json = org.json.JSONObject(b)
                                                    val translated = json.getJSONObject("responseData").getString("translatedText")
                                                    if (translated.isNotBlank()) {
                                                        results[line.timeMs] = translated.trim()
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (results.isNotEmpty()) {
                        translatedLyricLines = results
                        showTranslation = true
                        // Persist to database cache
                        if (currentSongFile != null && viewModel != null) {
                            val serialized = LyricsRepository.serializeTranslations(results)
                            viewModel.updateSongTranslatedLyrics(currentSongFile.id, serialized)
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    e.printStackTrace()
                    com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                        "Translation_Outer",
                        "Outer translation task failed",
                        e
                    )
                    if (!isAuto) {
                        withContext(Dispatchers.Main) {
                            val msg = getLocalized(
                                "Error de red al traducir letras",
                                "Network error while translating lyrics"
                            )
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } finally {
                    isTranslating = false
                }
            }
        }

        val autoTranslateEnabled = remember(settingsPrefs) { settingsPrefs.getBoolean("auto_translate", false) }

        LaunchedEffect(currentSongFile?.id, lyricLines, autoTranslateEnabled) {
            val cachedTranslation = currentSongFile?.translatedLyrics
            val lyrics = currentSongFile?.lyrics
            if (autoTranslateEnabled && cachedTranslation.isNullOrBlank() && !lyrics.isNullOrBlank() && !isTranslating) {
                translateLyrics(true)
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

                // Spacer to keep NOW PLAYING text perfectly centered without right-side top bar buttons
                Spacer(modifier = Modifier.size(48.dp))
            }

            AnimatedContent(
                targetState = showLyrics,
                transitionSpec = {
                    if (disableAnimations) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        fadeIn(animationSpec = tween(400)) + scaleIn(initialScale = 0.95f, animationSpec = tween(400)) togetherWith
                        fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.95f, animationSpec = tween(300))
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "LyricsTransition"
            ) { targetShowLyrics ->
                if (targetShowLyrics) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 12.dp)
                    ) {
                        ScrollingLyricsView(
                            lyricLines = lyricLines,
                            positionMs = playerState.position,
                            songTitle = title,
                            songArtist = artist,
                            translatedLines = if (showTranslation) translatedLyricLines else null,
                            isTranslating = isTranslating,
                            onTranslateClick = {
                                if (translatedLyricLines != null) {
                                    showTranslation = !showTranslation
                                } else {
                                    scope.launch {
                                        translateLyrics()
                                        showTranslation = true
                                    }
                                }
                            },
                            onTranslateLongClick = {
                                scope.launch {
                                    translateLyrics()
                                    showTranslation = true
                                }
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
                                    searchLyricsResults = emptyList()
                                    showSearchLyricsDialog = true
                                }
                            },
                            isSearchingOnline = isSearchingOnline || isAutoSearchingLyrics,
                            isInstrumental = currentSongFile?.lyrics == "[[Instrumental]]",
                            onMarkInstrumentalClick = {
                                if (currentSongFile != null) {
                                    viewModel?.updateSongLyrics(currentSongFile.id, "[[Instrumental]]")
                                }
                            },
                            onSwipeLeft = { onSkipNext() },
                            onSwipeRight = { onSkipPrevious() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val currentMediaItemIndex = playerState.currentMediaItemIndex
                        val pagerState = rememberPagerState(
                            initialPage = currentMediaItemIndex,
                            pageCount = { playerState.mediaItemCount }
                        )

                        LaunchedEffect(currentMediaItemIndex) {
                            if (currentMediaItemIndex in 0 until playerState.mediaItemCount && pagerState.currentPage != currentMediaItemIndex) {
                                pagerState.scrollToPage(currentMediaItemIndex)
                            }
                        }

                        LaunchedEffect(pagerState.currentPage) {
                            if (!pagerState.isScrollInProgress && pagerState.currentPage != playerState.currentMediaItemIndex && pagerState.currentPage in 0 until playerState.mediaItemCount) {
                                player.seekTo(pagerState.currentPage, 0)
                                player.play()
                            }
                        }

                        HorizontalPager(
                            state = pagerState,
                            beyondViewportPageCount = 1,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) { page ->
                            val pageSong = remember(page, playerState.currentSong, playerState.playlistVersion) {
                                if (page in 0 until playerState.mediaItemCount) player.getMediaItemAt(page) else null
                            }
                            val pageSongFile = remember(pageSong?.mediaId) {
                                derivedStateOf {
                                    viewModel?.localAudioFiles?.find { it.id.toString() == pageSong?.mediaId }
                                }
                            }.value
                            val pageTitle = pageSong?.mediaMetadata?.title?.toString() ?: "Unknown Title"
                            val pageArtist = pageSong?.mediaMetadata?.artist?.toString() ?: "Unknown Artist"
                            val pageUriString = remember(pageSong?.mediaId) {
                                if (pageSong?.mediaId != null) "content://media/external/audio/media/${pageSong.mediaId}" else null
                            }
                            val pageIsFavorite = remember(pageSongFile?.id, viewModel?.playlists?.get("Favoritos")) {
                                val favList = viewModel?.playlists?.get("Favoritos") ?: emptyList()
                                val songId = pageSongFile?.id
                                songId != null && favList.any { it.id == songId }
                            }
                            val pageFileInfo by produceState(initialValue = Pair("MP3", "320 kbps"), key1 = pageSong?.mediaId) {
                                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    getAudioFileInfo(context, pageUriString)
                                }
                            }
                            val pageArtGradient = remember(pageTitle) {
                                getGradientForString(pageTitle)
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(pageSong?.mediaId) {
                                        detectTapGestures(
                                            onDoubleTap = { offset ->
                                                if (pageSongFile != null && viewModel != null) {
                                                    val favExists = viewModel.playlists.containsKey("Favoritos")
                                                    if (!favExists) {
                                                        viewModel.createPlaylist("Favoritos")
                                                    }
                                                    if (pageIsFavorite) {
                                                        viewModel.removeSongFromPlaylist("Favoritos", pageSongFile.id)
                                                    } else {
                                                        viewModel.addSongToPlaylist("Favoritos", pageSongFile.id)
                                                    }
                                                }
                                                heartPosition = offset
                                                showHeartAnimation = true
                                            }
                                        )
                                    }
                                    .pointerInput(pageSong?.mediaId) {
                                        var totalY = 0f
                                        detectVerticalDragGestures(
                                            onDragStart = { totalY = 0f },
                                            onDragEnd = {
                                                if (totalY < -100f) {
                                                    setLyricsVisible(true)
                                                } else if (totalY > 100f) {
                                                    onBack()
                                                }
                                            },
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                totalY += dragAmount
                                            }
                                        )
                                    }
                            ) {
                                Spacer(modifier = Modifier.weight(0.2f))

                                // Premium Album Art Container with rich shadow and organic roundings
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth(0.9f)
                                        .aspectRatio(1f)
                                        .shadow(
                                            elevation = 32.dp,
                                            shape = if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(32.dp) else androidx.compose.ui.graphics.RectangleShape,
                                            clip = false,
                                            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                            spotColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                        ),
                                    shape = if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(32.dp) else androidx.compose.ui.graphics.RectangleShape,
                                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(pageArtGradient),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val pageArtBytes = rememberAlbumArt(pageUriString)
                                        if (disableAnimations) {
                                            if (pageArtBytes != null) {
                                                Image(
                                                    bitmap = pageArtBytes.asImageBitmap(),
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
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
                                        } else {
                                            androidx.compose.animation.Crossfade(
                                                targetState = pageArtBytes,
                                                animationSpec = androidx.compose.animation.core.tween(durationMillis = 200),
                                                label = "PageAlbumArtCrossfade"
                                            ) { bitmap ->
                                                if (bitmap != null) {
                                                    Image(
                                                        bitmap = bitmap.asImageBitmap(),
                                                        contentDescription = null,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                } else {
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
                                            }
                                        }

                                        if (showHeartAnimation && page == pagerState.currentPage) {
                                            LaunchedEffect(showHeartAnimation) {
                                                if (showHeartAnimation) {
                                                    kotlinx.coroutines.delay(800)
                                                    showHeartAnimation = false
                                                }
                                            }

                                            val scale = if (disableAnimations) {
                                                if (showHeartAnimation) 1.5f else 0f
                                            } else {
                                                val animScale by animateFloatAsState(
                                                    targetValue = if (showHeartAnimation) 1.5f else 0f,
                                                    animationSpec = androidx.compose.animation.core.spring(
                                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                                        stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                                                    ),
                                                    label = "heartScale"
                                                )
                                                animScale
                                            }

                                            val hPos = heartPosition ?: androidx.compose.ui.geometry.Offset.Zero
                                            Icon(
                                                imageVector = if (pageIsFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                                contentDescription = null,
                                                tint = if (pageIsFavorite) Color.Red else Color.White.copy(alpha = 0.8f),
                                                modifier = Modifier
                                                    .offset {
                                                        androidx.compose.ui.unit.IntOffset(
                                                            (hPos.x - 48.dp.toPx()).toInt(),
                                                            (hPos.y - 48.dp.toPx()).toInt()
                                                        )
                                                    }
                                                    .size(96.dp)
                                                    .graphicsLayer {
                                                        scaleX = scale
                                                        scaleY = scale
                                                        alpha = (1f - (scale - 1f).coerceIn(0f, 1f))
                                                    }
                                            )
                                        }
                                    }
                                }

                                if (isVisualizerEnabled) {
                                    val waveColor = animatedColor
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(60.dp)
                                            .padding(vertical = 8.dp)
                                            .clickable {
                                                isVisualizerEnabled = false
                                                settingsPrefs.edit().putBoolean("show_visualizer", false).apply()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        FFTVisualizer(
                                            fftData = fftData,
                                            hasAudioPermission = hasAudioPermission,
                                            audioSessionId = audioSessionId,
                                            waveColor = waveColor,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                } else {
                                    Spacer(
                                        modifier = Modifier
                                            .weight(0.3f)
                                            .clickable {
                                                val recordPermission = android.Manifest.permission.RECORD_AUDIO
                                                val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                                    context,
                                                    recordPermission
                                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                                                if (!hasPermission) {
                                                    permissionLauncher.launch(recordPermission)
                                                } else {
                                                    isVisualizerEnabled = true
                                                    settingsPrefs.edit().putBoolean("show_visualizer", true).apply()
                                                }
                                            }
                                    )
                                }

                                // Title and Artist with clean spacing
                                 Column(
                                     horizontalAlignment = Alignment.CenterHorizontally,
                                     modifier = Modifier.fillMaxWidth()
                                 ) {
                                     Text(
                                         text = pageTitle,
                                         style = MaterialTheme.typography.headlineMedium.copy(
                                             fontWeight = FontWeight.Black,
                                             letterSpacing = (-0.5).sp
                                         ),
                                         maxLines = 1,
                                         overflow = TextOverflow.Ellipsis,
                                         textAlign = TextAlign.Center,
                                         color = MaterialTheme.colorScheme.onBackground
                                     )

                                     Spacer(modifier = Modifier.height(4.dp))

                                     Text(
                                         text = pageArtist,
                                         style = MaterialTheme.typography.titleMedium.copy(
                                             fontWeight = FontWeight.Medium
                                         ),
                                         color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                         maxLines = 1,
                                         overflow = TextOverflow.Ellipsis,
                                         textAlign = TextAlign.Center,
                                         modifier = Modifier.clickable {
                                             if (pageArtist != "Unknown Artist" && pageArtist.isNotBlank()) {
                                                 onNavigateToArtist(pageArtist)
                                                 onBack()
                                             }
                                         }
                                     )
                                 }

                                 Spacer(modifier = Modifier.weight(0.15f))

                                 // Playback Controls Row (Repeat | Prev | Play/Pause | Next | Shuffle)
                                 Row(
                                     modifier = Modifier.fillMaxWidth(),
                                     horizontalArrangement = Arrangement.SpaceEvenly,
                                     verticalAlignment = Alignment.CenterVertically
                                 ) {
                                     // Repeat Button (Far Left)
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

                                     // Previous Button
                                     IconButton(
                                         onClick = { onSkipPrevious() },
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
                                             .size(72.dp)
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
                                                 if (playerState.isPlaying) {
                                                     player.pause()
                                                 } else {
                                                     if (player.playbackState == Player.STATE_IDLE) {
                                                         player.prepare()
                                                     }
                                                     player.play()
                                                 }
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
                                         onClick = { onSkipNext() },
                                         modifier = Modifier.size(54.dp)
                                     ) {
                                         Icon(
                                             imageVector = Icons.Rounded.SkipNext,
                                             contentDescription = "Next",
                                             tint = MaterialTheme.colorScheme.onBackground,
                                             modifier = Modifier.size(34.dp)
                                         )
                                     }

                                     // Shuffle Button (Far Right)
                                     IconButton(
                                         onClick = {
                                             val newShuffle = !shuffleEnabled
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
                                 }

                                 Spacer(modifier = Modifier.weight(0.15f))
                             }
                         }
                     }
                 }
             }

             // Seeking Slider & Times
             Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
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

                 Spacer(modifier = Modifier.height(10.dp))

                 // Audio Format Info (MP3 · 320 kb/s · 44.1 kHz)
                 val context = LocalContext.current
                 val pageFileInfo by produceState(initialValue = Pair("MP3", "320 kbps"), key1 = playerState.currentSong?.mediaId) {
                     value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                         getAudioFileInfo(context, currentSongUriString)
                     }
                 }
                 Text(
                     text = "${pageFileInfo.first} · ${pageFileInfo.second}",
                     style = MaterialTheme.typography.labelSmall.copy(
                         fontWeight = FontWeight.Bold,
                         letterSpacing = 0.8.sp
                     ),
                     color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                     textAlign = TextAlign.Center,
                     modifier = Modifier.fillMaxWidth()
                 )
             }

             Spacer(modifier = Modifier.height(18.dp))

             // Bottom Action Bar (5 Action Icons: Lyrics, Like, Sleep Timer, Queue, 3 Dots)
             Row(
                 modifier = Modifier
                     .fillMaxWidth()
                     .padding(bottom = 12.dp),
                 horizontalArrangement = Arrangement.SpaceEvenly,
                 verticalAlignment = Alignment.CenterVertically
             ) {
                 // 1. Lyrics Icon
                 IconButton(onClick = { setLyricsVisible(!showLyrics) }) {
                     Icon(
                         imageVector = Icons.Rounded.ChatBubbleOutline,
                         contentDescription = "Letras",
                         tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                         modifier = Modifier.size(24.dp)
                     )
                 }

                 // 2. Favorite Icon (Like)
                 IconButton(
                     onClick = {
                         if (currentSongFile != null && viewModel != null) {
                             val favExists = viewModel.playlists.containsKey("Favoritos")
                             if (!favExists) {
                                 viewModel.createPlaylist("Favoritos")
                             }
                             if (isFavorite) {
                                 viewModel.removeSongFromPlaylist("Favoritos", currentSongFile.id)
                             } else {
                                 viewModel.addSongToPlaylist("Favoritos", currentSongFile.id)
                             }
                         }
                     }
                 ) {
                     Icon(
                         imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                         contentDescription = "Favoritos",
                         tint = if (isFavorite) Color.Red else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                         modifier = Modifier.size(24.dp)
                     )
                 }

                 // 3. Moon / Sleep Timer Icon
                 IconButton(onClick = { showSleepTimerDialog = true }) {
                     Icon(
                         imageVector = Icons.Rounded.Bedtime,
                         contentDescription = "Temporizador",
                         tint = if (sleepTimerMinutes != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                         modifier = Modifier.size(24.dp)
                     )
                 }

                 // 4. Queue Icon
                 IconButton(onClick = { showQueueSheet = true }) {
                     Icon(
                         imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                         contentDescription = "Cola",
                         tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                         modifier = Modifier.size(24.dp)
                     )
                 }

                 // 5. 3 Dots / More Options Icon
                 IconButton(onClick = { showMoreOptions = true }) {
                     Icon(
                         imageVector = Icons.Rounded.MoreHoriz,
                         contentDescription = "Más opciones",
                         tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                         modifier = Modifier.size(24.dp)
                     )
                 }
             }
         }

        // Gorgeous Modal Bottom Sheet matching reference menu
        if (showMoreOptions) {
            ModalBottomSheet(
                onDismissRequest = { showMoreOptions = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF161829),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item {
                        // Header of Bottom Sheet
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            val artBytes = rememberAlbumArt(currentSongUriString)
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(if (com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded.current) RoundedCornerShape(14.dp) else androidx.compose.ui.graphics.RectangleShape)
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
                        
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(bottom = 6.dp))
                    }

                    // 1. Ecualizador
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showMoreOptions = false
                                    try {
                                        val intent = android.content.Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "Ecualizador no disponible", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Equalizer, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Ecualizador", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // 2. Guardar cola de reproducción
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showMoreOptions = false
                                    showSaveQueueDialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Guardar cola de reproducción", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // 3. Limpiar cola de reproducción
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showMoreOptions = false
                                    player.clearMediaItems()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Limpiar cola de reproducción", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    item {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 6.dp))
                    }

                    // 4. Ir al álbum
                    item {
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
                            Icon(Icons.Rounded.Album, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Ir al álbum", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // 5. Ir al artista
                    item {
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
                            Icon(Icons.Rounded.Person, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Ir al artista", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // 6. Ver artista del álbum
                    item {
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
                            Icon(Icons.Rounded.Group, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Ver artista del álbum", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // 7. Ir a la carpeta
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showMoreOptions = false
                                    val folder = currentSongFile?.folderName ?: ""
                                    if (folder.isNotBlank()) {
                                        viewModel?.requestedTab?.value = "Carpetas"
                                    }
                                    onBack()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Folder, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Ir a la carpeta", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    item {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 6.dp))
                    }

                    // 8. Agregar a lista de reproducción
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showMoreOptions = false
                                    showAddToPlaylistDialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Agregar a lista de reproducción", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // 9. Editar información
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showMoreOptions = false
                                    showTagEditorDialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Editar información", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // 10. Editar letras
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showMoreOptions = false
                                    showEditLyricsDialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.EditNote, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Editar letras", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    item {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 6.dp))
                    }

                    // 11. Detalles
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showMoreOptions = false
                                    showFileInfoDialog = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Info, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Detalles", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // 12. Compartir
                    item {
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
                                        context.startActivity(android.content.Intent.createChooser(shareIntent, "Compartir pista"))
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Compartir", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // Option: Delete Track
                    item {
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
                            Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Eliminar pista", color = MaterialTheme.colorScheme.error, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // Option: Delete Translation (only shown if translation exists)
                    if (translatedLyricLines != null) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showMoreOptions = false
                                        if (currentSongFile != null && viewModel != null) {
                                            viewModel.deleteSongTranslatedLyrics(currentSongFile.id)
                                        }
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Translate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Eliminar traducción", color = MaterialTheme.colorScheme.error, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }

        // Sleep Timer Dialog
        if (showSleepTimerDialog) {
            AlertDialog(
                onDismissRequest = { showSleepTimerDialog = false },
                title = { Text("Temporizador de apagado", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val options = listOf(
                            0 to "Desactivado",
                            15 to "15 minutos",
                            30 to "30 minutos",
                            45 to "45 minutos",
                            60 to "60 minutos",
                            -1 to "Al finalizar esta canción"
                        )
                        options.forEach { (mins, label) ->
                            val isSelected = sleepTimerMinutes == mins
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        sleepTimerMinutes = mins
                                        showSleepTimerDialog = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        sleepTimerMinutes = mins
                                        showSleepTimerDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(label, color = Color.White, fontSize = 15.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSleepTimerDialog = false }) {
                        Text("Cancelar", color = Color.White.copy(alpha = 0.7f))
                    }
                },
                containerColor = Color(0xFF161829),
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }

        // Save Queue as Playlist Dialog
        if (showSaveQueueDialog) {
            var playlistNameInput by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showSaveQueueDialog = false },
                title = { Text("Guardar cola de reproducción", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("Introduce un nombre para la nueva lista:", color = Color.White.copy(alpha = 0.8f))
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = playlistNameInput,
                            onValueChange = { playlistNameInput = it },
                            label = { Text("Nombre de lista") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (playlistNameInput.isNotBlank() && viewModel != null) {
                                val queue = viewModel.getPlayerQueue()
                                viewModel.createPlaylist(playlistNameInput)
                                queue.forEach { song ->
                                    viewModel.addSongToPlaylist(playlistNameInput, song.id)
                                }
                            }
                            showSaveQueueDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Guardar", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveQueueDialog = false }) {
                        Text("Cancelar", color = Color.White.copy(alpha = 0.7f))
                    }
                },
                containerColor = Color(0xFF161829),
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }

        // Add Song to Playlist Dialog
        if (showAddToPlaylistDialog && currentSongFile != null && viewModel != null) {
            var newPlaylistInput by remember { mutableStateOf("") }
            var showCreateField by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { showAddToPlaylistDialog = false },
                title = { Text("Agregar a lista de reproducción", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showCreateField = !showCreateField }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Crear nueva lista...", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            if (showCreateField) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = newPlaylistInput,
                                        onValueChange = { newPlaylistInput = it },
                                        placeholder = { Text("Nombre...") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            if (newPlaylistInput.isNotBlank()) {
                                                viewModel.createPlaylist(newPlaylistInput)
                                                viewModel.addSongToPlaylist(newPlaylistInput, currentSongFile.id)
                                                showAddToPlaylistDialog = false
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text("OK", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        }

                        items(viewModel.playlists.keys.toList()) { playlistName ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.addSongToPlaylist(playlistName, currentSongFile.id)
                                        showAddToPlaylistDialog = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(playlistName, color = Color.White, fontSize = 15.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAddToPlaylistDialog = false }) {
                        Text("Cerrar", color = Color.White.copy(alpha = 0.7f))
                    }
                },
                containerColor = Color(0xFF161829),
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
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
                            itemsIndexed(queueSongs, key = { index, song -> "${song.id}_$index" }) { index, song ->
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
            val getLocalized = { es: String, en: String ->
                if (targetLang == "es") es else en
            }
            AlertDialog(
                onDismissRequest = { 
                    if (!isSearchingOnline) showSearchLyricsDialog = false 
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(getLocalized("Buscar Letras en Línea", "Search Lyrics Online"), fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = getLocalized("Corrige el artista o título para buscar coincidencias alternativas:", "Correct the artist or title below to search alternative matches:"),
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        OutlinedTextField(
                            value = searchArtist,
                            onValueChange = { searchArtist = it },
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
                        OutlinedTextField(
                            value = searchTitle,
                            onValueChange = { searchTitle = it },
                            label = { Text(getLocalized("Título de Canción", "Song Title"), color = Color.White.copy(alpha = 0.5f)) },
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
                        
                        Button(
                            onClick = {
                                scope.launch {
                                    searchStatusMessage = getLocalized("Buscando en la base de datos...", "Searching LRCLIB database...")
                                    isSearchingOnline = true
                                    val results = LyricsRepository.searchLyricsOptionsFromLrcLib(searchArtist, searchTitle)
                                    searchLyricsResults = results
                                    if (results.isNotEmpty()) {
                                        searchStatusMessage = getLocalized("Se encontraron ${results.size} resultados.", "Found ${results.size} results.")
                                    } else {
                                        searchStatusMessage = getLocalized("No se encontraron letras. Intenta refinar la búsqueda.", "No lyrics found. Try refining the query!")
                                    }
                                    isSearchingOnline = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSearchingOnline
                        ) {
                            if (isSearchingOnline) {
                                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text(getLocalized("Buscar Coincidencias", "Search Matches"), fontWeight = FontWeight.Bold)
                            }
                        }

                        if (searchStatusMessage.isNotEmpty()) {
                            Text(
                                text = searchStatusMessage,
                                color = if (searchLyricsResults.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        if (searchLyricsResults.isNotEmpty()) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                text = getLocalized("SELECCIONA UNA LETRA:", "SELECT LYRICS TO APPLY:"),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.sp
                            )
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(searchLyricsResults, key = { it.id }) { result ->
                                    val isSynced = result.syncedLyrics != null
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = Color.White.copy(alpha = 0.05f)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val lyricsToApply = result.syncedLyrics ?: result.plainLyrics
                                                if (!lyricsToApply.isNullOrEmpty()) {
                                                    viewModel?.updateSongLyrics(currentSongFile.id, lyricsToApply)
                                                    showSearchLyricsDialog = false
                                                    searchLyricsResults = emptyList()
                                                    searchStatusMessage = ""
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = result.trackName,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    fontSize = 13.sp
                                                )
                                                Text(
                                                    text = "${result.artistName} • ${result.albumName}",
                                                    color = Color.White.copy(alpha = 0.5f),
                                                    fontSize = 11.sp
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (isSynced) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                        else MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = if (isSynced) getLocalized("Sincro", "Synced") else getLocalized("Texto", "Plain"),
                                                    color = if (isSynced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        onClick = { 
                            showSearchLyricsDialog = false 
                            searchLyricsResults = emptyList()
                            searchStatusMessage = ""
                        },
                        enabled = !isSearchingOnline
                    ) {
                        Text(getLocalized("Cancelar", "Cancel"), color = Color.White.copy(alpha = 0.6f))
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

        if (showTagEditorDialog && currentSongFile != null && viewModel != null) {
            TagEditorDialog(
                song = currentSongFile,
                viewModel = viewModel,
                onDismiss = { showTagEditorDialog = false }
            )
        }

        // Technical Audio Info Dialog
        if (showFileInfoDialog) {
            val currentSongUriString = remember(playerState.currentSong?.mediaId) {
                val mediaId = playerState.currentSong?.mediaId
                if (mediaId != null) "content://media/external/audio/media/$mediaId" else null
            }
            val detailedInfo by produceState(
                initialValue = DetailedAudioFileInfo("Loading...", "Loading...", "Loading...", "Loading...", "Loading...", "Loading...", "Loading..."),
                key1 = playerState.currentSong?.mediaId
            ) {
                value = getDetailedAudioFileInfo(context, currentSongUriString)
            }
            val getLocalized = { es: String, en: String ->
                if (java.util.Locale.getDefault().language == "es") es else en
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
                        Text(getLocalized("Especificaciones de Audio", "Audio Specifications"), fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(getLocalized("Título: ${detailedInfo.title}", "Title: ${detailedInfo.title}"), fontWeight = FontWeight.Bold, color = Color.White)
                        Text(getLocalized("Artista: ${detailedInfo.artist}", "Artist: ${detailedInfo.artist}"), color = Color.White.copy(alpha = 0.8f))
                        Text(getLocalized("Álbum: ${detailedInfo.album}", "Album: ${detailedInfo.album}"), color = Color.White.copy(alpha = 0.8f))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(getLocalized("Ubicación: ${detailedInfo.location}", "Location: ${detailedInfo.location}"), fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                        Text(getLocalized("Tipo: ${detailedInfo.type}", "Type: ${detailedInfo.type}"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(getLocalized("Tasa de bits: ${detailedInfo.bitrate}", "Bitrate: ${detailedInfo.bitrate}"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(getLocalized("Tamaño: ${detailedInfo.size}", "Size: ${detailedInfo.size}"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showFileInfoDialog = false }) {
                        Text(getLocalized("Cerrar", "Close"), fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF161829),
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }
    }
}

@Composable
fun rememberDominantColor(bitmap: android.graphics.Bitmap?): Color {
    val defaultColor = MaterialTheme.colorScheme.surfaceVariant
    var dominantColor by remember(bitmap) { mutableStateOf(defaultColor) }

    LaunchedEffect(bitmap) {
        if (bitmap != null) {
            withContext(Dispatchers.IO) {
                try {
                    val palette = Palette.from(bitmap).generate()
                    val color = palette.getVibrantColor(
                        palette.getDominantColor(defaultColor.toArgb())
                    )
                    dominantColor = Color(color)
                    // Note: We MUST NOT call bitmap.recycle() here, because the bitmap is cached
                    // globally in albumArtCache and will be drawn/reused by rememberAlbumArt.
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            dominantColor = defaultColor
        }
    }
    return dominantColor
}

