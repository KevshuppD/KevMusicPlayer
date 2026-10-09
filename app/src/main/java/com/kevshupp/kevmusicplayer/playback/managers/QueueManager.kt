package com.kevshupp.kevmusicplayer.playback.managers

import android.net.Uri
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaBrowser
import com.kevshupp.kevmusicplayer.data.AudioFile
import kotlin.random.Random

class QueueManager(
    private val browser: State<MediaBrowser?>,
    private val localAudioFiles: SnapshotStateList<AudioFile>,
    private val onSavePlaybackState: () -> Unit
) {
    private var originalQueue: List<AudioFile>? = null

    fun setOriginalQueue(queue: List<AudioFile>) {
        originalQueue = queue
    }

    fun getOriginalQueue(): List<AudioFile>? = originalQueue

    fun addToQueue(file: AudioFile) {
        val b = browser.value ?: return
        val mediaItem = buildMediaItem(file)
        b.addMediaItem(mediaItem)
        onSavePlaybackState()
    }

    fun playNext(file: AudioFile) {
        val b = browser.value ?: return
        val mediaItem = buildMediaItem(file)

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
        val count = b.mediaItemCount
        val filesMap = localAudioFiles.associateBy { it.id }
        for (i in 0 until count) {
            val item = b.getMediaItemAt(i)
            val id = item.mediaId.toLongOrNull() ?: continue
            val song = filesMap[id]
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

    fun shuffleUpcomingQueue() {
        val b = browser.value ?: return
        val total = b.mediaItemCount
        if (total <= 1) return

        val currentIndex = b.currentMediaItemIndex.coerceAtLeast(0)
        val upcomingStart = currentIndex + 1

        if (upcomingStart < total) {
            val upcomingItems = mutableListOf<MediaItem>()
            for (i in upcomingStart until total) {
                upcomingItems.add(b.getMediaItemAt(i))
            }
            val shuffledUpcoming = upcomingItems.shuffled(Random(System.nanoTime()))
            b.removeMediaItems(upcomingStart, total)
            b.addMediaItems(upcomingStart, shuffledUpcoming)
            onSavePlaybackState()
        } else if (currentIndex > 0) {
            // Player is at the last item, take earlier items and shuffle them as upcoming
            val earlierItems = mutableListOf<MediaItem>()
            for (i in 0 until currentIndex) {
                earlierItems.add(b.getMediaItemAt(i))
            }
            val shuffledUpcoming = earlierItems.shuffled(Random(System.nanoTime()))
            b.addMediaItems(currentIndex + 1, shuffledUpcoming)
            onSavePlaybackState()
        }
    }

    fun restoreUnshuffledQueue() {
        val b = browser.value ?: return
        val orig = originalQueue ?: return
        val currentMediaId = b.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val currentIndex = b.currentMediaItemIndex.coerceAtLeast(0)

        val origIndex = orig.indexOfFirst { it.id == currentMediaId }
        val remainingOrig = if (origIndex != -1) {
            orig.subList(origIndex + 1, orig.size)
        } else {
            orig.filter { it.id != currentMediaId }
        }

        val newUpcoming = remainingOrig.map { buildMediaItem(it) }
        val upcomingStart = currentIndex + 1

        if (upcomingStart < b.mediaItemCount) {
            b.removeMediaItems(upcomingStart, b.mediaItemCount)
        }
        if (newUpcoming.isNotEmpty()) {
            b.addMediaItems(upcomingStart, newUpcoming)
        }
        onSavePlaybackState()
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
        originalQueue = null
        onSavePlaybackState()
    }

    private fun buildMediaItem(file: AudioFile): MediaItem {
        val trackUri = Uri.parse(file.uriString)
        return MediaItem.Builder()
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
    }
}
