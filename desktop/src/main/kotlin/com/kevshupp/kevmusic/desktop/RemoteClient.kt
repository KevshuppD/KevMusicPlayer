package com.kevshupp.kevmusic.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

sealed interface ConnStatus {
    data object Disconnected : ConnStatus
    data class Connecting(val host: String) : ConnStatus
    data class Connected(val device: String) : ConnStatus
    data class Failed(val message: String) : ConnStatus
}

/**
 * Cliente del servidor de control remoto de la app Android (`RemoteControlServer`).
 * WebSocket para estado y comandos; HTTP para la biblioteca y las carátulas.
 */
class RemoteClient(private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow<ConnStatus>(ConnStatus.Disconnected)
    val status: StateFlow<ConnStatus> = _status
    // Lo que dice el teléfono; lo público ([state], [queue], [lyrics], [library]) es esto o el modo local
    private val _state = MutableStateFlow<PlayerState?>(null)
    private val _queue = MutableStateFlow(QueueState())
    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    private val _library = MutableStateFlow<Library?>(null)

    /** Modo local: la música del PC suena en el PC (ver [LocalPlayer]). */
    val local = com.kevshupp.kevmusic.desktop.local.LocalPlayer(scope)
    private val _onPc = MutableStateFlow(Settings.playOnPc)
    val onPc: StateFlow<Boolean> = _onPc

    val state: StateFlow<PlayerState?> = kotlinx.coroutines.flow.combine(_onPc, _state, local.state) { pc, phone, pcState -> if (pc) pcState else phone }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    val queue: StateFlow<QueueState> = kotlinx.coroutines.flow.combine(_onPc, _queue, local.queue) { pc, phone, pcQueue -> if (pc) pcQueue else phone }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, QueueState())
    val lyrics: StateFlow<Lyrics?> = kotlinx.coroutines.flow.combine(_onPc, _lyrics, local.lyrics) { pc, phone, pcLyrics -> if (pc) pcLyrics else phone }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    /** En el modo local, la biblioteca del PC con las listas del teléfono (emparejadas por artista y título). */
    val library: StateFlow<Library?> = kotlinx.coroutines.flow.combine(_onPc, _library, local.library, CloudLibrary.library) { pc, phone, pcLib, cloud ->
        if (!pc) phone else pcLib?.let { withPhoneSongs(it, phone ?: cloud) }
    }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    init {
        local.resolver = { song -> OnDemand.resolve(this, song) }
        if (_onPc.value) local.start()
        // Se cortó el teléfono mientras sonaba en él: tras 8 s sin volver (no por un corte breve del Wi-Fi), pasa
        // al PC con la misma cola; lo que el PC no tiene suena bajo demanda. Al volver, [handle] regresa al teléfono
        // si en el PC no suena nada.
        scope.launch {
            var wasConnected = false
            status.collect { st ->
                if (st is ConnStatus.Connected) { wasConnected = true; return@collect }
                if (!wasConnected || _onPc.value || userClosed) return@collect
                wasConnected = false
                delay(8000)
                if (_status.value !is ConnStatus.Connected && !_onPc.value) {
                    val hadMusic = _state.value?.id?.let { it != 0L } == true
                    autoPc = true
                    switchTo(true, persist = false)
                    _notice.value = Notice(
                        if (hadMusic) "Teléfono sin conexión: sigue en este PC (lo que no tienes aquí suena bajo demanda)"
                        else "Teléfono sin conexión: suena la música de este PC"
                    )
                }
            }
        }
    }
    private val _libraryLoading = MutableStateFlow(false)
    val libraryLoading: StateFlow<Boolean> = _libraryLoading

    /** Tema elegido en el teléfono (settings_prefs/app_theme). */
    private val _phoneTheme = MutableStateFlow("cyberpunk")
    val phoneTheme: StateFlow<String> = _phoneTheme

    private val _telegram = MutableStateFlow(TelegramStatus(false, "Conectando con el teléfono…"))
    val telegram: StateFlow<TelegramStatus> = _telegram
    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads

    // Peticiones a Deezer hechas por el teléfono, esperando su tgResult
    private val pending = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.CompletableDeferred<DzResult>>()
    private val nextReq = java.util.concurrent.atomic.AtomicLong(1)
    // Ediciones de la biblioteca esperando su editResult (null = bien, si no el motivo)
    private val pendingEdits = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.CompletableDeferred<String?>>()

    /** Aviso breve tras editar la biblioteca (lista creada, canciones eliminadas o el error). */
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice

    private var socket: WebSocket? = null
    private var device: SavedDevice? = null
    private var reconnectJob: Job? = null
    @Volatile private var userClosed = false

    private val clientName = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.name") + "-pc")

    // ---------- Conexión ----------

    /** Empareja con el código de 6 cifras que muestra el teléfono en Ajustes › Sistema. */
    fun pair(host: String, port: Int, code: String) = open(host, port, JSONObject().put("type", "pair").put("code", code.trim()))

    /** Reconecta con el token guardado. */
    fun resume(saved: SavedDevice) {
        device = saved
        loadCachedLibrary(saved)
        trimArtDiskCache()
        open(saved.host, saved.port, JSONObject().put("type", "hello").put("token", saved.token))
    }

    /**
     * Sin conexión no se sabe qué hace el teléfono: se deja la canción en pausa en la posición estimada, para que
     * la letra, la barra y MPRIS no sigan avanzando solos. El siguiente `state` al reconectar trae la real.
     */
    private fun freezeState() {
        val now = System.currentTimeMillis()
        _state.value = _state.value?.let { if (it.playing) it.copy(position = it.positionAt(now), playing = false, receivedAt = now) else it }
    }

    fun disconnect(forget: Boolean) {
        freezeState()
        userClosed = true
        reconnectJob?.cancel()
        socket?.close(1000, "bye")
        socket = null
        if (forget) {
            device?.let { Config.forget(it) }
            device = null
            _library.value = null
        }
        _status.value = ConnStatus.Disconnected
    }

    private fun open(host: String, port: Int, hello: JSONObject) {
        userClosed = false
        reconnectJob?.cancel()
        socket?.cancel()
        _status.value = ConnStatus.Connecting(host)
        hello.put("name", clientName)
        // Protocolo del PC: 7 = acepta "handoff" (sonar en el PC elegido desde el teléfono)
        hello.put("proto", 7)
        val request = Request.Builder().url("ws://$host:$port/ws").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket) return
                runCatching { handle(host, port, JSONObject(text)) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== socket) return
                onLost("No se pudo conectar con $host:$port (${t.message ?: t.javaClass.simpleName})")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== socket) return
                onLost("El teléfono cerró la conexión")
            }
        })
    }

    /** Con un teléfono ya emparejado se reintenta cada 3 s (pantalla apagada, Wi-Fi caída, app cerrada). */
    private fun onLost(message: String) {
        freezeState()
        if (userClosed) return
        val saved = device
        _status.value = ConnStatus.Failed(message)
        if (saved != null) {
            reconnectJob?.cancel()
            // Con varios emparejados se van alternando: suena el que esté abierto (release, debug u otro teléfono)
            val all = Config.all()
            val next = all.getOrNull((all.indexOfFirst { it.key == saved.key } + 1) % all.size.coerceAtLeast(1)) ?: saved
            reconnectJob = scope.launch {
                delay(if (all.size > 1) 1500 else 3000)
                if (!userClosed) resume(next)
            }
        }
    }

    // Mientras suena, el teléfono manda `state` cada segundo: sin noticias en 4 s (Wi-Fi caído sin cerrar el
    // socket), se congela la posición aunque OkHttp aún no haya notado la caída
    @Volatile private var lastStateAt = 0L
    private val stallWatch = scope.launch {
        while (true) {
            delay(1000)
            val st = _state.value
            if (st != null && st.playing && lastStateAt > 0 && System.currentTimeMillis() - lastStateAt > 4000) freezeState()
        }
    }

    private fun handle(host: String, port: Int, msg: JSONObject) {
        if (msg.optString("type") == "state") lastStateAt = System.currentTimeMillis()
        when (msg.optString("type")) {
            "auth" -> {
                if (!msg.optBoolean("ok")) {
                    // Código erróneo o token revocado ("Olvidar todos" en el teléfono)
                    val rejected = device
                    val hadToken = rejected != null
                    userClosed = true
                    socket?.close(1000, "auth")
                    if (rejected != null) {
                        // Solo se olvida este emparejamiento; si hay otros (app debug/release, otro teléfono), se prueban
                        Config.forget(rejected)
                        device = null
                        Config.load()?.let { resume(it); return }
                    }
                    _status.value = ConnStatus.Failed(
                        if (hadToken) "El teléfono ya no reconoce este equipo. Vuelve a emparejar."
                        else "Código incorrecto. Revisa el que muestra el teléfono."
                    )
                    return
                }
                val name = msg.optString("device", "Teléfono")
                phoneProtocol = msg.optInt("version", 1)
                val saved = SavedDevice(host, port, msg.getString("token"), name)
                device = saved
                Config.save(saved)
                _status.value = ConnStatus.Connected(name)
                // Volvió el teléfono tras el cambio automático al PC: a él, salvo que en el PC ya suene algo
                if (autoPc) {
                    autoPc = false
                    if (_onPc.value && local.state.value?.playing != true) {
                        scope.launch { switchTo(false, persist = false, transfer = false) }
                        _notice.value = Notice("Teléfono conectado: vuelve a sonar en el teléfono")
                    }
                }
                // Siempre: mientras estaba desconectado pudo cambiar (escaneo, organizar, migrar)
                refreshLibrary()
            }
            "state" -> _state.value = PlayerState(
                id = msg.optLong("id"),
                title = msg.optString("title"),
                artist = msg.optString("artist"),
                album = msg.optString("album"),
                duration = msg.optLong("duration"),
                position = msg.optLong("position"),
                playing = msg.optBoolean("playing"),
                buffering = msg.optBoolean("buffering"),
                index = msg.optInt("index", -1),
                repeat = msg.optInt("repeat"),
                shuffle = msg.optBoolean("shuffle"),
                volume = msg.optInt("volume"),
                volumeMax = msg.optInt("volumeMax", 15).coerceAtLeast(1),
                receivedAt = System.currentTimeMillis()
            )
            "queue" -> {
                val arr = msg.optJSONArray("items") ?: JSONArray()
                _queue.value = QueueState(
                    index = msg.optInt("index", -1),
                    items = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        QueueItem(o.optLong("id"), o.optString("title"), o.optString("artist"))
                    }
                )
            }
            "played" -> {
                // El teléfono contó una reproducción: Inicio se actualiza sin pedir otra vez la biblioteca
                val id = msg.optLong("id")
                val at = msg.optLong("at")
                _library.value?.let { lib ->
                    scope.launch(Dispatchers.Default) {
                        val next = lib.copy(songs = lib.songs.map { if (it.id == id) it.copy(plays = it.plays + 1, lastPlayed = at) else it })
                        next.warm()
                        _library.compareAndSet(lib, next)
                    }
                }
            }
            "libraryChanged" -> refreshLibrary()
            "editResult" -> pendingEdits.remove(msg.optLong("req"))?.complete(
                if (msg.optBoolean("ok")) null else msg.optString("error").ifBlank { "No se pudo hacer el cambio" }
            )
            // Las inteligentes cambian con cada reproducción: se sustituyen sin volver a pedir la biblioteca
            "smartPlaylists" -> _library.value?.let { lib ->
                val smart = parsePlaylists(msg.optJSONArray("playlists") ?: JSONArray())
                    .map { p -> p.copy(songIds = p.songIds.filter { it in lib.byId }) }
                _library.value = lib.copy(playlists = lib.playlists.filterNot { it.smart } + smart)
            }
            // Protocolo 7: cuenta de Google del teléfono; si el PC no tiene esa sesión, pide un token para abrirla
            "cloudAccount" -> {
                phoneCloud = msg
                TelegramPc.phoneBot = msg.optString("tgBot")
                _phoneCloudUid.value = msg.optString("uid").takeIf { it.isNotEmpty() }
                if (CloudSession.needsLink(msg.optString("uid"), msg.optBoolean("stats", true))) cmd("cloudLinkRequest")
            }
            "cloudLink" -> CloudSession.link(
                msg.optString("idToken"), phoneCloud?.optString("email").orEmpty(),
                phoneCloud?.optString("name").orEmpty(), phoneCloud?.optString("photo").orEmpty()
            )
            // Desde el selector de dispositivos del teléfono: lo que suena pasa a este PC
            "handoff" -> scope.launch(Dispatchers.Main) {
                setPlayOnPc(true)
                _notice.value = Notice("Ahora suena en este PC")
            }
            "slskAccount" -> _slskAccount.value = msg.optString("username") to msg.optString("password")
            "theme" -> _phoneTheme.value = msg.optString("theme", "cyberpunk")
            "tgStatus" -> _telegram.value = TelegramStatus(msg.optBoolean("ready"), msg.optString("reason"))
            "tgQueue" -> {
                val arr = msg.optJSONArray("items") ?: JSONArray()
                _downloads.value = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    DownloadItem(
                        parseTrack(o),
                        when (o.optString("status")) {
                            "requesting" -> DlStatus.Requesting
                            "downloading" -> DlStatus.Downloading
                            "done" -> DlStatus.Done
                            "failed" -> DlStatus.Failed
                            else -> DlStatus.Queued
                        },
                        o.optDouble("progress", 0.0).toFloat(),
                        o.optString("reason")
                    )
                }
            }
            "tgResult" -> pending.remove(msg.optLong("req"))?.complete(
                DzResult(
                    tracks = msg.optJSONArray("tracks")?.let { a -> (0 until a.length()).map { parseTrack(a.getJSONObject(it)) } } ?: emptyList(),
                    albums = msg.optJSONArray("albums")?.let { a ->
                        (0 until a.length()).map { i ->
                            val o = a.getJSONObject(i)
                            DzAlbum(o.getLong("id"), o.optString("title"), o.optString("artist"), o.optString("cover"),
                                o.optInt("tracks"), o.optString("type"), o.optString("year"), o.optInt("owned"))
                        }
                    } ?: emptyList(),
                    artists = msg.optJSONArray("artists")?.let { a ->
                        (0 until a.length()).map { i ->
                            val o = a.getJSONObject(i)
                            DzArtist(o.getLong("id"), o.optString("name"), o.optString("picture"), o.optInt("albums"), o.optInt("fans"))
                        }
                    } ?: emptyList(),
                    error = msg.optString("error").ifBlank { null }
                )
            )
            "lyrics" -> _lyrics.value = Lyrics.parse(msg.optLong("id"), msg.optString("lyrics"), msg.optString("translated"))
        }
    }

    // ---------- Comandos ----------

    /** Cuenta de Soulseek guardada en el teléfono (viaja en su copia de la nube); null si no tiene. */
    private val _slskAccount = MutableStateFlow<Pair<String, String>?>(null)
    val slskAccount: StateFlow<Pair<String, String>?> = _slskAccount

    /** Guarda la cuenta en el teléfono (protocolo 4+); los anteriores ignoran el mensaje. */
    fun saveSlskAccount(user: String, pass: String) {
        if (phoneProtocol < 4 || _slskAccount.value == (user to pass)) return
        cmd("slskAccountSave") { put("username", user); put("password", pass) }
        _slskAccount.value = user to pass
    }

    private fun send(json: JSONObject) {
        socket?.send(json.toString())
    }

    private fun cmd(type: String, build: JSONObject.() -> Unit = {}) = send(JSONObject().put("type", type).apply(build))

    fun toggle() { if (_onPc.value) local.toggle() else cmd("toggle") }
    fun next() { if (_onPc.value) local.next() else cmd("next") }
    fun previous() { if (_onPc.value) local.previous() else cmd("prev") }

    fun seek(positionMs: Long) {
        if (_onPc.value) return local.seek(positionMs)
        cmd("seek") { put("position", positionMs) }
        // Respuesta inmediata en la barra; el teléfono confirma con el siguiente "state"
        _state.value = _state.value?.copy(position = positionMs, receivedAt = System.currentTimeMillis())
    }

    fun setVolume(value: Int) {
        if (_onPc.value) return local.setVolume(value)
        cmd("volume") { put("value", value) }
        _state.value = _state.value?.copy(volume = value)
    }

    fun toggleShuffle() {
        if (_onPc.value) return local.toggleShuffle()
        cmd("shuffle")
        // Respuesta inmediata; el teléfono confirma con el siguiente "state"
        _state.value = _state.value?.let { it.copy(shuffle = !it.shuffle) }
    }

    fun setRepeat(mode: Int) { if (_onPc.value) local.setRepeat(mode) else cmd("repeat") { put("mode", mode.coerceIn(0, 2)) } }

    /** Carátula guardada en ~/.cache/kevmusic-desktop/art para MPRIS (que pide una ruta file://). */
    suspend fun artFile(id: Long): java.io.File? {
        if (com.kevshupp.kevmusic.desktop.local.LocalLibrary.isLocalId(id)) return withContext(Dispatchers.IO) {
            val t = local.track(id) ?: return@withContext null
            val f = java.io.File(Platform.cacheDir, "art/local_${-id}.jpg")
            if (f.length() > 0) return@withContext f
            com.kevshupp.kevmusic.desktop.local.LocalLibrary.artBytes(t)?.let { b -> f.parentFile.mkdirs(); f.writeBytes(b); f }
        }
        val d = device ?: return null
        val dir = java.io.File(Platform.cacheDir, "art").apply { mkdirs() }
        val file = java.io.File(dir, "$id.jpg")
        if (file.length() > 0) return file
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("http://${d.host}:${d.port}/art/$id?token=${d.token}&size=big").build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val tmp = java.io.File(dir, "$id.jpg.tmp")
                    tmp.writeBytes(resp.body!!.bytes())
                    if (tmp.renameTo(file)) file else null
                }
            }.getOrNull()
        }
    }

    /**
     * Descarga del teléfono el archivo original de una de sus canciones (`/audio/<id>`, protocolo 7) a [dest].
     * false si no está conectado, es anterior o no la tiene.
     */
    fun downloadFromPhone(id: Long, dest: java.io.File): Boolean {
        val d = device ?: return false
        if (phoneProtocol < 7 || _status.value !is ConnStatus.Connected) return false
        return runCatching {
            val req = Request.Builder().url("http://${d.host}:${d.port}/audio/$id?token=${d.token}").build()
            http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val tmp = java.io.File(dest.path + ".part")
                tmp.outputStream().use { resp.body!!.byteStream().copyTo(it) }
                tmp.length() > 0 && tmp.renameTo(dest)
            }
        }.getOrDefault(false)
    }

    /** Versión del protocolo del teléfono; la 2 (app 1.8.0) acepta canciones con `PUT /upload`. */
    @Volatile var phoneProtocol = 0
        private set
    @Volatile private var phoneCloud: JSONObject? = null

    /** Vuelve a pedir la sesión al teléfono (botón de Ajustes); false si no está conectado o es anterior al protocolo 7. */
    fun relinkCloud(): Boolean {
        val uid = phoneCloud?.optString("uid").orEmpty()
        if (phoneProtocol < 7 || socket == null || uid.isEmpty()) return false
        cmd("cloudLinkRequest")
        return true
    }
    /** Cuenta de Google del teléfono conectado (null sin sesión o sin conexión). */
    private val _phoneCloudUid = MutableStateFlow<String?>(null)
    val phoneCloudUid: StateFlow<String?> = _phoneCloudUid

    /** Por qué no se puede enviar una canción al teléfono ahora, o null si se puede. */
    fun uploadBlocker(): String? = when {
        _status.value !is ConnStatus.Connected -> "Esperando a que se conecte el teléfono"
        phoneProtocol < 2 -> "Esperando: actualiza KevMusic en el teléfono a la 1.8.0 para recibir canciones"
        else -> null
    }

    private val uploadHttp by lazy { http.newBuilder().readTimeout(2, TimeUnit.MINUTES).writeTimeout(2, TimeUnit.MINUTES).build() }

    /**
     * Sube una canción al teléfono (`PUT /upload`), que la guarda en su carpeta principal según sus etiquetas.
     * Bloqueante; devuelve la ruta donde quedó.
     */
    fun uploadSong(file: java.io.File, artistHint: String, albumHint: String, onProgress: (Long) -> Unit): String {
        val d = device ?: throw java.io.IOException("No hay teléfono emparejado")
        if (_status.value !is ConnStatus.Connected) throw java.io.IOException("El teléfono no está conectado")
        val sha = java.security.MessageDigest.getInstance("SHA-256").let { md ->
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: okio.BufferedSink) {
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
            }
        }
        val url = okhttp3.HttpUrl.Builder().scheme("http").host(d.host).port(d.port).addPathSegment("upload")
            .addQueryParameter("token", d.token).addQueryParameter("name", file.name)
            .addQueryParameter("artist", artistHint).addQueryParameter("album", albumHint).build()
        val req = Request.Builder().url(url).put(body).header("X-Sha256", sha).build()
        uploadHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 404) throw java.io.IOException("actualiza KevMusic en el teléfono a la 1.8.0 o posterior")
            if (!resp.isSuccessful) throw java.io.IOException(text.ifBlank { "HTTP ${resp.code}" })
            return JSONObject(text).optString("path")
        }
    }

    fun cycleRepeat() {
        if (_onPc.value) return local.cycleRepeat()
        val next = ((_state.value?.repeat ?: 0) + 1) % 3
        cmd("repeat") { put("mode", next) }
    }

    fun playSongs(ids: List<Long>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (ids.isEmpty()) return
        if (_onPc.value) return local.playSongs(ids, startIndex, shuffle)
        val list = if (shuffle) ids.shuffled() else ids
        cmd("playIds") {
            put("ids", JSONArray(list))
            put("index", if (shuffle) 0 else startIndex.coerceIn(0, list.size - 1))
        }
    }

    fun enqueue(ids: List<Long>, playNext: Boolean) {
        if (_onPc.value) return local.enqueue(ids, playNext)
        cmd("enqueue") {
            put("ids", JSONArray(ids))
            put("next", playNext)
        }
    }

    fun jumpTo(index: Int) { if (_onPc.value) local.jumpTo(index) else cmd("jump") { put("index", index) } }
    fun removeFromQueue(index: Int) { if (_onPc.value) local.removeFromQueue(index) else cmd("remove") { put("index", index) } }
    fun moveInQueue(from: Int, to: Int) { if (_onPc.value) local.moveInQueue(from, to) else cmd("move") { put("from", from); put("to", to) } }

    // ---------- Modo local ----------

    /**
     * Cambia dónde suena. Lo que estaba sonando sigue en el otro lado si allí está la misma canción
     * (por artista y título), en la misma posición; el lado que se deja se pausa.
     */
    /** El modo PC lo puso [fallbackToPcIfOffline] (no el usuario): al conectar el teléfono se vuelve a él. */
    @Volatile private var autoPc = false

    /** Elección del usuario (botón, Ajustes): se guarda y anula el cambio automático. */
    fun setPlayOnPc(on: Boolean) {
        autoPc = false
        switchTo(on, persist = true)
    }

    /**
     * Al abrir, si el teléfono guardado no responde: suena en el PC mientras tanto, sin guardarlo (la próxima vez
     * se vuelve a intentar con el teléfono). Al conectar, [handle] vuelve al teléfono si en el PC no suena nada.
     */
    fun fallbackToPcIfOffline() {
        if (_onPc.value || _status.value is ConnStatus.Connected) return
        autoPc = true
        switchTo(true, persist = false)
        _notice.value = Notice("El teléfono no responde: suena la música de este PC. Volverá al teléfono al conectarse.")
    }

    /** [transfer]: llevar lo que suena al otro lado (no al volver solo al teléfono, que tiene su propia cola). */
    private fun switchTo(on: Boolean, persist: Boolean, transfer: Boolean = true) {
        if (on == _onPc.value) return
        val from = state.value
        val fromQueue = queue.value
        if (persist) {
            Settings.playOnPc = on
            Settings.save()
        }
        if (on) {
            local.start()
            if (from?.playing == true) cmd("pause")
            _onPc.value = true
            scope.launch {
                // La biblioteca del PC puede estar cargándose
                kotlinx.coroutines.withTimeoutOrNull(5000) { local.library.first { it != null } } ?: return@launch
                // La combinada (PC + teléfono) se recalcula aparte: se espera a que ya sea la del modo PC
                val lib = kotlinx.coroutines.withTimeoutOrNull(3000) { library.first { it?.device == "Este PC" } } ?: return@launch
                if (from == null) return@launch
                // La del PC si la tiene; si no, la del teléfono (suena bajo demanda, ver OnDemand)
                val byKey = lib.songs.reversed().associateBy { songKey(it.artist, it.title) }
                fun idOf(id: Long, artist: String, title: String) = byKey[songKey(artist, title)]?.id ?: id.takeIf { it in lib.byId }
                val ids = fromQueue.items.mapNotNull { idOf(it.id, it.artist, it.title) }
                val current = idOf(from.id, from.artist, from.title) ?: return@launch
                local.playSongs(ids.ifEmpty { listOf(current) }, ids.indexOf(current).coerceAtLeast(0), false)
                local.seek(from.positionAt(System.currentTimeMillis()))
                if (!from.playing) local.toggle()
            }
        } else {
            local.pause()
            _onPc.value = false
            if (!transfer) return
            val phoneLib = _library.value ?: return
            if (from == null || from.id == 0L) return
            val byKey = phoneLib.songs.associateBy { songKey(it.artist, it.title) }
            val ids = fromQueue.items.mapNotNull { byKey[songKey(it.artist, it.title)]?.id }
            val current = byKey[songKey(from.artist, from.title)]?.id ?: return
            cmd("playIds") { put("ids", JSONArray(ids.ifEmpty { listOf(current) })); put("index", ids.indexOf(current).coerceAtLeast(0)) }
            scope.launch {
                delay(600)
                cmd("seek") { put("position", from.positionAt(System.currentTimeMillis())) }
                if (!from.playing) cmd("pause")
            }
        }
    }

    private fun songKey(artist: String, title: String) =
        com.kevshupp.kevmusic.desktop.ui.normalize(com.kevshupp.kevmusic.desktop.ui.splitArtists(artist).firstOrNull() ?: artist) + "|" +
            com.kevshupp.kevmusic.desktop.ui.normalize(title)

    /**
     * Modo PC: las canciones del PC más las del teléfono que el PC no tiene (las del teléfono conectado, su última
     * biblioteca guardada o la de la nube, [CloudLibrary]), con sus ids del teléfono; esas suenan bajo demanda
     * ([LocalPlayer.remoteSongs]). Las listas del teléfono usan la canción del PC cuando coincide por `songKey`.
     */
    private fun withPhoneSongs(pc: Library, phone: Library?): Library {
        if (phone == null) { local.remoteSongs = emptyMap(); return pc }
        val pcByKey = pc.songs.associateBy { songKey(it.artist, it.title) }
        val extra = phone.songs.filter { songKey(it.artist, it.title) !in pcByKey }
        local.remoteSongs = extra.associateBy { it.id }
        val toPc = phone.songs.associate { s -> s.id to (pcByKey[songKey(s.artist, s.title)]?.id ?: s.id) }
        val lists = phone.playlists.map { pl -> pl.copy(songIds = pl.songIds.mapNotNull { toPc[it] }.distinct()) }.filter { it.songIds.isNotEmpty() }
        return Library(pc.device, pc.songs + extra, lists).also { if (extra.isNotEmpty()) it.warm() }
    }

    /** Ids del PC → los del teléfono (para editar sus listas desde el modo local). */
    private fun toPhoneIds(ids: List<Long>): List<Long> {
        if (!_onPc.value) return ids
        val phone = _library.value ?: return emptyList()
        val pc = local.library.value ?: return emptyList()
        val phoneByKey = phone.songs.associateBy { songKey(it.artist, it.title) }
        // Las que no son del PC ya son ids del teléfono (canciones que el PC muestra desde el teléfono o la nube)
        return ids.mapNotNull { id -> if (id > 0) id else pc.byId[id]?.let { phoneByKey[songKey(it.artist, it.title)]?.id } }
    }

    // ---------- Edición de la biblioteca (protocolo 3) ----------

    /** Envía una edición y muestra [success] o el motivo del fallo en [notice]. */
    private fun edit(type: String, success: String, build: JSONObject.() -> Unit) {
        if (phoneProtocol < 3) {
            _notice.value = Notice("Actualiza KevMusic en el teléfono a la 1.8.4 para editar la biblioteca desde el PC", error = true)
            return
        }
        scope.launch {
            val req = nextReq.getAndIncrement()
            val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
            pendingEdits[req] = deferred
            send(JSONObject().put("type", type).put("req", req).apply(build))
            // "" = bien; null = sin respuesta a tiempo
            val error = kotlinx.coroutines.withTimeoutOrNull(30_000) { deferred.await() ?: "" }
                ?: "El teléfono no respondió".also { pendingEdits.remove(req) }
            _notice.value = if (error.isEmpty()) Notice(success) else Notice(error, error = true)
        }
    }

    fun createPlaylist(name: String, ids: List<Long> = emptyList()) =
        edit("playlistCreate", "Lista \"${name.trim()}\" creada") { put("name", name.trim()); put("ids", JSONArray(toPhoneIds(ids))) }
    fun addToPlaylist(name: String, ids: List<Long>) {
        val phoneIds = toPhoneIds(ids)
        if (phoneIds.isEmpty()) { _notice.value = Notice("Esa canción no está en el teléfono: las listas viven allí", error = true); return }
        edit("playlistAdd", if (phoneIds.size == 1) "Añadida a \"$name\"" else "${phoneIds.size} canciones añadidas a \"$name\"") { put("name", name); put("ids", JSONArray(phoneIds)) }
    }
    fun removeFromPlaylist(name: String, ids: List<Long>) =
        edit("playlistRemove", "Quitada de \"$name\"") { put("name", name); put("ids", JSONArray(toPhoneIds(ids))) }
    fun deletePlaylist(name: String) = edit("playlistDelete", "Lista \"$name\" eliminada") { put("name", name) }
    fun deleteSongs(ids: List<Long>) =
        if (_onPc.value) _notice.value = Notice("En el modo PC no se borran archivos desde aquí", error = true) else edit("deleteSongs", if (ids.size == 1) "Canción eliminada del teléfono" else "${ids.size} canciones eliminadas del teléfono") { put("ids", JSONArray(ids)) }
    fun dismissNotice() { _notice.value = null }

    // ---------- Telegram ----------

    private fun parseTrack(o: JSONObject) = DzTrack(
        o.getLong("id"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optInt("duration"),
        o.optString("cover"), o.optString("coverXl"), o.optString("link"), o.optBoolean("owned"), o.optLong("localId")
    )

    private suspend fun request(type: String, build: JSONObject.() -> Unit): DzResult {
        val req = nextReq.getAndIncrement()
        val deferred = kotlinx.coroutines.CompletableDeferred<DzResult>()
        pending[req] = deferred
        send(JSONObject().put("type", type).put("req", req).apply(build))
        return kotlinx.coroutines.withTimeoutOrNull(30_000) { deferred.await() }
            ?: DzResult(emptyList(), emptyList(), emptyList(), "El teléfono no respondió").also { pending.remove(req) }
    }

    suspend fun tgSearch(query: String, mode: String) = request("tgSearch") { put("query", query); put("mode", mode) }

    suspend fun tgAlbum(album: DzAlbum) = request("tgAlbum") {
        put("album", JSONObject().put("id", album.id).put("title", album.title).put("artist", album.artist)
            .put("cover", album.cover).put("tracks", album.tracks).put("type", album.type).put("year", album.year))
    }

    suspend fun tgArtist(artist: DzArtist) = request("tgArtist") { put("id", artist.id); put("name", artist.name) }

    /** Populares y discografía de un artista por su nombre (panel de artista); el teléfono lo busca en Deezer. */
    suspend fun tgArtistByName(name: String) = request("tgArtist") { put("id", 0); put("name", name) }

    // ---------- Bajo demanda (protocolo 5) ----------

    private fun odTrack(t: DzTrack) = JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist)
        .put("album", t.album).put("duration", t.durationSec).put("cover", t.cover).put("coverXl", t.coverXl).put("link", t.link)

    private fun odAvailable(): Boolean {
        if (phoneProtocol >= 5) return true
        _notice.value = Notice("Actualiza KevMusic en el teléfono para escuchar bajo demanda desde el PC", error = true)
        return false
    }

    /** Suena ya en el teléfono, en modo bajo demanda (el bot la manda; se guarda en caché o en la biblioteca). */
    fun playOnDemand(t: DzTrack) { if (odAvailable()) edit("odPlay", "Sonando bajo demanda: ${t.title}") { put("track", odTrack(t)) } }

    fun queueOnDemand(t: DzTrack, next: Boolean) {
        if (odAvailable()) edit("odQueue", if (next) "Sonará a continuación" else "Añadida a la cola") { put("track", odTrack(t)); put("next", next) }
    }

    fun tgDownload(tracks: List<DzTrack>) {
        if (tracks.isEmpty()) return
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album)
                .put("duration", t.durationSec).put("cover", t.cover).put("coverXl", t.coverXl).put("link", t.link))
        }
        cmd("tgDownload") { put("tracks", arr) }
    }

    fun tgClearFinished() = cmd("tgClearFinished")

    private val urlImages = object : LinkedHashMap<String, ImageBitmap?>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 300
    }

    fun cachedUrlImage(url: String): ImageBitmap? = synchronized(urlImages) { urlImages[url] }

    /** Imágenes de Deezer (portadas y fotos de artista de los resultados), directas desde internet. */
    suspend fun loadUrlImage(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        synchronized(urlImages) { if (urlImages.containsKey(url)) return urlImages[url] }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    org.jetbrains.skia.Image.makeFromEncoded(resp.body!!.bytes()).toComposeImageBitmap()
                }
            }
        }
        if (result.isSuccess) synchronized(urlImages) { urlImages[url] = result.getOrNull() }
        return result.getOrNull()
    }

    // ---------- HTTP ----------

    private var refreshJob: Job? = null

    /**
     * Vuelve a pedir la biblioteca. Con una ya cargada se hace en silencio: el teléfono avisa con `libraryChanged`
     * en cada escaneo o edición (con Normalizar, decenas por minuto) y "Cargando…" parpadeaba junto al nombre del
     * dispositivo como si se desconectara. Una petición nueva sustituye a la que esté en curso. [showProgress] para el
     * botón "Recargar", donde sí se espera ver que carga.
     */
    fun refreshLibrary(showProgress: Boolean = false) {
        val d = device ?: return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            _libraryLoading.value = showProgress || _library.value == null
            val lib = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url("http://${d.host}:${d.port}/library?token=${d.token}").build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val body = resp.body!!.string()
                        parseLibrary(JSONObject(body)).also { libraryCacheFile(d).apply { parentFile.mkdirs(); writeText(body) } }
                    }
                }.getOrNull()
            }
            // Igual que la que ya se ve (avisos repetidos, reconexión): no se toca, así nada se recalcula
            if (lib != null && lib != _library.value) {
                withContext(Dispatchers.Default) { lib.warm() }
                _library.value = lib
            }
            _libraryLoading.value = false
        }
    }

    private fun libraryCacheFile(d: SavedDevice) =
        java.io.File(Platform.cacheDir, "library/" + d.key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    /**
     * La última biblioteca de ese teléfono, guardada en disco: al abrir la app se ve al instante mientras llega
     * la real (que la sustituye en cuanto se conecta). Antes Inicio esperaba a conectar y descargarla.
     */
    private fun loadCachedLibrary(d: SavedDevice) {
        if (_library.value != null) return
        scope.launch(Dispatchers.IO) {
            val lib = runCatching { parseLibrary(JSONObject(libraryCacheFile(d).readText())) }.getOrNull() ?: return@launch
            lib.warm()
            _library.compareAndSet(null, lib)
        }
    }

    private fun parsePlaylists(arr: JSONArray) = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val ids = o.getJSONArray("songs")
        Playlist(o.getString("name"), (0 until ids.length()).map { ids.getLong(it) }, o.optBoolean("smart"))
    }

    private fun parseLibrary(j: JSONObject): Library {
        val songs = j.getJSONArray("songs").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Song(
                    o.getLong("id"), o.optString("title"), o.optString("artist"), o.optString("album"),
                    o.optLong("duration"), o.optInt("track"), o.optString("year"), o.optString("genre"),
                    o.optInt("plays"), o.optLong("added"), o.optLong("last")
                )
            }
        }
        val playlists = j.optJSONArray("playlists")?.let { parsePlaylists(it) } ?: emptyList()
        return Library(j.optString("device"), songs, playlists)
    }

    private val artCache = object : LinkedHashMap<String, ImageBitmap?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 600
    }

    fun clearArtCache() {
        synchronized(artCache) { artCache.clear() }
        synchronized(urlImages) { urlImages.clear() }
        java.io.File(Platform.cacheDir, "art").deleteRecursively()
        java.io.File(Platform.cacheDir, "art_cache").deleteRecursively()
    }

    fun cachedArt(id: Long, small: Boolean, tiny: Boolean = false): ImageBitmap? =
        synchronized(artCache) { artCache[artKey(id, small, tiny)] }

    private fun artKey(id: Long, small: Boolean, tiny: Boolean) = "$id/" + if (tiny && small) "t" else "$small"

    /**
     * Carátulas en disco (~/.cache/kevmusic-desktop/art_cache): al volver a abrir la app no se piden otra vez al
     * teléfono. El nombre lleva un resumen de artista y álbum, para que un id que MediaStore reasigne a otra
     * canción no muestre la carátula vieja. "Vaciar caché" de Ajustes las borra.
     */
    private fun artDiskFile(id: Long, small: Boolean): java.io.File {
        val tag = _library.value?.byId?.get(id)?.let { (it.artist + "|" + it.album).hashCode().toUInt().toString(16) } ?: "x"
        return java.io.File(Platform.cacheDir, "art_cache/${id}_${tag}_${if (small) "s" else "b"}.jpg")
    }

    /** Lado en píxeles de la versión reducida para filas de lista (40–44 dp; el doble para pantallas HiDPI). */
    private val TINY_PX = 96

    /**
     * Carátula de una canción (null si no tiene). Se recuerda también la ausencia, para no repetir peticiones.
     * [tiny]: reducida a [TINY_PX] a partir de la pequeña (160 px), para que la gráfica no tenga que escalarla en
     * cada cuadro al desplazarse.
     */
    suspend fun loadArt(id: Long, small: Boolean, tiny: Boolean = false): ImageBitmap? {
        val key = artKey(id, small, tiny)
        synchronized(artCache) { if (artCache.containsKey(key)) return artCache[key] }
        if (com.kevshupp.kevmusic.desktop.local.LocalLibrary.isLocalId(id)) {
            val t = local.track(id) ?: return null
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = com.kevshupp.kevmusic.desktop.local.LocalLibrary.artBytes(t) ?: return@runCatching null
                    val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
                    val side = if (tiny && small) TINY_PX else if (small) 160 else 640
                    if (image.width > side) scaled(image, side) else image.toComposeImageBitmap()
                }.getOrNull()
            }
            synchronized(artCache) { artCache[key] = bmp }
            return bmp
        }
        val d = device ?: return null
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val disk = artDiskFile(id, small)
                val bytes = if (disk.isFile && disk.length() > 0) disk.readBytes() else {
                    val size = if (small) "small" else "big"
                    val req = Request.Builder().url("http://${d.host}:${d.port}/art/$id?token=${d.token}&size=$size").build()
                    http.newCall(req).execute().use { resp ->
                        if (resp.code == 404) return@runCatching null
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        resp.body!!.bytes()
                    }.also { b -> runCatching { disk.parentFile.mkdirs(); disk.writeBytes(b) } }
                }
                val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
                if (tiny && small && image.width > TINY_PX) scaled(image, TINY_PX) else image.toComposeImageBitmap()
            }
        }
        // Los errores de red no se recuerdan: se reintentará la próxima vez
        if (bmp.isSuccess) synchronized(artCache) { artCache[key] = bmp.getOrNull() }
        return bmp.getOrNull()
    }

    private fun scaled(image: org.jetbrains.skia.Image, side: Int): ImageBitmap {
        val ratio = side.toFloat() / maxOf(image.width, image.height)
        val w = (image.width * ratio).toInt().coerceAtLeast(1); val h = (image.height * ratio).toInt().coerceAtLeast(1)
        val bitmap = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(w, h) }
        org.jetbrains.skia.Canvas(bitmap).use { c ->
            c.drawImageRect(
                image, org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
                org.jetbrains.skia.SamplingMode.LINEAR, null, true
            )
        }
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    }

    private val prefetchSlots = kotlinx.coroutines.sync.Semaphore(3)

    /** Carga por adelantado las carátulas de las filas que están por aparecer (pocas a la vez, sin bloquear). */
    fun prefetchArt(ids: List<Long>, small: Boolean = true, tiny: Boolean = false) {
        ids.filter { cachedArt(it, small, tiny) == null }.forEach { id ->
            scope.launch(Dispatchers.IO) {
                prefetchSlots.acquire()
                try { loadArt(id, small, tiny) } finally { prefetchSlots.release() }
            }
        }
    }

    /** Borra del disco las carátulas más viejas si la caché pasa de 200 MB. */
    fun trimArtDiskCache() {
        scope.launch(Dispatchers.IO) {
            val files = java.io.File(Platform.cacheDir, "art_cache").listFiles()?.sortedBy { it.lastModified() } ?: return@launch
            var total = files.sumOf { it.length() }
            for (f in files) { if (total <= 200L * 1024 * 1024) break; total -= f.length(); f.delete() }
        }
    }
}
