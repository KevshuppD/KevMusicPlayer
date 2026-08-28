package com.kevshupp.kevmusicplayer.playback.managers

import android.net.Uri
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaBrowser
import com.kevshupp.kevmusicplayer.data.AudioFile

class QueueManager(
    private val browser: State<MediaBrowser?>,
    private val localAudioFiles: SnapshotStateList<AudioFile>,
    private val onSavePlaybackState: () -> Unit
) {
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
        onSavePlaybackState()
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
        onSavePlaybackState()
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
            onSavePlaybackState()
        }
    }

    fun clearQueue() {
        val b = browser.value ?: return
        b.clearMediaItems()
        onSavePlaybackState()
    }
}
