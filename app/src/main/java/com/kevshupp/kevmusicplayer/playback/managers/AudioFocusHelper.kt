package com.kevshupp.kevmusicplayer.playback.managers

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
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
                    player.play()
                    playOnFocusGain = false
                }
                if (!isFadingIn()) {
                    player.volume = currentReplayGainFactor
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                player.pause()
                playOnFocusGain = false
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // If call is active, always pause for phone call privacy
                if (isCallActive) {
                    if (player.isPlaying) {
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
                    player.volume = 0.35f * currentReplayGainFactor
                    playOnFocusGain = true
                } else {
                    if (player.isPlaying) {
                        playOnFocusGain = true
                        player.pause()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                player.volume = 0.25f * currentReplayGainFactor
            }
        }
    }

    fun requestAudioFocus(): Boolean {
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
}
