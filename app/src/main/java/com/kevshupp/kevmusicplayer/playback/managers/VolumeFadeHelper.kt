package com.kevshupp.kevmusicplayer.playback.managers

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kevshupp.kevmusicplayer.data.PreferenceConstants
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Manages audio volume fading, crossfades between tracks, manual skip transitions, and playback watchdogs.
 */
class VolumeFadeHelper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val getReplayGainFactor: () -> Float
) {
    var isFadingIn: Boolean = false
        private set

    private var fadeJob: Job? = null
    private var fadeInJob: Job? = null
    private var manualSkipJob: Job? = null
    private var watchdogJob: Job? = null

    fun startFadeCheckLoop(player: ExoPlayer) {
        fadeJob?.cancel()
        val settingsPrefs = PreferenceConstants.getSettingsPrefs(context)
        val initialCrossfade = settingsPrefs.getInt(PreferenceConstants.KEY_CROSSFADE_DURATION, 0)
        val currentReplayGainFactor = getReplayGainFactor()

        if (initialCrossfade <= 0) {
            if (abs(player.volume - currentReplayGainFactor) > 0.02f && !isFadingIn) {
                player.volume = currentReplayGainFactor
            }
            return
        }

        fadeJob = scope.launch {
            var lastSkippedMediaItem: MediaItem? = null
            while (true) {
                val crossfadeSeconds = settingsPrefs.getInt(PreferenceConstants.KEY_CROSSFADE_DURATION, 0)
                val gainFactor = getReplayGainFactor()

                if (crossfadeSeconds <= 0) {
                    if (abs(player.volume - gainFactor) > 0.02f && !isFadingIn) {
                        player.volume = gainFactor
                    }
                    break
                }

                if (!player.isPlaying || isFadingIn) {
                    delay(1000)
                    continue
                }

                val duration = player.duration
                val position = player.currentPosition
                val currentItem = player.currentMediaItem
                if (duration > 0) {
                    val remainingMs = duration - position
                    val crossfadeMs = crossfadeSeconds * 1000L

                    if (remainingMs <= crossfadeMs) {
                        val progress = remainingMs.toFloat() / crossfadeMs
                        val targetVol = progress.coerceIn(0f, 1f) * gainFactor
                        player.volume = targetVol

                        if (remainingMs <= 200L && player.hasNextMediaItem() && currentItem != lastSkippedMediaItem) {
                            TelemetryLogger.logInfo(
                                context,
                                "Playback_Volume",
                                "Remaining time $remainingMs <= 200ms. Transitioning track with crossfade: current=$currentItem"
                            )
                            lastSkippedMediaItem = currentItem
                            player.seekToNextMediaItem()
                            fadeNewTrackIn(player, crossfadeMs)
                        }
                        delay(100)
                    } else {
                        if (abs(player.volume - gainFactor) > 0.02f && !isFadingIn) {
                            player.volume = gainFactor
                        }
                        val timeUntilCrossfade = remainingMs - crossfadeMs
                        val sleepMs = timeUntilCrossfade.coerceIn(150L, 1000L)
                        delay(sleepMs)
                    }
                } else {
                    delay(1000)
                }
            }
        }
    }

    fun fadeNewTrackIn(player: ExoPlayer, crossfadeMs: Long) {
        fadeInJob?.cancel()
        isFadingIn = true
        val gainFactor = getReplayGainFactor()

        fadeInJob = scope.launch {
            try {
                TelemetryLogger.logInfo(
                    context,
                    "Playback_Volume",
                    "Starting fade-in for new track over ${crossfadeMs}ms. Target volume: $gainFactor"
                )
                player.volume = 0f
                val steps = 20
                val delayMs = (crossfadeMs / steps).coerceAtLeast(10L)
                for (i in 1..steps) {
                    delay(delayMs)
                    if (!player.isPlaying) {
                        TelemetryLogger.logInfo(
                            context,
                            "Playback_Volume",
                            "Fade-in interrupted: player is not playing"
                        )
                        break
                    }
                    player.volume = (i.toFloat() / steps) * gainFactor
                }
                player.volume = gainFactor
                TelemetryLogger.logInfo(
                    context,
                    "Playback_Volume",
                    "Fade-in completed. Volume set to: $gainFactor"
                )
            } finally {
                isFadingIn = false
            }
        }
    }

    fun performManualSkip(player: ExoPlayer, next: Boolean) {
        fadeInJob?.cancel()
        manualSkipJob?.cancel()
        val gainFactor = getReplayGainFactor()

        manualSkipJob = scope.launch {
            TelemetryLogger.logInfo(
                context,
                "Playback_Volume",
                "Performing instant manual skip (next=$next)"
            )
            if (next) {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                        player.play()
                    }
                }
            } else {
                if (player.hasPreviousMediaItem()) {
                    player.seekToPreviousMediaItem()
                    if (player.playbackState == Player.STATE_IDLE) {
                        player.prepare()
                        player.play()
                    }
                }
            }
            player.volume = gainFactor
            TelemetryLogger.logInfo(
                context,
                "Playback_Volume",
                "Manual skip completed. Volume set to: $gainFactor"
            )
        }
    }

    fun startPlaybackWatchdogLoop(player: ExoPlayer) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var lastRecordedPos = -1L
            var stalledTicks = 0

            while (true) {
                delay(2000)
                try {
                    if (player.isPlaying && player.playWhenReady && player.playbackState == Player.STATE_READY) {
                        val currentPos = player.currentPosition
                        val duration = player.duration
                        // Detect if playback position is frozen while player reports isPlaying=true (Bluetooth sink stall)
                        if (currentPos == lastRecordedPos && (duration <= 0 || currentPos < duration - 1500L)) {
                            stalledTicks++
                            if (stalledTicks >= 2) { // 4 seconds without progress while isPlaying is true
                                TelemetryLogger.logWarn(
                                    context,
                                    "Playback_Watchdog",
                                    "Detected stalled playback clock at ${currentPos}ms (AudioTrack silent buffer stall). Auto-recovering ExoPlayer pipeline..."
                                )
                                stalledTicks = 0
                                lastRecordedPos = -1L
                                player.seekTo(currentPos)
                                player.prepare()
                                player.play()
                            }
                        } else {
                            stalledTicks = 0
                            lastRecordedPos = currentPos
                        }
                    } else {
                        stalledTicks = 0
                        lastRecordedPos = -1L
                    }
                } catch (e: Exception) {
                    // Ignore watchdog loop exceptions
                }
            }
        }
    }

    fun cancelAll() {
        fadeJob?.cancel()
        fadeInJob?.cancel()
        manualSkipJob?.cancel()
        watchdogJob?.cancel()
    }
}
