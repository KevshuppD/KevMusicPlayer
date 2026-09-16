package com.kevshupp.kevmusicplayer.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.kevshupp.kevmusicplayer.R
import com.kevshupp.kevmusicplayer.data.AppDatabase
import com.kevshupp.kevmusicplayer.data.PreferenceConstants
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import com.kevshupp.kevmusicplayer.playback.managers.AudioEffectsManager
import com.kevshupp.kevmusicplayer.playback.managers.AudioFocusHelper
import com.kevshupp.kevmusicplayer.playback.managers.VolumeFadeHelper
import com.kevshupp.kevmusicplayer.playback.managers.WidgetStateHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow

@Suppress("DEPRECATION")
class PlaybackService : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + TelemetryLogger.getExceptionHandler("PlaybackService_Scope"))
    private var wakeLock: PowerManager.WakeLock? = null
    private var playWhenRestored = false
    private var isRestoring = false
    private var skipToNextWhenRestored = false
    private var skipToPrevWhenRestored = false
    private var playerListener: Player.Listener? = null

    private var currentReplayGainFactor: Float = 1f

    private val audioFocusHelper by lazy {
        AudioFocusHelper(
            context = this,
            playerProvider = { mediaLibrarySession?.player as? ExoPlayer },
            getReplayGainFactor = { currentReplayGainFactor },
            isFadingIn = { volumeFadeHelper.isFadingIn }
        )
    }

    private val audioEffectsManager by lazy {
        AudioEffectsManager(this)
    }

    private val widgetStateHelper by lazy {
        WidgetStateHelper(this, serviceScope)
    }

    private val volumeFadeHelper by lazy {
        VolumeFadeHelper(
            context = this,
            scope = serviceScope,
            getReplayGainFactor = { currentReplayGainFactor }
        )
    }

    companion object {
        const val CHANNEL_ID = "playback_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KevMusicPlayer:PlaybackWakeLock")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,  // minBufferMs (30s)
                90_000,  // maxBufferMs (90s)
                2_000,   // bufferForPlaybackMs (2.0s)
                4_000    // bufferForPlaybackAfterRebufferMs (4.0s)
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }.setEnableAudioTrackPlaybackParams(true)

        val settingsPrefs = PreferenceConstants.getSettingsPrefs(this)
        val pauseOnNoisy = settingsPrefs.getBoolean(PreferenceConstants.KEY_PAUSE_ON_HEADPHONE_UNPLUG, true)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                false // handle audio focus manually
            )
            .setHandleAudioBecomingNoisy(pauseOnNoisy)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        serviceScope.launch {
            restorePlaybackState(player)
        }

        volumeFadeHelper.startFadeCheckLoop(player)
        volumeFadeHelper.startPlaybackWatchdogLoop(player)

        val eqPrefs = PreferenceConstants.getEqualizerPrefs(this)
        eqPrefs.registerOnSharedPreferenceChangeListener(eqPrefsListener)
        settingsPrefs.registerOnSharedPreferenceChangeListener(settingsPrefsListener)

        playerListener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val title = mediaItem?.mediaMetadata?.title?.toString() ?: ""
                val artist = mediaItem?.mediaMetadata?.artist?.toString() ?: ""
                val uriString = mediaItem?.requestMetadata?.mediaUri?.toString()

                TelemetryLogger.logInfo(
                    this@PlaybackService,
                    "Playback_Transition",
                    "Transition to: $title - $artist, uri: $uriString, reason: $reason, activeSessionId: ${player.audioSessionId}, volume: ${player.volume}"
                )

                widgetStateHelper.updateWidgetState(title, artist, player.isPlaying, uriString)
                applyReplayGain(mediaItem)

                val sessionId = player.audioSessionId
                if (sessionId != 0) {
                    TelemetryLogger.logInfo(
                        this@PlaybackService,
                        "Playback_Transition",
                        "Setting up audio effects on transition for session $sessionId"
                    )
                    audioEffectsManager.setupAudioEffects(sessionId)
                }
                savePlaybackState(player)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val title = mediaMetadata.title?.toString() ?: ""
                val artist = mediaMetadata.artist?.toString() ?: ""
                val uriString = player.currentMediaItem?.requestMetadata?.mediaUri?.toString()
                Log.d("WidgetDebug", "Metadata loaded: $title - $artist, uri: $uriString")
                widgetStateHelper.updateWidgetState(title, artist, player.isPlaying, uriString)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val title = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
                val artist = player.currentMediaItem?.mediaMetadata?.artist?.toString() ?: ""
                val uriString = player.currentMediaItem?.requestMetadata?.mediaUri?.toString()

                TelemetryLogger.logInfo(
                    this@PlaybackService,
                    "Playback_State",
                    "Is playing changed to $isPlaying for: $title - $artist, volume: ${player.volume}"
                )

                widgetStateHelper.updateWidgetState(title, artist, isPlaying, uriString)

                if (isPlaying) {
                    audioFocusHelper.requestAudioFocus()
                    volumeFadeHelper.startFadeCheckLoop(player)
                    try {
                        if (wakeLock?.isHeld == false) {
                            wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24h limit
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
                savePlaybackState(player)
            }

            override fun onPlaybackStateChanged(state: Int) {
                val stateStr = when (state) {
                    Player.STATE_IDLE -> "STATE_IDLE"
                    Player.STATE_BUFFERING -> "STATE_BUFFERING"
                    Player.STATE_READY -> "STATE_READY"
                    Player.STATE_ENDED -> "STATE_ENDED"
                    else -> "UNKNOWN ($state)"
                }
                TelemetryLogger.logInfo(
                    this@PlaybackService,
                    "Playback_StateChanged",
                    "Playback state -> $stateStr | isPlaying=${player.isPlaying}, playWhenReady=${player.playWhenReady}, suppressionReason=${player.playbackSuppressionReason}, pos=${player.currentPosition}"
                )
                savePlaybackState(player)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val reasonStr = when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
                    else -> "REASON_$reason"
                }
                TelemetryLogger.logInfo(
                    this@PlaybackService,
                    "Playback_PlayWhenReady",
                    "playWhenReady -> $playWhenReady (reason: $reasonStr) | isPlaying=${player.isPlaying}, pos=${player.currentPosition}"
                )
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val reasonStr = when (reason) {
                    Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
                    Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
                    Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
                    Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
                    Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
                    Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
                    else -> "REASON_$reason"
                }
                TelemetryLogger.logInfo(
                    this@PlaybackService,
                    "Playback_Discontinuity",
                    "Position discontinuity ($reasonStr): from ${oldPosition.positionMs}ms to ${newPosition.positionMs}ms"
                )
                savePlaybackState(player)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                savePlaybackState(player)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != 0) {
                    TelemetryLogger.logInfo(
                        this@PlaybackService,
                        "Playback_AudioSession",
                        "Audio session ID changed to: $audioSessionId (current stored session: ${audioEffectsManager.currentAudioSessionId})"
                    )
                    audioEffectsManager.setupAudioEffects(audioSessionId)
                    val prefs = PreferenceConstants.getPlaybackPrefs(this@PlaybackService)
                    prefs.edit().putInt(PreferenceConstants.KEY_AUDIO_SESSION_ID, audioSessionId).apply()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("PlaybackService", "ExoPlayer error: ${error.message}", error)
                TelemetryLogger.logError(
                    this@PlaybackService,
                    "ExoPlayer_Error",
                    "ErrorCodeName: ${error.errorCodeName}, ErrorCode: ${error.errorCode}",
                    error
                )

                val locale = resources.configuration.locales[0]
                val targetLang = locale.language
                val songTitle = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
                val msg = if (targetLang == "es") {
                    if (songTitle.isNotEmpty()) "No se pudo reproducir: $songTitle" else "Error al reproducir la pista"
                } else {
                    if (songTitle.isNotEmpty()) "Could not play: $songTitle" else "Error playing track"
                }

                serviceScope.launch(Dispatchers.Main) {
                    val isAudioSinkIssue = error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED

                    if (isAudioSinkIssue) {
                        try {
                            val currentPos = player.currentPosition
                            player.prepare()
                            player.seekTo(currentPos)
                            player.play()
                            return@launch
                        } catch (e: Exception) {
                            Log.w("PlaybackService", "Soft recovery failed, skipping to next track: ${e.message}")
                        }
                    }

                    Toast.makeText(this@PlaybackService, msg, Toast.LENGTH_LONG).show()
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                        player.prepare()
                        player.play()
                    }
                }
            }
        }
        player.addListener(playerListener!!)

        val analyticsListener = object : AnalyticsListener {
            override fun onAudioSinkError(
                eventTime: AnalyticsListener.EventTime,
                audioSinkError: Exception
            ) {
                TelemetryLogger.logError(
                    this@PlaybackService,
                    "Playback_AudioSinkError",
                    "AudioSink rendering error: ${audioSinkError.localizedMessage}. Attempting soft recovery...",
                    audioSinkError
                )

                serviceScope.launch(Dispatchers.Main) {
                    try {
                        if (player.isPlaying || player.playWhenReady) {
                            val currentPos = player.currentPosition
                            player.prepare()
                            player.seekTo(currentPos)
                            player.play()
                        }
                    } catch (e: Exception) {
                        Log.w("PlaybackService", "Failed AudioSink auto-recovery: ${e.message}")
                    }
                }
            }

            override fun onAudioCodecError(
                eventTime: AnalyticsListener.EventTime,
                audioCodecError: Exception
            ) {
                TelemetryLogger.logError(
                    this@PlaybackService,
                    "Playback_AudioCodecError",
                    "AudioCodec error: ${audioCodecError.localizedMessage}",
                    audioCodecError
                )
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long
            ) {
                TelemetryLogger.logError(
                    this@PlaybackService,
                    "Playback_AudioUnderrun",
                    "Audio underrun detected: bufferSize=$bufferSize, bufferSizeMs=$bufferSizeMs, elapsedSinceLastFeedMs=$elapsedSinceLastFeedMs"
                )
                if ((elapsedSinceLastFeedMs > bufferSizeMs + 100L || elapsedSinceLastFeedMs > 1500L) && (player.isPlaying || player.playWhenReady)) {
                    TelemetryLogger.logWarn(
                        this@PlaybackService,
                        "Playback_AudioUnderrun",
                        "AudioSink starved ($elapsedSinceLastFeedMs ms elapsed > $bufferSizeMs ms buffer). Auto-recovering ExoPlayer pipeline..."
                    )
                    serviceScope.launch(Dispatchers.Main) {
                        try {
                            val currentPos = player.currentPosition
                            player.seekTo(currentPos)
                            player.prepare()
                            player.play()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
        player.addAnalyticsListener(analyticsListener)

        val callback = object : MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val connectionResult = super.onConnect(session, controller)
                val sessionCommands = connectionResult.availableSessionCommands.buildUpon()
                sessionCommands.add(SessionCommand("ACTION_SKIP_NEXT", android.os.Bundle.EMPTY))
                sessionCommands.add(SessionCommand("ACTION_SKIP_PREV", android.os.Bundle.EMPTY))

                val sessionPlayer = session.player
                val title = sessionPlayer.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
                val artist = sessionPlayer.currentMediaItem?.mediaMetadata?.artist?.toString() ?: ""
                val uriString = sessionPlayer.currentMediaItem?.requestMetadata?.mediaUri?.toString()
                this@PlaybackService.widgetStateHelper.updateWidgetState(title, artist, sessionPlayer.isPlaying, uriString)

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
                customCommand: SessionCommand,
                args: android.os.Bundle
            ): ListenableFuture<SessionResult> {
                val exoplayer = session.player as? ExoPlayer
                if (exoplayer != null) {
                    when (customCommand.customAction) {
                        "ACTION_SKIP_NEXT" -> {
                            volumeFadeHelper.performManualSkip(exoplayer, next = true)
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                        "ACTION_SKIP_PREV" -> {
                            volumeFadeHelper.performManualSkip(exoplayer, next = false)
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = intent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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

        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private fun savePlaybackState(player: Player) {
        val currentItem = player.currentMediaItem
        val id = currentItem?.mediaId?.toLongOrNull() ?: -1L
        val position = player.currentPosition
        val activeIndex = player.currentMediaItemIndex
        val shuffleModeEnabled = player.shuffleModeEnabled

        val mediaIds = ArrayList<String>(player.mediaItemCount)
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            mediaIds.add(item.mediaId)
        }

        serviceScope.launch(Dispatchers.IO) {
            val prefs = PreferenceConstants.getPlaybackPrefs(this@PlaybackService)
            val editor = prefs.edit()
                .putBoolean(PreferenceConstants.KEY_LAST_SHUFFLE_ENABLED, shuffleModeEnabled)
            if (id != -1L) {
                val mediaIdsString = mediaIds.joinToString(",")
                editor.putLong(PreferenceConstants.KEY_LAST_SONG_ID, id)
                    .putLong(PreferenceConstants.KEY_LAST_POSITION, position)
                    .putInt(PreferenceConstants.KEY_LAST_ACTIVE_INDEX, activeIndex)
                    .putString(PreferenceConstants.KEY_LAST_QUEUE_IDS, mediaIdsString)
            }
            editor.apply()
        }
    }

    private suspend fun restorePlaybackState(player: Player) {
        if (isRestoring || player.mediaItemCount > 0) return
        isRestoring = true
        try {
            val prefs = PreferenceConstants.getPlaybackPrefs(this)
            val lastShuffleEnabled = prefs.getBoolean(PreferenceConstants.KEY_LAST_SHUFFLE_ENABLED, false)
            player.shuffleModeEnabled = lastShuffleEnabled

            val lastSongId = prefs.getLong(PreferenceConstants.KEY_LAST_SONG_ID, -1L)
            val lastPosition = prefs.getLong(PreferenceConstants.KEY_LAST_POSITION, 0L)
            val lastActiveIndex = prefs.getInt(PreferenceConstants.KEY_LAST_ACTIVE_INDEX, 0)
            val lastQueueIdsString = prefs.getString(PreferenceConstants.KEY_LAST_QUEUE_IDS, null)

            val (mediaItems, targetIndex, targetPosition) = withContext(Dispatchers.IO) {
                val database = AppDatabase.getDatabase(this@PlaybackService)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.playback_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession?.player
        if (player != null && (player.isPlaying || player.playWhenReady)) {
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaLibrarySession

    override fun onDestroy() {
        mediaLibrarySession?.player?.let { player ->
            try {
                val prefs = PreferenceConstants.getPlaybackPrefs(this)
                val currentItem = player.currentMediaItem
                val id = currentItem?.mediaId?.toLongOrNull() ?: -1L
                val position = player.currentPosition
                val activeIndex = player.currentMediaItemIndex
                val shuffleModeEnabled = player.shuffleModeEnabled

                val mediaIds = ArrayList<String>(player.mediaItemCount)
                for (i in 0 until player.mediaItemCount) {
                    val item = player.getMediaItemAt(i)
                    mediaIds.add(item.mediaId)
                }

                val editor = prefs.edit()
                    .putBoolean(PreferenceConstants.KEY_LAST_SHUFFLE_ENABLED, shuffleModeEnabled)
                if (id != -1L) {
                    val mediaIdsString = mediaIds.joinToString(",")
                    editor.putLong(PreferenceConstants.KEY_LAST_SONG_ID, id)
                        .putLong(PreferenceConstants.KEY_LAST_POSITION, position)
                        .putInt(PreferenceConstants.KEY_LAST_ACTIVE_INDEX, activeIndex)
                        .putString(PreferenceConstants.KEY_LAST_QUEUE_IDS, mediaIdsString)
                }
                editor.commit()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        serviceScope.cancel()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}

        val eqPrefs = PreferenceConstants.getEqualizerPrefs(this)
        eqPrefs.unregisterOnSharedPreferenceChangeListener(eqPrefsListener)
        val settingsPrefs = PreferenceConstants.getSettingsPrefs(this)
        settingsPrefs.unregisterOnSharedPreferenceChangeListener(settingsPrefsListener)

        playerListener?.let {
            mediaLibrarySession?.player?.removeListener(it)
        }

        volumeFadeHelper.cancelAll()

        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null

        audioEffectsManager.releaseEffects()

        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (device != null) {
                    val settingsPrefs = PreferenceConstants.getSettingsPrefs(context)
                    val isEnabled = settingsPrefs.getBoolean(PreferenceConstants.KEY_BLUETOOTH_RESUME_ENABLED, false)
                    if (isEnabled) {
                        val resumeAll = settingsPrefs.getBoolean(PreferenceConstants.KEY_BLUETOOTH_RESUME_ALL, true)
                        val allowedDevices = settingsPrefs.getStringSet(PreferenceConstants.KEY_BLUETOOTH_RESUME_DEVICES, emptySet()) ?: emptySet()

                        val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
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
            val devNames = addedDevices?.map { "${it.productName ?: "Device"} (type=${it.type})" }?.joinToString(", ") ?: "none"
            TelemetryLogger.logInfo(
                this@PlaybackService,
                "Playback_AudioDevice",
                "Audio devices added: $devNames"
            )
            triggerAudioEffectsRecreation()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            val devNames = removedDevices?.map { "${it.productName ?: "Device"} (type=${it.type})" }?.joinToString(", ") ?: "none"
            TelemetryLogger.logInfo(
                this@PlaybackService,
                "Playback_AudioDevice",
                "Audio devices removed: $devNames"
            )
            triggerAudioEffectsRecreation()
        }
    }

    private fun triggerAudioEffectsRecreation() {
        val player = mediaLibrarySession?.player as? ExoPlayer
        val sessionId = player?.audioSessionId ?: 0
        if (sessionId != 0) {
            Log.d("PlaybackService", "Audio routing changed for session: $sessionId")
            audioEffectsManager.setupAudioEffects(sessionId)
        }
    }

    private val eqPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val player = mediaLibrarySession?.player as? ExoPlayer
        val audioSessionId = player?.audioSessionId ?: 0
        if (audioSessionId != 0) {
            audioEffectsManager.setupAudioEffects(audioSessionId)
        }
    }

    private val settingsPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PreferenceConstants.KEY_NORMALIZE_SOUND) {
            val player = mediaLibrarySession?.player as? ExoPlayer
            val audioSessionId = player?.audioSessionId ?: 0
            if (audioSessionId != 0) {
                audioEffectsManager.setupAudioEffects(audioSessionId)
            }
            applyReplayGain(player?.currentMediaItem)
        } else if (key == PreferenceConstants.KEY_PAUSE_ON_HEADPHONE_UNPLUG) {
            val player = mediaLibrarySession?.player as? ExoPlayer
            val settingsPrefs = PreferenceConstants.getSettingsPrefs(this)
            val pauseOnNoisy = settingsPrefs.getBoolean(PreferenceConstants.KEY_PAUSE_ON_HEADPHONE_UNPLUG, true)
            player?.setHandleAudioBecomingNoisy(pauseOnNoisy)
        } else if (key == PreferenceConstants.KEY_CROSSFADE_DURATION) {
            val player = mediaLibrarySession?.player as? ExoPlayer
            if (player != null) {
                volumeFadeHelper.startFadeCheckLoop(player)
            }
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

        serviceScope.launch(Dispatchers.IO) {
            val database = AppDatabase.getDatabase(this@PlaybackService)
            val song = database.audioDao().getAudioFileById(songId)
            var gain = song?.replayGain

            val settingsPrefs = PreferenceConstants.getSettingsPrefs(this@PlaybackService)
            val normalizeEnabled = settingsPrefs.getBoolean(PreferenceConstants.KEY_NORMALIZE_SOUND, false)

            if (normalizeEnabled) {
                if (gain == null && song != null) {
                    try {
                        val path = getPhysicalPath(this@PlaybackService, song.id, song.uriString)
                        if (!path.isNullOrBlank()) {
                            val file = File(path)
                            if (file.exists() && file.isFile) {
                                try {
                                    org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true)
                                } catch (t: Throwable) {}
                                val audioFile = safeReadAudioFile(file)
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
                        TelemetryLogger.logError(
                            this@PlaybackService,
                            "ReplayGain_Read",
                            "Failed to read/resolve ReplayGain for songId ${song.id}",
                            e
                        )
                    }

                    try {
                        val finalGain = gain ?: 0f
                        val updatedSong = song.copy(replayGain = finalGain)
                        database.audioDao().insertAll(listOf(updatedSong))
                        gain = finalGain
                    } catch (e: Exception) {
                        e.printStackTrace()
                        TelemetryLogger.logError(
                            this@PlaybackService,
                            "ReplayGain_DB_Update",
                            "Failed to save ReplayGain to DB for songId ${song.id}",
                            e
                        )
                    }
                }

                if (gain != null && gain != 0f) {
                    val rawFactor = 10.0.pow(gain.toDouble() / 20.0).toFloat()
                    currentReplayGainFactor = rawFactor.coerceIn(0.15f, 1.0f)
                    TelemetryLogger.logInfo(
                        this@PlaybackService,
                        "ReplayGain",
                        "Resolved ReplayGain: $gain dB -> Factor: $currentReplayGainFactor for song: ${song?.title}"
                    )
                } else {
                    currentReplayGainFactor = 1f
                    TelemetryLogger.logInfo(
                        this@PlaybackService,
                        "ReplayGain",
                        "No ReplayGain found for song: ${song?.title}, defaulting factor to 1.0"
                    )
                }
            } else {
                currentReplayGainFactor = 1f
            }

            withContext(Dispatchers.Main) {
                val player = mediaLibrarySession?.player as? ExoPlayer
                if (player != null && !volumeFadeHelper.isFadingIn) {
                    player.volume = currentReplayGainFactor
                    TelemetryLogger.logInfo(
                        this@PlaybackService,
                        "ReplayGain",
                        "Applied ReplayGain volume factor $currentReplayGainFactor to ExoPlayer"
                    )
                }
            }
        }
    }
}
