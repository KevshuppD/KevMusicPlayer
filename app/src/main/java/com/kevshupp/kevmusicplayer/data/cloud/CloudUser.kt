package com.kevshupp.kevmusicplayer.data.cloud

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Represents the authenticated user profile for cloud backup and sync.
 */
@Immutable
@Serializable
data class CloudUser(
    val uid: String,
    val email: String,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val lastSyncTimestamp: Long = 0L,
    val isAutoSyncEnabled: Boolean = true,
    val autoSyncMode: String = "realtime", // "realtime", "on_exit", "daily", "manual"
    val syncWifiOnly: Boolean = false,
    val includePlaylists: Boolean = true,
    val includeLyrics: Boolean = true,
    val includeStats: Boolean = true,
    val includeSettings: Boolean = true
) {
    val isAuthenticated: Boolean get() = uid.isNotBlank()
}

enum class AutoSyncTrigger {
    REALTIME,
    ON_EXIT,
    PERIODIC
}
