package com.kevshupp.kevmusicplayer.data.soulseek

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Environment
import com.kevshupp.kevmusicplayer.data.LibraryImport
import com.kevshupp.kevmusicplayer.data.MusicFolders
import com.kevshupp.kevmusicplayer.data.PreferenceConstants
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import com.kevshupp.kevmusicplayer.data.remote.SoulseekAccount
import com.kevshupp.soulseek.PhoneTarget
import com.kevshupp.soulseek.SlskLabels
import com.kevshupp.soulseek.SlskSettings
import com.kevshupp.soulseek.SoulseekClient
import com.kevshupp.soulseek.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Soulseek en el teléfono (módulo opcional, apagado por defecto). El cliente es el mismo que el de KevMusic
 * Desktop (`soulseek-core`). Lo descargado va a la biblioteca con [LibraryImport] y se borra la copia temporal.
 *
 * Compartir también es opcional: con [KEY_SHARE] se comparten las carpetas de música, y con datos móviles solo si
 * [KEY_SHARE_MOBILE]. Sin compartir no se entra en la red distribuida (no se reciben búsquedas de otros).
 */
object SoulseekManager {
    const val KEY_ENABLED = "soulseek_enabled"
    const val KEY_SHARE = "soulseek_share"
    const val KEY_SHARE_MOBILE = "soulseek_share_mobile"
    const val KEY_UPLOAD_SLOTS = "soulseek_upload_slots"
    private const val IDLE_STOP_MS = 5 * 60_000L

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    /** En Wi‑Fi (o red sin límite de datos); decide si se comparte cuando [KEY_SHARE_MOBILE] está apagado. */
    private val _unmetered = MutableStateFlow(true)
    val unmetered: StateFlow<Boolean> = _unmetered.asStateFlow()

    @Volatile private var clientInstance: SoulseekClient? = null

    private val prefs: SharedPreferences get() = PreferenceConstants.getSettingsPrefs(app)

