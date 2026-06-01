package com.kevshupp.kevmusicplayer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_files")
data class AudioFileEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val duration: Long,
    val uriString: String,
    val folderPath: String,
    val folderName: String,
    val lyrics: String? = null,
    val translatedLyrics: String? = null
)
