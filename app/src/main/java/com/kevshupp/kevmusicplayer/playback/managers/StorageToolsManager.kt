package com.kevshupp.kevmusicplayer.playback.managers

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.playback.getPhysicalPath
import com.kevshupp.kevmusicplayer.playback.safeReadAudioFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class StorageToolsManager(
    private val application: Application,
    private val localAudioFiles: SnapshotStateList<AudioFile>,
    private val coroutineScope: CoroutineScope,
    private val onTriggerScan: (isManual: Boolean) -> Unit
) {

    fun forceDeepStorageScan(
        context: Context,
        isScanningState: (Boolean) -> Unit,
        onComplete: (Int) -> Unit
    ) {
        isScanningState(true)
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                val selectedFolder = prefs.getString("music_folder_path", null)
                val rootDir = if (!selectedFolder.isNullOrBlank() && File(selectedFolder).exists()) {
                    File(selectedFolder)
                } else {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                }

                val audioExtensions = setOf("mp3", "flac", "m4a", "wav", "ogg", "aac", "opus", "wma", "m4b")
                val foundAudioPaths = mutableListOf<String>()

                if (rootDir.exists() && rootDir.isDirectory) {
                    rootDir.walkTopDown()
                        .maxDepth(15)
                        .forEach { file ->
                            try {
                                if (file.isFile && audioExtensions.contains(file.extension.lowercase())) {
                                    foundAudioPaths.add(file.absolutePath)
                                }
                            } catch (e: Exception) {}
                        }
                }

                Log.d("DeepScan", "Found ${foundAudioPaths.size} physical audio files on disk in ${rootDir.absolutePath}")

                if (foundAudioPaths.isNotEmpty()) {
                    val batchSize = 50
                    foundAudioPaths.chunked(batchSize).forEach { chunk ->
                        MediaScannerConnection.scanFile(
                            context,
                            chunk.toTypedArray(),
                            null,
                            null
                        )
                    }
                }

                delay(1500L)

                withContext(Dispatchers.Main) {
                    isScanningState(false)
                }

                onTriggerScan(true)

                withContext(Dispatchers.Main) {
                    onComplete(foundAudioPaths.size)
                }
            } catch (e: Exception) {
                Log.e("DeepScan", "Error during forceDeepStorageScan", e)
                withContext(Dispatchers.Main) {
                    isScanningState(false)
                    onComplete(0)
                }
            }
        }
    }

    fun organizeMusicByArtistFolder(
        context: Context,
        onProgress: (current: Int, total: Int, currentName: String) -> Unit,
        onComplete: (successCount: Int, errorCount: Int) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val songsToOrganize = localAudioFiles.toList()
            val total = songsToOrganize.size
            var successCount = 0
            var errorCount = 0

            val settingsPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            val musicFolderPath = settingsPrefs.getString("music_folder_path", null)
            val baseMusicDir = if (!musicFolderPath.isNullOrBlank()) {
                val f = File(musicFolderPath)
                if (f.exists() && f.isDirectory) f else null
            } else {
                val defaultMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                if (defaultMusic.exists() && defaultMusic.isDirectory) defaultMusic else null
            }

            val parentDirsToCheck = mutableSetOf<File>()

            songsToOrganize.forEachIndexed { index, song ->
                try {
                    val physicalPath = getPhysicalPath(context, song.id, song.uriString)
                    if (!physicalPath.isNullOrBlank()) {
                        val oldFile = File(physicalPath)
                        if (oldFile.exists()) {
                            var albumArtist: String? = null
                            try {
                                val audioFile = safeReadAudioFile(oldFile)
                                val tag = audioFile.tag
                                if (tag != null) {
                                    val aa = tag.getFirst(FieldKey.ALBUM_ARTIST)
                                    if (!aa.isNullOrBlank()) {
                                        albumArtist = aa
                                    }
                                }
                            } catch (e: Exception) {}

                            val artistToUse = albumArtist ?: song.artist
                            val cleanArtist = artistToUse.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                            val isArtistValid = cleanArtist.isNotEmpty() &&
                                    !cleanArtist.equals("Unknown Artist", ignoreCase = true) &&
                                    !cleanArtist.equals("Unknown", ignoreCase = true) &&
                                    !cleanArtist.equals("<unknown>", ignoreCase = true) &&
                                    !cleanArtist.equals("Artista Desconocido", ignoreCase = true)

                            val artistFolderName = if (isArtistValid) cleanArtist else {
                                if (Locale.getDefault().language == "es") "Artista Desconocido" else "Unknown Artist"
                            }

                            val cleanAlbum = song.album.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                            val isAlbumValid = cleanAlbum.isNotEmpty() &&
                                    !cleanAlbum.equals("Unknown Album", ignoreCase = true) &&
                                    !cleanAlbum.equals("Unknown", ignoreCase = true) &&
                                    !cleanAlbum.equals("<unknown>", ignoreCase = true) &&
                                    !cleanAlbum.equals("Álbum Desconocido", ignoreCase = true)

                            val albumFolderName = if (isAlbumValid) cleanAlbum else {
                                if (Locale.getDefault().language == "es") "Álbum Desconocido" else "Unknown Album"
                            }

                            val targetBaseDir = baseMusicDir ?: oldFile.parentFile?.parentFile ?: oldFile.parentFile ?: File("/sdcard")
                            val targetArtistDir = File(targetBaseDir, artistFolderName)
                            val targetAlbumDir = File(targetArtistDir, albumFolderName)
                            val newFile = File(targetAlbumDir, oldFile.name)

                            val isAlreadyOrganized = oldFile.absolutePath == newFile.absolutePath

                            if (!isAlreadyOrganized) {
                                oldFile.parentFile?.let { parentDirsToCheck.add(it) }
                                oldFile.parentFile?.parentFile?.let { parentDirsToCheck.add(it) }

                                if (!targetAlbumDir.exists()) {
                                    targetAlbumDir.mkdirs()
                                }

                                withContext(Dispatchers.Main) {
                                    onProgress(index + 1, total, song.title)
                                }

                                var moveCompleted = false

                                try {
                                    val renamed = oldFile.renameTo(newFile)
                                    if (renamed) {
                                        moveCompleted = true
                                        val values = ContentValues().apply {
                                            put(MediaStore.Audio.Media.DATA, newFile.absolutePath)
                                        }
                                        context.contentResolver.update(
                                            android.net.Uri.parse(song.uriString),
                                            values,
                                            null,
                                            null
                                        )
                                        MediaScannerConnection.scanFile(
                                            context,
                                            arrayOf(newFile.absolutePath),
                                            null,
                                            null
                                        )
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }

                                if (moveCompleted) {
                                    successCount++
                                } else {
                                    errorCount++
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    errorCount++
                }
            }

            try {
                parentDirsToCheck.forEach { dir ->
                    if (dir.exists() && dir.isDirectory && dir.listFiles()?.isEmpty() == true) {
                        dir.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            onTriggerScan(true)

            withContext(Dispatchers.Main) {
                onComplete(successCount, errorCount)
            }
        }
    }

    fun deleteAllFolderCoverImages(
        context: Context,
        onProgress: (current: Int, total: Int) -> Unit,
        onComplete: (deletedCount: Int) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val deletedCount = AtomicInteger(0)
            try {
                val settingsPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                val musicFolderPath = settingsPrefs.getString("music_folder_path", null)
                val baseMusicDir = if (!musicFolderPath.isNullOrBlank()) {
                    val f = File(musicFolderPath)
                    if (f.exists() && f.isDirectory) f else null
                } else {
                    val defaultMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    if (defaultMusic.exists() && defaultMusic.isDirectory) defaultMusic else null
                }

                val directories = mutableSetOf<File>()
                localAudioFiles.forEach { song ->
                    val path = getPhysicalPath(context, song.id, song.uriString)
                    if (!path.isNullOrBlank()) {
                        File(path).parentFile?.let { directories.add(it) }
                    }
                }
                if (baseMusicDir != null && baseMusicDir.exists() && baseMusicDir.isDirectory) {
                    baseMusicDir.walkTopDown().forEach { file ->
                        if (file.isDirectory) directories.add(file)
                    }
                }

                val dirList = directories.toList()
                val total = dirList.size
                val processedCount = AtomicInteger(0)
                val coverNames = setOf(
                    "cover.jpg", "folder.jpg", "album.jpg", "front.jpg", "front.png", "cover.png", "folder.png", "album.png",
                    "Cover.jpg", "Folder.jpg", "Album.jpg", "Front.jpg", "Cover.png", "Folder.png", "Album.png"
                )

                dirList.chunked(25).forEach { chunk ->
                    coroutineScope {
                        chunk.map { dir ->
                            async(Dispatchers.IO) {
                                try {
                                    if (dir.exists() && dir.isDirectory) {
                                        val files = dir.listFiles()
                                        files?.forEach { file ->
                                            if (file.isFile && coverNames.contains(file.name)) {
                                                if (file.delete()) {
                                                    deletedCount.incrementAndGet()
                                                }
                                            }
                                        }
                                        try {
                                            val selection = "${MediaStore.Images.Media.DATA} LIKE ?"
                                            val selectionArgs = arrayOf("${dir.absolutePath}/%")
                                            context.contentResolver.delete(
                                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                                selection,
                                                selectionArgs
                                            )
                                        } catch (e: Exception) {}
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                } finally {
                                    val cur = processedCount.incrementAndGet()
                                    withContext(Dispatchers.Main) {
                                        onProgress(cur, total)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }

                com.kevshupp.kevmusicplayer.ui.screens.albumArtCache.evictAll()
                com.kevshupp.kevmusicplayer.ui.screens.clearDiskAlbumArtCache(application)
                withContext(Dispatchers.Main) {
                    com.kevshupp.kevmusicplayer.ui.screens.albumArtVersion++
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                onComplete(deletedCount.get())
            }
        }
    }

    fun deleteAllNoMediaFiles(
        context: Context,
        onProgress: (current: Int, total: Int) -> Unit,
        onComplete: (deletedCount: Int) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val deletedCount = AtomicInteger(0)
            try {
                val settingsPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                val musicFolderPath = settingsPrefs.getString("music_folder_path", null)
                val baseMusicDir = if (!musicFolderPath.isNullOrBlank()) {
                    val f = File(musicFolderPath)
                    if (f.exists() && f.isDirectory) f else null
                } else {
                    val defaultMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    if (defaultMusic.exists() && defaultMusic.isDirectory) defaultMusic else null
                }

                val directories = mutableSetOf<File>()
                localAudioFiles.forEach { song ->
                    val path = getPhysicalPath(context, song.id, song.uriString)
                    if (!path.isNullOrBlank()) {
                        File(path).parentFile?.let { directories.add(it) }
                    }
                }
                if (baseMusicDir != null && baseMusicDir.exists() && baseMusicDir.isDirectory) {
                    baseMusicDir.walkTopDown().forEach { file ->
                        if (file.isDirectory) directories.add(file)
                    }
                }

                val dirList = directories.toList()
                val total = dirList.size
                val processedCount = AtomicInteger(0)

                dirList.chunked(25).forEach { chunk ->
                    coroutineScope {
                        chunk.map { dir ->
                            async(Dispatchers.IO) {
                                try {
                                    if (dir.exists() && dir.isDirectory) {
                                        val files = dir.listFiles()
                                        files?.forEach { file ->
                                            if (file.isFile && file.name.equals(".nomedia", ignoreCase = true)) {
                                                if (file.delete()) {
                                                    deletedCount.incrementAndGet()
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                } finally {
                                    val cur = processedCount.incrementAndGet()
                                    withContext(Dispatchers.Main) {
                                        onProgress(cur, total)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }

                onTriggerScan(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                onComplete(deletedCount.get())
            }
        }
    }

    fun deleteAllLyricsFiles(
        context: Context,
        onProgress: (current: Int, total: Int) -> Unit,
        onComplete: (deletedCount: Int) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val deletedCount = AtomicInteger(0)
            try {
                val settingsPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                val musicFolderPath = settingsPrefs.getString("music_folder_path", null)
                val baseMusicDir = if (!musicFolderPath.isNullOrBlank()) {
                    val f = File(musicFolderPath)
                    if (f.exists() && f.isDirectory) f else null
                } else {
                    val defaultMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    if (defaultMusic.exists() && defaultMusic.isDirectory) defaultMusic else null
                }

                val directories = mutableSetOf<File>()
                localAudioFiles.forEach { song ->
                    val path = getPhysicalPath(context, song.id, song.uriString)
                    if (!path.isNullOrBlank()) {
                        File(path).parentFile?.let { directories.add(it) }
                    }
                }
                if (baseMusicDir != null && baseMusicDir.exists() && baseMusicDir.isDirectory) {
                    baseMusicDir.walkTopDown().forEach { file ->
                        if (file.isDirectory) directories.add(file)
                    }
                }

                val dirList = directories.toList()
                val total = dirList.size
                val processedCount = AtomicInteger(0)

                dirList.chunked(25).forEach { chunk ->
                    coroutineScope {
                        chunk.map { dir ->
                            async(Dispatchers.IO) {
                                try {
                                    if (dir.exists() && dir.isDirectory) {
                                        val files = dir.listFiles()
                                        files?.forEach { file ->
                                            if (file.isFile && (file.extension.equals("lrc", ignoreCase = true) || (file.extension.equals("txt", ignoreCase = true) && !file.name.equals("README.txt", ignoreCase = true)))) {
                                                if (file.delete()) {
                                                    deletedCount.incrementAndGet()
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                } finally {
                                    val cur = processedCount.incrementAndGet()
                                    withContext(Dispatchers.Main) {
                                        onProgress(cur, total)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                onComplete(deletedCount.get())
            }
        }
    }
}
