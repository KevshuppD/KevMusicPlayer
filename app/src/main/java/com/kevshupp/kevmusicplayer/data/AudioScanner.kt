package com.kevshupp.kevmusicplayer.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioScanner(private val context: Context) {

    suspend fun scanAudioFiles(): List<AudioFile>? = withContext(Dispatchers.IO) {
        val audioList = mutableListOf<AudioFile>()
        
        // Batch query all genres first for blazing fast performance
        val genresMap = getAllGenresMap()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED
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

                    // Get genre from our pre-cached map (O(1) lookup!)
                    val genre = genresMap[id] ?: "Unknown Genre"

                    // Try to read ReplayGain metadata from physical files
                    var replayGain: Float? = null
                    if (dataPath.isNotEmpty()) {
                        try {
                            val file = java.io.File(dataPath)
                            if (file.exists()) {
                                val audioFile = org.jaudiotagger.audio.AudioFileIO.read(file)
                                val tag = audioFile.tag
                                if (tag != null) {
                                    // Look for track or album replay gain tags
                                    var gainStr = tag.getFirst("REPLAYGAIN_TRACK_GAIN")
                                    if (gainStr.isNullOrEmpty()) {
                                        gainStr = tag.getFirst("replaygain_track_gain")
                                    }
                                    if (gainStr.isNullOrEmpty()) {
                                        gainStr = tag.getFirst("REPLAYGAIN_ALBUM_GAIN")
                                    }
                                    if (gainStr.isNullOrEmpty()) {
                                        gainStr = tag.getFirst("replaygain_album_gain")
                                    }
                                    if (gainStr.isNullOrEmpty()) {
                                        gainStr = tag.getFirst("LOUDNESS")
                                    }
                                    if (gainStr.isNullOrEmpty()) {
                                        gainStr = tag.getFirst("loudness")
                                    }
                                    if (!gainStr.isNullOrEmpty()) {
                                        val cleanGain = gainStr.replace("dB", "").trim()
                                        replayGain = cleanGain.toFloatOrNull()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Suppress errors during tag scanning to keep scanner robust
                        }
                    }

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

    private fun getAllGenresMap(): Map<Long, String> {
        val genreMap = mutableMapOf<Long, String>()
        try {
            val genresProjection = arrayOf(
                MediaStore.Audio.Genres._ID,
                MediaStore.Audio.Genres.NAME
            )
            context.contentResolver.query(
                MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                genresProjection,
                null,
                null,
                null
            )?.use { genresCursor ->
                val genreIdColumn = genresCursor.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
                val genreNameColumn = genresCursor.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
                
                while (genresCursor.moveToNext()) {
                    val genreId = genresCursor.getLong(genreIdColumn)
                    val genreName = genresCursor.getString(genreNameColumn) ?: continue
                    
                    val membersUri = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
                    val membersProjection = arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID)
                    
                    context.contentResolver.query(
                        membersUri,
                        membersProjection,
                        null,
                        null,
                        null
                    )?.use { membersCursor ->
                        val audioIdColumn = membersCursor.getColumnIndexOrThrow(MediaStore.Audio.Genres.Members.AUDIO_ID)
                        while (membersCursor.moveToNext()) {
                            val audioId = membersCursor.getLong(audioIdColumn)
                            genreMap[audioId] = genreName
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return genreMap
    }
}