    private val settings = object : SlskSettings {
        override val username get() = SoulseekAccount.get(app)?.first.orEmpty()
        override val password get() = SoulseekAccount.get(app)?.second.orEmpty()
        override val sharedFolders: List<String>
            get() {
                if (!sharing) return emptyList()
                return MusicFolders.all(prefs).ifEmpty {
                    listOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).path)
                }
            }
        override val downloadDir get() = File(app.getExternalFilesDir(null) ?: app.filesDir, "soulseek").path
        override val port get() = 2234
        // En datos móviles no hay router al que pedirle el puerto
        override val useUpnp get() = sharing && _unmetered.value
        override val uploadSlots get() = prefs.getInt(KEY_UPLOAD_SLOTS, 2).coerceIn(1, 5)
        override val uploadLimit get() = 0
        override val sendToPhone get() = true
        override val keepOnPc get() = false
        override val cacheShareIndex get() = true
    }

    /** Compartir activado y permitido en la red actual. */
    val sharing: Boolean
        get() = prefs.getBoolean(KEY_SHARE, false) && (_unmetered.value || prefs.getBoolean(KEY_SHARE_MOBILE, false))

    /** La biblioteca del teléfono como destino de cada descarga. */
    private val library = object : PhoneTarget {
        override val blocker: String? get() = null
        override fun upload(file: File, artistHint: String, albumHint: String, onProgress: (Long) -> Unit): String {
            val saved = LibraryImport.import(app, file, file.nameWithoutExtension, artistHint, albumHint)
            TelemetryLogger.logInfo(app, "Soulseek", "Descargada ${saved.artist} - ${saved.title}")
            return saved.file.absolutePath
        }
    }

    /** El cliente, creado la primera vez que hace falta (sin el módulo activado no se crean hilos ni sockets). */
    val client: SoulseekClient
        get() = clientInstance ?: synchronized(this) {
            clientInstance ?: SoulseekClient(
                scope, settings, File(app.filesDir, "soulseek").apply { mkdirs() },
                SlskLabels(
                    clientName = "KevMusicPlayer",
                    downloaded = "Descargada",
                    waiting = "Esperando para guardar",
                    sending = "Guardando en la biblioteca",
                    delivered = "En la biblioteca",
                    deliveryFailed = "No se pudo guardar en la biblioteca",
                    localMissing = "El archivo descargado ya no está"
                )
            ).also { it.phone = library; clientInstance = it }
        }

    val hasAccount get() = settings.hasAccount

    fun init(context: Context) {
        app = context.applicationContext
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        // La cuenta llega también desde el PC o al restaurar una copia
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // Corre al abrir la app: nada de disco ni de red en el hilo principal
        scope.launch {
            watchNetwork()
            if (!_enabled.value || !hasAccount) return@launch
            // Sin compartir ni descargas pendientes no se conecta hasta abrir Soulseek
            val queue = File(app.filesDir, "soulseek/soulseek_queue.json")
            if (sharing || queue.isFile) {
                val c = client
                if (sharing || needsConnection(c)) { touch(); c.start() }
            }
            watchIdle()
        }
    }

    @Volatile private var dialogsOpen = 0
    @Volatile private var lastUseMs = 0L
    private var idleJob: Job? = null

    private fun touch() { lastUseMs = System.currentTimeMillis() }

    /** Hay algo que necesita a la red de Soulseek: subidas, o descargas que no solo esperan a guardarse. */
    private fun needsConnection(c: SoulseekClient) =
        c.uploads.value.any { !it.finished } || c.downloads.value.any { !it.finished && it.state != TransferState.Sending }

    /** La pantalla de Soulseek se abre: conecta si hacía falta. Devolver con [closeScreen]. */
    fun openScreen() {
        dialogsOpen++
        touch()
        if (_enabled.value && hasAccount) client.start()
        watchIdle()
    }

    fun closeScreen() {
        dialogsOpen = (dialogsOpen - 1).coerceAtLeast(0)
        touch()
    }

    /** Sin compartir, desconecta tras [IDLE_STOP_MS] sin pantalla abierta ni transferencias. */
    private fun watchIdle() = synchronized(this) {
        if (idleJob?.isActive == true) return@synchronized
        idleJob = scope.launch {
            while (true) {
                delay(30_000)
                val c = clientInstance ?: continue
                if (!c.isRunning) continue
                if (dialogsOpen > 0 || sharing || needsConnection(c)) touch()
                else if (System.currentTimeMillis() - lastUseMs >= IDLE_STOP_MS) c.stop()
            }
        }
    }

    // Referencia fuerte: SharedPreferences guarda los oyentes con referencias débiles
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            SoulseekAccount.KEY_USER, SoulseekAccount.KEY_PASS -> if (_enabled.value) {
                if (hasAccount) { touch(); client.restart() } else clientInstance?.stop()
            }
            KEY_SHARE, KEY_SHARE_MOBILE, MusicFolders.KEY_MAIN, MusicFolders.KEY_EXTRA -> sharingChanged()
        }
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
        if (on) { touch(); watchIdle(); if (hasAccount) client.start() } else clientInstance?.stop()
    }

    fun login(user: String, pass: String) {
        SoulseekAccount.set(app, user.trim(), pass)
        // Si la cuenta no cambió no salta el oyente: conectar igualmente
        if (_enabled.value) { touch(); client.restart() }
    }

    fun logout() {
        clientInstance?.stop()
        SoulseekAccount.clear(app)
    }

    /** Vuelve a indexar lo compartido (o lo vacía) y avisa al servidor. */
    private fun sharingChanged() {
        scope.launch {
            // Empezar a compartir necesita conexión aunque nadie haya abierto Soulseek
            if (sharing && _enabled.value && hasAccount && clientInstance?.isRunning != true) {
                touch()
                watchIdle()
                client.start() // escanea al arrancar
            } else {
                clientInstance?.rescanShares()
            }
        }
    }

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        // Saber ya la red actual: si no, compartir arrancaría creyendo estar en Wi‑Fi
        cm.getNetworkCapabilities(cm.activeNetwork)?.let { _unmetered.value = it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                if (unmetered != _unmetered.value) {
                    _unmetered.value = unmetered
                    if (!prefs.getBoolean(KEY_SHARE_MOBILE, false)) sharingChanged()
                }
            }
        })
    }
}
