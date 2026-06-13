package com.kevshupp.kevmusicplayer.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioScanner(private val context: Context) {

    suspend fun scanAudioFiles(existingFiles: Map<Long, AudioFile> = emptyMap()): List<AudioFile>? = withContext(Dispatchers.IO) {
        val audioList = mutableListOf<AudioFile>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.GENRE
        )

        // Query ALL audio files on the device (selection = null) to ensure absolutely NO songs are missed
        // We will perform duration-based filtering in memory to ensure maximum robustness and compat
        val selection = null
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val genreColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn) ?: "Unknown Title"
                    val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                    val album = cursor.getString(albumColumn) ?: "Unknown Album"
                    val duration = cursor.getLong(durationColumn)
                    val dataPath = cursor.getString(dataColumn) ?: ""
                    val dateAddedSec = cursor.getLong(dateAddedColumn)
                    val dateAddedMs = dateAddedSec * 1000L

                    // Skip audio files that are shorter than 5 seconds (notification sounds, short recordings)
                    // but capture all actual music files on the phone
                    if (duration > 0 && duration < 5000) {
                        continue
                    }

                    // Extract folder information
                    var folderPath = "Internal Storage"
                    var folderName = "Root"
                    if (dataPath.isNotEmpty()) {
                        try {
                            val file = java.io.File(dataPath)
                            val parentFile = file.parentFile
                            if (parentFile != null) {
                                folderPath = parentFile.absolutePath
                                folderName = parentFile.name
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    // Get genre directly from MediaStore column
                    val genre = cursor.getString(genreColumn) ?: "Unknown Genre"

                    // Try to get ReplayGain metadata.
                    // Check local DB cache first to avoid extremely slow synchronous physical disk read/write on every startup scan!
                    val cachedFile = existingFiles[id]
                    val replayGain: Float? = cachedFile?.replayGain

                    audioList.add(
                        AudioFile(
                            id = id,
                            title = title,
                            artist = artist,
                            album = album,
                            genre = genre,
                            duration = duration,
                            uriString = contentUri.toString(),
                            folderPath = folderPath,
                            folderName = folderName,
                            dateAdded = dateAddedMs,
                            replayGain = replayGain
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
        return@withContext audioList
    }
}
