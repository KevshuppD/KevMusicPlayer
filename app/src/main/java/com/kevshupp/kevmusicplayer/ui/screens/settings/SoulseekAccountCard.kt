package com.kevshupp.kevmusicplayer.ui.screens.settings

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.data.PreferenceConstants
import com.kevshupp.kevmusicplayer.data.remote.SoulseekAccount

/** Cuenta de Soulseek que KevMusic Desktop guardó en el teléfono (viaja en la copia, sección Ajustes). */
@Composable
fun SoulseekAccountCard(getLocalized: (String, String) -> String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var account by remember { mutableStateOf(SoulseekAccount.get(context)) }
    var showForget by remember { mutableStateOf(false) }

    // El PC la guarda por el control remoto y restaurar una copia la cambia: seguir las preferencias
    DisposableEffect(Unit) {
        val prefs = PreferenceConstants.getSettingsPrefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == SoulseekAccount.KEY_USER || key == SoulseekAccount.KEY_PASS) account = SoulseekAccount.get(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = settingsCardContainerColor()),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Cabecera, como la de Google Cloud Sync
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(
                        Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Computer, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Soulseek", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = settingsTextColor())
                    Text(
                        getLocalized("Cuenta de KevMusic Desktop", "KevMusic Desktop account"),
                        fontSize = 12.sp,
                        color = settingsTextMutedColor()
                    )
                }
            }

            HorizontalDivider(color = settingsDividerColor())

            // Cuenta guardada o estado vacío
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (account != null) Icons.Rounded.AccountCircle else Icons.Rounded.CloudOff, null,
                    tint = if (account != null) MaterialTheme.colorScheme.primary else settingsTextMutedColor(),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        account?.first ?: getLocalized("Ninguna cuenta guardada", "No account saved"),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = settingsTextColor(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (account != null) getLocalized("Contraseña guardada y cifrada", "Password saved and encrypted")
                        else getLocalized("Inicia sesión en Soulseek desde el PC", "Sign in to Soulseek from the PC"),
                        fontSize = 12.sp, color = settingsTextMutedColor()
                    )
                }
                if (account != null) {
                    IconButton(onClick = { showForget = true }) {
                        Icon(Icons.Rounded.DeleteOutline, getLocalized("Olvidar cuenta", "Forget account"), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f))
                    }
                }
            }

            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Info, null, tint = settingsTextMutedColor(), modifier = Modifier.size(16.dp).padding(top = 1.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    getLocalized(
                        "El PC la guarda aquí al iniciar sesión con el teléfono conectado, y un PC sin cuenta la recibe al conectarse. Va en la copia si incluyes los Ajustes.",
                        "The PC saves it here when signing in with the phone connected, and a PC without one receives it on connect. It goes in the backup if Settings are included."
                    ),
                    fontSize = 12.sp, lineHeight = 16.sp, color = settingsTextMutedColor()
                )
            }
        }
    }

    if (showForget) {
        AlertDialog(
            onDismissRequest = { showForget = false },
            title = { Text(getLocalized("Olvidar cuenta de Soulseek", "Forget Soulseek account"), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    getLocalized(
                        "Se borra del teléfono y de la próxima copia. Los PC que ya la tienen la conservan.",
                        "It is removed from the phone and the next backup. PCs that already have it keep it."
                    ),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { showForget = false; SoulseekAccount.clear(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(getLocalized("Olvidar", "Forget"), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showForget = false }) { Text(getLocalized("Cancelar", "Cancel"), color = settingsTextMutedColor()) }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}
