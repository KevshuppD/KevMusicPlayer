package com.kevshupp.kevmusicplayer.data

import com.kevshupp.kevmusicplayer.KevMusicPlayerApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class CoverSearchType {
    AUTO,
    ALBUM,
    SONG
}

@kotlinx.serialization.Serializable
data class CoverSearchResult(
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val coverUrl: String,
    val source: String = "iTunes"
)

object CoverArtRepository {
    private val connectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)

    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", "KevMusicPlayer/1.5.6 (https://github.com/kevshupp/kevmusicplayer)")
                .build()
            chain.proceed(request)
        }
        .build()

    fun cleanCoverSearchTerm(text: String): String {
        return text
            .replace(Regex("(?i)\\s*[\\[(](?:official|music|video|lyric|audio|hd|hq|4k|remastered|remaster|live|deluxe|bonus|ft\\.|feat\\.).*?[\\])]"), "")
            .replace(Regex("(?i)\\s*(?:ft\\.|feat\\.).*"), "")
            .replace(Regex("^[0-9]{1,3}[\\s._-]+"), "") // Remove track number prefixes like "01 - " or "01. "
            .trim()
    }

    /**
     * Search covers on Deezer API. Supports Album and Track entities.
     * Returns high-resolution (cover_xl: 1000x1000) artwork URLs.
     */
    suspend fun searchCoversFromDeezer(query: String, type: CoverSearchType = CoverSearchType.AUTO): List<CoverSearchResult> {
        return withContext(Dispatchers.IO) {
            val list = mutableListOf<CoverSearchResult>()
            val cleanedQuery = cleanCoverSearchTerm(query)
            if (cleanedQuery.isBlank()) return@withContext emptyList()

            val encodedQuery = URLEncoder.encode(cleanedQuery, "UTF-8")
            val urlsToTry = mutableListOf<String>()

            when (type) {
                CoverSearchType.ALBUM -> {
                    urlsToTry.add("https://api.deezer.com/search/album?q=$encodedQuery&limit=12")
                }
                CoverSearchType.SONG -> {
                    urlsToTry.add("https://api.deezer.com/search/track?q=$encodedQuery&limit=12")
                }
                CoverSearchType.AUTO -> {
                    urlsToTry.add("https://api.deezer.com/search/album?q=$encodedQuery&limit=8")
                    urlsToTry.add("https://api.deezer.com/search/track?q=$encodedQuery&limit=8")
                }
            }

            for (url in urlsToTry) {
                val request = Request.Builder().url(url).build()
                try {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: return@use
                            val json = JSONObject(body)
                            val data = json.optJSONArray("data") ?: return@use

                            for (i in 0 until data.length()) {
                                val item = data.getJSONObject(i)
                                val coverUrl = when {
                                    item.has("cover_xl") && item.optString("cover_xl").isNotEmpty() -> item.optString("cover_xl")
                                    item.has("cover_big") && item.optString("cover_big").isNotEmpty() -> item.optString("cover_big")
                                    item.has("cover_medium") && item.optString("cover_medium").isNotEmpty() -> item.optString("cover_medium")
                                    item.has("album") -> {
                                        val albumObj = item.optJSONObject("album")
                                        albumObj?.optString("cover_xl", "")?.ifEmpty {
                                            albumObj.optString("cover_big", "").ifEmpty {
                                                albumObj.optString("cover_medium", "")
                                            }
                                        } ?: ""
                                    }
                                    else -> ""
                                }

                                if (coverUrl.isNotBlank() && !coverUrl.contains("default-cover")) {
                                    val title = item.optString("title", "")
                                    val artistName = if (item.has("artist")) {
                                        item.optJSONObject("artist")?.optString("name", "") ?: ""
                                    } else ""

                                    val albumName = if (type == CoverSearchType.ALBUM || url.contains("/search/album")) {
                                        title
                                    } else {
                                        item.optJSONObject("album")?.optString("title", "") ?: ""
                                    }

                                    val trackName = if (type == CoverSearchType.ALBUM || url.contains("/search/album")) {
                                        ""
                                    } else {
                                        title
                                    }

                                    if (list.none { it.coverUrl == coverUrl }) {
                                        list.add(
                                            CoverSearchResult(
                                                trackName = trackName,
                                                artistName = artistName,
                                                albumName = albumName,
                                                coverUrl = coverUrl,
                                                source = "Deezer"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }

            list
        }
    }

    /**
     * Search covers on iTunes Search API with proper entity support and 1000x1000 resolution.
     */
    suspend fun searchCoversFromITunes(query: String, type: CoverSearchType = CoverSearchType.AUTO): List<CoverSearchResult> {
        return withContext(Dispatchers.IO) {
            val list = mutableListOf<CoverSearchResult>()
            val cleanedQuery = cleanCoverSearchTerm(query)
            if (cleanedQuery.isBlank()) return@withContext emptyList()

            val encodedQuery = URLEncoder.encode(cleanedQuery, "UTF-8")
            val entityParam = when (type) {
                CoverSearchType.ALBUM -> "entity=album&limit=12"
                CoverSearchType.SONG -> "entity=song&limit=12"
                CoverSearchType.AUTO -> "media=music&entity=album,song&limit=12"
            }

            val request = Request.Builder()
                .url("https://itunes.apple.com/search?term=$encodedQuery&$entityParam")
                .build()

            var attempt = 0
            val maxAttempts = 2
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
                                    coverUrl = coverUrl.replace(Regex("\\d+x\\d+bb"), "1000x1000bb")
                                        .replace("100x100bb.jpg", "1000x1000bb.jpg")
                                        .replace("100x100bb.png", "1000x1000bb.png")
                                }

                                if (coverUrl.isNotEmpty() && list.none { it.coverUrl == coverUrl }) {
                                    list.add(
                                        CoverSearchResult(
                                            trackName = trackName,
                                            artistName = artistName,
                                            albumName = albumName,
                                            coverUrl = coverUrl,
                                            source = "iTunes"
                                        )
                                    )
                                }
                            }
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    kotlinx.coroutines.delay(500L * (attempt + 1))
                }
                attempt++
            }

            list
        }
    }

    /**
     * Unified multi-source cover search (Deezer + iTunes).
     * Dispatches queries in parallel and merges results with Deezer prioritized by default.
     */
    suspend fun searchCovers(query: String, type: CoverSearchType = CoverSearchType.AUTO): List<CoverSearchResult> {
        return withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isBlank()) return@withContext emptyList()

            val preferredProvider = try {
                KevMusicPlayerApplication.instance.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)
                    .getString("preferred_cover_provider", "deezer") ?: "deezer"
            } catch (e: Exception) {
                "deezer"
            }

            coroutineScope {
                val deezerDeferred = async { searchCoversFromDeezer(trimmed, type) }
                val itunesDeferred = async { searchCoversFromITunes(trimmed, type) }

                val deezerResults = try { deezerDeferred.await() } catch (e: Exception) { emptyList() }
                val itunesResults = try { itunesDeferred.await() } catch (e: Exception) { emptyList() }

                val combined = mutableListOf<CoverSearchResult>()
                val seenUrls = mutableSetOf<String>()

                when (preferredProvider) {
                    "itunes" -> {
                        // Prioritize iTunes first, then Deezer
                        for (item in itunesResults) {
                            if (seenUrls.add(item.coverUrl)) combined.add(item)
                        }
                        for (item in deezerResults) {
                            if (seenUrls.add(item.coverUrl)) combined.add(item)
                        }
                    }
                    "both" -> {
                        // Interleave 1 Deezer, 1 iTunes
                        val maxLen = maxOf(deezerResults.size, itunesResults.size)
                        for (i in 0 until maxLen) {
                            if (i < deezerResults.size) {
                                val item = deezerResults[i]
                                if (seenUrls.add(item.coverUrl)) combined.add(item)
                            }
                            if (i < itunesResults.size) {
                                val item = itunesResults[i]
                                if (seenUrls.add(item.coverUrl)) combined.add(item)
                            }
                        }
                    }
                    else -> {
                        // "deezer" (Default & Recommended): Deezer results take highest priority (first in list)
                        for (item in deezerResults) {
                            if (seenUrls.add(item.coverUrl)) combined.add(item)
                        }
                        for (item in itunesResults) {
                            if (seenUrls.add(item.coverUrl)) combined.add(item)
                        }
                    }
                }

                if (combined.isEmpty() && trimmed.contains(" ")) {
                    val firstPart = trimmed.substringBefore(" - ").substringBefore(" – ").trim()
                    if (firstPart.isNotBlank() && firstPart != trimmed) {
                        val fallbackDeezer = searchCoversFromDeezer(firstPart, type)
                        val fallbackITunes = searchCoversFromITunes(firstPart, type)
                        for (item in fallbackDeezer + fallbackITunes) {
                            if (seenUrls.add(item.coverUrl)) {
                                combined.add(item)
                            }
                        }
                    }
                }

                combined
            }
        }
    }

    /**
     * Download cover bytes with retry logic.
     */
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
                                break
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
                TelemetryLogger.logError(
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
