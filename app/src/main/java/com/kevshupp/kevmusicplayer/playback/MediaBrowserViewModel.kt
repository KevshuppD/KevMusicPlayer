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

    private val audioScanner = AudioScanner(application)
    private val database = AppDatabase.getDatabase(application)
    private val audioDao = database.audioDao()

    init {
        // Instantly load the cached songs from SQLite database to ensure the screen is NEVER empty upon startup!
        viewModelScope.launch {
            try {
                val cachedEntities = audioDao.getAllAudioFiles()
                val cachedFiles = cachedEntities.map { entity ->
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
                        translatedLyrics = entity.translatedLyrics
                    )
                }
                if (cachedFiles.isNotEmpty()) {
                    localAudioFiles.clear()
                    localAudioFiles.addAll(cachedFiles)
                    loadPlaylists()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun connect() {
        if (browser.value != null) return

        val sessionToken = SessionToken(
            getApplication(),
            ComponentName(getApplication(), PlaybackService::class.java)
        )
        browserFuture = MediaBrowser.Builder(getApplication(), sessionToken).buildAsync()
        
        browserFuture?.addListener({
            try {
                val connectedBrowser = browserFuture?.get()
                browser.value = connectedBrowser

                connectedBrowser?.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        savePlaybackState()
                        fetchLyricsForCurrentSong()
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

    fun savePlaybackState() {
        val b = browser.value ?: return
        val prefs = getApplication<Application>().getSharedPreferences("playback_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("last_shuffle_enabled", b.shuffleModeEnabled).apply()

        val currentItem = b.currentMediaItem ?: return
        val id = currentItem.mediaId.toLongOrNull() ?: return
        val position = b.currentPosition
        
        // Save the active index and all song IDs in the media item list
        val activeIndex = b.currentMediaItemIndex
        val mediaIds = mutableListOf<String>()
        for (i in 0 until b.mediaItemCount) {
            val item = b.getMediaItemAt(i)
            mediaIds.add(item.mediaId)
        }
        val mediaIdsString = mediaIds.joinToString(",")

        prefs.edit()
            .putLong("last_song_id", id)
            .putLong("last_position", position)
            .putInt("last_active_index", activeIndex)
            .putString("last_queue_ids", mediaIdsString)
            .apply()
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

                if (!lastQueueIdsString.isNullOrEmpty()) {
                    val idStrings = lastQueueIdsString.split(",")
                    val mediaItems = mutableListOf<MediaItem>()
                    
                    idStrings.forEach { idStr ->
                        val idLong = idStr.toLongOrNull()
                        if (idLong != null) {
                            val song = localAudioFiles.find { it.id == idLong }
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
                                mediaItems.add(mediaItem)
                            }
                        }
                    }

                    if (mediaItems.isNotEmpty()) {
                        b.setMediaItems(mediaItems)
                        val safeIndex = lastActiveIndex.coerceIn(0, mediaItems.size - 1)
                        b.seekTo(safeIndex, lastPosition)
                        b.prepare()
                        return@launch
                    }
                }

                val song = localAudioFiles.find { it.id == lastSongId }
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
                    
                    b.setMediaItem(mediaItem)
                    b.seekTo(lastPosition)
                    b.prepare()
                }
            }
        }
    }

    fun fetchLyricsForCurrentSong() {
        val b = browser.value ?: return
        val currentItem = b.currentMediaItem ?: return
        val id = currentItem.mediaId.toLongOrNull() ?: return
        
        viewModelScope.launch {
            try {
                var attempts = 0
                while (localAudioFiles.isEmpty() && attempts < 15) {
                    kotlinx.coroutines.delay(100)
                    attempts++
                }
                val song = localAudioFiles.find { it.id == id } ?: return@launch
                if (song.lyrics.isNullOrBlank()) {
                    val fetched = LyricsRepository.fetchLyricsFromLrcLib(song.artist, song.title)
                    if (!fetched.isNullOrEmpty()) {
                        updateSongLyrics(id, fetched)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun scanFiles(isManual: Boolean = false) {
        viewModelScope.launch {
            try {
                // 1. Perform background MediaStore scanning
                val scannedFiles = audioScanner.scanAudioFiles()
                
                if (scannedFiles == null) {
                    // Scan failed (exception or permissions). Do NOT touch the cache!
                    return@launch
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
                
                if (filteredScanned.isEmpty() && !isManual) {
                    // Automatic scan returned empty. This is a false negative due to startup delays,
                    // content provider load lag, or transient lifecycle issues.
                    // Keep the cache intact and display the saved songs so the user is never met with an empty screen!
                    return@launch
                }
                
                // 2. Map scanned files to database entities, preserving custom edited lyrics
                val existingEntities = audioDao.getAllAudioFiles().associateBy { it.id }
                val entities = filteredScanned.map { file ->
                    val existing = existingEntities[file.id]
                    file.copy(
                        lyrics = existing?.lyrics ?: file.lyrics,
                        translatedLyrics = existing?.translatedLyrics ?: file.translatedLyrics
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
                
                // 4. Update compose list with restored lyrics
                localAudioFiles.clear()
                localAudioFiles.addAll(filteredScanned.map { file ->
                    file.copy(
                        lyrics = existingEntities[file.id]?.lyrics,
                        translatedLyrics = existingEntities[file.id]?.translatedLyrics
                    )
                })
                loadPlaylists()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun playFile(file: AudioFile, customQueue: List<AudioFile>? = null) {
        val b = browser.value ?: return
        
        val queue = customQueue ?: localAudioFiles
        val index = queue.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
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
                val index = localAudioFiles.indexOfFirst { it.id == id }
                if (index != -1) {
                    val file = localAudioFiles[index]
                    localAudioFiles[index] = file.copy(lyrics = newLyrics)
                    
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
                
                // Export backup folder setting if present
                val backupDirUri = settingsPrefs.getString("backup_dir_uri", null)
                if (backupDirUri != null) {
                    settingsJson.put("backup_dir_uri", backupDirUri)
                }
                
                json.put("settings", settingsJson)

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

                // 1. Restore settings
                if (json.has("settings")) {
                    val settingsJson = json.getJSONObject("settings")
                    val settingsPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                    val edit = settingsPrefs.edit()
                    if (settingsJson.has("language")) edit.putString("language", settingsJson.getString("language"))
                    if (settingsJson.has("app_theme")) edit.putString("app_theme", settingsJson.getString("app_theme"))
                    if (settingsJson.has("refresh_rate")) edit.putString("refresh_rate", settingsJson.getString("refresh_rate"))
                    if (settingsJson.has("disable_animations")) edit.putBoolean("disable_animations", settingsJson.getBoolean("disable_animations"))
                    if (settingsJson.has("backup_dir_uri")) edit.putString("backup_dir_uri", settingsJson.getString("backup_dir_uri"))
                    edit.apply()
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
                        translatedLyrics = entity.translatedLyrics
                    )
                }

                withContext(Dispatchers.Main) {
                    localAudioFiles.clear()
                    localAudioFiles.addAll(updatedFiles)
                    loadPlaylists() // Reload playlists state instantly!
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

    fun loadPlaylists() {
        playlists.clear()
        playlistCovers.clear()
        val prefs = getApplication<Application>().getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
        val names = prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()
        names.forEach { name ->
            val idsStr = prefs.getString("playlist_$name", "") ?: ""
            if (idsStr.isNotBlank()) {
                val ids = idsStr.split(",").mapNotNull { it.toLongOrNull() }
                val songs = ids.mapNotNull { id -> localAudioFiles.find { it.id == id } }
                playlists[name] = songs
            } else {
                playlists[name] = emptyList()
            }

            val cover = prefs.getString("playlist_cover_$name", null)
            if (cover != null) {
                playlistCovers[name] = cover
            }
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
        
        val nextIndex = b.currentMediaItemIndex + 1
        if (nextIndex <= b.mediaItemCount) {
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
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Write metadata tags directly to physical file using jaudiotagger
                val physicalPath = getPhysicalPath(context, songId)
                if (!physicalPath.isNullOrBlank()) {
                    val file = File(physicalPath)
                    if (file.exists()) {
                        val audioFile = AudioFileIO.read(file)
                        val tag = audioFile.tag ?: audioFile.createDefaultTag()
                        tag.setField(FieldKey.TITLE, title)
                        tag.setField(FieldKey.ARTIST, artist)
                        tag.setField(FieldKey.ALBUM, album)
                        tag.setField(FieldKey.GENRE, genre)
                        audioFile.tag = tag
                        AudioFileIO.write(audioFile)
                    }
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
                    audioDao.insertAll(listOf(updatedEntity))
                }

                // 3. Update in-memory localAudioFiles on Main thread to instantly update UI
                withContext(Dispatchers.Main) {
                    val index = localAudioFiles.indexOfFirst { it.id == songId }
                    if (index != -1) {
                        val currentSong = localAudioFiles[index]
                        localAudioFiles[index] = currentSong.copy(
                            title = title,
                            artist = artist,
                            album = album,
                            genre = genre
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
                    
                    onSuccess()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e)
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
    try {
        val physicalPath = getPhysicalPath(context, songId)
        if (!physicalPath.isNullOrBlank()) {
            val audioFile = AudioFileIO.read(File(physicalPath))
            val tag = audioFile.tag ?: audioFile.createDefaultTag()
            tag.setField(FieldKey.LYRICS, lyrics)
            audioFile.tag = tag
            AudioFileIO.write(audioFile)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
