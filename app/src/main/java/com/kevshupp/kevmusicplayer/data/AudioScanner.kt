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
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.TRACK
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
                val yearColumn = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val trackColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn) ?: "Unknown Title"
                    val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                    val album = cursor.getString(albumColumn) ?: "Unknown Album"
                    val duration = cursor.getLong(durationColumn)
                    val dataPath = cursor.getString(dataColumn) ?: ""
                    val dateAddedSec = cursor.getLong(dateAddedColumn)
                    val dateAddedMs = dateAddedSec * 1000L
                    val dateModifiedSec = cursor.getLong(dateModifiedColumn)
                    val dateModifiedMs = dateModifiedSec * 1000L

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

                    // Extract year from MediaStore
                    val yearVal = if (yearColumn != -1) cursor.getInt(yearColumn) else 0
                    val year = if (yearVal > 0) yearVal.toString() else ""
                    val trackVal = if (trackColumn != -1) cursor.getInt(trackColumn) else 0

                    // Check local DB cache to preserve user metadata and playback stats
                    val cachedFile = existingFiles[id]
                    val replayGain: Float? = cachedFile?.replayGain
                    val playCount = cachedFile?.playCount ?: 0
                    val lastPlayed = cachedFile?.lastPlayed ?: 0L
                    val lyrics = cachedFile?.lyrics
                    val translatedLyrics = cachedFile?.translatedLyrics

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
                            replayGain = replayGain,
                            year = year,
                            dateModified = dateModifiedMs,
                            track = trackVal,
                            playCount = playCount,
                            lastPlayed = lastPlayed,
                            lyrics = lyrics,
                            translatedLyrics = translatedLyrics
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                context,
                "AudioScanner",
                "Failed to scan audio files from MediaStore",
                e
            )
            return@withContext null
        }
        return@withContext audioList
    }
}
