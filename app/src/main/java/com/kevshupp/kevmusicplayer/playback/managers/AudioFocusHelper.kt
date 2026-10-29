package com.kevshupp.kevmusicplayer.playback.managers

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import com.kevshupp.kevmusicplayer.data.PreferenceConstants
import com.kevshupp.kevmusicplayer.data.TelemetryLogger

/**
 * Manages Audio Focus requests, abandonment, transient losses, and ducking for PlaybackService.
 */
class AudioFocusHelper(
    private val context: Context,
    private val playerProvider: () -> ExoPlayer?,
    private val getReplayGainFactor: () -> Float,
    private val isFadingIn: () -> Boolean
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    var playOnFocusGain: Boolean = false
        private set

    // 1 normally, lower while another app holds the focus and the music is only lowered. The crossfade loop
    // multiplies its target volume by this, otherwise it would bring the volume straight back up.
    var duckFactor: Float = 1f
        private set

    // TikTok, Reels, etc. take the focus for each video and release it when swiping to the next one: resuming
    // right away made the music play for a second between videos and pause again (~60 times in 4 minutes).
    // Resume only if the focus stays with us for RESUME_DELAY_MS.
    private val handler = Handler(Looper.getMainLooper())
    private var pendingResume: Runnable? = null

    private fun cancelPendingResume(): Boolean {
        val pending = pendingResume ?: return false
        handler.removeCallbacks(pending)
        pendingResume = null
        return true
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val player = playerProvider() ?: return@OnAudioFocusChangeListener
        val isCallActive = audioManager.mode == AudioManager.MODE_IN_CALL ||
                audioManager.mode == AudioManager.MODE_IN_COMMUNICATION

        val focusChangeStr = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT (isCallActive=$isCallActive)"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
            else -> "UNKNOWN ($focusChange)"
        }
        TelemetryLogger.logInfo(
            context,
            "Playback_AudioFocus",
            "AudioFocus change: $focusChangeStr, isPlaying: ${player.isPlaying}, playWhenReady: ${player.playWhenReady}, pos: ${player.currentPosition}"
        )

        val settingsPrefs = PreferenceConstants.getSettingsPrefs(context)
        val ignoreTransientFocus = settingsPrefs.getBoolean(PreferenceConstants.KEY_IGNORE_TRANSIENT_AUDIO_FOCUS, true)
        val currentReplayGainFactor = getReplayGainFactor()

        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (playOnFocusGain) {
                    cancelPendingResume()
                    val resume = Runnable {
                        pendingResume = null
                        if (playOnFocusGain) {
                            playerProvider()?.play()
                            playOnFocusGain = false
                        }
                    }
                    pendingResume = resume
                    handler.postDelayed(resume, RESUME_DELAY_MS)
                }
                duckFactor = 1f
                if (!isFadingIn()) {
                    player.volume = currentReplayGainFactor
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                cancelPendingResume()
                duckFactor = 1f
                player.pause()
                playOnFocusGain = false
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Lost again while waiting to resume: stay paused and wait for the next gain
                val wasWaiting = cancelPendingResume()
                // If call is active, always pause for phone call privacy
                if (isCallActive) {
                    if (wasWaiting || player.isPlaying) {
                        playOnFocusGain = true
                        player.pause()
                    }
                } else if (ignoreTransientFocus) {
                    // Lower volume slightly (ducking) instead of cutting off music completely
                    TelemetryLogger.logInfo(
                        context,
                        "Playback_AudioFocus",
                        "Transient focus loss ignored (ducking enabled). Keeping playback alive."
                    )
                    duckFactor = 0.35f
                    player.volume = duckFactor * currentReplayGainFactor
                    playOnFocusGain = true
                } else {
                    if (wasWaiting || player.isPlaying) {
                        playOnFocusGain = true
                        player.pause()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckFactor = 0.25f
                player.volume = duckFactor * currentReplayGainFactor
            }
        }
    }

    fun requestAudioFocus(): Boolean {
        // The user pressed play: forget any automatic resume still waiting
        cancelPendingResume()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioManager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    companion object {
        private const val RESUME_DELAY_MS = 1500L
    }
}
