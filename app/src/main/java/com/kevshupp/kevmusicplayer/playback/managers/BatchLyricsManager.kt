package com.kevshupp.kevmusicplayer.playback.managers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.app.NotificationCompat
import com.kevshupp.kevmusicplayer.data.AudioDao
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.LyricsRepository
import com.kevshupp.kevmusicplayer.playback.readLocalLrcOrEmbedded
import com.kevshupp.kevmusicplayer.playback.saveLyricsPhysical
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class BatchLyricsManager(
    private val audioDao: AudioDao,
    private val localAudioFiles: SnapshotStateList<AudioFile>,
    private val coroutineScope: CoroutineScope,
    private val onPlaylistsReloadNeeded: () -> Unit
) {
    val isDownloadingAllLyrics = mutableStateOf(false)
    val isDeletingAllLyrics = mutableStateOf(false)
    val downloadAllLyricsCurrent = mutableStateOf(0)
    val downloadAllLyricsTotal = mutableStateOf(0)
    val downloadAllLyricsSuccessCount = mutableStateOf(0)
    val downloadAllLyricsCurrentName = mutableStateOf("")

    private fun getLocalized(es: String, en: String): String {
        val locale = Locale.getDefault().language
        return if (locale == "es") es else en
    }

    fun downloadAllLyrics(context: Context) {
        if (isDownloadingAllLyrics.value) return
        coroutineScope.launch(Dispatchers.IO) {
            val pendingListUpdates = mutableMapOf<Long, String>()
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationId = 1001
            val notificationIdSummary = 1002

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "lyrics_download_channel",
                    getLocalized("Descargador de Letras", "Lyrics Downloader"),
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(channel)
            }

            var downloadedSuccessCount = 0
            var skippedCount = 0
            var notFoundCount = 0
            var errorCount = 0

            try {
                isDownloadingAllLyrics.value = true
                downloadAllLyricsSuccessCount.value = 0
                val songsToProcess = localAudioFiles.toList()
                val total = songsToProcess.size
                downloadAllLyricsTotal.value = total

                val builder = NotificationCompat.Builder(context, "lyrics_download_channel")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(getLocalized("Descargando letras...", "Downloading lyrics..."))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)

                var lastNotifTime = 0L
                songsToProcess.forEachIndexed { index, song ->
                    if (!isDownloadingAllLyrics.value) return@launch

                    downloadAllLyricsCurrent.value = index + 1
                    downloadAllLyricsCurrentName.value = song.title

                    val now = System.currentTimeMillis()
                    if (now - lastNotifTime > 500L || index == 0 || index == total - 1) {
                        lastNotifTime = now
                        builder.setContentText("${song.title} (${index + 1}/$total)")
                            .setProgress(total, index + 1, false)
                        notificationManager.notify(notificationId, builder.build())
                    }

                    val dbHasSynced = !song.lyrics.isNullOrBlank() && LyricsRepository.isLrcSynced(song.lyrics)

                    if (dbHasSynced) {
                        skippedCount++
                    } else {
                        val localLyrics = readLocalLrcOrEmbedded(context, song)
                        val localIsSynced = !localLyrics.isNullOrBlank() && LyricsRepository.isLrcSynced(localLyrics)

                        if (localIsSynced) {
                            audioDao.updateLyrics(song.id, localLyrics)
                            pendingListUpdates[song.id] = localLyrics!!
                            skippedCount++
                        } else {
                            try {
                                val fetched = LyricsRepository.fetchLyricsFromLrcLib(song.artist, song.title)
                                if (!fetched.isNullOrEmpty()) {
                                    val fetchedIsSynced = LyricsRepository.isLrcSynced(fetched)
                                    if (fetchedIsSynced || song.lyrics.isNullOrBlank()) {
                                        audioDao.updateLyrics(song.id, fetched)
                                        pendingListUpdates[song.id] = fetched
                                        saveLyricsPhysical(context, song.id, song.title, song.folderPath, fetched)
                                        downloadedSuccessCount++
                                    } else {
                                        notFoundCount++
                                    }
                                } else {
                                    notFoundCount++
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                errorCount++
                            }
                            delay(300)
                        }
                    }

                    if (pendingListUpdates.size >= 10 || index == songsToProcess.lastIndex) {
                        val batch = pendingListUpdates.toMap()
                        pendingListUpdates.clear()
                        withContext(Dispatchers.Main) {
                            batch.forEach { (songId, lyricsText) ->
                                val listIndex = localAudioFiles.indexOfFirst { it.id == songId }
                                if (listIndex != -1) {
                                    localAudioFiles[listIndex] = localAudioFiles[listIndex].copy(lyrics = lyricsText)
                                }
                            }
                        }
                    }
                }

                notificationManager.cancel(notificationId)
                val totalWithLyricsNow = localAudioFiles.count { !it.lyrics.isNullOrBlank() }
                downloadAllLyricsSuccessCount.value = downloadedSuccessCount

                val summaryText = getLocalized(
                    "Con letra: $totalWithLyricsNow | Nuevas: $downloadedSuccessCount | Existentes: $skippedCount | No encontradas: $notFoundCount | Errores: $errorCount",
                    "With lyrics: $totalWithLyricsNow | New: $downloadedSuccessCount | Existing: $skippedCount | Not Found: $notFoundCount | Errors: $errorCount"
                )

                val summaryBuilder = NotificationCompat.Builder(context, "lyrics_download_channel")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(getLocalized("Descarga de letras finalizada", "Lyrics download finished"))
                    .setContentText(summaryText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
                    .setOngoing(false)
                    .setAutoCancel(true)
                notificationManager.notify(notificationIdSummary, summaryBuilder.build())

                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, summaryText, android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (pendingListUpdates.isNotEmpty()) {
                    val batch = pendingListUpdates.toMap()
                    pendingListUpdates.clear()
                    withContext(Dispatchers.Main) {
                        batch.forEach { (songId, lyricsText) ->
                            val listIndex = localAudioFiles.indexOfFirst { it.id == songId }
                            if (listIndex != -1) {
                                localAudioFiles[listIndex] = localAudioFiles[listIndex].copy(lyrics = lyricsText)
                            }
                        }
                    }
                }
                notificationManager.cancel(notificationId)
                withContext(Dispatchers.Main) {
                    isDownloadingAllLyrics.value = false
                    downloadAllLyricsCurrent.value = 0
                    downloadAllLyricsTotal.value = 0
                    downloadAllLyricsCurrentName.value = ""
                }
            }
        }
    }

    fun cancelDownloadAllLyrics() {
        isDownloadingAllLyrics.value = false
    }

    fun deleteAllLyrics(context: Context, onComplete: () -> Unit) {
        isDeletingAllLyrics.value = true
        coroutineScope.launch {
            try {
                audioDao.deleteAllLyrics()
                val songsToProcess = localAudioFiles.toList()

                val settingsPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                val musicFolderPath = settingsPrefs.getString("music_folder_path", null)
                val baseMusicDir = if (!musicFolderPath.isNullOrBlank()) {
                    File(musicFolderPath)
                } else {
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
                }

                withContext(Dispatchers.IO) {
                    songsToProcess.forEach { song ->
                        try {
                            val cleanTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            if (song.folderPath.isNotBlank()) {
                                val lrcFile = File(song.folderPath, "$cleanTitle.lrc")
                                if (lrcFile.exists()) lrcFile.delete()

                                val locale = Locale.getDefault().language
                                val transLrcFile = File(song.folderPath, "$cleanTitle.$locale.lrc")
                                if (transLrcFile.exists()) transLrcFile.delete()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    try {
                        val internalLyricsDir = File(context.filesDir, "lyrics")
                        if (internalLyricsDir.exists() && internalLyricsDir.isDirectory) {
                            internalLyricsDir.deleteRecursively()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    if (baseMusicDir != null && baseMusicDir.exists() && baseMusicDir.isDirectory) {
                        try {
                            baseMusicDir.walk().forEach { file ->
                                if (file.isFile && file.name.endsWith(".lrc", ignoreCase = true)) {
                                    file.delete()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                localAudioFiles.indices.forEach { index ->
                    val file = localAudioFiles[index]
                    localAudioFiles[index] = file.copy(lyrics = null, translatedLyrics = null)
                }

                onPlaylistsReloadNeeded()

                withContext(Dispatchers.Main) {
                    isDeletingAllLyrics.value = false
                    onComplete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isDeletingAllLyrics.value = false
                    onComplete()
                }
            }
        }
    }
}
