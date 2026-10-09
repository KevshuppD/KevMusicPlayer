package com.kevshupp.kevmusicplayer.data.cloud

import android.content.Context
import android.os.Build
import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.kevshupp.kevmusicplayer.data.AppDatabase
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import com.kevshupp.kevmusicplayer.playback.managers.BackupManager
import com.kevshupp.kevmusicplayer.playback.managers.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Metadata representation of a backup stored in the cloud.
 */
data class CloudBackupMetadata(
    val timestamp: Long = 0L,
    val device: String = "",
    val versionName: String = "",
    val playlistsCount: Int = 0,
    val tracksCount: Int = 0,
    val lyricsCount: Int = 0,
    val sizeBytes: Long = 0L
)

/**
 * Handles Cloud Backup synchronization using Firebase Firestore with GZIP compression.
 */
class CloudBackupManager(
    private val context: Context
) {
    private val database = AppDatabase.getDatabase(context)
    private val backupManager = BackupManager(database.audioDao())
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /**
     * Exports current app state (settings, playlists, lyrics, stats) as a UTF-8 JSON String.
     */
    suspend fun generateBackupPayload(
        includeSettings: Boolean = true,
        includeEqualizer: Boolean = true,
        includePlaylists: Boolean = true,
        includeLyrics: Boolean = true,
        includeStatistics: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream()
        backupManager.exportBackup(
            context = context,
            outputStream = out,
            includeSettings = includeSettings,
            includeEqualizer = includeEqualizer,
            includePlaylists = includePlaylists,
            includeLyrics = includeLyrics,
            includeStatistics = includeStatistics
        )
        out.toString(Charsets.UTF_8.name())
    }

    /**
     * Restores application state from a JSON String payload fetched from the cloud.
     */
    suspend fun restoreBackupPayload(jsonPayload: String): ImportResult = withContext(Dispatchers.IO) {
        val input = ByteArrayInputStream(jsonPayload.toByteArray(Charsets.UTF_8))
        backupManager.importBackup(context, input)
    }

    /**
     * Uploads the backup payload for the logged-in user to Firebase Firestore and local cache.
     */
    suspend fun uploadBackupToCloud(user: CloudUser): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val payload = generateBackupPayload(
                includeSettings = user.includeSettings,
                includeEqualizer = user.includeSettings,
                includePlaylists = user.includePlaylists,
                includeLyrics = user.includeLyrics,
                includeStatistics = user.includeStats
            )
            val timestamp = System.currentTimeMillis()
            val compressedPayload = compressGzip(payload)

            // Extract quick summary
            var tracksCount = 0
            var playlistsCount = 0
            var lyricsCount = 0
            try {
                val json = JSONObject(payload)
                if (json.has("songs_metadata")) {
                    tracksCount = json.getJSONArray("songs_metadata").length()
                }
                if (json.has("playlists")) {
                    playlistsCount = json.getJSONObject("playlists").length()
                }
                if (json.has("lyrics")) {
                    lyricsCount = json.getJSONObject("lyrics").length()
                }
            } catch (e: Exception) {
                // Ignore parsing errors for metadata stats
            }

            val pInfo = try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (e: Exception) {
                null
            }
            val versionName = pInfo?.versionName ?: "1.2.29"
            val deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

            val backupDoc = hashMapOf(
                "timestamp" to timestamp,
                "device" to deviceModel,
                "versionName" to versionName,
                "payloadGzip" to compressedPayload,
                "tracksCount" to tracksCount,
                "playlistsCount" to playlistsCount,
                "lyricsCount" to lyricsCount,
                "sizeBytes" to payload.toByteArray(Charsets.UTF_8).size.toLong()
            )

            // Save to Firestore: users/{uid}/backups/latest
            firestore.collection("users")
                .document(user.uid)
                .collection("backups")
                .document("latest")
                .set(backupDoc, SetOptions.merge())
                .await()

            // Save to local cloud cache file for offline redundancy
            val cloudCacheDir = File(context.filesDir, "cloud_backups")
            if (!cloudCacheDir.exists()) cloudCacheDir.mkdirs()
            val userBackupFile = File(cloudCacheDir, "${user.uid}_latest.json")
            userBackupFile.writeText(payload, Charsets.UTF_8)

            CloudAuthManager.updateLastSyncTimestamp(context, timestamp)
            TelemetryLogger.logInfo(
                context,
                "CloudBackup",
                "Cloud backup successfully uploaded to Firestore for ${user.email} (${payload.length} bytes, gzip: ${compressedPayload.length} chars)"
            )
            Result.success(timestamp)
        } catch (e: Exception) {
            TelemetryLogger.logError(context, "CloudBackup", "Failed to upload backup to Firestore", e)
            Result.failure(e)
        }
    }

    /**
     * Retrieves metadata of the latest cloud backup from Firestore.
     */
    suspend fun getLatestCloudBackupInfo(user: CloudUser): Result<CloudBackupMetadata?> = withContext(Dispatchers.IO) {
        try {
            val doc = firestore.collection("users")
                .document(user.uid)
                .collection("backups")
                .document("latest")
                .get()
                .await()

            if (!doc.exists()) {
                return@withContext Result.success(null)
            }

            val timestamp = doc.getLong("timestamp") ?: 0L
            val device = doc.getString("device") ?: "Desconocido"
            val versionName = doc.getString("versionName") ?: ""
            val playlistsCount = doc.getLong("playlistsCount")?.toInt() ?: 0
            val tracksCount = doc.getLong("tracksCount")?.toInt() ?: 0
            val lyricsCount = doc.getLong("lyricsCount")?.toInt() ?: 0
            val sizeBytes = doc.getLong("sizeBytes") ?: 0L

            Result.success(
                CloudBackupMetadata(
                    timestamp = timestamp,
                    device = device,
                    versionName = versionName,
                    playlistsCount = playlistsCount,
                    tracksCount = tracksCount,
                    lyricsCount = lyricsCount,
                    sizeBytes = sizeBytes
                )
            )
        } catch (e: Exception) {
            TelemetryLogger.logError(context, "CloudBackup", "Failed to fetch cloud backup metadata", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads and restores the latest backup for the logged-in user from Firestore.
     */
    suspend fun restoreBackupFromCloud(user: CloudUser): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val doc = firestore.collection("users")
                .document(user.uid)
                .collection("backups")
                .document("latest")
                .get()
                .await()

            var payload: String? = null

            if (doc.exists()) {
                val compressedPayload = doc.getString("payloadGzip")
                if (!compressedPayload.isNullOrBlank()) {
                    payload = decompressGzip(compressedPayload)
                } else {
                    payload = doc.getString("payload")
                }
            }

            // Fallback to local cache if offline or missing
            if (payload == null) {
                val cloudCacheDir = File(context.filesDir, "cloud_backups")
                val userBackupFile = File(cloudCacheDir, "${user.uid}_latest.json")
                if (userBackupFile.exists()) {
                    payload = userBackupFile.readText(Charsets.UTF_8)
                }
            }

            if (payload.isNullOrBlank()) {
                return@withContext Result.failure(Exception("No se encontró ninguna copia de seguridad en la nube para esta cuenta."))
            }

            val result = restoreBackupPayload(payload)
            val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
            CloudAuthManager.updateLastSyncTimestamp(context, timestamp)

            TelemetryLogger.logInfo(
                context,
                "CloudBackup",
                "Cloud backup successfully restored from Firestore for ${user.email}"
            )
            Result.success(result)
        } catch (e: Exception) {
            TelemetryLogger.logError(context, "CloudBackup", "Failed to restore cloud backup from Firestore", e)
            Result.failure(e)
        }
    }

    private fun compressGzip(data: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzip ->
            gzip.write(data.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun decompressGzip(base64Compressed: String): String {
        val bytes = Base64.decode(base64Compressed, Base64.NO_WRAP)
        val bis = ByteArrayInputStream(bytes)
        GZIPInputStream(bis).use { gzip ->
            return gzip.bufferedReader(Charsets.UTF_8).readText()
        }
    }
}
