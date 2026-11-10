package com.kevshupp.kevmusic.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kevshupp.kevmusic.desktop.ui.ConnectScreen
import com.kevshupp.kevmusic.desktop.ui.KevTheme
import com.kevshupp.kevmusic.desktop.ui.LocalClient
import com.kevshupp.kevmusic.desktop.ui.LocalSoulseek
import com.kevshupp.soulseek.PhoneTarget
import com.kevshupp.soulseek.SoulseekClient
import com.kevshupp.kevmusic.desktop.soulseek.SoulseekConfig
import com.kevshupp.soulseek.SlskStatus
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
    if (Platform.isWindows) return
    runCatching {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(null, WM_CLASS)
    }
}

/** Pantalla de emparejar con el teléfono, abierta a pedido (la app funciona sin teléfono, con la música del PC). */
object ConnectRequest {
    var open by mutableStateOf(false)
}

/** Abierta por el inicio de sesión (Iniciar con el sistema): empieza oculta en la bandeja. */
private var startHidden = false

fun main(args: Array<String>) {
    // Antes de cualquier hilo nativo (mpv, TDLib, Skia): ver Mpv.fixNumericLocale
    com.kevshupp.kevmusic.desktop.local.Mpv.fixNumericLocale()
    startHidden = "--hidden" in args
    // Ya abierta: que se muestre esa (instantáneo) y salir sin cargar Compose
    if (!CDS_TRAINING && Instance.forwardToRunning()) return
    if (!CDS_TRAINING) Instance.listen()
    setWmClass()
    Settings.load()
    Settings.applyRenderer()
    SoulseekConfig.load()
    app()
}

