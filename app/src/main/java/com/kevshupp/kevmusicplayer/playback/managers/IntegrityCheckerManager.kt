package com.kevshupp.kevmusicplayer.playback.managers

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.kevshupp.kevmusicplayer.data.AudioFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IntegrityCheckerManager(
    private val localAudioFiles: SnapshotStateList<AudioFile>,
    private val scope: CoroutineScope
) {
    var isVerifyingIntegrity = mutableStateOf(false)
        private set
    var verifyIntegrityCurrent = mutableStateOf(0)
        private set
    var verifyIntegrityTotal = mutableStateOf(0)
        private set
    var verifyIntegrityCurrentName = mutableStateOf("")
        private set

    fun verifySongsIntegrity(
        context: Context,
        onComplete: (List<Pair<AudioFile, String>>) -> Unit
    ) {
        if (isVerifyingIntegrity.value) return
        isVerifyingIntegrity.value = true
        verifyIntegrityCurrent.value = 0
        verifyIntegrityTotal.value = 0
        verifyIntegrityCurrentName.value = ""
        
        scope.launch(Dispatchers.IO) {
            val songsToVerify = localAudioFiles.toList()
            withContext(Dispatchers.Main) {
                verifyIntegrityTotal.value = songsToVerify.size
            }
            val damaged = mutableListOf<Pair<AudioFile, String>>()

            songsToVerify.forEachIndexed { index, song ->
                withContext(Dispatchers.Main) {
                    verifyIntegrityCurrent.value = index + 1
                    verifyIntegrityCurrentName.value = song.title
                }
                
                var isDamaged = false
                var reason = ""
                
                // 1. Check if the URI is readable
                try {
                    val uri = Uri.parse(song.uriString)
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                        // Successfully opened file descriptor
                    } ?: run {
                        isDamaged = true
                        reason = if (java.util.Locale.getDefault().language == "es") "Archivo inaccesible o eliminado" else "File inaccessible or deleted"
                    }
                } catch (e: Exception) {
                    isDamaged = true
                    reason = if (java.util.Locale.getDefault().language == "es") "No se puede abrir el archivo" else "Cannot open file"
                }

                // 2. If it is readable, try parsing metadata to see if it's corrupted/damaged
                if (!isDamaged) {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        val uri = Uri.parse(song.uriString)
                        retriever.setDataSource(context, uri)
                        val hasAudio = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                        if (hasAudio == null) {
                            isDamaged = true
                            reason = if (java.util.Locale.getDefault().language == "es") "Archivo de audio sin pistas válidas" else "Audio file has no valid tracks"
                        }
                    } catch (e: Exception) {
                        isDamaged = true
                        reason = if (java.util.Locale.getDefault().language == "es") "Archivo de audio dañado o corrupto" else "Corrupted or damaged audio file"
                    } finally {
                        try { retriever.release() } catch (e: Exception) {}
                    }
                }

                if (isDamaged) {
                    damaged.add(Pair(song, reason))
                }
            }

            withContext(Dispatchers.Main) {
                isVerifyingIntegrity.value = false
                onComplete(damaged)
            }
        }
    }


}
