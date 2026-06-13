package com.kevshupp.kevmusicplayer.playback

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import com.kevshupp.kevmusicplayer.data.LyricsRepository
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import java.io.InputStream
import java.io.OutputStream
import android.content.Context


enum class SmartPlaylistRule {
    MOST_PLAYED, RECENTLY_ADDED, PLAYBACK_HISTORY, LONGEST_SONGS, SHORTEST_SONGS, NEVER_PLAYED, RANDOM_MIX
}

enum class LogicalOperator {
    AND, OR
}

enum class RuleField {
    TITLE, ARTIST, ALBUM, GENRE, YEAR, DURATION_SECONDS, PLAY_COUNT, LAST_PLAYED_DAYS, DATE_ADDED_DAYS
}

enum class RuleOperator {
    EQUALS, CONTAINS, GREATER_THAN, LESS_THAN, STARTS_WITH, ENDS_WITH
}

sealed class SmartRuleNode {
    abstract fun evaluate(context: android.content.Context, song: AudioFile): Boolean
    abstract fun toJson(): JSONObject

    companion object {
        fun fromJson(json: JSONObject): SmartRuleNode {
            return if (json.has("operator")) {
                val op = LogicalOperator.valueOf(json.getString("operator"))
                val childrenJson = json.getJSONArray("children")
                val children = mutableListOf<SmartRuleNode>()
                for (i in 0 until childrenJson.length()) {
                    children.add(fromJson(childrenJson.getJSONObject(i)))
                }
                GroupNode(op, children)
            } else {
                val field = RuleField.valueOf(json.getString("field"))
                val op = RuleOperator.valueOf(json.getString("operator_type"))
                val value = json.getString("value")
                ConditionNode(field, op, value)
            }
        }
    }
}

