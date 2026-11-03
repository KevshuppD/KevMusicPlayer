package com.kevshupp.kevmusicplayer.data.cast

import android.content.Context
import android.net.Uri
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import com.kevshupp.kevmusicplayer.playback.getPhysicalPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Transmite la cola a un Chromecast o a un dispositivo DLNA (ver [DlnaSession]); el reproductor local queda en pausa. */
object CastController {
    data class CastState(val device: String? = null, val connecting: Boolean = false, val title: String = "", val playing: Boolean = false, val error: String? = null)
    data class Device(val id: String, val name: String, val route: MediaRouter.RouteInfo? = null, val dlna: DlnaRenderer? = null)

    data class VolumeChange(val level: Float, val at: Long)

    private const val MAX_QUEUE = 50
    private const val CONNECT_TIMEOUT_MS = 20_000L

    /** Último cambio con los botones físicos, para mostrarlo en pantalla. */
    private val _volumeChange = MutableStateFlow<VolumeChange?>(null)
    val volumeChange: StateFlow<VolumeChange?> = _volumeChange

    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices

    private var server: CastMediaServer? = null
    private var pending: Pair<List<AudioFile>, Long>? = null
    private var listening = false
    private lateinit var app: Context

    private val selector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastOptionsProvider.receiverId))
            .build()
    }

    private val routeCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices(router)
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices(router)
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshDevices(router)
    }

    private var castDevices: List<Device> = emptyList()
    private var dlnaDevices: List<Device> = emptyList()
    private var dlnaDiscovery: kotlinx.coroutines.Job? = null
    private var dlna: DlnaSession? = null
    private var connectTimeout: kotlinx.coroutines.Job? = null

    private fun refreshDevices(router: MediaRouter) {
        castDevices = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
            .map { Device(it.id, it.name, it) }
        publishDevices()
    }

    private fun lastPrefs() = app.getSharedPreferences("cast_prefs", Context.MODE_PRIVATE)

    /** El último dispositivo usado (id de la ruta o UDN de DLNA), que la lista muestra primero. */
    fun lastDeviceId(): String? = if (::app.isInitialized) lastPrefs().getString("last_device", null) else null

    private fun publishDevices() {
        val last = lastDeviceId()
        _devices.value = (castDevices + dlnaDevices).sortedByDescending { it.id == last }
    }

    private var enabledFlow: MutableStateFlow<Boolean>? = null

    /** Interruptor de Ajustes › Complementos; apagado, el reproductor no muestra el botón. */
    fun enabled(context: Context): StateFlow<Boolean> = enabledFlow ?: MutableStateFlow(
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).getBoolean("cast_enabled", true)
    ).also { enabledFlow = it }

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).edit().putBoolean("cast_enabled", value).apply()
        enabled(context)
        enabledFlow?.value = value
        if (!value && isCasting) disconnect()
    }

    /** Busca mientras el diálogo está abierto. El bloqueo hace que Android no descarte el multicast de las respuestas. */
    private fun startDlnaDiscovery() {
        if (dlnaDiscovery?.isActive == true) return
        dlnaDiscovery = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val wifi = app.getSystemService(android.net.wifi.WifiManager::class.java)
            val lock = runCatching { wifi?.createMulticastLock("kevmusic-dlna")?.apply { acquire() } }.getOrNull()
            try {
                while (true) {
                    val found = runCatching { DlnaClient.discover() }.getOrElse {
                        TelemetryLogger.logInfo(app, "Cast", "Búsqueda DLNA falló: ${it.message}")
                        emptyList()
                    }
                    if (found.isNotEmpty() || dlnaDevices.isNotEmpty()) {
                        dlnaDevices = found.map { Device(it.id, it.name, dlna = it) }
                        publishDevices()
                    }
                    kotlinx.coroutines.delay(4000)
                }
            } finally {
                runCatching { lock?.release() }
            }
        }
    }

    private val clientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = publishStatus()
        override fun onMetadataUpdated() = publishStatus()
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var localPlayer: androidx.media3.common.Player? = null
    /** Canción a la que se movió el teléfono para seguir a la TV: ese cambio no se reenvía. */
    private var followingMediaId: String? = null

    val isCasting get() = _state.value.device != null
    val supportsLyrics get() = dlna == null && CastOptionsProvider.CUSTOM_RECEIVER_ID.isNotBlank()

    /** Posición real mientras se transmite: la del teléfono está parada. */
    fun remotePosition(): Long? = if (!isCasting) null else dlna?.positionMs ?: client()?.approximateStreamPosition

    /** Para el botón de reproducir: el teléfono está en pausa, así que `player.pause()` no llegaría a la TV. */
    fun interceptToggle(): Boolean {
        if (!isCasting) return false
        togglePlay()
        return true
    }

    /** El volumen del Chromecast, de 0 a 1. */
    fun volume(): Float = dlna?.volume ?: runCatching { CastContext.getSharedInstance(app).sessionManager.currentCastSession?.volume?.toFloat() }.getOrNull() ?: 0f

    fun setVolume(value: Float) {
        dlna?.let { return it.changeVolume(value) }
        runCatching { CastContext.getSharedInstance(app).sessionManager.currentCastSession?.volume = value.toDouble().coerceIn(0.0, 1.0) }
    }

    /** Botones físicos de volumen; true si se usaron para el Chromecast. */
    fun adjustVolume(up: Boolean): Boolean {
        if (!isCasting) return false
        // La sesión tarda en confirmar el valor: con pulsaciones seguidas se parte del último pedido
        val from = _volumeChange.value?.takeIf { System.currentTimeMillis() - it.at < 2000 }?.level ?: volume()
        // En DLNA la escala real es 0-100 y las teles ya suenan fuerte en 20: un 5 % por pulsación es demasiado
        val step = if (dlna != null) 0.02f else 0.05f
        val level = (from + if (up) step else -step).coerceIn(0f, 1f)
        setVolume(level)
        _volumeChange.value = VolumeChange(level, System.currentTimeMillis())
        return true
    }

    private const val LYRICS_NAMESPACE = "urn:x-cast:com.kevshupp.kevmusic"
    private var lyricsSentFor: String? = null

    /** La letra va por mensaje, solo de la canción que suena: en la cola no cabrían 50 (límite de 64 KB). */
    private fun sendLyrics(c: RemoteMediaClient) {
        if (CastOptionsProvider.CUSTOM_RECEIVER_ID.isBlank()) return
        val id = c.mediaInfo?.contentId?.substringAfterLast('/')?.substringBefore('.') ?: return
        if (id == lyricsSentFor) return
        val songId = id.toLongOrNull() ?: return
        lyricsSentFor = id
        scope.launch {
            val song = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.kevshupp.kevmusicplayer.data.AppDatabase.getDatabase(app).audioDao().getAudioFileById(songId)
            } ?: return@launch
            val json = org.json.JSONObject().put("id", id).put("lyrics", song.lyrics.orEmpty()).put("translated", song.translatedLyrics.orEmpty()).toString()
            if (json.toByteArray().size > 60_000) return@launch
            runCatching { CastContext.getSharedInstance(app).sessionManager.currentCastSession?.sendMessage(LYRICS_NAMESPACE, json) }
        }
    }

    /**
     * Mientras se transmite, el reproductor del teléfono es el control remoto: nunca suena, reproducir o pausar
     * actúa en la TV, y elegir otra canción o mover la barra se reenvía. Así valen la pantalla, la notificación y el PC.
     */
    fun bindPlayer(context: Context, player: androidx.media3.common.Player) {
        app = context.applicationContext
        localPlayer = player
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady || !isCasting) return
                player.pause()
                togglePlay()
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (!isCasting || mediaItem == null) return
                if (mediaItem.mediaId == followingMediaId) {
                    followingMediaId = null
                    return
                }
                sendFromPlayer(player, 0L)
            }

            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int
            ) {
                if (!isCasting || reason != androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK) return
                if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) return
                dlna?.let { return it.seek(newPosition.positionMs) }
                client()?.seek(com.google.android.gms.cast.MediaSeekOptions.Builder().setPosition(newPosition.positionMs).build())
            }
        })
    }

    private fun sendFromPlayer(player: androidx.media3.common.Player, positionMs: Long) {
        val start = player.currentMediaItemIndex.coerceAtLeast(0)
        val ids = (start until player.mediaItemCount).take(MAX_QUEUE).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
        scope.launch {
            val dao = com.kevshupp.kevmusicplayer.data.AppDatabase.getDatabase(app).audioDao()
            val songs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ids.filter { it > 0 }.mapNotNull { dao.getAudioFileById(it) } }
            if (songs.isNotEmpty()) send(songs, positionMs)
        }
    }

    /** La TV pasó a otra canción: el teléfono la muestra, sin sonar. */
    private fun followRemote(c: RemoteMediaClient) {
        val player = localPlayer ?: return
        val id = c.mediaInfo?.contentId?.substringAfterLast('/')?.substringBefore('.') ?: return
        followSong(id)
    }

    private fun followSong(id: String) {
        val player = localPlayer ?: return
        if (player.currentMediaItem?.mediaId == id) return
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == id } ?: return
        followingMediaId = id
        player.seekTo(index, 0L)
    }

    private fun client(): RemoteMediaClient? = runCatching {
        CastContext.getSharedInstance(app).sessionManager.currentCastSession?.remoteMediaClient
    }.getOrNull()

    private fun publishStatus() {
        val c = client() ?: return
        val status = c.mediaStatus
        followRemote(c)
        sendLyrics(c)
        _state.value = _state.value.copy(
            title = c.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE).orEmpty(),
            playing = status?.playerState == MediaStatus.PLAYER_STATE_PLAYING || status?.playerState == MediaStatus.PLAYER_STATE_BUFFERING,
            error = if (status?.playerState == MediaStatus.PLAYER_STATE_IDLE && status.idleReason == MediaStatus.IDLE_REASON_ERROR)
                "El Chromecast no pudo reproducir esta canción" else null
        )
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onConnected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onConnected(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) = fail("No se pudo conectar con el dispositivo ($error)")
        override fun onSessionResumeFailed(session: CastSession, error: Int) = fail("No se pudo conectar con el dispositivo ($error)")
        override fun onSessionEnded(session: CastSession, error: Int) = cleanUp()
        override fun onSessionStarting(session: CastSession) { _state.value = _state.value.copy(connecting = true, error = null) }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private fun fail(message: String) {
        cleanUp()
        _state.value = CastState(error = message)
    }

    private fun onConnected(session: CastSession) {
        connectTimeout?.cancel()
        _state.value = _state.value.copy(device = session.castDevice?.friendlyName ?: "Chromecast", connecting = false, error = null)
        localPlayer?.pause()
        session.remoteMediaClient?.registerCallback(clientCallback)
        pending?.let { (songs, position) ->
            pending = null
            load(session, songs, position)
        }
        publishStatus()
    }

    /** Empieza a buscar dispositivos. false si este teléfono no tiene Google Play Services. */
    fun startDiscovery(context: Context): Boolean {
        app = context.applicationContext
        return try {
            val manager = CastContext.getSharedInstance(app).sessionManager
            if (!listening) {
                manager.addSessionManagerListener(sessionListener, CastSession::class.java)
                listening = true
            }
            val router = MediaRouter.getInstance(app)
            router.addCallback(selector, routeCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            refreshDevices(router)
            true
        } catch (e: Exception) {
            // Sin Google Play Services, DLNA sigue funcionando
            TelemetryLogger.logError(app, "Cast", "No se pudo iniciar Cast", e)
            true
        }.also { startDlnaDiscovery() }
    }

    fun stopDiscovery() {
        runCatching { MediaRouter.getInstance(app).removeCallback(routeCallback) }
        dlnaDiscovery?.cancel()
    }

    fun castTo(device: Device, songs: List<AudioFile>, positionMs: Long) {
        lastPrefs().edit().putString("last_device", device.id).apply()
        publishDevices()
        device.dlna?.let { return castToDlna(it, songs, positionMs) }
        pending = songs to positionMs
        _state.value = CastState(connecting = true)
        val session = runCatching { CastContext.getSharedInstance(app).sessionManager.currentCastSession }.getOrNull()
        if (session?.isConnected == true) return onConnected(session)
        device.route?.select()
        // Google Cast no siempre avisa si el dispositivo no responde: sin esto, "Conectando…" no termina nunca
        connectTimeout?.cancel()
        connectTimeout = scope.launch {
            kotlinx.coroutines.delay(CONNECT_TIMEOUT_MS)
            if (_state.value.connecting) {
                TelemetryLogger.logInfo(app, "Cast", "Sin respuesta de ${device.name}")
                runCatching { CastContext.getSharedInstance(app).sessionManager.endCurrentSession(true) }
                runCatching { MediaRouter.getInstance(app).let { it.unselect(MediaRouter.UNSELECT_REASON_STOPPED) } }
                fail("${device.name} no respondió. Si es una tele sin Chromecast, prueba con su entrada DLNA.")
            }
        }
    }

    private fun castToDlna(renderer: DlnaRenderer, songs: List<AudioFile>, positionMs: Long) {
        dlna?.stop {}
        val base = ensureServer() ?: return fail("No hay red local: conecta el teléfono al mismo Wi-Fi que el dispositivo")
        val session = DlnaSession(
            app, renderer, base,
            onStatus = { title, playing, error ->
                _state.value = _state.value.copy(title = title, playing = playing, error = error)
            },
            onSongChanged = { id -> scope.launch { followSong(id.toString()) } },
            onLost = { message -> scope.launch { fail(message) } }
        )
        dlna = session
        _state.value = CastState(device = renderer.name)
        localPlayer?.pause()
        session.load(songs.filter { getPhysicalPath(app, it.id, it.uriString)?.let(CastMediaServer::mimeOf) != null }, positionMs)
    }

    private fun ensureServer(): String? {
        val media = server ?: CastMediaServer(app).also {
            it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = it
        }
        return media.baseUrl()
    }

    /** Vuelve a enviar la cola con la sesión ya abierta (al cambiar de canción en el teléfono). */
    fun send(songs: List<AudioFile>, positionMs: Long) {
        dlna?.let { return it.load(songs, positionMs) }
        val session = runCatching { CastContext.getSharedInstance(app).sessionManager.currentCastSession }.getOrNull() ?: return
        load(session, songs, positionMs)
    }

    private fun load(session: CastSession, songs: List<AudioFile>, positionMs: Long) {
        val base = ensureServer() ?: return fail("No hay red local: conecta el teléfono al mismo Wi-Fi que el dispositivo")
        val current = songs.firstOrNull()
        val items = songs.take(MAX_QUEUE).mapNotNull { queueItem(it, base) }.toTypedArray()
        if (items.isEmpty()) return fail("El Chromecast no admite el formato de esta canción")
        val startsAtCurrent = current != null && queueItem(current, base) != null
        session.remoteMediaClient?.queueLoad(items, 0, MediaStatus.REPEAT_MODE_REPEAT_OFF, if (startsAtCurrent) positionMs else 0L, null)
            ?.setResultCallback { result ->
                if (!result.status.isSuccess) {
                    TelemetryLogger.logInfo(app, "Cast", "queueLoad falló: ${result.status.statusCode}")
                    _state.value = _state.value.copy(error = "El Chromecast rechazó la cola (${result.status.statusCode})")
                }
            }
    }

    private fun queueItem(song: AudioFile, base: String): MediaQueueItem? {
        val path = getPhysicalPath(app, song.id, song.uriString) ?: return null
        val mime = CastMediaServer.mimeOf(path) ?: return null
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, song.title)
            putString(MediaMetadata.KEY_ARTIST, song.artist)
            putString(MediaMetadata.KEY_ALBUM_TITLE, song.album)
            addImage(WebImage(Uri.parse("$base/art/${song.id}.jpg")))
        }
        val info = MediaInfo.Builder("$base/song/${song.id}.${path.substringAfterLast('.')}")
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setStreamDuration(song.duration.toLong())
            .setMetadata(metadata)
            .build()
        return MediaQueueItem.Builder(info).setAutoplay(true).setPreloadTime(15.0).build()
    }

    fun togglePlay() { dlna?.let { return it.togglePlay() }; client()?.togglePlayback() }
    fun next() { dlna?.let { return it.next() }; client()?.queueNext(null) }
    fun previous() { dlna?.let { return it.previous() }; client()?.queuePrev(null) }

    fun disconnect() {
        if (dlna == null) runCatching { CastContext.getSharedInstance(app).sessionManager.endCurrentSession(true) }
        cleanUp()
    }

    private fun cleanUp() {
        connectTimeout?.cancel()
        runCatching { client()?.unregisterCallback(clientCallback) }
        pending = null
        lyricsSentFor = null
        val media = server
        server = null
        // El servidor sigue hasta que el dispositivo DLNA haya parado
        dlna?.stop { media?.stop() } ?: media?.stop()
        dlna = null
        _state.value = CastState()
    }
}
