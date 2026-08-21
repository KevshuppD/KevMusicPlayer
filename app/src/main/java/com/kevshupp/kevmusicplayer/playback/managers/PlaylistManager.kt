package com.kevshupp.kevmusicplayer.playback.managers

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.playback.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class PlaylistManager(
    private val application: Application,
    private val localAudioFiles: SnapshotStateList<AudioFile>,
    private val scope: CoroutineScope
) {
    val playlists = mutableStateMapOf<String, List<AudioFile>>()
    val playlistCovers = mutableStateMapOf<String, String>()
    val smartPlaylists = mutableStateMapOf<String, List<AudioFile>>()
    val smartPlaylistConfigs = mutableStateListOf<SmartPlaylistConfig>()

    fun addSongsToPlaylist(playlistName: String, songIds: List<Long>) {
        val currentList = playlists[playlistName]?.toMutableList() ?: mutableListOf()
        var modified = false
        songIds.forEach { songId ->
            if (!currentList.any { it.id == songId }) {
                val song = localAudioFiles.find { it.id == songId }
                if (song != null) {
                    currentList.add(song)
                    modified = true
                }
            }
        }
        if (modified) {
            playlists[playlistName] = currentList
            val prefs = application.getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
            val idsStr = currentList.map { it.id }.joinToString(",")
            prefs.edit()
                .putString("playlist_$playlistName", idsStr)
                .apply()
        }
    }


    fun updateSmartPlaylists() {
        val localFilesCopy = ArrayList(localAudioFiles)
        scope.launch {
            val updatedMap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val tempMap = mutableMapOf<String, List<AudioFile>>()
                val keepRecommendations = smartPlaylists.filterKeys { it.startsWith("Recomendaciones") }
                tempMap.putAll(keepRecommendations)

                smartPlaylistConfigs.forEach { config ->
                    val list = if (config.isAdvanced && config.advancedRule != null) {
                        localFilesCopy.filter { config.advancedRule.evaluate(application, it) }
                            .take(config.limit)
                    } else {
                        when (config.rule) {
                            SmartPlaylistRule.MOST_PLAYED -> {
                                localFilesCopy.filter { it.playCount > 0 }
                                    .sortedByDescending { it.playCount }
                                    .take(config.limit)
                            }
                            SmartPlaylistRule.RECENTLY_ADDED -> {
                                localFilesCopy.sortedByDescending { it.dateAdded }
                                    .take(config.limit)
                            }
                            SmartPlaylistRule.PLAYBACK_HISTORY -> {
                                localFilesCopy.filter { it.lastPlayed > 0L }
                                    .sortedByDescending { it.lastPlayed }
                                    .take(config.limit)
                            }
                            SmartPlaylistRule.LONGEST_SONGS -> {
                                localFilesCopy.sortedByDescending { it.duration }
                                    .take(config.limit)
                            }
                            SmartPlaylistRule.SHORTEST_SONGS -> {
                                localFilesCopy.sortedBy { it.duration }
                                    .take(config.limit)
                            }
                            SmartPlaylistRule.NEVER_PLAYED -> {
                                localFilesCopy.filter { it.playCount == 0 }
                                    .sortedBy { it.title.lowercase() }
                                    .take(config.limit)
                            }
                            SmartPlaylistRule.RANDOM_MIX -> {
                                localFilesCopy.shuffled()
                                    .take(config.limit)
                            }
                        }
                    }
                    tempMap[config.name] = list
                }

                // 4. "Recomendaciones" (Recommendations) based on the user's most played artist
                val artistPlayCounts = localFilesCopy.filter { it.playCount > 0 }
                    .groupBy { it.artist }
                    .mapValues { entry -> entry.value.sumOf { it.playCount } }
                
                val favoriteArtist = artistPlayCounts.maxByOrNull { it.value }?.key
                
                if (favoriteArtist != null) {
                    val artistSongs = localFilesCopy.filter { it.artist == favoriteArtist }
                    val recommendedSongs = artistSongs.sortedBy { it.playCount }.take(15)
                    tempMap["Recomendaciones ($favoriteArtist)"] = recommendedSongs
                } else {
                    val allArtists = localFilesCopy.map { it.artist }.distinct()
                    if (allArtists.isNotEmpty()) {
                        val randomArtist = allArtists.random()
                        val recommendedSongs = localFilesCopy.filter { it.artist == randomArtist }.take(15)
                        tempMap["Recomendaciones ($randomArtist)"] = recommendedSongs
                    } else {
                        tempMap["Recomendaciones"] = emptyList()
                    }
                }
                tempMap
            }

            smartPlaylists.clear()
            smartPlaylists.putAll(updatedMap)
        }
    }

    fun loadPlaylists() {
        scope.launch {
            val localFilesCopy = ArrayList(localAudioFiles)
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val tempPlaylists = mutableMapOf<String, List<AudioFile>>()
                val tempCovers = mutableMapOf<String, String>()
                val tempConfigs = mutableListOf<SmartPlaylistConfig>()
                
                val prefs = application.getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
                val names = prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()
                val songsMap = localFilesCopy.associateBy { it.id }
                
                names.forEach { name ->
                    val idsStr = prefs.getString("playlist_$name", "") ?: ""
                    if (idsStr.isNotBlank()) {
                        val ids = idsStr.split(",").mapNotNull { it.toLongOrNull() }
                        val songs = ids.mapNotNull { id -> songsMap[id] }
                        tempPlaylists[name] = songs
                    } else {
                        tempPlaylists[name] = emptyList()
                    }

                    val cover = prefs.getString("playlist_cover_$name", null)
                    if (cover != null) {
                        tempCovers[name] = cover
                    }
                }

                val smartNames = prefs.getStringSet("smart_playlist_names", emptySet()) ?: emptySet()
                smartNames.forEach { name ->
                    val jsonString = prefs.getString("smart_playlist_config_$name", null)
                    if (jsonString != null) {
                        try {
                            val json = JSONObject(jsonString)
                            val rule = SmartPlaylistRule.valueOf(json.getString("rule"))
                            val limit = json.getInt("limit")
                            val isAdvanced = json.optBoolean("isAdvanced", false)
                            val advancedRule = if (isAdvanced && json.has("advancedRule")) {
                                SmartRuleNode.fromJson(json.getJSONObject("advancedRule"))
                            } else null
                            tempConfigs.add(SmartPlaylistConfig(name, rule, limit, isAdvanced, advancedRule))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                Triple(tempPlaylists, tempCovers, tempConfigs)
            }
            
            playlists.clear()
            playlists.putAll(result.first)
            playlistCovers.clear()
            playlistCovers.putAll(result.second)
            smartPlaylistConfigs.clear()
            smartPlaylistConfigs.addAll(result.third)
            
            updateSmartPlaylists()
        }
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        val prefs = application.getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
        val names = (prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()).toMutableSet()
        names.add(name)
        prefs.edit()
            .putStringSet("playlist_names", names)
            .putString("playlist_$name", "")
            .apply()
        playlists[name] = emptyList()
    }

    fun createSmartPlaylist(name: String, rule: SmartPlaylistRule, limit: Int, isAdvanced: Boolean = false, advancedRule: SmartRuleNode? = null) {
        if (name.isBlank()) return
        val prefs = application.getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
        val names = (prefs.getStringSet("smart_playlist_names", emptySet()) ?: emptySet()).toMutableSet()
        names.add(name)
        
        val json = JSONObject()
        json.put("name", name)
        json.put("rule", rule.name)
        json.put("limit", limit)
        json.put("isAdvanced", isAdvanced)
        if (isAdvanced && advancedRule != null) {
            json.put("advancedRule", advancedRule.toJson())
        }
        
        prefs.edit()
            .putStringSet("smart_playlist_names", names)
            .putString("smart_playlist_config_$name", json.toString())
            .apply()
            
        smartPlaylistConfigs.add(SmartPlaylistConfig(name, rule, limit, isAdvanced, advancedRule))
        updateSmartPlaylists()
    }

    fun setPlaylistCover(name: String, imageUriStr: String) {
        val prefs = application.getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("playlist_cover_$name", imageUriStr).apply()
        playlistCovers[name] = imageUriStr
    }

    fun addSongToPlaylist(playlistName: String, songId: Long) {
        val currentList = playlists[playlistName]?.toMutableList() ?: mutableListOf()
        if (currentList.any { it.id == songId }) return
        val song = localAudioFiles.find { it.id == songId } ?: return
        currentList.add(song)
        playlists[playlistName] = currentList

        val prefs = application.getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
        val idsStr = currentList.map { it.id }.joinToString(",")
        prefs.edit()
            .putString("playlist_$playlistName", idsStr)
            .apply()
    }

    fun removeSongFromPlaylist(playlistName: String, songId: Long) {
        val currentList = playlists[playlistName]?.toMutableList() ?: return
        currentList.removeAll { it.id == songId }
        playlists[playlistName] = currentList

        val prefs = application.getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
        val idsStr = currentList.map { it.id }.joinToString(",")
        prefs.edit()
            .putString("playlist_$playlistName", idsStr)
            .apply()
    }

    fun deletePlaylist(name: String) {
        val prefs = application.getSharedPreferences("playlists_prefs", android.content.Context.MODE_PRIVATE)
        
        val smartNames = (prefs.getStringSet("smart_playlist_names", emptySet()) ?: emptySet()).toMutableSet()
        if (smartNames.contains(name)) {
            smartNames.remove(name)
            prefs.edit()
                .putStringSet("smart_playlist_names", smartNames)
                .remove("smart_playlist_config_$name")
                .apply()
            smartPlaylistConfigs.removeAll { it.name == name }
            smartPlaylists.remove(name)
            return
        }

        val names = (prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()).toMutableSet()
        names.remove(name)

        // Delete local cover file if exists
        val coverPath = prefs.getString("playlist_cover_$name", null)
        if (coverPath != null) {
            try {
                val file = java.io.File(coverPath)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        prefs.edit()
            .putStringSet("playlist_names", names)
            .remove("playlist_$name")
            .remove("playlist_cover_$name")
            .apply()
        playlists.remove(name)
        playlistCovers.remove(name)
    }


}
