package com.kevshupp.kevmusicplayer.data.cast

import android.content.Context
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import com.kevshupp.kevmusicplayer.data.telegram.OnDemandTracks
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
    private val onLost: (String) -> Unit,
    /** La tele cambió su modo de reproducción (botones de repetir o aleatorio): modo anterior y nuevo. */
    private val onPlayMode: (String, String) -> Unit = { _, _ -> }
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

    /** 0 no repetir, 1 repetir una (`Player.REPEAT_MODE_ONE`), 2 repetir todo. Lo fija el reproductor del teléfono. */
    @Volatile var repeatMode = 0

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

    /**
     * La cola del teléfono cambió (añadir, quitar, mover) con la sesión abierta: se cambian las siguientes sin
     * tocar la que suena. [songs] empieza en la canción actual; si no coincide con la de la TV se ignora.
     */
    fun syncQueue(songs: List<AudioFile>) {
        scope.launch {
            commands.withLock {
                val current = queue.getOrNull(index) ?: return@withLock
                if (songs.firstOrNull()?.id != current.id) return@withLock
                queue = queue.take(index) + songs
                runCatching { preloadNext() }
            }
        }
    }

    /** Si el dispositivo rechaza una canción (formato), pasa a la siguiente, hasta 3 seguidas. */
    private suspend fun playIndex(startMs: Long, attempt: Int = 0) {
        val song = queue.getOrNull(index) ?: return finish()
        changing = true
        preparedNext = null
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
        val path = if (OnDemandTracks.isRemoteId(song.id)) {
            // Bajo demanda: se pide al bot (mismo candado y descarga compartida que el reproductor) y luego se sirve el archivo
            OnDemandTracks.ensureFile(context, OnDemandTracks.deezerIdOf(song.id)).absolutePath
        } else {
            getPhysicalPath(context, song.id, song.uriString) ?: error("Archivo no encontrado")
        }
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
        runCatching { preloadNext() }
        // Una vez por sesión: qué permite la tele (si "Next" no está en la lista, su botón de siguiente no puede funcionar)
        if (!capabilitiesLogged) {
            capabilitiesLogged = true
            delay(1500)
            TelemetryLogger.logInfo(
                context, "Cast",
                "${renderer.name} acciones permitidas: ${DlnaClient.transportActions(renderer)} · capacidades: ${DlnaClient.deviceCapabilities(renderer)}"
            )
        }
    }

    private var capabilitiesLogged = false

    /** Índice de la canción que sigue a la actual según el modo de repetición, o null si no hay (o se repite la misma). */
    private fun nextIndex(): Int? = when {
        repeatMode == 1 -> null
        index + 1 < queue.size -> index + 1
        repeatMode == 2 && queue.size > 1 -> 0
        else -> null
    }

    /** Índice al que se pasa al terminar o al pulsar siguiente; fuera de rango si se acabó la cola. */
    private fun advancedIndex(): Int = when {
        repeatMode == 1 -> index
        index + 1 < queue.size -> index + 1
        repeatMode == 2 -> 0
        else -> queue.size
    }

    /** Deja preparada en la tele la canción siguiente (si ya está lista en el teléfono; las bajo demanda se piden al llegarles el turno). */
    private fun preloadNext() {
        val i = nextIndex() ?: return
        val song = queue[i]
        // Ya preparada para esta canción (la cola se sincroniza justo tras empezar): no repetir la orden
        val key = (queue.getOrNull(index)?.id ?: return) to song.id
        if (preparedNext == key) return
        val path = if (OnDemandTracks.isRemoteId(song.id)) OnDemandTracks.readyFile(context, OnDemandTracks.deezerIdOf(song.id))?.absolutePath
            else getPhysicalPath(context, song.id, song.uriString)
        path ?: return
        val mime = CastMediaServer.mimeOf(path) ?: return
        val url = "$baseUrl/song/${song.id}.${path.substringAfterLast('.')}"
        try {
            DlnaClient.setNextUri(renderer, url, DlnaClient.didl(song.title, song.artist, song.album, url, mime, "$baseUrl/art/${song.id}.jpg", song.duration))
            preparedNext = key
            TelemetryLogger.logInfo(context, "Cast", "${renderer.name} aceptó la siguiente canción: ${song.title}")
        } catch (e: Exception) {
            if (!nextUriRejectedLogged) {
                nextUriRejectedLogged = true
                TelemetryLogger.logInfo(context, "Cast", "${renderer.name} no aceptó la siguiente canción preparada: ${e.message}")
            }
        }
    }

    private var nextUriRejectedLogged = false
    /** (canción actual, siguiente) ya enviada a la tele; se borra al cambiar de canción porque SetAVTransportURI la descarta. */
    @Volatile private var preparedNext: Pair<Long, Long>? = null
    @Volatile private var lastPlayMode: String? = null

    /** Sigue los botones de repetir y aleatorio de la tele: solo avisa de cambios, la primera lectura es la referencia. */
    private fun checkPlayMode() {
        val mode = DlnaClient.playMode(renderer) ?: return
        val previous = lastPlayMode
        lastPlayMode = mode
        if (previous == null) {
            TelemetryLogger.logInfo(context, "Cast", "${renderer.name} modo de reproducción inicial: $mode")
        } else if (mode != previous) {
            TelemetryLogger.logInfo(context, "Cast", "${renderer.name} cambió el modo de reproducción: $previous → $mode")
            onPlayMode(previous, mode)
        }
    }

    /** El teléfono cambió repetir o aleatorio: se refleja en los iconos de la tele (sin tomarlo luego por un cambio suyo). */
    fun pushPlayMode(mode: String) = command {
        DlnaClient.setPlayMode(renderer, mode)
        lastPlayMode = mode
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
                val (position, trackUri) = DlnaClient.positionInfo(renderer)
                failures = 0
                onPoll(state, position, trackUri)
                if (++polls % 3 == 0) refreshVolume()
                if (polls % 2 == 0) checkPlayMode()
            } catch (e: Exception) {
                if (++failures >= 6) {
                    TelemetryLogger.logInfo(context, "Cast", "DLNA sin respuesta: ${e.message}")
                    onLost("Se perdió la conexión con ${renderer.name}")
                    return
                }
            }
        }
    }

    private suspend fun onPoll(state: String, position: Long, trackUri: String?) {
        val song = queue.getOrNull(index) ?: return
        // La tele cambió de canción sola (botón "siguiente" del mando o paso a la canción preparada): se sigue su cambio.
        // Justo tras empezar una canción algunas teles aún informan de la anterior, así que se espera unos segundos.
        val playingId = trackUri?.let { Regex("/song/(-?\\d+)\\.").find(it)?.groupValues?.get(1)?.toLongOrNull() }
        if (state == "PLAYING" && playingId != null && playingId != song.id && System.currentTimeMillis() - startedAt > TRACK_CHANGE_GRACE_MS) {
            val found = queue.indexOfFirst { it.id == playingId }
            if (found >= 0) {
                TelemetryLogger.logInfo(context, "Cast", "${renderer.name} cambió de canción por su cuenta: ${queue[found].title}")
                commands.withLock {
                    index = found
                    startedAt = System.currentTimeMillis()
                    sawPlaying = true
                    positionMs = 0L
                    error = null
                    val now = queue[found]
                    publish(now)
                    onSongChanged(now.id)
                    preparedNext = null
                    runCatching { preloadNext() }
                }
                return
            }
        }
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
                index = advancedIndex()
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
        val target = if (repeatMode == 1) (if (index + 1 < queue.size) index + 1 else if (queue.size > 1) 0 else -1)
            else advancedIndex()
        if (target in queue.indices) {
            index = target
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
        const val TRACK_CHANGE_GRACE_MS = 4_000L
    }
}
