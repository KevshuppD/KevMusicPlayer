package com.kevshupp.kevmusicplayer.data.cast

import android.content.Context
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import com.kevshupp.kevmusicplayer.playback.getPhysicalPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/**
 * Transmisión a un dispositivo DLNA. A diferencia de Chromecast, DLNA no tiene cola: el teléfono la lleva, consulta
 * el estado cada segundo y envía la siguiente canción cuando la actual termina.
 */
class DlnaSession(
    private val context: Context,
    private val renderer: DlnaRenderer,
    private val baseUrl: String,
    /** Título, si suena y error. */
    private val onStatus: (String, Boolean, String?) -> Unit,
    private val onSongChanged: (Long) -> Unit,
    private val onLost: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private var queue: List<AudioFile> = emptyList()
    private var index = 0
    private var pollJob: Job? = null
    /** Evita tomar por "terminó" el STOPPED que hay mientras se cambia de canción. */
    @Volatile private var changing = false
    @Volatile private var startedAt = 0L
    @Volatile private var sawPlaying = false
    @Volatile private var playing = false
    @Volatile private var error: String? = null

    @Volatile var positionMs = 0L
        private set
    /** De 0 a 1: se guarda para no ir a la red desde el hilo principal. */
    @Volatile var volume = 0f
        private set
    @Volatile private var volumeChangedAt = 0L
    private var volumeJob: Job? = null

    val name get() = renderer.name

    fun load(songs: List<AudioFile>, startMs: Long) {
        scope.launch {
            commands.withLock {
                queue = songs
                index = 0
                DlnaClient.volume(renderer)?.let { volume = it / 100f }
                playIndex(startMs)
            }
        }
        if (pollJob == null) pollJob = scope.launch { pollLoop() }
    }

    /** Si el dispositivo rechaza una canción (formato), pasa a la siguiente, hasta 3 seguidas. */
    private suspend fun playIndex(startMs: Long, attempt: Int = 0) {
        val song = queue.getOrNull(index) ?: return finish()
        changing = true
        sawPlaying = false
        positionMs = startMs
        publish(song)
        val failure = runCatching { start(song, startMs) }.exceptionOrNull()
        startedAt = System.currentTimeMillis()
        changing = false
        if (failure == null) {
            error = null
            onSongChanged(song.id)
            publish(song)
            return
        }
        TelemetryLogger.logInfo(context, "Cast", "DLNA rechazó ${song.title}: ${failure.message}")
        error = "${renderer.name} no pudo reproducir «${song.title}»"
        if (attempt < 2 && index + 1 < queue.size) {
            index++
            playIndex(0L, attempt + 1)
        } else {
            playing = false
            publish(song)
        }
    }

    private suspend fun start(song: AudioFile, startMs: Long) {
        val path = getPhysicalPath(context, song.id, song.uriString) ?: error("Archivo no encontrado")
        val mime = CastMediaServer.mimeOf(path) ?: error("Formato no admitido")
        val url = "$baseUrl/song/${song.id}.${path.substringAfterLast('.')}"
        // Muchas teles no aceptan otra URI mientras reproducen
        runCatching { DlnaClient.stop(renderer) }
        DlnaClient.setUri(renderer, url, DlnaClient.didl(song.title, song.artist, song.album, url, mime, "$baseUrl/art/${song.id}.jpg", song.duration))
        retrying { DlnaClient.play(renderer) }
        playing = true
        if (startMs > 1000) {
            // Solo se puede saltar cuando ya empezó
            runCatching { waitUntilPlaying(); DlnaClient.seek(renderer, startMs) }
        }
    }

    private suspend fun waitUntilPlaying() {
        repeat(20) {
            if (runCatching { DlnaClient.transportState(renderer) }.getOrNull() == "PLAYING") return
            delay(250)
        }
    }

    /** Justo tras cambiar de canción el dispositivo puede responder "transición no disponible" (701). */
    private suspend fun retrying(block: () -> Unit) {
        repeat(4) {
            try {
                block()
                return
            } catch (e: DlnaClient.SoapException) {
                delay(500)
            }
        }
        block()
    }

    private suspend fun pollLoop() {
        var failures = 0
        var polls = 0
        while (scope.isActive) {
            delay(1000)
            if (changing || commands.isLocked) continue
            try {
                val state = DlnaClient.transportState(renderer)
                val position = DlnaClient.positionMs(renderer)
                failures = 0
                onPoll(state, position)
                if (++polls % 3 == 0) refreshVolume()
            } catch (e: Exception) {
                if (++failures >= 6) {
                    TelemetryLogger.logInfo(context, "Cast", "DLNA sin respuesta: ${e.message}")
                    onLost("Se perdió la conexión con ${renderer.name}")
                    return
                }
            }
        }
    }

    private suspend fun onPoll(state: String, position: Long) {
        val song = queue.getOrNull(index) ?: return
        if (position > 0) positionMs = position
        val stopped = state == "STOPPED" || state == "NO_MEDIA_PRESENT"
        when {
            state == "PLAYING" -> {
                sawPlaying = true
                setPlaying(true, song)
            }
            state == "PAUSED_PLAYBACK" -> setPlaying(false, song)
            // Terminó sola: parar desde el mando de la tele lejos del final no debe saltar a otra canción
            stopped && sawPlaying && positionMs >= song.duration - END_MARGIN_MS -> commands.withLock {
                index++
                playIndex(0L)
            }
            stopped && sawPlaying -> setPlaying(false, song)
            stopped && System.currentTimeMillis() - startedAt > START_TIMEOUT_MS -> commands.withLock {
                error = "${renderer.name} no pudo reproducir «${song.title}»"
                index++
                playIndex(0L)
            }
        }
    }

    private fun setPlaying(value: Boolean, song: AudioFile) {
        if (playing == value && error == null) return
        playing = value
        if (value) error = null
        publish(song)
    }

    private fun finish() {
        playing = false
        queue.getOrNull(queue.lastIndex)?.let { publish(it) }
    }

    private fun publish(song: AudioFile) = onStatus(song.title, playing, error)

    private fun command(block: suspend () -> Unit) {
        scope.launch {
            commands.withLock {
                runCatching { block() }.onFailure { TelemetryLogger.logInfo(context, "Cast", "Orden DLNA falló: ${it.message}") }
            }
        }
    }

    fun togglePlay() = command {
        if (playing) {
            DlnaClient.pause(renderer)
            playing = false
        } else {
            DlnaClient.play(renderer)
            playing = true
        }
        queue.getOrNull(index)?.let { publish(it) }
    }

    fun next() = command {
        if (index + 1 < queue.size) {
            index++
            playIndex(0L)
        }
    }

    fun previous() = command {
        if (index > 0) {
            index--
            playIndex(0L)
        }
    }

    fun seek(ms: Long) = command {
        DlnaClient.seek(renderer, ms)
        positionMs = ms
    }

    /**
     * Arrastrar la barra pide decenas de cambios por segundo y la tele tarda en responder cada uno: se envía solo el
     * último valor, uno a la vez, para que no se acumulen ni lleguen desordenados.
     */
    fun changeVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        volumeChangedAt = System.currentTimeMillis()
        if (volumeJob?.isActive == true) return
        volumeJob = scope.launch {
            var sent = -1
            while (true) {
                val wanted = (volume * 100).roundToInt()
                if (wanted == sent) break
                runCatching { DlnaClient.setVolume(renderer, wanted) }
                sent = wanted
            }
        }
    }

    /** Sigue los cambios hechos con el mando, salvo justo después de que el usuario lo mueva aquí. */
    private fun refreshVolume() {
        if (volumeJob?.isActive == true || System.currentTimeMillis() - volumeChangedAt < 3000) return
        DlnaClient.volume(renderer)?.let { if (volumeJob?.isActive != true) volume = it / 100f }
    }

    /** Para el dispositivo y luego llama a [onStopped], que puede cerrar el servidor de audio. */
    fun stop(onStopped: () -> Unit) {
        pollJob?.cancel()
        scope.launch {
            runCatching { DlnaClient.stop(renderer) }
            onStopped()
            scope.cancel()
        }
    }

    private companion object {
        const val END_MARGIN_MS = 8000L
        const val START_TIMEOUT_MS = 20_000L
    }
}
