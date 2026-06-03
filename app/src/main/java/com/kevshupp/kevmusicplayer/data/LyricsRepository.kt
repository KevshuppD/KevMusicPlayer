package com.kevshupp.kevmusicplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

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

    suspend fun searchLyricsOptionsFromLrcLib(artist: String, title: String): List<LrcLibSearchResult> {
        return withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val query = URLEncoder.encode("$artist $title", "UTF-8")
            val request = Request.Builder()
                .url("https://lrclib.net/api/search?q=$query")
                .build()
            val list = mutableListOf<LrcLibSearchResult>()
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
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            list
        }
    }

    suspend fun fetchLyricsFromLrcLib(artist: String, title: String): String? {
        return withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val query = URLEncoder.encode("$artist $title", "UTF-8")
            val request = Request.Builder()
                .url("https://lrclib.net/api/search?q=$query")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@withContext null
                        val jsonArray = JSONArray(body)
                        if (jsonArray.length() > 0) {
                            val bestMatch = jsonArray.getJSONObject(0)
                            val syncedLyrics = bestMatch.optString("syncedLyrics")
                            if (!syncedLyrics.isNullOrEmpty() && syncedLyrics != "null") {
                                return@withContext syncedLyrics
                            }
                            val plainLyrics = bestMatch.optString("plainLyrics")
                            if (!plainLyrics.isNullOrEmpty() && plainLyrics != "null") {
                                return@withContext plainLyrics
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
}
