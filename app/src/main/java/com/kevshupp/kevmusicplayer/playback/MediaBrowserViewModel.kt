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
import com.kevshupp.kevmusicplayer.data.AudioFileEntity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.kevshupp.kevmusicplayer.ui.screens.fetchLyricsFromLrcLib

class MediaBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    val browser = mutableStateOf<MediaBrowser?>(null)
    val localAudioFiles = mutableStateListOf<AudioFile>()
    val enabledTabs = mutableStateOf(listOf("Songs", "Albums", "Artists", "Genres", "Folders", "Playlists"))
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
                        lyrics = entity.lyrics
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

        val prefs = getApplication<Application>().getSharedPreferences("playback_prefs", android.content.Context.MODE_PRIVATE)
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
                    val fetched = fetchLyricsFromLrcLib(song.artist, song.title)
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
                
                if (scannedFiles.isEmpty() && !isManual) {
                    // Automatic scan returned empty. This is a false negative due to startup delays,
                    // content provider load lag, or transient lifecycle issues.
                    // Keep the cache intact and display the saved songs so the user is never met with an empty screen!
                    return@launch
                }
                
                // 2. Map scanned files to database entities, preserving custom edited lyrics
                val existingEntities = audioDao.getAllAudioFiles().associateBy { it.id }
                val entities = scannedFiles.map { file ->
                    val existing = existingEntities[file.id]
                    AudioFileEntity(
                        id = file.id,
                        title = file.title,
                        artist = file.artist,
                        album = file.album,
                        genre = file.genre,
                        duration = file.duration,
                        uriString = file.uriString,
                        folderPath = file.folderPath,
                        folderName = file.folderName,
                        lyrics = existing?.lyrics ?: file.lyrics
                    )
                }
                
                // 3. Write to Room database (single transaction cache sync)
                // Clean up old files that were deleted from device storage
                val scannedIds = scannedFiles.map { it.id }
                if (scannedIds.isNotEmpty()) {
                    audioDao.keepOnlyIds(scannedIds)
                    audioDao.insertAll(entities)
                } else {
                    audioDao.deleteAll()
                }
                
                // 4. Update compose list with restored lyrics
                localAudioFiles.clear()
                localAudioFiles.addAll(scannedFiles.map { file ->
                    file.copy(lyrics = existingEntities[file.id]?.lyrics)
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
                }
                loadPlaylists() // Sync playlists cache
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Playlists system
    val playlists = androidx.compose.runtime.mutableStateMapOf<String, List<AudioFile>>()

    fun loadPlaylists() {
        playlists.clear()
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
        prefs.edit()
            .putStringSet("playlist_names", names)
            .remove("playlist_$name")
            .apply()
        playlists.remove(name)
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

    override fun onCleared() {
        super.onCleared()
        browserFuture?.let { MediaBrowser.releaseFuture(it) }
        browser.value = null
    }
}