private fun app() = application {
    val scope = rememberCoroutineScope()
    val client = remember { RemoteClient(scope) }
    val discovery = remember { Discovery() }
    val soulseek = remember {
        SoulseekClient(scope, SoulseekConfig, Config.dir).apply {
            phone = object : PhoneTarget {
                override val blocker get() = client.uploadBlocker()
                override fun upload(file: java.io.File, artistHint: String, albumHint: String, onProgress: (Long) -> Unit) =
                    client.uploadSong(file, artistHint, albumHint, onProgress)
            }
        }
    }
    var raiseRequest by remember { mutableStateOf(0) }
    var minimizeRequest by remember { mutableStateOf(0) }
    var minimizeAtStart by remember { mutableStateOf(false) }
    var windowVisible by remember { mutableStateOf(!startHidden) }
    DisposableEffect(Unit) {
        Instance.onShow = { windowVisible = true; raiseRequest++ }
        onDispose { Instance.onShow = null }
    }
    val mpris = remember {
        Mpris(client, scope, onRaise = { windowVisible = true; raiseRequest++ }, onQuit = {
            client.disconnect(forget = false)
            discovery.stop()
            soulseek.stop()
            exitApplication()
        })
    }
    val tray = remember { StatusNotifier(client) { windowVisible = true; raiseRequest++ } }
    fun quit() {
        client.local.close()
        client.disconnect(forget = false)
        discovery.stop()
        mpris.stop()
        tray.stop()
        soulseek.stop()
        exitApplication()
    }
    val status by client.status.collectAsState()
    var section by remember { mutableStateOf(Section.Home) }
    // Sin teléfono emparejado la app no espera a uno: abre con la música del PC (modo local)
    LaunchedEffect(Unit) { if (Config.load() == null && !CDS_TRAINING) client.setPlayOnPc(true) }

    // Cuenta de Soulseek: el PC sin cuenta toma la del teléfono; tras iniciar sesión, se la guarda al teléfono
    LaunchedEffect(Unit) {
        client.slskAccount.collect { acc ->
            if (acc != null && !SoulseekConfig.hasAccount) {
                SoulseekConfig.username = acc.first; SoulseekConfig.password = acc.second; SoulseekConfig.autoConnect = true
                SoulseekConfig.save()
                soulseek.start()
            }
        }
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.flow.combine(soulseek.status, client.status) { s, c -> s to c }.collect { (s, c) ->
            if (s is SlskStatus.Online && c is ConnStatus.Connected) client.saveSlskAccount(SoulseekConfig.username, SoulseekConfig.password)
        }
    }
    LaunchedEffect(Unit) {
        // cds-train.sh: abrir la ventana, dibujar unos segundos y salir, para que la JVM guarde las clases usadas
        // (AppCDS) sin conectar con nada
        if (CDS_TRAINING) {
            kotlinx.coroutines.delay(6000)
            exitApplication()
            return@LaunchedEffect
        }
        Config.load()?.let { client.resume(it) }
        // Teléfono guardado que no responde al abrir: mientras tanto, la música de este PC
        if (Config.load() != null && !client.onPc.value) launch {
            kotlinx.coroutines.delay(6000)
            if (client.status.value !is ConnStatus.Connected) client.fallbackToPcIfOffline()
        }
        discovery.start()
        // La conexión a D-Bus tarda: fuera del hilo de la interfaz para no retrasar el primer dibujo
        if (!Platform.isWindows) launch(kotlinx.coroutines.Dispatchers.IO) { mpris.start() }
        if (SoulseekConfig.autoConnect && SoulseekConfig.hasAccount) soulseek.start()
        if (Settings.autoCheckUpdates) Updater.check()
    }
    LaunchedEffect(status) {
        // Recién emparejado desde la pantalla de conexión: pasa a sonar en el teléfono
        if (status is ConnStatus.Connected && ConnectRequest.open) { ConnectRequest.open = false; client.setPlayOnPc(false) }
        // Desvinculado: de vuelta a la música del PC
        if (status is ConnStatus.Disconnected && Config.load() == null) client.setPlayOnPc(true)
    }

    // Bandeja del sistema: en Linux por D-Bus (ver StatusNotifier), en Windows la de AWT (Tray de Compose, abajo);
    // si no hay bandeja, la X minimiza
    LaunchedEffect(Settings.closeAction) {
        val want = Settings.closeAction == Settings.CLOSE_TRAY
        TrayState.active = if (Platform.isWindows) want && java.awt.SystemTray.isSupported()
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            tray.stop()
            if (want) tray.start() else null
        }
        // Al quitar la bandeja (o si no hay), que la ventana no se quede oculta sin forma de volver
        if (!want || TrayState.active != true) {
            if (!windowVisible && startHidden) minimizeAtStart = true
            windowVisible = true
        }
    }
    val trayAvailable = TrayState.active == true
    if (Platform.isWindows && trayAvailable) {
        Tray(
            icon = painterResource("icon.png"),
            tooltip = "KevMusic Desktop",
            onAction = { windowVisible = true; raiseRequest++ },
            menu = {
                Item("Mostrar", onClick = { windowVisible = true; raiseRequest++ })
                Item("Reproducir / Pausa", onClick = client::toggle)
                Item("Siguiente", onClick = client::next)
                Item("Anterior", onClick = client::previous)
                Separator()
                Item("Salir", onClick = ::quit)
            }
        )
    }

    Window(
        visible = windowVisible,
        onCloseRequest = {
            when {
                Settings.closeAction == Settings.CLOSE_TRAY && trayAvailable -> windowVisible = false
                Settings.closeAction != Settings.CLOSE_EXIT -> minimizeRequest++
                else -> quit()
            }
        },
        title = "KevMusic Desktop",
        icon = painterResource("icon.png"),
        state = rememberWindowState(size = DpSize(1240.dp, 800.dp))
    ) {
        window.minimumSize = java.awt.Dimension(960, 620)
        LaunchedEffect(minimizeRequest, minimizeAtStart) { if (minimizeRequest > 0 || minimizeAtStart) window.isMinimized = true; minimizeAtStart = false }
        LaunchedEffect(raiseRequest) {
            if (raiseRequest > 0) { window.isMinimized = false; window.toFront(); window.requestFocus() }
        }
        run {
            MenuBar {
                Menu("Archivo") {
                    Item("Salir", shortcut = KeyShortcut(Key.Q, ctrl = true), onClick = ::quit)
                }
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
                if (ConnectRequest.open) ConnectScreen(discovery, status)
                else MainScreen(status, section) { section = it }
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
