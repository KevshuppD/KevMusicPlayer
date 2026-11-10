package com.kevshupp.kevmusic.desktop.local

import com.kevshupp.kevmusic.desktop.Library
import com.kevshupp.kevmusic.desktop.Lyrics
import com.kevshupp.kevmusic.desktop.PlayerState
import com.kevshupp.kevmusic.desktop.QueueItem
import com.kevshupp.kevmusic.desktop.QueueState
import com.kevshupp.kevmusic.desktop.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Modo local ("Sonar en este PC"): biblioteca de [LocalLibrary] y audio por [Mpv]. Publica los mismos
 * [PlayerState], [QueueState], [Lyrics] y [Library] que el teléfono, así la interfaz no distingue el origen.
 * La cola es propia; mpv solo reproduce la pista actual.
 */
class LocalPlayer(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<PlayerState?>(null)
    val state: StateFlow<PlayerState?> = _state
    private val _queue = MutableStateFlow(QueueState())
    val queue: StateFlow<QueueState> = _queue
    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics
    private val _library = MutableStateFlow<Library?>(null)
    val library: StateFlow<Library?> = _library
    /** (leídas, total) mientras escanea; null en reposo. */
    private val _scanning = MutableStateFlow<Pair<Int, Int>?>(null)
    val scanning: StateFlow<Pair<Int, Int>?> = _scanning
    /** Motivo si el audio no está disponible (sin libmpv). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var tracks: Map<Long, LocalTrack> = emptyMap()
    private var mpv: Mpv? = null
    private var order: MutableList<Long> = mutableListOf()
    /** Orden antes de activar el aleatorio, para volver a él. */
    private var unshuffled: List<Long>? = null
    private var index = -1
    private var repeat = 0
    private var volume = 80
    private val _volumeLevel = MutableStateFlow(volume)
    /** Volumen actual (0–100), también antes de reproducir nada. */
    val volumeLevel: StateFlow<Int> = _volumeLevel
    private var scanJob: Job? = null

    private val lock = Any()

    fun folders(): List<File> = Settings.localFolders.map(::File).ifEmpty { listOf(LocalLibrary.defaultFolder()) }

    /** Primera vez que se usa: muestra lo guardado y vuelve a escanear en segundo plano. */
    fun start() {
        if (_library.value == null) publishLibrary(LocalLibrary.cachedTracks())
        rescan()
    }

    fun rescan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch(Dispatchers.IO) {
            _scanning.value = 0 to 0
            try {
                publishLibrary(LocalLibrary.scan(folders()) { done, total -> _scanning.value = done to total })
            } finally {
                _scanning.value = null
            }
        }
    }

    private fun publishLibrary(list: List<LocalTrack>) {
        tracks = list.associateBy { it.song.id }
        _library.value = Library("Este PC", list.map { it.song }, emptyList()).also { it.warm() }
    }

    fun track(id: Long): LocalTrack? = tracks[id]

    private fun engine(): Mpv? {
        mpv?.let { return it }
        val m = Mpv.create()
        if (m == null) { _error.value = Mpv.unavailableReason; return null }
        _error.value = null
        m.setVolume(volume)
        m.listener = object : Mpv.Listener {
            override fun onPosition(seconds: Double) = update { copy(position = (seconds * 1000).toLong(), buffering = false) }
            override fun onDuration(seconds: Double) = update { copy(duration = (seconds * 1000).toLong()) }
            override fun onPause(paused: Boolean) = update { copy(playing = !paused) }
            override fun onEndOfFile() { scope.launch { advance(auto = true) } }
            override fun onError(message: String) { _error.value = message }
        }
        mpv = m
        return m
    }

    private inline fun update(f: PlayerState.() -> PlayerState) {
        _state.value = _state.value?.f()?.copy(receivedAt = System.currentTimeMillis())
    }

    // ---------- Comandos (mismos que RemoteClient) ----------

    fun playSongs(ids: List<Long>, startIndex: Int, shuffle: Boolean) = synchronized(lock) {
        val valid = ids.filter { it in tracks }
        if (valid.isEmpty()) return
        val start = ids.getOrNull(startIndex)?.takeIf { it in tracks } ?: valid.first()
        if (shuffle) {
            unshuffled = valid
            order = (listOf(start) + (valid - start).shuffled()).toMutableList()
            index = 0
        } else {
            unshuffled = null
            order = valid.toMutableList()
            index = order.indexOf(start).coerceAtLeast(0)
        }
        playCurrent()
    }

    fun enqueue(ids: List<Long>, playNext: Boolean) = synchronized(lock) {
        val valid = ids.filter { it in tracks }
        if (valid.isEmpty()) return
        if (order.isEmpty()) { order = valid.toMutableList(); index = 0; playCurrent(); return }
        if (playNext) order.addAll(index + 1, valid) else order.addAll(valid)
        unshuffled = unshuffled?.plus(valid)
        publishQueue()
    }

    fun toggle() {
        val st = _state.value
        if (st == null || index < 0) return
        engine()?.setPaused(st.playing)
        update { copy(playing = !playing) }
    }

    fun next() = scope.launch { advance(auto = false) }

    fun previous() = synchronized(lock) {
        if (index < 0) return
        // Como en el teléfono: pasados 3 s vuelve al inicio de la canción
        if ((_state.value?.positionAt(System.currentTimeMillis()) ?: 0) > 3000 || index == 0) seek(0)
        else { index--; playCurrent() }
    }

    fun seek(positionMs: Long) {
        engine()?.seek(positionMs / 1000.0)
        update { copy(position = positionMs) }
    }

    fun setVolume(value: Int) {
        volume = value.coerceIn(0, 100)
        _volumeLevel.value = volume
        engine()?.setVolume(volume)
        update { copy(volume = this@LocalPlayer.volume) }
    }

    fun setRepeat(mode: Int) { repeat = mode.coerceIn(0, 2); update { copy(repeat = this@LocalPlayer.repeat) } }
    fun cycleRepeat() = setRepeat((repeat + 1) % 3)

    fun toggleShuffle() = synchronized(lock) {
        if (order.isEmpty()) return
        val current = order.getOrNull(index)
        val original = unshuffled
        if (original == null) {
            unshuffled = order.toList()
            val rest = order.filterIndexed { i, _ -> i > index }.shuffled()
            order = (order.take(index + 1) + rest).toMutableList()
        } else {
            order = original.toMutableList()
            unshuffled = null
            index = current?.let { order.indexOf(it) }?.coerceAtLeast(0) ?: 0
        }
        publishQueue()
        update { copy(shuffle = unshuffled != null) }
    }

    fun jumpTo(i: Int) = synchronized(lock) { if (i in order.indices) { index = i; playCurrent() } }

    fun removeFromQueue(i: Int) = synchronized(lock) {
        if (i !in order.indices || i == index) return
        order.removeAt(i)
        if (i < index) index--
        publishQueue()
    }

    fun moveInQueue(from: Int, to: Int) = synchronized(lock) {
        if (from !in order.indices || to !in order.indices) return
        val current = order[index]
        order.add(to, order.removeAt(from))
        index = order.indexOf(current)
        publishQueue()
    }

    /** Pausa al pasar el audio al teléfono. */
    fun pause() {
        if (_state.value?.playing == true) { mpv?.setPaused(true); update { copy(playing = false) } }
    }

    fun close() { mpv?.close(); mpv = null }

    // ---------- Internos ----------

    private fun advance(auto: Boolean) = synchronized(lock) {
        if (order.isEmpty()) return
        when {
            auto && repeat == 1 -> playCurrent()
            index + 1 < order.size -> { index++; playCurrent() }
            repeat == 2 || !auto -> { index = 0; playCurrent(paused = auto && repeat != 2) }
            else -> update { copy(playing = false, position = duration) }
        }
    }

    private fun playCurrent(paused: Boolean = false) {
        val t = tracks[order.getOrNull(index) ?: return] ?: return
        val m = engine() ?: return
        m.load(t.path, startPaused = paused)
        val s = t.song
        _state.value = PlayerState(
            s.id, s.title, s.artist, s.album, s.duration, 0, !paused, true, index, repeat,
            unshuffled != null, volume, 100, System.currentTimeMillis()
        )
        publishQueue()
        scope.launch(Dispatchers.IO) {
            _lyrics.value = LocalLibrary.lyrics(t)?.let { Lyrics.parse(s.id, it, "") }
            if (!paused) countPlay(s.id)
        }
    }

    private fun countPlay(id: Long) {
        val (plays, last) = LocalLibrary.recordPlay(id, System.currentTimeMillis())
        tracks[id]?.song?.let { com.kevshupp.kevmusic.desktop.CloudSession.recordPlay(it.artist, it.title, last) }
        val lib = _library.value ?: return
        _library.value = lib.copy(songs = lib.songs.map { if (it.id == id) it.copy(plays = plays, lastPlayed = last) else it })
    }

    private fun publishQueue() {
        _queue.value = QueueState(index, order.mapNotNull { id -> tracks[id]?.song?.let { QueueItem(it.id, it.title, it.artist) } })
    }
}
