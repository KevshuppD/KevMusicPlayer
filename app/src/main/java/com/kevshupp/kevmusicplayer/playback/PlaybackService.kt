package com.kevshupp.kevmusicplayer.playback

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.DefaultMediaNotificationProvider
import com.kevshupp.kevmusicplayer.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibrarySession? = null

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
            .build()

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
                // Ensure the URI is preserved if it's passed from the client, with bulletproof fallback
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

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
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
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }
}