data class ConditionNode(
    val field: RuleField,
    val operator: RuleOperator,
    val value: String
) : SmartRuleNode() {
    companion object {
        val songYearCache = java.util.concurrent.ConcurrentHashMap<Long, String>()
    }

    override fun evaluate(context: android.content.Context, song: AudioFile): Boolean {
        return try {
            val fieldValue: String = when (field) {
                RuleField.TITLE -> song.title
                RuleField.ARTIST -> song.artist
                RuleField.ALBUM -> song.album
                RuleField.GENRE -> song.genre ?: ""
                RuleField.YEAR -> {
                    val cached = songYearCache[song.id]
                    if (cached != null) {
                        cached
                    } else {
                        val yearVal = try {
                            val path = getPhysicalPath(context, song.id, song.uriString)
                            if (!path.isNullOrBlank()) {
                                val f = File(path)
                                if (f.exists() && f.isFile) {
                                    val audioFile = AudioFileIO.read(f)
                                    val tag = audioFile.tag
                                    val yearStr = tag?.getFirst(FieldKey.YEAR) ?: ""
                                    val yearMatch = Regex("\\d{4}").find(yearStr)
                                    yearMatch?.value ?: yearStr
                                } else ""
                            } else ""
                        } catch (e: Exception) {
                            ""
                        }
                        songYearCache[song.id] = yearVal
                        yearVal
                    }
                }
                RuleField.DURATION_SECONDS -> (song.duration / 1000).toString()
                RuleField.PLAY_COUNT -> song.playCount.toString()
                RuleField.LAST_PLAYED_DAYS -> {
                    if (song.lastPlayed <= 0L) "-1"
                    else {
                        val diffMs = System.currentTimeMillis() - song.lastPlayed
                        val diffDays = diffMs / (1000L * 60 * 60 * 24)
                        diffDays.toString()
                    }
                }
                RuleField.DATE_ADDED_DAYS -> {
                    val diffMs = System.currentTimeMillis() - song.dateAdded
                    val diffDays = diffMs / (1000L * 60 * 60 * 24)
                    diffDays.toString()
                }
            }

            when (operator) {
                RuleOperator.EQUALS -> fieldValue.equals(value, ignoreCase = true)
                RuleOperator.CONTAINS -> fieldValue.contains(value, ignoreCase = true)
                RuleOperator.STARTS_WITH -> fieldValue.startsWith(value, ignoreCase = true)
                RuleOperator.ENDS_WITH -> fieldValue.endsWith(value, ignoreCase = true)
                RuleOperator.GREATER_THAN -> {
                    val fieldNum = fieldValue.toDoubleOrNull() ?: 0.0
                    val valNum = value.toDoubleOrNull() ?: 0.0
                    fieldNum > valNum
                }
                RuleOperator.LESS_THAN -> {
                    val fieldNum = fieldValue.toDoubleOrNull() ?: 0.0
                    val valNum = value.toDoubleOrNull() ?: 0.0
                    fieldNum < valNum
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("field", field.name)
        json.put("operator_type", operator.name)
        json.put("value", value)
        return json
    }
}

data class GroupNode(
    val operator: LogicalOperator,
    val children: List<SmartRuleNode>
) : SmartRuleNode() {
    override fun evaluate(context: android.content.Context, song: AudioFile): Boolean {
        if (children.isEmpty()) return true
        return when (operator) {
            LogicalOperator.AND -> children.all { it.evaluate(context, song) }
            LogicalOperator.OR -> children.any { it.evaluate(context, song) }
        }
    }

    override fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("operator", operator.name)
        val arr = JSONArray()
        children.forEach { arr.put(it.toJson()) }
        json.put("children", arr)
        return json
    }
}

data class SmartPlaylistConfig(
    val name: String,
    val rule: SmartPlaylistRule,
    val limit: Int = 50,
    val isAdvanced: Boolean = false,
    val advancedRule: SmartRuleNode? = null
)

class MediaBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    val browser = mutableStateOf<MediaBrowser?>(null)
    val localAudioFiles = mutableStateListOf<AudioFile>()
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

    val isDownloadingAllLyrics = mutableStateOf(false)
    val downloadAllLyricsCurrent = mutableStateOf(0)
    val downloadAllLyricsTotal = mutableStateOf(0)
    val downloadAllLyricsSuccessCount = mutableStateOf(0)
    val downloadAllLyricsCurrentName = mutableStateOf("")
    val isScanning = mutableStateOf(false)
    var ignoreSavePlaybackState = false

    private val audioScanner = AudioScanner(application)
    private val database = AppDatabase.getDatabase(application)
    private val audioDao = database.audioDao()

    init {
        // Force jaudiotagger to run in Android mode (bypasses java.awt classes)
        try {
            org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true)
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        // Instantly load the cached songs from SQLite database in chunks to ensure the screen is NEVER empty upon startup and we avoid RAM spike/freezes!
        viewModelScope.launch {
            try {
                // 1. Load the first chunk (500 files) for instant rendering
                val firstChunk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    audioDao.getAudioFilesPaged(500, 0)
                }
                if (firstChunk.isNotEmpty()) {
                    localAudioFiles.clear()
                    localAudioFiles.addAll(firstChunk)
                    loadPlaylists()
                    updateSmartPlaylists()
                }

                // 2. Load the remaining files in the background in larger pages
                var offset = firstChunk.size
                val pageSize = 2000
                while (true) {
                    val nextChunk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        audioDao.getAudioFilesPaged(pageSize, offset)
                    }
                    if (nextChunk.isEmpty()) break
                    
                    localAudioFiles.addAll(nextChunk)
                    offset += nextChunk.size
                    
                    // Yield or delay slightly to prevent UI thread starvation during rendering
                    kotlinx.coroutines.yield()
                }
                
                // Final reload of playlists / smart playlists to encompass all newly loaded items
                if (offset > firstChunk.size) {
                    loadPlaylists()
                    updateSmartPlaylists()
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
                        if (mediaItem != null) {
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
                    }
                    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                        savePlaybackState()
                    }
                })

                restorePlaybackState()
                scanFiles()
            } catch (e: Exception) {
                e.printStackTrace()
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
            }
        }
        browser.value = null
        browserFuture = null
    }

    fun savePlaybackState() {
        if (ignoreSavePlaybackState) return
        val b = browser.value ?: return
        val currentItem = b.currentMediaItem
        val id = currentItem?.mediaId?.toLongOrNull() ?: -1L
        val position = b.currentPosition
        val activeIndex = b.currentMediaItemIndex
        val shuffleModeEnabled = b.shuffleModeEnabled
        
        // Fetch media item IDs on the main thread
        val mediaIds = ArrayList<String>(b.mediaItemCount)
        for (i in 0 until b.mediaItemCount) {
            val item = b.getMediaItemAt(i)
            mediaIds.add(item.mediaId)
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
        if (b.currentMediaItem != null) return

        val prefs = getApplication<Application>().getSharedPreferences("playback_prefs", android.content.Context.MODE_PRIVATE)
        val lastShuffleEnabled = prefs.getBoolean("last_shuffle_enabled", false)
        b.shuffleModeEnabled = lastShuffleEnabled

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
                
                // 1. Check local/embedded sources first (LRC file next to it or embedded tag) on Dispatchers.IO
                val localLyrics = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    readLocalLrcOrEmbedded(context, song)
                }
                val localTranslation = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    readLocalTranslatedLrcOrEmbedded(context, song)
                }
                if (!localTranslation.isNullOrBlank() && song.translatedLyrics != localTranslation) {
                    updateSongTranslatedLyrics(id, localTranslation)
                }

                if (!localLyrics.isNullOrBlank()) {
                    val localIsSynced = LyricsRepository.isLrcSynced(localLyrics)
                    val dbIsSynced = LyricsRepository.isLrcSynced(song.lyrics)
                    
                    // If local is synced, or if DB is empty, use local lyrics!
                    if (localIsSynced || (song.lyrics.isNullOrBlank() && !localIsSynced)) {
                        if (song.lyrics != localLyrics) {
                            updateSongLyrics(id, localLyrics)
                        }
                        if (localIsSynced) {
                            return@launch // Synchronized lyrics successfully loaded, done!
                        }
                    }
                }

                // 2. If currently stored DB lyrics are already synchronized, do not fetch online
                if (LyricsRepository.isLrcSynced(song.lyrics)) {
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
            }
        }
    }

    fun scanFiles(isManual: Boolean = false) {
        if (isScanning.value) return
        isScanning.value = true
        viewModelScope.launch {
            try {
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
                        emptyList<String>()
                    }

                    val filteredScanned = scannedFiles.filter { file ->
                        excludedFolders.none { excluded ->
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
                    
                    // 2. Map scanned files to database entities, preserving custom edited lyrics
                    val entities = filteredScanned.map { file ->
                        val existing = existingEntities[file.id]
                        file.copy(
                            lyrics = existing?.lyrics ?: file.lyrics,
                            translatedLyrics = existing?.translatedLyrics ?: file.translatedLyrics,
                            playCount = existing?.playCount ?: 0,
                            lastPlayed = existing?.lastPlayed ?: 0L,
                            replayGain = existing?.replayGain ?: file.replayGain
                        )
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
                    localAudioFiles.clear()
                    localAudioFiles.addAll(updatedFilesList)
                    loadPlaylists()
                    updateSmartPlaylists()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isScanning.value = false
            }
        }
    }

    fun playFile(file: AudioFile, customQueue: List<AudioFile>? = null) {
        val b = browser.value ?: return
        
        val fullQueue = customQueue ?: localAudioFiles
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

        b.setMediaItems(mediaItems, index, 0L)
        b.prepare()
        b.play()
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getLocalized(es: String, en: String): String {
        val locale = java.util.Locale.getDefault().language
        return if (locale == "es") es else en
    }

    fun downloadAllLyrics(context: android.content.Context) {
        if (isDownloadingAllLyrics.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val pendingListUpdates = mutableMapOf<Long, String>()
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val NOTIFICATION_ID = 1001
            val NOTIFICATION_ID_SUMMARY = 1002
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "lyrics_download_channel",
                    getLocalized("Descargador de Letras", "Lyrics Downloader"),
                    android.app.NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(channel)
            }
            
            var downloadedSuccessCount = 0
            var skippedCount = 0
            var notFoundCount = 0
            var errorCount = 0
            
            try {
                isDownloadingAllLyrics.value = true
                downloadAllLyricsSuccessCount.value = 0
                val songsToProcess = localAudioFiles.toList()
                val total = songsToProcess.size
                downloadAllLyricsTotal.value = total
                
                val builder = androidx.core.app.NotificationCompat.Builder(context, "lyrics_download_channel")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(getLocalized("Descargando letras...", "Downloading lyrics..."))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                
                songsToProcess.forEachIndexed { index, song ->
                    if (!isDownloadingAllLyrics.value) return@launch // Cancel if requested
                    
                    downloadAllLyricsCurrent.value = index + 1
                    downloadAllLyricsCurrentName.value = song.title
                    
                    // Update progress notification
                    val titleText = song.title
                    builder.setContentText("$titleText (${index + 1}/$total)")
                        .setProgress(total, index + 1, false)
                    notificationManager.notify(NOTIFICATION_ID, builder.build())
                    
                    // Check if song already has synced lyrics in DB
                    val dbHasSynced = !song.lyrics.isNullOrBlank() && LyricsRepository.isLrcSynced(song.lyrics)
                    
                    if (dbHasSynced) {
                        skippedCount++
                    } else {
                        // Check local/embedded first
                        val localLyrics = readLocalLrcOrEmbedded(context, song)
                        val localIsSynced = !localLyrics.isNullOrBlank() && LyricsRepository.isLrcSynced(localLyrics)
                        
                        if (localIsSynced) {
                            // Update DB
                            audioDao.updateLyrics(song.id, localLyrics)
                            pendingListUpdates[song.id] = localLyrics!!
                            skippedCount++
                        } else {
                            // Fetch online from LrcLib
                            try {
                                val fetched = LyricsRepository.fetchLyricsFromLrcLib(song.artist, song.title)
                                if (!fetched.isNullOrEmpty()) {
                                    val fetchedIsSynced = LyricsRepository.isLrcSynced(fetched)
                                    // If fetched is synced, or if local is completely empty, save it!
                                    if (fetchedIsSynced || song.lyrics.isNullOrBlank()) {
                                        audioDao.updateLyrics(song.id, fetched)
                                        pendingListUpdates[song.id] = fetched
                                        // Save physical
                                        saveLyricsPhysical(context, song.id, song.title, song.folderPath, fetched)
                                        downloadedSuccessCount++
                                    } else {
                                        notFoundCount++
                                    }
                                } else {
                                    notFoundCount++
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                errorCount++
                            }
                            // Small delay to be polite to LrcLib API rate limits
                            kotlinx.coroutines.delay(300)
                        }
                    }

                    // Flush batch updates to UI list in groups of 10
                    if (pendingListUpdates.size >= 10 || index == songsToProcess.lastIndex) {
                        val batch = pendingListUpdates.toMap()
                        pendingListUpdates.clear()
                        withContext(Dispatchers.Main) {
                            batch.forEach { (songId, lyricsText) ->
                                val listIndex = localAudioFiles.indexOfFirst { it.id == songId }
                                if (listIndex != -1) {
                                    localAudioFiles[listIndex] = localAudioFiles[listIndex].copy(lyrics = lyricsText)
                                }
                            }
                        }
                    }
                }
                
                // Done! Show a notification and toast
                notificationManager.cancel(NOTIFICATION_ID)
                
                val totalWithLyricsNow = localAudioFiles.count { !it.lyrics.isNullOrBlank() }
                downloadAllLyricsSuccessCount.value = downloadedSuccessCount
                
                val summaryText = getLocalized(
                    "Con letra: $totalWithLyricsNow | Nuevas: $downloadedSuccessCount | Existentes: $skippedCount | No encontradas: $notFoundCount | Errores: $errorCount",
                    "With lyrics: $totalWithLyricsNow | New: $downloadedSuccessCount | Existing: $skippedCount | Not Found: $notFoundCount | Errors: $errorCount"
                )
                
                val summaryBuilder = androidx.core.app.NotificationCompat.Builder(context, "lyrics_download_channel")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(getLocalized("Descarga de letras finalizada", "Lyrics download finished"))
                    .setContentText(summaryText)
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(summaryText))
                    .setOngoing(false)
                    .setAutoCancel(true)
                notificationManager.notify(NOTIFICATION_ID_SUMMARY, summaryBuilder.build())

                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        summaryText,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                // Apply any remaining list updates before exiting
                if (pendingListUpdates.isNotEmpty()) {
                    val batch = pendingListUpdates.toMap()
                    pendingListUpdates.clear()
                    withContext(Dispatchers.Main) {
                        batch.forEach { (songId, lyricsText) ->
                            val listIndex = localAudioFiles.indexOfFirst { it.id == songId }
                            if (listIndex != -1) {
                                localAudioFiles[listIndex] = localAudioFiles[listIndex].copy(lyrics = lyricsText)
                            }
                        }
                    }
                }
                notificationManager.cancel(NOTIFICATION_ID)
                withContext(Dispatchers.Main) {
                    isDownloadingAllLyrics.value = false
                    downloadAllLyricsCurrent.value = 0
                    downloadAllLyricsTotal.value = 0
                    downloadAllLyricsCurrentName.value = ""
                }
            }
        }
    }

    fun cancelDownloadAllLyrics() {
        isDownloadingAllLyrics.value = false
    }

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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun exportBackup(
        context: Context,
        outputStream: OutputStream,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject()
                json.put("backup_version", 1)

                // 1. Export settings
                val settingsPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                val settingsJson = JSONObject()
                settingsJson.put("language", settingsPrefs.getString("language", "en"))
                settingsJson.put("app_theme", settingsPrefs.getString("app_theme", "cyberpunk"))
                settingsJson.put("refresh_rate", settingsPrefs.getString("refresh_rate", "120"))
                settingsJson.put("disable_animations", settingsPrefs.getBoolean("disable_animations", false))
                settingsJson.put("excluded_folders", settingsPrefs.getString("excluded_folders", "[]"))
                
                // Export backup folder setting if present
                val backupDirUri = settingsPrefs.getString("backup_dir_uri", null)
                if (backupDirUri != null) {
                    settingsJson.put("backup_dir_uri", backupDirUri)
                }
                
                // Export tab configurations
                val playbackPrefs = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
                settingsJson.put("enabled_tabs", playbackPrefs.getString("enabled_tabs", ""))

                // Export additional settings
                settingsJson.put("ambient_glow_enabled", settingsPrefs.getBoolean("ambient_glow_enabled", true))
                settingsJson.put("ambient_glow_intensity", settingsPrefs.getString("ambient_glow_intensity", "medium"))
                settingsJson.put("auto_translate", settingsPrefs.getBoolean("auto_translate", false))
                settingsJson.put("bluetooth_resume_enabled", settingsPrefs.getBoolean("bluetooth_resume_enabled", false))
                settingsJson.put("bluetooth_resume_all", settingsPrefs.getBoolean("bluetooth_resume_all", false))
                
                val btDevices = settingsPrefs.getStringSet("bluetooth_resume_devices", emptySet()) ?: emptySet()
                val btDevicesArray = JSONArray()
                btDevices.forEach { btDevicesArray.put(it) }
                settingsJson.put("bluetooth_resume_devices", btDevicesArray)
                
                settingsJson.put("normalize_sound", settingsPrefs.getBoolean("normalize_sound", false))
                settingsJson.put("crossfade_duration", settingsPrefs.getInt("crossfade_duration", 0))
                settingsJson.put("show_visualizer", settingsPrefs.getBoolean("show_visualizer", true))
                
                json.put("settings", settingsJson)

                // 1.5. Export equalizer
                val eqPrefs = context.getSharedPreferences("equalizer_prefs", Context.MODE_PRIVATE)
                val eqJson = JSONObject()
                eqJson.put("eq_enabled", eqPrefs.getBoolean("eq_enabled", false))
                eqJson.put("bb_enabled", eqPrefs.getBoolean("bb_enabled", false))
                eqJson.put("bb_strength", eqPrefs.getInt("bb_strength", 0))
                eqJson.put("virt_enabled", eqPrefs.getBoolean("virt_enabled", false))
                eqJson.put("virt_strength", eqPrefs.getInt("virt_strength", 0))
                eqJson.put("eq_bands", eqPrefs.getString("eq_bands", "0,0,0,0,0"))
                eqJson.put("eq_preset", eqPrefs.getString("eq_preset", "Flat"))
                json.put("equalizer", eqJson)

                // 2. Export playlists
                val playlistsPrefs = context.getSharedPreferences("playlists_prefs", Context.MODE_PRIVATE)
                val playlistsJson = JSONObject()
                val playlistNames = playlistsPrefs.getStringSet("playlist_names", emptySet()) ?: emptySet()
                playlistNames.forEach { name ->
                    val songsStr = playlistsPrefs.getString("playlist_$name", "") ?: ""
                    val songIdsArray = JSONArray()
                    if (songsStr.isNotBlank()) {
                        songsStr.split(",").forEach { idStr ->
                            idStr.toLongOrNull()?.let { songIdsArray.put(it) }
                        }
                    }
                    playlistsJson.put(name, songIdsArray)
                }
                json.put("playlists", playlistsJson)

                // Export smart playlists
                val smartPlaylistsJson = JSONObject()
                val smartPlaylistNames = playlistsPrefs.getStringSet("smart_playlist_names", emptySet()) ?: emptySet()
                smartPlaylistNames.forEach { name ->
                    val configStr = playlistsPrefs.getString("smart_playlist_config_$name", null)
                    if (configStr != null) {
                        try {
                            smartPlaylistsJson.put(name, JSONObject(configStr))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                json.put("smart_playlists", smartPlaylistsJson)

                // Export playlist covers
                val playlistCoversJson = JSONObject()
                playlistNames.forEach { name ->
                    val cover = playlistsPrefs.getString("playlist_cover_$name", null)
                    if (cover != null) {
                        playlistCoversJson.put(name, cover)
                    }
                }
                json.put("playlist_covers", playlistCoversJson)

                // 3. Export cached songs (lyrics and translatedLyrics)
                val cachedSongsArray = JSONArray()
                val allEntities = audioDao.getAllAudioFiles()
                allEntities.forEach { entity ->
                    if (!entity.lyrics.isNullOrBlank() || !entity.translatedLyrics.isNullOrBlank()) {
                        val songJson = JSONObject()
                        songJson.put("id", entity.id)
                        songJson.put("lyrics", entity.lyrics)
                        songJson.put("translatedLyrics", entity.translatedLyrics)
                        cachedSongsArray.put(songJson)
                    }
                }
                json.put("cached_songs", cachedSongsArray)

                // Write to stream
                outputStream.use { stream ->
                    stream.write(json.toString(4).toByteArray(Charsets.UTF_8))
                }

                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
                val jsonStr = inputStream.use { stream ->
                    stream.bufferedReader().use { it.readText() }
                }
                val json = JSONObject(jsonStr)
                var importedLanguage: String? = null

                // 1. Restore settings
                if (json.has("settings")) {
                    val settingsJson = json.getJSONObject("settings")
                    val settingsPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                    val edit = settingsPrefs.edit()
                    edit.putBoolean("is_first_run", false)
                    if (settingsJson.has("language")) {
                        val lang = settingsJson.getString("language")
                        edit.putString("language", lang)
                        importedLanguage = lang
                    }
                    if (settingsJson.has("app_theme")) edit.putString("app_theme", settingsJson.getString("app_theme"))
                    if (settingsJson.has("refresh_rate")) edit.putString("refresh_rate", settingsJson.getString("refresh_rate"))
                    if (settingsJson.has("disable_animations")) edit.putBoolean("disable_animations", settingsJson.getBoolean("disable_animations"))
                    if (settingsJson.has("backup_dir_uri")) edit.putString("backup_dir_uri", settingsJson.getString("backup_dir_uri"))
                    if (settingsJson.has("excluded_folders")) edit.putString("excluded_folders", settingsJson.getString("excluded_folders"))
                    
                    if (settingsJson.has("ambient_glow_enabled")) edit.putBoolean("ambient_glow_enabled", settingsJson.getBoolean("ambient_glow_enabled"))
                    if (settingsJson.has("ambient_glow_intensity")) edit.putString("ambient_glow_intensity", settingsJson.getString("ambient_glow_intensity"))
                    if (settingsJson.has("auto_translate")) edit.putBoolean("auto_translate", settingsJson.getBoolean("auto_translate"))
                    if (settingsJson.has("bluetooth_resume_enabled")) edit.putBoolean("bluetooth_resume_enabled", settingsJson.getBoolean("bluetooth_resume_enabled"))
                    if (settingsJson.has("bluetooth_resume_all")) edit.putBoolean("bluetooth_resume_all", settingsJson.getBoolean("bluetooth_resume_all"))
                    
                    if (settingsJson.has("bluetooth_resume_devices")) {
                        val btArray = settingsJson.getJSONArray("bluetooth_resume_devices")
                        val btSet = mutableSetOf<String>()
                        for (i in 0 until btArray.length()) {
                            btSet.add(btArray.getString(i))
                        }
                        edit.putStringSet("bluetooth_resume_devices", btSet)
                    }
                    
                    if (settingsJson.has("normalize_sound")) edit.putBoolean("normalize_sound", settingsJson.getBoolean("normalize_sound"))
                    if (settingsJson.has("crossfade_duration")) edit.putInt("crossfade_duration", settingsJson.getInt("crossfade_duration"))
                    if (settingsJson.has("show_visualizer")) edit.putBoolean("show_visualizer", settingsJson.getBoolean("show_visualizer"))
                    
                    edit.apply()

                    // Restore tab configurations
                    if (settingsJson.has("enabled_tabs")) {
                        val playbackPrefs = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
                        val tabsStr = settingsJson.getString("enabled_tabs")
                        playbackPrefs.edit().putString("enabled_tabs", tabsStr).apply()
                        withContext(Dispatchers.Main) {
                            if (!tabsStr.isNullOrBlank()) {
                                enabledTabs.value = tabsStr.split(",")
                            }
                        }
                    }
                }

                // 1.5. Restore equalizer
                if (json.has("equalizer")) {
                    val eqJson = json.getJSONObject("equalizer")
                    val eqPrefs = context.getSharedPreferences("equalizer_prefs", Context.MODE_PRIVATE)
                    val eqEdit = eqPrefs.edit()
                    if (eqJson.has("eq_enabled")) eqEdit.putBoolean("eq_enabled", eqJson.getBoolean("eq_enabled"))
                    if (eqJson.has("bb_enabled")) eqEdit.putBoolean("bb_enabled", eqJson.getBoolean("bb_enabled"))
                    if (eqJson.has("bb_strength")) eqEdit.putInt("bb_strength", eqJson.getInt("bb_strength"))
                    if (eqJson.has("virt_enabled")) eqEdit.putBoolean("virt_enabled", eqJson.getBoolean("virt_enabled"))
                    if (eqJson.has("virt_strength")) eqEdit.putInt("virt_strength", eqJson.getInt("virt_strength"))
                    if (eqJson.has("eq_bands")) eqEdit.putString("eq_bands", eqJson.getString("eq_bands"))
                    if (eqJson.has("eq_preset")) eqEdit.putString("eq_preset", eqJson.getString("eq_preset"))
                    eqEdit.apply()
                }

                // 2. Restore playlists
                if (json.has("playlists")) {
                    val playlistsJson = json.getJSONObject("playlists")
                    val playlistsPrefs = context.getSharedPreferences("playlists_prefs", Context.MODE_PRIVATE)
                    val edit = playlistsPrefs.edit()
                    val names = mutableSetOf<String>()

                    playlistsJson.keys().forEach { name ->
                        names.add(name)
                        val songIdsArray = playlistsJson.getJSONArray(name)
                        val songIds = mutableListOf<String>()
                        for (i in 0 until songIdsArray.length()) {
                            songIds.add(songIdsArray.getLong(i).toString())
                        }
                        edit.putString("playlist_$name", songIds.joinToString(","))
                    }
                    edit.putStringSet("playlist_names", names)

                    // Restore smart playlists if any
                    if (json.has("smart_playlists")) {
                        val smartJson = json.getJSONObject("smart_playlists")
                        val smartNames = mutableSetOf<String>()
                        smartJson.keys().forEach { name ->
                            smartNames.add(name)
                            edit.putString("smart_playlist_config_$name", smartJson.getJSONObject(name).toString())
                        }
                        edit.putStringSet("smart_playlist_names", smartNames)
                    }

                    // Restore playlist covers if any
                    if (json.has("playlist_covers")) {
                        val coversJson = json.getJSONObject("playlist_covers")
                        coversJson.keys().forEach { name ->
                            edit.putString("playlist_cover_$name", coversJson.getString(name))
                        }
                    }

                    edit.apply()
                }

                // 3. Restore cached songs (lyrics and translatedLyrics)
                if (json.has("cached_songs")) {
                    val cachedSongsArray = json.getJSONArray("cached_songs")
                    for (i in 0 until cachedSongsArray.length()) {
                        val songJson = cachedSongsArray.getJSONObject(i)
                        val id = songJson.getLong("id")
                        val lyrics = if (songJson.has("lyrics") && !songJson.isNull("lyrics")) songJson.getString("lyrics") else null
                        val translatedLyrics = if (songJson.has("translatedLyrics") && !songJson.isNull("translatedLyrics")) songJson.getString("translatedLyrics") else null

                        audioDao.updateLyrics(id, lyrics)
                        audioDao.updateTranslatedLyrics(id, translatedLyrics)
                    }
                }

                // Reload localAudioFiles from database to sync current in-memory lists instantly!
                val allEntities = audioDao.getAllAudioFiles()
                val updatedFiles = allEntities.map { entity ->
                    AudioFile(
                        id = entity.id,
                        title = entity.title,
                        artist = entity.artist,
                        album = entity.album,
                        genre = entity.genre,
                        duration = entity.duration,
                        uriString = entity.uriString,
                        folderPath = entity.folderPath,
                        folderName = entity.folderName,
                        lyrics = entity.lyrics,
                        translatedLyrics = entity.translatedLyrics,
                        playCount = entity.playCount,
                        dateAdded = entity.dateAdded,
                        lastPlayed = entity.lastPlayed
                    )
                }

                withContext(Dispatchers.Main) {
                    if (importedLanguage != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        try {
                            val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
                            localeManager?.applicationLocales = android.os.LocaleList.forLanguageTags(importedLanguage)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
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

                    browser.value?.clearMediaItems()
                    browser.value?.stop()
                    browser.value?.release()

                    browserFuture?.let {
                        try {
                            MediaBrowser.releaseFuture(it)
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                        }
                    }
                    browserFuture = null
                    browser.value = null
                    
                    val serviceIntent = android.content.Intent(context, com.kevshupp.kevmusicplayer.playback.PlaybackService::class.java)
                    context.stopService(serviceIntent)

                    kotlinx.coroutines.delay(800)
                    onSuccess()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    fun updateEnabledTabs(tabs: List<String>) {
        enabledTabs.value = tabs
        val prefs = getApplication<Application>().getSharedPreferences("playback_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("enabled_tabs", tabs.joinToString(","))
            .apply()
    }

    // Playlists system
    val playlists = androidx.compose.runtime.mutableStateMapOf<String, List<AudioFile>>()
    val playlistCovers = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val smartPlaylists = androidx.compose.runtime.mutableStateMapOf<String, List<AudioFile>>()
    val smartPlaylistConfigs = androidx.compose.runtime.mutableStateListOf<SmartPlaylistConfig>()

    fun incrementSongPlayCount(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                audioDao.incrementPlayCount(id, now)
                withContext(Dispatchers.Main) {
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
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateSmartPlaylists() {
        val localFilesCopy = ArrayList(localAudioFiles)
        viewModelScope.launch {
            val updatedMap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val tempMap = mutableMapOf<String, List<AudioFile>>()
                val keepRecommendations = smartPlaylists.filterKeys { it.startsWith("Recomendaciones") }
                tempMap.putAll(keepRecommendations)

                smartPlaylistConfigs.forEach { config ->
                    val list = if (config.isAdvanced && config.advancedRule != null) {
                        localFilesCopy.filter { config.advancedRule.evaluate(getApplication(), it) }
                            .take(config.limit)
                    } else {
                        when (config.rule) {
                            SmartPlaylistRule.MOST_PLAYED -> {
                                localFilesCopy.filter { it.playCount > 0 }
                                    .sortedByDescending { it.playCount }
                                    .take(config.limit)
                            }
                            SmartPlaylistRule.RECENTLY_ADDED -> {
                                localFilesCopy.sortedByDescending { it.dateAdded }
                                    .take(config.limit)
                            }
                            SmartPlaylistRule.PLAYBACK_HISTORY -> {
                                localFilesCopy.filter { it.lastPlayed > 0L }
                                    .sortedByDescending { it.lastPlayed }
                                    .take(config.limit)
                            }
                            SmartPlaylistRule.LONGEST_SONGS -> {
                                localFilesCopy.sortedByDescending { it.duration }
                                    .take(config.limit)
                            }
                            SmartPlaylistRule.SHORTEST_SONGS -> {
                                localFilesCopy.sortedBy { it.duration }
                                    .take(config.limit)
                            }
                            SmartPlaylistRule.NEVER_PLAYED -> {
                                localFilesCopy.filter { it.playCount == 0 }
                                    .sortedBy { it.title.lowercase() }
                                    .take(config.limit)
                            }
                            SmartPlaylistRule.RANDOM_MIX -> {
                                localFilesCopy.shuffled()
                                    .take(config.limit)
                            }
                        }
                    }
                    tempMap[config.name] = list
                }

                // 4. "Recomendaciones" (Recommendations) based on the user's most played artist
                val artistPlayCounts = localFilesCopy.filter { it.playCount > 0 }
                    .groupBy { it.artist }
                    .mapValues { entry -> entry.value.sumOf { it.playCount } }
                
                val favoriteArtist = artistPlayCounts.maxByOrNull { it.value }?.key
                
                if (favoriteArtist != null) {
                    val artistSongs = localFilesCopy.filter { it.artist == favoriteArtist }
                    val recommendedSongs = artistSongs.sortedBy { it.playCount }.take(15)
                    tempMap["Recomendaciones ($favoriteArtist)"] = recommendedSongs
                } else {
                    val allArtists = localFilesCopy.map { it.artist }.distinct()
                    if (allArtists.isNotEmpty()) {
                        val randomArtist = allArtists.random()
                        val recommendedSongs = localFilesCopy.filter { it.artist == randomArtist }.take(15)
                        tempMap["Recomendaciones ($randomArtist)"] = recommendedSongs
                    } else {
                        tempMap["Recomendaciones"] = emptyList()
                    }
                }
                tempMap
            }

            smartPlaylists.clear()
            smartPlaylists.putAll(updatedMap)
        }
    }

    fun loadPlaylists() {
        viewModelScope.launch {
            val localFilesCopy = ArrayList(localAudioFiles)
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val tempPlaylists = mutableMapOf<String, List<AudioFile>>()
                val tempCovers = mutableMapOf<String, String>()
                val tempConfigs = mutableListOf<SmartPlaylistConfig>()
                
                val prefs = getApplication<Application>().getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
                val names = prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()
                val songsMap = localFilesCopy.associateBy { it.id }
                
                names.forEach { name ->
                    val idsStr = prefs.getString("playlist_$name", "") ?: ""
                    if (idsStr.isNotBlank()) {
                        val ids = idsStr.split(",").mapNotNull { it.toLongOrNull() }
                        val songs = ids.mapNotNull { id -> songsMap[id] }
                        tempPlaylists[name] = songs
                    } else {
                        tempPlaylists[name] = emptyList()
                    }

                    val cover = prefs.getString("playlist_cover_$name", null)
                    if (cover != null) {
                        tempCovers[name] = cover
                    }
                }

                val smartNames = prefs.getStringSet("smart_playlist_names", emptySet()) ?: emptySet()
                smartNames.forEach { name ->
                    val jsonString = prefs.getString("smart_playlist_config_$name", null)
                    if (jsonString != null) {
                        try {
                            val json = JSONObject(jsonString)
                            val rule = SmartPlaylistRule.valueOf(json.getString("rule"))
                            val limit = json.getInt("limit")
                            val isAdvanced = json.optBoolean("isAdvanced", false)
                            val advancedRule = if (isAdvanced && json.has("advancedRule")) {
                                SmartRuleNode.fromJson(json.getJSONObject("advancedRule"))
                            } else null
                            tempConfigs.add(SmartPlaylistConfig(name, rule, limit, isAdvanced, advancedRule))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                Triple(tempPlaylists, tempCovers, tempConfigs)
            }
            
            playlists.clear()
            playlists.putAll(result.first)
            playlistCovers.clear()
            playlistCovers.putAll(result.second)
            smartPlaylistConfigs.clear()
            smartPlaylistConfigs.addAll(result.third)
            
            updateSmartPlaylists()
        }
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        val prefs = getApplication<Application>().getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
        val names = (prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()).toMutableSet()
        names.add(name)
        prefs.edit()
            .putStringSet("playlist_names", names)
            .putString("playlist_$name", "")
            .apply()
        playlists[name] = emptyList()
    }

    fun createSmartPlaylist(name: String, rule: SmartPlaylistRule, limit: Int, isAdvanced: Boolean = false, advancedRule: SmartRuleNode? = null) {
        if (name.isBlank()) return
        val prefs = getApplication<Application>().getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
        val names = (prefs.getStringSet("smart_playlist_names", emptySet()) ?: emptySet()).toMutableSet()
        names.add(name)
        
        val json = JSONObject()
        json.put("name", name)
        json.put("rule", rule.name)
        json.put("limit", limit)
        json.put("isAdvanced", isAdvanced)
        if (isAdvanced && advancedRule != null) {
            json.put("advancedRule", advancedRule.toJson())
        }
        
        prefs.edit()
            .putStringSet("smart_playlist_names", names)
            .putString("smart_playlist_config_$name", json.toString())
            .apply()
            
        smartPlaylistConfigs.add(SmartPlaylistConfig(name, rule, limit, isAdvanced, advancedRule))
        updateSmartPlaylists()
    }

    fun setPlaylistCover(name: String, imageUriStr: String) {
        val prefs = getApplication<Application>().getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("playlist_cover_$name", imageUriStr).apply()
        playlistCovers[name] = imageUriStr
    }

    fun addSongToPlaylist(playlistName: String, songId: Long) {
        val currentList = playlists[playlistName]?.toMutableList() ?: mutableListOf()
        if (currentList.any { it.id == songId }) return
        val song = localAudioFiles.find { it.id == songId } ?: return
        currentList.add(song)
        playlists[playlistName] = currentList

        val prefs = getApplication<Application>().getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
        val idsStr = currentList.map { it.id }.joinToString(",")
        prefs.edit()
            .putString("playlist_$playlistName", idsStr)
            .apply()
    }

    fun removeSongFromPlaylist(playlistName: String, songId: Long) {
        val currentList = playlists[playlistName]?.toMutableList() ?: return
        currentList.removeAll { it.id == songId }
        playlists[playlistName] = currentList

        val prefs = getApplication<Application>().getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
        val idsStr = currentList.map { it.id }.joinToString(",")
        prefs.edit()
            .putString("playlist_$playlistName", idsStr)
            .apply()
    }

    fun deletePlaylist(name: String) {
        val prefs = getApplication<Application>().getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
        
        val smartNames = (prefs.getStringSet("smart_playlist_names", emptySet()) ?: emptySet()).toMutableSet()
        if (smartNames.contains(name)) {
            smartNames.remove(name)
            prefs.edit()
                .putStringSet("smart_playlist_names", smartNames)
                .remove("smart_playlist_config_$name")
                .apply()
            smartPlaylistConfigs.removeAll { it.name == name }
            smartPlaylists.remove(name)
            return
        }

        val names = (prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()).toMutableSet()
        names.remove(name)

        // Delete local cover file if exists
        val coverPath = prefs.getString("playlist_cover_$name", null)
        if (coverPath != null) {
            try {
                val file = java.io.File(coverPath)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        prefs.edit()
            .putStringSet("playlist_names", names)
            .remove("playlist_$name")
            .remove("playlist_cover_$name")
            .apply()
        playlists.remove(name)
        playlistCovers.remove(name)
    }

    // Queue system
    fun addToQueue(file: AudioFile) {
        val b = browser.value ?: return
        val trackUri = Uri.parse(file.uriString)
        val mediaItem = MediaItem.Builder()
            .setMediaId(file.id.toString())
            .setUri(trackUri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(trackUri)
                    .build()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(file.title)
                    .setArtist(file.artist)
                    .setAlbumTitle(file.album)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()
        b.addMediaItem(mediaItem)
        savePlaybackState()
    }

    fun playNext(file: AudioFile) {
        val b = browser.value ?: return
        val trackUri = Uri.parse(file.uriString)
        val mediaItem = MediaItem.Builder()
            .setMediaId(file.id.toString())
            .setUri(trackUri)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(trackUri)
                    .build()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(file.title)
                    .setArtist(file.artist)
                    .setAlbumTitle(file.album)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()
        
        val currentIndex = b.currentMediaItemIndex
        val nextIndex = if (currentIndex < 0) 0 else currentIndex + 1

        if (nextIndex < b.mediaItemCount) {
            b.addMediaItem(nextIndex, mediaItem)
        } else {
            b.addMediaItem(mediaItem)
        }
        savePlaybackState()
    }

    fun getPlayerQueue(): List<AudioFile> {
        val b = browser.value ?: return emptyList()
        val list = mutableListOf<AudioFile>()
        for (i in 0 until b.mediaItemCount) {
            val item = b.getMediaItemAt(i)
            val id = item.mediaId.toLongOrNull() ?: continue
            val song = localAudioFiles.find { it.id == id }
            if (song != null) {
                list.add(song)
            } else {
                list.add(
                    AudioFile(
                        id = id,
                        title = item.mediaMetadata.title?.toString() ?: "Unknown",
                        artist = item.mediaMetadata.artist?.toString() ?: "Unknown",
                        album = item.mediaMetadata.albumTitle?.toString() ?: "Unknown",
                        duration = 0L,
                        uriString = item.requestMetadata.mediaUri?.toString() ?: ""
                    )
                )
            }
        }
        return list
    }

    fun removeFromQueue(index: Int) {
        val b = browser.value ?: return
        if (index in 0 until b.mediaItemCount) {
            b.removeMediaItem(index)
            savePlaybackState()
        }
    }

    fun clearQueue() {
        val b = browser.value ?: return
        b.clearMediaItems()
        savePlaybackState()
    }

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
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Force jaudiotagger to run in Android mode
                try {
                    org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                // 1. Write metadata tags directly to physical file using jaudiotagger
                val songEntity = audioDao.getAudioFileById(songId)
                var songUriString: String? = null
                
                val writeSuccess = writeMetadataWithTempFile(context, songId, songEntity?.uriString) { audioFile ->
                    val tag = audioFile.getTagOrCreateAndSetDefault()
                    tag.setField(FieldKey.TITLE, title)
                    tag.setField(FieldKey.ARTIST, artist)
                    tag.setField(FieldKey.ALBUM, album)
                    tag.setField(FieldKey.GENRE, genre)
                    if (coverBytes != null) {
                        try {
                            tag.deleteArtworkField()
                            val artwork = AndroidArtwork().apply {
                                setBinaryData(coverBytes)
                                setMimeType(getMimeTypeFromBytes(coverBytes))
                                setPictureType(org.jaudiotagger.tag.reference.PictureTypes.DEFAULT_ID)
                                try {
                                    val options = android.graphics.BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                    }
                                    android.graphics.BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size, options)
                                    if (options.outWidth > 0 && options.outHeight > 0) {
                                        setWidth(options.outWidth)
                                        setHeight(options.outHeight)
                                    } else {
                                        setWidth(800)
                                        setHeight(800)
                                    }
                                } catch (e: Exception) {
                                    setWidth(800)
                                    setHeight(800)
                                }
                            }
                            tag.setField(artwork)
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    }
                    audioFile.tag = tag
                }
                if (!writeSuccess) {
                    throw Exception("Failed to write physical tags")
                }

                // 2. Update metadata in Room Database
                val allEntities = audioDao.getAllAudioFiles()
                val targetEntity = allEntities.find { it.id == songId }
                if (targetEntity != null) {
                    val updatedEntity = targetEntity.copy(
                        title = title,
                        artist = artist,
                        album = album,
                        genre = genre
                    )
                    songUriString = updatedEntity.uriString
                    audioDao.insertAll(listOf(updatedEntity))
                }

                // 3. Clear/Update in-memory artwork cache
                if (coverBytes != null && songUriString != null) {
                    com.kevshupp.kevmusicplayer.ui.screens.albumArtCache.put(songUriString, coverBytes)
                }

                // 4. Update in-memory localAudioFiles on Main thread to instantly update UI
                withContext(Dispatchers.Main) {
                    com.kevshupp.kevmusicplayer.ui.screens.albumArtVersion++
                    val index = localAudioFiles.indexOfFirst { it.id == songId }
                    if (index != -1) {
                        val currentSong = localAudioFiles[index]
                        localAudioFiles[index] = currentSong.copy(
                            title = title,
                            artist = artist,
                            album = album,
                            genre = genre,
                            playCount = currentSong.playCount
                        )
                    }
                    
                    // Update in playlists too
                    playlists.keys.toList().forEach { playlistName ->
                        val list = playlists[playlistName] ?: emptyList()
                        val pIndex = list.indexOfFirst { it.id == songId }
                        if (pIndex != -1) {
                            val newList = list.toMutableList()
                            newList[pIndex] = newList[pIndex].copy(
                                title = title,
                                artist = artist,
                                album = album,
                                genre = genre
                            )
                            playlists[playlistName] = newList
                        }
                    }

                    // Refresh smart playlists
                    updateSmartPlaylists()
                    
                    onSuccess()
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    onError(if (e is Exception) e else Exception(e))
                }
            }
        }
    }

    fun updateAlbumCover(
        context: Context,
        albumName: String,
        coverBytes: ByteArray,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Force jaudiotagger to run in Android mode
                try {
                    org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                // Find all songs in the album (robust, trimmed, case-insensitive match)
                val songsInAlbum = localAudioFiles.filter { it.album.trim().equals(albumName.trim(), ignoreCase = true) }
                if (songsInAlbum.isEmpty()) {
                    throw Exception("No songs found in album $albumName")
                }

                for (song in songsInAlbum) {
                    writeMetadataWithTempFile(context, song.id, song.uriString) { audioFile ->
                        val tag = audioFile.getTagOrCreateAndSetDefault()
                        try {
                            tag.deleteArtworkField()
                            val artwork = AndroidArtwork().apply {
                                setBinaryData(coverBytes)
                                setMimeType(getMimeTypeFromBytes(coverBytes))
                                setPictureType(org.jaudiotagger.tag.reference.PictureTypes.DEFAULT_ID)
                                try {
                                    val options = android.graphics.BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                    }
                                    android.graphics.BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size, options)
                                    if (options.outWidth > 0 && options.outHeight > 0) {
                                        setWidth(options.outWidth)
                                        setHeight(options.outHeight)
                                    } else {
                                        setWidth(800)
                                        setHeight(800)
                                    }
                                } catch (e: Exception) {
                                    setWidth(800)
                                    setHeight(800)
                                }
                            }
                            tag.setField(artwork)
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                        audioFile.tag = tag
                    }

                    // Update in-memory artwork cache
                    com.kevshupp.kevmusicplayer.ui.screens.albumArtCache.put(song.uriString, coverBytes)
                }

                // Force UI update by refreshing the localAudioFiles in-memory list
                withContext(Dispatchers.Main) {
                    com.kevshupp.kevmusicplayer.ui.screens.albumArtVersion++
                    songsInAlbum.forEach { song ->
                        val index = localAudioFiles.indexOfFirst { it.id == song.id }
                        if (index != -1) {
                            val currentSong = localAudioFiles[index]
                            localAudioFiles[index] = currentSong.copy() // Re-assign copy to trigger State update
                        }
                    }
                    
                    // Update in playlists too
                    playlists.keys.toList().forEach { playlistName ->
                        val list = playlists[playlistName] ?: emptyList()
                        var modified = false
                        val newList = list.map { song ->
                            if (song.album.trim().equals(albumName.trim(), ignoreCase = true)) {
                                modified = true
                                song.copy()
                            } else {
                                song
                            }
                        }
                        if (modified) {
                            playlists[playlistName] = newList
                        }
                    }

                    // Refresh smart playlists
                    updateSmartPlaylists()

                    onSuccess()
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    onError(if (e is Exception) e else Exception(e))
                }
            }
        }
    }

    fun updateAlbumMetadata(
        context: Context,
        oldAlbumName: String,
        newAlbumName: String,
        newArtist: String,
        coverBytes: ByteArray? = null,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Force jaudiotagger to run in Android mode
                try {
                    org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                // Find all songs in the old album (robust, trimmed, case-insensitive match)
                val songsInAlbum = localAudioFiles.filter { it.album.trim().equals(oldAlbumName.trim(), ignoreCase = true) }
                if (songsInAlbum.isEmpty()) {
                    throw Exception("No songs found in album $oldAlbumName")
                }

                // 1. Write tags physically for each song
                for (song in songsInAlbum) {
                    writeMetadataWithTempFile(context, song.id, song.uriString) { audioFile ->
                        val tag = audioFile.getTagOrCreateAndSetDefault()
                        tag.setField(FieldKey.ALBUM, newAlbumName)
                        if (newArtist.isNotBlank()) {
                            tag.setField(FieldKey.ARTIST, newArtist)
                        }
                        if (coverBytes != null) {
                            try {
                                tag.deleteArtworkField()
                                val artwork = AndroidArtwork().apply {
                                    setBinaryData(coverBytes)
                                    setMimeType(getMimeTypeFromBytes(coverBytes))
                                    setPictureType(org.jaudiotagger.tag.reference.PictureTypes.DEFAULT_ID)
                                    try {
                                        val options = android.graphics.BitmapFactory.Options().apply {
                                            inJustDecodeBounds = true
                                        }
                                        android.graphics.BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size, options)
                                        if (options.outWidth > 0 && options.outHeight > 0) {
                                            setWidth(options.outWidth)
                                            setHeight(options.outHeight)
                                        } else {
                                            setWidth(800)
                                            setHeight(800)
                                        }
                                    } catch (e: Exception) {
                                        setWidth(800)
                                        setHeight(800)
                                    }
                                }
                                tag.setField(artwork)
                            } catch (e: Throwable) {
                                e.printStackTrace()
                            }
                        }
                        audioFile.tag = tag
                    }

                    // Update in-memory artwork cache if new cover provided
                    if (coverBytes != null) {
                        com.kevshupp.kevmusicplayer.ui.screens.albumArtCache.put(song.uriString, coverBytes)
                    }
                }

                // 2. Update Room database entries
                val allEntities = audioDao.getAllAudioFiles()
                val updatedEntities = mutableListOf<AudioFile>()
                for (song in songsInAlbum) {
                    val entity = allEntities.find { it.id == song.id }
                    if (entity != null) {
                        updatedEntities.add(
                            entity.copy(
                                album = newAlbumName,
                                artist = if (newArtist.isNotBlank()) newArtist else entity.artist
                            )
                        )
                    }
                }
                if (updatedEntities.isNotEmpty()) {
                    audioDao.insertAll(updatedEntities)
                }

                // 3. Update in-memory localAudioFiles on Main thread to instantly update UI
                withContext(Dispatchers.Main) {
                    com.kevshupp.kevmusicplayer.ui.screens.albumArtVersion++
                    
                    songsInAlbum.forEach { song ->
                        val index = localAudioFiles.indexOfFirst { it.id == song.id }
                        if (index != -1) {
                            val currentSong = localAudioFiles[index]
                            localAudioFiles[index] = currentSong.copy(
                                album = newAlbumName,
                                artist = if (newArtist.isNotBlank()) newArtist else currentSong.artist
                            )
                        }
                    }

                    // Update in playlists too
                    playlists.keys.toList().forEach { playlistName ->
                        val list = playlists[playlistName] ?: emptyList()
                        val newList = list.toMutableList()
                        var changed = false
                        newList.indices.forEach { i ->
                            val item = newList[i]
                            if (item.album.trim().equals(oldAlbumName.trim(), ignoreCase = true)) {
                                newList[i] = item.copy(
                                    album = newAlbumName,
                                    artist = if (newArtist.isNotBlank()) newArtist else item.artist
                                )
                                changed = true
                            }
                        }
                        if (changed) {
                            playlists[playlistName] = newList
                        }
                    }

                    // Refresh smart playlists
                    updateSmartPlaylists()
                    
                    onSuccess()
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    onError(if (e is Exception) e else Exception(e))
                }
            }
        }
    }

    fun renameSongFilesToMetadata(
        context: Context,
        onProgress: (current: Int, total: Int, currentName: String) -> Unit,
        onComplete: (successCount: Int, errorCount: Int) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val songsToRename = localAudioFiles.toList()
            val total = songsToRename.size
            var successCount = 0
            var errorCount = 0

            songsToRename.forEachIndexed { index, song ->
                try {
                    val cleanArtist = song.artist.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                    val cleanTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

                    // Only rename if artist and title are not empty/Unknown placeholders
                    val isArtistValid = cleanArtist.isNotEmpty() && !cleanArtist.equals("Unknown Artist", ignoreCase = true)
                    val isTitleValid = cleanTitle.isNotEmpty() && !cleanTitle.equals("Unknown Title", ignoreCase = true)

                    if (isArtistValid && isTitleValid) {
                        val physicalPath = getPhysicalPath(context, song.id, song.uriString)
                        if (!physicalPath.isNullOrBlank()) {
                            val oldFile = File(physicalPath)
                            if (oldFile.exists()) {
                                // Extract track number if available in tags
                                var trackPrefix = ""
                                try {
                                    val audioFile = AudioFileIO.read(oldFile)
                                    val tag = audioFile.tag
                                    val rawTrack = tag?.getFirst(FieldKey.TRACK)?.trim() ?: ""
                                    if (rawTrack.isNotEmpty()) {
                                        val firstPart = rawTrack.split("/")[0].trim()
                                        if (firstPart.toIntOrNull() != null) {
                                            trackPrefix = "$firstPart. "
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }

                                val extension = oldFile.extension.let { if (it.isNotEmpty()) ".$it" else "" }
                                val newFileName = "$trackPrefix$cleanArtist - $cleanTitle$extension"
                                val newFile = File(oldFile.parentFile, newFileName)

                                if (oldFile.absolutePath != newFile.absolutePath) {
                                    withContext(Dispatchers.Main) {
                                        onProgress(index + 1, total, song.title)
                                    }

                                    var renameCompleted = false

                                    // 1. Try updating MediaStore DISPLAY_NAME directly (modern Android way)
                                    try {
                                        val uri = Uri.parse(song.uriString)
                                        val values = android.content.ContentValues().apply {
                                            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, newFileName)
                                        }
                                        val rows = context.contentResolver.update(uri, values, null, null)
                                        if (rows > 0) {
                                            renameCompleted = true
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }

                                    // 2. Fallback to physical Java file renaming
                                    if (!renameCompleted) {
                                        try {
                                            val renamed = oldFile.renameTo(newFile)
                                            if (renamed) {
                                                renameCompleted = true
                                                // Sync MediaStore with the physical change
                                                val values = android.content.ContentValues().apply {
                                                    put(android.provider.MediaStore.Audio.Media.DATA, newFile.absolutePath)
                                                    put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, newFileName)
                                                }
                                                val uri = Uri.parse(song.uriString)
                                                context.contentResolver.update(uri, values, null, null)
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }

                                    if (renameCompleted) {
                                        // Trigger system media scanner for both old and new paths
                                        android.media.MediaScannerConnection.scanFile(
                                            context,
                                            arrayOf(oldFile.absolutePath, newFile.absolutePath),
                                            null
                                        ) { _, _ -> }

                                        successCount++
                                    } else {
                                        errorCount++
                                    }
                                } else {
                                    // Already named correctly
                                    successCount++
                                }
                            } else {
                                errorCount++
                            }
                        } else {
                            errorCount++
                        }
                    } else {
                        // Skip renaming but count as processed
                        successCount++
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    errorCount++
                }
            }

            // Sync app databases & state
            scanFiles(isManual = true)

            withContext(Dispatchers.Main) {
                onComplete(successCount, errorCount)
            }
        }
    }

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
                        if (!list.contains(parentPath)) {
                            list.add(parentPath)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list.sorted()
    }

    override fun onCleared() {
        super.onCleared()
        browser.value?.release()
        browserFuture?.let { MediaBrowser.releaseFuture(it) }
        browser.value = null
    }
}

fun getPhysicalPath(context: android.content.Context, songId: Long, uriString: String? = null): String? {
    val uri = if (!uriString.isNullOrBlank()) {
        Uri.parse(uriString)
    } else {
        android.content.ContentUris.withAppendedId(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            songId
        )
    }
    val projection = arrayOf(android.provider.MediaStore.Audio.Media.DATA)
    return try {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.DATA)
                if (idx != -1) cursor.getString(idx) else null
            } else null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun saveLyricsPhysical(context: android.content.Context, songId: Long, songTitle: String, folderPath: String, lyrics: String) {
    // 1. Save to .lrc file next to the song
    try {
        val cleanTitle = songTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val lrcFile = File(folderPath, "$cleanTitle.lrc")
        lrcFile.writeText(lyrics)
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 2. Save directly inside the song metadata using jaudiotagger
    writeMetadataWithTempFile(context, songId, null) { audioFile ->
        val tag = audioFile.getTagOrCreateAndSetDefault()
        tag.setField(FieldKey.LYRICS, lyrics)
        audioFile.tag = tag
    }
}

fun saveTranslatedLyricsPhysical(context: android.content.Context, songId: Long, songTitle: String, folderPath: String, translatedLyrics: String?) {
    if (translatedLyrics.isNullOrBlank()) return
    try {
        val cleanTitle = songTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val locale = java.util.Locale.getDefault().language
        val lrcFile = File(folderPath, "$cleanTitle.$locale.lrc")
        lrcFile.writeText(translatedLyrics)
    } catch (e: Exception) {
        e.printStackTrace()
    }

    writeMetadataWithTempFile(context, songId, null) { audioFile ->
        val tag = audioFile.getTagOrCreateAndSetDefault()
        val locale = java.util.Locale.getDefault().language
        tag.setField(FieldKey.CUSTOM1, "TRANSLATED_LYRICS_$locale:$translatedLyrics")
        audioFile.tag = tag
    }
}

fun readLocalLrcOrEmbedded(context: android.content.Context, song: AudioFile): String? {
    // 1. Try reading .lrc file next to the song
    try {
        val cleanTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val lrcFile = File(song.folderPath, "$cleanTitle.lrc")
        if (lrcFile.exists() && lrcFile.isFile) {
            val content = lrcFile.readText()
            if (content.isNotBlank()) {
                return content
            }
        }
    } catch (e: java.lang.Exception) {
        e.printStackTrace()
    }

    // 2. Try reading inside the song metadata using jaudiotagger
    try {
        val physicalPath = getPhysicalPath(context, song.id, song.uriString)
        if (!physicalPath.isNullOrBlank()) {
            val f = File(physicalPath)
            if (f.exists() && f.isFile) {
                val audioFile = AudioFileIO.read(f)
                val tag = audioFile.tag
                if (tag != null) {
                    val embeddedLyrics = tag.getFirst(FieldKey.LYRICS)
                    if (!embeddedLyrics.isNullOrBlank()) {
                        return embeddedLyrics
                    }
                }
            }
        }
    } catch (e: java.lang.Exception) {
        e.printStackTrace()
    }

    return null
}

fun readLocalTranslatedLrcOrEmbedded(context: android.content.Context, song: AudioFile): String? {
    val locale = java.util.Locale.getDefault().language
    try {
        val cleanTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val lrcFile = File(song.folderPath, "$cleanTitle.$locale.lrc")
        if (lrcFile.exists() && lrcFile.isFile) {
            val content = lrcFile.readText()
            if (content.isNotBlank()) {
                return content
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    try {
        val physicalPath = getPhysicalPath(context, song.id, song.uriString)
        if (!physicalPath.isNullOrBlank()) {
            val f = File(physicalPath)
            if (f.exists() && f.isFile) {
                val audioFile = AudioFileIO.read(f)
                val tag = audioFile.tag
                if (tag != null) {
                    val embedded = tag.getFirst(FieldKey.CUSTOM1)
                    if (!embedded.isNullOrBlank() && embedded.startsWith("TRANSLATED_LYRICS_$locale:")) {
                        return embedded.substringAfter("TRANSLATED_LYRICS_$locale:")
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

fun getMimeTypeFromBytes(bytes: ByteArray?): String {
    if (bytes == null || bytes.size < 4) return "image/jpeg"
    return if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
        "image/png"
    } else {
        "image/jpeg"
    }
}

fun writeMetadataWithTempFile(context: android.content.Context, songId: Long, uriString: String?, block: (org.jaudiotagger.audio.AudioFile) -> Unit): Boolean {
    val physicalPath = getPhysicalPath(context, songId, uriString) ?: return false
    val uri = if (!uriString.isNullOrBlank()) {
        Uri.parse(uriString)
    } else {
        android.content.ContentUris.withAppendedId(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            songId
        )
    }
    var tempFile: File? = null
    return try {
        // 1. Copy source file to temp file using the same extension so jaudiotagger can detect format
        val extension = File(physicalPath).extension
        val suffix = if (extension.isNotEmpty()) ".$extension" else ""
        tempFile = File(context.cacheDir, "temp_jaudiotagger_${System.currentTimeMillis()}_${songId}$suffix")
        
        android.util.Log.d("MetadataWrite", "Copying original file to temp: $tempFile")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: run {
            android.util.Log.e("MetadataWrite", "Failed to open input stream for URI: $uri")
            return false
        }
        
        // 2. Open and modify tag in temp file (running in Android mode)
        try {
            org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true)
        } catch (t: Throwable) {
            android.util.Log.e("MetadataWrite", "Failed to set jaudiotagger android mode", t)
        }
        val audioFile = AudioFileIO.read(tempFile)
        block(audioFile)
        AudioFileIO.write(audioFile)
        android.util.Log.d("MetadataWrite", "Successfully wrote tags to temp file.")
        
        // 3. Write temp file back to original source
        var writtenDirectly = false
        try {
            val destFile = File(physicalPath)
            tempFile.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            writtenDirectly = true
            android.util.Log.d("MetadataWrite", "Successfully copied temp back directly to physical path: $physicalPath")
        } catch (e: Exception) {
            android.util.Log.w("MetadataWrite", "Failed direct physical write, trying fallback", e)
        }
        
        if (!writtenDirectly) {
            // Fallback to ContentResolver write-truncate
            context.contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: run {
                android.util.Log.e("MetadataWrite", "Fallback openOutputStream returned null")
                return false
            }
            android.util.Log.d("MetadataWrite", "Successfully copied temp back via ContentResolver fallback")
        }
        
        // 4. Force system to scan media
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(physicalPath),
            null
        ) { _, _ -> }
        
        true
    } catch (e: Exception) {
        android.util.Log.e("MetadataWrite", "Exception in writeMetadataWithTempFile", e)
        false
    } finally {
        try {
            tempFile?.delete()
        } catch (e: Exception) {
            // ignore
        }
    }
}

class AndroidArtwork : org.jaudiotagger.tag.images.Artwork {
    private var binaryData: ByteArray = ByteArray(0)
    private var mimeType: String = ""
    private var description: String = ""
    private var height: Int = 0
    private var width: Int = 0
    private var pictureType: Int = 0
    private var imageUrl: String = ""
    private var linked: Boolean = false

    override fun getBinaryData(): ByteArray = binaryData
    override fun setBinaryData(p0: ByteArray) { binaryData = p0 }
    override fun getMimeType(): String = mimeType
    override fun setMimeType(p0: String) { mimeType = p0 }
    override fun getDescription(): String = description
    override fun setDescription(p0: String) { description = p0 }
    override fun getHeight(): Int = height
    override fun setHeight(p0: Int) { height = p0 }
    override fun getWidth(): Int = width
    override fun setWidth(p0: Int) { width = p0 }
    override fun getPictureType(): Int = pictureType
    override fun setPictureType(p0: Int) { pictureType = p0 }
    override fun getImageUrl(): String = imageUrl
    override fun setImageUrl(p0: String) { imageUrl = p0 }
    override fun isLinked(): Boolean = linked
    override fun setLinked(p0: Boolean) { linked = p0 }
    
    override fun setImageFromData(): Boolean {
        return true
    }
    
    override fun getImage(): Any? = null
    
    override fun setFromFile(p0: java.io.File) {
        binaryData = p0.readBytes()
    }
    
    override fun setFromMetadataBlockDataPicture(p0: org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture) {
        binaryData = p0.imageData
        mimeType = p0.mimeType
        description = p0.description
        height = p0.height
        width = p0.width
        pictureType = p0.pictureType
    }
}

