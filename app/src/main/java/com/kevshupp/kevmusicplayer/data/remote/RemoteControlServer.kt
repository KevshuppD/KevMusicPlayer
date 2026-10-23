package com.kevshupp.kevmusicplayer.data.remote

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.kevshupp.kevmusicplayer.data.AppDatabase
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.PreferenceConstants
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import com.kevshupp.kevmusicplayer.data.telegram.DeezerTrack
import com.kevshupp.kevmusicplayer.data.telegram.OnDemandTracks
import com.kevshupp.kevmusicplayer.data.telegram.TelegramDownloadQueue
import com.kevshupp.kevmusicplayer.ui.screens.loadAlbumArtBitmap
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Servidor de control remoto para KevMusic Desktop (carpeta `desktop/` del repositorio).
 *
 * Vive dentro de PlaybackService mientras `settings_prefs/remote_enabled` está activo. Protocolo:
 * - WebSocket en `/ws`: el primer mensaje es `pair` (código de 6 cifras visible en Ajustes) o `hello` (token
 *   guardado). Después el teléfono emite `state`, `queue` y `lyrics`, y acepta los comandos de [handleCommand].
 * - HTTP `GET /library?token=` (canciones y listas en JSON), `GET /art/<id>?token=&size=` (carátula JPEG) y
 *   `PUT /upload?token=&name=` (una canción descargada en el PC, p. ej. de Soulseek; ver [receiveUpload]).
 * Se anuncia en la red local por mDNS como `_kevmusic._tcp`.
 */
class RemoteControlServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val playerProvider: () -> Player?,
    private val onSkip: (next: Boolean) -> Unit
) {
    companion object {
        const val PORT = 47820
        const val KEY_ENABLED = "remote_enabled"
        const val SERVICE_TYPE = "_kevmusic._tcp."
        private const val PREFS = "remote_prefs"
        private const val KEY_TOKENS = "tokens"
        private const val PROTOCOL_VERSION = 2
        private const val MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024
        private val UPLOAD_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "aiff", "aif", "wma", "alac", "ape")

        /** Código de emparejamiento actual (null si el servidor está parado). Lo muestra Ajustes. */
        val pairingCode = MutableStateFlow<String?>(null)
        /**
         * Letra conocida o cambiada en la app (id, letra, traducción). La app la consigue a menudo después de
         * empezar la canción (.lrc, etiquetas, LrcLib), cuando ya se envió la de Room vacía.
         */
        val lyricsChanged = kotlinx.coroutines.flow.MutableSharedFlow<Triple<Long, String?, String?>>(extraBufferCapacity = 16)

        /**
         * Modo aleatorio propio de la app (la cola se reordena físicamente; el shuffle de ExoPlayer siempre está apagado).
         * Lo escribe MediaBrowserViewModel; null hasta que la pantalla de la app existe.
         */
        val shuffleEnabled = MutableStateFlow<Boolean?>(null)
        /**
         * Listas inteligentes ya calculadas (nombre → ids), las publica MediaBrowserViewModel. Vacío mientras la
         * pantalla de la app no ha existido en este proceso (solo el servicio): las reglas viven en el ViewModel.
         */
        val smartPlaylists = MutableStateFlow<Map<String, List<Long>>>(emptyMap())
        /** Peticiones del escritorio para cambiar el aleatorio; MediaBrowserViewModel las atiende con toggleShuffle(). */
        val shuffleToggleRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)

        /** Equipos conectados ahora mismo (nombres). */
        val connectedClients = MutableStateFlow<List<String>>(emptyList())

        fun isEnabled(context: Context) =
            PreferenceConstants.getSettingsPrefs(context).getBoolean(KEY_ENABLED, false)

        /** Equipos emparejados (token → nombre). */
        fun pairedDevices(context: Context): Map<String, String> =
            (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_TOKENS, emptySet()) ?: emptySet())
                .mapNotNull { entry -> entry.split("|", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()

        fun forgetAllDevices(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TOKENS).apply()
        }

        /** IPv4 de la red local, para mostrarla en Ajustes y escribirla a mano en el escritorio. */
        fun localIpAddress(context: Context): String? {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
            return props.linkAddresses.map { it.address }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }
    }

    private val random = SecureRandom()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sockets = CopyOnWriteArraySet<ClientSocket>()
    // Los mensajes salen del hilo principal (eventos del Player): escribir en el socket ahí lanza
    // NetworkOnMainThreadException, así que todo envío pasa por este hilo, en orden
    private val sendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var server: Server? = null
    private var tickJob: Job? = null
    private var playsJob: Job? = null
    private var lyricsJob: Job? = null
    private var shuffleJob: Job? = null
    private var smartJob: Job? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    // Tema de la app (settings_prefs/app_theme): el escritorio lo copia si está en "Igual que el teléfono"
    private fun themeJson() = JSONObject().put("type", "theme")
        .put("theme", PreferenceConstants.getSettingsPrefs(context).getString("app_theme", "cyberpunk") ?: "cyberpunk")
    // Referencia fuerte: SharedPreferences solo guarda los oyentes con referencias débiles
    private val themeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "app_theme") broadcast(themeJson())
    }

    private val telegram = RemoteTelegramBridge(context, scope) { broadcast(it) }
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (sockets.isEmpty()) return
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                broadcast(buildQueue(player))
            }
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED
                )
            ) {
                broadcast(buildState(player))
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                player.currentMediaItem?.mediaId?.toLongOrNull()?.let { sendLyrics(it, null) }
            }
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start() {
        if (server != null) return
        pairingCode.value = newPairingCode()
        try {
            server = Server().also { it.start(0, true) } // sin timeout: el WebSocket puede estar inactivo minutos
        } catch (e: IOException) {
            TelemetryLogger.logWarn(context, "RemoteControl", "No se pudo abrir el puerto $PORT: ${e.message}")
            server = null
            pairingCode.value = null
            return
        }
        playerProvider()?.addListener(playerListener)
        registerNsd()
        telegram.start()
        PreferenceConstants.getSettingsPrefs(context).registerOnSharedPreferenceChangeListener(themeListener)
        _running.value = true
        // Reproducciones ya guardadas en Room: el escritorio actualiza Inicio (recientes, más escuchadas) sin recargar
        playsJob = scope.launch {
            com.kevshupp.kevmusicplayer.playback.PlaybackService.playCounted.collect { (id, at) ->
                broadcast(JSONObject().put("type", "played").put("id", id).put("at", at))
            }
        }
        shuffleJob = scope.launch(Dispatchers.Main) {
            shuffleEnabled.collect { playerProvider()?.let { broadcast(buildState(it)) } }
        }
        // Cambian con cada reproducción (más escuchadas, historial): se agrupan y se envían sin recargar la biblioteca
        smartJob = scope.launch(Dispatchers.Main) {
            smartPlaylists.drop(1).debounce(2000).collect { broadcast(JSONObject().put("type", "smartPlaylists").put("playlists", smartPlaylistsJson())) }
        }
        lyricsJob = scope.launch(Dispatchers.Main) {
            lyricsChanged.collect { (id, lyrics, translated) ->
                if (sockets.isEmpty() || playerProvider()?.currentMediaItem?.mediaId?.toLongOrNull() != id) return@collect
                broadcast(
                    JSONObject().put("type", "lyrics").put("id", id)
                        .put("lyrics", lyrics ?: "").put("translated", translated ?: "")
                )
            }
        }
        // Mientras suena, la posición viaja cada segundo (el escritorio interpola entre medias)
        tickJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(1000)
                val player = playerProvider() ?: continue
                if (sockets.isNotEmpty() && player.isPlaying) broadcast(buildState(player))
            }
        }
    }

    fun stop() {
        tickJob?.cancel()
        tickJob = null
        playsJob?.cancel()
        playsJob = null
        lyricsJob?.cancel()
        lyricsJob = null
        shuffleJob?.cancel()
        smartJob?.cancel()
        smartJob = null
        shuffleJob = null
        playerProvider()?.removeListener(playerListener)
        unregisterNsd()
        telegram.stop()
        PreferenceConstants.getSettingsPrefs(context).unregisterOnSharedPreferenceChangeListener(themeListener)
        sockets.forEach { runCatching { it.close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "stop", false) } }
        sockets.clear()
        server?.stop()
        server = null
        pairingCode.value = null
        connectedClients.value = emptyList()
        _running.value = false
    }

    // ---------- Mensajes salientes ----------

    private fun broadcast(json: JSONObject) {
        val text = json.toString()
        sockets.filter { it.authorized }.forEach { s -> s.post(text) }
    }

    private fun audioManager() = context.getSystemService(AudioManager::class.java)

    /** Debe llamarse en el hilo principal (acceso al Player). */
    private fun buildState(player: Player): JSONObject {
        val item = player.currentMediaItem
        val md = item?.mediaMetadata
        val am = audioManager()
        return JSONObject()
            .put("type", "state")
            .put("id", item?.mediaId?.toLongOrNull() ?: 0L)
            .put("title", md?.title?.toString() ?: "")
            .put("artist", md?.artist?.toString() ?: "")
            .put("album", md?.albumTitle?.toString() ?: "")
            .put("duration", player.duration.takeIf { it > 0 } ?: 0L)
            .put("position", player.currentPosition.coerceAtLeast(0L))
            .put("playing", player.isPlaying)
            .put("buffering", player.playbackState == Player.STATE_BUFFERING)
            .put("index", player.currentMediaItemIndex)
            .put("repeat", player.repeatMode)
            .put("shuffle", shuffleEnabled.value ?: PreferenceConstants.getPlaybackPrefs(context).getBoolean(PreferenceConstants.KEY_LAST_SHUFFLE_ENABLED, false))
            .put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("volumeMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            .put("ts", System.currentTimeMillis())
    }

    private fun buildQueue(player: Player): JSONObject {
        val items = JSONArray()
        for (i in 0 until player.mediaItemCount) {
            val it = player.getMediaItemAt(i)
            items.put(
                JSONObject()
                    .put("id", it.mediaId.toLongOrNull() ?: 0L)
                    .put("title", it.mediaMetadata.title?.toString() ?: "")
                    .put("artist", it.mediaMetadata.artist?.toString() ?: "")
            )
        }
        return JSONObject().put("type", "queue").put("index", player.currentMediaItemIndex).put("items", items)
    }

    private fun sendLyrics(id: Long, target: ClientSocket?) {
        scope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).audioDao()
            val lyrics = if (id > 0) runCatching { dao.getLyricsById(id) }.getOrNull() else null
            val translated = if (id > 0) runCatching { dao.getTranslatedLyricsById(id) }.getOrNull() else null
            val json = JSONObject().put("type", "lyrics").put("id", id)
                .put("lyrics", lyrics ?: "").put("translated", translated ?: "")
            if (target != null) target.post(json.toString()) else broadcast(json)
        }
    }

    private fun sendSnapshot(socket: ClientSocket) {
        mainHandler.post {
            val player = playerProvider() ?: return@post
            socket.post(themeJson().toString())
            telegram.snapshot().forEach { socket.post(it.toString()) }
            socket.post(buildQueue(player).toString())
            socket.post(buildState(player).toString())
            player.currentMediaItem?.mediaId?.toLongOrNull()?.let { sendLyrics(it, socket) }
        }
    }

    // ---------- Comandos ----------

    private fun handleCommand(socket: ClientSocket, msg: JSONObject) {
        if (telegram.handle(msg) { socket.post(it.toString()) }) return
        when (msg.optString("type")) {
            "volume" -> {
                val am = audioManager()
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, msg.optInt("value").coerceIn(0, max), 0)
                mainHandler.post { playerProvider()?.let { broadcast(buildState(it)) } }
            }
            "getLyrics" -> sendLyrics(msg.optLong("id"), socket)
            "playIds", "enqueue" -> scope.launch {
                val ids = msg.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optLong(it) } } ?: emptyList()
                val items = withContext(Dispatchers.IO) { mediaItemsFor(ids) }
                if (items.isEmpty()) return@launch
                withContext(Dispatchers.Main) {
                    val player = playerProvider() ?: return@withContext
                    if (msg.optString("type") == "playIds") {
                        player.shuffleModeEnabled = false
                        player.setMediaItems(items, msg.optInt("index").coerceIn(0, items.size - 1), 0L)
                        player.prepare()
                        player.play()
                    } else if (msg.optBoolean("next") && player.mediaItemCount > 0) {
                        player.addMediaItems(player.currentMediaItemIndex + 1, items)
                    } else {
                        player.addMediaItems(items)
                        if (player.mediaItemCount == items.size) player.prepare()
                    }
                }
            }
            else -> mainHandler.post {
                val player = playerProvider() ?: return@post
                when (msg.optString("type")) {
                    "toggle" -> if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
                    "play" -> { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
                    "pause" -> player.pause()
                    "next" -> onSkip(true)
                    "prev" -> onSkip(false)
                    "seek" -> player.seekTo(msg.optLong("position").coerceAtLeast(0L))
                    "repeat" -> player.repeatMode = msg.optInt("mode").coerceIn(0, 2)
                    "shuffle" -> toggleShuffle(player)
                    "jump" -> msg.optInt("index", -1).takeIf { it in 0 until player.mediaItemCount }?.let { player.seekTo(it, 0L); player.play() }
                    "remove" -> msg.optInt("index", -1).takeIf { it in 0 until player.mediaItemCount && it != player.currentMediaItemIndex }?.let { player.removeMediaItem(it) }
                    "move" -> {
                        val from = msg.optInt("from", -1)
                        val to = msg.optInt("to", -1)
                        if (from in 0 until player.mediaItemCount && to in 0 until player.mediaItemCount) player.moveMediaItem(from, to)
                    }
                }
            }
        }
    }

    /**
     * Con la pantalla de la app viva, el ViewModel baraja (y al apagar restaura el orden original).
     * Sin ella solo se puede barajar lo que viene después; al apagar, el orden barajado se queda.
     */
    private fun toggleShuffle(player: Player) {
        if (shuffleToggleRequests.subscriptionCount.value > 0) {
            shuffleToggleRequests.tryEmit(Unit)
            return
        }
        val prefs = PreferenceConstants.getPlaybackPrefs(context)
        val enable = !(shuffleEnabled.value ?: prefs.getBoolean(PreferenceConstants.KEY_LAST_SHUFFLE_ENABLED, false))
        if (enable) {
            val start = player.currentMediaItemIndex + 1
            val count = player.mediaItemCount - start
            if (count > 1) {
                val upcoming = (start until player.mediaItemCount).map { player.getMediaItemAt(it) }.shuffled()
                player.replaceMediaItems(start, player.mediaItemCount, upcoming)
            }
        }
        prefs.edit().putBoolean(PreferenceConstants.KEY_LAST_SHUFFLE_ENABLED, enable).apply()
        shuffleEnabled.value = enable
    }

    private suspend fun mediaItemsFor(ids: List<Long>): List<MediaItem> {
        val dao = AppDatabase.getDatabase(context).audioDao()
        val byId = dao.getAllAudioFilesLightweight().associateBy { it.id }
        val limit = PreferenceConstants.getSettingsPrefs(context).getInt("ipc_queue_limit", 1500).coerceIn(100, 1500)
        return ids.asSequence()
            .mapNotNull { byId[it] ?: OnDemandTracks.audioFileFor(context, it) }
            .take(limit)
            .map { buildMediaItem(it) }
            .toList()
    }

    private fun buildMediaItem(file: AudioFile): MediaItem {
        val trackUri = Uri.parse(file.uriString)
        return MediaItem.Builder()
            .setMediaId(file.id.toString())
            .setUri(trackUri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(trackUri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(file.title)
                    .setArtist(file.artist)
                    .setAlbumTitle(file.album)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()
    }

    // ---------- Emparejamiento ----------

    private fun newPairingCode() = (100000 + random.nextInt(900000)).toString()

    private fun isValidToken(token: String) = token.isNotBlank() && pairedDevices(context).containsKey(token)

    private fun pair(code: String, name: String): String? {
        if (code != pairingCode.value) return null
        val bytes = ByteArray(24).also { random.nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(KEY_TOKENS, emptySet()) ?: emptySet()).toMutableSet()
        set.add("$token|${name.replace("|", " ").take(60)}")
        prefs.edit().putStringSet(KEY_TOKENS, set).apply()
        // Un código sirve una sola vez
        pairingCode.value = newPairingCode()
        TelemetryLogger.logInfo(context, "RemoteControl", "Equipo emparejado: $name")
        return token
    }

    private fun updateConnectedClients() {
        connectedClients.value = sockets.filter { it.authorized }.map { it.clientName }
    }

    // ---------- mDNS ----------

    private fun registerNsd() {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "KevMusic ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                TelemetryLogger.logWarn(context, "RemoteControl", "mDNS no registrado ($errorCode)")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onSuccess { nsdListener = listener }
    }

    private fun unregisterNsd() {
        val listener = nsdListener ?: return
        nsdListener = null
        runCatching { context.getSystemService(NsdManager::class.java)?.unregisterService(listener) }
    }

    // ---------- HTTP + WebSocket ----------

    private inner class Server : NanoWSD(PORT) {
        override fun openWebSocket(handshake: IHTTPSession): WebSocket = ClientSocket(handshake)

        override fun serveHttp(session: IHTTPSession): Response {
            val token = session.parameters["token"]?.firstOrNull() ?: ""
            if (!isValidToken(token)) return text(Response.Status.UNAUTHORIZED, "unauthorized")
            val uri = session.uri
            return try {
                when {
                    uri == "/library" -> json(runBlocking(Dispatchers.IO) { buildLibrary() })
                    uri == "/upload" && session.method == Method.PUT -> receiveUpload(session)
                    uri.startsWith("/art/") -> serveArt(uri.removePrefix("/art/").toLongOrNull(), session.parameters["size"]?.firstOrNull())
                    else -> text(Response.Status.NOT_FOUND, "not found")
                }
            } catch (e: Exception) {
                text(Response.Status.INTERNAL_ERROR, e.message ?: "error")
            }
        }

        private fun text(status: Response.Status, body: String) = newFixedLengthResponse(status, "text/plain", body)

        private fun json(body: String): Response {
            val bytes = body.toByteArray()
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", ByteArrayInputStream(bytes), bytes.size.toLong())
        }

        /**
         * Canción enviada por el PC: se recibe en la caché (comprobando tamaño y SHA-256), se guarda en
         * `<carpeta principal>/Artista/Álbum/Artista - Título.ext` según sus etiquetas, como las descargas de
         * Telegram, y se escanea. El cuerpo se lee a mano: `parseBody()` de NanoHTTPD lo convertiría en texto.
         */
        private fun receiveUpload(session: IHTTPSession): Response {
            val length = session.headers["content-length"]?.toLongOrNull()
                ?: return text(Response.Status.BAD_REQUEST, "Falta Content-Length")
            if (length <= 0 || length > MAX_UPLOAD_BYTES) return text(Response.Status.BAD_REQUEST, "Tamaño no válido")
            val name = File(session.parameters["name"]?.firstOrNull().orEmpty()).name
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in UPLOAD_EXTENSIONS) return text(Response.Status.BAD_REQUEST, "Formato no admitido")

            val dir = File(context.cacheDir, "remote_upload").apply { mkdirs() }
            val temp = File(dir, "${System.nanoTime()}.$ext")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val input = session.inputStream
                temp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var left = length
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("Conexión cortada")
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        left -= n
                    }
                }
                val expected = session.headers["x-sha256"]?.lowercase()
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (expected != null && expected != actual) return text(Response.Status.BAD_REQUEST, "El archivo llegó dañado")

                val hint = { key: String -> session.parameters[key]?.firstOrNull().orEmpty() }
                val tags = readTags(temp)
                val track = DeezerTrack(
                    id = 0,
                    title = tags[0].ifBlank { name.substringBeforeLast('.') },
                    artist = tags[1].ifBlank { hint("artist") },
                    album = tags[2].ifBlank { hint("album") },
                    durationSec = 0, coverSmall = "", coverXl = "", link = ""
                )
                val saved = TelegramDownloadQueue.saveToMusicFolder(context, temp, track)
                runBlocking { TelegramDownloadQueue.scanFile(context, saved) }
                // El escaneo de la biblioteca lo hace el ViewModel (con la app cerrada, entra en el siguiente)
                Handler(Looper.getMainLooper()).post { TelegramDownloadQueue.onSongSaved?.invoke() }
                TelemetryLogger.logInfo(context, "Remote", "Recibida desde el PC: ${saved.absolutePath}")
                return json(JSONObject().put("ok", true).put("path", saved.absolutePath).put("title", track.title).put("artist", track.artist).toString())
            } finally {
                temp.delete()
            }
        }

        /** Título, artista (o el del álbum) y álbum de las etiquetas. */
        private fun readTags(file: File): List<String> {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(file.absolutePath)
                listOf(
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim(),
                    (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)).orEmpty().trim(),
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim()
                )
            } catch (e: Exception) {
                listOf("", "", "")
            } finally {
                r.release()
            }
        }

        private fun serveArt(id: Long?, size: String?): Response {
            id ?: return text(Response.Status.BAD_REQUEST, "bad id")
            val song = runBlocking(Dispatchers.IO) {
                AppDatabase.getDatabase(context).audioDao().getAudioFileById(id)
            } ?: OnDemandTracks.audioFileFor(context, id) ?: return text(Response.Status.NOT_FOUND, "no song")
            val bitmap = loadAlbumArtBitmap(context, song.uriString) ?: return text(Response.Status.NOT_FOUND, "no art")
            val target = if (size == "small") 160 else 640
            val scaled = if (bitmap.width > target) {
                android.graphics.Bitmap.createScaledBitmap(bitmap, target, (bitmap.height * target / bitmap.width).coerceAtLeast(1), true)
            } else bitmap
            val out = ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            // Nunca reciclar el original: viene de la caché de carátulas
            if (scaled !== bitmap) scaled.recycle()
            val bytes = out.toByteArray()
            return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
                .apply { addHeader("Cache-Control", "max-age=3600") }
        }
    }

    private fun smartPlaylistsJson(known: Set<Long>? = null) = JSONArray().apply {
        smartPlaylists.value.toSortedMap().forEach { (name, ids) ->
            val valid = if (known != null) ids.filter { it in known } else ids
            put(JSONObject().put("name", name).put("smart", true).put("songs", JSONArray(valid)))
        }
    }

    private suspend fun buildLibrary(): String {
        val songs = AppDatabase.getDatabase(context).audioDao().getAllAudioFilesLightweight()
        val songsJson = JSONArray()
        songs.forEach { s ->
            songsJson.put(
                JSONObject()
                    .put("id", s.id).put("title", s.title).put("artist", s.artist).put("album", s.album)
                    .put("duration", s.duration).put("track", s.track).put("year", s.year)
                    .put("genre", s.genre).put("plays", s.playCount).put("added", s.dateAdded)
                    .put("last", s.lastPlayed)
            )
        }
        // Solo listas normales: las inteligentes se calculan en la app y viajan como canciones sueltas
        val prefs = PreferenceConstants.getPlaylistsPrefs(context)
        val known = songs.mapTo(HashSet()) { it.id }
        val playlistsJson = JSONArray()
        (prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()).sorted().forEach { name ->
            val ids = (prefs.getString("playlist_$name", "") ?: "").split(",")
                .mapNotNull { it.trim().toLongOrNull() }.filter { it in known }
            playlistsJson.put(JSONObject().put("name", name).put("songs", JSONArray(ids)))
        }
        smartPlaylistsJson(known).let { smart -> for (i in 0 until smart.length()) playlistsJson.put(smart.get(i)) }
        return JSONObject()
            .put("version", PROTOCOL_VERSION)
            .put("device", Build.MODEL)
            .put("songs", songsJson)
            .put("playlists", playlistsJson)
            .toString()
    }

    private inner class ClientSocket(handshake: NanoHTTPD.IHTTPSession) : NanoWSD.WebSocket(handshake) {
        @Volatile var authorized = false
        @Volatile var clientName = "PC"

        fun post(text: String) {
            sendExecutor.execute { runCatching { send(text) } }
        }

        override fun onOpen() {
            sockets.add(this)
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            sockets.remove(this)
            updateConnectedClients()
        }

        override fun onMessage(frame: NanoWSD.WebSocketFrame) {
            val msg = runCatching { JSONObject(frame.textPayload) }.getOrNull() ?: return
            if (!authorized) {
                clientName = msg.optString("name", "PC").ifBlank { "PC" }
                val token = when (msg.optString("type")) {
                    "pair" -> pair(msg.optString("code"), clientName)
                    "hello" -> msg.optString("token").takeIf { isValidToken(it) }
                    else -> null
                }
                if (token == null) {
                    runCatching { send(JSONObject().put("type", "auth").put("ok", false).toString()) }
                    return
                }
                authorized = true
                runCatching {
                    send(
                        JSONObject().put("type", "auth").put("ok", true).put("token", token)
                            .put("device", Build.MODEL).put("version", PROTOCOL_VERSION).toString()
                    )
                }
                updateConnectedClients()
                sendSnapshot(this)
                return
            }
            handleCommand(this, msg)
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame?) {}

        override fun onException(exception: IOException?) {
            sockets.remove(this)
            updateConnectedClients()
        }
    }
}
