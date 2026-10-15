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

    /** Set by the ViewModel: a few titles of the artist's songs in the library. */
    @Volatile
    var songTitlesProvider: ((String) -> List<String>)? = null

    /** Bumped whenever a photo file changes, so ArtistImage reloads instead of showing the cached bitmap. */
    val imagesVersion = androidx.compose.runtime.mutableIntStateOf(0)

    fun markChanged() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { imagesVersion.intValue++ }
    }

    private const val MATCHING_VERSION = 2

    /**
     * Earlier versions accepted the first Deezer result when no name matched, and stored photos of other artists.
     * Once, photos downloaded from Deezer (always 1000x1000) are deleted so they are searched again with the
     * strict matching; photos the user picked by hand keep their size and are left alone.
     */
    fun migrateIfNeeded(context: Context) {
        try {
            val prefs = context.getSharedPreferences("artist_images_prefs", Context.MODE_PRIVATE)
            if (prefs.getInt("matching_version", 1) >= MATCHING_VERSION) return
            val dir = File(context.filesDir, "artist_images")
            dir.listFiles()?.forEach { file ->
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                val fromDeezer = options.outWidth == options.outHeight && options.outWidth in setOf(1000, 500, 250)
                if (fromDeezer || file.length() == 0L) file.delete()
            }
            prefs.edit().putInt("matching_version", MATCHING_VERSION).apply()
            markChanged()
        } catch (e: Exception) {
            TelemetryLogger.logError(context, "ArtistImages", "Failed to migrate artist photos", e)
        }
    }

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
        markChanged()
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
                val ownTitles = try { songTitlesProvider?.invoke(artistName).orEmpty() } catch (e: Exception) { emptyList() }
                var pictureUrl: String? = searchDeezerArtistByOwnSongs(cleanSearch, ownTitles)

                if (pictureUrl.isNullOrEmpty()) {
                    pictureUrl = searchDeezerArtistPicture(cleanSearch)
                }

                if (pictureUrl.isNullOrEmpty()) {
                    pictureUrl = searchDeezerTrackArtistPicture(cleanSearch)
                }

                if (!pictureUrl.isNullOrEmpty()) {
                    val downloaded = downloadImageToDisk(pictureUrl, localFile)
                    if (downloaded) {
                        markChanged()
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

    private fun normalizeName(name: String): String = name.stripAccents().lowercase().trim()
    private fun alphaNum(name: String): String = normalizeName(name).replace(Regex("[^a-z0-9]"), "")

    private fun namesMatch(candidate: String, target: String): Boolean {
        val c = normalizeName(candidate)
        val t = normalizeName(target)
        if (c == t) return true
        val ca = alphaNum(candidate)
        return ca.isNotEmpty() && ca == alphaNum(target)
    }

    private fun pictureOf(artistObj: JSONObject): String? {
        val pic = artistObj.optString("picture_xl", "").ifEmpty {
            artistObj.optString("picture_big", "").ifEmpty { artistObj.optString("picture_medium", "") }
        }
        return pic.takeIf { isValidPictureUrl(it) }
    }

    private fun getJson(url: String): JSONObject? {
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            JSONObject(body)
        }
    }

    /**
     * Finds the artist through one of the user's own songs (`artist:"X" track:"Y"`). This is what tells apart
     * artists that share a name: the right one is the one who actually recorded the song in the library.
     */
    private fun searchDeezerArtistByOwnSongs(cleanSearch: String, titles: List<String>): String? {
        for (title in titles) {
            val cleanTitle = title.replace(Regex("\\s*[\\[(].*?[\\])]"), "").trim()
            if (cleanTitle.length < 2) continue
            try {
                val query = URLEncoder.encode("artist:\"$cleanSearch\" track:\"$cleanTitle\"", "UTF-8")
                val data = getJson("https://api.deezer.com/search?q=$query&limit=5")?.optJSONArray("data") ?: continue
                for (i in 0 until data.length()) {
                    val artistObj = data.getJSONObject(i).optJSONObject("artist") ?: continue
                    if (!namesMatch(artistObj.optString("name", ""), cleanSearch)) continue
                    pictureOf(artistObj)?.let { return it }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        return null
    }

    /**
     * Artist search. Only a result whose name matches is accepted (several with the same name: the one with the
     * most fans). There is no fallback to the first result: a placeholder is better than another artist's photo.
     */
    private fun searchDeezerArtistPicture(cleanSearch: String): String? {
        return try {
            val encodedQuery = URLEncoder.encode(cleanSearch, "UTF-8")
            val data = getJson("https://api.deezer.com/search/artist?q=$encodedQuery&limit=15")?.optJSONArray("data") ?: return null
            val candidates = (0 until data.length()).map { data.getJSONObject(it) }
            val exact = candidates.filter { namesMatch(it.optString("name", ""), cleanSearch) && pictureOf(it) != null }
            exact.maxByOrNull { it.optLong("nb_fan", 0L) }?.let { return pictureOf(it) }

            // Close spelling only ("The Beatles" / "Beatles"): one name contains the other, nearly the same length
            val target = alphaNum(cleanSearch)
            if (target.length < 4) return null
            candidates.filter {
                val name = alphaNum(it.optString("name", ""))
                name.isNotEmpty() && (name.contains(target) || target.contains(name)) &&
                    Math.abs(name.length - target.length) <= 3 && pictureOf(it) != null
            }.maxByOrNull { it.optLong("nb_fan", 0L) }?.let { pictureOf(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** Track search fallback: only an artist whose name matches is accepted. */
    private fun searchDeezerTrackArtistPicture(cleanSearch: String): String? {
        return try {
            val query = URLEncoder.encode("artist:\"$cleanSearch\"", "UTF-8")
            val data = getJson("https://api.deezer.com/search?q=$query&limit=10")?.optJSONArray("data") ?: return null
            for (i in 0 until data.length()) {
                val artistObj = data.getJSONObject(i).optJSONObject("artist") ?: continue
                if (!namesMatch(artistObj.optString("name", ""), cleanSearch)) continue
                pictureOf(artistObj)?.let { return it }
            }
            null
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
