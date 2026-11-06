package com.kevshupp.kevmusicplayer.data.together

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.SongIdentity
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import com.kevshupp.kevmusicplayer.data.telegram.DeezerTrack
import com.kevshupp.kevmusicplayer.data.telegram.DeezerTrackSearch
import com.kevshupp.kevmusicplayer.data.telegram.OnDemandTracks
import com.kevshupp.kevmusicplayer.data.telegram.TelegramMusicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Escuchar juntos: dos teléfonos suenan a la vez. El anfitrión tiene la cola; el invitado sigue su canción, pausa y
 * posición, y puede pausar, reanudar, mover la barra y añadir canciones a la cola del anfitrión.
 * - **Juntos** ([Mode.NEARBY]): Nearby Connections (descubre por Bluetooth y sube a Wi‑Fi directo). Las canciones que
 *   falten en un lado se envían como archivo. Reloj de referencia: el del anfitrión (ping/pong).
 * - **A distancia** ([Mode.REMOTE]): documento `together/<código>` y subcolección `cmds` de Firestore. Reloj de
 *   referencia: el del servidor de Firestore (cada teléfono mide su desfase).
 * La sincronía se corrige ajustando un poco la velocidad (sin cambiar el tono); solo salta con desfases grandes.
 */
object TogetherController {
    enum class Mode { NEARBY, REMOTE }
    enum class Role { HOST, GUEST }
    enum class Phase { IDLE, WAITING, CONNECTING, CONNECTED }

    data class Peer(val id: String, val name: String)

    data class State(
        val mode: Mode? = null,
        val role: Role? = null,
        val phase: Phase = Phase.IDLE,
        val peer: String? = null,
        val code: String? = null,
        val authDigits: String? = null,
        val found: List<Peer> = emptyList(),
        val nowPlaying: String? = null,
        val message: String? = null,
        val progress: Float? = null,
        /** Desfase medido en el último ajuste (invitado), en ms; positivo = vamos adelantados. */
        val driftMs: Long? = null
    ) {
        val active get() = phase != Phase.IDLE
    }

    /** Lo que el controlador necesita de la biblioteca; lo da el ViewModel al conectar el reproductor. */
    interface Library {
        fun fileForMediaId(mediaId: String): AudioFile?
        fun findLocal(title: String, artist: String, durationMs: Long): AudioFile?
        fun playSong(file: AudioFile)
        fun playOnDemand(track: DeezerTrack)
        fun queueSong(file: AudioFile, next: Boolean)
        fun queueOnDemand(track: DeezerTrack, next: Boolean)
    }

    private const val SERVICE_ID = "com.kevshupp.kevmusic.together"
    private const val COLLECTION = "together"
    private const val COMMANDS = "cmds"
    /** Una sesión a distancia sin latidos durante este tiempo se da por abandonada (TTL de `together/` y `cmds/`). */
    private const val SESSION_TTL_MS = 60L * 60 * 1000

    // Corrección de la sincronía
    private const val SYNC_TICK_MS = 1000L
    private const val HARD_SEEK_NEARBY_MS = 700L
    private const val HARD_SEEK_REMOTE_MS = 1500L
    private const val SEEK_COOLDOWN_MS = 2500L
    private const val DEAD_BAND_MS = 25L
    private const val MAX_SPEED_DELTA = 0.04f
    /** Desfase al que se aplica la velocidad máxima (4 % = 40 ms recuperados por segundo). */
    private const val SPEED_GAIN_MS = 1000f
    /** Tras una orden propia (play, pausa, salto, carga) no se toman sus eventos por del usuario. */
    private const val SELF_CHANGE_WINDOW_MS = 900L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var app: Context
    private var player: Player? = null
    private var library: Library? = null

    val isGuest: Boolean get() = _state.value.role == Role.GUEST && _state.value.phase == Phase.CONNECTED

    // --- Interruptor de Ajustes › Complementos ---
    private var enabledFlow: MutableStateFlow<Boolean>? = null

