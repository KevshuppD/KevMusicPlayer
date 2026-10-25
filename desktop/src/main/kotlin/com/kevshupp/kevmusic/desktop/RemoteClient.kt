package com.kevshupp.kevmusic.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val _state = MutableStateFlow<PlayerState?>(null)
    val state: StateFlow<PlayerState?> = _state
    private val _queue = MutableStateFlow(QueueState())
    val queue: StateFlow<QueueState> = _queue
    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics
    private val _library = MutableStateFlow<Library?>(null)
    val library: StateFlow<Library?> = _library
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
                _library.value = _library.value?.let { lib ->
                    lib.copy(songs = lib.songs.map { if (it.id == id) it.copy(plays = it.plays + 1, lastPlayed = at) else it })
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

    fun toggle() = cmd("toggle")
    fun next() = cmd("next")
    fun previous() = cmd("prev")

    fun seek(positionMs: Long) {
        cmd("seek") { put("position", positionMs) }
        // Respuesta inmediata en la barra; el teléfono confirma con el siguiente "state"
        _state.value = _state.value?.copy(position = positionMs, receivedAt = System.currentTimeMillis())
    }

    fun setVolume(value: Int) {
        cmd("volume") { put("value", value) }
        _state.value = _state.value?.copy(volume = value)
    }

    fun toggleShuffle() {
        cmd("shuffle")
        // Respuesta inmediata; el teléfono confirma con el siguiente "state"
        _state.value = _state.value?.let { it.copy(shuffle = !it.shuffle) }
    }

    fun setRepeat(mode: Int) = cmd("repeat") { put("mode", mode.coerceIn(0, 2)) }

    /** Carátula guardada en ~/.cache/kevmusic-desktop/art para MPRIS (que pide una ruta file://). */
    suspend fun artFile(id: Long): java.io.File? {
        val d = device ?: return null
        val dir = java.io.File(System.getProperty("user.home"), ".cache/kevmusic-desktop/art").apply { mkdirs() }
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

    /** Versión del protocolo del teléfono; la 2 (app 1.8.0) acepta canciones con `PUT /upload`. */
    @Volatile var phoneProtocol = 0
        private set

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
        val next = ((_state.value?.repeat ?: 0) + 1) % 3
        cmd("repeat") { put("mode", next) }
    }

    fun playSongs(ids: List<Long>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (ids.isEmpty()) return
        val list = if (shuffle) ids.shuffled() else ids
        cmd("playIds") {
            put("ids", JSONArray(list))
            put("index", if (shuffle) 0 else startIndex.coerceIn(0, list.size - 1))
        }
    }

    fun enqueue(ids: List<Long>, playNext: Boolean) = cmd("enqueue") {
        put("ids", JSONArray(ids))
        put("next", playNext)
    }

    fun jumpTo(index: Int) = cmd("jump") { put("index", index) }
    fun removeFromQueue(index: Int) = cmd("remove") { put("index", index) }
    fun moveInQueue(from: Int, to: Int) = cmd("move") { put("from", from); put("to", to) }

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
        edit("playlistCreate", "Lista \"${name.trim()}\" creada") { put("name", name.trim()); put("ids", JSONArray(ids)) }
    fun addToPlaylist(name: String, ids: List<Long>) =
        edit("playlistAdd", if (ids.size == 1) "Añadida a \"$name\"" else "${ids.size} canciones añadidas a \"$name\"") { put("name", name); put("ids", JSONArray(ids)) }
    fun removeFromPlaylist(name: String, ids: List<Long>) =
        edit("playlistRemove", "Quitada de \"$name\"") { put("name", name); put("ids", JSONArray(ids)) }
    fun deletePlaylist(name: String) = edit("playlistDelete", "Lista \"$name\" eliminada") { put("name", name) }
    fun deleteSongs(ids: List<Long>) =
        edit("deleteSongs", if (ids.size == 1) "Canción eliminada del teléfono" else "${ids.size} canciones eliminadas del teléfono") { put("ids", JSONArray(ids)) }
    fun dismissNotice() { _notice.value = null }

    // ---------- Telegram ----------

    private fun parseTrack(o: JSONObject) = DzTrack(
        o.getLong("id"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optInt("duration"),
        o.optString("cover"), o.optString("coverXl"), o.optString("link"), o.optBoolean("owned")
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
                        parseLibrary(JSONObject(resp.body!!.string()))
                    }
                }.getOrNull()
            }
            if (lib != null) _library.value = lib
            _libraryLoading.value = false
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
        java.io.File(System.getProperty("user.home"), ".cache/kevmusic-desktop/art").deleteRecursively()
    }

    fun cachedArt(id: Long, small: Boolean): ImageBitmap? = synchronized(artCache) { artCache["$id/$small"] }

    /** Carátula de una canción (null si no tiene). Se recuerda también la ausencia, para no repetir peticiones. */
    suspend fun loadArt(id: Long, small: Boolean): ImageBitmap? {
        val key = "$id/$small"
        synchronized(artCache) { if (artCache.containsKey(key)) return artCache[key] }
        val d = device ?: return null
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val size = if (small) "small" else "big"
                val req = Request.Builder().url("http://${d.host}:${d.port}/art/$id?token=${d.token}&size=$size").build()
                http.newCall(req).execute().use { resp ->
                    if (resp.code == 404) return@use null
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    org.jetbrains.skia.Image.makeFromEncoded(resp.body!!.bytes()).toComposeImageBitmap()
                }
            }
        }
        // Los errores de red no se recuerdan: se reintentará la próxima vez
        if (bmp.isSuccess) synchronized(artCache) { artCache[key] = bmp.getOrNull() }
        return bmp.getOrNull()
    }
}
