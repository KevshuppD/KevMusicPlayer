package com.kevshupp.kevmusicplayer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ArtistImageHelper {
    private val connectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)
    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val downloadingArtists = ConcurrentHashMap.newKeySet<String>()
    private val failedArtists = ConcurrentHashMap<String, Long>()
    private const val FAILED_RETRY_INTERVAL_MS = 60_000L // Retry after 1 minute

    private const val BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

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

        // Extract main artist if features / extra info are present
        var cleaned = trimmed
            .replace(Regex("(?i)\\s*\\(?(feat\\.|ft\\.|featuring)\\s+[^)]+\\)?.*"), "")
            .replace(Regex("(?i)\\s*\\(?(with|con)\\s+[^)]+\\)?.*"), "")
            .replace(Regex("(?i)\\s*\\(?(prod\\.|produced by)\\s+[^)]+\\)?.*"), "")
            .replace(Regex("\\s*[\\[(](official|remastered|remaster|video|audio|hq|hd|live|lyrics?)[^\\])]*[\\])]", RegexOption.IGNORE_CASE), "")
            .trim()

        // Delimiters with spaces around to avoid breaking bands like "AC/DC"
        val spacedDelimiters = listOf(" ; ", " / ", " , ", " & ", " x ", " X ", " vs. ", " vs ", " feat. ", " ft. ", " with ")
        for (delim in spacedDelimiters) {
            if (cleaned.contains(delim, ignoreCase = true)) {
                val firstPart = cleaned.substringBefore(delim).trim()
                if (firstPart.length >= 2) {
                    cleaned = firstPart
                    break
                }
            }
        }

        // Semicolons or commas (excluding prefixes/suffixes like Jr., Sr.)
        if (cleaned.contains(";") || (cleaned.contains(",") && !cleaned.contains(", Jr") && !cleaned.contains(", Sr"))) {
            val delim = if (cleaned.contains(";")) ";" else ","
            val firstPart = cleaned.substringBefore(delim).trim()
            if (firstPart.length >= 2) {
                cleaned = firstPart
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
        val dir = File(context.filesDir, "artist_images")
        if (!dir.exists()) dir.mkdirs()

        val cleanBase = cleanArtistSearchName(artist) ?: artist.trim()
        val slug = cleanBase.lowercase()
            .stripAccents()
            .replace(Regex("[^a-z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(24)

        val hash = try {
            val md5 = java.security.MessageDigest.getInstance("MD5")
            val bytes = md5.digest(artist.trim().lowercase().toByteArray())
            bytes.joinToString("") { "%02x".format(it) }.take(8)
        } catch (e: Exception) {
            artist.trim().hashCode().toString().replace("-", "n")
        }

        val fileName = if (slug.isNotEmpty()) "${slug}_$hash.jpg" else "art_$hash.jpg"
        return File(dir, fileName)
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

        val now = System.currentTimeMillis()
        if (force) {
            failedArtists.remove(artistName)
        } else {
            val lastFailed = failedArtists[artistName]
            if (lastFailed != null && (now - lastFailed) < FAILED_RETRY_INTERVAL_MS) {
                return null
            }
            if (downloadingArtists.contains(artistName)) {
                return null
            }
        }

        downloadingArtists.add(artistName)

        return withContext(Dispatchers.IO) {
            try {
                var pictureUrl: String? = searchDeezerArtistPicture(cleanSearch)

                if (pictureUrl.isNullOrEmpty()) {
                    pictureUrl = searchDeezerTrackArtistPicture(cleanSearch)
                }

                if (!pictureUrl.isNullOrEmpty()) {
                    val downloaded = downloadImageToDisk(pictureUrl, localFile)
                    if (downloaded) {
                        failedArtists.remove(artistName)
                        downloadingArtists.remove(artistName)
                        return@withContext localFile
                    }
                }

                failedArtists[artistName] = System.currentTimeMillis()
                downloadingArtists.remove(artistName)
                null
            } catch (e: Exception) {
                failedArtists[artistName] = System.currentTimeMillis()
                downloadingArtists.remove(artistName)
                null
            }
        }
    }

    private fun isValidPictureUrl(pic: String): Boolean {
        if (pic.isBlank()) return false
        if (pic.contains("default-artist") || pic.contains("avatar") || pic.contains("d41d8cd98f00b204e9800998ecf8427e")) return false
        if (pic.endsWith("/500x500-000000-80-0-0.jpg") || pic.endsWith("/250x250-000000-80-0-0.jpg") || pic.contains("/images/artist//")) return false
        return true
    }

    private fun searchDeezerArtistPicture(cleanSearch: String): String? {
        return try {
            val encodedQuery = URLEncoder.encode(cleanSearch, "UTF-8")
            val url = "https://api.deezer.com/search/artist?q=$encodedQuery&limit=10"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val responseBody = response.body?.string() ?: return null
                val json = JSONObject(responseBody)
                val dataArray = json.optJSONArray("data") ?: return null
                if (dataArray.length() == 0) return null

                val targetNorm = cleanSearch.stripAccents().lowercase().trim()
                val targetAlphaNum = targetNorm.replace(Regex("[^a-z0-9]"), "")

                var bestUrl: String? = null

                for (i in 0 until minOf(dataArray.length(), 10)) {
                    val candidate = dataArray.getJSONObject(i)
                    val candidateName = candidate.optString("name", "").trim()
                    val candidateNorm = candidateName.stripAccents().lowercase().trim()
                    val candidateAlphaNum = candidateNorm.replace(Regex("[^a-z0-9]"), "")

                    val isExactMatch = candidateNorm == targetNorm
                    val isAlphaMatch = candidateAlphaNum.isNotEmpty() && candidateAlphaNum == targetAlphaNum
                    val isCloseMatch = (candidateNorm.contains(targetNorm) || targetNorm.contains(candidateNorm)) &&
                            Math.abs(candidateNorm.length - targetNorm.length) <= 5

                    val pic = candidate.optString("picture_xl", "").ifEmpty {
                        candidate.optString("picture_big", "").ifEmpty {
                            candidate.optString("picture_medium", "")
                        }
                    }

                    if (isValidPictureUrl(pic)) {
                        if (isExactMatch || isAlphaMatch) {
                            return pic
                        }
                        if (isCloseMatch && bestUrl == null) {
                            bestUrl = pic
                        } else if (i == 0 && bestUrl == null) {
                            bestUrl = pic
                        }
                    }
                }
                bestUrl
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun searchDeezerTrackArtistPicture(cleanSearch: String): String? {
        return try {
            val encodedQuery = URLEncoder.encode(cleanSearch, "UTF-8")
            val url = "https://api.deezer.com/search?q=$encodedQuery&limit=8"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val responseBody = response.body?.string() ?: return null
                val json = JSONObject(responseBody)
                val dataArray = json.optJSONArray("data") ?: return null

                for (i in 0 until dataArray.length()) {
                    val item = dataArray.getJSONObject(i)
                    val artistObj = item.optJSONObject("artist") ?: continue
                    val pic = artistObj.optString("picture_xl", "").ifEmpty {
                        artistObj.optString("picture_big", "").ifEmpty {
                            artistObj.optString("picture_medium", "")
                        }
                    }
                    if (isValidPictureUrl(pic)) {
                        return pic
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun searchItunesArtistPicture(cleanSearch: String): String? {
        return try {
            val encodedQuery = URLEncoder.encode(cleanSearch, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encodedQuery&entity=album&limit=3"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KevMusicPlayer/1.5.6")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val responseBody = response.body?.string() ?: return null
                val json = JSONObject(responseBody)
                val results = json.optJSONArray("results") ?: return null
                if (results.length() > 0) {
                    val first = results.getJSONObject(0)
                    val rawUrl = first.optString("artworkUrl100", "")
                    if (rawUrl.isNotEmpty()) {
                        return rawUrl.replace("100x100bb.jpg", "600x600bb.jpg")
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadImageToDisk(url: String, destFile: File): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KevMusicPlayer/1.5.6")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
                        FileOutputStream(tempFile).use { fos ->
                            fos.write(bytes)
                        }
                        if (tempFile.exists() && tempFile.length() > 0) {
                            if (destFile.exists()) destFile.delete()
                            return tempFile.renameTo(destFile)
                        }
                    }
                }
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
