@file:Suppress("FunctionName", "UNCHECKED_CAST")

package com.kevshupp.kevmusic.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
}

/**
 * Publica KevMusic Desktop en D-Bus como reproductor MPRIS: las teclas multimedia del teclado,
 * el applet de sonido y la pantalla de bloqueo de GNOME/Cinnamon controlan así el teléfono.
 * Si no hay bus de sesión (otro escritorio, SSH) simplemente no se activa.
 */
class Mpris(
    private val client: RemoteClient,
    private val scope: CoroutineScope,
    private val onRaise: () -> Unit,
    private val onQuit: () -> Unit
) : MediaPlayer2, MediaPlayer2Player, Properties {
    private companion object {
        const val PATH = "/org/mpris/MediaPlayer2"
        const val ROOT = "org.mpris.MediaPlayer2"
        const val PLAYER = "org.mpris.MediaPlayer2.Player"
        const val BUS_NAME = "org.mpris.MediaPlayer2.kevmusic"
    }

    private var connection: DBusConnection? = null
    private var watchJob: Job? = null
    @Volatile private var artUrl: String = ""
    private var lastSent: Map<String, Variant<*>> = emptyMap()

    fun start() {
        runCatching {
            val conn = DBusConnectionBuilder.forSessionBus().build()
            conn.requestBusName(BUS_NAME)
            conn.exportObject(PATH, this)
            connection = conn
        }.onFailure { System.err.println("MPRIS no disponible: ${it.message}"); return }

        watchJob = scope.launch(Dispatchers.Default) {
            var lastArtId = 0L
            client.state.collect { state ->
                val id = state?.id ?: 0L
                if (id != lastArtId) {
                    lastArtId = id
                    artUrl = ""
                    if (id != 0L) {
                        // La carátula llega después: se vuelve a avisar cuando está en disco
                        launch {
                            client.artFile(id)?.let { if (lastArtId == id) { artUrl = "file://" + it.absolutePath; emitChanges() } }
                        }
                    }
                }
                emitChanges()
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        runCatching { connection?.releaseBusName(BUS_NAME) }
        runCatching { connection?.close() }
        connection = null
    }

    override fun getObjectPath() = PATH
    override fun isRemote() = false

    // ---------- org.mpris.MediaPlayer2 ----------
    override fun Raise() = onRaise()
    override fun Quit() = onQuit()

    // ---------- org.mpris.MediaPlayer2.Player ----------
    override fun Next() = client.next()
    override fun Previous() = client.previous()
    override fun Pause() { if (client.state.value?.playing == true) client.toggle() }
    override fun PlayPause() = client.toggle()
    override fun Stop() = Pause()
    override fun Play() { if (client.state.value?.playing != true) client.toggle() }

    override fun Seek(offset: Long) {
        val s = client.state.value ?: return
        client.seek((s.positionAt(System.currentTimeMillis()) + offset / 1000).coerceIn(0, s.duration.coerceAtLeast(0)))
    }

    override fun SetPosition(trackId: DBusPath, position: Long) {
        if (trackId.path == trackPath(client.state.value?.id ?: 0)) client.seek(position / 1000)
    }

    override fun OpenUri(uri: String) {}

    // ---------- org.freedesktop.DBus.Properties ----------

    private fun trackPath(id: Long) = if (id == 0L) "/org/mpris/MediaPlayer2/TrackList/NoTrack" else "/com/kevshupp/kevmusic/track/${if (id < 0) "r${-id}" else id}"

    private fun rootProps(): Map<String, Variant<*>> = mapOf(
        "CanQuit" to Variant(true),
        "CanRaise" to Variant(true),
        "HasTrackList" to Variant(false),
        "Identity" to Variant("KevMusic"),
        "DesktopEntry" to Variant("kevmusic-desktop"),
        "SupportedUriSchemes" to Variant(arrayOf<String>(), "as"),
        "SupportedMimeTypes" to Variant(arrayOf<String>(), "as")
    )

    private fun playerProps(includePosition: Boolean): Map<String, Variant<*>> {
        val s = client.state.value
        val connected = client.status.value is ConnStatus.Connected
        val metadata = mutableMapOf<String, Variant<*>>("mpris:trackid" to Variant(DBusPath(trackPath(s?.id ?: 0))))
        if (s != null && s.id != 0L) {
            metadata["xesam:title"] = Variant(s.title)
            metadata["xesam:artist"] = Variant(arrayOf(s.artist), "as")
            metadata["xesam:album"] = Variant(s.album)
            if (s.duration > 0) metadata["mpris:length"] = Variant(s.duration * 1000)
            if (artUrl.isNotEmpty()) metadata["mpris:artUrl"] = Variant(artUrl)
        }
        val props = mutableMapOf<String, Variant<*>>(
            "PlaybackStatus" to Variant(if (s?.playing == true) "Playing" else if (s != null && s.id != 0L) "Paused" else "Stopped"),
            "LoopStatus" to Variant(when (s?.repeat) { 1 -> "Track"; 2 -> "Playlist"; else -> "None" }),
            "Rate" to Variant(1.0),
            "Shuffle" to Variant(s?.shuffle == true),
            "Metadata" to Variant(metadata, "a{sv}"),
            "Volume" to Variant(if (s != null) s.volume.toDouble() / s.volumeMax else 0.0),
            "MinimumRate" to Variant(1.0),
            "MaximumRate" to Variant(1.0),
            "CanGoNext" to Variant(connected),
            "CanGoPrevious" to Variant(connected),
            "CanPlay" to Variant(connected),
            "CanPause" to Variant(connected),
            "CanSeek" to Variant(connected && (s?.duration ?: 0) > 0),
            "CanControl" to Variant(true)
        )
        if (includePosition) props["Position"] = Variant((s?.positionAt(System.currentTimeMillis()) ?: 0L) * 1000)
        return props
    }

    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A {
        val props = if (interfaceName == ROOT) rootProps() else playerProps(includePosition = true)
        return props[propertyName] as A
    }

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        val v = (value as? Variant<*>)?.value ?: value
        when (propertyName) {
            "Volume" -> client.state.value?.let { s -> client.setVolume(((v as? Double ?: 0.0) * s.volumeMax).toInt().coerceIn(0, s.volumeMax)) }
            "Shuffle" -> if ((v as? Boolean ?: false) != (client.state.value?.shuffle == true)) client.toggleShuffle()
            "LoopStatus" -> client.setRepeat(when (v) { "Track" -> 1; "Playlist" -> 2; else -> 0 })
        }
    }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
        if (interfaceName == ROOT) rootProps() else playerProps(includePosition = true)

    /** Avisa a GNOME/Cinnamon solo de las propiedades que cambiaron (la posición no se señala, la piden ellos). */
    @Synchronized
    private fun emitChanges() {
        val conn = connection ?: return
        val now = playerProps(includePosition = false)
        val changed = now.filter { (k, v) -> lastSent[k]?.value?.let { variantEquals(it, v.value) } != true }
        lastSent = now
        if (changed.isEmpty()) return
        runCatching { conn.sendMessage(Properties.PropertiesChanged(PATH, PLAYER, changed, emptyList())) }
    }

    private fun variantEquals(a: Any?, b: Any?): Boolean = when {
        a is Array<*> && b is Array<*> -> a.contentEquals(b)
        a is Map<*, *> && b is Map<*, *> -> a.size == b.size && a.all { (k, v) -> variantEquals((v as? Variant<*>)?.value ?: v, (b[k] as? Variant<*>)?.value ?: b[k]) }
        a is DBusPath && b is DBusPath -> a.path == b.path
        else -> a == b
    }
}
