package com.kevshupp.kevmusic.desktop.soulseek

import com.kevshupp.kevmusic.desktop.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

object ServerCode {
    const val LOGIN = 1
    const val SET_WAIT_PORT = 2
    const val GET_PEER_ADDRESS = 3
    const val CONNECT_TO_PEER = 18
    const val MESSAGE_USER = 22
    const val MESSAGE_ACKED = 23
    const val FILE_SEARCH = 26
    const val SET_STATUS = 28
    const val PING = 32
    const val SHARED_FOLDERS_FILES = 35
    const val RELOGGED = 41
    const val HAVE_NO_PARENT = 71
    const val EMBEDDED_MESSAGE = 93
    const val ACCEPT_CHILDREN = 100
    const val POSSIBLE_PARENTS = 102
    const val SEND_UPLOAD_SPEED = 121
    const val BRANCH_LEVEL = 126
    const val BRANCH_ROOT = 127
    const val CANT_CONNECT_TO_PEER = 1001
}

object PeerCode {
    const val SHARED_FILE_LIST_REQUEST = 4
    const val SHARED_FILE_LIST_RESPONSE = 5
    const val FILE_SEARCH_RESPONSE = 9
    const val USER_INFO_REQUEST = 15
    const val USER_INFO_RESPONSE = 16
    const val FOLDER_CONTENTS_REQUEST = 36
    const val FOLDER_CONTENTS_RESPONSE = 37
    const val TRANSFER_REQUEST = 40
    const val TRANSFER_RESPONSE = 41
    const val QUEUE_UPLOAD = 43
    const val PLACE_IN_QUEUE_RESPONSE = 44
    const val UPLOAD_FAILED = 46
    const val UPLOAD_DENIED = 50
    const val PLACE_IN_QUEUE_REQUEST = 51
}

private object DistribCode {
    const val SEARCH = 3
    const val BRANCH_LEVEL = 4
    const val BRANCH_ROOT = 5
    const val EMBEDDED = 93
}

/** El teléfono, para enviarle lo descargado (lo implementa RemoteClient). */
interface PhoneTarget {
    /** Por qué no se puede enviar ahora (sin conexión, app antigua), o null si se puede. */
    val blocker: String?
    /** Devuelve la ruta donde quedó la canción en el teléfono. */
    fun upload(file: File, artistHint: String, albumHint: String, onProgress: (Long) -> Unit): String
}

/** Conexión con otro usuario: 'P' mensajes, 'F' transferencia de un archivo, 'D' red distribuida. */
class PeerConn(val socket: Socket, @Volatile var username: String, @Volatile var type: Char) {
    val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
    val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

    fun send(bytes: ByteArray) = synchronized(this) { output.write(bytes); output.flush() }
    fun close() { runCatching { socket.close() } }
    val isOpen get() = !socket.isClosed
}

/**
 * Cliente de Soulseek escrito desde la documentación del protocolo de Nicotine+. Descarga a la carpeta del PC,
 * comparte las carpetas elegidas (responde búsquedas de la red distribuida, listas de archivos y subidas) y
 * envía cada canción descargada al teléfono por el servidor de la app.
 *
 * Todo el trabajo de red va en hilos propios (sockets bloqueantes); la interfaz solo lee los StateFlow.
 */
class SoulseekClient(private val scope: CoroutineScope) {
    companion object {
        /** En una descarga repartida, tiempo máximo en la cola de un usuario antes de pedir la canción a otro. */
        private const val SPLIT_WAIT_MS = 90_000L
        const val SERVER_HOST = "server.slsknet.org"
        const val SERVER_PORT = 2242
        private const val CLIENT_VERSION = 160
        private const val MINOR_VERSION = 1
        private const val MAX_RESULTS = 600
        private const val MAX_QUEUED_PER_USER = 200
    }

    private val _status = MutableStateFlow<SlskStatus>(SlskStatus.Off)
    val status: StateFlow<SlskStatus> = _status
    private val _results = MutableStateFlow<List<SlskResult>>(emptyList())
    val results: StateFlow<List<SlskResult>> = _results
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching
    private val _downloads = MutableStateFlow<List<Transfer>>(emptyList())
    val downloads: StateFlow<List<Transfer>> = _downloads
    private val _uploads = MutableStateFlow<List<Transfer>>(emptyList())
    val uploads: StateFlow<List<Transfer>> = _uploads
    /** Estado del puerto de escucha y del UPnP, para Ajustes. */
    private val _portInfo = MutableStateFlow("")
    val portInfo: StateFlow<String> = _portInfo
    /** Carpetas y archivos compartidos. */
    private val _shareStats = MutableStateFlow(0 to 0)
    val shareStats: StateFlow<Pair<Int, Int>> = _shareStats

    val shares = Shares()
    var phone: PhoneTarget? = null

