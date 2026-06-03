package com.kevshupp.kevmusicplayer.playback

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.DefaultMediaNotificationProvider
import com.kevshupp.kevmusicplayer.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.kevshupp.kevmusicplayer.widget.MusicWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PlaybackService : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var playWhenRestored = false
    private var isRestoring = false
    private var skipToNextWhenRestored = false
    private var skipToPrevWhenRestored = false

    companion object {
        const val CHANNEL_ID = "playback_channel"
    }

    override fun onCreate() {
        super.onCreate()
        
        val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "KevMusicPlayer:PlaybackWakeLock")
        
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true // handle audio focus
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        serviceScope.launch {
            restorePlaybackState(player)
        }

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val title = mediaItem?.mediaMetadata?.title?.toString() ?: ""
                val artist = mediaItem?.mediaMetadata?.artist?.toString() ?: ""
                val uriString = mediaItem?.requestMetadata?.mediaUri?.toString()
                android.util.Log.d("WidgetDebug", "Transition to: $title - $artist, uri: $uriString")
                updateWidgetState(title, artist, player.isPlaying, uriString)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val title = mediaMetadata.title?.toString() ?: ""
                val artist = mediaMetadata.artist?.toString() ?: ""
                val uriString = player.currentMediaItem?.requestMetadata?.mediaUri?.toString()
                android.util.Log.d("WidgetDebug", "Metadata loaded: $title - $artist, uri: $uriString")
                updateWidgetState(title, artist, player.isPlaying, uriString)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val title = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
                val artist = player.currentMediaItem?.mediaMetadata?.artist?.toString() ?: ""
                val uriString = player.currentMediaItem?.requestMetadata?.mediaUri?.toString()
                android.util.Log.d("WidgetDebug", "Is playing changed: $isPlaying, $title - $artist")
                updateWidgetState(title, artist, isPlaying, uriString)

                if (isPlaying) {
                    try {
                        if (wakeLock?.isHeld == false) {
                            wakeLock?.acquire(10 * 60 * 1000L /* 10 minutes timeout */)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    try {
                        if (wakeLock?.isHeld == true) {
                            wakeLock?.release()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        })

        val callback = object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val rootItem = MediaItem.Builder()
                    .setMediaId("ROOT_ID")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setTitle(getString(R.string.app_name))
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                val updatedItems = mediaItems.map { item ->
                    val resolvedUri = item.requestMetadata.mediaUri 
                        ?: item.localConfiguration?.uri
                        ?: Uri.parse("content://media/external/audio/media/${item.mediaId}")
                    item.buildUpon()
                        .setUri(resolvedUri)
                        .setMediaMetadata(item.mediaMetadata)
                        .setRequestMetadata(item.requestMetadata)
                        .build()
                }.toMutableList()
                return Futures.immediateFuture(updatedItems)
            }
        }

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = intent?.let {
            android.app.PendingIntent.getActivity(
                this,
                0,
                it,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val sessionBuilder = MediaLibrarySession.Builder(this, player, callback)
        if (pendingIntent != null) {
            sessionBuilder.setSessionActivity(pendingIntent)
        }
        mediaLibrarySession = sessionBuilder.build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_notification_channel_name)
                .build()
        )
    }

    private fun updateWidgetState(title: String, artist: String, isPlaying: Boolean, uriString: String? = null) {
        android.util.Log.d("WidgetDebug", "updateWidgetState: title = $title, artist = $artist, isPlaying = $isPlaying, uri = $uriString")
        
        // Launch on Main dispatcher to ensure high-priority execution, performing file I/O on IO thread
        serviceScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val artFile = java.io.File(cacheDir, "current_widget_art.png")
                if (uriString != null) {
                    val retriever = android.media.MediaMetadataRetriever()
                    var success = false
                    try {
                        retriever.setDataSource(this@PlaybackService, Uri.parse(uriString))
                        val picture = retriever.embeddedPicture
                        if (picture != null) {
                            artFile.writeBytes(picture)
                            success = true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        try {
                            retriever.release()
                        } catch (e: Exception) {}
                    }
                    if (!success) {
                        if (artFile.exists()) artFile.delete()
                    }
                } else {
                    if (artFile.exists()) artFile.delete()
                }
            }

            try {
                val manager = GlanceAppWidgetManager(this@PlaybackService)
                val glanceIds = manager.getGlanceIds(MusicWidget::class.java)
                android.util.Log.d("WidgetDebug", "Found ${glanceIds.size} active widget instances to update")
                glanceIds.forEach { glanceId ->
                    updateAppWidgetState(this@PlaybackService, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[stringPreferencesKey("title")] = title
                            this[stringPreferencesKey("artist")] = artist
                            this[booleanPreferencesKey("isPlaying")] = isPlaying
                        }
                    }
                    MusicWidget().update(this@PlaybackService, glanceId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun restorePlaybackState(player: Player) {
        if (isRestoring || player.mediaItemCount > 0) return
        isRestoring = true
        try {
            val prefs = getSharedPreferences("playback_prefs", android.content.Context.MODE_PRIVATE)
            val lastShuffleEnabled = prefs.getBoolean("last_shuffle_enabled", false)
            player.shuffleModeEnabled = lastShuffleEnabled

            val lastSongId = prefs.getLong("last_song_id", -1L)
            val lastPosition = prefs.getLong("last_position", 0L)
            val lastActiveIndex = prefs.getInt("last_active_index", 0)
            val lastQueueIdsString = prefs.getString("last_queue_ids", null)

            if (lastSongId != -1L) {
                val database = com.kevshupp.kevmusicplayer.data.AppDatabase.getDatabase(this)
                val audioDao = database.audioDao()
                val localAudioFiles = audioDao.getAllAudioFiles()

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
                        player.setMediaItems(mediaItems)
                        val safeIndex = lastActiveIndex.coerceIn(0, mediaItems.size - 1)
                        player.seekTo(safeIndex, lastPosition)
                        player.prepare()
                        
                        if (skipToNextWhenRestored) {
                            skipToNextWhenRestored = false
                            if (player.hasNextMediaItem()) {
                                player.seekToNextMediaItem()
                            }
                        } else if (skipToPrevWhenRestored) {
                            skipToPrevWhenRestored = false
                            if (player.hasPreviousMediaItem()) {
                                player.seekToPreviousMediaItem()
                            }
                        }

                        if (playWhenRestored) {
                            player.play()
                            playWhenRestored = false
                        }
                        return
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
                    
                    player.setMediaItem(mediaItem)
                    player.seekTo(lastPosition)
                    player.prepare()
                    
                    if (playWhenRestored) {
                        player.play()
                        playWhenRestored = false
                    }
                }
            }
        } finally {
            isRestoring = false
        }
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (action != null) {
            val player = mediaLibrarySession?.player
            if (player != null) {
                when (action) {
                    "com.kevshupp.kevmusicplayer.action.PLAY_PAUSE" -> {
                        if (player.isPlaying) {
                            player.pause()
                        } else {
                            if (player.mediaItemCount > 0) {
                                player.play()
                            } else {
                                playWhenRestored = true
                                serviceScope.launch {
                                    restorePlaybackState(player)
                                }
                            }
                        }
                    }
                    "com.kevshupp.kevmusicplayer.action.NEXT" -> {
                        if (player.mediaItemCount > 0) {
                            if (player.hasNextMediaItem()) {
                                player.seekToNextMediaItem()
                            }
                        } else {
                            skipToNextWhenRestored = true
                            playWhenRestored = true
                            serviceScope.launch {
                                restorePlaybackState(player)
                            }
                        }
                    }
                    "com.kevshupp.kevmusicplayer.action.PREVIOUS" -> {
                        if (player.mediaItemCount > 0) {
                            if (player.hasPreviousMediaItem()) {
                                player.seekToPreviousMediaItem()
                            }
                        } else {
                            skipToPrevWhenRestored = true
                            playWhenRestored = true
                            serviceScope.launch {
                                restorePlaybackState(player)
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = mediaLibrarySession?.player
        if (player != null) {
            if (!player.playWhenReady || player.mediaItemCount == 0) {
                stopSelf()
            }
        } else {
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaLibrarySession

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }
}
