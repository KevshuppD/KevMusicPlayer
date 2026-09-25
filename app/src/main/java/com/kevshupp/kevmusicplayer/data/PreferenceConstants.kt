package com.kevshupp.kevmusicplayer.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized constant definitions and helper accessors for SharedPreferences across the application.
 */
object PreferenceConstants {
    // SharedPreferences file names
    const val PREFS_SETTINGS = "settings_prefs"
    const val PREFS_PLAYBACK = "playback_prefs"
    const val PREFS_EQUALIZER = "equalizer_prefs"
    const val PREFS_PLAYLISTS = "playlists_prefs"
    const val PREFS_BACKUP = "backup_prefs"
    const val PREFS_METRICS = "metrics_prefs"

    // Settings Keys
    const val KEY_IGNORE_TRANSIENT_AUDIO_FOCUS = "ignore_transient_audio_focus"
    const val KEY_PAUSE_ON_HEADPHONE_UNPLUG = "pause_on_headphone_unplug"
    const val KEY_CROSSFADE_DURATION = "crossfade_duration"
    const val KEY_NORMALIZE_SOUND = "normalize_sound"
    const val KEY_BLUETOOTH_RESUME_ENABLED = "bluetooth_resume_enabled"
    const val KEY_BLUETOOTH_RESUME_ALL = "bluetooth_resume_all"
    const val KEY_BLUETOOTH_RESUME_DEVICES = "bluetooth_resume_devices"
    const val KEY_APP_THEME = "app_theme"
    const val KEY_AMOLED_MODE = "amoled_mode"
    const val KEY_DYNAMIC_COLORS = "dynamic_colors"
    const val KEY_PRIMARY_ACCENT_COLOR = "primary_accent_color"
    const val KEY_ACCENT_COLOR = "accent_color"
    const val KEY_AUTO_DOWNLOAD_ARTIST_IMAGES = "auto_download_artist_images"
    const val KEY_AUTO_DOWNLOAD_LYRICS = "auto_download_lyrics"

    // Cloud Sync Keys
    const val KEY_CLOUD_AUTO_SYNC_MODE = "cloud_auto_sync_mode" // "realtime", "on_exit", "daily", "manual"
    const val KEY_CLOUD_SYNC_WIFI_ONLY = "cloud_sync_wifi_only"
    const val KEY_CLOUD_SYNC_INCLUDE_PLAYLISTS = "cloud_sync_include_playlists"
    const val KEY_CLOUD_SYNC_INCLUDE_LYRICS = "cloud_sync_include_lyrics"
    const val KEY_CLOUD_SYNC_INCLUDE_STATS = "cloud_sync_include_stats"
    const val KEY_CLOUD_SYNC_INCLUDE_SETTINGS = "cloud_sync_include_settings"

    // Playback Keys
    const val KEY_LAST_SHUFFLE_ENABLED = "last_shuffle_enabled"
    const val KEY_LAST_SONG_ID = "last_song_id"
    const val KEY_LAST_POSITION = "last_position"
    const val KEY_LAST_ACTIVE_INDEX = "last_active_index"
    const val KEY_LAST_QUEUE_IDS = "last_queue_ids"
    const val KEY_AUDIO_SESSION_ID = "audio_session_id"
    const val KEY_REPEAT_MODE = "repeat_mode"

    // Equalizer Keys
    const val KEY_EQ_ENABLED = "eq_enabled"
    const val KEY_EQ_BANDS = "eq_bands"
    const val KEY_BB_ENABLED = "bb_enabled"
    const val KEY_BB_STRENGTH = "bb_strength"
    const val KEY_VIRT_ENABLED = "virt_enabled"
    const val KEY_VIRT_STRENGTH = "virt_strength"

    // Helper functions
    fun getSettingsPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
    }

    fun getPlaybackPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
    }

    fun getEqualizerPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_EQUALIZER, Context.MODE_PRIVATE)
    }

    fun getPlaylistsPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_PLAYLISTS, Context.MODE_PRIVATE)
    }
}
