package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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

// La lista de TVs y su control están en la hoja de Dispositivos (DevicesDialog.kt).

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
