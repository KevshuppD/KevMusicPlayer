package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.CloudSession
import com.kevshupp.kevmusic.desktop.ConnStatus
import com.kevshupp.kevmusic.desktop.TelegramPc

private val OkGreen = Color(0xFF4CAF50)

/**
 * Botón de perfil del Inicio (como el de la app): foto de la cuenta de Google con un punto verde si hay sesión en
 * la nube. Al tocarlo muestra la cuenta y el estado de la nube, el teléfono y Telegram, con acceso a Ajustes.
 */
@Composable
fun ProfileButton(onOpenSettings: () -> Unit) {
    val client = LocalClient.current
    val account by CloudSession.account.collectAsState()
    val cloudError by CloudSession.error.collectAsState()
    val status by client.status.collectAsState()
    val tgState by TelegramPc.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    val a = account

    Box {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable { open = true }, contentAlignment = Alignment.Center) {
            Avatar(a?.photo.orEmpty(), a?.name?.ifBlank { a.email }.orEmpty(), 40.dp)
            // Punto de estado de la nube
            Box(
                Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape)
                    .background(if (a != null && cloudError == null) OkGreen else MaterialTheme.colorScheme.outline)
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
            )
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Column(Modifier.width(300.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(a?.photo.orEmpty(), a?.name?.ifBlank { a.email }.orEmpty(), 52.dp)
                    Column {
                        Text(a?.name?.ifBlank { null } ?: a?.email ?: "Sin sesión", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (a != null && a.name.isNotBlank()) a.email else if (a == null) "Conecta el teléfono para entrar con su cuenta" else "",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
                StatusRow(
                    Icons.Rounded.Cloud, "Nube",
                    when {
                        a == null -> "Sin sesión"
                        cloudError != null -> cloudError!!
                        else -> "Conectada · tus reproducciones se sincronizan"
                    },
                    a != null && cloudError == null
                )
                StatusRow(
                    Icons.Rounded.PhoneAndroid, "Teléfono",
                    when (val s = status) {
                        is ConnStatus.Connected -> "Conectado · ${s.device}"
                        is ConnStatus.Connecting -> "Conectando…"
                        is ConnStatus.Failed -> "Sin conexión"
                        ConnStatus.Disconnected -> "Sin conexión"
                    },
                    status is ConnStatus.Connected
                )
                StatusRow(
                    Icons.Rounded.Send, "Telegram en este PC",
                    when {
                        tgState == com.kevshupp.kevmusic.desktop.TgState.Ready -> "Listo" + (TelegramPc.bot.takeIf { it.isNotBlank() }?.let { " · @$it" } ?: "")
                        !TelegramPc.configured -> "No disponible en esta compilación"
                        !TelegramPc.hasSession -> "Sin sesión: inicia sesión en Ajustes"
                        TelegramPc.bot.isBlank() -> "Falta el bot: conecta el teléfono o escríbelo en Ajustes"
                        else -> "Listo para bajo demanda" + " · @${TelegramPc.bot}"
                    },
                    TelegramPc.ready
                )
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { open = false; onOpenSettings() }) { Text("Ajustes") }
                    if (a != null) OutlinedButton(onClick = { open = false; CloudSession.signOut() }) { Text("Cerrar sesión") }
                }
            }
        }
    }
}

@Composable
private fun Avatar(photo: String, name: String, size: androidx.compose.ui.unit.Dp) {
    if (photo.startsWith("http")) UrlImage(photo, size, round = true)
    else Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val initial = name.trim().firstOrNull()?.uppercase()
        if (initial != null) Text(initial, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        else Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun StatusRow(icon: ImageVector, title: String, detail: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) OkGreen else MaterialTheme.colorScheme.outline))
    }
}
