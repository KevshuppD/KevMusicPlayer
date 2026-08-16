package com.kevshupp.kevmusicplayer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.Normalizer

object ArtistImageHelper {
    private val client = OkHttpClient()
    private val downloadingArtists = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val failedArtists = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val ignoredArtistNames = setOf(
        "unknown", "<unknown>", "unknown artist", "artista desconocido", "desconocido",
        "varios artistas", "various artists", "various", "soundtrack", "ost", "va", "v.a.",
        "audio", "track", "pista", "whatsapp", "youtube", "voice recorder", "recording",
        "instrumental", "unknown genre"
    )

    private fun String.stripAccents(): String {
        val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    fun cleanArtistSearchName(rawArtist: String): String? {
        val trimmed = rawArtist.trim()
        val lowerNorm = trimmed.stripAccents().lowercase()
        if (lowerNorm.isEmpty() || ignoredArtistNames.contains(lowerNorm)) {
            return null
        }

        // Extract main artist if multiple artists / features are present
        var cleaned = trimmed
            .replace(Regex("(?i)\\s*\\(?(feat\\.|ft\\.|featuring)\\s+[^)]+\\)?.*"), "")
            .replace(Regex("(?i)\\s*\\(?(with|con)\\s+[^)]+\\)?.*"), "")
            .replace(Regex("(?i)\\s*\\(?(prod\\.|produced by)\\s+[^)]+\\)?.*"), "")
            .replace(Regex("\\s*[\\[(](official|remastered|video|audio|hq|hd|live|lyrics?)[^\\])]*[\\])]", RegexOption.IGNORE_CASE), "")
            .trim()

        // If split by delimiters like comma, slash, semicolon, ampersand, take the primary artist
        val delimiters = listOf(";", "/", ",", "&", " x ", " X ", " vs. ", " vs ")
        for (delim in delimiters) {
            if (cleaned.contains(delim)) {
                val firstPart = cleaned.substringBefore(delim).trim()
                if (firstPart.length >= 2) {
                    cleaned = firstPart
                    break
                }
            }
        }

        val finalClean = cleaned.trim().trim('"', '\'', '-', '_')
        val finalLower = finalClean.stripAccents().lowercase()
        if (finalClean.length < 2 || ignoredArtistNames.contains(finalLower)) {
            return null
        }
        return finalClean
    }

    fun getArtistImageFile(context: Context, artist: String): File {
        val sanitized = artist.lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        val dir = File(context.filesDir, "artist_images")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$sanitized.jpg")
    }

    fun deleteArtistImage(context: Context, artist: String): Boolean {
        failedArtists.remove(artist.trim())
        val file = getArtistImageFile(context, artist)
        return if (file.exists()) file.delete() else false
    }

    fun clearAllArtistImages(context: Context) {
        failedArtists.clear()
        val dir = File(context.filesDir, "artist_images")
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    suspend fun downloadArtistImage(context: Context, artist: String, force: Boolean = false): File? {
        val artistName = artist.trim()
        val cleanSearch = cleanArtistSearchName(artistName) ?: return null

        val localFile = getArtistImageFile(context, artistName)
        if (!force && localFile.exists() && localFile.length() > 0) {
            return localFile
        }

        if (force) {
            failedArtists.remove(artistName)
        } else if (downloadingArtists.contains(artistName) || failedArtists.contains(artistName)) {
            return null
        }

        downloadingArtists.add(artistName)

        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(cleanSearch, "UTF-8")
                val url = "https://api.deezer.com/search/artist?q=$encodedQuery"
                
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "KevMusicPlayer/1.5.5")
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        failedArtists.add(artistName)
                        downloadingArtists.remove(artistName)
                        return@withContext null
                    }
                    
                    val responseBody = response.body?.string() ?: ""
                    if (responseBody.isEmpty()) {
                        failedArtists.add(artistName)
                        downloadingArtists.remove(artistName)
                        return@withContext null
                    }
                    
                    val json = JSONObject(responseBody)
                    val dataArray = json.optJSONArray("data")
                    if (dataArray != null && dataArray.length() > 0) {
                        val targetNorm = cleanSearch.stripAccents().lowercase().trim()
                        var bestMatchedPictureUrl: String? = null

                        // Check up to 8 search results to find a true match
                        for (i in 0 until minOf(dataArray.length(), 8)) {
                            val candidate = dataArray.getJSONObject(i)
                            val candidateName = candidate.optString("name", "").trim()
                            val candidateNorm = candidateName.stripAccents().lowercase().trim()
                            
                            val isExactMatch = candidateNorm == targetNorm
                            val isStrongMatch = candidateNorm.replace(" ", "") == targetNorm.replace(" ", "")
                            val isCloseSubstring = (candidateNorm.contains(targetNorm) || targetNorm.contains(candidateNorm)) &&
                                    Math.abs(candidateNorm.length - targetNorm.length) <= 4

                            if (isExactMatch || isStrongMatch || isCloseSubstring) {
                                val pic = candidate.optString("picture_big", "").ifEmpty {
                                    candidate.optString("picture_medium", "")
                                }
                                if (pic.isNotEmpty() && !pic.contains("default-artist") && !pic.contains("avatar")) {
                                    bestMatchedPictureUrl = pic
                                    break
                                }
                            }
                        }

                        if (bestMatchedPictureUrl != null) {
                            val imageRequest = Request.Builder()
                                .url(bestMatchedPictureUrl)
                                .header("User-Agent", "KevMusicPlayer/1.5.5")
                                .build()
                            client.newCall(imageRequest).execute().use { imageResponse ->
                                if (imageResponse.isSuccessful) {
                                    val bytes = imageResponse.body?.bytes()
                                    if (bytes != null && bytes.isNotEmpty()) {
                                        FileOutputStream(localFile).use { fos ->
                                            fos.write(bytes)
                                        }
                                        downloadingArtists.remove(artistName)
                                        return@withContext localFile
                                    }
                                }
                            }
                        }
                    }
                }
                failedArtists.add(artistName)
                downloadingArtists.remove(artistName)
                null
            } catch (e: Exception) {
                e.printStackTrace()
                downloadingArtists.remove(artistName)
                null
            }
        }
    }
}
