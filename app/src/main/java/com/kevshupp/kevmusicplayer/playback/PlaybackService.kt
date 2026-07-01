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
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@Suppress("DEPRECATION")
class PlaybackService : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + com.kevshupp.kevmusicplayer.data.TelemetryLogger.getExceptionHandler("PlaybackService_Scope"))
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var playWhenRestored = false
    private var isRestoring = false
    private var skipToNextWhenRestored = false
    private var skipToPrevWhenRestored = false
    private var playerListener: Player.Listener? = null

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

        startFadeCheckLoop(player)
        val eqPrefs = getSharedPreferences("equalizer_prefs", android.content.Context.MODE_PRIVATE)
        eqPrefs.registerOnSharedPreferenceChangeListener(eqPrefsListener)
        val settingsPrefs = getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)
        settingsPrefs.registerOnSharedPreferenceChangeListener(settingsPrefsListener)

        playerListener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val title = mediaItem?.mediaMetadata?.title?.toString() ?: ""
                val artist = mediaItem?.mediaMetadata?.artist?.toString() ?: ""
                val uriString = mediaItem?.requestMetadata?.mediaUri?.toString()
                android.util.Log.d("WidgetDebug", "Transition to: $title - $artist, uri: $uriString")
                updateWidgetState(title, artist, player.isPlaying, uriString)
                applyReplayGain(mediaItem)

                // Force recreation of audio effects on track transition to prevent hardware silence issues
                val sessionId = player.audioSessionId
                if (sessionId != 0) {
                    currentAudioSessionId = 0
                    setupAudioEffects(sessionId)
                }
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
                            wakeLock?.acquire()
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

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != 0) {
                    setupAudioEffects(audioSessionId)
                    val prefs = getSharedPreferences("playback_prefs", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putInt("audio_session_id", audioSessionId).apply()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("PlaybackService", "ExoPlayer error: ${error.message}", error)
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    this@PlaybackService,
                    "ExoPlayer_Error",
                    "ErrorCodeName: ${error.errorCodeName}, ErrorCode: ${error.errorCode}",
                    error
                )
            }
        }
        player.addListener(playerListener!!)

        val callback = object : MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val connectionResult = super.onConnect(session, controller)
                val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
                sessionCommands.add(androidx.media3.session.SessionCommand("ACTION_SKIP_NEXT", android.os.Bundle.EMPTY))
                sessionCommands.add(androidx.media3.session.SessionCommand("ACTION_SKIP_PREV", android.os.Bundle.EMPTY))
                
                // Trigger a widget update on controller connection
                val player = session.player
                val title = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
                val artist = player.currentMediaItem?.mediaMetadata?.artist?.toString() ?: ""
                val uriString = player.currentMediaItem?.requestMetadata?.mediaUri?.toString()
                this@PlaybackService.updateWidgetState(title, artist, player.isPlaying, uriString)

                return MediaSession.ConnectionResult.accept(
                    sessionCommands.build(),
                    connectionResult.availablePlayerCommands
                )
            }

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

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: androidx.media3.session.SessionCommand,
                args: android.os.Bundle
            ): ListenableFuture<androidx.media3.session.SessionResult> {
                val player = session.player as? ExoPlayer
                if (player != null) {
                    when (customCommand.customAction) {
                        "ACTION_SKIP_NEXT" -> {
                            performManualSkip(player, next = true)
                            return Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
                        }
                        "ACTION_SKIP_PREV" -> {
                            performManualSkip(player, next = false)
                            return Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
                        }
                    }
                }
                return Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_ERROR_NOT_SUPPORTED))
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

        val filter = android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }

        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
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
                        var isUriReadable = false
                        try {
                            contentResolver.openAssetFileDescriptor(Uri.parse(uriString), "r")?.use {
                                isUriReadable = true
                            }
                        } catch (e: Exception) {
                            // File not found or not readable
                        }

                        if (isUriReadable) {
                            retriever.setDataSource(this@PlaybackService, Uri.parse(uriString))
                            val picture = retriever.embeddedPicture
                            if (picture != null) {
                                val opts = android.graphics.BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true
                                }
                                android.graphics.BitmapFactory.decodeByteArray(picture, 0, picture.size, opts)
                                
                                val targetSize = 200
                                var sampleSize = 1
                                val largestDim = maxOf(opts.outWidth, opts.outHeight)
                                if (largestDim > targetSize) {
                                    sampleSize = Math.round(largestDim.toFloat() / targetSize)
                                }
                                
                                val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                                    inSampleSize = sampleSize
                                }
                                val bitmap = android.graphics.BitmapFactory.decodeByteArray(picture, 0, picture.size, decodeOpts)
                                if (bitmap != null) {
                                    val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
                                    val tmpFile = java.io.File(cacheDir, "current_widget_art_tmp.png")
                                    java.io.FileOutputStream(tmpFile).use { out ->
                                        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
                                    }
                                    if (tmpFile.exists()) {
                                        tmpFile.renameTo(artFile)
                                    }
                                    if (scaledBitmap != bitmap) {
                                        bitmap.recycle()
                                    }
                                    scaledBitmap.recycle()
                                    success = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        e.printStackTrace()
                        com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                            this@PlaybackService,
                            "Widget_Art_Extract",
                            "Failed to extract art from uri $uriString for widget",
                            e
                        )
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    this@PlaybackService,
                    "Widget_State_Update",
                    "Failed to update Glance widget state for $title - $artist",
                    e
                )
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

            val (mediaItems, targetIndex, targetPosition) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val database = com.kevshupp.kevmusicplayer.data.AppDatabase.getDatabase(this@PlaybackService)
                val audioDao = database.audioDao()
                val localAudioFiles = audioDao.getAllAudioFiles()
                val songsMap = localAudioFiles.associateBy { it.id }
                
                val items = mutableListOf<MediaItem>()
                var computedIndex = lastActiveIndex
                var computedPosition = lastPosition

                if (lastSongId != -1L) {
                    if (!lastQueueIdsString.isNullOrEmpty()) {
                        val idStrings = lastQueueIdsString.split(",")
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
                            computedIndex = 0
                        }
                    }
                }

                // Fallback: If nothing was restored or lastSongId was -1, but we have files in database,
                // load all songs and shuffle them, choosing index 0 to start playback!
                if (items.isEmpty() && localAudioFiles.isNotEmpty()) {
                    val shuffledFiles = localAudioFiles.shuffled()
                    shuffledFiles.forEach { song ->
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
                    computedIndex = 0
                    computedPosition = 0L
                }

                Triple(items, computedIndex, computedPosition)
            }

            if (mediaItems.isNotEmpty()) {
                player.setMediaItems(mediaItems)
                val safeIndex = targetIndex.coerceIn(0, mediaItems.size - 1)
                player.seekTo(safeIndex, targetPosition)
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
            }
        } finally {
            isRestoring = false
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = android.app.NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.playback_notification_channel_name),
                    android.app.NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
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
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaLibrarySession

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
        val eqPrefs = getSharedPreferences("equalizer_prefs", android.content.Context.MODE_PRIVATE)
        eqPrefs.unregisterOnSharedPreferenceChangeListener(eqPrefsListener)
        val settingsPrefs = getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)
        settingsPrefs.unregisterOnSharedPreferenceChangeListener(settingsPrefsListener)

        // Remove player listener before releasing the player to avoid deadlocks/callbacks
        playerListener?.let {
            mediaLibrarySession?.player?.removeListener(it)
        }

        fadeJob?.cancel()
        fadeInJob?.cancel()

        // Release Media3 session and player first
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null

        // Release audio effects after player release
        equalizer?.release()
        bassBoost?.release()
        virtualizer?.release()
        loudnessEnhancer?.release()

        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action == android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED) {
                val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                if (device != null) {
                    val settingsPrefs = getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)
                    val isEnabled = settingsPrefs.getBoolean("bluetooth_resume_enabled", false)
                    if (isEnabled) {
                        val resumeAll = settingsPrefs.getBoolean("bluetooth_resume_all", true)
                        val allowedDevices = settingsPrefs.getStringSet("bluetooth_resume_devices", emptySet()) ?: emptySet()
                        
                        val deviceName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                device.name
                            } else null
                        } else {
                            device.name
                        }
                        val deviceAddress = device.address

                        val isAllowed = resumeAll || 
                                (deviceName != null && allowedDevices.contains(deviceName)) || 
                                (deviceAddress != null && allowedDevices.contains(deviceAddress))
                        
                        if (isAllowed) {
                            serviceScope.launch {
                                kotlinx.coroutines.delay(1500L)
                                mediaLibrarySession?.player?.play()
                            }
                        }
                    }
                }
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            triggerAudioEffectsRecreation()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            triggerAudioEffectsRecreation()
        }
    }

    private fun triggerAudioEffectsRecreation() {
        val player = mediaLibrarySession?.player as? ExoPlayer
        val sessionId = player?.audioSessionId ?: 0
        if (sessionId != 0) {
            android.util.Log.d("PlaybackService", "Audio routing changed. Recreating audio effects for session: $sessionId")
            currentAudioSessionId = 0
            setupAudioEffects(sessionId)
        }
    }

    private var equalizer: android.media.audiofx.Equalizer? = null
    private var bassBoost: android.media.audiofx.BassBoost? = null
    private var virtualizer: android.media.audiofx.Virtualizer? = null
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private var currentAudioSessionId: Int = 0
    private var currentReplayGainFactor: Float = 1f

    private val eqPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val player = mediaLibrarySession?.player as? ExoPlayer
        val audioSessionId = player?.audioSessionId ?: 0
        if (audioSessionId != 0) {
            setupAudioEffects(audioSessionId)
        }
    }

    private val settingsPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "normalize_sound") {
            val player = mediaLibrarySession?.player as? ExoPlayer
            val audioSessionId = player?.audioSessionId ?: 0
            if (audioSessionId != 0) {
                setupAudioEffects(audioSessionId)
            }
            applyReplayGain(player?.currentMediaItem)
        }
    }

    private fun setupAudioEffects(audioSessionId: Int) {
        if (audioSessionId == 0) return
        try {
            if (currentAudioSessionId != audioSessionId) {
                try { equalizer?.release() } catch (e: Exception) {}
                equalizer = null
                try { bassBoost?.release() } catch (e: Exception) {}
                bassBoost = null
                try { virtualizer?.release() } catch (e: Exception) {}
                virtualizer = null
                try { loudnessEnhancer?.release() } catch (e: Exception) {}
                loudnessEnhancer = null
                currentAudioSessionId = audioSessionId
            }
            
            val prefs = getSharedPreferences("equalizer_prefs", android.content.Context.MODE_PRIVATE)
            
            // Equalizer
            try {
                val eqEnabled = prefs.getBoolean("eq_enabled", false)
                if (eqEnabled) {
                    if (equalizer == null) {
                        equalizer = android.media.audiofx.Equalizer(0, audioSessionId)
                    }
                    equalizer?.enabled = true
                    
                    val eq = equalizer
                    if (eq != null) {
                        val bandsStr = prefs.getString("eq_bands", null) ?: "0,0,0,0,0"
                        val bands = bandsStr.split(",").mapNotNull { it.toIntOrNull() }
                        val numBands = eq.numberOfBands.toInt()
                        for (i in 0 until minOf(numBands, bands.size)) {
                            try {
                                val level = bands[i].coerceIn(eq.bandLevelRange[0].toInt(), eq.bandLevelRange[1].toInt())
                                eq.setBandLevel(i.toShort(), level.toShort())
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                } else {
                    try { equalizer?.release() } catch (e: Exception) {}
                    equalizer = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(this, "AudioEffects_EQ", "Failed to setup Equalizer", e)
                try { equalizer?.release() } catch (ex: Exception) {}
                equalizer = null
            }

            // Bass Boost
            try {
                val bbEnabled = prefs.getBoolean("bb_enabled", false)
                if (bbEnabled) {
                    val bbStrength = prefs.getInt("bb_strength", 0).toShort()
                    if (bassBoost == null) {
                        bassBoost = android.media.audiofx.BassBoost(0, audioSessionId)
                    }
                    bassBoost?.enabled = true
                    bassBoost?.setStrength(bbStrength)
                } else {
                    try { bassBoost?.release() } catch (e: Exception) {}
                    bassBoost = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(this, "AudioEffects_BB", "Failed to setup Bass Boost", e)
                try { bassBoost?.release() } catch (ex: Exception) {}
                bassBoost = null
            }

            // Virtualizer
            try {
                val virtEnabled = prefs.getBoolean("virt_enabled", false)
                if (virtEnabled) {
                    val virtStrength = prefs.getInt("virt_strength", 0).toShort()
                    if (virtualizer == null) {
                        virtualizer = android.media.audiofx.Virtualizer(0, audioSessionId)
                    }
                    virtualizer?.enabled = true
                    virtualizer?.setStrength(virtStrength)
                } else {
                    try { virtualizer?.release() } catch (e: Exception) {}
                    virtualizer = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(this, "AudioEffects_Virt", "Failed to setup Virtualizer", e)
                try { virtualizer?.release() } catch (ex: Exception) {}
                virtualizer = null
            }

            // Loudness Normalization
            try {
                val settingsPrefs = getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)
                val normalizeEnabled = settingsPrefs.getBoolean("normalize_sound", false)
                if (normalizeEnabled) {
                    if (loudnessEnhancer == null) {
                        loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(audioSessionId)
                    }
                    loudnessEnhancer?.enabled = true
                    try {
                        loudnessEnhancer?.setTargetGain(800)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        android.util.Log.w("PlaybackService", "Failed to set target gain on LoudnessEnhancer: ${e.message}")
                    }
                } else {
                    try { loudnessEnhancer?.release() } catch (e: Exception) {}
                    loudnessEnhancer = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(this, "AudioEffects_Loudness", "Failed to setup LoudnessEnhancer", e)
                try { loudnessEnhancer?.release() } catch (ex: Exception) {}
                loudnessEnhancer = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(this, "AudioEffects_Setup", "General failure in setupAudioEffects", e)
        }
    }

    private fun applyReplayGain(mediaItem: MediaItem?) {
        val songIdStr = mediaItem?.mediaId
        if (songIdStr == null) {
            currentReplayGainFactor = 1f
            val player = mediaLibrarySession?.player as? ExoPlayer
            player?.volume = 1f
            return
        }
        val songId = songIdStr.toLongOrNull()
        if (songId == null) {
            currentReplayGainFactor = 1f
            val player = mediaLibrarySession?.player as? ExoPlayer
            player?.volume = 1f
            return
        }

        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val database = com.kevshupp.kevmusicplayer.data.AppDatabase.getDatabase(this@PlaybackService)
            val song = database.audioDao().getAudioFileById(songId)
            var gain = song?.replayGain

            val settingsPrefs = getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)
            val normalizeEnabled = settingsPrefs.getBoolean("normalize_sound", false)

            if (normalizeEnabled) {
                if (gain == null && song != null) {
                    try {
                        val path = getPhysicalPath(this@PlaybackService, song.id, song.uriString)
                        if (!path.isNullOrBlank()) {
                            val file = java.io.File(path)
                            if (file.exists() && file.isFile) {
                                try {
                                    org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true)
                                } catch (t: Throwable) {}
                                val audioFile = if (file.extension.lowercase() in listOf("m4a", "mp4")) {
                                    try {
                                        val reader = SafeMp4FileReader()
                                        val readAudio = reader.read(file)
                                        readAudio.setExt(file.extension.lowercase())
                                        readAudio
                                    } catch (e: Exception) {
                                        org.jaudiotagger.audio.AudioFileIO.read(file)
                                    }
                                } else {
                                    org.jaudiotagger.audio.AudioFileIO.read(file)
                                }
                                val tag = audioFile.tag
                                if (tag != null) {
                                    var gainStr = tag.getFirst("REPLAYGAIN_TRACK_GAIN")
                                    if (gainStr.isNullOrEmpty()) gainStr = tag.getFirst("replaygain_track_gain")
                                    if (gainStr.isNullOrEmpty()) gainStr = tag.getFirst("REPLAYGAIN_ALBUM_GAIN")
                                    if (gainStr.isNullOrEmpty()) gainStr = tag.getFirst("replaygain_album_gain")
                                    if (gainStr.isNullOrEmpty()) gainStr = tag.getFirst("LOUDNESS")
                                    if (gainStr.isNullOrEmpty()) gainStr = tag.getFirst("loudness")
                                    if (!gainStr.isNullOrEmpty()) {
                                        val cleanGain = gainStr.replace("dB", "").trim()
                                        gain = cleanGain.toFloatOrNull()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                            this@PlaybackService,
                            "ReplayGain_Read",
                            "Failed to read/resolve ReplayGain for songId ${song.id}",
                            e
                        )
                    }

                    // Save the fetched/resolved gain to database (use 0f as marker for no gain found)
                    try {
                        val finalGain = gain ?: 0f
                        val updatedSong = song.copy(replayGain = finalGain)
                        database.audioDao().insertAll(listOf(updatedSong))
                        gain = finalGain
                    } catch (e: Exception) {
                        e.printStackTrace()
                        com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                            this@PlaybackService,
                            "ReplayGain_DB_Update",
                            "Failed to save ReplayGain to DB for songId ${song.id}",
                            e
                        )
                    }
                }

                if (gain != null && gain != 0f) {
                    val rawFactor = Math.pow(10.0, gain.toDouble() / 20.0).toFloat()
                    currentReplayGainFactor = rawFactor.coerceIn(0.15f, 1.0f)
                    android.util.Log.d("ReplayGain", "Applied ReplayGain: $gain dB, factor: $currentReplayGainFactor for song: ${song?.title}")
                } else {
                    currentReplayGainFactor = 1f
                }
            } else {
                currentReplayGainFactor = 1f
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val player = mediaLibrarySession?.player as? ExoPlayer
                if (player != null && !isFadingIn) {
                    player.volume = currentReplayGainFactor
                }
            }
        }
    }

    private var fadeJob: kotlinx.coroutines.Job? = null
    private var isFadingIn = false
    private var fadeInJob: kotlinx.coroutines.Job? = null
    private var manualSkipJob: kotlinx.coroutines.Job? = null

    private fun startFadeCheckLoop(player: ExoPlayer) {
        fadeJob?.cancel()
        fadeJob = serviceScope.launch {
            val playbackPrefs = getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)
            var lastSkippedMediaItem: MediaItem? = null
            while (true) {
                kotlinx.coroutines.delay(150)
                if (!player.isPlaying || isFadingIn) continue
                
                val crossfadeSeconds = playbackPrefs.getInt("crossfade_duration", 0)
                if (crossfadeSeconds <= 0) {
                    if (player.volume != currentReplayGainFactor && !isFadingIn) {
                        player.volume = currentReplayGainFactor
                    }
                    continue
                }
                
                val duration = player.duration
                val position = player.currentPosition
                val currentItem = player.currentMediaItem
                if (duration > 0) {
                    val remainingMs = duration - position
                    val crossfadeMs = crossfadeSeconds * 1000L
                    
                    if (remainingMs <= crossfadeMs) {
                        val progress = remainingMs.toFloat() / crossfadeMs
                        player.volume = progress.coerceIn(0f, 1f) * currentReplayGainFactor
                        
                        if (remainingMs <= 200L && player.hasNextMediaItem() && currentItem != lastSkippedMediaItem) {
                            lastSkippedMediaItem = currentItem
                            player.seekToNextMediaItem()
                            fadeNewTrackIn(player, crossfadeMs)
                        }
                    } else {
                        if (player.volume != currentReplayGainFactor && !isFadingIn) {
                            player.volume = currentReplayGainFactor
                        }
                    }
                }
            }
        }
    }

    private fun fadeNewTrackIn(player: ExoPlayer, crossfadeMs: Long) {
        fadeInJob?.cancel()
        isFadingIn = true
        fadeInJob = serviceScope.launch {
            try {
                player.volume = 0f
                val steps = 20
                val delayMs = (crossfadeMs / steps).coerceAtLeast(10L)
                for (i in 1..steps) {
                    kotlinx.coroutines.delay(delayMs)
                    if (!player.isPlaying) break
                    player.volume = (i.toFloat() / steps) * currentReplayGainFactor
                }
                player.volume = currentReplayGainFactor
            } finally {
                isFadingIn = false
            }
        }
    }

    private fun performManualSkip(player: ExoPlayer, next: Boolean) {
        fadeInJob?.cancel()
        manualSkipJob?.cancel()
        manualSkipJob = serviceScope.launch {
            val fadeOutSteps = 10
            val originalVolume = player.volume
            for (i in fadeOutSteps downTo 0) {
                player.volume = (i.toFloat() / fadeOutSteps) * originalVolume
                kotlinx.coroutines.delay(30)
            }
            
            if (next) {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                }
            } else {
                if (player.hasPreviousMediaItem()) {
                    player.seekToPreviousMediaItem()
                }
            }
            
            player.volume = 0f
            for (i in 1..fadeOutSteps) {
                kotlinx.coroutines.delay(30)
                player.volume = (i.toFloat() / fadeOutSteps) * currentReplayGainFactor
            }
            player.volume = currentReplayGainFactor
        }
    }
}
