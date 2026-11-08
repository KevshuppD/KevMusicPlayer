package com.kevshupp.kevmusic.desktop

import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * Una sola instancia: la primera escucha en un socket Unix (`<config>/instance.sock`); abrir la app otra vez
 * (menú, lanzador) conecta, le pide mostrar la ventana y sale. Con "Iniciar con el sistema" la app ya está
 * oculta en la bandeja y abrirla es instantáneo, sin arrancar otra JVM con todo Compose.
 */
object Instance {
    private val socket = File(Config.dir, "instance.sock")

    /** Lo pone la ventana: mostrar y traer al frente. */
    @Volatile var onShow: (() -> Unit)? = null

    /** true si otra instancia la recibió (y esta debe salir). */
    fun forwardToRunning(): Boolean = runCatching {
        if (!socket.exists()) return false
        SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
            ch.connect(UnixDomainSocketAddress.of(socket.toPath()))
            ch.write(ByteBuffer.wrap("show\n".toByteArray()))
        }
        true
    }.getOrDefault(false)

    /** Empieza a escuchar; un socket huérfano (la app murió) se reemplaza. */
    fun listen() {
        runCatching {
            Config.dir.mkdirs()
            socket.delete()
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            server.bind(UnixDomainSocketAddress.of(socket.toPath()))
            socket.deleteOnExit()
            Thread({
                while (true) {
                    val ch = runCatching { server.accept() }.getOrNull() ?: break
                    runCatching { ch.use { it.read(ByteBuffer.allocate(16)) } }
                    javax.swing.SwingUtilities.invokeLater { onShow?.invoke() }
                }
            }, "kevmusic-instance").apply { isDaemon = true }.start()
        }
    }
}

/**
 * "Iniciar con el sistema" (solo Linux): un .desktop en ~/.config/autostart que abre la app oculta
 * (`--hidden`) en la bandeja al iniciar sesión.
 */
object Autostart {
    private val file = File(System.getProperty("user.home"), ".config/autostart/kevmusic-desktop.desktop")

    /** El lanzador de jpackage; null al ejecutar desde Gradle (desarrollo). */
    val launcher: String? get() = System.getProperty("jpackage.app-path")?.takeIf { File(it).canExecute() }

    val supported get() = !Platform.isWindows && launcher != null

    fun apply(enabled: Boolean) {
        runCatching {
            if (!enabled) { file.delete(); return }
            val exe = launcher ?: return
            file.parentFile.mkdirs()
            file.writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=KevMusic Desktop
                Comment=Abre KevMusic Desktop oculto en la bandeja
                Exec="$exe" --hidden
                Icon=${File(exe).parentFile.parentFile.resolve("lib/kevmusic-desktop.png").absolutePath}
                X-GNOME-Autostart-enabled=true
                NoDisplay=false
                """.trimIndent() + "\n"
            )
        }
    }
}
