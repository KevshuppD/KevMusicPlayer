package com.kevshupp.kevmusicplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import com.kevshupp.kevmusicplayer.KevMusicPlayerApplication
import java.util.concurrent.TimeUnit

data class LyricLine(
    val timeMs: Long,
    val text: String
)

@kotlinx.serialization.Serializable
data class LrcLibSearchResult(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSeconds: Int,
    val syncedLyrics: String?,
    val plainLyrics: String?
)

object LyricsRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    fun parseLrc(lrcText: String?): List<LyricLine> {
        if (lrcText.isNullOrBlank()) return emptyList()
        val lines = mutableListOf<LyricLine>()
        val pattern = Regex("\\[(\\d+):(\\d+)(?:\\.(\\d+))?]\\s*(.*)")
        lrcText.lines().forEach { rawLine ->
            val match = pattern.find(rawLine)
            if (match != null) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                val msPart = match.groupValues[3]
                val ms = if (msPart.isNotEmpty()) {
                    val padded = msPart.padEnd(3, '0').take(3)
                    padded.toLong()
                } else 0L
                val timeMs = (min * 60 + sec) * 1000 + ms
                val text = match.groupValues[4].trim()
                lines.add(LyricLine(timeMs, text))
            } else if (rawLine.isNotBlank() && !rawLine.startsWith("[")) {
                lines.add(LyricLine(0L, rawLine.trim()))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    fun isLrcSynced(lrcText: String?): Boolean {
        if (lrcText.isNullOrBlank()) return false
        val pattern = Regex("\\[\\d+:\\d+(?:\\.\\d+)?\\]")
        return pattern.containsMatchIn(lrcText)
    }

    suspend fun searchLyricsOptionsFromLrcLib(artist: String, title: String): List<LrcLibSearchResult> {
        return withContext(Dispatchers.IO) {
            val query = URLEncoder.encode("$artist $title", "UTF-8")
            val request = Request.Builder()
                .url("https://lrclib.net/api/search?q=$query")
                .build()
            val list = mutableListOf<LrcLibSearchResult>()
            var attempt = 0
            val maxAttempts = 3
            var lastException: Exception? = null

            while (attempt < maxAttempts) {
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: return@withContext emptyList()
                            val jsonArray = JSONArray(body)
                            for (i in 0 until jsonArray.length()) {
                                val obj = jsonArray.getJSONObject(i)
                                val id = obj.optLong("id", 0L)
                                val trackName = obj.optString("trackName", "")
                                val artistName = obj.optString("artistName", "")
                                val albumName = obj.optString("albumName", "")
                                val duration = obj.optInt("duration", 0)
                                
                                var synced = obj.optString("syncedLyrics", "")
                                if (synced.isEmpty() || synced == "null") synced = ""
                                
                                var plain = obj.optString("plainLyrics", "")
                                if (plain.isEmpty() || plain == "null") plain = ""
                                
                                list.add(
                                    LrcLibSearchResult(
                                        id = id,
                                        trackName = trackName,
                                        artistName = artistName,
                                        albumName = albumName,
                                        durationSeconds = duration,
                                        syncedLyrics = if (synced.isNotEmpty()) synced else null,
                                        plainLyrics = if (plain.isNotEmpty()) plain else null
                                    )
                                )
                            }
                            lastException = null
                            break // Success!
                        } else {
                            if (response.code == 429 || response.code >= 500) {
                                throw java.io.IOException("HTTP error code: ${response.code}")
                            } else {
                                break // Non-retryable error
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastException = e
                    val isNetworkError = e is java.net.SocketTimeoutException || 
                                         e is java.net.ConnectException || 
                                         e is java.net.UnknownHostException || 
                                         e is java.net.SocketException ||
                                         e is java.io.IOException
                    if (!isNetworkError) {
                        break
                    }
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
                attempt++
            }

            val exceptionToLog = lastException
            if (exceptionToLog != null) {
                val isNetworkError = exceptionToLog is java.net.SocketTimeoutException || 
                                     exceptionToLog is java.net.ConnectException || 
                                     exceptionToLog is java.net.UnknownHostException || 
                                     exceptionToLog is java.net.SocketException
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    KevMusicPlayerApplication.instance,
                    "LyricsSearch",
                    "Failed search from LrcLib for $artist - $title: ${exceptionToLog.localizedMessage ?: exceptionToLog.message}",
                    if (isNetworkError) null else exceptionToLog
                )
            }

            // Prioritize results that have synced lyrics
            list.sortedWith(compareByDescending<LrcLibSearchResult> { it.syncedLyrics != null }
                .thenBy { it.trackName.length })
        }
    }

    suspend fun fetchLyricsFromLrcLib(artist: String, title: String): String? {
        return withContext(Dispatchers.IO) {
            val query = URLEncoder.encode("$artist $title", "UTF-8")
            val request = Request.Builder()
                .url("https://lrclib.net/api/search?q=$query")
                .build()
            var attempt = 0
            val maxAttempts = 3
            var lastException: Exception? = null

            while (attempt < maxAttempts) {
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: return@withContext null
                            val jsonArray = JSONArray(body)
                            if (jsonArray.length() > 0) {
                                // First pass: try to find a match with synced lyrics
                                for (i in 0 until jsonArray.length()) {
                                    val match = jsonArray.getJSONObject(i)
                                    val syncedLyrics = match.optString("syncedLyrics")
                                    if (!syncedLyrics.isNullOrEmpty() && syncedLyrics != "null") {
                                        return@withContext syncedLyrics
                                    }
                                }
                                // Second pass: fallback to the first match with plain lyrics
                                for (i in 0 until jsonArray.length()) {
                                    val match = jsonArray.getJSONObject(i)
                                    val plainLyrics = match.optString("plainLyrics")
                                    if (!plainLyrics.isNullOrEmpty() && plainLyrics != "null") {
                                        return@withContext plainLyrics
                                    }
                                }
                            }
                            lastException = null
                            return@withContext null
                        } else {
                            if (response.code == 429 || response.code >= 500) {
                                throw java.io.IOException("HTTP error code: ${response.code}")
                            } else {
                                break // Non-retryable error
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastException = e
                    val isNetworkError = e is java.net.SocketTimeoutException || 
                                         e is java.net.ConnectException || 
                                         e is java.net.UnknownHostException || 
                                         e is java.net.SocketException ||
                                         e is java.io.IOException
                    if (!isNetworkError) {
                        break
                    }
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
                attempt++
            }

            val exceptionToLog = lastException
            if (exceptionToLog != null) {
                val isNetworkError = exceptionToLog is java.net.SocketTimeoutException || 
                                     exceptionToLog is java.net.ConnectException || 
                                     exceptionToLog is java.net.UnknownHostException || 
                                     exceptionToLog is java.net.SocketException
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    KevMusicPlayerApplication.instance,
                    "LyricsFetch",
                    "Failed fetch from LrcLib for $artist - $title: ${exceptionToLog.localizedMessage ?: exceptionToLog.message}",
                    if (isNetworkError) null else exceptionToLog
                )
            }
            null
        }
    }

    fun serializeTranslations(map: Map<Long, String>): String {
        val json = JSONObject()
        map.forEach { (timeMs, text) ->
            json.put(timeMs.toString(), text)
        }
        return json.toString()
    }

    fun deserializeTranslations(jsonStr: String?): Map<Long, String>? {
        if (jsonStr.isNullOrBlank()) return null
        return try {
            val json = JSONObject(jsonStr)
            val map = mutableMapOf<Long, String>()
            json.keys().forEach { key ->
                val timeMs = key.toLongOrNull()
                val text = json.optString(key)
                if (timeMs != null && text != null) {
                    map[timeMs] = text
                }
            }
            map
        } catch (e: Exception) {
            null
        }
    }

    suspend fun searchCoversFromITunes(query: String): List<ITunesCoverSearchResult> {
        return withContext(Dispatchers.IO) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=10")
                .build()
            val list = mutableListOf<ITunesCoverSearchResult>()
            var attempt = 0
            val maxAttempts = 3
            var lastException: Exception? = null

            while (attempt < maxAttempts) {
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: return@withContext emptyList()
                            val jsonObject = JSONObject(body)
                            val results = jsonObject.optJSONArray("results") ?: return@withContext emptyList()
                            for (i in 0 until results.length()) {
                                val obj = results.getJSONObject(i)
                                val trackName = obj.optString("trackName", "")
                                val artistName = obj.optString("artistName", "")
                                val albumName = obj.optString("collectionName", "")
                                var coverUrl = obj.optString("artworkUrl100", "")
                                if (coverUrl.isNotEmpty()) {
                                    coverUrl = coverUrl.replace("100x100bb.jpg", "600x600bb.jpg")
                                }
                                if (coverUrl.isNotEmpty()) {
                                    list.add(ITunesCoverSearchResult(trackName, artistName, albumName, coverUrl))
                                }
                            }
                            lastException = null
                            break // Success!
                        } else {
                            if (response.code == 429 || response.code >= 500) {
                                throw java.io.IOException("HTTP error code: ${response.code}")
                            } else {
                                break // Non-retryable error
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastException = e
                    val isNetworkError = e is java.net.SocketTimeoutException || 
                                         e is java.net.ConnectException || 
                                         e is java.net.UnknownHostException || 
                                         e is java.net.SocketException ||
                                         e is java.io.IOException
                    if (!isNetworkError) {
                        break
                    }
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
                attempt++
            }

            val exceptionToLog = lastException
            if (exceptionToLog != null) {
                val isNetworkError = exceptionToLog is java.net.SocketTimeoutException || 
                                     exceptionToLog is java.net.ConnectException || 
                                     exceptionToLog is java.net.UnknownHostException || 
                                     exceptionToLog is java.net.SocketException
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    KevMusicPlayerApplication.instance,
                    "CoverSearch",
                    "Failed search from iTunes for $query: ${exceptionToLog.localizedMessage ?: exceptionToLog.message}",
                    if (isNetworkError) null else exceptionToLog
                )
            }
            list
        }
    }

