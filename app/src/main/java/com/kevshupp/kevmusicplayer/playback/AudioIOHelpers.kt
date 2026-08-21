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
    // 1. Save to .lrc file next to the song
    try {
        val cleanTitle = songTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val lrcFile = File(folderPath, "$cleanTitle.lrc")
        lrcFile.writeText(lyrics)
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 2. Save directly inside the song metadata using jaudiotagger
    writeMetadataWithTempFile(context, songId, null) { audioFile ->
        val tag = audioFile.getTagOrCreateAndSetDefault()
        tag.setField(FieldKey.LYRICS, lyrics)
        audioFile.tag = tag
    }
}

fun saveTranslatedLyricsPhysical(context: android.content.Context, songId: Long, songTitle: String, folderPath: String, translatedLyrics: String?) {
    if (translatedLyrics.isNullOrBlank()) return
    try {
        val cleanTitle = songTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val locale = java.util.Locale.getDefault().language
        val lrcFile = File(folderPath, "$cleanTitle.$locale.lrc")
        lrcFile.writeText(translatedLyrics)
    } catch (e: Exception) {
        e.printStackTrace()
    }

    writeMetadataWithTempFile(context, songId, null) { audioFile ->
        val tag = audioFile.getTagOrCreateAndSetDefault()
        val locale = java.util.Locale.getDefault().language
        tag.setField(FieldKey.CUSTOM1, "TRANSLATED_LYRICS_$locale:$translatedLyrics")
        audioFile.tag = tag
    }
}

fun readLocalLrcOrEmbedded(context: android.content.Context, song: AudioFile): String? {
    // 1. Try reading .lrc file next to the song
    try {
        val cleanTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val lrcFile = File(song.folderPath, "$cleanTitle.lrc")
        if (lrcFile.exists() && lrcFile.isFile) {
            val content = lrcFile.readText()
            if (content.isNotBlank()) {
                return content
            }
        }
    } catch (e: java.lang.Exception) {
        e.printStackTrace()
    }

    // 2. Try reading inside the song metadata using jaudiotagger
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
    } catch (e: java.lang.Exception) {
        e.printStackTrace()
    }

    return null
}

