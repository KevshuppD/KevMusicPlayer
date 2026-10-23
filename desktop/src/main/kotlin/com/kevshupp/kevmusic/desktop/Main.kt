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
import com.kevshupp.kevmusic.desktop.ui.MainScreen
import com.kevshupp.kevmusic.desktop.ui.Section
import com.kevshupp.kevmusic.desktop.ui.UpdateDialog

fun main() {
    Settings.load()
    app()
}

private fun app() = application {
    val scope = rememberCoroutineScope()
    val client = remember { RemoteClient(scope) }
    val discovery = remember { Discovery() }
    var raiseRequest by remember { mutableStateOf(0) }
    val mpris = remember {
        Mpris(client, scope, onRaise = { raiseRequest++ }, onQuit = {
            client.disconnect(forget = false)
            discovery.stop()
            exitApplication()
        })
    }
    val status by client.status.collectAsState()
    var section by remember { mutableStateOf(Section.Home) }
    // Pantalla principal en cuanto hay un teléfono emparejado; si se cae la conexión, se queda y reintenta
    var paired by remember { mutableStateOf(Config.load() != null) }

    LaunchedEffect(Unit) {
        Config.load()?.let { client.resume(it) }
        discovery.start()
        mpris.start()
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
            CompositionLocalProvider(LocalClient provides client) {
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
