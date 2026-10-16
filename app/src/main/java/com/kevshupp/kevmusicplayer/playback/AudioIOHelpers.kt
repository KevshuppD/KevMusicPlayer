package com.kevshupp.kevmusicplayer.playback

import android.content.Context
import android.net.Uri
import com.kevshupp.kevmusicplayer.data.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

data class DuplicateItem(
    val file: AudioFile,
    val path: String,
    val sizeBytes: Long
)

data class DuplicateGroup(
    val original: DuplicateItem,
    val duplicates: List<DuplicateItem>
)

fun getPhysicalPath(context: android.content.Context, songId: Long, uriString: String? = null): String? {
    if (!uriString.isNullOrBlank()) {
        try {
            val parsedUri = Uri.parse(uriString)
            if (parsedUri.scheme == "file") {
                val path = parsedUri.path
                if (!path.isNullOrBlank() && java.io.File(path).exists()) {
                    return path
                }
            }
        } catch (e: Exception) {}
    }

    val uri = if (!uriString.isNullOrBlank()) {
        Uri.parse(uriString)
    } else {
        android.content.ContentUris.withAppendedId(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            songId
        )
    }
    val projection = arrayOf(android.provider.MediaStore.Audio.Media.DATA)
    return try {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media.DATA)
                if (idx != -1) cursor.getString(idx) else null
            } else null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun isSafeToDeleteDirectory(dir: File, baseMusicDir: File?): Boolean {
    val path = try { dir.canonicalPath } catch (e: Exception) { dir.absolutePath }
    // Never delete roots or standard storage paths
    if (path == "/" || path == "/storage" || path == "/storage/emulated" || path == "/storage/emulated/0" || path == "/sdcard") return false
    
    // Never delete hidden directories (starting with '.') or files inside them
    var checkDir: File? = dir
    while (checkDir != null) {
        if (checkDir.name.startsWith(".")) {
            return false
        }
        checkDir = checkDir.parentFile
    }
    
    try {
        val musicPublicDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC).canonicalPath
        if (path == musicPublicDir) return false
    } catch (e: Exception) {}
    
    if (baseMusicDir != null) {
        try {
            val baseCanonical = baseMusicDir.canonicalPath
            // Never delete base music folder itself or any of its parents
            if (path == baseCanonical || baseCanonical.startsWith(path)) {
                return false
            }
        } catch (e: Exception) {}
    }
    
    try {
        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).canonicalPath
        if (path == downloadDir) return false
    } catch (e: Exception) {}
    
    try {
        val dcimDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM).canonicalPath
        if (path == dcimDir) return false
    } catch (e: Exception) {}

    try {
        val documentsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS).canonicalPath
        if (path == documentsDir) return false
    } catch (e: Exception) {}
    
    return true
}

fun hasAudioFiles(dir: File): Boolean {
    val audioExtensions = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "opus", "wma", "mp4", "mkv", "mid", "amr")
    try {
        return dir.walkBottomUp().any { file ->
            file.isFile && audioExtensions.contains(file.extension.lowercase())
        }
    } catch (e: Exception) {
        return true // Safely assume it has audio files on permission errors or exceptions to prevent deletion
    }
}


