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

/** A file that could not be moved, with a reason the user can act on. */
data class FileOpFailure(val name: String, val reason: String)

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
                val rootDirs = com.kevshupp.kevmusicplayer.data.MusicFolders.all(prefs)
                    .map { File(it) }.filter { it.exists() }
                    .ifEmpty { listOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)) }

                val audioExtensions = setOf("mp3", "flac", "m4a", "wav", "ogg", "aac", "opus", "wma", "m4b")
                val foundAudioPaths = mutableListOf<String>()

                rootDirs.filter { it.isDirectory }.forEach { rootDir ->
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

                Log.d("DeepScan", "Found ${foundAudioPaths.size} physical audio files on disk in ${rootDirs.joinToString { it.absolutePath }}")

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
        onComplete: (successCount: Int, failures: List<FileOpFailure>) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val songsToOrganize = localAudioFiles.toList()
            val total = songsToOrganize.size
            var successCount = 0
            val failures = mutableListOf<FileOpFailure>()

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

                                val sameVolume = !newFile.exists() && oldFile.renameTo(newFile)
                                val reason = when {
                                    sameVolume -> {
                                        val values = ContentValues().apply {
                                            put(MediaStore.Audio.Media.DATA, newFile.absolutePath)
                                        }
                                        runCatching {
                                            context.contentResolver.update(android.net.Uri.parse(song.uriString), values, null, null)
                                        }
                                        MediaScannerConnection.scanFile(context, arrayOf(newFile.absolutePath), null, null)
                                        null
                                    }
                                    newFile.exists() -> "Ya existe un archivo con ese nombre en $artistFolderName/$albumFolderName"
                                    // Extra music folders can live on another volume (SD card vs internal),
                                    // where renameTo always fails. MediaStore indexes the copy as a new song;
                                    // the next scan remaps its id (SongIdentity), keeping stats and playlists.
                                    else -> moveAcrossVolumes(oldFile, newFile).also { r ->
                                        if (r == null) MediaScannerConnection.scanFile(context, arrayOf(oldFile.absolutePath, newFile.absolutePath), null, null)
                                    }
                                }
                                if (reason == null) successCount++ else failures += FileOpFailure(oldFile.name, reason)
                            }
                        } else {
                            failures += FileOpFailure(song.title, "El archivo ya no existe en ${oldFile.parent}")
                        }
                    } else {
                        failures += FileOpFailure(song.title, "No se encontró la ruta del archivo (vuelve a escanear)")
                    }
                } catch (e: Exception) {
                    failures += FileOpFailure(song.title, e.message ?: e.javaClass.simpleName)
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

            if (failures.isNotEmpty()) {
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logInfo(
                    context, "Organize", failures.joinToString("\n") { "${it.name}: ${it.reason}" }
                )
            }
            withContext(Dispatchers.Main) {
                onComplete(successCount, failures)
            }
        }
    }

    /**
     * Moves a whole music folder to another one (typically SD card → internal storage) keeping the same subfolders
     * and file names: audio, .lrc lyrics, folder images and the hidden `.covers/` folder. Nothing is ever
     * overwritten. MediaStore gives the moved songs new ids; the scan afterwards remaps them (SongIdentity), so
     * stats, lyrics and playlists are kept.
     */
    fun migrateFolder(
        context: Context,
        fromPath: String,
        toPath: String,
        onProgress: (current: Int, total: Int, currentName: String) -> Unit,
        onComplete: (successCount: Int, failures: List<FileOpFailure>) -> Unit
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val failures = mutableListOf<FileOpFailure>()
            var successCount = 0
            val fromDir = File(fromPath)
            val toDir = File(toPath)
            val from = fromDir.canonicalPath
            val to = toDir.canonicalPath
            when {
                !fromDir.isDirectory -> failures += FileOpFailure(fromDir.name, "La carpeta de origen no existe o no se puede leer")
                from == to || to.startsWith("$from/") || from.startsWith("$to/") ->
                    failures += FileOpFailure(fromDir.name, "El origen y el destino no pueden estar uno dentro del otro")
                !toDir.exists() && !toDir.mkdirs() -> failures += FileOpFailure(toDir.name, "No se pudo crear la carpeta de destino")
            }
            if (failures.isNotEmpty()) {
                withContext(Dispatchers.Main) { onComplete(0, failures) }
                return@launch
            }

            val audio = setOf("mp3", "flac", "m4a", "wav", "ogg", "aac", "opus", "wma", "m4b", "aiff", "aif", "alac", "ape")
            val extras = setOf("lrc", "jpg", "jpeg", "png", "webp")
            val files = fromDir.walkTopDown()
                .onEnter { it == fromDir || !it.name.startsWith(".") || it.name == ".covers" }
                .filter { f ->
                    f.isFile && !f.name.startsWith(".kmp_") &&
                        (f.extension.lowercase() in audio || f.extension.lowercase() in extras || f.parentFile?.name == ".covers")
                }
                .toList()
            val scanPaths = mutableListOf<String>()
            files.forEachIndexed { index, file ->
                withContext(Dispatchers.Main) { onProgress(index + 1, files.size, file.name) }
                val target = File(toDir, file.relativeTo(fromDir).path)
                val reason = try { moveFile(file, target) } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
                if (reason == null) {
                    if (file.extension.lowercase() in audio) {
                        successCount++
                        scanPaths += file.absolutePath
                        scanPaths += target.absolutePath
                    }
                } else {
                    failures += FileOpFailure(file.relativeTo(fromDir).path, reason)
                }
            }
            // Empty folders left behind (never the origin itself)
            fromDir.walkBottomUp().filter { it != fromDir && it.isDirectory }.forEach { dir ->
                if (dir.listFiles()?.isEmpty() == true) dir.delete()
            }
            scanPaths.chunked(100).forEach { MediaScannerConnection.scanFile(context, it.toTypedArray(), null, null) }
            if (failures.isNotEmpty()) {
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logInfo(
                    context, "Migrate", "$fromPath → $toPath\n" + failures.joinToString("\n") { "${it.name}: ${it.reason}" }
                )
            }
            delay(1500L)
            onTriggerScan(true)
            withContext(Dispatchers.Main) { onComplete(successCount, failures) }
        }
    }

    /**
     * Copies a song to another volume through a hidden sibling file, checks its content and only then removes the
     * original. If the original cannot be deleted the copy is removed, so the song is never duplicated.
     * Returns null on success, or the reason it failed.
     */
    private fun moveAcrossVolumes(source: File, target: File): String? {
        val targetDir = target.parentFile ?: return "Carpeta de destino no válida"
        if (targetDir.usableSpace < source.length() + 1_048_576L) return "No hay espacio suficiente en el destino"
        val temp = File(targetDir, ".kmp_move_${target.name}")
        try {
            if (!com.kevshupp.kevmusicplayer.playback.copyAudioVerified(source, temp)) {
                temp.delete()
                return "La copia no coincidió con el original (se conservó el original)"
            }
            if (target.exists() || !temp.renameTo(target)) {
                temp.delete()
                return "No se pudo guardar en el destino"
            }
            if (!source.delete()) {
                target.delete()
                return "No se pudo borrar el original (¿la SD es de solo lectura?)"
            }
            return null
        } catch (e: Exception) {
            temp.delete()
            return e.message ?: e.javaClass.simpleName
        }
    }

    /** Moves within the same volume (rename) or across volumes (verified copy). Null on success, or the reason. */
    private fun moveFile(source: File, target: File): String? {
        if (target.exists()) return "Ya existe un archivo con ese nombre en ${target.parentFile?.name}"
        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) return "No se pudo crear la carpeta ${it.absolutePath}" }
        if (source.renameTo(target)) return null
        return moveAcrossVolumes(source, target)
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
