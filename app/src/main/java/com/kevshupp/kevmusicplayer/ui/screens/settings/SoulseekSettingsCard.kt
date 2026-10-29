package com.kevshupp.kevmusicplayer.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.data.PreferenceConstants
import com.kevshupp.kevmusicplayer.data.soulseek.SoulseekManager
import com.kevshupp.soulseek.SlskStatus

/** Módulo opcional de Soulseek: activarlo, abrirlo y decidir si se comparte (y si también con datos móviles). */
@Composable
fun SoulseekSettingsCard(
    context: android.content.Context,
    getLocalized: (String, String) -> String,
    onOpen: () -> Unit
) {
    val prefs = remember { PreferenceConstants.getSettingsPrefs(context) }
    val enabled by SoulseekManager.enabled.collectAsState()
    val unmetered by SoulseekManager.unmetered.collectAsState()
    var share by remember { mutableStateOf(prefs.getBoolean(SoulseekManager.KEY_SHARE, false)) }
    var shareMobile by remember { mutableStateOf(prefs.getBoolean(SoulseekManager.KEY_SHARE_MOBILE, false)) }
    var slots by remember { mutableIntStateOf(prefs.getInt(SoulseekManager.KEY_UPLOAD_SLOTS, 2)) }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = settingsCardContainerColor()),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Soulseek", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = settingsTextColor())
                    Text(
                        getLocalized(
                            "Busca y descarga álbumes de la red Soulseek a tu biblioteca. Misma cuenta que KevMusic Desktop.",
                            "Search and download albums from the Soulseek network into your library. Same account as KevMusic Desktop."
                        ),
                        fontSize = 12.sp, color = settingsTextMutedColor()
                    )
                }
                Switch(checked = enabled, onCheckedChange = { SoulseekManager.setEnabled(it) })
            }
            if (!enabled) return@Column

            val status by SoulseekManager.client.status.collectAsState()
            val shareStats by SoulseekManager.client.shareStats.collectAsState()
            Text(
                when (val s = status) {
                    is SlskStatus.Online -> getLocalized("Conectado como ", "Connected as ") + s.username
                    SlskStatus.Connecting -> getLocalized("Conectando…", "Connecting…")
                    is SlskStatus.Failed -> s.message
                    SlskStatus.Off -> getLocalized("Sin conectar: abre Soulseek para iniciar sesión", "Not connected: open Soulseek to sign in")
                },
                fontSize = 12.sp, color = if (status is SlskStatus.Failed) MaterialTheme.colorScheme.error else settingsTextMutedColor()
            )
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text(getLocalized("Abrir Soulseek", "Open Soulseek")) }

            HorizontalDivider(color = settingsDividerColor())
            SwitchLine(
                getLocalized("Compartir mi música", "Share my music"),
                getLocalized(
                    "Otros usuarios pueden buscar y descargar las canciones de tus carpetas de música. Usa datos y batería mientras sube.",
                    "Other users can search and download the songs in your music folders. Uses data and battery while uploading."
                ),
                share
            ) {
                share = it
                prefs.edit().putBoolean(SoulseekManager.KEY_SHARE, it).apply()
            }
            if (share) {
                SwitchLine(
                    getLocalized("Compartir también con datos móviles", "Share on mobile data too"),
                    getLocalized(
                        "Apagado, solo compartes en Wi‑Fi. Las descargas funcionan en cualquier red.",
                        "Off, you only share on Wi‑Fi. Downloads work on any network."
                    ),
                    shareMobile
                ) {
                    shareMobile = it
                    prefs.edit().putBoolean(SoulseekManager.KEY_SHARE_MOBILE, it).apply()
                }
                Text(
                    getLocalized("Subidas a la vez", "Simultaneous uploads") + ": $slots",
                    fontSize = 13.sp, color = settingsTextColor()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..5).forEach { n ->
                        FilterChip(selected = slots == n, onClick = {
                            slots = n
                            prefs.edit().putInt(SoulseekManager.KEY_UPLOAD_SLOTS, n).apply()
                        }, label = { Text("$n") })
                    }
                }
                Text(
                    when {
                        !unmetered && !shareMobile -> getLocalized("Con datos móviles: no se comparte ahora.", "On mobile data: not sharing right now.")
                        else -> getLocalized("Compartiendo ", "Sharing ") + "${shareStats.second} " +
                            getLocalized("canciones en ", "songs in ") + "${shareStats.first} " + getLocalized("carpetas", "folders")
                    },
                    fontSize = 12.sp, color = settingsTextMutedColor()
                )
            }
        }
    }
}

@Composable
private fun SwitchLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = settingsTextColor())
            Text(subtitle, fontSize = 12.sp, color = settingsTextMutedColor())
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