    private val daemon = ThreadFactory { r -> Thread(r, "soulseek").apply { isDaemon = true } }
    private val pool = Executors.newCachedThreadPool(daemon)
    // Las búsquedas de otros llegan en ráfagas; las que no caben se descartan
    private val searchPool = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, ArrayBlockingQueue(150), daemon, ThreadPoolExecutor.DiscardPolicy())

    @Volatile private var running = false
    val isRunning get() = running
    @Volatile private var serverSocket: Socket? = null
    private val serverLock = Any()
    @Volatile private var me = ""
    private var listener: ServerSocket? = null
    @Volatile private var listenPort = 0
    @Volatile private var parent: PeerConn? = null

    private val tokens = AtomicLong((System.nanoTime() and 0x0FFFFFFF) + 1000)
    private val ids = AtomicLong(1)
    private val peers = ConcurrentHashMap<String, PeerConn>()
    private val userLocks = ConcurrentHashMap<String, Any>()
    private val addrWaiters = ConcurrentHashMap<String, CompletableFuture<Pair<String, Int>>>()

    private class Pending(val username: String, val type: Char, val future: CompletableFuture<PeerConn>)
    private val indirect = ConcurrentHashMap<Long, Pending>()
    private val folderWaiters = ConcurrentHashMap<Long, CompletableFuture<List<SlskFile>>>()
    private val uploadWaiters = ConcurrentHashMap<Long, CompletableFuture<Pair<Boolean, String>>>()
    private val downloadTokens = ConcurrentHashMap<Long, Long>()

    private val dl = ConcurrentHashMap<Long, Transfer>()
    private val ul = ConcurrentHashMap<Long, Transfer>()
    private val ulFiles = ConcurrentHashMap<Long, File>()
    private val toPhone = LinkedBlockingQueue<Long>()
    /** Otras fuentes de cada canción de una descarga repartida, por orden de preferencia (ver [downloadSplit]). */
    private val alternates = ConcurrentHashMap<Long, ArrayDeque<Pair<String, SlskFile>>>()
    private val waitingSince = ConcurrentHashMap<Long, Long>()
    @Volatile private var splitWatchdog: Thread? = null

    @Volatile private var searchToken = -1L
    private val pendingResults = ConcurrentLinkedQueue<SlskResult>()
    @Volatile private var completedUploads = 0
    @Volatile private var avgSpeed = 0L

    private val queueFile = File(Config.dir, "soulseek_queue.json")

    init {
        loadQueue()
        thread(isDaemon = true, name = "soulseek-phone") { phoneLoop() }
    }

    // ---------- Conexión ----------

    fun start() {
        if (running || !SoulseekConfig.hasAccount) return
        running = true
        _status.value = SlskStatus.Connecting
        thread(isDaemon = true, name = "soulseek-server") { runLoop() }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        runCatching { listener?.close() }
        listener = null
        peers.values.forEach { it.close() }
        peers.clear()
        parent?.close()
        parent = null
        // Hilo no demonio: al cerrar la app la JVM espera a que se borre la regla del router
        Thread { Upnp.close() }.start()
        _status.value = SlskStatus.Off
    }

    /** Tras cambiar usuario, contraseña o puerto. */
    fun restart() {
        stop()
        pool.execute { Thread.sleep(500); start() }
    }

    private fun runLoop() {
        rescanShares()
        openListener()
        var wait = 5_000L
        while (running) {
            try {
                connectServer()
                wait = 5_000L
            } catch (e: LoginException) {
                running = false
                _status.value = SlskStatus.Failed(e.message ?: "No se pudo iniciar sesión")
                return
            } catch (e: Exception) {
                if (!running) break
                _status.value = SlskStatus.Failed("Sin conexión con Soulseek (${e.message ?: e.javaClass.simpleName}); reintentando…")
            }
            if (!running) break
            Thread.sleep(wait)
            wait = (wait * 2).coerceAtMost(120_000)
        }
    }

    private class LoginException(message: String) : Exception(message)

    private fun connectServer() {
        val socket = Socket()
        socket.connect(InetSocketAddress(SERVER_HOST, SERVER_PORT), 10_000)
        socket.keepAlive = true
        serverSocket = socket
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val user = SoulseekConfig.username.trim()
        val pass = SoulseekConfig.password
        val hash = MessageDigest.getInstance("MD5").digest((user + pass).toByteArray()).joinToString("") { "%02x".format(it) }
        sendServer(MsgWriter().str(user).str(pass).u32(CLIENT_VERSION).str(hash).u32(MINOR_VERSION).frame(ServerCode.LOGIN))

        val pinger = scope.launch {
            while (true) { delay(60_000); runCatching { sendServer(MsgWriter().frame(ServerCode.PING)) } }
        }
        try {
            while (running) {
                val r = MsgReader(input.readFrame())
                val code = r.i32()
                if (code == ServerCode.LOGIN) onLogin(r, user) else runCatching { handleServer(code, r) }
            }
        } finally {
            pinger.cancel()
            runCatching { socket.close() }
            parent?.close()
        }
    }

    private fun onLogin(r: MsgReader, user: String) {
        if (!r.bool()) {
            val reason = r.str()
            throw LoginException(
                when {
                    reason.startsWith("INVALIDPASS") -> "Contraseña incorrecta para \"$user\" (ese nombre ya existe con otra contraseña)."
                    reason.startsWith("INVALIDUSERNAME") -> "Nombre de usuario no válido."
                    reason.startsWith("SVRFULL") -> "El servidor de Soulseek está lleno, prueba más tarde."
                    reason.startsWith("INVALIDVERSION") -> "El servidor no acepta esta versión del cliente."
                    else -> "Soulseek rechazó el inicio de sesión: $reason"
                }
            )
        }
        me = user
        sendServer(MsgWriter().u32(listenPort).frame(ServerCode.SET_WAIT_PORT))
        sendShareCounts()
        sendServer(MsgWriter().u32(2).frame(ServerCode.SET_STATUS)) // 2 = en línea
        sendServer(MsgWriter().bool(true).frame(ServerCode.HAVE_NO_PARENT))
        sendServer(MsgWriter().bool(false).frame(ServerCode.ACCEPT_CHILDREN))
        if (avgSpeed > 0) sendServer(MsgWriter().u32(avgSpeed).frame(ServerCode.SEND_UPLOAD_SPEED))
        _status.value = SlskStatus.Online(user)
        // Descargas que quedaron pendientes (de antes de cerrar o pedidas sin conexión)
        dl.values.filter { it.state == TransferState.Queued || it.state == TransferState.Waiting || it.state == TransferState.Connecting }
            .forEach { t -> pool.execute { requestDownload(t.id) } }
    }

    private fun sendServer(bytes: ByteArray) {
        val s = serverSocket ?: throw IOException("sin servidor")
        synchronized(serverLock) { s.getOutputStream().apply { write(bytes); flush() } }
    }

    private fun sendShareCounts() = runCatching {
        sendServer(MsgWriter().u32(shares.folderCount).u32(shares.fileCount).frame(ServerCode.SHARED_FOLDERS_FILES))
    }

    private fun openListener() {
        if (listener != null) return
        val base = SoulseekConfig.port
        for (p in base..base + 10) {
            val ss = runCatching { ServerSocket(p) }.getOrNull() ?: continue
            listener = ss
            listenPort = p
            _portInfo.value = "Escuchando en el puerto $p"
            thread(isDaemon = true, name = "soulseek-listen") {
                while (!ss.isClosed) {
                    val s = runCatching { ss.accept() }.getOrNull() ?: break
                    pool.execute { handleIncoming(s) }
                }
            }
            if (SoulseekConfig.useUpnp) pool.execute {
                val ip = runCatching { Upnp.open(p) }.getOrNull()
                _portInfo.value = when {
                    ip == null -> "Puerto $p sin abrir: el router no respondió a UPnP. Ábrelo a mano para más velocidad."
                    isPrivateIp(ip) -> "El router abrió el puerto $p, pero tu proveedor usa CGNAT (IP externa $ip): desde internet " +
                        "no pueden conectarse a ti. Descargas y subes igual con quien tenga su puerto abierto."
                    else -> "Puerto $p abierto en el router (UPnP), IP $ip"
                }
            }
            return
        }
        _portInfo.value = "No se pudo abrir un puerto de escucha ($base–${base + 10} ocupados)"
    }

    /** IP privada o de CGNAT (100.64.0.0/10): el router está detrás de otro NAT del proveedor. */
    private fun isPrivateIp(ip: String): Boolean {
        val o = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (o.size != 4) return false
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 100 && o[1] in 64..127)
    }

    private fun handleServer(code: Int, r: MsgReader) {
        when (code) {
            ServerCode.GET_PEER_ADDRESS -> {
                val user = r.str(); val ip = r.ip(); val port = r.u32().toInt()
                addrWaiters.remove(user)?.complete(ip to port)
            }
            ServerCode.CONNECT_TO_PEER -> {
                val user = r.str(); val type = r.str().firstOrNull() ?: 'P'; val ip = r.ip(); val port = r.u32().toInt(); val token = r.u32()
                pool.execute { pierce(user, type, ip, port, token) }
            }
            ServerCode.MESSAGE_USER -> sendServer(MsgWriter().u32(r.u32()).frame(ServerCode.MESSAGE_ACKED))
            ServerCode.FILE_SEARCH -> { val user = r.str(); val token = r.u32(); onSearchRequest(user, token, r.str()) }
            ServerCode.EMBEDDED_MESSAGE -> handleDistrib(r.u8(), r)
            ServerCode.POSSIBLE_PARENTS -> {
                if (parent != null) return
                val list = (0 until r.u32()).map { Triple(r.str(), r.ip(), r.u32().toInt()) }
                pool.execute { connectParent(list) }
            }
            ServerCode.RELOGGED -> {
                running = false
                _status.value = SlskStatus.Failed("Tu cuenta de Soulseek se abrió en otro programa o equipo.")
                runCatching { serverSocket?.close() }
            }
        }
    }

    // ---------- Conexiones con otros usuarios ----------

    private fun nextToken() = tokens.incrementAndGet() and 0xFFFFFFFFL

    private fun peerAddress(user: String): Pair<String, Int> {
        var created = false
        val f = addrWaiters.computeIfAbsent(user) { created = true; CompletableFuture() }
        if (created) sendServer(MsgWriter().str(user).frame(ServerCode.GET_PEER_ADDRESS))
        return try { f.get(10, TimeUnit.SECONDS) } catch (e: Exception) { addrWaiters.remove(user, f); throw IOException("El servidor no respondió") }
    }

    /** Conexión directa y, si el otro no acepta conexiones, indirecta (el servidor le pide que se conecte él). */
    private fun connectPeer(user: String, type: Char): PeerConn {
        val (ip, port) = peerAddress(user)
        if (port == 0 || ip == "0.0.0.0") throw IOException("$user no está conectado")
        try {
            val s = Socket()
            s.connect(InetSocketAddress(ip, port), 4_000)
            s.tcpNoDelay = true
            val c = PeerConn(s, user, type)
            c.send(MsgWriter().str(me).str(type.toString()).u32(0).frameByte(1))
            return c
        } catch (_: IOException) {
        }
        val token = nextToken()
        val f = CompletableFuture<PeerConn>()
        indirect[token] = Pending(user, type, f)
        sendServer(MsgWriter().u32(token).str(user).str(type.toString()).frame(ServerCode.CONNECT_TO_PEER))
        return try {
            f.get(20, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw IOException("No se pudo conectar con $user")
        } finally {
            indirect.remove(token)
        }
    }

    private fun getPeer(user: String): PeerConn {
        peers[user]?.takeIf { it.isOpen }?.let { return it }
        synchronized(userLocks.computeIfAbsent(user) { Any() }) {
            peers[user]?.takeIf { it.isOpen }?.let { return it }
            return connectPeer(user, 'P').also { registerPeer(it) }
        }
    }

    private fun registerPeer(c: PeerConn) {
        peers[c.username] = c
        pool.execute { readPeer(c) }
    }

    private fun handleIncoming(s: Socket) {
        try {
            s.soTimeout = 30_000
            s.tcpNoDelay = true
            val c = PeerConn(s, "", '?')
            val r = MsgReader(c.input.readFrame(64 * 1024))
            when (r.u8()) {
                0 -> { // PierceFirewall: respuesta a una conexión indirecta que pedimos
                    val p = indirect.remove(r.u32()) ?: return c.close()
                    c.username = p.username; c.type = p.type
                    if (!p.future.complete(c)) c.close()
                }
                1 -> { // PeerInit
                    c.username = r.str(); c.type = r.str().firstOrNull() ?: 'P'
                    dispatch(c)
                }
                else -> c.close()
            }
        } catch (e: Exception) {
            runCatching { s.close() }
        }
    }

    /** Alguien pidió al servidor que nos conectáramos con él. */
    private fun pierce(user: String, type: Char, ip: String, port: Int, token: Long) {
        try {
            val s = Socket()
            s.connect(InetSocketAddress(ip, port), 6_000)
            s.tcpNoDelay = true
            val c = PeerConn(s, user, type)
            c.send(MsgWriter().u32(token).frameByte(0))
            dispatch(c)
        } catch (e: Exception) {
            runCatching { sendServer(MsgWriter().u32(token).str(user).frame(ServerCode.CANT_CONNECT_TO_PEER)) }
        }
    }

    private fun dispatch(c: PeerConn) = when (c.type) {
        'P' -> registerPeer(c)
        'F' -> receiveFile(c)
        else -> c.close() // no aceptamos hijos en la red distribuida
    }

    private fun readPeer(c: PeerConn) {
        try {
            c.socket.soTimeout = 300_000
            while (c.isOpen) {
                val r = MsgReader(c.input.readFrame())
                val code = r.i32()
                try { handlePeer(c, code, r) } catch (e: IOException) { throw e } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        } finally {
            c.close()
            peers.remove(c.username, c)
        }
    }

    private fun handlePeer(c: PeerConn, code: Int, r: MsgReader) {
        val user = c.username
        when (code) {
            PeerCode.SHARED_FILE_LIST_REQUEST -> c.send(shares.sharedListMessage())
            PeerCode.USER_INFO_REQUEST -> c.send(
                MsgWriter().str("KevMusic Desktop").bool(false).u32(SoulseekConfig.uploadSlots).u32(queuedUploads())
                    .bool(freeSlot()).u32(1).frame(PeerCode.USER_INFO_RESPONSE)
            )
            PeerCode.FOLDER_CONTENTS_REQUEST -> {
                val token = r.u32(); val folder = r.str()
                val files = shares.folder(folder)
                val w = MsgWriter().u32(token).str(folder)
                if (files.isEmpty()) w.u32(0) else { w.u32(1).str(folder); Shares.writeFiles(w, files, fullPath = false) }
                c.send(w.frameZlib(PeerCode.FOLDER_CONTENTS_RESPONSE))
            }
            PeerCode.FOLDER_CONTENTS_RESPONSE -> {
                val d = MsgReader(inflate(r.rest()))
                val token = d.u32(); d.str()
                val files = ArrayList<SlskFile>()
                repeat(d.u32().toInt()) { val dir = d.str(); files += readFiles(d, dir) }
                folderWaiters.remove(token)?.complete(files)
            }
            PeerCode.FILE_SEARCH_RESPONSE -> onSearchResponse(r)
            PeerCode.TRANSFER_REQUEST -> {
                val direction = r.u32(); val token = r.u32(); val path = r.str()
                if (direction == 1L) {
                    val size = if (r.remaining >= 8) r.u64() else 0L
                    val t = dl.values.firstOrNull { it.username == user && it.path == path && !it.finished && it.state != TransferState.Sending }
                    if (t != null) {
                        downloadTokens[token] = t.id
                        updateDl(t.id) { it.copy(state = TransferState.Connecting, detail = "Conectando…", size = if (size > 0) size else it.size) }
                        c.send(MsgWriter().u32(token).bool(true).frame(PeerCode.TRANSFER_RESPONSE))
                    } else {
                        c.send(MsgWriter().u32(token).bool(false).str("Cancelled").frame(PeerCode.TRANSFER_RESPONSE))
                    }
                } else {
                    // Petición antigua de descarga: se encola como las modernas
                    queueUpload(c, path)
                    c.send(MsgWriter().u32(token).bool(false).str("Queued").frame(PeerCode.TRANSFER_RESPONSE))
                }
            }
            PeerCode.TRANSFER_RESPONSE -> {
                val token = r.u32(); val allowed = r.bool()
                val reason = if (!allowed && r.remaining >= 4) r.str() else ""
                uploadWaiters.remove(token)?.complete(allowed to reason)
            }
            PeerCode.QUEUE_UPLOAD -> queueUpload(c, r.str())
            PeerCode.PLACE_IN_QUEUE_REQUEST -> {
                val path = r.str()
                val mine = ul.values.filter { it.username == user && it.state == TransferState.Queued }.sortedBy { it.id }
                val place = mine.indexOfFirst { it.path == path } + 1
                if (place > 0) c.send(MsgWriter().str(path).u32(place).frame(PeerCode.PLACE_IN_QUEUE_RESPONSE))
            }
            PeerCode.PLACE_IN_QUEUE_RESPONSE -> {
                val path = r.str(); val place = r.u32()
                findDl(user, path)?.takeIf { it.state == TransferState.Waiting }?.let { t ->
                    updateDl(t.id) { it.copy(detail = "Puesto $place en la cola de $user") }
                }
            }
            PeerCode.UPLOAD_FAILED -> findDl(user, r.str())?.let { t -> failDl(t.id, "$user no pudo enviarlo") }
            PeerCode.UPLOAD_DENIED -> {
                val path = r.str(); val reason = r.str()
                findDl(user, path)?.let { t -> failDl(t.id, denyReason(reason)) }
            }
        }
    }

    private fun denyReason(reason: String) = when {
        reason.contains("not shared", true) -> "Ya no comparte ese archivo"
        reason.contains("Too many", true) -> "No acepta más archivos por ahora"
        reason.contains("Banned", true) -> "Ese usuario te bloqueó"
        reason.contains("Cancelled", true) -> "Cancelada por el usuario"
        reason.contains("Pending shutdown", true) -> "El usuario está cerrando su programa"
        reason.contains("File read error", true) -> "No pudo leer el archivo"
        else -> reason
    }

    private fun readFiles(r: MsgReader, folder: String?): List<SlskFile> = (0 until r.u32()).map {
        r.u8()
        val name = r.str(); val size = r.u64(); r.str()
        var bitrate: Int? = null; var duration: Int? = null; var rate: Int? = null; var depth: Int? = null
        repeat(r.u32().toInt()) {
            val type = r.u32().toInt(); val value = r.u32().toInt()
            when (type) { 0 -> bitrate = value; 1 -> duration = value; 4 -> rate = value; 5 -> depth = value }
        }
        SlskFile(if (folder != null) "$folder\\$name" else name, size, bitrate, duration, rate, depth)
    }

    // ---------- Búsquedas propias ----------

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty() || _status.value !is SlskStatus.Online) return
        val token = nextToken()
        searchToken = token
        pendingResults.clear()
        _results.value = emptyList()
        _searching.value = true
        pool.execute { runCatching { sendServer(MsgWriter().u32(token).str(q).frame(ServerCode.FILE_SEARCH)) } }
        // Los resultados llegan durante decenas de segundos; se publican en lotes para no redibujar en cada uno
        scope.launch {
            val end = System.currentTimeMillis() + 45_000
            while (searchToken == token && System.currentTimeMillis() < end) {
                delay(400)
                flushResults(token)
            }
            flushResults(token)
            if (searchToken == token) _searching.value = false
        }
    }

    private fun flushResults(token: Long) {
        if (searchToken != token || pendingResults.isEmpty()) return
        val batch = generateSequence { pendingResults.poll() }.toList()
        _results.value = (_results.value + batch).take(MAX_RESULTS)
    }

    private fun onSearchResponse(r: MsgReader) {
        val d = MsgReader(inflate(r.rest()))
        val user = d.str(); val token = d.u32()
        if (token != searchToken) return
        val files = readFiles(d, null).filter { it.extension in AUDIO_EXTENSIONS }
        if (files.isEmpty()) return
        val free = runCatching { d.bool() }.getOrDefault(false)
        val speed = runCatching { d.u32() }.getOrDefault(0)
        val queue = runCatching { d.u32() }.getOrDefault(0)
        pendingResults.add(SlskResult(user, files, free, speed, queue))
    }

    // ---------- Búsquedas de otros (compartir) ----------

    private fun onSearchRequest(user: String, token: Long, query: String) {
        if (user == me || shares.fileCount == 0 || query.length < 3) return
        searchPool.execute {
            val found = shares.search(query, 100)
            if (found.isEmpty()) return@execute
            runCatching {
                val w = MsgWriter().str(me).u32(token)
                Shares.writeFiles(w, found, fullPath = true)
                w.bool(freeSlot()).u32(avgSpeed).u32(queuedUploads()).u32(0)
                getPeer(user).send(w.frameZlib(PeerCode.FILE_SEARCH_RESPONSE))
            }
        }
    }

    /** La red distribuida reparte las búsquedas: sin padre, casi nadie encontraría lo que compartimos. */
    private fun connectParent(candidates: List<Triple<String, String, Int>>) {
        for ((user, ip, port) in candidates) {
            if (parent != null || !running) return
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), 4_000)
                val c = PeerConn(s, user, 'D')
                c.send(MsgWriter().str(me).str("D").u32(0).frameByte(1))
                parent = c
                sendServer(MsgWriter().bool(false).frame(ServerCode.HAVE_NO_PARENT))
                pool.execute { readDistrib(c) }
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun readDistrib(c: PeerConn) {
        try {
            c.socket.soTimeout = 300_000
            while (c.isOpen) {
                val r = MsgReader(c.input.readFrame())
                runCatching { handleDistrib(r.u8(), r) }
            }
        } catch (_: Exception) {
        } finally {
            c.close()
            if (parent === c) {
                parent = null
                runCatching { sendServer(MsgWriter().bool(true).frame(ServerCode.HAVE_NO_PARENT)) }
            }
        }
    }

    private fun handleDistrib(code: Int, r: MsgReader) {
        when (code) {
            DistribCode.SEARCH -> { r.u32(); val user = r.str(); val token = r.u32(); onSearchRequest(user, token, r.str()) }
            DistribCode.BRANCH_LEVEL -> sendServer(MsgWriter().u32(r.i32() + 1).frame(ServerCode.BRANCH_LEVEL))
            DistribCode.BRANCH_ROOT -> sendServer(MsgWriter().str(r.str()).frame(ServerCode.BRANCH_ROOT))
            DistribCode.EMBEDDED -> handleDistrib(r.u8(), r)
        }
    }

    // ---------- Descargas ----------

    fun download(user: String, files: List<SlskFile>) {
        files.forEach { f ->
            if (dl.values.any { it.username == user && it.path == f.path && it.state != TransferState.Failed }) return@forEach
            dl.values.firstOrNull { it.username == user && it.path == f.path }?.let { dl.remove(it.id) }
            val t = Transfer(ids.getAndIncrement(), user, f.path, f.size, detail = "Pendiente")
            dl[t.id] = t
            if (_status.value is SlskStatus.Online) pool.execute { requestDownload(t.id) }
        }
        publishDl()
    }

    /**
     * Descarga repartida de un álbum: cada canción trae sus fuentes posibles (usuario, archivo), la primera la
     * mejor. Como cada usuario envía un archivo cada vez, se reparte para que varias canciones bajen a la vez:
     * a cada canción le toca la fuente con menos canciones asignadas hasta ahora (a igualdad, la mejor). Si una
     * falla o pasa más de [SPLIT_WAIT_MS] en la cola de su usuario, pasa a la siguiente fuente. Todas se
     * guardan en la misma carpeta [folder].
     */
    fun downloadSplit(folder: String, songs: List<List<Pair<String, SlskFile>>>) {
        val load = HashMap<String, Int>()
        songs.forEach { candidates ->
            if (candidates.isEmpty()) return@forEach
            if (candidates.any { (u, f) -> dl.values.any { it.username == u && it.path == f.path && it.state != TransferState.Failed } }) return@forEach
            val pick = candidates.minByOrNull { load[it.first] ?: 0 } ?: return@forEach
            load[pick.first] = (load[pick.first] ?: 0) + 1
            val (user, f) = pick
            val t = Transfer(ids.getAndIncrement(), user, f.path, f.size, detail = "Pendiente", folder = folder)
            dl[t.id] = t
            alternates[t.id] = ArrayDeque(candidates.filter { it !== pick })
            if (_status.value is SlskStatus.Online) pool.execute { requestDownload(t.id) }
        }
        publishDl()
        startSplitWatchdog()
    }

    /** Pasa la canción a su siguiente fuente; false si no le quedan. */
    private fun switchSource(id: Long, reason: String): Boolean {
        val next = alternates[id]?.removeFirstOrNull() ?: run { alternates.remove(id); return false }
        val (user, f) = next
        waitingSince.remove(id)
        updateDl(id) { it.copy(username = user, path = f.path, size = f.size, state = TransferState.Queued, done = 0, speed = 0, detail = "$reason · probando con $user") }
        if (_status.value is SlskStatus.Online) pool.execute { requestDownload(id) }
        return true
    }

    private fun startSplitWatchdog() {
        if (splitWatchdog?.isAlive == true) return
        splitWatchdog = thread(isDaemon = true, name = "soulseek-split") {
            while (alternates.isNotEmpty()) {
                Thread.sleep(10_000)
                val now = System.currentTimeMillis()
                alternates.keys.toList().forEach { id ->
                    val t = dl[id]
                    when {
                        t == null || t.finished -> { alternates.remove(id); waitingSince.remove(id) }
                        t.state != TransferState.Waiting || alternates[id].isNullOrEmpty() -> waitingSince.remove(id)
                        else -> {
                            val since = waitingSince.getOrPut(id) { now }
                            if (now - since > SPLIT_WAIT_MS) switchSource(id, "Mucha cola en ${t.username}")
                        }
                    }
                }
            }
        }
    }

    /** Pide la carpeta completa al usuario (el resultado de búsqueda solo trae lo que coincidió). */
    fun downloadFolder(user: String, folder: String, fallback: List<SlskFile>, onError: (String) -> Unit) {
        pool.execute {
            val files = try {
                val token = nextToken()
                val f = CompletableFuture<List<SlskFile>>()
                folderWaiters[token] = f
                getPeer(user).send(MsgWriter().u32(token).str(folder).frame(PeerCode.FOLDER_CONTENTS_REQUEST))
                f.get(25, TimeUnit.SECONDS).filter { it.extension in AUDIO_EXTENSIONS }.ifEmpty { fallback }
            } catch (e: Exception) {
                if (fallback.isEmpty()) { onError("No se pudo abrir la carpeta de $user"); return@execute }
                fallback
            }
            download(user, files.sortedBy { it.path })
        }
    }

    fun retry(id: Long) {
        val t = dl[id] ?: return
        if (t.localPath.isNotEmpty() && File(t.localPath).isFile) {
            updateDl(id) { it.copy(state = TransferState.Sending, detail = "Esperando al teléfono") }
            toPhone.add(id)
        } else {
            updateDl(id) { it.copy(state = TransferState.Queued, detail = "Pendiente", done = 0) }
            if (_status.value is SlskStatus.Online) pool.execute { requestDownload(id) }
        }
    }

    fun cancel(id: Long) {
        dl.remove(id)
        publishDl()
    }

    fun clearFinished() {
        dl.values.filter { it.finished }.forEach { dl.remove(it.id) }
        ul.values.filter { it.finished }.forEach { ul.remove(it.id); ulFiles.remove(it.id) }
        publishDl(); publishUl()
    }

    private fun requestDownload(id: Long) {
        val t = dl[id] ?: return
        try {
            val c = getPeer(t.username)
            c.send(MsgWriter().str(t.path).frame(PeerCode.QUEUE_UPLOAD))
            c.send(MsgWriter().str(t.path).frame(PeerCode.PLACE_IN_QUEUE_REQUEST))
            updateDl(id) { if (it.state == TransferState.Queued) it.copy(state = TransferState.Waiting, detail = "En la cola de ${t.username}") else it }
        } catch (e: Exception) {
            failDl(id, e.message ?: "No se pudo conectar")
        }
    }

    private fun receiveFile(c: PeerConn) {
        var id = -1L
        try {
            c.socket.soTimeout = 60_000
            val token = c.input.readU32()
            id = downloadTokens.remove(token) ?: return c.close()
            val t = dl[id] ?: return c.close()
            val part = partFile(t)
            part.parentFile.mkdirs()
            val offset = if (part.isFile && part.length() < t.size) part.length() else { part.delete(); 0L }
            c.send(MsgWriter().u64(offset).toByteArray())
            updateDl(id) { it.copy(state = TransferState.Transferring, done = offset, detail = "") }

            var done = offset
            var lastUpdate = 0L
            var windowStart = System.currentTimeMillis(); var windowBytes = 0L; var speed = 0L
            java.io.FileOutputStream(part, true).use { out ->
                val buf = ByteArray(64 * 1024)
                while (done < t.size) {
                    val n = c.input.read(buf, 0, minOf(buf.size.toLong(), t.size - done).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n; windowBytes += n
                    val now = System.currentTimeMillis()
                    if (now - windowStart >= 1000) { speed = windowBytes * 1000 / (now - windowStart); windowStart = now; windowBytes = 0 }
                    if (now - lastUpdate > 300) {
                        lastUpdate = now
                        val d = done; val s = speed
                        if (dl[id] == null) throw IOException("cancelada")
                        updateDl(id) { it.copy(done = d, speed = s) }
                    }
                }
            }
            c.close()
            if (done < t.size) throw IOException("Se cortó a mitad (se reanuda al reintentar)")
            val target = finalFile(t)
            target.parentFile.mkdirs()
            if (!part.renameTo(target)) { part.copyTo(target); part.delete() }
            onDownloaded(id, target)
        } catch (e: Exception) {
            c.close()
            if (id >= 0 && dl[id] != null) failDl(id, e.message ?: "Error en la descarga")
        }
    }

    private fun onDownloaded(id: Long, file: File) {
        updateDl(id) { it.copy(state = TransferState.Done, done = it.size, speed = 0, detail = "En el PC", localPath = file.path) }
        scheduleRescan()
        if (SoulseekConfig.sendToPhone) {
            updateDl(id) { it.copy(state = TransferState.Sending, detail = "Esperando al teléfono", done = 0) }
            toPhone.add(id)
        }
    }

    /** Envía al teléfono en serie; si no está conectado, espera a que vuelva. */
    private fun phoneLoop() {
        while (true) {
            val id = toPhone.take()
            val t = dl[id] ?: continue
            val file = File(t.localPath)
            if (!file.isFile) { failDl(id, "El archivo ya no está en el PC"); continue }
            // Sin teléfono o con una app que no recibe canciones, espera (no falla) y lo muestra
            while (true) {
                val blocker = phone?.blocker ?: if (phone == null) "Esperando al teléfono" else break
                if (dl[id]?.detail != blocker) updateDl(id) { it.copy(detail = blocker) }
                Thread.sleep(3000)
            }
            try {
                updateDl(id) { it.copy(detail = "Enviando al teléfono", done = 0, size = file.length()) }
                val parts = t.path.split('\\').dropLast(1)
                val album = parts.lastOrNull().orEmpty()
                val artist = parts.dropLast(1).lastOrNull().orEmpty()
                var last = 0L
                val path = phone!!.upload(file, artist, album) { sent ->
                    val now = System.currentTimeMillis()
                    if (now - last > 300) { last = now; updateDl(id) { it.copy(done = sent) } }
                }
                if (!SoulseekConfig.keepOnPc) { file.delete(); scheduleRescan() }
                updateDl(id) { it.copy(state = TransferState.Done, done = it.size, detail = "En el teléfono: ${path.substringAfterLast("/Music/").ifBlank { path }}") }
            } catch (e: Exception) {
                failDl(id, "No se pudo enviar al teléfono: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun partFile(t: Transfer) = File(File(SoulseekConfig.downloadDir, ".incompleto"), "${t.username.hashCode().toUInt()}_${safe(t.name)}.part")

    /** `<carpeta de descargas>/<carpeta remota>/<archivo>`; nunca sobrescribe. */
    private fun finalFile(t: Transfer): File {
        val folder = safe(t.folder.ifBlank { t.path.split('\\').dropLast(1).lastOrNull().orEmpty().ifBlank { t.username } })
        val dir = File(SoulseekConfig.downloadDir, folder)
        val name = safe(t.name)
        var f = File(dir, name)
        var n = 2
        while (f.exists()) f = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.', "")}").also { n++ }
        return f
    }

    private fun safe(s: String) = s.replace(Regex("[/\\u0000]"), "_").trim().trimStart('.').ifBlank { "archivo" }

    private fun findDl(user: String, path: String) = dl.values.firstOrNull { it.username == user && it.path == path && !it.finished }

    private fun failDl(id: Long, reason: String) {
        // Descarga repartida: se prueba la siguiente fuente antes de darla por fallida
        if (dl[id] != null && switchSource(id, reason)) return
        updateDl(id) { it.copy(state = TransferState.Failed, detail = reason, speed = 0) }
    }

    private fun updateDl(id: Long, change: (Transfer) -> Transfer) {
        dl.computeIfPresent(id) { _, t -> change(t) }
        publishDl()
    }

    private fun publishDl() {
        _downloads.value = dl.values.sortedBy { it.id }
        saveQueue()
    }

    // ---------- Subidas ----------

    private fun freeSlot() = ul.values.count { it.state == TransferState.Connecting || it.state == TransferState.Transferring } < SoulseekConfig.uploadSlots
    private fun queuedUploads() = ul.values.count { it.state == TransferState.Queued }

    private fun queueUpload(c: PeerConn, path: String) {
        val user = c.username
        val entry = shares.find(path)
        if (entry == null) {
            c.send(MsgWriter().str(path).str("File not shared.").frame(PeerCode.UPLOAD_DENIED))
            return
        }
        if (ul.values.any { it.username == user && it.path == path && !it.finished }) return
        if (ul.values.count { it.username == user && !it.finished } >= MAX_QUEUED_PER_USER) {
            c.send(MsgWriter().str(path).str("Too many files").frame(PeerCode.UPLOAD_DENIED))
            return
        }
        val t = Transfer(ids.getAndIncrement(), user, path, entry.size, detail = "En cola")
        ul[t.id] = t
        ulFiles[t.id] = entry.file
        publishUl()
        scheduleUploads()
    }

    @Synchronized
    private fun scheduleUploads() {
        var active = ul.values.count { it.state == TransferState.Connecting || it.state == TransferState.Transferring }
        while (active < SoulseekConfig.uploadSlots) {
            val busy = ul.values.filter { it.state == TransferState.Connecting || it.state == TransferState.Transferring }.map { it.username }.toSet()
            val queued = ul.values.filter { it.state == TransferState.Queued }.sortedBy { it.id }
            // Turnos: primero quien no está recibiendo nada
            val next = queued.firstOrNull { it.username !in busy } ?: queued.firstOrNull() ?: return
            ul[next.id] = next.copy(state = TransferState.Connecting, detail = "Conectando…")
            publishUl()
            active++
            pool.execute { runUpload(next.id) }
        }
    }

    private fun runUpload(id: Long) {
        val t = ul[id] ?: return
        val file = ulFiles[id] ?: return
        var token = -1L
        try {
            val c = getPeer(t.username)
            token = nextToken()
            val f = CompletableFuture<Pair<Boolean, String>>()
            uploadWaiters[token] = f
            c.send(MsgWriter().u32(1).u32(token).str(t.path).u64(file.length()).frame(PeerCode.TRANSFER_REQUEST))
            val (allowed, reason) = f.get(60, TimeUnit.SECONDS)
            if (!allowed) throw IOException(denyReason(reason.ifBlank { "Rechazada" }))

            val fc = connectPeer(t.username, 'F')
            try {
                fc.socket.soTimeout = 60_000
                fc.send(MsgWriter().u32(token).toByteArray())
                val offset = fc.input.readU64().coerceIn(0, file.length())
                updateUl(id) { it.copy(state = TransferState.Transferring, done = offset, detail = "") }
                val started = System.currentTimeMillis()
                var sent = offset
                var last = 0L
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = raf.read(buf)
                        if (n < 0) break
                        fc.output.write(buf, 0, n)
                        sent += n
                        throttle(started, sent - offset)
                        val now = System.currentTimeMillis()
                        if (now - last > 400) {
                            last = now
                            val s = sent; val speed = (s - offset) * 1000 / (now - started).coerceAtLeast(1)
                            updateUl(id) { it.copy(done = s, speed = speed) }
                        }
                    }
                    fc.output.flush()
                }
                // Se espera a que el otro cierre, para no cortar los últimos bytes
                runCatching { fc.socket.soTimeout = 15_000; while (fc.input.read() >= 0) Unit }
                val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
                val speed = (sent - offset) * 1000 / elapsed
                completedUploads++
                avgSpeed = if (avgSpeed == 0L) speed else (avgSpeed * 4 + speed) / 5
                runCatching { sendServer(MsgWriter().u32(avgSpeed).frame(ServerCode.SEND_UPLOAD_SPEED)) }
                updateUl(id) { it.copy(state = TransferState.Done, done = it.size, speed = 0, detail = "Enviada") }
            } finally {
                fc.close()
            }
        } catch (e: Exception) {
            updateUl(id) { it.copy(state = TransferState.Failed, speed = 0, detail = e.message ?: "Error") }
            ul[id]?.let { tr ->
                runCatching { peers[tr.username]?.send(MsgWriter().str(tr.path).frame(PeerCode.UPLOAD_FAILED)) }
            }
        } finally {
            if (token >= 0) uploadWaiters.remove(token)
            scheduleUploads()
        }
    }

    /** Límite de subida repartido entre las subidas activas. */
    private fun throttle(started: Long, bytes: Long) {
        val limit = SoulseekConfig.uploadLimit
        if (limit <= 0) return
        val active = ul.values.count { it.state == TransferState.Transferring }.coerceAtLeast(1)
        val perUpload = limit * 1024L / active
        val expectedMs = bytes * 1000 / perUpload.coerceAtLeast(1)
        val ahead = expectedMs - (System.currentTimeMillis() - started)
        if (ahead > 0) Thread.sleep(ahead.coerceAtMost(1000))
    }

    private fun updateUl(id: Long, change: (Transfer) -> Transfer) {
        ul.computeIfPresent(id) { _, t -> change(t) }
        publishUl()
    }

    private fun publishUl() {
        _uploads.value = ul.values.sortedByDescending { it.id }.take(300)
    }

    // ---------- Compartidos ----------

    @Volatile private var rescanPending = false

    fun rescanShares() {
        shares.rescan(SoulseekConfig.sharedFolders)
        _shareStats.value = shares.folderCount to shares.fileCount
        if (_status.value is SlskStatus.Online) sendShareCounts()
    }

    private fun scheduleRescan() {
        if (rescanPending) return
        rescanPending = true
        scope.launch {
            delay(5_000)
            rescanPending = false
            pool.execute { rescanShares() }
        }
    }

    // ---------- Cola guardada ----------

    private fun saveQueue() {
        // Pendientes de descargar, y descargadas que aún no llegaron al teléfono (con su archivo local)
        val pending = dl.values.filter { (!it.finished && it.state != TransferState.Sending) || it.awaitingPhone }
        runCatching {
            val arr = JSONArray()
            pending.forEach {
                arr.put(JSONObject().put("user", it.username).put("path", it.path).put("size", it.size)
                    .put("local", if (it.awaitingPhone) it.localPath else "").put("folder", it.folder))
            }
            queueFile.writeText(arr.toString())
        }
    }

    private fun loadQueue() {
        runCatching {
            val arr = JSONArray(queueFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val local = o.optString("local")
                val t = if (local.isNotEmpty() && File(local).isFile) {
                    Transfer(ids.getAndIncrement(), o.getString("user"), o.getString("path"), o.optLong("size"),
                        state = TransferState.Sending, detail = "Esperando al teléfono", localPath = local).also { toPhone.add(it.id) }
                } else {
                    Transfer(ids.getAndIncrement(), o.getString("user"), o.getString("path"), o.optLong("size"), detail = "Pendiente", folder = o.optString("folder"))
                }
                dl[t.id] = t
            }
        }
        _downloads.value = dl.values.sortedBy { it.id }
    }
}
