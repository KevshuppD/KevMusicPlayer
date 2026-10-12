package com.kevshupp.kevmusicplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A song whose MediaStore row vanished (SD card unmounted, file moved while the app was closed...), kept so its
 * stats, lyrics and playlist membership can be handed back when the same file shows up again under a new id.
 */
data class OrphanedSong(
    val oldId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val folderPath: String,
    val playCount: Int,
    val lastPlayed: Long,
    val replayGain: Float?,
    val lyrics: String?,
    val translatedLyrics: String?,
    val removedAt: Long
)

/**
 * MediaStore ids are not stable: re-indexing a volume gives every file a new one. Everything the app stores is
 * keyed by that id, so this keeps the identity of a song across id changes.
 */
object SongIdentity {
    private const val ORPHANS_FILE = "orphaned_songs.json"
    private const val MAX_ORPHANS = 5000
    private const val ORPHAN_TTL_MS = 365L * 24 * 60 * 60 * 1000

    /** Same rule the backup import uses to recognise a song on another device. */
    fun sameSong(
        title: String, artist: String, duration: Long,
        otherTitle: String, otherArtist: String, otherDuration: Long
    ): Boolean {
        return title.trim().equals(otherTitle.trim(), ignoreCase = true) &&
            (artist.trim().equals(otherArtist.trim(), ignoreCase = true) || artist.isBlank() || otherArtist.isBlank()) &&
            Math.abs(duration - otherDuration) < 4000
    }

    /** Higher is a better candidate when several old songs share title, artist and length. */
    fun matchScore(folderPath: String, album: String, otherFolderPath: String, otherAlbum: String): Int {
        var score = 0
        if (folderPath == otherFolderPath) score += 2
        if (album.trim().equals(otherAlbum.trim(), ignoreCase = true)) score += 1
        return score
    }

    /** Rewrites a comma separated id list, dropping the duplicates a remap can create. Null if nothing changed. */
    fun remapIdList(idsStr: String, remap: Map<Long, Long>): String? {
        if (idsStr.isBlank()) return null
        var changed = false
        val seen = LinkedHashSet<String>()
        idsStr.split(",").forEach { raw ->
            val newId = raw.trim().toLongOrNull()?.let { remap[it] }
            if (newId != null) {
                changed = true
                seen.add(newId.toString())
            } else {
                seen.add(raw)
            }
        }
        return if (changed) seen.joinToString(",") else null
    }

    fun playlistSongIds(context: Context): Set<Long> {
        val prefs = context.getSharedPreferences("playlists_prefs", Context.MODE_PRIVATE)
        val ids = HashSet<Long>()
        (prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()).forEach { name ->
            (prefs.getString("playlist_$name", "") ?: "").split(",").forEach { raw ->
                raw.trim().toLongOrNull()?.let { ids.add(it) }
            }
        }
        return ids
    }

    /** Points the playlists and the internal lyric files of every remapped song at its new id. */
    fun applyIdRemap(context: Context, remap: Map<Long, Long>) {
        if (remap.isEmpty()) return

        val prefs = context.getSharedPreferences("playlists_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var playlistsChanged = false
        (prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()).forEach { name ->
            val remapped = remapIdList(prefs.getString("playlist_$name", "") ?: "", remap)
            if (remapped != null) {
                editor.putString("playlist_$name", remapped)
                playlistsChanged = true
            }
        }
        if (playlistsChanged) editor.apply()

        try {
            File(context.filesDir, "lyrics").listFiles()?.forEach { file ->
                val oldId = file.name.substringBefore('.').toLongOrNull() ?: return@forEach
                val newId = remap[oldId] ?: return@forEach
                val target = File(file.parentFile, newId.toString() + file.name.substring(file.name.indexOf('.')))
                if (!target.exists()) file.renameTo(target)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadOrphans(context: Context): List<OrphanedSong> {
        return try {
            val file = File(context.filesDir, ORPHANS_FILE)
            if (!file.exists()) return emptyList()
            val array = JSONArray(file.readText())
            val minRemovedAt = System.currentTimeMillis() - ORPHAN_TTL_MS
            (0 until array.length()).mapNotNull { i ->
                val json = array.getJSONObject(i)
                val removedAt = json.optLong("removedAt", 0L)
                if (removedAt < minRemovedAt) return@mapNotNull null
                OrphanedSong(
                    oldId = json.getLong("oldId"),
                    title = json.optString("title", ""),
                    artist = json.optString("artist", ""),
                    album = json.optString("album", ""),
                    duration = json.optLong("duration", 0L),
                    folderPath = json.optString("folderPath", ""),
                    playCount = json.optInt("playCount", 0),
                    lastPlayed = json.optLong("lastPlayed", 0L),
                    replayGain = if (json.has("replayGain")) json.getDouble("replayGain").toFloat() else null,
                    lyrics = if (json.has("lyrics")) json.getString("lyrics") else null,
                    translatedLyrics = if (json.has("translatedLyrics")) json.getString("translatedLyrics") else null,
                    removedAt = removedAt
                )
            }
        } catch (e: Exception) {
            TelemetryLogger.logError(context, "SongIdentity", "Failed to read orphaned songs", e)
            emptyList()
        }
    }

    fun saveOrphans(context: Context, orphans: List<OrphanedSong>) {
        try {
            val array = JSONArray()
            orphans.sortedByDescending { it.removedAt }.take(MAX_ORPHANS).forEach { orphan ->
                val json = JSONObject()
                json.put("oldId", orphan.oldId)
                json.put("title", orphan.title)
                json.put("artist", orphan.artist)
                json.put("album", orphan.album)
                json.put("duration", orphan.duration)
                json.put("folderPath", orphan.folderPath)
                json.put("playCount", orphan.playCount)
                json.put("lastPlayed", orphan.lastPlayed)
                if (orphan.replayGain != null) json.put("replayGain", orphan.replayGain.toDouble())
                if (orphan.lyrics != null) json.put("lyrics", orphan.lyrics)
                if (orphan.translatedLyrics != null) json.put("translatedLyrics", orphan.translatedLyrics)
                json.put("removedAt", orphan.removedAt)
                array.put(json)
            }
            // Written to a sibling and renamed so an interrupted save never leaves half a file
            val file = File(context.filesDir, ORPHANS_FILE)
            val temp = File(context.filesDir, "$ORPHANS_FILE.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(file)) temp.delete()
        } catch (e: Exception) {
            TelemetryLogger.logError(context, "SongIdentity", "Failed to save orphaned songs", e)
        }
    }
}
