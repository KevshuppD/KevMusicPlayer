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

    companion object {
        const val CHANNEL_ID = "playback_channel"
    }

    override fun onCreate() {
        super.onCreate()
        
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

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
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
                            }
                        }
                    }
                    "com.kevshupp.kevmusicplayer.action.NEXT" -> {
                        if (player.hasNextMediaItem()) {
                            player.seekToNextMediaItem()
                        }
                    }
                    "com.kevshupp.kevmusicplayer.action.PREVIOUS" -> {
                        if (player.hasPreviousMediaItem()) {
                            player.seekToPreviousMediaItem()
                        }
                    }
                }
            }
        }
        return START_STICKY
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
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }
}