fun readLocalTranslatedLrcOrEmbedded(context: android.content.Context, song: AudioFile): String? {
    val locale = java.util.Locale.getDefault().language
    try {
        val cleanTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val lrcFile = File(song.folderPath, "$cleanTitle.$locale.lrc")
        if (lrcFile.exists() && lrcFile.isFile) {
            val content = lrcFile.readText()
            if (content.isNotBlank()) {
                return content
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    try {
        val physicalPath = getPhysicalPath(context, song.id, song.uriString)
        if (!physicalPath.isNullOrBlank()) {
            val f = File(physicalPath)
            if (f.exists() && f.isFile) {
                val audioFile = safeReadAudioFile(f)
                val tag = audioFile.tag
                if (tag != null) {
                    val embedded = tag.getFirst(FieldKey.CUSTOM1)
                    if (!embedded.isNullOrBlank() && embedded.startsWith("TRANSLATED_LYRICS_$locale:")) {
                        return embedded.substringAfter("TRANSLATED_LYRICS_$locale:")
                    }
                }
            }
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
    val mimeType = getMimeTypeFromBytes(coverBytes)

    return try {
        val artwork = org.jaudiotagger.tag.images.ArtworkFactory.getNew()
        artwork.binaryData = coverBytes
        artwork.mimeType = mimeType
        artwork.pictureType = org.jaudiotagger.tag.reference.PictureTypes.DEFAULT_ID

        if (artwork is org.jaudiotagger.tag.images.AndroidArtwork) {
            try {
                artwork.setImageFromData()
            } catch (t: Throwable) {
                android.util.Log.w("createArtwork", "setImageFromData failed", t)
            }
        }

        try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                artwork.width = options.outWidth
                artwork.height = options.outHeight
            } else {
                artwork.width = 800
                artwork.height = 800
            }
        } catch (e: Throwable) {
            artwork.width = 800
            artwork.height = 800
        }

        artwork
    } catch (e: Throwable) {
        android.util.Log.e("createArtwork", "Failed to create Artwork via ArtworkFactory", e)
        try {
            val androidArtwork = org.jaudiotagger.tag.images.AndroidArtwork()
            androidArtwork.binaryData = coverBytes
            androidArtwork.mimeType = mimeType
            androidArtwork.pictureType = org.jaudiotagger.tag.reference.PictureTypes.DEFAULT_ID
            try {
                androidArtwork.setImageFromData()
            } catch (t: Throwable) {}
            androidArtwork.width = 800
            androidArtwork.height = 800
            androidArtwork
        } catch (ex: Throwable) {
            null
        }
    }
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

fun saveFolderCoverArt(context: android.content.Context, physicalPathOrFolder: String, coverBytes: ByteArray) {
    try {
        android.util.Log.d("FolderCover", "saveFolderCoverArt called for path: $physicalPathOrFolder with ${coverBytes.size} bytes")
        
        var realPath: String? = physicalPathOrFolder
        if (physicalPathOrFolder.startsWith("content://") || physicalPathOrFolder.startsWith("file://")) {
            realPath = getPhysicalPath(context, 0L, physicalPathOrFolder)
            if (realPath.isNullOrBlank() && physicalPathOrFolder.startsWith("file://")) {
                realPath = try { android.net.Uri.parse(physicalPathOrFolder).path } catch (e: Exception) { null }
            }
        }
        
        if (realPath.isNullOrBlank()) {
            android.util.Log.w("FolderCover", "Could not resolve real file system path for: $physicalPathOrFolder")
            return
        }

        val target = java.io.File(realPath)
        val parentDir = if (target.isDirectory) target else target.parentFile
        if (parentDir != null && (parentDir.exists() || parentDir.mkdirs())) {
            // Write ONLY 1 canonical cover image file: cover.jpg
            val coverFile = java.io.File(parentDir, "cover.jpg")
            var written = false
            try {
                coverFile.outputStream().use { out ->
                    out.write(coverBytes)
                }
                written = true
                android.util.Log.d("FolderCover", "Successfully wrote cover.jpg in ${coverFile.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.w("FolderCover", "Direct outputStream failed writing cover.jpg in ${parentDir.absolutePath}, attempting MediaStore fallback", e)
            }

            if (!written) {
                try {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "cover.jpg")
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            val relativePath = parentDir.absolutePath.substringAfter("/storage/emulated/0/")
                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                        } else {
                            put(android.provider.MediaStore.Images.Media.DATA, coverFile.absolutePath)
                        }
                    }
                    val imageUri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (imageUri != null) {
                        context.contentResolver.openOutputStream(imageUri)?.use { out ->
                            out.write(coverBytes)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                            context.contentResolver.update(imageUri, values, null, null)
                        }
                        written = true
                        android.util.Log.d("FolderCover", "Successfully inserted cover.jpg via MediaStore for ${parentDir.absolutePath}")
                    }
                } catch (ex: Exception) {
                    android.util.Log.e("FolderCover", "MediaStore fallback also failed for ${parentDir.absolutePath}", ex)
                }
            }

            if (written && coverFile.exists()) {
                try {
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        arrayOf(coverFile.absolutePath),
                        arrayOf("image/jpeg")
                    ) { path, uri ->
                        android.util.Log.d("FolderCover", "MediaScanner scanned cover.jpg at $path -> $uri")
                    }
                } catch (e: Exception) {}
            }

            // Delete old/redundant duplicate files if they exist (folder.jpg, album.jpg, front.jpg)
            listOf("folder.jpg", "album.jpg", "front.jpg", "folder.png", "album.png", "front.png", "cover.png").forEach { name ->
                try {
                    val redundantFile = java.io.File(parentDir, name)
                    if (redundantFile.exists() && !redundantFile.name.equals(coverFile.name, ignoreCase = true)) {
                        redundantFile.delete()
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        } else {
            android.util.Log.w("FolderCover", "parentDir is null or cannot be created for path: $realPath")
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

fun writeMp3TagsWithMp3Agic(
    filePath: String,
    title: String? = null,
    artist: String? = null,
    album: String? = null,
    genre: String? = null,
    coverBytes: ByteArray? = null
): Boolean {
    return try {
        val mp3file = com.mpatric.mp3agic.Mp3File(filePath)
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

        val tempSavePath = "$filePath.tmp_save"
        mp3file.save(tempSavePath)

        val origFile = java.io.File(filePath)
        val tmpFile = java.io.File(tempSavePath)

        if (tmpFile.exists() && tmpFile.length() > 0) {
            val renamed = tmpFile.renameTo(origFile)
            if (!renamed) {
                origFile.delete()
                tmpFile.renameTo(origFile)
            }
            android.util.Log.d("Mp3Agic", "Successfully saved updated MP3 file at $filePath")
            true
        } else {
            false
        }
    } catch (e: Exception) {
        android.util.Log.e("Mp3Agic", "Failed to write MP3 tags with mp3agic for $filePath", e)
        false
    }
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
    return try {
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
        
        // 2. Open and modify tag in temp file (running in Android mode)
        try {
            org.jaudiotagger.tag.TagOptionSingleton.getInstance().setAndroid(true)
        } catch (t: Throwable) {
            android.util.Log.e("MetadataWrite", "Failed to set jaudiotagger android mode", t)
        }
        val audioFile = safeReadAudioFile(tempFile)
        block(audioFile)
        AudioFileIO.write(audioFile)
        android.util.Log.d("MetadataWrite", "Successfully wrote tags to temp file.")
        
        // 3. Write temp file back to original source
        var writtenDirectly = false
        try {
            val destFile = File(physicalPath)
            tempFile.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            writtenDirectly = true
            android.util.Log.d("MetadataWrite", "Successfully copied temp back directly to physical path: $physicalPath")
        } catch (e: Exception) {
            android.util.Log.w("MetadataWrite", "Failed direct physical write, trying fallback", e)
        }
        
        if (!writtenDirectly) {
            // Fallback to ContentResolver write-truncate
            context.contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: run {
                android.util.Log.e("MetadataWrite", "Fallback openOutputStream returned null")
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    context, "MetadataWrite", 
                    "Fallback openOutputStream returned null for songId $songId (uri: $uri)"
                )
                return false
            }
            android.util.Log.d("MetadataWrite", "Successfully copied temp back via ContentResolver fallback")
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
    }
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