    fun enabled(context: Context): StateFlow<Boolean> = enabledFlow ?: MutableStateFlow(
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).getBoolean("together_enabled", true)
    ).also { enabledFlow = it }

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).edit().putBoolean("together_enabled", value).apply()
        enabled(context)
        enabledFlow?.value = value
        if (!value) stop()
    }

    // --- Ajuste fino del invitado (auriculares Bluetooth, altavoces con retardo) ---
    private val _userOffsetMs = MutableStateFlow(0L)
    val userOffsetMs: StateFlow<Long> = _userOffsetMs.asStateFlow()

    private fun loadUserOffset(context: Context) {
        _userOffsetMs.value = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).getLong("together_offset_ms", 0L)
    }

    fun setUserOffset(context: Context, ms: Long) {
        _userOffsetMs.value = ms
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).edit().putLong("together_offset_ms", ms).apply()
    }

    // --- Enlace con el reproductor ---
    private var selfChangeUntil = 0L

    private fun markSelfChange(windowMs: Long = SELF_CHANGE_WINDOW_MS) {
        selfChangeUntil = SystemClock.elapsedRealtime() + windowMs
    }

    private fun isSelfChange() = SystemClock.elapsedRealtime() < selfChangeUntil

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val s = _state.value
            if (s.phase != Phase.CONNECTED) return
            if (s.role == Role.HOST) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED
                    )
                ) broadcastState()
                return
            }
            // Invitado: un play/pausa que no hicimos nosotros es del usuario → se lo pide al anfitrión
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && !isSelfChange()) {
                val host = lastHostState ?: return
                val wants = player.playWhenReady
                if (wants != host.playing) {
                    sendCommand(JSONObject().put("a", if (wants) "play" else "pause").put("pos", player.currentPosition))
                    // Se aplica ya aquí para que la corrección no lo deshaga antes de que conteste el anfitrión
                    lastHostState = host.copy(playing = wants, positionMs = player.currentPosition, at = refNow())
                }
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (!isGuest || reason != Player.DISCONTINUITY_REASON_SEEK || isSelfChange()) return
            val host = lastHostState ?: return
            if (newPosition.mediaItemIndex != oldPosition.mediaItemIndex) return
            sendCommand(JSONObject().put("a", "seek").put("pos", newPosition.positionMs))
            lastHostState = host.copy(positionMs = newPosition.positionMs, at = refNow())
            lastSeekAt = SystemClock.elapsedRealtime()
        }
    }

    fun attach(context: Context, player: Player, library: Library) {
        app = context.applicationContext
        this.player?.removeListener(listener)
        this.player = player
        this.library = library
        player.addListener(listener)
        loadUserOffset(app)
    }

    private fun update(block: State.() -> State) { _state.value = _state.value.block() }

    private fun t(es: String, en: String): String =
        if (java.util.Locale.getDefault().language == "es") es else en

    // --- Reloj de referencia ---
    /** Reloj de referencia - el nuestro. Juntos: el del anfitrión; a distancia: el del servidor. */
    private var clockOffset = 0L
    private var bestRtt = Long.MAX_VALUE

    private fun refNow() = System.currentTimeMillis() + clockOffset

    // --- Estado que manda el anfitrión ---
    private data class SongState(
        val key: String, val title: String, val artist: String, val album: String, val durationMs: Long,
        val playing: Boolean, val positionMs: Long, val at: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("t", "state").put("key", key).put("title", title).put("artist", artist).put("album", album)
            .put("dur", durationMs).put("playing", playing).put("pos", positionMs).put("at", at)

        fun toMap(host: String): Map<String, Any> = mapOf(
            "host" to host, "key" to key, "title" to title, "artist" to artist, "album" to album,
            "dur" to durationMs, "playing" to playing, "pos" to positionMs, "at" to at,
            // Campo de la política TTL de Firestore: cada latido lo aplaza; una sesión abandonada se borra sola
            "expiresAt" to Timestamp(java.util.Date(System.currentTimeMillis() + SESSION_TTL_MS))
        )

        companion object {
            fun from(o: JSONObject) = SongState(
                o.optString("key"), o.optString("title"), o.optString("artist"), o.optString("album"),
                o.optLong("dur"), o.optBoolean("playing"), o.optLong("pos"), o.optLong("at")
            )

            fun from(m: Map<String, Any?>) = SongState(
                m["key"] as? String ?: "", m["title"] as? String ?: "", m["artist"] as? String ?: "",
                m["album"] as? String ?: "", (m["dur"] as? Number)?.toLong() ?: 0L,
                m["playing"] as? Boolean ?: false, (m["pos"] as? Number)?.toLong() ?: 0L,
                (m["at"] as? Number)?.toLong() ?: 0L
            )
        }
    }

    private fun currentHostState(): SongState? {
        val p = player ?: return null
        val item = p.currentMediaItem ?: return null
        val file = library?.fileForMediaId(item.mediaId)
        val md = item.mediaMetadata
        return SongState(
            key = item.mediaId,
            title = file?.title ?: md.title?.toString().orEmpty(),
            artist = file?.artist ?: md.artist?.toString().orEmpty(),
            album = file?.album ?: md.albumTitle?.toString().orEmpty(),
            durationMs = file?.duration ?: p.duration.coerceAtLeast(0),
            // Solo "sonando" de verdad: en carga la posición no avanza y el invitado se adelantaría
            playing = p.isPlaying,
            positionMs = p.currentPosition,
            at = refNow()
        )
    }

    private var heartbeat: Job? = null

    private fun broadcastState() {
        val s = currentHostState() ?: return
        update { copy(nowPlaying = "${s.title} — ${s.artist}") }
        when (_state.value.mode) {
            Mode.NEARBY -> nearbyEndpoint?.let { sendJson(it, s.toJson()) }
            Mode.REMOTE -> {
                val code = _state.value.code ?: return
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
                FirebaseFirestore.getInstance().collection(COLLECTION).document(code).set(s.toMap(uid))
                    .addOnFailureListener { fail("Firestore", it) }
            }
            null -> Unit
        }
    }

    private fun startHeartbeat(periodMs: Long) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (true) {
                delay(periodMs)
                if (_state.value.role == Role.HOST && _state.value.phase == Phase.CONNECTED) broadcastState()
            }
        }
    }

    // --- Órdenes del invitado al anfitrión ---
    private fun sendCommand(cmd: JSONObject) {
        when (_state.value.mode) {
            Mode.NEARBY -> nearbyEndpoint?.let { sendJson(it, cmd.put("t", "cmd")) }
            Mode.REMOTE -> {
                val code = _state.value.code ?: return
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
                val data = mutableMapOf<String, Any>(
                    "from" to uid, "at" to FieldValue.serverTimestamp(),
                    "expiresAt" to Timestamp(java.util.Date(System.currentTimeMillis() + SESSION_TTL_MS))
                )
                cmd.keys().forEach { k -> data[k] = cmd.get(k) }
                FirebaseFirestore.getInstance().collection(COLLECTION).document(code).collection(COMMANDS).add(data)
                    .addOnFailureListener { fail("Firestore orden", it) }
            }
            null -> Unit
        }
    }

    /** Invitado: añade una canción de su biblioteca a la cola del anfitrión. */
    fun requestQueue(file: AudioFile, next: Boolean) {
        sendCommand(
            JSONObject().put("a", "queue").put("key", file.id.toString()).put("title", file.title)
                .put("artist", file.artist).put("album", file.album).put("dur", file.duration).put("next", next)
        )
        update { copy(message = t("«${file.title}» enviada a la cola del otro teléfono", "“${file.title}” sent to the other phone's queue")) }
    }

    /** Anfitrión: canciones que el invitado pidió añadir y nos está enviando (clave del invitado → orden). */
    private val pendingQueue = mutableMapOf<String, JSONObject>()

    private fun onCommand(o: JSONObject) {
        if (_state.value.role != Role.HOST) return
        val p = player ?: return
        when (o.optString("a")) {
            "play" -> {
                if (o.has("pos")) p.seekTo(o.optLong("pos"))
                p.play()
            }
            "pause" -> {
                p.pause()
                if (o.has("pos")) p.seekTo(o.optLong("pos"))
            }
            "seek" -> p.seekTo(o.optLong("pos"))
            "queue" -> {
                val title = o.optString("title")
                val next = o.optBoolean("next")
                val local = library?.findLocal(title, o.optString("artist"), o.optLong("dur"))
                when {
                    local != null -> library?.queueSong(local, next)
                    _state.value.mode == Mode.NEARBY -> {
                        // No la tenemos: que el invitado nos mande el archivo
                        val key = o.optString("key")
                        pendingQueue[key] = o
                        nearbyEndpoint?.let { sendJson(it, JSONObject().put("t", "need").put("key", key)) }
                    }
                    else -> scope.launch { queueOnline(o, next) }
                }
                update { copy(message = t("Tu amigo añadió «$title» a la cola", "Your friend queued “$title”")) }
            }
        }
    }

    private suspend fun queueOnline(o: JSONObject, next: Boolean) {
        val title = o.optString("title")
        if (!(TelegramMusicClient.isReady && OnDemandTracks.isEnabled(app))) {
            update { copy(message = t("No tienes «$title» y el modo bajo demanda está apagado", "You don't have “$title” and on-demand is off")) }
            return
        }
        val track = findOnline(title, o.optString("artist"), o.optLong("dur"))
        if (track == null) update { copy(message = t("No se encontró «$title»", "Couldn't find “$title”")) }
        else library?.queueOnDemand(track, next)
    }

    private suspend fun findOnline(title: String, artist: String, durationMs: Long): DeezerTrack? {
        val results = runCatching { withContext(Dispatchers.IO) { DeezerTrackSearch.search("$artist $title", 10) } }.getOrDefault(emptyList())
        return results.firstOrNull { SongIdentity.sameSong(title, artist, durationMs, it.title, it.artist, it.durationSec * 1000L) }
            ?: results.firstOrNull()
    }

    // --- Lado del invitado: seguir al anfitrión ---
    private var lastHostState: SongState? = null
    private var loadedKey: String? = null
    private var loadingKey: String? = null
    private var lastSeekAt = 0L
    private var currentSpeed = 1f
    private var syncJob: Job? = null

    private fun onHostState(s: SongState) {
        lastHostState = s
        update { copy(nowPlaying = "${s.title} — ${s.artist}") }
        if (s.key != loadedKey && s.key != loadingKey) loadSong(s) else applySync()
    }

    private fun loadSong(s: SongState) {
        val lib = library ?: return
        loadingKey = s.key
        val local = lib.findLocal(s.title, s.artist, s.durationMs)
        if (local != null) {
            markSelfChange(3000)
            lib.playSong(local)
            markLoaded(s.key)
            return
        }
        when (_state.value.mode) {
            Mode.NEARBY -> {
                update { copy(message = t("Recibiendo «${s.title}»…", "Receiving “${s.title}”…"), progress = 0f) }
                nearbyEndpoint?.let { sendJson(it, JSONObject().put("t", "need").put("key", s.key)) }
            }
            Mode.REMOTE -> scope.launch { loadOnDemand(s) }
            null -> Unit
        }
    }

    private suspend fun loadOnDemand(s: SongState) {
        if (!(TelegramMusicClient.isReady && OnDemandTracks.isEnabled(app))) {
            loadingKey = null
            markSelfChange()
            player?.pause()
            update { copy(message = t("No tienes «${s.title}» en tu biblioteca", "You don't have “${s.title}” in your library")) }
            return
        }
        update { copy(message = t("Buscando «${s.title}» en línea…", "Finding “${s.title}” online…")) }
        val track = findOnline(s.title, s.artist, s.durationMs)
        if (lastHostState?.key != s.key) return
        if (track == null) {
            loadingKey = null
            update { copy(message = t("No se encontró «${s.title}»", "Couldn't find “${s.title}”")) }
            return
        }
        markSelfChange(3000)
        library?.playOnDemand(track)
        markLoaded(s.key)
    }

    private fun mediaItemFor(key: String, file: File, title: String, artist: String, album: String): MediaItem {
        val uri = Uri.fromFile(file)
        return MediaItem.Builder()
            .setMediaId("together:$key")
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
                    .setIsPlayable(true).setIsBrowsable(false).build()
            )
            .build()
    }

    private fun playReceivedFile(key: String, file: File) {
        val s = lastHostState ?: return
        if (s.key != key) return
        val p = player ?: return
        markSelfChange(3000)
        p.setMediaItem(mediaItemFor(key, file, s.title, s.artist, s.album), 0L)
        p.prepare()
        markLoaded(key)
    }

    /** Anfitrión: llegó del invitado una canción que pidió añadir y no teníamos. */
    private fun queueReceivedFile(key: String, file: File) {
        val p = player ?: return
        val o = pendingQueue.remove(key)
        val next = o?.optBoolean("next") ?: false
        receivedFiles["together:$key"] = file
        val item = mediaItemFor(key, file, o?.optString("title").orEmpty(), o?.optString("artist").orEmpty(), o?.optString("album").orEmpty())
        val index = if (next && p.currentMediaItemIndex >= 0) (p.currentMediaItemIndex + 1).coerceAtMost(p.mediaItemCount) else p.mediaItemCount
        p.addMediaItem(index, item)
        update { copy(progress = null) }
    }

    private fun markLoaded(key: String) {
        loadedKey = key
        loadingKey = null
        lastSeekAt = 0L
        update { copy(message = null, progress = null) }
        scope.launch { delay(400); applySync() }
    }

    private fun setSpeed(p: Player, speed: Float) {
        if (kotlin.math.abs(speed - currentSpeed) < 0.004f) return
        currentSpeed = speed
        p.setPlaybackSpeed(speed)
    }

    /**
     * Lleva el reproductor a donde va el anfitrión. Con desfases pequeños cambia la velocidad hasta un 4 % (inaudible
     * y sin cortes); saltar con `seekTo` deja siempre unos cientos de ms de error por lo que tarda en volver a sonar.
     */
    private fun applySync() {
        val s = lastHostState ?: return
        val p = player ?: return
        if (s.key != loadedKey) return
        if (s.playing && !p.playWhenReady) { markSelfChange(); p.play() }
        if (!s.playing && p.playWhenReady) { markSelfChange(); p.pause() }
        val target = (if (s.playing) s.positionMs + (refNow() - s.at).coerceAtLeast(0) else s.positionMs) + _userOffsetMs.value
        if (s.durationMs in 1 until target) return
        val drift = p.currentPosition - target
        update { copy(driftMs = drift) }
        val now = SystemClock.elapsedRealtime()
        if (!s.playing || p.playbackState != Player.STATE_READY) {
            setSpeed(p, 1f)
            if (!s.playing && kotlin.math.abs(drift) > 300 && now - lastSeekAt > SEEK_COOLDOWN_MS) {
                lastSeekAt = now; markSelfChange(); p.seekTo(target.coerceAtLeast(0))
            }
            return
        }
        val hard = if (_state.value.mode == Mode.NEARBY) HARD_SEEK_NEARBY_MS else HARD_SEEK_REMOTE_MS
        when {
            kotlin.math.abs(drift) > hard -> if (now - lastSeekAt > SEEK_COOLDOWN_MS) {
                lastSeekAt = now
                setSpeed(p, 1f)
                markSelfChange()
                p.seekTo(target.coerceAtLeast(0))
            }
            kotlin.math.abs(drift) <= DEAD_BAND_MS -> setSpeed(p, 1f)
            else -> {
                val delta = (drift / SPEED_GAIN_MS * MAX_SPEED_DELTA).coerceIn(-MAX_SPEED_DELTA, MAX_SPEED_DELTA)
                setSpeed(p, 1f - delta) // adelantados → más lento; atrasados → más rápido
            }
        }
    }

    private fun startGuestSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            var ticks = 0
            while (true) {
                delay(SYNC_TICK_MS)
                if (!isGuest) continue
                applySync()
                // El reloj se vuelve a medir cada 30 s (los relojes de los teléfonos derivan)
                if (++ticks % 30 == 0) measureClock()
            }
        }
    }

    private fun measureClock() {
        when (_state.value.mode) {
            Mode.NEARBY -> nearbyEndpoint?.let { ep ->
                scope.launch {
                    bestRtt = Long.MAX_VALUE
                    repeat(6) {
                        sendJson(ep, JSONObject().put("t", "ping").put("c", System.currentTimeMillis()))
                        delay(200)
                    }
                }
            }
            Mode.REMOTE -> scope.launch { measureServerClock() }
            null -> Unit
        }
    }

    /** Desfase con el reloj del servidor de Firestore: se escribe su hora y se lee de vuelta. Toma la ida y vuelta más corta. */
    private suspend fun measureServerClock() {
        val code = _state.value.code ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val doc = FirebaseFirestore.getInstance().collection(COLLECTION).document(code).collection(COMMANDS).document("clock_$uid")
        var best = Long.MAX_VALUE
        repeat(3) {
            runCatching {
                val t0 = System.currentTimeMillis()
                doc.set(
                    mapOf(
                        "from" to uid, "a" to "clock", "t" to FieldValue.serverTimestamp(),
                        "expiresAt" to Timestamp(java.util.Date(t0 + SESSION_TTL_MS))
                    )
                ).await()
                val t1 = System.currentTimeMillis()
                val server = doc.get(Source.SERVER).await().getTimestamp("t")?.toDate()?.time
                // El servidor fija la hora hacia la mitad de la escritura
                if (server != null && t1 - t0 < best) {
                    best = t1 - t0
                    clockOffset = server - (t0 + t1) / 2
                }
            }.onFailure { fail("Reloj servidor", it) }
        }
    }

    // --- Juntos: Nearby Connections ---
    private var client: ConnectionsClient? = null
    private var nearbyEndpoint: String? = null
    private val incomingFiles = mutableMapOf<Long, Payload>()
    private val fileKeys = mutableMapOf<Long, String>()
    private val doneFiles = mutableSetOf<Long>()
    /** Anfitrión: canciones recibidas del invitado (por `mediaId`), por si hay que reenviarlas. */
    private val receivedFiles = mutableMapOf<String, File>()

    private fun deviceName(): String =
        runCatching { android.provider.Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    private fun nearby(): ConnectionsClient = client ?: Nearby.getConnectionsClient(app).also { client = it }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let { bytes ->
                    runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()?.let { onNearbyMessage(endpointId, it) }
                }
                Payload.Type.FILE -> {
                    incomingFiles[payload.id] = payload
                    tryFinishFile(payload.id)
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (!incomingFiles.containsKey(update.payloadId) && !fileKeys.containsKey(update.payloadId)) return
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> if (update.totalBytes > 0) {
                    update { copy(progress = update.bytesTransferred.toFloat() / update.totalBytes) }
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    doneFiles += update.payloadId
                    tryFinishFile(update.payloadId)
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    incomingFiles.remove(update.payloadId)
                    fileKeys.remove(update.payloadId)
                    loadingKey = null
                    update { copy(message = t("No se pudo recibir la canción", "Couldn't receive the song"), progress = null) }
                }
            }
        }
    }

    private fun tryFinishFile(id: Long) {
        val payload = incomingFiles[id] ?: return
        val key = fileKeys[id] ?: return
        if (id !in doneFiles) return
        incomingFiles.remove(id); fileKeys.remove(id); doneFiles.remove(id)
        val host = _state.value.role == Role.HOST
        scope.launch {
            val dest = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(app.cacheDir, "together").apply { mkdirs() }
                    // El invitado solo guarda la que suena; el anfitrión, las que añadió el invitado a la cola
                    if (!host) dir.listFiles()?.forEach { it.delete() }
                    val out = File(dir, "song_${key.hashCode().toUInt()}")
                    val src = payload.asFile() ?: return@runCatching null
                    val uri = src.asUri()
                    if (uri != null) {
                        app.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                        runCatching { app.contentResolver.delete(uri, null, null) }
                    } else {
                        src.asParcelFileDescriptor().use { pfd ->
                            java.io.FileInputStream(pfd.fileDescriptor).use { input -> out.outputStream().use { input.copyTo(it) } }
                        }
                    }
                    out
                }.getOrNull()
            }
            when {
                dest == null || dest.length() == 0L ->
                    update { copy(message = t("No se pudo recibir la canción", "Couldn't receive the song"), progress = null) }
                host -> queueReceivedFile(key, dest)
                else -> playReceivedFile(key, dest)
            }
        }
    }

    private fun onNearbyMessage(endpointId: String, o: JSONObject) {
        when (o.optString("t")) {
            "state" -> if (_state.value.role == Role.GUEST) onHostState(SongState.from(o))
            "cmd" -> onCommand(o)
            "ping" -> sendJson(endpointId, JSONObject().put("t", "pong").put("c", o.optLong("c")).put("h", refNow()))
            "pong" -> {
                val now = System.currentTimeMillis()
                val sent = o.optLong("c")
                val rtt = now - sent
                if (rtt in 0 until bestRtt) {
                    bestRtt = rtt
                    clockOffset = o.optLong("h") - (sent + rtt / 2)
                }
            }
            "need" -> sendSongFile(endpointId, o.optString("key"))
            "file" -> {
                val id = o.optLong("pid")
                fileKeys[id] = o.optString("key")
                tryFinishFile(id)
            }
            "nofile" -> {
                loadingKey = null
                pendingQueue.remove(o.optString("key"))
                update { copy(message = t("El otro teléfono no puede enviar esta canción", "The other phone can't send this song"), progress = null) }
            }
        }
    }

    private fun sendSongFile(endpointId: String, key: String) {
        val pfd = receivedFiles[key]?.let { f -> runCatching { android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() }
            ?: library?.fileForMediaId(key)?.let { runCatching { app.contentResolver.openFileDescriptor(Uri.parse(it.uriString), "r") }.getOrNull() }
        if (pfd == null) {
            sendJson(endpointId, JSONObject().put("t", "nofile").put("key", key))
            return
        }
        val payload = Payload.fromFile(pfd)
        sendJson(endpointId, JSONObject().put("t", "file").put("key", key).put("pid", payload.id))
        nearby().sendPayload(endpointId, payload).addOnFailureListener { fail("Nearby envío", it) }
    }

    private fun sendJson(endpointId: String, o: JSONObject) {
        nearby().sendPayload(endpointId, Payload.fromBytes(o.toString().toByteArray(Charsets.UTF_8)))
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            update { copy(phase = Phase.CONNECTING, peer = info.endpointName, authDigits = info.authenticationDigits) }
            nearby().acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) {
                update { copy(phase = if (role == Role.HOST) Phase.WAITING else Phase.IDLE, message = t("No se pudo conectar", "Couldn't connect")) }
                return
            }
            nearbyEndpoint = endpointId
            val c = nearby()
            c.stopAdvertising(); c.stopDiscovery()
            update { copy(phase = Phase.CONNECTED, message = null, found = emptyList()) }
            if (_state.value.role == Role.HOST) {
                broadcastState()
                startHeartbeat(3000)
            } else {
                measureClock()
                startGuestSync()
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId != nearbyEndpoint) return
            nearbyEndpoint = null
            stop()
            update { copy(message = t("Se desconectó el otro teléfono", "The other phone disconnected")) }
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            update { copy(found = (found.filter { it.id != endpointId } + Peer(endpointId, info.endpointName))) }
        }

        override fun onEndpointLost(endpointId: String) {
            update { copy(found = found.filter { it.id != endpointId }) }
        }
    }

    private val strategy = Strategy.P2P_POINT_TO_POINT

    fun hostNearby(context: Context) {
        app = context.applicationContext
        stop()
        update { State(mode = Mode.NEARBY, role = Role.HOST, phase = Phase.WAITING, peer = deviceName()) }
        nearby().startAdvertising(deviceName(), SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { fail("Nearby anuncio", it); stop(); update { copy(message = t("No se pudo activar Bluetooth/Nearby", "Couldn't start Bluetooth/Nearby")) } }
    }

    fun discoverNearby(context: Context) {
        app = context.applicationContext
        stop()
        update { State(mode = Mode.NEARBY, role = Role.GUEST, phase = Phase.WAITING) }
        nearby().startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { fail("Nearby búsqueda", it); stop(); update { copy(message = t("No se pudo buscar por Bluetooth/Nearby", "Couldn't search via Bluetooth/Nearby")) } }
    }

    fun connectTo(peer: Peer) {
        update { copy(phase = Phase.CONNECTING, peer = peer.name) }
        nearby().requestConnection(deviceName(), peer.id, lifecycle)
            .addOnFailureListener { fail("Nearby conexión", it); update { copy(phase = Phase.WAITING, message = t("No se pudo conectar", "Couldn't connect")) } }
    }

    // --- A distancia: Firestore ---
    private var remoteListener: ListenerRegistration? = null
    private var commandListener: ListenerRegistration? = null

    private fun signInMessage() = t("Inicia sesión con Google (Perfil) para escuchar a distancia", "Sign in with Google (Profile) to listen remotely")

    fun hostRemote(context: Context) {
        app = context.applicationContext
        stop()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            update { copy(message = signInMessage()) }
            return
        }
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val code = (1..6).map { alphabet.random() }.joinToString("")
        update { State(mode = Mode.REMOTE, role = Role.HOST, phase = Phase.CONNECTED, code = code) }
        scope.launch {
            // El documento de la sesión va primero: las reglas de `cmds` lo consultan para saber quién es el anfitrión
            broadcastState()
            measureServerClock()
            broadcastState() // ya con `at` en la hora del servidor
            startHeartbeat(10_000)
            listenCommands(code, uid)
        }
    }

    private fun listenCommands(code: String, uid: String) {
        commandListener?.remove()
        val cmds = FirebaseFirestore.getInstance().collection(COLLECTION).document(code).collection(COMMANDS)
        commandListener = cmds.addSnapshotListener { snap, error ->
            if (error != null) { fail("Firestore órdenes", error); return@addSnapshotListener }
            snap?.documentChanges?.forEach { change ->
                if (change.type != DocumentChange.Type.ADDED) return@forEach
                val doc = change.document
                if (doc.getString("from") == uid || doc.getString("a") == "clock") return@forEach
                val o = JSONObject()
                doc.data.forEach { (k, v) -> if (v is String || v is Number || v is Boolean) o.put(k, v) }
                onCommand(o)
                doc.reference.delete()
            }
        }
    }

    fun joinRemote(context: Context, rawCode: String) {
        app = context.applicationContext
        stop()
        val code = rawCode.trim().uppercase()
        if (FirebaseAuth.getInstance().currentUser == null) {
            update { copy(message = signInMessage()) }
            return
        }
        if (code.length < 4) return
        update { State(mode = Mode.REMOTE, role = Role.GUEST, phase = Phase.CONNECTING, code = code) }
        scope.launch { measureServerClock() }
        remoteListener = FirebaseFirestore.getInstance().collection(COLLECTION).document(code)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    fail("Firestore escucha", error)
                    stop()
                    update { copy(message = t("No se pudo unir a la sesión", "Couldn't join the session")) }
                    return@addSnapshotListener
                }
                // El TTL de Firestore puede tardar hasta un día en borrar: una sesión caducada ya no vale
                val expired = snap?.getTimestamp("expiresAt")?.toDate()?.time?.let { it < System.currentTimeMillis() } == true
                if (snap == null || !snap.exists() || expired) {
                    val wasConnected = _state.value.phase == Phase.CONNECTED
                    stop()
                    update { copy(message = if (wasConnected) t("La sesión terminó", "The session ended") else t("No existe la sesión $code", "Session $code doesn't exist")) }
                    return@addSnapshotListener
                }
                if (_state.value.phase != Phase.CONNECTED) {
                    update { copy(phase = Phase.CONNECTED) }
                    startGuestSync()
                }
                onHostState(SongState.from(snap.data.orEmpty()))
            }
    }

    // --- Salir ---
    fun stop() {
        heartbeat?.cancel(); heartbeat = null
        syncJob?.cancel(); syncJob = null
        val s = _state.value
        client?.let { c ->
            c.stopAdvertising(); c.stopDiscovery(); c.stopAllEndpoints()
        }
        nearbyEndpoint = null
        incomingFiles.clear(); fileKeys.clear(); doneFiles.clear()
        pendingQueue.clear()
        remoteListener?.remove(); remoteListener = null
        commandListener?.remove(); commandListener = null
        if (s.mode == Mode.REMOTE && s.role == Role.HOST && s.code != null) {
            FirebaseFirestore.getInstance().collection(COLLECTION).document(s.code).delete()
        }
        if (currentSpeed != 1f) player?.setPlaybackSpeed(1f)
        currentSpeed = 1f
        lastHostState = null; loadedKey = null; loadingKey = null
        clockOffset = 0L; bestRtt = Long.MAX_VALUE
        // El invitado borra la canción recibida; el anfitrión conserva las suyas porque siguen en su cola
        if (s.role == Role.GUEST && ::app.isInitialized) File(app.cacheDir, "together").listFiles()?.forEach { it.delete() }
        _state.value = State()
    }

    fun clearMessage() = update { copy(message = null) }

    private fun fail(where: String, e: Throwable) {
        if (::app.isInitialized) TelemetryLogger.logInfo(app, "Together", "$where: ${e.message}")
    }
}
