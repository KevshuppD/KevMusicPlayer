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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        watchNetwork()
        // La cuenta llega también desde el PC o al restaurar una copia
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        if (_enabled.value && hasAccount) client.start()
    }

    // Referencia fuerte: SharedPreferences guarda los oyentes con referencias débiles
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            SoulseekAccount.KEY_USER, SoulseekAccount.KEY_PASS -> if (_enabled.value) {
                if (hasAccount) client.restart() else clientInstance?.stop()
            }
            KEY_SHARE, KEY_SHARE_MOBILE, MusicFolders.KEY_MAIN, MusicFolders.KEY_EXTRA -> sharingChanged()
        }
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
        if (on) { if (hasAccount) client.start() } else clientInstance?.stop()
    }

    fun login(user: String, pass: String) {
        SoulseekAccount.set(app, user.trim(), pass)
        // Si la cuenta no cambió no salta el oyente: conectar igualmente
        if (_enabled.value) client.restart()
    }

    fun logout() {
        clientInstance?.stop()
        SoulseekAccount.clear(app)
    }

    /** Vuelve a indexar lo compartido (o lo vacía) y avisa al servidor. */
    private fun sharingChanged() {
        val c = clientInstance ?: return
        scope.launch { c.rescanShares() }
    }

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
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
