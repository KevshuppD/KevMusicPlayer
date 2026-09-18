package com.kevshupp.kevmusicplayer.playback.managers

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import com.kevshupp.kevmusicplayer.data.AudioDao
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import com.kevshupp.kevmusicplayer.playback.createJaudiotaggerArtwork
import com.kevshupp.kevmusicplayer.playback.getPhysicalPath
import com.kevshupp.kevmusicplayer.playback.invalidateMediaStoreAlbumArt
import com.kevshupp.kevmusicplayer.playback.safeReadAudioFile
import com.kevshupp.kevmusicplayer.playback.saveFolderCoverArt
import com.kevshupp.kevmusicplayer.playback.writeMetadataWithTempFile
import com.kevshupp.kevmusicplayer.playback.writeMp3TagsWithMp3Agic
import com.kevshupp.kevmusicplayer.ui.screens.albumArtCache
import com.kevshupp.kevmusicplayer.ui.screens.albumArtVersion
import com.kevshupp.kevmusicplayer.ui.screens.deleteDiskAlbumArtCacheForUri
import com.kevshupp.kevmusicplayer.ui.screens.getDiskCacheFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.tag.FieldKey
import java.io.File

class TagEditorManager(
    private val application: Application,
    private val audioDao: AudioDao,
    private val localAudioFiles: SnapshotStateList<AudioFile>,
    private val playlists: SnapshotStateMap<String, List<AudioFile>>,
    private val browser: State<MediaBrowser?>,
    private val coroutineScope: CoroutineScope,
    private val onUpdateSmartPlaylists: () -> Unit,
    private val onTriggerScan: (isManual: Boolean) -> Unit
) {

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
        coroutineScope.launch(Dispatchers.IO) {
            try {
                try {
                    org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                val songObj = localAudioFiles.find { it.id == songId }
                val songEntity = audioDao.getAudioFileById(songId)
                val targetUri = songEntity?.uriString ?: songObj?.uriString ?: android.content.ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    songId
                ).toString()
                val songUriString = targetUri

                val pathForMp3Agic = getPhysicalPath(context, songId, targetUri)
                var isMp3Success = false
                if (!pathForMp3Agic.isNullOrBlank() && pathForMp3Agic.endsWith(".mp3", ignoreCase = true)) {
                    isMp3Success = writeMp3TagsWithMp3Agic(context, songId, targetUri, pathForMp3Agic, title, artist, album, genre, coverBytes)
                }

                val writeSuccess = if (isMp3Success) {
                    true
                } else {
                    writeMetadataWithTempFile(context, songId, targetUri) { audioFile ->
                        val tag = audioFile.getTagOrCreateAndSetDefault()
                        tag.setField(FieldKey.TITLE, title)
                        tag.setField(FieldKey.ARTIST, artist)
                        tag.setField(FieldKey.ALBUM, album)
                        tag.setField(FieldKey.GENRE, genre)
                        if (coverBytes != null) {
                            try {
                                val artwork = createJaudiotaggerArtwork(coverBytes)
                                if (artwork != null) {
                                    try {
                                        tag.deleteArtworkField()
                                    } catch (e: Throwable) {}
                                    try {
                                        tag.setField(artwork)
                                    } catch (e: Throwable) {
                                        try {
                                            tag.addField(artwork)
                                        } catch (e2: Throwable) {
                                            val field = tag.createField(artwork)
                                            if (field != null) {
                                                tag.setField(field)
                                            }
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                e.printStackTrace()
                                TelemetryLogger.logError(context, "MetadataArtwork", "Failed to set artwork field for songId $songId", e)
                            }
                        }
                        audioFile.tag = tag
                    }
                }
                if (!writeSuccess && !isMp3Success) {
                    TelemetryLogger.logError(context, "MetadataWrite", "Warning: Physical tags could not be written for songId $songId, continuing with DB and cache update")
                }

                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.TITLE, title)
                        put(MediaStore.Audio.Media.ARTIST, artist)
                        put(MediaStore.Audio.Media.ALBUM, album)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            put(MediaStore.Audio.Media.GENRE, genre)
                        }
                    }
                    context.contentResolver.update(Uri.parse(targetUri), values, null, null)
                } catch (e: Exception) {
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Audio.Media.TITLE, title)
                            put(MediaStore.Audio.Media.ARTIST, artist)
                            put(MediaStore.Audio.Media.ALBUM, album)
                        }
                        context.contentResolver.update(Uri.parse(targetUri), values, null, null)
                    } catch (ex: Exception) {
                        Log.e("MetadataWrite", "Failed to update MediaStore columns", ex)
                    }
                }

                val allEntities = audioDao.getAllAudioFiles()
                val targetEntity = allEntities.find { it.id == songId }
                val folderPath = songObj?.folderPath ?: targetEntity?.folderPath
                val physicalPath = getPhysicalPath(context, songId, targetUri) ?: folderPath ?: targetUri.let {
                    if (it.startsWith("file://")) Uri.parse(it).path else null
                }

                if (!physicalPath.isNullOrBlank()) {
                    invalidateMediaStoreAlbumArt(context, songId, targetUri)
                    if (coverBytes != null) {
                        saveFolderCoverArt(context, physicalPath, coverBytes, artist, album.ifBlank { title })
                    }
                }
                val newModTime = if (physicalPath != null) File(physicalPath).lastModified() else 0L
                if (targetEntity != null) {
                    val updatedEntity = targetEntity.copy(
                        title = title,
                        artist = artist,
                        album = album,
                        genre = genre,
                        dateModified = newModTime
                    )
                    audioDao.insertAll(listOf(updatedEntity))
                }

                deleteDiskAlbumArtCacheForUri(application, songUriString)
                if (coverBytes != null) {
                    try {
                        val res = try {
                            context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).getInt("art_resolution", 500)
                        } catch (e: Exception) { 500 }
                        val diskFile = getDiskCacheFile(context, songUriString, res)
                        diskFile.parentFile?.mkdirs()
                        diskFile.writeBytes(coverBytes)
                    } catch (e: Exception) {}

                    val bitmap = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
                    if (bitmap != null) {
                        albumArtCache.put(songUriString, bitmap)
                    }
                } else {
                    albumArtCache.remove(songUriString)
                }

                withContext(Dispatchers.Main) {
                    albumArtVersion++
                    val index = localAudioFiles.indexOfFirst { it.id == songId }
                    if (index != -1) {
                        val currentSong = localAudioFiles[index]
                        localAudioFiles[index] = currentSong.copy(
                            title = title,
                            artist = artist,
                            album = album,
                            genre = genre,
                            playCount = currentSong.playCount,
                            dateModified = newModTime
                        )
                    }

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

                    onUpdateSmartPlaylists()

                    val b = browser.value
                    if (b != null) {
                        for (i in 0 until b.mediaItemCount) {
                            val item = b.getMediaItemAt(i)
                            if (item.mediaId == songId.toString()) {
                                val trackUri = item.requestMetadata.mediaUri ?: Uri.parse(songUriString ?: "")
                                val newMediaItem = MediaItem.Builder()
                                    .setMediaId(songId.toString())
                                    .setUri(trackUri)
                                    .setRequestMetadata(
                                        MediaItem.RequestMetadata.Builder()
                                            .setMediaUri(trackUri)
                                            .build()
                                    )
                                    .setMediaMetadata(
                                        androidx.media3.common.MediaMetadata.Builder()
                                            .setTitle(title)
                                            .setArtist(artist)
                                            .setAlbumTitle(album)
                                            .setIsPlayable(true)
                                            .setIsBrowsable(false)
                                            .build()
                                    )
                                    .build()
                                b.replaceMediaItem(i, newMediaItem)
                            }
                        }
                    }

                    onSuccess()
                }
            } catch (e: Throwable) {
                val ex = if (e is Exception) e else Exception(e)
                TelemetryLogger.logError(context, "MetadataUpdate", "Failed to update song metadata for songId $songId", ex)
                withContext(Dispatchers.Main) {
                    onError(ex)
                }
            }
        }
    }

    fun updateAlbumCover(
        context: Context,
        albumName: String,
        coverBytes: ByteArray,
        targetSongIds: List<Long>? = null,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                try {
                    org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                val songsInAlbum = if (!targetSongIds.isNullOrEmpty()) {
                    val idSet = targetSongIds.toSet()
                    val matched = localAudioFiles.filter { idSet.contains(it.id) }
                    if (matched.isNotEmpty()) matched else localAudioFiles.filter { it.album.trim().equals(albumName.trim(), ignoreCase = true) }
                } else {
                    localAudioFiles.filter { it.album.trim().equals(albumName.trim(), ignoreCase = true) }
                }

                if (songsInAlbum.isEmpty()) {
                    throw Exception("No songs found in album $albumName")
                }

                val bitmap = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)

                for (song in songsInAlbum) {
                    var writtenPhysically = false
                    val pathForMp3Agic = getPhysicalPath(context, song.id, song.uriString)
                    if (!pathForMp3Agic.isNullOrBlank() && pathForMp3Agic.endsWith(".mp3", ignoreCase = true)) {
                        writtenPhysically = writeMp3TagsWithMp3Agic(context, song.id, song.uriString, pathForMp3Agic, coverBytes = coverBytes)
                    }

                    if (!writtenPhysically) {
                        writeMetadataWithTempFile(context, song.id, song.uriString) { audioFile ->
                            val tag = audioFile.getTagOrCreateAndSetDefault()
                            try {
                                val artwork = createJaudiotaggerArtwork(coverBytes)
                                if (artwork != null) {
                                    try {
                                        tag.deleteArtworkField()
                                    } catch (e: Throwable) {}
                                    try {
                                        tag.setField(artwork)
                                    } catch (e: Throwable) {
                                        try {
                                            tag.addField(artwork)
                                        } catch (e2: Throwable) {
                                            val field = tag.createField(artwork)
                                            if (field != null) {
                                                tag.setField(field)
                                            }
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                e.printStackTrace()
                                TelemetryLogger.logError(context, "AlbumCoverArtwork", "Failed to set artwork field in updateAlbumCover for songId ${song.id}", e)
                            }
                            audioFile.tag = tag
                        }
                    }

                    deleteDiskAlbumArtCacheForUri(application, song.uriString)
                    try {
                        val res = try {
                            context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).getInt("art_resolution", 500)
                        } catch (e: Exception) { 500 }
                        val diskFile = getDiskCacheFile(context, song.uriString, res)
                        diskFile.parentFile?.mkdirs()
                        diskFile.writeBytes(coverBytes)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    if (bitmap != null) {
                        albumArtCache.put(song.uriString, bitmap)
                    }
                }

                if (songsInAlbum.isNotEmpty()) {
                    songsInAlbum.forEach { song ->
                        val songUri = song.uriString
                        val folderPath = song.folderPath
                        val path = getPhysicalPath(context, song.id, songUri) ?: folderPath ?: songUri.let {
                            if (it.startsWith("file://")) Uri.parse(it).path else null
                        }
                        if (!path.isNullOrBlank()) {
                            saveFolderCoverArt(context, path, coverBytes, song.artist, song.album)
                            invalidateMediaStoreAlbumArt(context, song.id, songUri)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    albumArtVersion++
                    songsInAlbum.forEach { song ->
                        val index = localAudioFiles.indexOfFirst { it.id == song.id }
                        if (index != -1) {
                            val currentSong = localAudioFiles[index]
                            localAudioFiles[index] = currentSong.copy()
                        }
                    }

                    playlists.keys.toList().forEach { playlistName ->
                        val list = playlists[playlistName] ?: emptyList()
                        var modified = false
                        val newList = list.map { song ->
                            if (songsInAlbum.any { it.id == song.id } || song.album.trim().equals(albumName.trim(), ignoreCase = true)) {
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

                    onUpdateSmartPlaylists()
                    onSuccess()
                }
            } catch (e: Throwable) {
                val ex = if (e is Exception) e else Exception(e)
                TelemetryLogger.logError(context, "AlbumCoverUpdate", "Failed to update album cover for $albumName", ex)
                withContext(Dispatchers.Main) {
                    onError(ex)
                }
            }
        }
    }

    fun updateAlbumMetadata(
        context: Context,
        oldAlbumName: String,
        songs: List<AudioFile>,
        newAlbumName: String,
        newArtist: String,
        onSuccess: () -> Unit
    ) {
        updateAlbumMetadata(
            context = context,
            oldAlbumName = oldAlbumName,
            newAlbumName = newAlbumName,
            newArtist = newArtist,
            coverBytes = null,
            onSuccess = onSuccess,
            onError = {}
        )
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
        coroutineScope.launch(Dispatchers.IO) {
            try {
                try {
                    org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                val songsInAlbum = localAudioFiles.filter { it.album.trim().equals(oldAlbumName.trim(), ignoreCase = true) }
                if (songsInAlbum.isEmpty()) {
                    throw Exception("No songs found in album $oldAlbumName")
                }

                var successCount = 0
                val bitmap = if (coverBytes != null) BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size) else null

                for (song in songsInAlbum) {
                    var writtenPhysically = false
                    val pathForMp3Agic = getPhysicalPath(context, song.id, song.uriString)
                    if (!pathForMp3Agic.isNullOrBlank() && pathForMp3Agic.endsWith(".mp3", ignoreCase = true)) {
                        writtenPhysically = writeMp3TagsWithMp3Agic(context, song.id, song.uriString, pathForMp3Agic, album = newAlbumName, artist = if (newArtist.isNotBlank()) newArtist else null, coverBytes = coverBytes)
                    }

                    if (!writtenPhysically) {
                        writeMetadataWithTempFile(context, song.id, song.uriString) { audioFile ->
                            val tag = audioFile.getTagOrCreateAndSetDefault()
                            tag.setField(FieldKey.ALBUM, newAlbumName)
                            if (newArtist.isNotBlank()) {
                                tag.setField(FieldKey.ARTIST, newArtist)
                            }
                            if (coverBytes != null) {
                                try {
                                    val artwork = createJaudiotaggerArtwork(coverBytes)
                                    if (artwork != null) {
                                        try {
                                            tag.deleteArtworkField()
                                        } catch (e: Throwable) {}
                                        try {
                                            tag.setField(artwork)
                                        } catch (e: Throwable) {
                                            try {
                                                tag.addField(artwork)
                                            } catch (e2: Throwable) {
                                                val field = tag.createField(artwork)
                                                if (field != null) {
                                                    tag.setField(field)
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Throwable) {
                                    e.printStackTrace()
                                    TelemetryLogger.logError(context, "AlbumMetadataArtwork", "Failed to set artwork field in updateAlbumMetadata for songId ${song.id}", e)
                                }
                            }
                            audioFile.tag = tag
                        }
                    }

                    successCount++
                    if (coverBytes != null) {
                        deleteDiskAlbumArtCacheForUri(application, song.uriString)
                        try {
                            val res = try {
                                context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).getInt("art_resolution", 500)
                            } catch (e: Exception) { 500 }
                            val diskFile = getDiskCacheFile(context, song.uriString, res)
                            diskFile.parentFile?.mkdirs()
                            diskFile.writeBytes(coverBytes)
                        } catch (e: Exception) {}

                        if (bitmap != null) {
                            albumArtCache.put(song.uriString, bitmap)
                        }
                    }

                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Audio.Media.ALBUM, newAlbumName)
                            if (newArtist.isNotBlank()) {
                                put(MediaStore.Audio.Media.ARTIST, newArtist)
                            }
                        }
                        context.contentResolver.update(Uri.parse(song.uriString), values, null, null)
                    } catch (e: Exception) {
                        Log.e("MetadataWrite", "Failed to update MediaStore columns", e)
                    }
                }

                if (coverBytes != null && songsInAlbum.isNotEmpty()) {
                    songsInAlbum.forEach { song ->
                        val songUri = song.uriString
                        val folderPath = song.folderPath
                        val path = getPhysicalPath(context, song.id, songUri) ?: folderPath ?: songUri.let {
                            if (it.startsWith("file://")) Uri.parse(it).path else null
                        }
                        if (!path.isNullOrBlank()) {
                            saveFolderCoverArt(context, path, coverBytes, if (newArtist.isNotBlank()) newArtist else song.artist, newAlbumName)
                            invalidateMediaStoreAlbumArt(context, song.id, songUri)
                        }
                    }
                }

                if (successCount == 0 && songsInAlbum.isNotEmpty()) {
                    throw Exception("Failed to write metadata to any songs in the album")
                }

                val allEntities = audioDao.getAllAudioFiles()
                val updatedEntities = mutableListOf<AudioFile>()
                for (song in songsInAlbum) {
                    val entity = allEntities.find { it.id == song.id }
                    if (entity != null) {
                        val physicalPath = getPhysicalPath(context, song.id, entity.uriString)
                        val newModTime = if (physicalPath != null) File(physicalPath).lastModified() else 0L
                        updatedEntities.add(
                            entity.copy(
                                album = newAlbumName,
                                artist = if (newArtist.isNotBlank()) newArtist else entity.artist,
                                dateModified = newModTime
                            )
                        )
                    }
                }
                if (updatedEntities.isNotEmpty()) {
                    audioDao.insertAll(updatedEntities)
                }

                withContext(Dispatchers.Main) {
                    albumArtVersion++
                    songsInAlbum.forEach { song ->
                        val index = localAudioFiles.indexOfFirst { it.id == song.id }
                        if (index != -1) {
                            val currentSong = localAudioFiles[index]
                            val physicalPath = getPhysicalPath(context, song.id, currentSong.uriString)
                            val newModTime = if (physicalPath != null) File(physicalPath).lastModified() else 0L
                            localAudioFiles[index] = currentSong.copy(
                                album = newAlbumName,
                                artist = if (newArtist.isNotBlank()) newArtist else currentSong.artist,
                                dateModified = newModTime
                            )
                        }
                    }

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

                    onUpdateSmartPlaylists()
                    onSuccess()
                }
            } catch (e: Throwable) {
                val ex = if (e is Exception) e else Exception(e)
                TelemetryLogger.logError(context, "AlbumMetadataUpdate", "Failed to update album metadata for $oldAlbumName", ex)
                withContext(Dispatchers.Main) {
                    onError(ex)
                }
            }
        }
    }

    fun renameSongFilesToMetadata(
        context: Context,
        onProgress: (current: Int, total: Int, currentName: String) -> Unit,
        onComplete: (successCount: Int, errorCount: Int) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val songsToRename = localAudioFiles.toList()
            val total = songsToRename.size
            var successCount = 0
            var errorCount = 0

            songsToRename.forEachIndexed { index, song ->
                try {
                    val cleanArtist = song.artist.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                    val cleanTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

                    val isArtistValid = cleanArtist.isNotEmpty() && !cleanArtist.equals("Unknown Artist", ignoreCase = true)
                    val isTitleValid = cleanTitle.isNotEmpty() && !cleanTitle.equals("Unknown Title", ignoreCase = true)

                    if (isArtistValid && isTitleValid) {
                        val physicalPath = getPhysicalPath(context, song.id, song.uriString)
                        if (!physicalPath.isNullOrBlank()) {
                            val oldFile = File(physicalPath)
                            if (oldFile.exists()) {
                                var trackPrefix = ""
                                try {
                                    val audioFile = safeReadAudioFile(oldFile)
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

                                    try {
                                        val uri = Uri.parse(song.uriString)
                                        val values = ContentValues().apply {
                                            put(MediaStore.Audio.Media.DISPLAY_NAME, newFileName)
                                        }
                                        val rows = context.contentResolver.update(uri, values, null, null)
                                        if (rows > 0) {
                                            renameCompleted = true
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }

                                    if (!renameCompleted) {
                                        try {
                                            val renamed = oldFile.renameTo(newFile)
                                            if (renamed) {
                                                renameCompleted = true
                                                val values = ContentValues().apply {
                                                    put(MediaStore.Audio.Media.DATA, newFile.absolutePath)
                                                    put(MediaStore.Audio.Media.DISPLAY_NAME, newFileName)
                                                }
                                                val uri = Uri.parse(song.uriString)
                                                context.contentResolver.update(uri, values, null, null)
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }

                                    if (renameCompleted) {
                                        val oldBaseName = oldFile.nameWithoutExtension
                                        val newBaseName = newFile.nameWithoutExtension
                                        listOf("lrc", "txt").forEach { ext ->
                                            val oldLrc = File(oldFile.parentFile, "$oldBaseName.$ext")
                                            if (oldLrc.exists() && oldLrc.isFile) {
                                                val newLrc = File(oldFile.parentFile, "$newBaseName.$ext")
                                                try {
                                                    oldLrc.renameTo(newLrc)
                                                    MediaScannerConnection.scanFile(context, arrayOf(newLrc.absolutePath), null, null)
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                }
                                            }
                                        }

                                        MediaScannerConnection.scanFile(
                                            context,
                                            arrayOf(oldFile.absolutePath, newFile.absolutePath),
                                            null
                                        ) { _, _ -> }

                                        successCount++
                                    } else {
                                        errorCount++
                                    }
                                } else {
                                    successCount++
                                }
                            } else {
                                errorCount++
                            }
                        } else {
                            errorCount++
                        }
                    } else {
                        successCount++
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    errorCount++
                }
            }

            onTriggerScan(true)

            withContext(Dispatchers.Main) {
                onComplete(successCount, errorCount)
            }
        }
    }
}
