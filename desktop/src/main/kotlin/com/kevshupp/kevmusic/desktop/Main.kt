package com.kevshupp.kevmusic.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kevshupp.kevmusic.desktop.ui.ConnectScreen
import com.kevshupp.kevmusic.desktop.ui.KevTheme
import com.kevshupp.kevmusic.desktop.ui.LocalClient
import com.kevshupp.kevmusic.desktop.ui.LocalSoulseek
import com.kevshupp.kevmusic.desktop.soulseek.PhoneTarget
import com.kevshupp.kevmusic.desktop.soulseek.SoulseekClient
import com.kevshupp.kevmusic.desktop.soulseek.SoulseekConfig
import com.kevshupp.kevmusic.desktop.ui.MainScreen
import com.kevshupp.kevmusic.desktop.ui.Section
import com.kevshupp.kevmusic.desktop.ui.UpdateDialog
import kotlinx.coroutines.launch

/**
 * Nombre de la ventana para el gestor de ventanas (WM_CLASS). Por defecto AWT usa el de la clase principal
 * ("com-kevshupp-kevmusic-desktop-MainKt"), que no coincide con ningún lanzador: Cinnamon no podía fijar la app
 * al panel y le ponía un icono genérico. Debe coincidir con el .desktop que instala el .deb.
 * Necesita `--add-opens java.desktop/sun.awt.X11=ALL-UNNAMED` (jvmArgs en build.gradle.kts).
 */
private const val WM_CLASS = "kevmusic-desktop-kevmusic-desktop"

/** Arranque de entrenamiento para el archivo de clases (ver desktop/cds-train.sh). */
private val CDS_TRAINING = System.getenv("KEVMUSIC_CDS_TRAIN") == "1"

private fun setWmClass() {
    runCatching {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(null, WM_CLASS)
    }
}

fun main() {
    setWmClass()
    Settings.load()
    SoulseekConfig.load()
    app()
}

private fun app() = application {
    val scope = rememberCoroutineScope()
    val client = remember { RemoteClient(scope) }
    val discovery = remember { Discovery() }
    val soulseek = remember {
        SoulseekClient(scope).apply {
            phone = object : PhoneTarget {
                override val blocker get() = client.uploadBlocker()
                override fun upload(file: java.io.File, artistHint: String, albumHint: String, onProgress: (Long) -> Unit) =
                    client.uploadSong(file, artistHint, albumHint, onProgress)
            }
        }
    }
    var raiseRequest by remember { mutableStateOf(0) }
    val mpris = remember {
        Mpris(client, scope, onRaise = { raiseRequest++ }, onQuit = {
            client.disconnect(forget = false)
            discovery.stop()
            soulseek.stop()
            exitApplication()
        })
    }
    val status by client.status.collectAsState()
    var section by remember { mutableStateOf(Section.Home) }
    // Pantalla principal en cuanto hay un teléfono emparejado; si se cae la conexión, se queda y reintenta
    var paired by remember { mutableStateOf(Config.load() != null) }

    LaunchedEffect(Unit) {
        // cds-train.sh: abrir la ventana, dibujar unos segundos y salir, para que la JVM guarde las clases usadas
        // (AppCDS) sin conectar con nada
        if (CDS_TRAINING) {
            kotlinx.coroutines.delay(6000)
            exitApplication()
            return@LaunchedEffect
        }
        Config.load()?.let { client.resume(it) }
        discovery.start()
        // La conexión a D-Bus tarda: fuera del hilo de la interfaz para no retrasar el primer dibujo
        launch(kotlinx.coroutines.Dispatchers.IO) { mpris.start() }
        if (SoulseekConfig.autoConnect && SoulseekConfig.hasAccount) soulseek.start()
        if (Settings.autoCheckUpdates) Updater.check()
    }
    LaunchedEffect(status) {
        if (status is ConnStatus.Connected) paired = true
        if (status is ConnStatus.Disconnected || (status is ConnStatus.Failed && Config.load() == null)) paired = false
    }

    Window(
        onCloseRequest = {
            client.disconnect(forget = false)
            discovery.stop()
            mpris.stop()
            soulseek.stop()
            exitApplication()
        },
        title = "KevMusic Desktop",
        icon = painterResource("icon.png"),
        state = rememberWindowState(size = DpSize(1240.dp, 800.dp))
    ) {
        window.minimumSize = java.awt.Dimension(960, 620)
        LaunchedEffect(raiseRequest) {
            if (raiseRequest > 0) { window.isMinimized = false; window.toFront(); window.requestFocus() }
        }
        if (paired) {
            MenuBar {
                Menu("Reproducción") {
                    Item("Reproducir / Pausa", shortcut = KeyShortcut(Key.P, ctrl = true), onClick = client::toggle)
                    Item("Siguiente", shortcut = KeyShortcut(Key.DirectionRight, ctrl = true), onClick = client::next)
                    Item("Anterior", shortcut = KeyShortcut(Key.DirectionLeft, ctrl = true), onClick = client::previous)
                    Item("Subir volumen", shortcut = KeyShortcut(Key.DirectionUp, ctrl = true)) {
                        client.state.value?.let { client.setVolume((it.volume + 1).coerceAtMost(it.volumeMax)) }
                    }
                    Item("Bajar volumen", shortcut = KeyShortcut(Key.DirectionDown, ctrl = true)) {
                        client.state.value?.let { client.setVolume((it.volume - 1).coerceAtLeast(0)) }
                    }
                }
                Menu("Ver") {
                    Section.entries.forEachIndexed { i, s ->
                        Item(s.label, shortcut = KeyShortcut(Key(Key.One.keyCode + i), ctrl = true)) { section = s }
                    }
                }
            }
        }
        val phoneTheme by client.phoneTheme.collectAsState()
        KevTheme(if (Settings.theme == Settings.THEME_PHONE) phoneTheme else Settings.theme) {
            CompositionLocalProvider(LocalClient provides client, LocalSoulseek provides soulseek) {
                if (paired) MainScreen(status, section) { section = it }
                else ConnectScreen(discovery, status)
                UpdateDialog(onExit = {
                    client.disconnect(forget = false)
                    discovery.stop()
                    mpris.stop()
                    exitApplication()
                })
            }
        }
    }
}
