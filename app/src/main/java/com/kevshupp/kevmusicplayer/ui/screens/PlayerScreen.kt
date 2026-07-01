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
    var showMoreOptions by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showFileInfoDialog by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showEditLyricsDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showTagEditorDialog by remember { mutableStateOf(false) }
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
        showLyrics = false
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
    
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0]
    val targetLang = locale.language // "es" or "en"

    val settingsPrefs = remember(context) { context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE) }
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

    val isFavorite = remember(currentSongFile?.id, viewModel?.playlists) {
        val favList = viewModel?.playlists?.get("Favoritos") ?: emptyList()
        favList.any { it.id == currentSongFile?.id }
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
    val animatedColor by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 1000),
        label = "DominantColor"
    )

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
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )

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

        val translateLyrics: suspend () -> Unit = {
            val getLocalized: (String, String) -> String = { es, en ->
                if (targetLang == "es") es else en
            }
            if (lyricLines.isNotEmpty()) {
                isTranslating = true
                val results = mutableMapOf<Long, String>()
                try {
                    withContext(Dispatchers.IO) {
                        val client = okhttp3.OkHttpClient()
                        val linesToTranslate = lyricLines.filter { it.text.isNotBlank() }
                        
                        // Detect source language based on all non-blank lyric lines combined!
                        val fullLyricsSample = linesToTranslate.joinToString("\n") { it.text }
                        val detectedSource = detectLanguage(fullLyricsSample)
                        val sourceLang = if (detectedSource == targetLang) {
                            if (targetLang == "es") "en" else "es"
                        } else {
                            detectedSource
                        }
                        
                        android.util.Log.d("KevTranslation", "Starting translation from $sourceLang to $targetLang. Sample: ${fullLyricsSample.take(50)}")
                        
                        // Chunking into batches of at most 450 characters or 10 lines to stay safe
                        val batches = mutableListOf<List<LyricLine>>()
                        var currentBatch = mutableListOf<LyricLine>()
                        var currentBatchLength = 0
                        
                        linesToTranslate.forEach { line ->
                            if (currentBatchLength + line.text.length > 450 || currentBatch.size >= 10) {
                                batches.add(currentBatch)
                                currentBatch = mutableListOf()
                                currentBatchLength = 0
                            }
                            currentBatch.add(line)
                            currentBatchLength += line.text.length + 3
                        }
                        if (currentBatch.isNotEmpty()) {
                            batches.add(currentBatch)
                        }
                        
                        android.util.Log.d("KevTranslation", "Prepared ${batches.size} batches for translation.")
                        
                        // 1. Try Google Translate first! (Unlimited, completely free, robust)
                        var googleTranslateSuccess = false
                        try {
                            android.util.Log.d("KevTranslation", "Attempting translation via Google Translate API...")
                            val resultsTemp = mutableMapOf<Long, String>()
                            
                            for (batchIdx in batches.indices) {
                                val batch = batches[batchIdx]
                                val joinedText = batch.joinToString(" | ") { it.text }
                                val encodedText = java.net.URLEncoder.encode(joinedText, "UTF-8")
                                val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encodedText"
                                val request = okhttp3.Request.Builder().url(url).build()
                                
                                client.newCall(request).execute().use { response ->
                                    if (response.isSuccessful) {
                                        val body = response.body?.string() ?: ""
                                        val jsonArray = org.json.JSONArray(body)
                                        val segments = jsonArray.getJSONArray(0)
                                        val sb = java.lang.StringBuilder()
                                        for (i in 0 until segments.length()) {
                                            val segment = segments.getJSONArray(i)
                                            sb.append(segment.getString(0))
                                        }
                                        val translatedText = sb.toString()
                                        val translatedLines = translatedText.split("|")
                                        if (translatedLines.size == batch.size) {
                                            batch.forEachIndexed { index, line ->
                                                resultsTemp[line.timeMs] = translatedLines[index].trim()
                                            }
                                        } else {
                                            // Fallback: translate line-by-line via Google Translate
                                            for (lineIdx in batch.indices) {
                                                val line = batch[lineIdx]
                                                val encLine = java.net.URLEncoder.encode(line.text, "UTF-8")
                                                val lUrl = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encLine"
                                                val lRequest = okhttp3.Request.Builder().url(lUrl).build()
                                                client.newCall(lRequest).execute().use { lResp ->
                                                    if (lResp.isSuccessful) {
                                                        val lBody = lResp.body?.string() ?: ""
                                                        val lArr = org.json.JSONArray(lBody)
                                                        val lSegs = lArr.getJSONArray(0)
                                                        val lSb = java.lang.StringBuilder()
                                                        for (j in 0 until lSegs.length()) {
                                                            lSb.append(lSegs.getJSONArray(j).getString(0))
                                                        }
                                                        resultsTemp[line.timeMs] = lSb.toString().trim()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            val nonBlankCount = linesToTranslate.size
                            val translatedCount = resultsTemp.keys.size
                            android.util.Log.d("KevTranslation", "Google Translate fetched: $translatedCount/$nonBlankCount")
                            if (translatedCount >= nonBlankCount * 0.8) {
                                results.putAll(resultsTemp)
                                googleTranslateSuccess = true
                                android.util.Log.d("KevTranslation", "Google Translate successful! Skipping MyMemory fallback.")
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            android.util.Log.e("KevTranslation", "Google Translate failed/timed out: ${e.message}")
                            e.printStackTrace()
                            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                                "Translation_Google",
                                "Google Translate failed/timed out",
                                e
                            )
                        }
                        
                        // 2. Fall back to MyMemory ONLY if Google Translate failed!
                        if (!googleTranslateSuccess) {
                            android.util.Log.w("KevTranslation", "Google Translate failed. Falling back to MyMemory API...")
                            var hitRateLimit = false
                            
                            for (batchIdx in batches.indices) {
                                if (hitRateLimit) break
                                
                                val batch = batches[batchIdx]
                                val joinedText = batch.joinToString(" | ") { it.text }
                                val encodedText = java.net.URLEncoder.encode(joinedText, "UTF-8")
                                val url = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=$sourceLang|$targetLang&de=kevshupp.musicplayer@gmail.com"
                                val request = okhttp3.Request.Builder().url(url).build()
                                
                                android.util.Log.d("KevTranslation", "Requesting Batch $batchIdx from MyMemory: URL = $url")
                                var success = false
                                try {
                                    client.newCall(request).execute().use { response ->
                                        if (response.code == 429) {
                                            hitRateLimit = true
                                        }
                                        
                                        if (response.isSuccessful) {
                                            val body = response.body?.string() ?: ""
                                            val json = org.json.JSONObject(body)
                                            val status = json.optInt("responseStatus", 200)
                                            if (status == 200) {
                                                val translatedText = json.getJSONObject("responseData").getString("translatedText")
                                                val translatedLines = translatedText.split("|")
                                                if (translatedLines.size == batch.size) {
                                                    batch.forEachIndexed { index, line ->
                                                        results[line.timeMs] = translatedLines[index].trim()
                                                    }
                                                    success = true
                                                }
                                            } else if (status == 429) {
                                                hitRateLimit = true
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    android.util.Log.e("KevTranslation", "MyMemory Batch $batchIdx failed: ${e.message}")
                                    com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                                        "Translation_MyMemory_Batch",
                                        "MyMemory Batch $batchIdx failed",
                                        e
                                    )
                                }
                                
                                if (!success && !hitRateLimit) {
                                    for (lineIdx in batch.indices) {
                                        if (hitRateLimit) break
                                        val line = batch[lineIdx]
                                        val encodedLine = java.net.URLEncoder.encode(line.text, "UTF-8")
                                        val fallbackUrl = "https://api.mymemory.translated.net/get?q=$encodedLine&langpair=$sourceLang|$targetLang&de=kevshupp.musicplayer@gmail.com"
                                        val fallbackRequest = okhttp3.Request.Builder().url(fallbackUrl).build()
                                        try {
                                            client.newCall(fallbackRequest).execute().use { resp ->
                                                if (resp.code == 429) {
                                                    hitRateLimit = true
                                                }
                                                if (resp.isSuccessful) {
                                                    val b = resp.body?.string() ?: ""
                                                    val json = org.json.JSONObject(b)
                                                    val translated = json.getJSONObject("responseData").getString("translatedText")
                                                    results[line.timeMs] = translated.trim()
                                                }
                                            }
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            e.printStackTrace()
                                            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                                                "Translation_MyMemory_Fallback",
                                                "MyMemory single line fallback failed for line indexing",
                                                e
                                            )
                                        }
                                    }
                                }
                            }
                            
                            if (hitRateLimit) {
                                throw Exception("rate_limit_429")
                            }
                        }
                    }
                    translatedLyricLines = results
                    showTranslation = true
                    android.util.Log.d("KevTranslation", "Translation completed successfully. Total translated lines stored: ${results.size}")
                    // Persist to database cache!
                    if (currentSongFile != null && viewModel != null && results.isNotEmpty()) {
                        val serialized = LyricsRepository.serializeTranslations(results)
                        viewModel.updateSongTranslatedLyrics(currentSongFile.id, serialized)
                        android.util.Log.d("KevTranslation", "Translations cached to local DB for song ID: ${currentSongFile.id}")
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.e("KevTranslation", "Outer try-catch failure: ${e.message}")
                    e.printStackTrace()
                    com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                        "Translation_Outer",
                        "Outer translation task failed",
                        e
                    )
                    withContext(Dispatchers.Main) {
                        val msg = if (e.message == "rate_limit_429") {
                            getLocalized(
                                "Límite de traducción excedido (429). Por favor, intenta de nuevo más tarde.",
                                "Translation limit exceeded (429). Please try again later."
                            )
                        } else {
                            getLocalized(
                                "Error de red: verifica tu conexión / Network error",
                                "Network error: check your connection"
                            )
                        }
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
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
                val parsedLines = LyricsRepository.parseLrc(lyrics)
                val sample = parsedLines.filter { it.text.isNotBlank() }.joinToString("\n") { it.text }
                if (sample.isNotBlank()) {
                    val detected = detectLanguage(sample)
                    if (detected != targetLang) {
                        translateLyrics()
                    }
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
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
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
                    if (pagerState.currentPage != playerState.currentMediaItemIndex && pagerState.currentPage in 0 until playerState.mediaItemCount) {
                        player.seekTo(pagerState.currentPage, 0)
                        player.play()
                    }
                }

                HorizontalPager(
                    state = pagerState,
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
                    val pageIsFavorite = remember(pageSongFile, viewModel?.playlists) {
                        val favList = viewModel?.playlists?.get("Favoritos") ?: emptyList()
                        favList.any { it.id == pageSongFile?.id }
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
                                    },
                                    onTap = {
                                        showLyrics = !showLyrics
                                    }
                                )
                            }
                            .pointerInput(pageSong?.mediaId) {
                                var totalY = 0f
                                detectVerticalDragGestures(
                                    onDragStart = { totalY = 0f },
                                    onDragEnd = {
                                        if (totalY < -100f) {
                                            showLyrics = true
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
                                SubcomposeAsyncImage(
                                    model = pageArtBytes,
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

                                if (showHeartAnimation && page == pagerState.currentPage) {
                                    LaunchedEffect(showHeartAnimation) {
                                        if (showHeartAnimation) {
                                            kotlinx.coroutines.delay(800)
                                            showHeartAnimation = false
                                        }
                                    }

                                    val scale by animateFloatAsState(
                                        targetValue = if (showHeartAnimation) 1.5f else 0f,
                                        animationSpec = androidx.compose.animation.core.spring(
                                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                                        ),
                                        label = "heartScale"
                                    )

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
                            val barWidth = 6.dp
                            val barSpacing = 4.dp

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

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = pageArtist,
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
                                            text = pageFileInfo.first,
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
                                            text = pageFileInfo.second,
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
                }
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
                val context = androidx.compose.ui.platform.LocalContext.current
                IconButton(
                    onClick = {
                        val crossfadeSeconds = context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE).getInt("crossfade_duration", 0)
                        val controller = player as? androidx.media3.session.MediaController
                        if (controller != null && crossfadeSeconds > 0) {
                            controller.sendCustomCommand(
                                androidx.media3.session.SessionCommand("ACTION_SKIP_PREV", android.os.Bundle.EMPTY),
                                android.os.Bundle.EMPTY
                            )
                        } else {
                            player.seekToPrevious()
                        }
                    },
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
                    onClick = {
                        val crossfadeSeconds = context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE).getInt("crossfade_duration", 0)
                        val controller = player as? androidx.media3.session.MediaController
                        if (controller != null && crossfadeSeconds > 0) {
                            controller.sendCustomCommand(
                                androidx.media3.session.SessionCommand("ACTION_SKIP_NEXT", android.os.Bundle.EMPTY),
                                android.os.Bundle.EMPTY
                            )
                        } else {
                            player.seekToNext()
                        }
                    },
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
                            val mediaId = playerState.currentSong?.mediaId
                            if (mediaId != null) "content://media/external/audio/media/$mediaId" else null
                        }
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
                    
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    
                    val context = LocalContext.current
                    val currentSongUriString = remember(playerState.currentSong?.mediaId) {
                        val mediaId = playerState.currentSong?.mediaId
                        if (mediaId != null) "content://media/external/audio/media/$mediaId" else null
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

                    // Option: Edit Metadata
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
                        Icon(
                            imageVector = Icons.Rounded.EditNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = if (targetLang == "es") "Editar metadatos" else "Edit Metadata",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
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

                    // Gorgeous premium Option: Delete Translation (only shown if translation exists)
                    if (translatedLyricLines != null) {
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
                            Text("Eliminar traducción", color = MaterialTheme.colorScheme.error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
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
fun rememberDominantColor(artBytes: ByteArray?): Color {
    val defaultColor = MaterialTheme.colorScheme.surfaceVariant
    var dominantColor by remember(artBytes) { mutableStateOf(defaultColor) }

    LaunchedEffect(artBytes) {
        if (artBytes != null) {
            withContext(Dispatchers.IO) {
                try {
                    val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                    if (bitmap != null) {
                        val palette = Palette.from(bitmap).generate()
                        val color = palette.getVibrantColor(
                            palette.getDominantColor(defaultColor.toArgb())
                        )
                        dominantColor = Color(color)
                        bitmap.recycle()
                    }
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

