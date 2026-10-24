@file:Suppress("FunctionName", "UNCHECKED_CAST")

package com.kevshupp.kevmusic.desktop

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.awt.RenderingHints
import java.awt.image.BufferedImage

@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItem : DBusInterface {
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun ContextMenu(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)
}

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

/** Si el icono de la bandeja está puesto (null: no se ha intentado). Lo lee Ajustes. */
object TrayState {
    var active by androidx.compose.runtime.mutableStateOf<Boolean?>(null)
}

/** Imagen ARGB32 (orden de red) del protocolo StatusNotifierItem. */
class IconPixmap(@field:Position(0) val width: Int, @field:Position(1) val height: Int, @field:Position(2) val data: ByteArray) : Struct()

/**
 * Icono en la bandeja del sistema por D-Bus (StatusNotifierItem, el que usan Cinnamon con xapp-sn-watcher, KDE y
 * GNOME con AppIndicator). La bandeja de AWT (`java.awt.SystemTray`) usa el protocolo XEmbed antiguo y en Linux
 * Mint devuelve `isSupported() == false`, así que la opción "Seguir en la bandeja" no podía funcionar con ella.
 * Clic: mostrar u ocultar la ventana · clic central: reproducir/pausa · rueda: volumen del teléfono.
 * Sin menú propio (DBusMenu): ItemIsMenu = false y ContextMenu también muestra la ventana.
 */
class StatusNotifier(
    private val client: RemoteClient,
    private val onActivate: () -> Unit
) : StatusNotifierItem, Properties {
    private companion object {
        const val PATH = "/StatusNotifierItem"
        const val ITEM = "org.kde.StatusNotifierItem"
    }

    private var connection: DBusConnection? = null
    private var busName: String? = null
    private val pixmaps: Array<IconPixmap> by lazy { loadPixmaps() }

    /** true si un vigilante de la bandeja aceptó el icono. */
    fun start(): Boolean = runCatching {
        val conn = DBusConnectionBuilder.forSessionBus().build()
        val name = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
        conn.requestBusName(name)
        conn.exportObject(PATH, this)
        conn.getRemoteObject("org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher", StatusNotifierWatcher::class.java)
            .RegisterStatusNotifierItem(name)
        connection = conn
        busName = name
        true
    }.getOrElse {
        System.err.println("Bandeja no disponible: ${it.message}")
        stop()
        false
    }

    fun stop() {
        runCatching { busName?.let { connection?.releaseBusName(it) } }
        runCatching { connection?.close() }
        connection = null
        busName = null
    }

    override fun getObjectPath() = PATH
    override fun isRemote() = false

    override fun Activate(x: Int, y: Int) = onActivate()
    override fun SecondaryActivate(x: Int, y: Int) = client.toggle()
    override fun ContextMenu(x: Int, y: Int) = onActivate()
    override fun Scroll(delta: Int, orientation: String) {
        if (orientation.lowercase() != "vertical") return
        val s = client.state.value ?: return
        // La rueda manda pasos de 120 (o de 1 en algunos paneles): un paso de volumen por gesto
        val step = if (delta > 0) 1 else if (delta < 0) -1 else 0
        client.setVolume((s.volume + step).coerceIn(0, s.volumeMax))
    }

    private fun props(): Map<String, Variant<*>> = mapOf(
        "Category" to Variant("ApplicationStatus"),
        "Id" to Variant("kevmusic-desktop"),
        "Title" to Variant("KevMusic Desktop"),
        "Status" to Variant("Active"),
        "WindowId" to Variant(org.freedesktop.dbus.types.UInt32(0)),
        "IconName" to Variant(""),
        "IconPixmap" to Variant(pixmaps, "a(iiay)"),
        "ItemIsMenu" to Variant(false),
        "Menu" to Variant(DBusPath("/NO_DBUSMENU"))
    )

    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = props()[propertyName] as A
    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {}
    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = if (interfaceName == ITEM) props() else emptyMap()

    private fun loadPixmaps(): Array<IconPixmap> {
        val source = javax.imageio.ImageIO.read(StatusNotifier::class.java.getResourceAsStream("/icon.png") ?: return emptyArray())
        return intArrayOf(22, 32, 48, 64).map { size ->
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            img.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(source, 0, 0, size, size, null)
                dispose()
            }
            val data = ByteArray(size * size * 4)
            var i = 0
            for (y in 0 until size) for (x in 0 until size) {
                val argb = img.getRGB(x, y)
                data[i++] = (argb ushr 24).toByte()
                data[i++] = (argb ushr 16).toByte()
                data[i++] = (argb ushr 8).toByte()
                data[i++] = argb.toByte()
            }
            IconPixmap(size, size, data)
        }.toTypedArray()
    }
}
