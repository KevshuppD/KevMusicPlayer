package com.kevshupp.kevmusicplayer.data

import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Music folders chosen by the user. The main folder (`music_folder_path`, the one picked during onboarding) is
 * where "Organize library" moves songs; the extra folders (`extra_music_folders`, JSON array) are only scanned.
 * No main folder means the whole device is scanned.
 */
object MusicFolders {
    const val KEY_MAIN = "music_folder_path"
    const val KEY_EXTRA = "extra_music_folders"

    fun main(prefs: SharedPreferences): String? = prefs.getString(KEY_MAIN, null)?.takeIf { it.isNotBlank() }

    fun extras(prefs: SharedPreferences): List<String> {
        return try {
            val array = JSONArray(prefs.getString(KEY_EXTRA, "[]") ?: "[]")
            (0 until array.length()).map { array.getString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Main folder first, then the extra ones. Empty when the whole device is scanned. */
    fun all(prefs: SharedPreferences): List<String> {
        val main = main(prefs) ?: return emptyList()
        return (listOf(main) + extras(prefs)).distinct()
    }

    fun isInside(folderPath: String, folders: List<String>): Boolean {
        if (folders.isEmpty()) return true
        return folders.any { root ->
            val base = root.trimEnd('/')
            folderPath.equals(base, ignoreCase = true) || folderPath.startsWith("$base/", ignoreCase = true)
        }
    }

    fun setExtras(prefs: SharedPreferences, folders: List<String>) {
        val array = JSONArray()
        folders.distinct().forEach { array.put(it) }
        prefs.edit().putString(KEY_EXTRA, array.toString()).apply()
    }

    /** Adds a folder: it becomes the main one if there is none yet. Returns false if it was already covered. */
    fun add(prefs: SharedPreferences, path: String): Boolean {
        val main = main(prefs)
        if (main == null) {
            prefs.edit().putString(KEY_MAIN, path).apply()
            return true
        }
        val current = all(prefs)
        if (current.any { it.equals(path, ignoreCase = true) }) return false
        setExtras(prefs, extras(prefs) + path)
        return true
    }

    /** Removes a folder. Removing the main one promotes the first extra folder to main. */
    fun remove(prefs: SharedPreferences, path: String) {
        val extras = extras(prefs)
        if (main(prefs) == path) {
            val promoted = extras.firstOrNull()
            if (promoted != null) {
                prefs.edit().putString(KEY_MAIN, promoted).apply()
                setExtras(prefs, extras.drop(1))
            } else {
                prefs.edit().remove(KEY_MAIN).apply()
            }
        } else {
            setExtras(prefs, extras.filter { it != path })
        }
    }

    /** Makes an extra folder the main one; the old main folder becomes an extra. */
    fun makeMain(prefs: SharedPreferences, path: String) {
        val main = main(prefs) ?: return
        if (main == path) return
        prefs.edit().putString(KEY_MAIN, path).apply()
        setExtras(prefs, listOf(main) + extras(prefs).filter { it != path })
    }
}
