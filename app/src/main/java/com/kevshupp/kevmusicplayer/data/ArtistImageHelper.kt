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

object ArtistImageHelper {
    private val client = OkHttpClient()
    private val downloadingArtists = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val failedArtists = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun getArtistImageFile(context: Context, artist: String): File {
        val sanitized = artist.lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        val dir = File(context.filesDir, "artist_images")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$sanitized.jpg")
    }

    suspend fun downloadArtistImage(context: Context, artist: String): File? {
        val artistName = artist.trim()
        if (artistName.isEmpty() || artistName.lowercase() == "unknown artist" || artistName.lowercase() == "<unknown>") return null

        val localFile = getArtistImageFile(context, artistName)
        if (localFile.exists() && localFile.length() > 0) {
            return localFile
        }

        if (downloadingArtists.contains(artistName) || failedArtists.contains(artistName)) {
            return null
        }

        downloadingArtists.add(artistName)

        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(artistName, "UTF-8")
                val url = "https://api.deezer.com/search/artist?q=$encodedQuery"
                
                val request = Request.Builder()
                    .url(url)
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
                        val firstResult = dataArray.getJSONObject(0)
                        val pictureUrl = firstResult.optString("picture_big", "")
                        if (pictureUrl.isNotEmpty()) {
                            val imageRequest = Request.Builder().url(pictureUrl).build()
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
