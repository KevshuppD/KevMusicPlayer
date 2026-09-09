package com.kevshupp.kevmusicplayer.playback.managers

import android.content.Context
import android.net.Uri
import com.kevshupp.kevmusicplayer.data.AudioDao
import com.kevshupp.kevmusicplayer.data.AudioFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

class BackupManager(
    private val audioDao: AudioDao
) {
    suspend fun exportBackup(
        context: Context,
        outputStream: OutputStream,
        includeSettings: Boolean = true,
        includeEqualizer: Boolean = true,
        includePlaylists: Boolean = true,
        includeLyrics: Boolean = true,
        includeStatistics: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val json = JSONObject()
        json.put("backup_version", 1)

        // 1. Export settings
        if (includeSettings) {
            val settingsPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            val defaultSystemLang = java.util.Locale.getDefault().language
            val fallbackLang = if (defaultSystemLang in listOf("es", "en", "fr", "pt")) defaultSystemLang else "es"
            val currentLanguage = settingsPrefs.getString("language", fallbackLang) ?: fallbackLang
            
            val settingsJson = JSONObject()
            settingsJson.put("language", currentLanguage)
            settingsJson.put("app_theme", settingsPrefs.getString("app_theme", "cyberpunk"))
            settingsJson.put("refresh_rate", settingsPrefs.getString("refresh_rate", "120"))
            settingsJson.put("disable_animations", settingsPrefs.getBoolean("disable_animations", false))
            settingsJson.put("excluded_folders", settingsPrefs.getString("excluded_folders", "[]"))
            
            val backupDirUri = settingsPrefs.getString("backup_dir_uri", null)
            if (backupDirUri != null) {
                settingsJson.put("backup_dir_uri", backupDirUri)
            }
            
            val musicFolderPath = settingsPrefs.getString("music_folder_path", null)
            if (musicFolderPath != null) {
                settingsJson.put("music_folder_path", musicFolderPath)
            }
            
            val playbackPrefs = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
            settingsJson.put("enabled_tabs", playbackPrefs.getString("enabled_tabs", ""))

            settingsJson.put("ambient_glow_enabled", settingsPrefs.getBoolean("ambient_glow_enabled", true))
            settingsJson.put("ambient_glow_intensity", settingsPrefs.getString("ambient_glow_intensity", "medium"))
            settingsJson.put("auto_translate", settingsPrefs.getBoolean("auto_translate", false))
            settingsJson.put("bluetooth_resume_enabled", settingsPrefs.getBoolean("bluetooth_resume_enabled", false))
            settingsJson.put("bluetooth_resume_all", settingsPrefs.getBoolean("bluetooth_resume_all", false))
            
            val btDevices = settingsPrefs.getStringSet("bluetooth_resume_devices", emptySet()) ?: emptySet()
            val btDevicesArray = JSONArray()
            btDevices.forEach { btDevicesArray.put(it) }
            settingsJson.put("bluetooth_resume_devices", btDevicesArray)
            
            settingsJson.put("normalize_sound", settingsPrefs.getBoolean("normalize_sound", false))
            settingsJson.put("crossfade_duration", settingsPrefs.getInt("crossfade_duration", 0))
            settingsJson.put("show_visualizer", settingsPrefs.getBoolean("show_visualizer", true))
            settingsJson.put("haptic_feedback_enabled", settingsPrefs.getBoolean("haptic_feedback_enabled", true))
            settingsJson.put("song_image_rounded", settingsPrefs.getBoolean("song_image_rounded", true))
            settingsJson.put("enable_transparency", settingsPrefs.getBoolean("enable_transparency", true))
            settingsJson.put("performance_profile", settingsPrefs.getString("performance_profile", "max"))
            settingsJson.put("preload_art_count", settingsPrefs.getInt("preload_art_count", 5))
            settingsJson.put("cover_cache_capacity", settingsPrefs.getInt("cover_cache_capacity", 150))
            settingsJson.put("art_resolution", settingsPrefs.getInt("art_resolution", 500))
            settingsJson.put("disk_cache_quality", settingsPrefs.getInt("disk_cache_quality", 85))
            settingsJson.put("lazy_replay_gain", settingsPrefs.getBoolean("lazy_replay_gain", true))
            settingsJson.put("ipc_queue_limit", settingsPrefs.getInt("ipc_queue_limit", 1500))
            settingsJson.put("auto_clean_temp", settingsPrefs.getBoolean("auto_clean_temp", true))
            settingsJson.put("auto_backup_interval", settingsPrefs.getString("auto_backup_interval", "off"))
            settingsJson.put("use_same_folder_for_backup", settingsPrefs.getBoolean("use_same_folder_for_backup", false))
            settingsJson.put("auto_search_lyrics", settingsPrefs.getBoolean("auto_search_lyrics", false))
            settingsJson.put("remember_lyrics_open", settingsPrefs.getBoolean("remember_lyrics_open", true))
            settingsJson.put("ignore_transient_audio_focus", settingsPrefs.getBoolean("ignore_transient_audio_focus", true))
            settingsJson.put("pause_on_headphone_unplug", settingsPrefs.getBoolean("pause_on_headphone_unplug", true))
            
            json.put("settings", settingsJson)
        }

        // 1.5. Export equalizer
        if (includeEqualizer) {
            val eqPrefs = context.getSharedPreferences("equalizer_prefs", Context.MODE_PRIVATE)
            val eqJson = JSONObject()
            eqJson.put("eq_enabled", eqPrefs.getBoolean("eq_enabled", false))
            eqJson.put("bb_enabled", eqPrefs.getBoolean("bb_enabled", false))
            eqJson.put("bb_strength", eqPrefs.getInt("bb_strength", 0))
            eqJson.put("virt_enabled", eqPrefs.getBoolean("virt_enabled", false))
            eqJson.put("virt_strength", eqPrefs.getInt("virt_strength", 0))
            eqJson.put("eq_bands", eqPrefs.getString("eq_bands", "0,0,0,0,0"))
            eqJson.put("eq_preset", eqPrefs.getString("eq_preset", "Flat"))
            json.put("equalizer", eqJson)
        }

        // 2. Export playlists
        val playlistsPrefs = context.getSharedPreferences("playlists_prefs", Context.MODE_PRIVATE)
        val playlistNames = playlistsPrefs.getStringSet("playlist_names", emptySet()) ?: emptySet()
        if (includePlaylists) {
            val playlistsJson = JSONObject()
            playlistNames.forEach { name ->
                val songsStr = playlistsPrefs.getString("playlist_$name", "") ?: ""
                val songIdsArray = JSONArray()
                if (songsStr.isNotBlank()) {
                    songsStr.split(",").forEach { idStr ->
                        idStr.toLongOrNull()?.let { songIdsArray.put(it) }
                    }
                }
                playlistsJson.put(name, songIdsArray)
            }
            json.put("playlists", playlistsJson)

            // Export smart playlists
            val smartPlaylistsJson = JSONObject()
            val smartPlaylistNames = playlistsPrefs.getStringSet("smart_playlist_names", emptySet()) ?: emptySet()
            smartPlaylistNames.forEach { name ->
                val configStr = playlistsPrefs.getString("smart_playlist_config_$name", null)
                if (configStr != null) {
                    try {
                        smartPlaylistsJson.put(name, JSONObject(configStr))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            json.put("smart_playlists", smartPlaylistsJson)

            // Export playlist covers
            val playlistCoversJson = JSONObject()
            playlistNames.forEach { name ->
                val cover = playlistsPrefs.getString("playlist_cover_$name", null)
                if (cover != null) {
                    playlistCoversJson.put(name, cover)
                }
            }
            json.put("playlist_covers", playlistCoversJson)
        }

        // 3. Export cached songs (lyrics and translatedLyrics)
        val allEntities = audioDao.getAllAudioFiles()
        if (includeLyrics) {
            val cachedSongsArray = JSONArray()
            allEntities.forEach { entity ->
                if (!entity.lyrics.isNullOrBlank() || !entity.translatedLyrics.isNullOrBlank()) {
                    val songJson = JSONObject()
                    songJson.put("id", entity.id)
                    songJson.put("lyrics", entity.lyrics)
                    songJson.put("translatedLyrics", entity.translatedLyrics)
                    cachedSongsArray.put(songJson)
                }
            }
            json.put("cached_songs", cachedSongsArray)
        }

        // 3.5. Export library songs statistics & metadata (for resumen musical and edits)
        if (includeStatistics) {
            val librarySongsArray = JSONArray()
            allEntities.forEach { entity ->
                val songJson = JSONObject()
                songJson.put("id", entity.id)
                songJson.put("title", entity.title)
                songJson.put("artist", entity.artist)
                songJson.put("album", entity.album)
                songJson.put("genre", entity.genre)
                songJson.put("year", entity.year)
                songJson.put("track", entity.track)
                songJson.put("duration", entity.duration)
                songJson.put("playCount", entity.playCount)
                songJson.put("lastPlayed", entity.lastPlayed)
                songJson.put("dateAdded", entity.dateAdded)
                if (entity.replayGain != null) {
                    songJson.put("replayGain", entity.replayGain.toDouble())
                }
                librarySongsArray.put(songJson)
            }
            json.put("library_songs", librarySongsArray)
        }

        // 4. Export songs metadata mapping for backup portability
        val idsToExport = mutableSetOf<Long>()
        if (includePlaylists) {
            playlistNames.forEach { name ->
                val songsStr = playlistsPrefs.getString("playlist_$name", "") ?: ""
                if (songsStr.isNotBlank()) {
                    songsStr.split(",").forEach { idStr ->
                        idStr.toLongOrNull()?.let { idsToExport.add(it) }
                    }
                }
            }
        }
        if (includeLyrics) {
            allEntities.forEach { entity ->
                if (!entity.lyrics.isNullOrBlank() || !entity.translatedLyrics.isNullOrBlank()) {
                    idsToExport.add(entity.id)
                }
            }
        }
        if (includeStatistics) {
            allEntities.forEach { entity ->
                idsToExport.add(entity.id)
            }
        }
        
        if (idsToExport.isNotEmpty()) {
            val songsMetadataArray = JSONArray()
            allEntities.forEach { entity ->
                if (idsToExport.contains(entity.id)) {
                    val metaJson = JSONObject()
                    metaJson.put("id", entity.id)
                    metaJson.put("title", entity.title)
                    metaJson.put("artist", entity.artist)
                    metaJson.put("album", entity.album)
                    metaJson.put("duration", entity.duration)
                    songsMetadataArray.put(metaJson)
                }
            }
            json.put("songs_metadata", songsMetadataArray)
        }

        // Write to stream
        outputStream.use { stream ->
            stream.write(json.toString(4).toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun importBackup(
        context: Context,
        inputStream: InputStream
    ): ImportResult = withContext(Dispatchers.IO) {
        val jsonStr = inputStream.use { stream ->
            stream.bufferedReader().use { it.readText() }
        }
        val json = JSONObject(jsonStr)
        var importedLanguage: String? = null
        var restoredTabs: List<String>? = null

        // 0. Build mapping from old ID to new ID using songs_metadata
        val oldToNewIdMap = mutableMapOf<Long, Long>()
        val currentSongs = audioDao.getAllAudioFiles()
        
        if (json.has("songs_metadata")) {
            val songsMetadataArray = json.getJSONArray("songs_metadata")
            for (i in 0 until songsMetadataArray.length()) {
                val meta = songsMetadataArray.getJSONObject(i)
                val oldId = meta.getLong("id")
                val title = meta.getString("title")
                val artist = meta.getString("artist")
                val album = meta.getString("album")
                val duration = meta.getLong("duration")
                
                // Look for a matching local song
                val matchedSong = currentSongs.find { current ->
                    current.title.trim().equals(title.trim(), ignoreCase = true) &&
                    (current.artist.trim().equals(artist.trim(), ignoreCase = true) || artist.isBlank() || current.artist.isBlank()) &&
                    Math.abs(current.duration - duration) < 4000
                }
                if (matchedSong != null) {
                    oldToNewIdMap[oldId] = matchedSong.id
                }
            }
        }

        fun translateId(oldId: Long): Long? {
            return if (oldToNewIdMap.isNotEmpty()) {
                oldToNewIdMap[oldId]
            } else {
                oldId // fallback to direct mapping for backward compatibility
            }
        }

        // 1. Restore settings
        if (json.has("settings")) {
            val settingsJson = json.getJSONObject("settings")
            val settingsPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            val edit = settingsPrefs.edit()
            edit.putBoolean("is_first_run", false)
            if (settingsJson.has("language")) {
                val lang = settingsJson.getString("language")
                edit.putString("language", lang)
                importedLanguage = lang
            }
            if (settingsJson.has("app_theme")) edit.putString("app_theme", settingsJson.getString("app_theme"))
            if (settingsJson.has("refresh_rate")) edit.putString("refresh_rate", settingsJson.getString("refresh_rate"))
            if (settingsJson.has("disable_animations")) edit.putBoolean("disable_animations", settingsJson.getBoolean("disable_animations"))
            if (settingsJson.has("backup_dir_uri")) edit.putString("backup_dir_uri", settingsJson.getString("backup_dir_uri"))
            if (settingsJson.has("music_folder_path")) {
                val musicFolderPath = settingsJson.getString("music_folder_path")
                if (!musicFolderPath.isNullOrBlank()) {
                    edit.putString("music_folder_path", musicFolderPath)
                }
            }
            if (settingsJson.has("excluded_folders")) edit.putString("excluded_folders", settingsJson.getString("excluded_folders"))
            
            if (settingsJson.has("ambient_glow_enabled")) edit.putBoolean("ambient_glow_enabled", settingsJson.getBoolean("ambient_glow_enabled"))
            if (settingsJson.has("ambient_glow_intensity")) edit.putString("ambient_glow_intensity", settingsJson.getString("ambient_glow_intensity"))
            if (settingsJson.has("auto_translate")) edit.putBoolean("auto_translate", settingsJson.getBoolean("auto_translate"))
            if (settingsJson.has("bluetooth_resume_enabled")) edit.putBoolean("bluetooth_resume_enabled", settingsJson.getBoolean("bluetooth_resume_enabled"))
            if (settingsJson.has("bluetooth_resume_all")) edit.putBoolean("bluetooth_resume_all", settingsJson.getBoolean("bluetooth_resume_all"))
            
            if (settingsJson.has("bluetooth_resume_devices")) {
                val btArray = settingsJson.getJSONArray("bluetooth_resume_devices")
                val btSet = mutableSetOf<String>()
                for (i in 0 until btArray.length()) {
                    btSet.add(btArray.getString(i))
                }
                edit.putStringSet("bluetooth_resume_devices", btSet)
            }
            
            if (settingsJson.has("normalize_sound")) edit.putBoolean("normalize_sound", settingsJson.getBoolean("normalize_sound"))
            if (settingsJson.has("crossfade_duration")) edit.putInt("crossfade_duration", settingsJson.getInt("crossfade_duration"))
            if (settingsJson.has("show_visualizer")) edit.putBoolean("show_visualizer", settingsJson.getBoolean("show_visualizer"))
            if (settingsJson.has("haptic_feedback_enabled")) edit.putBoolean("haptic_feedback_enabled", settingsJson.getBoolean("haptic_feedback_enabled"))
            if (settingsJson.has("song_image_rounded")) edit.putBoolean("song_image_rounded", settingsJson.getBoolean("song_image_rounded"))
            if (settingsJson.has("enable_transparency")) edit.putBoolean("enable_transparency", settingsJson.getBoolean("enable_transparency"))
            if (settingsJson.has("performance_profile")) edit.putString("performance_profile", settingsJson.getString("performance_profile"))
            if (settingsJson.has("preload_art_count")) edit.putInt("preload_art_count", settingsJson.getInt("preload_art_count"))
            if (settingsJson.has("cover_cache_capacity")) edit.putInt("cover_cache_capacity", settingsJson.getInt("cover_cache_capacity"))
            if (settingsJson.has("art_resolution")) edit.putInt("art_resolution", settingsJson.getInt("art_resolution"))
            if (settingsJson.has("disk_cache_quality")) edit.putInt("disk_cache_quality", settingsJson.getInt("disk_cache_quality"))
            if (settingsJson.has("lazy_replay_gain")) edit.putBoolean("lazy_replay_gain", settingsJson.getBoolean("lazy_replay_gain"))
            if (settingsJson.has("ipc_queue_limit")) edit.putInt("ipc_queue_limit", settingsJson.getInt("ipc_queue_limit"))
            if (settingsJson.has("auto_clean_temp")) edit.putBoolean("auto_clean_temp", settingsJson.getBoolean("auto_clean_temp"))
            if (settingsJson.has("auto_backup_interval")) edit.putString("auto_backup_interval", settingsJson.getString("auto_backup_interval"))
            if (settingsJson.has("use_same_folder_for_backup")) edit.putBoolean("use_same_folder_for_backup", settingsJson.getBoolean("use_same_folder_for_backup"))
            if (settingsJson.has("auto_search_lyrics")) edit.putBoolean("auto_search_lyrics", settingsJson.getBoolean("auto_search_lyrics"))
            if (settingsJson.has("remember_lyrics_open")) edit.putBoolean("remember_lyrics_open", settingsJson.getBoolean("remember_lyrics_open"))
            if (settingsJson.has("ignore_transient_audio_focus")) edit.putBoolean("ignore_transient_audio_focus", settingsJson.getBoolean("ignore_transient_audio_focus"))
            if (settingsJson.has("pause_on_headphone_unplug")) edit.putBoolean("pause_on_headphone_unplug", settingsJson.getBoolean("pause_on_headphone_unplug"))
            
            edit.apply()

            // Restore tab configurations
            if (settingsJson.has("enabled_tabs")) {
                val playbackPrefs = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
                val tabsStr = settingsJson.getString("enabled_tabs")
                playbackPrefs.edit().putString("enabled_tabs", tabsStr).apply()
                if (!tabsStr.isNullOrBlank()) {
                    restoredTabs = tabsStr.split(",")
                }
            }
        }

        // 1.5. Restore equalizer
        if (json.has("equalizer")) {
            val eqJson = json.getJSONObject("equalizer")
            val eqPrefs = context.getSharedPreferences("equalizer_prefs", Context.MODE_PRIVATE)
            val eqEdit = eqPrefs.edit()
            if (eqJson.has("eq_enabled")) eqEdit.putBoolean("eq_enabled", eqJson.getBoolean("eq_enabled"))
            if (eqJson.has("bb_enabled")) eqEdit.putBoolean("bb_enabled", eqJson.getBoolean("bb_enabled"))
            if (eqJson.has("bb_strength")) eqEdit.putInt("bb_strength", eqJson.getInt("bb_strength"))
            if (eqJson.has("virt_enabled")) eqEdit.putBoolean("virt_enabled", eqJson.getBoolean("virt_enabled"))
            if (eqJson.has("virt_strength")) eqEdit.putInt("virt_strength", eqJson.getInt("virt_strength"))
            if (eqJson.has("eq_bands")) eqEdit.putString("eq_bands", eqJson.getString("eq_bands"))
            if (eqJson.has("eq_preset")) eqEdit.putString("eq_preset", eqJson.getString("eq_preset"))
            eqEdit.apply()
        }

        // 2. Restore playlists
        if (json.has("playlists")) {
            val playlistsJson = json.getJSONObject("playlists")
            val playlistsPrefs = context.getSharedPreferences("playlists_prefs", Context.MODE_PRIVATE)
            val edit = playlistsPrefs.edit()
            val names = mutableSetOf<String>()

            playlistsJson.keys().forEach { name ->
                names.add(name)
                val songIdsArray = playlistsJson.getJSONArray(name)
                val songIds = mutableListOf<String>()
                for (i in 0 until songIdsArray.length()) {
                    val oldId = songIdsArray.getLong(i)
                    val newId = translateId(oldId)
                    if (newId != null) {
                        songIds.add(newId.toString())
                    }
                }
                edit.putString("playlist_$name", songIds.joinToString(","))
            }
            edit.putStringSet("playlist_names", names)

            // Restore smart playlists if any
            if (json.has("smart_playlists")) {
                val smartJson = json.getJSONObject("smart_playlists")
                val smartNames = mutableSetOf<String>()
                smartJson.keys().forEach { name ->
                    smartNames.add(name)
                    edit.putString("smart_playlist_config_$name", smartJson.getJSONObject(name).toString())
                }
                edit.putStringSet("smart_playlist_names", smartNames)
            }

            // Restore playlist covers if any
            if (json.has("playlist_covers")) {
                val coversJson = json.getJSONObject("playlist_covers")
                coversJson.keys().forEach { name ->
                    edit.putString("playlist_cover_$name", coversJson.getString(name))
                }
            }

            edit.apply()
        }

        // 3. Restore cached songs (lyrics and translatedLyrics)
        if (json.has("cached_songs")) {
            val cachedSongsArray = json.getJSONArray("cached_songs")
            for (i in 0 until cachedSongsArray.length()) {
                val songJson = cachedSongsArray.getJSONObject(i)
                val oldId = songJson.getLong("id")
                val newId = translateId(oldId)
                if (newId != null) {
                    val lyrics = if (songJson.has("lyrics") && !songJson.isNull("lyrics")) songJson.getString("lyrics") else null
                    val translatedLyrics = if (songJson.has("translatedLyrics") && !songJson.isNull("translatedLyrics")) songJson.getString("translatedLyrics") else null

                    audioDao.updateLyrics(newId, lyrics)
                    audioDao.updateTranslatedLyrics(newId, translatedLyrics)
                }
            }
        }

        // 3.5. Restore library songs data (statistics, ReplayGain, custom metadata)
        if (json.has("library_songs")) {
            val librarySongsArray = json.getJSONArray("library_songs")
            val songsToUpdate = mutableListOf<AudioFile>()
            for (i in 0 until librarySongsArray.length()) {
                val songJson = librarySongsArray.getJSONObject(i)
                val oldId = songJson.getLong("id")
                val newId = translateId(oldId)
                if (newId != null) {
                    val localSong = currentSongs.find { it.id == newId }
                    if (localSong != null) {
                        var updated = localSong
                        if (songJson.has("playCount")) {
                            updated = updated.copy(playCount = songJson.getInt("playCount"))
                        }
                        if (songJson.has("lastPlayed")) {
                            updated = updated.copy(lastPlayed = songJson.getLong("lastPlayed"))
                        }
                        if (songJson.has("replayGain") && !songJson.isNull("replayGain")) {
                            updated = updated.copy(replayGain = songJson.getDouble("replayGain").toFloat())
                        }
                        if (songJson.has("genre") && !songJson.isNull("genre")) {
                            updated = updated.copy(genre = songJson.getString("genre"))
                        }
                        if (songJson.has("year") && !songJson.isNull("year")) {
                            updated = updated.copy(year = songJson.getString("year"))
                        }
                        if (songJson.has("track")) {
                            updated = updated.copy(track = songJson.getInt("track"))
                        }
                        if (songJson.has("title") && !songJson.isNull("title")) {
                            updated = updated.copy(title = songJson.getString("title"))
                        }
                        if (songJson.has("artist") && !songJson.isNull("artist")) {
                            updated = updated.copy(artist = songJson.getString("artist"))
                        }
                        if (songJson.has("album") && !songJson.isNull("album")) {
                            updated = updated.copy(album = songJson.getString("album"))
                        }
                        if (songJson.has("dateAdded")) {
                            updated = updated.copy(dateAdded = songJson.getLong("dateAdded"))
                        }
                        songsToUpdate.add(updated)
                    }
                }
            }
            if (songsToUpdate.isNotEmpty()) {
                audioDao.insertAll(songsToUpdate)
            }
        }

        ImportResult(
            importedLanguage = importedLanguage,
            restoredTabs = restoredTabs
        )
    }

    fun checkAutoBackup(context: Context, onPerformBackup: (OutputStream, () -> Unit, (Exception) -> Unit) -> Unit) {
        val settingsPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        val interval = settingsPrefs.getString("auto_backup_interval", "off") ?: "off"
        if (interval == "off") return

        val lastBackupTime = settingsPrefs.getLong("last_backup_time", 0L)
        val currentTime = System.currentTimeMillis()
        val durationMs = when (interval) {
            "daily" -> 24L * 60 * 60 * 1000
            "weekly" -> 7L * 24 * 60 * 60 * 1000
            "monthly" -> 30L * 24 * 60 * 60 * 1000
            else -> return
        }

        if (currentTime - lastBackupTime >= durationMs) {
            performAutoBackup(context, onPerformBackup)
        }
    }

    private fun performAutoBackup(context: Context, onPerformBackup: (OutputStream, () -> Unit, (Exception) -> Unit) -> Unit) {
        val settingsPrefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        val useSameFolder = settingsPrefs.getBoolean("use_same_folder_for_backup", false)
        val selectedMusicFolder = settingsPrefs.getString("music_folder_path", null)
        val backupDirUri = settingsPrefs.getString("backup_dir_uri", null)

        if (useSameFolder && selectedMusicFolder != null) {
            val backupFile = java.io.File(selectedMusicFolder, "kev_music_player_backup.json")
            try {
                val outputStream = backupFile.outputStream()
                onPerformBackup(
                    outputStream,
                    { settingsPrefs.edit().putLong("last_backup_time", System.currentTimeMillis()).apply() },
                    { e -> e.printStackTrace() }
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else if (!useSameFolder && backupDirUri != null) {
            try {
                val folderUri = Uri.parse(backupDirUri)
                val dirFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
                if (dirFile != null && dirFile.exists()) {
                    var backupFile = dirFile.findFile("kev_music_player_backup.json")
                    if (backupFile == null) {
                        backupFile = dirFile.createFile("application/json", "kev_music_player_backup.json")
                    }
                    val fileUri = backupFile?.uri
                    if (fileUri != null) {
                        val outputStream = context.contentResolver.openOutputStream(fileUri, "rwt")
                        if (outputStream != null) {
                            onPerformBackup(
                                outputStream,
                                { settingsPrefs.edit().putLong("last_backup_time", System.currentTimeMillis()).apply() },
                                { e -> e.printStackTrace() }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

data class ImportResult(
    val importedLanguage: String?,
    val restoredTabs: List<String>?
)
