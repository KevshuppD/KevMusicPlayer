package com.kevshupp.kevmusicplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
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
    private val connectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)
    
    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", "KevMusicPlayer/1.5.4 (https://github.com/kevshupp/kevmusicplayer)")
                .build()
            chain.proceed(request)
        }
        .build()

    fun cleanSearchTerm(text: String): String {
        return text
            .replace(Regex("(?i)\\s*[\\[(](?:official|music|video|lyric|audio|hd|hq|4k|remastered|remaster|live|ft\\.|feat\\.).*?[\\])]"), "")
            .replace(Regex("(?i)\\s*(?:ft\\.|feat\\.).*"), "")
            .trim()
    }

    private val parsedLrcCache = android.util.LruCache<String, List<LyricLine>>(100)

    fun parseLrc(lrcText: String?): List<LyricLine> {
        if (lrcText.isNullOrBlank()) return emptyList()
        val cached = parsedLrcCache.get(lrcText)
        if (cached != null) return cached

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
        val result = lines.sortedBy { it.timeMs }
        parsedLrcCache.put(lrcText, result)
        return result
    }

    fun isLrcSynced(lrcText: String?): Boolean {
        if (lrcText.isNullOrBlank()) return false
        val pattern = Regex("\\[\\d+:\\d+(?:\\.\\d+)?\\]")
        return pattern.containsMatchIn(lrcText)
    }

    suspend fun searchLyricsOptionsFromLrcLib(artist: String, title: String): List<LrcLibSearchResult> {
        return withContext(Dispatchers.IO) {
            val list = mutableListOf<LrcLibSearchResult>()
            val cleanedTitle = cleanSearchTerm(title)
            val cleanedArtist = cleanSearchTerm(artist)

            // Prepare query candidates
            val queriesToTry = mutableListOf<String>()
            val cleanQuery = if (cleanedArtist.isBlank() || cleanedArtist.contains("Unknown", ignoreCase = true)) {
                cleanedTitle
            } else {
                "$cleanedArtist $cleanedTitle"
            }.trim()
            if (cleanQuery.isNotBlank()) queriesToTry.add(cleanQuery)

            val rawQuery = if (artist.isBlank() || artist.contains("Unknown", ignoreCase = true)) {
                title
            } else {
                "$artist $title"
            }.trim()
            if (rawQuery.isNotBlank() && rawQuery != cleanQuery) queriesToTry.add(rawQuery)

            var lastException: Exception? = null

            for (q in queriesToTry) {
                val encodedQuery = URLEncoder.encode(q, "UTF-8")
                val request = Request.Builder()
                    .url("https://lrclib.net/api/search?q=$encodedQuery")
                    .build()

                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: return@use
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

                                if (list.none { it.id == id }) {
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
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastException = e
                }

                if (list.isNotEmpty()) break
            }

            // Also try explicit track_name and artist_name search if search results are empty
            if (list.isEmpty() && cleanedTitle.isNotBlank() && cleanedArtist.isNotBlank() && !cleanedArtist.contains("Unknown", ignoreCase = true)) {
                try {
                    val encTrack = URLEncoder.encode(cleanedTitle, "UTF-8")
                    val encArtist = URLEncoder.encode(cleanedArtist, "UTF-8")
                    val request = Request.Builder()
                        .url("https://lrclib.net/api/search?track_name=$encTrack&artist_name=$encArtist")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
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

                                if (list.none { it.id == id }) {
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
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastException = e
                }
            }

            val exceptionToLog = lastException
            if (exceptionToLog != null && list.isEmpty()) {
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    KevMusicPlayerApplication.instance,
                    "LyricsSearch",
                    "Failed search from LrcLib for $artist - $title: ${exceptionToLog.localizedMessage ?: exceptionToLog.message}",
                    exceptionToLog
                )
            }

            // Prioritize results that have synced lyrics
            list.sortedWith(compareByDescending<LrcLibSearchResult> { it.syncedLyrics != null }
                .thenBy { it.trackName.length })
        }
    }

    suspend fun fetchLyricsFromLrcLib(artist: String, title: String): String? {
        return withContext(Dispatchers.IO) {
            val cleanedTitle = cleanSearchTerm(title)
            val cleanedArtist = cleanSearchTerm(artist)

            // Pass 1: Try direct GET endpoint first (/api/get)
            if (cleanedTitle.isNotBlank() && cleanedArtist.isNotBlank() && !cleanedArtist.contains("Unknown", ignoreCase = true)) {
                try {
                    val encTrack = URLEncoder.encode(cleanedTitle, "UTF-8")
                    val encArtist = URLEncoder.encode(cleanedArtist, "UTF-8")
                    val request = Request.Builder()
                        .url("https://lrclib.net/api/get?artist_name=$encArtist&track_name=$encTrack")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            if (!body.isNullOrEmpty()) {
                                val obj = JSONObject(body)
                                val syncedLyrics = obj.optString("syncedLyrics")
                                if (!syncedLyrics.isNullOrEmpty() && syncedLyrics != "null") {
                                    return@withContext syncedLyrics
                                }
                                val plainLyrics = obj.optString("plainLyrics")
                                if (!plainLyrics.isNullOrEmpty() && plainLyrics != "null") {
                                    return@withContext plainLyrics
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }

            // Pass 2: Search with searchLyricsOptionsFromLrcLib
            val results = searchLyricsOptionsFromLrcLib(artist, title)
            if (results.isNotEmpty()) {
                val syncedMatch = results.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
                if (syncedMatch != null) return@withContext syncedMatch.syncedLyrics
                val plainMatch = results.firstOrNull { !it.plainLyrics.isNullOrBlank() }
                if (plainMatch != null) return@withContext plainMatch.plainLyrics
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

    suspend fun searchCoversFromITunes(query: String): List<CoverSearchResult> {
        return CoverArtRepository.searchCovers(query)
    }

    suspend fun downloadCoverBytes(url: String): ByteArray? {
        return CoverArtRepository.downloadCoverBytes(url)
    }
}

typealias ITunesCoverSearchResult = CoverSearchResult