fun saveLyricsPhysical(context: android.content.Context, songId: Long, songTitle: String, folderPath: String, lyrics: String) {
    // 1. Save to .lrc file next to the song (by title and by physical file name)
    try {
        val cleanTitle = songTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (folderPath.isNotBlank()) {
            val lrcFile = File(folderPath, "$cleanTitle.lrc")
            lrcFile.writeText(lyrics)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 2. Save a copy in app internal storage as a guaranteed fallback
    try {
        val lyricsDir = File(context.filesDir, "lyrics")
        if (!lyricsDir.exists()) lyricsDir.mkdirs()
        val internalLrc = File(lyricsDir, "$songId.lrc")
        internalLrc.writeText(lyrics)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun saveTranslatedLyricsPhysical(context: android.content.Context, songId: Long, songTitle: String, folderPath: String, translatedLyrics: String?) {
    if (translatedLyrics.isNullOrBlank()) return
    val locale = java.util.Locale.getDefault().language

    // 1. Save to .$locale.lrc file next to the song
    try {
        val cleanTitle = songTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (folderPath.isNotBlank()) {
            val lrcFile = File(folderPath, "$cleanTitle.$locale.lrc")
            lrcFile.writeText(translatedLyrics)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 2. Save a copy in app internal storage
    try {
        val lyricsDir = File(context.filesDir, "lyrics")
        if (!lyricsDir.exists()) lyricsDir.mkdirs()
        val internalLrc = File(lyricsDir, "$songId.$locale.lrc")
        internalLrc.writeText(translatedLyrics)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun readLocalLrcOrEmbedded(context: android.content.Context, song: AudioFile): String? {
    val cleanTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    // 1. Try reading .lrc file next to the song by clean title
    try {
        if (song.folderPath.isNotBlank()) {
            val lrcFile = File(song.folderPath, "$cleanTitle.lrc")
            if (lrcFile.exists() && lrcFile.isFile) {
                val content = lrcFile.readText()
                if (content.isNotBlank()) return content
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 2. Try reading .lrc matching the audio file's name on disk
    try {
        val physicalPath = getPhysicalPath(context, song.id, song.uriString)
        if (!physicalPath.isNullOrBlank()) {
            val audioF = File(physicalPath)
            val lrcByFileName = File(audioF.parentFile, "${audioF.nameWithoutExtension}.lrc")
            if (lrcByFileName.exists() && lrcByFileName.isFile) {
                val content = lrcByFileName.readText()
                if (content.isNotBlank()) return content
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 3. Try reading from app internal lyrics storage
    try {
        val internalLrc = File(File(context.filesDir, "lyrics"), "${song.id}.lrc")
        if (internalLrc.exists() && internalLrc.isFile) {
            val content = internalLrc.readText()
            if (content.isNotBlank()) return content
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 4. Read-only fallback: check embedded lyrics in metadata WITHOUT modifying the file
    try {
        val physicalPath = getPhysicalPath(context, song.id, song.uriString)
        if (!physicalPath.isNullOrBlank()) {
            val f = File(physicalPath)
            if (f.exists() && f.isFile) {
                val audioFile = safeReadAudioFile(f)
                val tag = audioFile.tag
                if (tag != null) {
                    val embeddedLyrics = tag.getFirst(FieldKey.LYRICS)
                    if (!embeddedLyrics.isNullOrBlank()) {
                        return embeddedLyrics
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return null
}

fun readLocalTranslatedLrcOrEmbedded(context: android.content.Context, song: AudioFile): String? {
    val locale = java.util.Locale.getDefault().language
    val cleanTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    // 1. Try reading .$locale.lrc next to the song
    try {
        if (song.folderPath.isNotBlank()) {
            val lrcFile = File(song.folderPath, "$cleanTitle.$locale.lrc")
            if (lrcFile.exists() && lrcFile.isFile) {
                val content = lrcFile.readText()
                if (content.isNotBlank()) return content
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 2. Try reading .$locale.lrc by physical file name
    try {
        val physicalPath = getPhysicalPath(context, song.id, song.uriString)
        if (!physicalPath.isNullOrBlank()) {
            val audioF = File(physicalPath)
            val lrcByFileName = File(audioF.parentFile, "${audioF.nameWithoutExtension}.$locale.lrc")
            if (lrcByFileName.exists() && lrcByFileName.isFile) {
                val content = lrcByFileName.readText()
                if (content.isNotBlank()) return content
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 3. Try reading from internal storage
    try {
        val internalLrc = File(File(context.filesDir, "lyrics"), "${song.id}.$locale.lrc")
        if (internalLrc.exists() && internalLrc.isFile) {
            val content = internalLrc.readText()
            if (content.isNotBlank()) return content
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return null
}

fun getMimeTypeFromBytes(bytes: ByteArray?): String {
    if (bytes == null || bytes.size < 4) return "image/jpeg"
    return if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
        "image/png"
    } else {
        "image/jpeg"
    }
}

fun createJaudiotaggerArtwork(coverBytes: ByteArray): org.jaudiotagger.tag.images.Artwork? {
    if (coverBytes.isEmpty()) return null

    // The library's own Android artwork throws UnsupportedOperationException in setImageFromData(), which
    // FLAC (and Ogg) tags call while building the picture block: those formats never got their cover embedded.
    // The app's AndroidArtwork carries the same data and reports the image as ready.
    val artwork = AndroidArtwork()
    artwork.binaryData = coverBytes
    artwork.mimeType = getMimeTypeFromBytes(coverBytes)
    artwork.description = ""
    artwork.pictureType = org.jaudiotagger.tag.reference.PictureTypes.DEFAULT_ID
    artwork.width = 800
    artwork.height = 800
    try {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            artwork.width = options.outWidth
            artwork.height = options.outHeight
        }
    } catch (e: Throwable) {
        // Keep the default size
    }
    return artwork
}

class SafeMp4FileReader : org.jaudiotagger.audio.mp4.Mp4FileReader() {
    override fun getEncodingInfo(path: java.nio.file.Path): org.jaudiotagger.audio.generic.GenericAudioHeader {
        return try {
            val header = super.getEncodingInfo(path)
            header.apply {
                try {
                    setChannelNumber(2)
                } catch (t: Throwable) {
                    // ignore
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w("SafeMp4FileReader", "Caught error in getEncodingInfo for MP4/M4A, returning safe audio header", e)
            org.jaudiotagger.audio.generic.GenericAudioHeader().apply {
                try { setChannelNumber(2) } catch (t: Throwable) {}
                try { setSamplingRate(44100) } catch (t: Throwable) {}
                try { setBitRate(256) } catch (t: Throwable) {}
                try { setPreciseLength(0.0) } catch (t: Throwable) {}
            }
        }
    }

    override fun getTag(path: java.nio.file.Path): org.jaudiotagger.tag.Tag {
        return try {
            super.getTag(path)
        } catch (e: Throwable) {
            android.util.Log.w("SafeMp4FileReader", "Caught error in getTag for MP4/M4A, returning empty Mp4Tag", e)
            org.jaudiotagger.tag.mp4.Mp4Tag()
        }
    }
}

fun safeReadAudioFile(file: File): org.jaudiotagger.audio.AudioFile {
    val ext = file.extension.lowercase()
    return if (ext in listOf("m4a", "mp4")) {
        try {
            val af = SafeMp4FileReader().read(file)
            af.setExt(ext)
            af
        } catch (e: Throwable) {
            try {
                AudioFileIO.read(file)
            } catch (e2: Throwable) {
                org.jaudiotagger.audio.AudioFile(
                    file,
                    org.jaudiotagger.audio.generic.GenericAudioHeader().apply {
                        try { setChannelNumber(2) } catch (t: Throwable) {}
                        try { setSamplingRate(44100) } catch (t: Throwable) {}
                        try { setBitRate(256) } catch (t: Throwable) {}
                        try { setPreciseLength(0.0) } catch (t: Throwable) {}
                    },
                    org.jaudiotagger.tag.mp4.Mp4Tag()
                ).apply { setExt(ext) }
            }
        }
    } else {
        AudioFileIO.read(file)
    }
}

fun writeMetadataWithTagLib(context: android.content.Context, physicalPath: String, title: String? = null, artist: String? = null, album: String? = null, genre: String? = null, coverBytes: ByteArray? = null): Boolean {
    // Native TagLib JNI file descriptor operations trigger Android 10+ fdsan SIGABRT crashes on detached FDs.
    // Metadata and cover art writing is safely handled by Java jaudiotagger (writeMetadataWithTempFile).
    return false
}

/** File name used for a cover saved by [saveFolderCoverArt]; the loader uses the same name to find it again. */
fun folderCoverFileName(artist: String?, albumOrTitle: String?): String {
    val cleanArtist = artist?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.trim() ?: ""
    val cleanAlbumOrTitle = albumOrTitle?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.trim() ?: ""
    return when {
        cleanArtist.isNotBlank() && cleanAlbumOrTitle.isNotBlank() -> "cover_${cleanArtist}_$cleanAlbumOrTitle.jpg"
        cleanAlbumOrTitle.isNotBlank() -> "cover_$cleanAlbumOrTitle.jpg"
        cleanArtist.isNotBlank() -> "cover_$cleanArtist.jpg"
        else -> "cover.jpg"
    }
}

/**
 * Finds the cover saved for this specific song's album, in the hidden '.covers' folder next to the audio
 * file or in the app's private covers directory. Only a file saved for the same artist/album matches, so
 * a song without art never borrows another album's cover. Must be called off the main thread.
 */
fun findSavedCoverFile(context: android.content.Context, uriString: String, physicalPath: String?): File? {
    val songId = uriString.substringAfterLast("/").toLongOrNull() ?: return null
    val info = try {
        com.kevshupp.kevmusicplayer.data.AppDatabase.getDatabase(context).audioDao().getCoverLookupInfoBlocking(songId)
    } catch (e: Exception) {
        null
    } ?: return null
    val coverFileName = folderCoverFileName(info.artist, info.album.ifBlank { info.title })

    if (!physicalPath.isNullOrBlank()) {
        val parentDir = File(physicalPath).parentFile
        if (parentDir != null) {
            val hiddenCover = File(File(parentDir, ".covers"), coverFileName)
            if (hiddenCover.isFile && hiddenCover.length() > 0) return hiddenCover
        }
    }

    val internalCover = File(File(context.filesDir, "covers"), coverFileName)
    if (internalCover.isFile && internalCover.length() > 0) return internalCover
    return null
}

fun saveFolderCoverArt(
    context: android.content.Context,
    physicalPathOrFolder: String,
    coverBytes: ByteArray,
    artist: String? = null,
    albumOrTitle: String? = null
) {
    try {
        if (coverBytes.isEmpty()) return

        var realPath: String? = physicalPathOrFolder
        if (physicalPathOrFolder.startsWith("content://") || physicalPathOrFolder.startsWith("file://")) {
            realPath = getPhysicalPath(context, 0L, physicalPathOrFolder)
            if (realPath.isNullOrBlank() && physicalPathOrFolder.startsWith("file://")) {
                realPath = try { android.net.Uri.parse(physicalPathOrFolder).path } catch (e: Exception) { null }
            }
        }
        
        val coverFileName = folderCoverFileName(artist, albumOrTitle)

        // 1. Save in app internal private storage (100% hidden and invisible to gallery apps)
        try {
            val internalCoversDir = java.io.File(context.filesDir, "covers")
            if (!internalCoversDir.exists()) internalCoversDir.mkdirs()
            val internalCoverFile = java.io.File(internalCoversDir, coverFileName)
            internalCoverFile.writeBytes(coverBytes)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Save in physical folder under hidden '.covers' with '.nomedia' to protect user gallery
        if (!realPath.isNullOrBlank()) {
            val target = java.io.File(realPath)
            val parentDir = if (target.isDirectory) target else target.parentFile
            if (parentDir != null && (parentDir.exists() || parentDir.mkdirs())) {
                val hiddenDir = java.io.File(parentDir, ".covers")
                if (!hiddenDir.exists()) {
                    hiddenDir.mkdirs()
                }
                // Ensure .nomedia exists so gallery/media scanner ignores all files inside
                val noMediaFile = java.io.File(hiddenDir, ".nomedia")
                if (!noMediaFile.exists()) {
                    try { noMediaFile.createNewFile() } catch (e: Exception) {}
                }

                val hiddenCoverFile = java.io.File(hiddenDir, coverFileName)
                try {
                    hiddenCoverFile.outputStream().use { out ->
                        out.write(coverBytes)
                    }
                    android.util.Log.d("FolderCover", "Successfully saved hidden cover at: ${hiddenCoverFile.absolutePath}")
                } catch (e: Exception) {
                    android.util.Log.w("FolderCover", "Failed saving hidden cover in ${hiddenDir.absolutePath}", e)
                }

                // Clean up any old visible loose cover files from parentDir (cover.jpg, folder.jpg)
                // so they stop cluttering the user's photo gallery / file manager
                listOf(
                    "cover.jpg", "folder.jpg", "album.jpg", "front.jpg", "artwork.jpg",
                    "Cover.jpg", "Folder.jpg", "Album.jpg", "Front.jpg", "cover.png", "folder.png"
                ).forEach { name ->
                    try {
                        val looseFile = java.io.File(parentDir, name)
                        if (looseFile.exists() && looseFile.isFile) {
                            looseFile.delete()
                        }
                    } catch (e: Exception) {}
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("FolderCover", "Error in saveFolderCoverArt for $physicalPathOrFolder", e)
    }
}

fun invalidateMediaStoreAlbumArt(context: android.content.Context, songId: Long, uriString: String?) {
    try {
        val songUri = if (!uriString.isNullOrBlank()) android.net.Uri.parse(uriString) else null
        val songIdFromUri = songUri?.lastPathSegment?.toLongOrNull() ?: songId

        var albumId: Long? = null
        val projection = arrayOf(android.provider.MediaStore.Audio.Media.ALBUM_ID)
        val selection = "${android.provider.MediaStore.Audio.Media._ID} = ?"
        val selectionArgs = arrayOf(songIdFromUri.toString())

        context.contentResolver.query(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                albumId = cursor.getLong(0)
            }
        }

        if (albumId != null && albumId!! > 0) {
            val albumArtUri = android.content.ContentUris.withAppendedId(
                android.net.Uri.parse("content://media/external/audio/albumart"),
                albumId!!
            )
            try {
                context.contentResolver.delete(albumArtUri, null, null)
                android.util.Log.d("MediaStoreArt", "Invalidated MediaStore album art URI: $albumArtUri")
            } catch (e: Exception) {
                android.util.Log.w("MediaStoreArt", "Could not delete MediaStore album art entry", e)
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("MediaStoreArt", "Error invalidating MediaStore album art", e)
    }
}

// Serializes every physical tag write. Two writers on the same song (an album cover and a song edit launched
// together) used to read a file the other one was halfway through rewriting, and saved that truncated copy back.
private val audioFileWriteLock = Any()

/**
 * Replaces the audio file at [destPath] with [newContent] without ever leaving a partial file behind.
 * The new content is copied to a hidden sibling, verified and renamed over the original, so a reader never sees
 * a truncated song (ExoPlayer keeps playing the old file it has open). When the rename is not possible it falls
 * back to an in-place copy that restores the original if it cannot be completed.
 */
fun replaceAudioFileSafely(context: android.content.Context, newContent: File, destPath: String, uri: Uri): Boolean {
    val expectedSize = newContent.length()
    if (expectedSize <= 0L) return false
    val destFile = File(destPath)

    val sibling = File(destFile.parentFile, ".kmp_write_${destFile.name}")
    try {
        if (sibling.exists()) sibling.delete()
        if (copyAudioVerified(newContent, sibling) && sibling.renameTo(destFile) && destFile.length() == expectedSize) {
            return true
        }
    } catch (e: Exception) {
        android.util.Log.w("MetadataWrite", "Atomic replace failed for $destPath, falling back to in-place copy", e)
    } finally {
        try { if (sibling.exists()) sibling.delete() } catch (e: Exception) {}
    }

    // In-place fallback. Overwriting destroys the original, so it is saved first and put back if the copy
    // fails (a full SD card used to leave the song cut at whatever had been written).
    val backup = File(context.cacheDir, "kmp_backup_${System.currentTimeMillis()}_${destFile.name}")
    try {
        val originalSize = destFile.length()
        val freeBytes = destFile.parentFile?.usableSpace ?: 0L
        if (freeBytes + originalSize < expectedSize) {
            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                context, "MetadataWrite",
                "Not enough space to rewrite $destPath (needs $expectedSize bytes, $freeBytes free). File left untouched"
            )
            return false
        }
        destFile.inputStream().use { input -> backup.outputStream().use { output -> input.copyTo(output) } }
        if (backup.length() != originalSize) return false

        if (copyOverFile(context, newContent, destFile, uri)) return true

        val restored = copyOverFile(context, backup, destFile, uri)
        com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
            context, "MetadataWrite",
            "Rewrite failed for $destPath (needed $expectedSize bytes, ${destFile.parentFile?.usableSpace} free). Original restored: $restored"
        )
        if (!restored) {
            // Last resort: keep the only complete copy of the song instead of deleting it with the temp files
            val rescued = File(context.getExternalFilesDir(null) ?: context.filesDir, "rescued_${destFile.name}")
            try { backup.copyTo(rescued, overwrite = true) } catch (e: Exception) {}
            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                context, "MetadataWrite", "Original of $destPath saved at ${rescued.absolutePath}"
            )
        }
        return false
    } catch (e: Exception) {
        android.util.Log.e("MetadataWrite", "In-place replace failed for $destPath", e)
        return false
    } finally {
        try { backup.delete() } catch (e: Exception) {}
    }
}

private fun copyOverFile(context: android.content.Context, source: File, destFile: File, uri: Uri): Boolean {
    val expectedSize = source.length()
    try {
        source.inputStream().use { input ->
            java.io.FileOutputStream(destFile).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    } catch (e: Exception) {
        try {
            context.contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
        } catch (e2: Exception) {
            android.util.Log.w("MetadataWrite", "In-place copy failed for ${destFile.absolutePath}", e2)
        }
    }
    return destFile.length() == expectedSize
}

fun writeMp3TagsWithMp3Agic(
    context: android.content.Context,
    songId: Long,
    uriString: String? = null,
    filePath: String,
    title: String? = null,
    artist: String? = null,
    album: String? = null,
    genre: String? = null,
    coverBytes: ByteArray? = null
): Boolean {
    val uri = if (!uriString.isNullOrBlank()) {
        Uri.parse(uriString)
    } else {
        android.content.ContentUris.withAppendedId(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            songId
        )
    }

    var tempSaveFile: File? = null
    var tempSourceFile: File? = null

    return synchronized(audioFileWriteLock) { try {
        val origFile = File(filePath)
        val sourceFile = if (origFile.exists() && origFile.canRead()) {
            origFile
        } else {
            tempSourceFile = File(context.cacheDir, "temp_mp3_src_${System.currentTimeMillis()}_$songId.mp3")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempSourceFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempSourceFile
        }

        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            android.util.Log.e("Mp3Agic", "Source MP3 file does not exist or is empty: $filePath")
            return false
        }

        val mp3file = com.mpatric.mp3agic.Mp3File(sourceFile.absolutePath)
        val id3v2Tag = if (mp3file.hasId3v2Tag()) {
            mp3file.id3v2Tag
        } else {
            val newTag = com.mpatric.mp3agic.ID3v23Tag()
            mp3file.id3v2Tag = newTag
            newTag
        }

        if (!title.isNullOrBlank()) id3v2Tag.title = title
        if (!artist.isNullOrBlank()) id3v2Tag.artist = artist
        if (!album.isNullOrBlank()) id3v2Tag.album = album
        if (!genre.isNullOrBlank()) {
            try {
                id3v2Tag.genreDescription = genre
            } catch (e: Exception) {}
        }

        if (coverBytes != null && coverBytes.isNotEmpty()) {
            val mimeType = getMimeTypeFromBytes(coverBytes)
            id3v2Tag.setAlbumImage(coverBytes, mimeType)
            android.util.Log.d("Mp3Agic", "Set album image on ID3v2 tag (${coverBytes.size} bytes, $mimeType) for $filePath")
        }

        tempSaveFile = File(context.cacheDir, "temp_mp3_save_${System.currentTimeMillis()}_$songId.mp3")
        mp3file.save(tempSaveFile.absolutePath)

        if (!tempSaveFile.exists() || tempSaveFile.length() < 1024) {
            android.util.Log.e("Mp3Agic", "Temp save file is invalid or too small: ${tempSaveFile.length()}")
            return false
        }

        // Only the tag may change: the rewritten copy must carry exactly the same audio frames
        val savedFile = com.mpatric.mp3agic.Mp3File(tempSaveFile.absolutePath)
        val originalAudioBytes = mp3file.endOffset - mp3file.startOffset
        val savedAudioBytes = savedFile.endOffset - savedFile.startOffset
        if (savedFile.frameCount != mp3file.frameCount || savedAudioBytes != originalAudioBytes) {
            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                context, "MetadataWrite",
                "Aborted MP3 write for songId $songId: audio changed (frames ${mp3file.frameCount} -> ${savedFile.frameCount}, bytes $originalAudioBytes -> $savedAudioBytes)"
            )
            return false
        }

        if (!replaceAudioFileSafely(context, tempSaveFile, filePath, uri)) {
            android.util.Log.e("Mp3Agic", "Could not write the updated MP3 back to $filePath")
            return false
        }

        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(filePath),
            arrayOf("audio/mpeg")
        ) { _, _ -> }

        android.util.Log.d("Mp3Agic", "Successfully saved updated MP3 file at $filePath")
        true
    } catch (e: Exception) {
        android.util.Log.e("Mp3Agic", "Failed to write MP3 tags with mp3agic for $filePath", e)
        false
    } finally {
        try { tempSaveFile?.delete() } catch (e: Exception) {}
        try { tempSourceFile?.delete() } catch (e: Exception) {}
    } }
}

fun writeMetadataWithTempFile(context: android.content.Context, songId: Long, uriString: String?, block: (org.jaudiotagger.audio.AudioFile) -> Unit): Boolean {
    val physicalPath = getPhysicalPath(context, songId, uriString)
    if (physicalPath == null) {
        com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
            context, "MetadataWrite", 
            "Failed to get physical path for songId $songId (uri: $uriString)"
        )
        return false
    }
    val uri = if (!uriString.isNullOrBlank()) {
        Uri.parse(uriString)
    } else {
        android.content.ContentUris.withAppendedId(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            songId
        )
    }
    var tempFile: File? = null
    return synchronized(audioFileWriteLock) { try {
        // 1. Copy source file to temp file using the same extension so jaudiotagger can detect format
        val extension = File(physicalPath).extension
        val suffix = if (extension.isNotEmpty()) ".$extension" else ""
        tempFile = File(context.cacheDir, "temp_jaudiotagger_${System.currentTimeMillis()}_${songId}$suffix")
        
        android.util.Log.d("MetadataWrite", "Copying original file to temp: $tempFile")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: run {
            android.util.Log.e("MetadataWrite", "Failed to open input stream for URI: $uri")
            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                context, "MetadataWrite", 
                "Failed to open input stream for songId $songId (uri: $uri)"
            )
            return false
        }
        
        val sourceFile = File(physicalPath)
        val originalSize = if (sourceFile.exists()) sourceFile.length() else 0L
        if (originalSize > 0L && tempFile.length() != originalSize) {
            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                context, "MetadataWrite",
                "Aborted write for songId $songId: incomplete working copy (${tempFile.length()} of $originalSize bytes)"
            )
            return false
        }

        // 2. Open and modify tag in temp file (running in Android mode)
        try {
            org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true)
        } catch (t: Throwable) {
            android.util.Log.e("MetadataWrite", "Failed to set jaudiotagger android mode", t)
        }
        val audioFile = safeReadAudioFile(tempFile)
        val originalTrackLength = audioFile.audioHeader.trackLength
        block(audioFile)
        AudioFileIO.write(audioFile)
        android.util.Log.d("MetadataWrite", "Successfully wrote tags to temp file.")

        // Only the tag may change: the rewritten copy must still decode to the same length
        val writtenTrackLength = safeReadAudioFile(tempFile).audioHeader.trackLength
        if (kotlin.math.abs(writtenTrackLength - originalTrackLength) > 1) {
            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                context, "MetadataWrite",
                "Aborted write for songId $songId: track length changed (${originalTrackLength}s -> ${writtenTrackLength}s)"
            )
            return false
        }

        // Safety Guard: Verify temp file is not empty and hasn't lost audio payload!
        val tempSize = tempFile.length()
        if (tempSize < 1024 || (originalSize > 10240 && tempSize < originalSize * 0.4)) {
            android.util.Log.e("MetadataWrite", "Aborting write! Temp file size ($tempSize) is abnormally small compared to original ($originalSize)")
            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                context, "MetadataWrite",
                "Aborted write to avoid corrupting file for songId $songId. OrigSize: $originalSize, TempSize: $tempSize"
            )
            return false
        }
        
        // 3. Write temp file back to original source safely
        if (!replaceAudioFileSafely(context, tempFile, physicalPath, uri)) {
            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                context, "MetadataWrite",
                "Could not write the updated file back for songId $songId (path: $physicalPath)"
            )
            return false
        }

        // 4. Force system to scan media
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(physicalPath),
            null
        ) { _, _ -> }
        
        true
    } catch (e: Exception) {
        android.util.Log.e("MetadataWrite", "Exception in writeMetadataWithTempFile", e)
        com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(context, "MetadataWrite", "Failed in writeMetadataWithTempFile for songId $songId (path: $physicalPath)", e)
        false
    } finally {
        try {
            tempFile?.delete()
        } catch (e: Exception) {
            // ignore
        }
    } }
}

class AndroidArtwork : org.jaudiotagger.tag.images.Artwork {
    private var binaryData: ByteArray = ByteArray(0)
    private var mimeType: String = ""
    private var description: String = ""
    private var height: Int = 0
    private var width: Int = 0
    private var pictureType: Int = 0
    private var imageUrl: String = ""
    private var linked: Boolean = false

    override fun getBinaryData(): ByteArray = binaryData
    override fun setBinaryData(p0: ByteArray) { binaryData = p0 }
    override fun getMimeType(): String = mimeType
    override fun setMimeType(p0: String) { mimeType = p0 }
    override fun getDescription(): String = description
    override fun setDescription(p0: String) { description = p0 }
    override fun getHeight(): Int = height
    override fun setHeight(p0: Int) { height = p0 }
    override fun getWidth(): Int = width
    override fun setWidth(p0: Int) { width = p0 }
    override fun getPictureType(): Int = pictureType
    override fun setPictureType(p0: Int) { pictureType = p0 }
    override fun getImageUrl(): String = imageUrl
    override fun setImageUrl(p0: String) { imageUrl = p0 }
    override fun isLinked(): Boolean = linked
    override fun setLinked(p0: Boolean) { linked = p0 }
    
    override fun setImageFromData(): Boolean {
        return true
    }
    
    override fun getImage(): Any? = null
    
    override fun setFromFile(p0: java.io.File) {
        binaryData = p0.readBytes()
    }
    
    override fun setFromMetadataBlockDataPicture(p0: org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture) {
        binaryData = p0.imageData
        mimeType = p0.mimeType
        description = p0.description
        height = p0.height
        width = p0.width
        pictureType = p0.pictureType
    }
}

fun syncLyricsAndCoverArtForMovedFile(
    context: Context,
    oldFile: File,
    newFile: File,
    targetAlbumDir: File,
    song: AudioFile
) {
    try {
        val oldBaseName = oldFile.nameWithoutExtension
        val newBaseName = newFile.nameWithoutExtension

        // 1. Move or copy sidecar lyrics files (.lrc and .txt)
        val lrcExtensions = listOf("lrc", "txt")
        lrcExtensions.forEach { ext ->
            val oldLrcFile = File(oldFile.parentFile, "$oldBaseName.$ext")
            if (oldLrcFile.exists() && oldLrcFile.isFile) {
                val newLrcFile = File(targetAlbumDir, "$newBaseName.$ext")
                try {
                    if (oldLrcFile.absolutePath != newLrcFile.absolutePath) {
                        oldLrcFile.copyTo(newLrcFile, overwrite = true)
                        oldLrcFile.delete()
                    }
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(newLrcFile.absolutePath), null, null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 2. Export database lyrics to .lrc sidecar file if no .lrc exists
        val songLyrics = song.lyrics
        if (!songLyrics.isNullOrBlank()) {
            val newLrcFile = File(targetAlbumDir, "$newBaseName.lrc")
            if (!newLrcFile.exists()) {
                try {
                    newLrcFile.writeText(songLyrics)
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(newLrcFile.absolutePath), null, null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 3. Move/copy image files from old parent folder to targetAlbumDir
        val oldParentDir = oldFile.parentFile
        if (oldParentDir != null && oldParentDir.exists() && oldParentDir.isDirectory && oldParentDir.absolutePath != targetAlbumDir.absolutePath) {
            val imageExtensions = listOf("jpg", "jpeg", "png", "webp")
            val imageFiles = oldParentDir.listFiles { file ->
                file.isFile && imageExtensions.contains(file.extension.lowercase())
            }
            imageFiles?.forEach { imgFile ->
                try {
                    val targetImgFile = File(targetAlbumDir, imgFile.name)
                    if (!targetImgFile.exists()) {
                        imgFile.copyTo(targetImgFile, overwrite = true)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}


/**
 * Copies a song to [dest], forces it to storage and reads both files back to compare their content.
 * Checking only the size is not enough: a copy can have the right length with blocks that never reached the
 * storage (they read back as zeros). Returns false, and deletes [dest], when the copy is not identical.
 */
fun copyAudioVerified(source: File, dest: File): Boolean {
    try {
        source.inputStream().use { input ->
            java.io.FileOutputStream(dest).use { output ->
                input.copyTo(output, 64 * 1024)
                output.fd.sync()
            }
        }
        if (dest.length() == source.length() && dest.length() > 0L && sha256(dest).contentEquals(sha256(source))) return true
    } catch (e: Exception) {
        android.util.Log.w("AudioCopy", "Copy to ${dest.absolutePath} failed", e)
    }
    try { dest.delete() } catch (e: Exception) {}
    return false
}

private fun sha256(file: File): ByteArray {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest()
}
