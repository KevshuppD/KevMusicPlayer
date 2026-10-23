package com.kevshupp.kevmusicplayer.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.data.remote.RemoteControlServer

/**
 * Control desde el PC: activa el servidor de KevMusic Desktop en PlaybackService y muestra
 * la dirección y el código de emparejamiento que hay que escribir en el escritorio.
 */
@Composable
fun RemoteControlSettingsCard(context: android.content.Context, getLocalized: (String, String) -> String) {
    val prefs = remember { context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(RemoteControlServer.isEnabled(context)) }
    val code by RemoteControlServer.pairingCode.collectAsState()
    val connected by RemoteControlServer.connectedClients.collectAsState()
    var paired by remember { mutableStateOf(RemoteControlServer.pairedDevices(context).values.toList()) }
    val ip = remember(enabled) { RemoteControlServer.localIpAddress(context) }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = settingsCardContainerColor()),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(getLocalized("Control desde el PC", "Control from PC"), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = settingsTextColor())
                    Text(
                        getLocalized(
                            "Maneja la reproducción, la cola y la biblioteca con KevMusic Desktop en la misma red Wi-Fi.",
                            "Control playback, queue and library with KevMusic Desktop on the same Wi-Fi network."
                        ),
                        fontSize = 12.sp,
                        color = settingsTextMutedColor()
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        prefs.edit().putBoolean(RemoteControlServer.KEY_ENABLED, it).apply()
                    }
                )
            }
            if (!enabled) return@Column
            HorizontalDivider(color = settingsDividerColor())

            if (code == null) {
                Text(
                    getLocalized("Iniciando servidor… (abre el reproductor si no aparece)", "Starting server… (open the player if it doesn't show up)"),
                    fontSize = 12.sp, color = settingsTextMutedColor()
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(getLocalized("Dirección", "Address"), fontSize = 11.sp, color = settingsTextMutedColor())
                        Text("${ip ?: "—"}:${RemoteControlServer.PORT}", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = settingsTextColor())
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(getLocalized("Código", "Code"), fontSize = 11.sp, color = settingsTextMutedColor())
                        Text(
                            code ?: "",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            letterSpacing = 3.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Text(
                if (connected.isEmpty()) getLocalized("Ningún equipo conectado", "No computer connected")
                else getLocalized("Conectado: ", "Connected: ") + connected.joinToString(", "),
                fontSize = 12.sp,
                color = if (connected.isEmpty()) settingsTextMutedColor() else MaterialTheme.colorScheme.primary
            )
            if (paired.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        getLocalized("Emparejados: ", "Paired: ") + paired.joinToString(", "),
                        fontSize = 12.sp, color = settingsTextMutedColor(), modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        RemoteControlServer.forgetAllDevices(context)
                        paired = emptyList()
                    }) { Text(getLocalized("Olvidar todos", "Forget all")) }
                }
            }
        }
    }
}