    suspend fun downloadCoverBytes(url: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            var attempt = 0
            val maxAttempts = 3
            var lastException: Exception? = null

            while (attempt < maxAttempts) {
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            lastException = null
                            return@withContext response.body?.bytes()
                        } else {
                            if (response.code == 429 || response.code >= 500) {
                                throw java.io.IOException("HTTP error code: ${response.code}")
                            } else {
                                break // Non-retryable error
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastException = e
                    val isNetworkError = e is java.net.SocketTimeoutException || 
                                         e is java.net.ConnectException || 
                                         e is java.net.UnknownHostException || 
                                         e is java.net.SocketException ||
                                         e is java.io.IOException
                    if (!isNetworkError) {
                        break
                    }
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
                attempt++
            }

            val exceptionToLog = lastException
            if (exceptionToLog != null) {
                val isNetworkError = exceptionToLog is java.net.SocketTimeoutException || 
                                     exceptionToLog is java.net.ConnectException || 
                                     exceptionToLog is java.net.UnknownHostException || 
                                     exceptionToLog is java.net.SocketException
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    KevMusicPlayerApplication.instance,
                    "CoverDownload",
                    "Failed download from $url: ${exceptionToLog.localizedMessage ?: exceptionToLog.message}",
                    if (isNetworkError) null else exceptionToLog
                )
            }
            null
        }
    }
}

@kotlinx.serialization.Serializable
data class ITunesCoverSearchResult(
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val coverUrl: String
)
