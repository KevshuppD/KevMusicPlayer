@file:Suppress("DEPRECATION")

package com.kevshupp.kevmusicplayer.playback.managers

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.kevshupp.kevmusicplayer.data.PreferenceConstants
import com.kevshupp.kevmusicplayer.data.TelemetryLogger

/**
 * Manages system audio effects (Equalizer, BassBoost, Virtualizer, LoudnessEnhancer).
 */
class AudioEffectsManager(private val context: Context) {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    var currentAudioSessionId: Int = 0
        private set

    fun setupAudioEffects(audioSessionId: Int) {
        if (audioSessionId == 0) return
        try {
            if (currentAudioSessionId != audioSessionId) {
                TelemetryLogger.logInfo(
                    context,
                    "AudioEffects_Setup",
                    "Releasing old effects because session ID changed from $currentAudioSessionId to $audioSessionId"
                )
                releaseEffects()
                currentAudioSessionId = audioSessionId
            }

            val prefs = PreferenceConstants.getEqualizerPrefs(context)

            // Equalizer
            try {
                val eqEnabled = prefs.getBoolean(PreferenceConstants.KEY_EQ_ENABLED, false)
                if (eqEnabled) {
                    val isNew = equalizer == null
                    if (equalizer == null) {
                        equalizer = Equalizer(0, audioSessionId)
                    }
                    equalizer?.enabled = true

                    val eq = equalizer
                    if (eq != null) {
                        val bandsStr = prefs.getString(PreferenceConstants.KEY_EQ_BANDS, null) ?: "0,0,0,0,0"
                        val bands = bandsStr.split(",").mapNotNull { it.toIntOrNull() }
                        val numBands = eq.numberOfBands.toInt()
                        for (i in 0 until minOf(numBands, bands.size)) {
                            try {
                                val level = bands[i].coerceIn(eq.bandLevelRange[0].toInt(), eq.bandLevelRange[1].toInt())
                                eq.setBandLevel(i.toShort(), level.toShort())
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        TelemetryLogger.logInfo(
                            context,
                            "AudioEffects_EQ",
                            "Equalizer ${if (isNew) "created" else "updated"}: bands=$bandsStr, session=$audioSessionId"
                        )
                    }
                } else {
                    if (equalizer != null) {
                        TelemetryLogger.logInfo(context, "AudioEffects_EQ", "Disabling and releasing Equalizer")
                        try { equalizer?.release() } catch (e: Exception) {}
                        equalizer = null
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                TelemetryLogger.logError(context, "AudioEffects_EQ", "Failed to setup Equalizer", e)
                try { equalizer?.release() } catch (ex: Exception) {}
                equalizer = null
            }

            // Bass Boost
            try {
                val bbEnabled = prefs.getBoolean(PreferenceConstants.KEY_BB_ENABLED, false)
                if (bbEnabled) {
                    val bbStrength = prefs.getInt(PreferenceConstants.KEY_BB_STRENGTH, 0).toShort()
                    val isNew = bassBoost == null
                    if (bassBoost == null) {
                        bassBoost = BassBoost(0, audioSessionId)
                    }
                    bassBoost?.enabled = true
                    bassBoost?.setStrength(bbStrength)
                    TelemetryLogger.logInfo(
                        context,
                        "AudioEffects_BB",
                        "BassBoost ${if (isNew) "created" else "updated"}: strength=$bbStrength, session=$audioSessionId"
                    )
                } else {
                    if (bassBoost != null) {
                        TelemetryLogger.logInfo(context, "AudioEffects_BB", "Disabling and releasing BassBoost")
                        try { bassBoost?.release() } catch (e: Exception) {}
                        bassBoost = null
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                TelemetryLogger.logError(context, "AudioEffects_BB", "Failed to setup Bass Boost", e)
                try { bassBoost?.release() } catch (ex: Exception) {}
                bassBoost = null
            }

            // Virtualizer
            try {
                val virtEnabled = prefs.getBoolean(PreferenceConstants.KEY_VIRT_ENABLED, false)
                if (virtEnabled) {
                    val virtStrength = prefs.getInt(PreferenceConstants.KEY_VIRT_STRENGTH, 0).toShort()
                    val isNew = virtualizer == null
                    if (virtualizer == null) {
                        virtualizer = Virtualizer(0, audioSessionId)
                    }
                    virtualizer?.enabled = true
                    virtualizer?.setStrength(virtStrength)
                    TelemetryLogger.logInfo(
                        context,
                        "AudioEffects_Virt",
                        "Virtualizer ${if (isNew) "created" else "updated"}: strength=$virtStrength, session=$audioSessionId"
                    )
                } else {
                    if (virtualizer != null) {
                        TelemetryLogger.logInfo(context, "AudioEffects_Virt", "Disabling and releasing Virtualizer")
                        try { virtualizer?.release() } catch (e: Exception) {}
                        virtualizer = null
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                TelemetryLogger.logError(context, "AudioEffects_Virt", "Failed to setup Virtualizer", e)
                try { virtualizer?.release() } catch (ex: Exception) {}
                virtualizer = null
            }

            // Loudness Normalization
            try {
                val settingsPrefs = PreferenceConstants.getSettingsPrefs(context)
                val normalizeEnabled = settingsPrefs.getBoolean(PreferenceConstants.KEY_NORMALIZE_SOUND, false)
                if (normalizeEnabled) {
                    val isNew = loudnessEnhancer == null
                    if (loudnessEnhancer == null) {
                        loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                    }
                    loudnessEnhancer?.enabled = true
                    try {
                        loudnessEnhancer?.setTargetGain(250) // Safe 250 mB (+2.5 dB)
                        TelemetryLogger.logInfo(
                            context,
                            "AudioEffects_Loudness",
                            "LoudnessEnhancer ${if (isNew) "created" else "updated"}: targetGain=250, session=$audioSessionId"
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Log.w("PlaybackService", "Failed to set target gain on LoudnessEnhancer: ${e.message}")
                    }
                } else {
                    if (loudnessEnhancer != null) {
                        TelemetryLogger.logInfo(context, "AudioEffects_Loudness", "Disabling and releasing LoudnessEnhancer")
                        try { loudnessEnhancer?.release() } catch (e: Exception) {}
                        loudnessEnhancer = null
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                TelemetryLogger.logError(context, "AudioEffects_Loudness", "Failed to setup LoudnessEnhancer", e)
                try { loudnessEnhancer?.release() } catch (ex: Exception) {}
                loudnessEnhancer = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            TelemetryLogger.logError(context, "AudioEffects_Setup", "General failure in setupAudioEffects", e)
        }
    }

    fun releaseEffects() {
        try { equalizer?.release() } catch (e: Exception) {}
        equalizer = null
        try { bassBoost?.release() } catch (e: Exception) {}
        bassBoost = null
        try { virtualizer?.release() } catch (e: Exception) {}
        virtualizer = null
        try { loudnessEnhancer?.release() } catch (e: Exception) {}
        loudnessEnhancer = null
    }
}
