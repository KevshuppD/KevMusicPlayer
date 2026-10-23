package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.ConnStatus
import com.kevshupp.kevmusic.desktop.Discovery

private const val DEFAULT_PORT = 47820

@Composable
fun ConnectScreen(discovery: Discovery, status: ConnStatus) {
    val client = LocalClient.current
    val found by discovery.found.collectAsState()
    var host by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val connecting = status is ConnStatus.Connecting

    fun connect() {
        val (h, p) = host.trim().split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: DEFAULT_PORT) }
        if (h.isNotBlank() && code.length == 6) client.pair(h, p, code)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.width(520.dp)
        ) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("KevMusic Desktop", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(
                    "En el teléfono abre Ajustes › Sistema y activa \"Control desde el PC\". " +
                        "Los dos equipos tienen que estar en la misma red Wi-Fi.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
                )

                Text("Teléfonos encontrados", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                if (found.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Buscando en la red… también puedes escribir la dirección.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                found.forEach { phone ->
                    val address = "${phone.host}:${phone.port}"
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (host == address) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { host = address }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(phone.name, fontWeight = FontWeight.Medium)
                            Text(address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                OutlinedTextField(
                    value = host, onValueChange = { host = it.trim() },
                    label = { Text("Dirección del teléfono (IP o IP:puerto)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = code, onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                    label = { Text("Código de 6 cifras") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                if (status is ConnStatus.Failed) {
                    Text(status.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Button(
                    onClick = { connect() },
                    enabled = !connecting && host.isNotBlank() && code.length == 6,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Emparejar", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
