package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.data.remote.RemoteControlServer

/**
 * Dónde suena la música: este teléfono o un PC con KevMusic Desktop conectado (protocolo 7). Elegir un PC le pasa
 * la cola y la posición; elegir el teléfono la trae de vuelta. La TV sigue en el botón de Transmitir.
 */
@Composable
fun DevicesDialog(onDismiss: () -> Unit) {
    val pcs by RemoteControlServer.handoffTargets.collectAsState()
    val playback by RemoteControlServer.pcPlayback.collectAsState()
    val active = playback

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Devices, null) },
        title = { Text("Dispositivos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceRow(Icons.Rounded.PhoneAndroid, "Este teléfono", if (active == null) "Sonando aquí" else "Toca para traer la música", active == null) {
                    active?.let { RemoteControlServer.handoffBackRequests.tryEmit(it.pc) }
                    onDismiss()
                }
                pcs.forEach { pc ->
                    val here = active?.pc == pc
                    DeviceRow(
                        Icons.Rounded.Computer, pc,
                        when {
                            here && active!!.playing -> "Sonando · ${active.title}"
                            here -> "En pausa · ${active!!.title}"
                            else -> "KevMusic Desktop · sonar en el PC"
                        },
                        here
                    ) {
                        if (!here) RemoteControlServer.handoffRequests.tryEmit(pc)
                        onDismiss()
                    }
                }
                if (pcs.isEmpty()) Text(
                    "Abre KevMusic Desktop en tu PC (con \"Control desde el PC\" activo en Ajustes › Complementos) para que aparezca aquí.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, detail: String, current: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (current) primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, color = if (current) primary else MaterialTheme.colorScheme.onSurface)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (current) Icon(Icons.Rounded.VolumeUp, null, tint = primary, modifier = Modifier.size(20.dp))
    }
}
