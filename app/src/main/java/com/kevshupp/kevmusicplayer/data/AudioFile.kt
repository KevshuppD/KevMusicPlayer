package com.kevshupp.kevmusicplayer.data

import android.net.Uri
import kotlinx.serialization.Serializable

@Serializable
data class AudioFile(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String = "Unknown Genre",
    val duration: Long,
    val uriString: String,
    val folderPath: String = "Internal Storage",
    val folderName: String = "Root",
    val lyrics: String? = null,
    val translatedLyrics: String? = null
) {
    val uri: Uri get() = Uri.parse(uriString)
}
