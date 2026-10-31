package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.cast.CastController

/** Elegir dispositivo y controlar lo que suena en él. [queue] da la cola desde la canción actual y su posición. */
@Composable
fun CastDialog(queue: () -> Pair<List<AudioFile>, Long>, onStarted: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by CastController.state.collectAsState()
    val devices by CastController.devices.collectAsState()
    var available by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        available = CastController.startDiscovery(context)
        onDispose { CastController.stopDiscovery() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Cast, null) },
        title = { Text(state.device ?: "Transmitir a") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when {
                    !available -> Text("Este teléfono no tiene los servicios de Google que Cast necesita.")
                    state.connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Conectando…")
                    }
                    state.device != null -> {
                        Text(state.title.ifBlank { "Cargando…" }, fontWeight = FontWeight.SemiBold)
                        Text("El ecualizador y el crossfade no se aplican al transmitir.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        var volume by remember { mutableStateOf(CastController.volume()) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.VolumeUp, "Volumen de la TV")
                            Spacer(Modifier.width(8.dp))
                            Slider(value = volume, onValueChange = { volume = it; CastController.setVolume(it) })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            IconButton(onClick = CastController::previous) { Icon(Icons.Rounded.SkipPrevious, "Anterior") }
                            IconButton(onClick = CastController::togglePlay) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Reproducir o pausar") }
                            IconButton(onClick = CastController::next) { Icon(Icons.Rounded.SkipNext, "Siguiente") }
                        }
                    }
                    devices.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Buscando en tu Wi-Fi…")
                    }
                    else -> devices.forEach { device ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val (songs, position) = queue()
                                CastController.castTo(device, songs, position)
                                onStarted()
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Tv, null)
                            Spacer(Modifier.width(12.dp))
                            Text(device.name)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = {
            if (state.device != null) TextButton(onClick = { CastController.disconnect() }) { Text("Dejar de transmitir") }
        }
    )
}

/** El sistema solo dibuja su barra para el volumen del teléfono; la del Chromecast la pone la app. */
@Composable
fun CastVolumeIndicator() {
    val change by CastController.volumeChange.collectAsState()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(change) {
        if (change == null) return@LaunchedEffect
        visible = true
        kotlinx.coroutines.delay(1500)
        visible = false
    }
    val level = change?.level ?: 0f
    androidx.compose.animation.AnimatedVisibility(visible, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CastConnected, "Volumen de la TV", Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    LinearProgressIndicator(progress = { level }, modifier = Modifier.width(150.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("${(level * 100).toInt()} %", fontSize = 13.sp)
                }
            }
        }
    }
}
