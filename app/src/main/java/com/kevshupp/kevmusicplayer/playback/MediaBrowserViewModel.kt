package com.kevshupp.kevmusicplayer.playback

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.AudioScanner
import com.kevshupp.kevmusicplayer.data.AppDatabase
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.kevshupp.kevmusicplayer.data.LyricsRepository
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import java.io.InputStream
import java.io.OutputStream
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import com.kevshupp.kevmusicplayer.ui.screens.stripAccents

class MediaBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private var initialDbLoadJob: kotlinx.coroutines.Job? = null
    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    val browser = mutableStateOf<MediaBrowser?>(null)
    val localAudioFiles = mutableStateListOf<AudioFile>()
    val audioFileMap: Map<String, AudioFile> by androidx.compose.runtime.derivedStateOf {
        localAudioFiles.associateBy { it.id.toString() }
    }
    val searchIndex: Map<Long, String> by androidx.compose.runtime.derivedStateOf {
        localAudioFiles.associate { song ->
            song.id to "${song.title.stripAccents()} ${song.artist.stripAccents()} ${song.album.stripAccents()} ${song.genre.stripAccents()}"
        }
    }
    val enabledTabs = mutableStateOf(run {
        val prefs = application.getSharedPreferences("playback_prefs", android.content.Context.MODE_PRIVATE)
        val saved = prefs.getString("enabled_tabs", null)
        if (!saved.isNullOrBlank()) {
            saved.split(",")
        } else {
            listOf("Songs", "Albums", "Artists", "Genres", "Folders", "Playlists")
        }
    })
    val sortBy = mutableStateOf("Alphabetical")
    
    // Requested global navigation target triggers
    val requestedTab = mutableStateOf<String?>(null)
    val requestedSubViewType = mutableStateOf<String?>(null)
    val requestedSubViewName = mutableStateOf<String?>(null)
    val returnToHomeScreenOnDetailBack = mutableStateOf(false)

    val isScanning = mutableStateOf(false)
    val isShuffleActive = mutableStateOf(false)
    var ignoreSavePlaybackState = false

    private val audioScanner = AudioScanner(application)
    val database = AppDatabase.getDatabase(application)
    val audioDao = database.audioDao()
    private val backupManager = com.kevshupp.kevmusicplayer.playback.managers.BackupManager(audioDao)

    val batchLyricsManager = com.kevshupp.kevmusicplayer.playback.managers.BatchLyricsManager(
        audioDao = audioDao,
        localAudioFiles = localAudioFiles,
        coroutineScope = viewModelScope,
        onPlaylistsReloadNeeded = { loadPlaylists() }
    )
    val isDownloadingAllLyrics get() = batchLyricsManager.isDownloadingAllLyrics
    val isDeletingAllLyrics get() = batchLyricsManager.isDeletingAllLyrics
    val downloadAllLyricsCurrent get() = batchLyricsManager.downloadAllLyricsCurrent
    val downloadAllLyricsTotal get() = batchLyricsManager.downloadAllLyricsTotal
    val downloadAllLyricsSuccessCount get() = batchLyricsManager.downloadAllLyricsSuccessCount
    val downloadAllLyricsCurrentName get() = batchLyricsManager.downloadAllLyricsCurrentName

    val storageToolsManager = com.kevshupp.kevmusicplayer.playback.managers.StorageToolsManager(
        application = application,
        localAudioFiles = localAudioFiles,
        coroutineScope = viewModelScope,
        onTriggerScan = { isManual -> scanFiles(isManual = isManual) }
    )

    val audioFilesPagingFlow = Pager(
        config = PagingConfig(
            pageSize = 40,
            prefetchDistance = 20,
            enablePlaceholders = false,
            initialLoadSize = 60
        )
    ) {
        audioDao.getAudioFilesPagingSource()
    }.flow.cachedIn(viewModelScope)

    init {
        // Force jaudiotagger to run in Android mode (bypasses java.awt classes)
        try {
            org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true)
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        viewModelScope.launch(Dispatchers.IO) {
            checkAutoBackup(application)
        }

        // Instantly load cached songs with lightweight metadata from SQLite database (lyrics loaded on demand to minimize startup RAM)
        initialDbLoadJob = viewModelScope.launch {
            try {
                val allFiles = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    audioDao.getAllAudioFilesLightweight()
                }
                if (allFiles.isNotEmpty()) {
                    localAudioFiles.clear()
                    localAudioFiles.addAll(allFiles)
                    loadPlaylists()
                    updateSmartPlaylists()
                }

                // Pre-warm disk thumbnail cache in background for initial batch of songs
                val appCtx = getApplication<android.app.Application>()
                val itemsToPrewarm = localAudioFiles.take(150).map { it.uriString }
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    for (uriStr in itemsToPrewarm) {
                        try {
                            com.kevshupp.kevmusicplayer.ui.screens.preloadAlbumArt(appCtx, uriStr)
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    "Init_Database_Load",
                    "Failed to load cached audio files from Room DB on startup",
                    e
                )
            }
        }
    }

    fun connect() {
        ignoreSavePlaybackState = false
        if (browser.value != null && browser.value?.isConnected == true) {
            if (localAudioFiles.isEmpty()) {
                scanFiles()
            }
            return
        }

        browser.value?.release()
        browserFuture?.let {
            try {
                MediaBrowser.releaseFuture(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        browser.value = null

        val sessionToken = SessionToken(
            getApplication(),
            ComponentName(getApplication(), PlaybackService::class.java)
        )
        
        // Explicitly start the service to ensure it transitions to the started state and doesn't get destroyed on client unbind
        try {
            val startIntent = android.content.Intent(getApplication(), PlaybackService::class.java)
            getApplication<android.app.Application>().startService(startIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val future = MediaBrowser.Builder(getApplication(), sessionToken).buildAsync()
        browserFuture = future
        
        future.addListener({
            try {
                if (future.isCancelled) return@addListener
                val connectedBrowser = future.get()
                
                if (browserFuture != future) {
                    connectedBrowser.release()
                    return@addListener
                }
                
                browser.value = connectedBrowser

                connectedBrowser.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        savePlaybackState()
                        fetchLyricsForCurrentSong()
                        preloadUpcomingArtwork()
                        if (mediaItem != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                            val id = mediaItem.mediaId.toLongOrNull()
                            if (id != null) {
                                incrementSongPlayCount(id)
                            }
                        }
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        savePlaybackState()
                    }
                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        savePlaybackState()
                        preloadUpcomingArtwork()
                    }
                    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                        isShuffleActive.value = shuffleModeEnabled
                        savePlaybackState()
                        preloadUpcomingArtwork()
                    }
                })

                restorePlaybackState()
                refreshStatsFromDb()
                scanFiles()
            } catch (e: Exception) {
                e.printStackTrace()
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    "MediaBrowser_Connect",
                    "Failed to initialize MediaBrowser connection",
                    e
                )
            }
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    fun disconnect() {
        browser.value?.release()
        browserFuture?.let {
            try {
                MediaBrowser.releaseFuture(it)
            } catch (e: Exception) {
                e.printStackTrace()
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    "MediaBrowser_Disconnect",
                    "Failed to release MediaBrowser future",
                    e
                )
            }
        }
        browser.value = null
        browserFuture = null
    }

    private var saveStateJob: kotlinx.coroutines.Job? = null

    fun savePlaybackState() {
        if (ignoreSavePlaybackState) return
        val b = browser.value ?: return
        val currentItem = b.currentMediaItem
        val id = currentItem?.mediaId?.toLongOrNull() ?: -1L
        val position = b.currentPosition
        val activeIndex = b.currentMediaItemIndex
        val shuffleModeEnabled = isShuffleActive.value || b.shuffleModeEnabled
        
        val itemCount = b.mediaItemCount
        val mediaIds = ArrayList<String>(itemCount)
        for (i in 0 until itemCount) {
            val item = b.getMediaItemAt(i)
            mediaIds.add(item.mediaId)
        }

        saveStateJob?.cancel()
        saveStateJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(300) // Debounce rapid playback events
            val prefs = getApplication<Application>().getSharedPreferences("playback_prefs", android.content.Context.MODE_PRIVATE)
            val editor = prefs.edit()
                .putBoolean("last_shuffle_enabled", shuffleModeEnabled)
            if (id != -1L) {
                val mediaIdsString = mediaIds.joinToString(",")
                editor.putLong("last_song_id", id)
                    .putLong("last_position", position)
                    .putInt("last_active_index", activeIndex)
                    .putString("last_queue_ids", mediaIdsString)
            }
            editor.apply()
        }
    }

    fun restorePlaybackState() {
        val b = browser.value ?: return
        if (b.currentMediaItem != null || b.mediaItemCount > 0) return

        val prefs = getApplication<Application>().getSharedPreferences("playback_prefs", android.content.Context.MODE_PRIVATE)
        val lastShuffleEnabled = prefs.getBoolean("last_shuffle_enabled", false)
        isShuffleActive.value = lastShuffleEnabled
        b.shuffleModeEnabled = false

        val lastSongId = prefs.getLong("last_song_id", -1L)
        val lastPosition = prefs.getLong("last_position", 0L)
        val lastActiveIndex = prefs.getInt("last_active_index", 0)
        val lastQueueIdsString = prefs.getString("last_queue_ids", null)

        if (lastSongId != -1L) {
            viewModelScope.launch {
                var attempts = 0
                while (localAudioFiles.isEmpty() && attempts < 15) {
                    kotlinx.coroutines.delay(100)
                    attempts++
                }

                if (localAudioFiles.isEmpty()) return@launch

                // Double check to ensure player hasn't synced/restored its queue during the delay
                if (b.currentMediaItem != null || b.mediaItemCount > 0) return@launch

                val (mediaItems, finalActiveIndex) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val songsMap = localAudioFiles.associateBy { it.id }
                    val items = mutableListOf<MediaItem>()
                    var adjustedActiveIndex = lastActiveIndex
                    
                    if (!lastQueueIdsString.isNullOrEmpty()) {
                        var idStrings = lastQueueIdsString.split(",")
                        if (idStrings.size > 1500) {
                            val start = (lastActiveIndex - 750).coerceAtLeast(0)
                            val end = (start + 1500).coerceAtMost(idStrings.size)
                            val adjustedStart = (end - 1500).coerceAtLeast(0)
                            idStrings = idStrings.subList(adjustedStart, end)
                            adjustedActiveIndex = lastActiveIndex - adjustedStart
                        }
                        idStrings.forEach { idStr ->
                            val idLong = idStr.toLongOrNull()
                            if (idLong != null) {
                                val song = songsMap[idLong]
                                if (song != null) {
                                    val trackUri = Uri.parse(song.uriString)
                                    val mediaItem = MediaItem.Builder()
                                        .setMediaId(song.id.toString())
                                        .setUri(trackUri)
                                        .setRequestMetadata(
                                            MediaItem.RequestMetadata.Builder()
                                                .setMediaUri(trackUri)
                                                .build()
                                        )
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(song.title)
                                                .setArtist(song.artist)
                                                .setAlbumTitle(song.album)
                                                .setIsPlayable(true)
                                                .setIsBrowsable(false)
                                                .build()
                                        )
                                        .build()
                                    items.add(mediaItem)
                                }
                            }
                        }
                    }

                    if (items.isEmpty()) {
                        val song = songsMap[lastSongId]
                        if (song != null) {
                            val trackUri = Uri.parse(song.uriString)
                            val mediaItem = MediaItem.Builder()
                                .setMediaId(song.id.toString())
                                .setUri(trackUri)
                                .setRequestMetadata(
                                    MediaItem.RequestMetadata.Builder()
                                        .setMediaUri(trackUri)
                                        .build()
                                )
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(song.title)
                                        .setArtist(song.artist)
                                        .setAlbumTitle(song.album)
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .build()
                                )
                                .build()
                            items.add(mediaItem)
                            adjustedActiveIndex = 0
                        }
                    }
                    items to adjustedActiveIndex
                }

                if (mediaItems.isNotEmpty()) {
                    b.setMediaItems(mediaItems)
                    val safeIndex = finalActiveIndex.coerceIn(0, mediaItems.size - 1)
                    b.seekTo(safeIndex, lastPosition)
                    b.prepare()
                }
            }
        }
    }

    fun preloadUpcomingArtwork() {
        val b = browser.value ?: return
        val context = getApplication<Application>()
        val prefs = context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)
        val preloadCount = prefs.getInt("preload_art_count", 5)
        if (preloadCount <= 0) return

        val totalItems = b.mediaItemCount
        if (totalItems <= 0) return
        val currentIndex = b.currentMediaItemIndex

        val urisToPreload = mutableSetOf<String>()
        for (i in 0..preloadCount) {
            val nextIndex = (currentIndex + i) % totalItems
            val prevIndex = (currentIndex - i + totalItems) % totalItems

            for (targetIndex in listOf(nextIndex, prevIndex)) {
                if (targetIndex in 0 until totalItems) {
                    try {
                        val mediaItem = b.getMediaItemAt(targetIndex)
                        val mediaId = mediaItem.mediaId
                        if (mediaId.isNotBlank()) {
                            urisToPreload.add("content://media/external/audio/media/$mediaId")
                            val audioFile = localAudioFiles.find { it.id.toString() == mediaId }
                            if (audioFile != null && audioFile.uriString.isNotBlank()) {
                                urisToPreload.add(audioFile.uriString)
                            }
                        }
                        val reqUri = mediaItem.requestMetadata.mediaUri?.toString()
                        if (!reqUri.isNullOrBlank()) {
                            urisToPreload.add(reqUri)
                        }
                        val localUri = mediaItem.localConfiguration?.uri?.toString()
                        if (!localUri.isNullOrBlank()) {
                            urisToPreload.add(localUri)
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
        }

        if (urisToPreload.isEmpty()) return

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (uriString in urisToPreload) {
                try {
                    com.kevshupp.kevmusicplayer.ui.screens.preloadAlbumArt(context, uriString)
                } catch (e: Exception) {
                    // Ignore errors during preload
                }
            }
        }
    }

    fun fetchLyricsForCurrentSong() {
        val b = browser.value ?: return
        val currentItem = b.currentMediaItem ?: return
        val id = currentItem.mediaId.toLongOrNull() ?: return
        val context = getApplication<android.app.Application>()

        viewModelScope.launch {
            try {
                var attempts = 0
                while (localAudioFiles.isEmpty() && attempts < 15) {
                    kotlinx.coroutines.delay(100)
                    attempts++
                }
                val song = localAudioFiles.find { it.id == id } ?: return@launch
                
                // 0. If lyrics or translated lyrics are not yet loaded in RAM, load them from SQLite DB
                var currentSongLyrics = song.lyrics
                var currentSongTranslation = song.translatedLyrics
                if (currentSongLyrics == null || currentSongTranslation == null) {
                    val (dbLyrics, dbTranslation) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val l = if (currentSongLyrics == null) audioDao.getLyricsById(id) else currentSongLyrics
                        val t = if (currentSongTranslation == null) audioDao.getTranslatedLyricsById(id) else currentSongTranslation
                        l to t
                    }
                    if (dbLyrics != currentSongLyrics || dbTranslation != currentSongTranslation) {
                        currentSongLyrics = dbLyrics
                        currentSongTranslation = dbTranslation
                        val index = localAudioFiles.indexOfFirst { it.id == id }
                        if (index != -1) {
                            localAudioFiles[index] = localAudioFiles[index].copy(
                                lyrics = dbLyrics,
                                translatedLyrics = dbTranslation
                            )
                        }
                    }
                }

                // 1. Check local/embedded sources first (LRC file next to it or embedded tag) on Dispatchers.IO
                val localLyrics = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    readLocalLrcOrEmbedded(context, song)
                }
                val localTranslation = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    readLocalTranslatedLrcOrEmbedded(context, song)
                }
                if (!localTranslation.isNullOrBlank() && currentSongTranslation != localTranslation) {
                    updateSongTranslatedLyrics(id, localTranslation)
                    currentSongTranslation = localTranslation
                }

                if (!localLyrics.isNullOrBlank()) {
                    val localIsSynced = LyricsRepository.isLrcSynced(localLyrics)
                    val dbIsSynced = LyricsRepository.isLrcSynced(currentSongLyrics)
                    
                    // If local is synced, or if DB is empty, use local lyrics!
                    if (localIsSynced || (currentSongLyrics.isNullOrBlank() && !localIsSynced)) {
                        if (currentSongLyrics != localLyrics) {
                            updateSongLyrics(id, localLyrics)
                        }
                        if (localIsSynced) {
                            return@launch // Synchronized lyrics successfully loaded, done!
                        }
                    }
                }

                // 2. If currently stored DB lyrics are already synchronized, do not fetch online
                if (LyricsRepository.isLrcSynced(currentSongLyrics)) {
                    return@launch
                }

                // 3. If currently stored/local lyrics are either blank or unsynced, fetch from LrcLib!
                val fetched = LyricsRepository.fetchLyricsFromLrcLib(song.artist, song.title)
                if (!fetched.isNullOrEmpty()) {
                    val fetchedIsSynced = LyricsRepository.isLrcSynced(fetched)
                    // If fetched lyrics are synced, or we currently have nothing, update!
                    if (fetchedIsSynced || song.lyrics.isNullOrBlank()) {
                        updateSongLyrics(id, fetched)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    getApplication<android.app.Application>(),
                    "BackgroundLyricsFetch",
                    "Failed to fetch lyrics in background for songId $id",
                    e
                )
            }
        }
    }

    fun scanFiles(isManual: Boolean = false) {
        if (isScanning.value) return
        isScanning.value = true
        viewModelScope.launch {
            try {
                initialDbLoadJob?.join()
                val updatedFilesList = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // 0. Fetch existing cached entities first to bypass slow disk I/O on unchanged files
                    val existingEntities = audioDao.getAllAudioFiles().associateBy { it.id }

                    // 1. Perform background MediaStore scanning, passing the existing cache map
                    val scannedFiles = audioScanner.scanAudioFiles(existingEntities)
                    
                    if (scannedFiles == null) {
                        return@withContext null
                    }

                    // Exclude folders filtering
                    val prefs = getApplication<android.app.Application>().getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)
                    val excludedJson = prefs.getString("excluded_folders", "[]") ?: "[]"
                    val excludedFolders = try {
                        val jsonArray = org.json.JSONArray(excludedJson)
                        (0 until jsonArray.length()).map { jsonArray.getString(it) }
                    } catch (e: Exception) {
                        com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                            "Excluded_Folders_Parse",
                            "Failed to parse excluded folders JSON",
                            e
                        )
                        emptyList<String>()
                    }

                    val selectedMusicFolder = prefs.getString("music_folder_path", null)

                    val filteredScanned = scannedFiles.filter { file ->
                        val isInsideSelected = if (selectedMusicFolder != null) {
                            file.folderPath.startsWith(selectedMusicFolder)
                        } else {
                            true
                        }
                        isInsideSelected && excludedFolders.none { excluded ->
                            file.folderPath.equals(excluded, ignoreCase = true) || file.folderPath.startsWith(excluded + "/", ignoreCase = true)
                        }
                    }
                    
                    // Note: We use localAudioFiles.isEmpty() check. To read it safely on IO, we can pass its size or empty status
                    val isLocalEmpty = localAudioFiles.isEmpty()
                    if (filteredScanned.isEmpty() && !isManual && !isLocalEmpty) {
                        // Automatic scan returned empty, but we already have songs cached in memory.
                        // Keep the cache intact and display the saved songs so the user is never met with an empty screen!
                        return@withContext null
                    }
                    
                    // 2. Map scanned files to database entities, preserving custom edited lyrics and metadata
                    val entities = filteredScanned.map { file ->
                        val existing = existingEntities[file.id] ?: existingEntities.values.find {
                            it.title.trim().equals(file.title.trim(), ignoreCase = true) &&
                            (it.artist.trim().equals(file.artist.trim(), ignoreCase = true) || it.artist.isBlank() || file.artist.isBlank()) &&
                            Math.abs(it.duration - file.duration) < 4000
                        }
                        if (existing != null && existing.dateModified == file.dateModified) {
                            // File has not been modified on disk. Preserve user's database metadata edits!
                            file.copy(
                                title = existing.title,
                                artist = existing.artist,
                                album = existing.album,
                                genre = existing.genre,
                                year = existing.year,
                                lyrics = existing.lyrics,
                                translatedLyrics = existing.translatedLyrics,
                                playCount = existing.playCount,
                                lastPlayed = existing.lastPlayed,
                                replayGain = existing.replayGain,
                                track = existing.track
                            )
                        } else if (existing != null) {
                            // File HAS been modified on disk. Read physical tags directly!
                            var finalTitle = file.title
                            var finalArtist = file.artist
                            var finalAlbum = file.album
                            var finalGenre = file.genre
                            var finalYear = file.year
                            var finalTrack = file.track
                            try {
                                val path = getPhysicalPath(getApplication(), file.id, file.uriString)
                                if (!path.isNullOrBlank()) {
                                    val f = File(path)
                                    if (f.exists() && f.isFile) {
                                        val audioFile = safeReadAudioFile(f)
                                        val tag = audioFile.tag
                                        if (tag != null) {
                                            val t = tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)
                                            if (!t.isNullOrBlank()) finalTitle = t
                                            val a = tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST)
                                            if (!a.isNullOrBlank()) finalArtist = a
                                            val al = tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM)
                                            if (!al.isNullOrBlank()) finalAlbum = al
                                            val g = tag.getFirst(org.jaudiotagger.tag.FieldKey.GENRE)
                                            if (!g.isNullOrBlank()) finalGenre = g
                                            val y = tag.getFirst(org.jaudiotagger.tag.FieldKey.YEAR)
                                            if (!y.isNullOrBlank()) finalYear = y
                                            val tr = tag.getFirst(org.jaudiotagger.tag.FieldKey.TRACK)
                                            val parsedTrack = tr?.substringBefore('/')?.toIntOrNull() ?: 0
                                            if (parsedTrack > 0) finalTrack = parsedTrack
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            file.copy(
                                title = finalTitle,
                                artist = finalArtist,
                                album = finalAlbum,
                                genre = finalGenre,
                                year = finalYear,
                                lyrics = existing.lyrics,
                                translatedLyrics = existing.translatedLyrics,
                                playCount = existing.playCount,
                                lastPlayed = existing.lastPlayed,
                                replayGain = existing.replayGain,
                                track = finalTrack
                            )
                        } else {
                            // New file from MediaStore (fast indexed metadata)
                            file
                        }
                    }
                    
                    // 3. Write to Room database (single transaction cache sync)
                    // Clean up old files that were deleted from device storage
                    val scannedIds = filteredScanned.map { it.id }
                    if (scannedIds.isNotEmpty()) {
                        audioDao.keepOnlyIds(scannedIds)
                        audioDao.insertAll(entities)
                    } else {
                        audioDao.deleteAll()
                    }
                    
                    entities
                }

                if (updatedFilesList != null) {
                    if (localAudioFiles != updatedFilesList) {
                        localAudioFiles.clear()
                        localAudioFiles.addAll(updatedFilesList)
                    }
                    loadPlaylists()
                    updateSmartPlaylists()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    getApplication<android.app.Application>(),
                    "LibraryScan",
                    "Scan failed",
                    e
                )
            } finally {
                isScanning.value = false
            }
        }
    }

    fun forceDeepStorageScan(context: android.content.Context, onComplete: (Int) -> Unit) =
        storageToolsManager.forceDeepStorageScan(context, { isScanning.value = it }, onComplete)

    fun playFile(file: AudioFile, customQueue: List<AudioFile>? = null) {
        val b = browser.value ?: return

        // Ensure the service is started
        try {
            val startIntent = android.content.Intent(getApplication(), PlaybackService::class.java)
            getApplication<android.app.Application>().startService(startIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val fullQueue = customQueue ?: localAudioFiles
        queueManager.setOriginalQueue(fullQueue.toList())
        val fullIndex = fullQueue.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
        
        // Limit queue size to 1500 items to avoid IPC TransactionTooLargeException
        val queueLimit = 1500
        val (queue, index) = if (fullQueue.size > queueLimit) {
            val half = queueLimit / 2
            val start = (fullIndex - half).coerceAtLeast(0)
            val end = (start + queueLimit).coerceAtMost(fullQueue.size)
            val adjustedStart = (end - queueLimit).coerceAtLeast(0)
            fullQueue.subList(adjustedStart, end) to (fullIndex - adjustedStart)
        } else {
            fullQueue to fullIndex
        }

        val mediaItems = queue.map { audioFile ->
            val trackUri = Uri.parse(audioFile.uriString)
            MediaItem.Builder()
                .setMediaId(audioFile.id.toString())
                .setUri(trackUri)
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setMediaUri(trackUri)
                        .build()
                )
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(audioFile.title)
                        .setArtist(audioFile.artist)
                        .setAlbumTitle(audioFile.album)
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build()
                )
                .build()
        }

        isShuffleActive.value = false
        b.shuffleModeEnabled = false
        b.setMediaItems(mediaItems, index, 0L)
        b.prepare()
        b.play()
        preloadUpcomingArtwork()
    }

    fun shuffleAll(customQueue: List<AudioFile>? = null, startSong: AudioFile? = null): AudioFile? {
        val b = browser.value ?: return null

        try {
            val startIntent = android.content.Intent(getApplication(), PlaybackService::class.java)
            getApplication<android.app.Application>().startService(startIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val fullQueue = (customQueue ?: localAudioFiles).toList()
        if (fullQueue.isEmpty()) return null
        queueManager.setOriginalQueue(fullQueue)

        val random = kotlin.random.Random(System.nanoTime())
        val shuffledList = if (startSong != null) {
            val remaining = fullQueue.filter { it.id != startSong.id }.shuffled(random)
            listOf(startSong) + remaining
        } else {
            fullQueue.shuffled(random)
        }

        val queueLimit = 1500
        val queue = if (shuffledList.size > queueLimit) {
            shuffledList.take(queueLimit)
        } else {
            shuffledList
        }

        val mediaItems = queue.map { audioFile ->
            val trackUri = Uri.parse(audioFile.uriString)
            MediaItem.Builder()
                .setMediaId(audioFile.id.toString())
                .setUri(trackUri)
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setMediaUri(trackUri)
                        .build()
                )
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(audioFile.title)
                        .setArtist(audioFile.artist)
                        .setAlbumTitle(audioFile.album)
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build()
                )
                .build()
        }

        isShuffleActive.value = true
        b.shuffleModeEnabled = false
        b.setMediaItems(mediaItems, 0, 0L)
        b.prepare()
        b.play()
        preloadUpcomingArtwork()
        return queue.firstOrNull()
    }

    fun toggleShuffle() {
        val b = browser.value ?: return
        val newShuffle = !isShuffleActive.value
        isShuffleActive.value = newShuffle
        if (newShuffle) {
            queueManager.shuffleUpcomingQueue()
        } else {
            queueManager.restoreUnshuffledQueue()
        }
        savePlaybackState()
    }

    fun updateSongLyrics(id: Long, newLyrics: String?) {
        viewModelScope.launch {
            try {
                audioDao.updateLyrics(id, newLyrics)
                audioDao.updateTranslatedLyrics(id, null)
                val index = localAudioFiles.indexOfFirst { it.id == id }
                if (index != -1) {
                    val file = localAudioFiles[index]
                    localAudioFiles[index] = file.copy(lyrics = newLyrics, translatedLyrics = null)
                    
                    // Physically write lyrics inside the song metadata and LRC file next to it!
                    if (!newLyrics.isNullOrBlank()) {
                        withContext(Dispatchers.IO) {
                            saveLyricsPhysical(getApplication(), id, file.title, file.folderPath, newLyrics)
                        }
                    }
                }
                loadPlaylists() // Sync playlists cache
                triggerAutoSync()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun downloadAllLyrics(context: android.content.Context) = batchLyricsManager.downloadAllLyrics(context)

    fun cancelDownloadAllLyrics() = batchLyricsManager.cancelDownloadAllLyrics()

    fun deleteSongTranslatedLyrics(id: Long) {
        viewModelScope.launch {
            try {
                audioDao.updateTranslatedLyrics(id, null)
                val index = localAudioFiles.indexOfFirst { it.id == id }
                if (index != -1) {
                    val file = localAudioFiles[index]
                    localAudioFiles[index] = file.copy(translatedLyrics = null)
                }
                loadPlaylists() // Sync playlists cache
                triggerAutoSync()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateSongTranslatedLyrics(id: Long, newTranslatedLyrics: String?) {
        viewModelScope.launch {
            try {
                audioDao.updateTranslatedLyrics(id, newTranslatedLyrics)
                val index = localAudioFiles.indexOfFirst { it.id == id }
                if (index != -1) {
                    val file = localAudioFiles[index]
                    localAudioFiles[index] = file.copy(translatedLyrics = newTranslatedLyrics)
                    
                    if (!newTranslatedLyrics.isNullOrBlank()) {
                        withContext(Dispatchers.IO) {
                            saveTranslatedLyricsPhysical(getApplication(), id, file.title, file.folderPath, newTranslatedLyrics)
                        }
                    }
                }
                loadPlaylists() // Sync playlists cache
                triggerAutoSync()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun exportBackup(
        context: Context,
        outputStream: OutputStream,
        includeSettings: Boolean = true,
        includeEqualizer: Boolean = true,
        includePlaylists: Boolean = true,
        includeLyrics: Boolean = true,
        includeStatistics: Boolean = true,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                backupManager.exportBackup(
                    context = context,
                    outputStream = outputStream,
                    includeSettings = includeSettings,
                    includeEqualizer = includeEqualizer,
                    includePlaylists = includePlaylists,
                    includeLyrics = includeLyrics,
                    includeStatistics = includeStatistics
                )
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    "Backup_Export",
                    "Failed to export settings/playlists backup JSON",
                    e
                )
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    fun importBackup(
        context: Context,
        inputStream: InputStream,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = backupManager.importBackup(context, inputStream)
                val updatedFiles = audioDao.getAllAudioFiles()

                withContext(Dispatchers.Main) {
                    if (result.importedLanguage != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        try {
                            val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
                            localeManager?.applicationLocales = android.os.LocaleList.forLanguageTags(result.importedLanguage)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    if (result.restoredTabs != null) {
                        enabledTabs.value = result.restoredTabs
                    }
                    localAudioFiles.clear()
                    localAudioFiles.addAll(updatedFiles)
                    loadPlaylists() // Reload playlists state instantly!
                    updateSmartPlaylists() // Reload smart playlists state instantly!

                    ignoreSavePlaybackState = true

                    // IMPORTANTE: Limpiar preferencias de sesión de audio para evitar que el servicio 
                    // restaure una cola con IDs obsoletos o inconsistentes tras la restauración.
                    val playbackPrefs = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
                    playbackPrefs.edit()
                        .remove("last_song_id")
                        .remove("last_queue_ids")
                        .remove("last_active_index")
                        .apply()

                    // Safely clear previous playback items without tearing down the browser connection
                    try {
                        browser.value?.stop()
                        browser.value?.clearMediaItems()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    if (browser.value == null) {
                        connect()
                    }

                    onSuccess()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    "Backup_Import",
                    "Failed to import/restore settings/playlists backup JSON",
                    e
                )
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    val cloudUser = com.kevshupp.kevmusicplayer.data.cloud.CloudAuthManager.currentUser

    fun getGoogleSignInIntent(context: Context): Intent =
        com.kevshupp.kevmusicplayer.data.cloud.CloudAuthManager.getGoogleSignInIntent(context)

    fun handleGoogleSignInResult(
        context: Context,
        intent: Intent?,
        onSuccess: (com.kevshupp.kevmusicplayer.data.cloud.CloudUser) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val result = com.kevshupp.kevmusicplayer.data.cloud.CloudAuthManager.handleGoogleSignInResult(context, intent)
            result.onSuccess { user ->
                withContext(Dispatchers.Main) { onSuccess(user) }
            }.onFailure { err ->
                withContext(Dispatchers.Main) { onError(err.localizedMessage ?: "Error al iniciar sesión") }
            }
        }
    }

    fun updateCustomUserPhoto(context: Context, photoUri: String?) {
        com.kevshupp.kevmusicplayer.data.cloud.CloudAuthManager.updateCustomPhoto(context, photoUri)
    }

    fun signOutGoogle(context: Context) {
        viewModelScope.launch {
            com.kevshupp.kevmusicplayer.data.cloud.CloudAuthManager.signOut(context)
        }
    }

    private var autoSyncJob: kotlinx.coroutines.Job? = null

    fun triggerAutoSync(
        debounceMs: Long = 4000L,
        trigger: com.kevshupp.kevmusicplayer.data.cloud.AutoSyncTrigger = com.kevshupp.kevmusicplayer.data.cloud.AutoSyncTrigger.REALTIME
    ) {
        val user = cloudUser.value ?: return
        if (!user.isAutoSyncEnabled) return

        // Validate trigger against configured autoSyncMode
        when (trigger) {
            com.kevshupp.kevmusicplayer.data.cloud.AutoSyncTrigger.REALTIME -> {
                if (user.autoSyncMode != "realtime") return
            }
            com.kevshupp.kevmusicplayer.data.cloud.AutoSyncTrigger.ON_EXIT -> {
                if (user.autoSyncMode == "manual") return
            }
            com.kevshupp.kevmusicplayer.data.cloud.AutoSyncTrigger.PERIODIC -> {
                if (user.autoSyncMode != "daily" && user.autoSyncMode != "realtime") return
            }
        }

        // Wi-Fi only check
        if (user.syncWifiOnly && !com.kevshupp.kevmusicplayer.data.cloud.CloudAuthManager.isWifiConnected(getApplication())) {
            return
        }

        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch(Dispatchers.IO) {
            if (debounceMs > 0) {
                kotlinx.coroutines.delay(debounceMs)
            }
            try {
                val cloudBackupMgr = com.kevshupp.kevmusicplayer.data.cloud.CloudBackupManager(getApplication())
                cloudBackupMgr.uploadBackupToCloud(user)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setCloudAutoSync(context: Context, enabled: Boolean) {
        com.kevshupp.kevmusicplayer.data.cloud.CloudAuthManager.setAutoSyncEnabled(context, enabled)
        if (enabled) {
            triggerAutoSync(debounceMs = 0L)
        }
    }

    fun setCloudAutoSyncMode(context: Context, mode: String) {
        com.kevshupp.kevmusicplayer.data.cloud.CloudAuthManager.setAutoSyncMode(context, mode)
    }

    fun setCloudSyncWifiOnly(context: Context, wifiOnly: Boolean) {
        com.kevshupp.kevmusicplayer.data.cloud.CloudAuthManager.setSyncWifiOnly(context, wifiOnly)
    }

    fun setCloudSyncIncludePlaylists(context: Context, include: Boolean) {
        com.kevshupp.kevmusicplayer.data.cloud.CloudAuthManager.setIncludePlaylists(context, include)
    }

    fun setCloudSyncIncludeLyrics(context: Context, include: Boolean) {
        com.kevshupp.kevmusicplayer.data.cloud.CloudAuthManager.setIncludeLyrics(context, include)
    }

    fun setCloudSyncIncludeStats(context: Context, include: Boolean) {
        com.kevshupp.kevmusicplayer.data.cloud.CloudAuthManager.setIncludeStats(context, include)
    }

    fun setCloudSyncIncludeSettings(context: Context, include: Boolean) {
        com.kevshupp.kevmusicplayer.data.cloud.CloudAuthManager.setIncludeSettings(context, include)
    }

    fun uploadCloudBackup(
        context: Context,
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        val user = cloudUser.value ?: run {
            onError("Debes iniciar sesión con Google primero")
            return
        }
        viewModelScope.launch {
            val cloudBackupMgr = com.kevshupp.kevmusicplayer.data.cloud.CloudBackupManager(context)
            val result = cloudBackupMgr.uploadBackupToCloud(user)
            result.onSuccess { timestamp ->
                withContext(Dispatchers.Main) { onSuccess(timestamp) }
            }.onFailure { err ->
                withContext(Dispatchers.Main) { onError(err.localizedMessage ?: "Error al subir copia de seguridad") }
            }
        }
    }

    fun restoreCloudBackup(
        context: Context,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val user = cloudUser.value ?: run {
            onError("Debes iniciar sesión con Google primero")
            return
        }
        viewModelScope.launch {
            val cloudBackupMgr = com.kevshupp.kevmusicplayer.data.cloud.CloudBackupManager(context)
            val result = cloudBackupMgr.restoreBackupFromCloud(user)
            result.onSuccess { importResult ->
                val updatedFiles = audioDao.getAllAudioFiles()
                withContext(Dispatchers.Main) {
                    if (importResult.importedLanguage != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        try {
                            val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
                            localeManager?.applicationLocales = android.os.LocaleList.forLanguageTags(importResult.importedLanguage)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    if (importResult.restoredTabs != null) {
                        enabledTabs.value = importResult.restoredTabs
                    }
                    localAudioFiles.clear()
                    localAudioFiles.addAll(updatedFiles)
                    loadPlaylists()
                    updateSmartPlaylists()

                    ignoreSavePlaybackState = true
                    val playbackPrefs = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
                    playbackPrefs.edit()
                        .remove("last_song_id")
                        .remove("last_queue_ids")
                        .remove("last_active_index")
                        .apply()

                    // Safely clear previous playback items without tearing down the browser connection
                    try {
                        browser.value?.stop()
                        browser.value?.clearMediaItems()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    if (browser.value == null) {
                        connect()
                    }

                    onSuccess()
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) { onError(err.localizedMessage ?: "Error al restaurar copia") }
            }
        }
    }

    fun getLatestCloudBackupInfo(
        context: Context,
        onResult: (com.kevshupp.kevmusicplayer.data.cloud.CloudBackupMetadata?) -> Unit
    ) {
        val user = cloudUser.value ?: run {
            onResult(null)
            return
        }
        viewModelScope.launch {
            val cloudBackupMgr = com.kevshupp.kevmusicplayer.data.cloud.CloudBackupManager(context)
            val result = cloudBackupMgr.getLatestCloudBackupInfo(user)
            withContext(Dispatchers.Main) {
                onResult(result.getOrNull())
            }
        }
    }

    fun checkAutoBackup(context: Context) {
        backupManager.checkAutoBackup(context) { outputStream, onComplete, onErr ->
            exportBackup(
                context = context,
                outputStream = outputStream,
                includeSettings = true,
                includeEqualizer = true,
                includePlaylists = true,
                includeLyrics = true,
                includeStatistics = true,
                onSuccess = {
                    onComplete()
                    val user = cloudUser.value
                    if (user != null && user.isAutoSyncEnabled) {
                        uploadCloudBackup(context, onSuccess = {}, onError = {})
                    }
                },
                onError = onErr
            )
        }
    }

    fun updateSortBy(newSortBy: String) {
        sortBy.value = newSortBy
    }

    fun deleteAllLyrics(context: Context, onComplete: () -> Unit) =
        batchLyricsManager.deleteAllLyrics(context, onComplete)

    fun organizeMusicByArtistFolder(
        context: Context,
        onProgress: (current: Int, total: Int, currentName: String) -> Unit,
        onComplete: (successCount: Int, errorCount: Int) -> Unit
    ) = storageToolsManager.organizeMusicByArtistFolder(context, onProgress, onComplete)

    fun deleteAllFolderCoverImages(
        context: Context,
        onProgress: (current: Int, total: Int) -> Unit,
        onComplete: (deletedCount: Int) -> Unit
    ) = storageToolsManager.deleteAllFolderCoverImages(context, onProgress, onComplete)

    fun deleteAllNoMediaFiles(
        context: Context,
        onProgress: (current: Int, total: Int) -> Unit,
        onComplete: (deletedCount: Int) -> Unit
    ) = storageToolsManager.deleteAllNoMediaFiles(context, onProgress, onComplete)

    fun deleteAllLyricsFiles(
        context: Context,
        onProgress: (current: Int, total: Int) -> Unit,
        onComplete: (deletedCount: Int) -> Unit
    ) = storageToolsManager.deleteAllLyricsFiles(context, onProgress, onComplete)

    fun updateEnabledTabs(tabs: List<String>) {
        enabledTabs.value = tabs
        val prefs = getApplication<Application>().getSharedPreferences("playback_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("enabled_tabs", tabs.joinToString(","))
            .apply()
        triggerAutoSync()
    }

    fun incrementSongPlayCount(id: Long) {
        val now = System.currentTimeMillis()
        val index = localAudioFiles.indexOfFirst { it.id == id }
        if (index != -1) {
            val currentSong = localAudioFiles[index]
            localAudioFiles[index] = currentSong.copy(
                playCount = currentSong.playCount + 1,
                lastPlayed = now
            )
        }
        
        playlists.keys.toList().forEach { playlistName ->
            val list = playlists[playlistName] ?: emptyList()
            val pIndex = list.indexOfFirst { it.id == id }
            if (pIndex != -1) {
                val newList = list.toMutableList()
                newList[pIndex] = newList[pIndex].copy(
                    playCount = newList[pIndex].playCount + 1,
                    lastPlayed = now
                )
                playlists[playlistName] = newList
            }
        }

        updateSmartPlaylists()
        triggerAutoSync(debounceMs = 30000L)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                audioDao.incrementPlayCount(id, now)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun refreshStatsFromDb() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lightweight = audioDao.getAllAudioFilesLightweight()
                if (lightweight.isEmpty()) return@launch
                val dbMap = lightweight.associateBy { it.id }
                withContext(Dispatchers.Main) {
                    var hasChanges = false
                    for (i in localAudioFiles.indices) {
                        val current = localAudioFiles[i]
                        val dbEntry = dbMap[current.id]
                        if (dbEntry != null && (dbEntry.playCount != current.playCount || dbEntry.lastPlayed != current.lastPlayed)) {
                            localAudioFiles[i] = current.copy(
                                playCount = dbEntry.playCount,
                                lastPlayed = dbEntry.lastPlayed
                            )
                            hasChanges = true
                        }
                    }
                    if (hasChanges) {
                        updateSmartPlaylists()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Playlists system
    val playlistManager = com.kevshupp.kevmusicplayer.playback.managers.PlaylistManager(application, localAudioFiles, viewModelScope)
    val playlists get() = playlistManager.playlists
    val playlistCovers get() = playlistManager.playlistCovers
    val smartPlaylists get() = playlistManager.smartPlaylists
    val smartPlaylistConfigs get() = playlistManager.smartPlaylistConfigs

    fun updateSmartPlaylists() = playlistManager.updateSmartPlaylists()
    fun loadPlaylists() = playlistManager.loadPlaylists()
    fun createPlaylist(name: String) {
        playlistManager.createPlaylist(name)
        triggerAutoSync()
    }
    fun createSmartPlaylist(name: String, rule: SmartPlaylistRule, limit: Int, isAdvanced: Boolean = false, advancedRule: SmartRuleNode? = null) {
        playlistManager.createSmartPlaylist(name, rule, limit, isAdvanced, advancedRule)
        triggerAutoSync()
    }
    fun setPlaylistCover(name: String, imageUriStr: String) {
        playlistManager.setPlaylistCover(name, imageUriStr)
        triggerAutoSync()
    }
    fun addSongToPlaylist(playlistName: String, songId: Long) {
        playlistManager.addSongToPlaylist(playlistName, songId)
        triggerAutoSync()
    }
    fun addSongsToPlaylist(playlistName: String, songIds: List<Long>) {
        playlistManager.addSongsToPlaylist(playlistName, songIds)
        triggerAutoSync()
    }
    fun removeSongFromPlaylist(playlistName: String, songId: Long) {
        playlistManager.removeSongFromPlaylist(playlistName, songId)
        triggerAutoSync()
    }
    fun deletePlaylist(name: String) {
        playlistManager.deletePlaylist(name)
        triggerAutoSync()
    }

    // Queue system
    val queueManager = com.kevshupp.kevmusicplayer.playback.managers.QueueManager(browser, localAudioFiles) { savePlaybackState() }

    fun addToQueue(file: AudioFile) = queueManager.addToQueue(file)
    fun playNext(file: AudioFile) = queueManager.playNext(file)
    fun getPlayerQueue(): List<AudioFile> = queueManager.getPlayerQueue()
    fun removeFromQueue(index: Int) = queueManager.removeFromQueue(index)
    fun clearQueue() = queueManager.clearQueue()
    fun shuffleUpcomingQueue() = queueManager.shuffleUpcomingQueue()
    fun restoreUnshuffledQueue() = queueManager.restoreUnshuffledQueue()

    fun deleteSong(context: android.content.Context, songId: Long) {
        viewModelScope.launch {
            try {
                val song = localAudioFiles.find { it.id == songId } ?: return@launch
                
                val b = browser.value
                if (b != null && b.currentMediaItem?.mediaId == songId.toString()) {
                    if (b.hasNextMediaItem()) {
                        b.seekToNext()
                    } else if (b.hasPreviousMediaItem()) {
                        b.seekToPrevious()
                    } else {
                        b.stop()
                    }
                }
                
                // 1. Physically delete from storage first if it's a direct file path or resolvable MediaStore DATA path
                try {
                    val uri = Uri.parse(song.uriString)
                    val path = if (uri.scheme == "file") {
                        uri.path
                    } else if (uri.scheme == "content") {
                        val projection = arrayOf(android.provider.MediaStore.Audio.Media.DATA)
                        val cursor = context.contentResolver.query(uri, projection, null, null, null)
                        val dataPath = cursor?.use {
                            if (it.moveToFirst()) {
                                val columnIndex = it.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                                it.getString(columnIndex)
                            } else null
                        }
                        dataPath
                    } else {
                        song.uriString
                    }
                    
                    if (!path.isNullOrEmpty()) {
                        val file = java.io.File(path)
                        if (file.exists()) {
                            val deletedFile = file.delete()
                            android.util.Log.d("MediaBrowserViewModel", "Physical file deletion at $path: $deletedFile")
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 2. Delete using ContentResolver
                try {
                    val uri = Uri.parse(song.uriString)
                    context.contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                audioDao.deleteById(songId)
                localAudioFiles.removeAll { it.id == songId }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val tagEditorManager = com.kevshupp.kevmusicplayer.playback.managers.TagEditorManager(
        application = application,
        audioDao = audioDao,
        localAudioFiles = localAudioFiles,
        playlists = playlists,
        browser = browser,
        coroutineScope = viewModelScope,
        onUpdateSmartPlaylists = { updateSmartPlaylists() },
        onTriggerScan = { isManual -> scanFiles(isManual = isManual) }
    )

    fun updateSongMetadata(
        context: Context,
        songId: Long,
        title: String,
        artist: String,
        album: String,
        genre: String,
        coverBytes: ByteArray? = null,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) = tagEditorManager.updateSongMetadata(
        context, songId, title, artist, album, genre, coverBytes,
        onSuccess = {
            triggerAutoSync()
            onSuccess()
        },
        onError = onError
    )

    fun updateAlbumCover(
        context: Context,
        albumName: String,
        coverBytes: ByteArray,
        targetSongIds: List<Long>? = null,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) = tagEditorManager.updateAlbumCover(
        context, albumName, coverBytes, targetSongIds,
        onSuccess = {
            triggerAutoSync()
            onSuccess()
        },
        onError = onError
    )

    fun updateAlbumMetadata(
        context: Context,
        oldAlbumName: String,
        songs: List<AudioFile>,
        newAlbumName: String,
        newArtist: String,
        onSuccess: () -> Unit
    ) = tagEditorManager.updateAlbumMetadata(
        context, oldAlbumName, songs, newAlbumName, newArtist,
        onSuccess = {
            triggerAutoSync()
            onSuccess()
        }
    )

    fun updateAlbumMetadata(
        context: Context,
        oldAlbumName: String,
        newAlbumName: String,
        newArtist: String,
        coverBytes: ByteArray? = null,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) = tagEditorManager.updateAlbumMetadata(
        context, oldAlbumName, newAlbumName, newArtist, coverBytes,
        onSuccess = {
            triggerAutoSync()
            onSuccess()
        },
        onError = onError
    )

    fun renameSongFilesToMetadata(
        context: Context,
        onProgress: (current: Int, total: Int, currentName: String) -> Unit,
        onComplete: (successCount: Int, errorCount: Int) -> Unit
    ) = tagEditorManager.renameSongFilesToMetadata(context, onProgress, onComplete)

    fun getExcludedFolders(): List<String> {
        val prefs = getApplication<android.app.Application>().getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)
        val json = prefs.getString("excluded_folders", "[]") ?: "[]"
        return try {
            val jsonArray = org.json.JSONArray(json)
            (0 until jsonArray.length()).map { jsonArray.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setExcludedFolders(folders: List<String>) {
        val prefs = getApplication<android.app.Application>().getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)
        val jsonArray = org.json.JSONArray()
        folders.forEach { jsonArray.put(it) }
        prefs.edit().putString("excluded_folders", jsonArray.toString()).apply()
        // Force manual scan to apply exclusions instantly!
        scanFiles(isManual = true)
    }

    fun getAllDeviceFolders(context: Context): List<String> {
        val list = mutableListOf<String>()
        val settingsPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        val selectedMusicFolder = settingsPrefs.getString("music_folder_path", null)

        val projection = arrayOf(android.provider.MediaStore.Audio.Media.DATA)
        try {
            context.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val dataPath = cursor.getString(dataColumn) ?: continue
                    val file = java.io.File(dataPath)
                    val parentFile = file.parentFile
                    if (parentFile != null) {
                        val parentPath = parentFile.absolutePath
                        if (selectedMusicFolder != null) {
                            if (parentPath.startsWith(selectedMusicFolder)) {
                                if (!list.contains(parentPath)) {
                                    list.add(parentPath)
                                }
                            }
                        } else {
                            if (!list.contains(parentPath)) {
                                list.add(parentPath)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list.sorted()
    }

    suspend fun findDuplicates(context: android.content.Context): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val groups = mutableListOf<DuplicateGroup>()
        val localFilesCopy = ArrayList(localAudioFiles)
        if (localFilesCopy.isEmpty()) return@withContext emptyList()
        
        val pathMap = mutableMapOf<Long, String>()
        val sizeMap = mutableMapOf<Long, Long>()
        try {
            val projection = arrayOf(
                android.provider.MediaStore.Audio.Media._ID,
                android.provider.MediaStore.Audio.Media.DATA,
                android.provider.MediaStore.Audio.Media.SIZE
            )
            context.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
                val dataCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val data = cursor.getString(dataCol)
                    val size = cursor.getLong(sizeCol)
                    if (!data.isNullOrEmpty()) {
                        pathMap[id] = data
                        sizeMap[id] = size
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        localFilesCopy.forEach { file ->
            val path = pathMap[file.id]
            if (path != null && sizeMap[file.id] == null) {
                try {
                    val f = java.io.File(path)
                    if (f.exists()) {
                        sizeMap[file.id] = f.length()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        val processedIds = mutableSetOf<Long>()

        val folderGroups = localFilesCopy.groupBy { file ->
            val path = pathMap[file.id] ?: ""
            val parentPath = if (path.isNotEmpty()) java.io.File(path).parent ?: "" else file.folderPath
            val name = if (path.isNotEmpty()) java.io.File(path).name else file.title
            val cleanName = cleanFilename(name).lowercase()
            Pair(parentPath.lowercase(), cleanName)
        }

        folderGroups.forEach { (_, files) ->
            if (files.size > 1) {
                val sorted = files.sortedWith(compareBy<AudioFile> { file ->
                    val path = pathMap[file.id] ?: ""
                    val name = java.io.File(path).name
                    val hasSuffix = name.contains(REGEX_SUFFIX_PARENTHESIS) || 
                                    name.contains(REGEX_SUFFIX_COPIA) ||
                                    name.contains(REGEX_SUFFIX_UNDERSCORE)
                    if (hasSuffix) 1 else 0
                }.thenBy { it.dateAdded }
                 .thenBy { (pathMap[it.id] ?: "").length })

                val originalFile = sorted.first()
                val originalPath = pathMap[originalFile.id] ?: ""
                val originalSize = sizeMap[originalFile.id] ?: 0L

                val duplicateItems = sorted.drop(1).map { file ->
                    val path = pathMap[file.id] ?: ""
                    val size = sizeMap[file.id] ?: 0L
                    processedIds.add(file.id)
                    DuplicateItem(file, path, size)
                }

                processedIds.add(originalFile.id)

                groups.add(
                    DuplicateGroup(
                        original = DuplicateItem(originalFile, originalPath, originalSize),
                        duplicates = duplicateItems
                    )
                )
            }
        }

        val remainingFiles = localFilesCopy.filter { !processedIds.contains(it.id) }
        val metaGroups = remainingFiles.groupBy { file ->
            val t = file.title.trim().lowercase()
            val a = file.artist.trim().lowercase()
            Pair(t, a)
        }

        metaGroups.forEach { (metaKey, files) ->
            val title = metaKey.first
            val artist = metaKey.second
            
            val isUnknown = title.isBlank() || title.contains("unknown") ||
                            artist.isBlank() || artist.contains("unknown")
            
            if (!isUnknown && files.size > 1) {
                val partitions = mutableListOf<MutableList<AudioFile>>()
                files.forEach { file ->
                    var added = false
                    for (part in partitions) {
                        val representative = part.first()
                        if (Math.abs(representative.duration - file.duration) <= 3000) {
                            part.add(file)
                            added = true
                            break
                        }
                    }
                    if (!added) {
                        partitions.add(mutableListOf(file))
                    }
                }

                partitions.forEach { part ->
                    if (part.size > 1) {
                        val sorted = part.sortedWith(compareBy<AudioFile> { file ->
                            val path = pathMap[file.id] ?: ""
                            val name = java.io.File(path).name
                            val hasSuffix = name.contains(REGEX_SUFFIX_PARENTHESIS) || 
                                            name.contains(REGEX_SUFFIX_COPIA) ||
                                            name.contains(REGEX_SUFFIX_UNDERSCORE)
                            if (hasSuffix) 1 else 0
                        }.thenBy { it.dateAdded }
                         .thenBy { (pathMap[it.id] ?: "").length })

                        val originalFile = sorted.first()
                        val originalPath = pathMap[originalFile.id] ?: ""
                        val originalSize = sizeMap[originalFile.id] ?: 0L

                        val duplicateItems = sorted.drop(1).map { file ->
                            val path = pathMap[file.id] ?: ""
                            val size = sizeMap[file.id] ?: 0L
                            DuplicateItem(file, path, size)
                        }

                        groups.add(
                            DuplicateGroup(
                                original = DuplicateItem(originalFile, originalPath, originalSize),
                                duplicates = duplicateItems
                            )
                        )
                    }
                }
            }
        }

        return@withContext groups
    }

    private fun cleanFilename(filename: String): String {
        val nameWithoutExt = filename.substringBeforeLast(".")
        return nameWithoutExt
            .replace(REGEX_CLEAN_PARENTHESIS, "")
            .replace(REGEX_CLEAN_COPIA, "")
            .replace(REGEX_CLEAN_UNDERSCORE, "")
            .trim()
    }

    fun deleteSongs(context: android.content.Context, songIds: List<Long>, onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                val playingId = browser.value?.currentMediaItem?.mediaId
                val isPlayingDeleted = playingId != null && songIds.contains(playingId.toLongOrNull() ?: -1L)
                if (isPlayingDeleted) {
                    val b = browser.value
                    if (b != null) {
                        if (b.hasNextMediaItem()) {
                            b.seekToNext()
                        } else if (b.hasPreviousMediaItem()) {
                            b.seekToPrevious()
                        } else {
                            b.stop()
                        }
                    }
                }

                songIds.forEach { songId ->
                    val song = localAudioFiles.find { it.id == songId } ?: return@forEach
                    
                    var path: String? = null
                    try {
                        val uri = Uri.parse(song.uriString)
                        path = if (uri.scheme == "file") {
                            uri.path
                        } else if (uri.scheme == "content") {
                            val projection = arrayOf(android.provider.MediaStore.Audio.Media.DATA)
                            val cursor = context.contentResolver.query(uri, projection, null, null, null)
                            val dataPath = cursor?.use {
                                if (it.moveToFirst()) {
                                    val columnIndex = it.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                                    it.getString(columnIndex)
                                } else null
                            }
                            dataPath
                        } else {
                            song.uriString
                        }
                        
                        if (!path.isNullOrEmpty()) {
                            val file = java.io.File(path)
                            if (file.exists()) {
                                file.delete()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                            context,
                            "FileDelete",
                            "Failed to physically delete file for songId $songId (path: $path)",
                            e
                        )
                    }
                    
                    try {
                        val uri = Uri.parse(song.uriString)
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                            context,
                            "ContentResolverDelete",
                            "Failed to delete URI from ContentResolver for songId $songId (uri: ${song.uriString})",
                            e
                        )
                    }
                    
                    audioDao.deleteById(songId)
                    localAudioFiles.removeAll { it.id == songId }
                }
                
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    context,
                    "DeleteSongs",
                    "Bulk deletion operation failed",
                    e
                )
            }
        }
    }

    val integrityManager = com.kevshupp.kevmusicplayer.playback.managers.IntegrityCheckerManager(localAudioFiles, viewModelScope)
    val isVerifyingIntegrity get() = integrityManager.isVerifyingIntegrity
    val verifyIntegrityCurrent get() = integrityManager.verifyIntegrityCurrent
    val verifyIntegrityTotal get() = integrityManager.verifyIntegrityTotal
    val verifyIntegrityCurrentName get() = integrityManager.verifyIntegrityCurrentName

    fun verifySongsIntegrity(context: Context, onComplete: (List<Pair<AudioFile, String>>) -> Unit) =
        integrityManager.verifySongsIntegrity(context, onComplete)

    override fun onCleared() {
        super.onCleared()
        browser.value?.release()
        browserFuture?.let { MediaBrowser.releaseFuture(it) }
        browser.value = null
    }
}

