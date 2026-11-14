package com.kevshupp.kevmusicplayer.data

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Salidas de audio del teléfono (altavoz, Bluetooth, cable, USB) para el diálogo Dispositivos, como el selector
 * de Spotify. Elegir una fija la salida del reproductor (`ExoPlayer.setPreferredAudioDevice`, que aplica
 * PlaybackService por [applyPreferred]); sin elección manda Android (lo último conectado).
 */
object AudioOutputs {
    enum class Kind { SPEAKER, BLUETOOTH, WIRED, USB }

    data class Output(val id: Int, val name: String, val kind: Kind, val current: Boolean)

    private val _outputs = MutableStateFlow<List<Output>>(emptyList())
    val outputs: StateFlow<List<Output>> = _outputs

    /** Lo asigna PlaybackService mientras vive; `null` = que decida Android. Se llama en el hilo principal. */
    @Volatile var applyPreferred: ((AudioDeviceInfo?) -> Unit)? = null

    private var preferredId: Int? = null
    private var audioManager: AudioManager? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            // Al conectar unos audífonos se espera que suene por ellos: la elección anterior deja de valer
            if (added?.any { kindOf(it) != null && kindOf(it) != Kind.SPEAKER } == true && preferredId != null) {
                preferredId = null
                applyPreferred?.invoke(null)
            }
            refresh()
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
            if (removed?.any { it.id == preferredId } == true) {
                preferredId = null
                applyPreferred?.invoke(null)
            }
            refresh()
        }
    }

    fun start(context: Context) {
        if (audioManager != null) { refresh(); return }
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    /** Vuelve a aplicar la salida elegida a un reproductor recién creado. */
    fun reapply() {
        val id = preferredId ?: return
        devices().firstOrNull { it.id == id }?.let { applyPreferred?.invoke(it) }
    }

    fun select(id: Int) {
        val device = devices().firstOrNull { it.id == id } ?: return
        preferredId = id
        applyPreferred?.invoke(device)
        refresh()
    }

    private fun devices(): List<AudioDeviceInfo> =
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.filter { kindOf(it) != null }.orEmpty()

    private fun refresh() {
        val all = devices()
        // Sin elección, Android usa lo último conectado: Bluetooth, luego cable o USB, y si no el altavoz
        val currentId = preferredId?.takeIf { id -> all.any { it.id == id } }
            ?: (all.firstOrNull { kindOf(it) == Kind.BLUETOOTH }
                ?: all.firstOrNull { kindOf(it) == Kind.WIRED }
                ?: all.firstOrNull { kindOf(it) == Kind.USB }
                ?: all.firstOrNull())?.id
        _outputs.value = all.map { d ->
            val kind = kindOf(d)!!
            val name = if (kind == Kind.SPEAKER) "" else d.productName?.toString()?.trim().orEmpty()
            Output(d.id, name, kind, d.id == currentId)
        }
            // Un mismo aparato puede salir dos veces (A2DP y LE): se deja uno, el que suena si es alguno
            .groupBy { it.kind to it.name }.map { (_, same) -> same.firstOrNull { it.current } ?: same.first() }
            .sortedBy { it.kind.ordinal }
    }

    private fun kindOf(d: AudioDeviceInfo): Kind? = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Kind.SPEAKER
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID -> Kind.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG -> Kind.WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> Kind.USB
        else -> null
    }
}
