package com.kevshupp.kevmusicplayer.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.data.cast.CastController
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import com.kevshupp.kevmusicplayer.ui.screens.dialogs.SoulseekDialog

/** Funciones opcionales que se pueden apagar: Telegram y bajo demanda, Soulseek, control desde el PC y transmitir a la TV. */
@Composable
fun AddonsSettingsSection(
    viewModel: MediaBrowserViewModel,
    context: android.content.Context,
    getLocalized: (String, String) -> String
) {
    OnDemandSettingsCard(context, getLocalized)
    Spacer(modifier = Modifier.height(4.dp))

    var showSoulseek by remember { mutableStateOf(false) }
    SoulseekSettingsCard(context, getLocalized) { showSoulseek = true }
    if (showSoulseek) {
        SoulseekDialog(librarySongs = viewModel.localAudioFiles, onDismiss = { showSoulseek = false })
    }
    Spacer(modifier = Modifier.height(4.dp))

    RemoteControlSettingsCard(context, getLocalized)
    Spacer(modifier = Modifier.height(4.dp))

    val castEnabled by remember { CastController.enabled(context) }.collectAsState()
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = settingsCardContainerColor()),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Cast, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(getLocalized("Transmitir a la TV", "Cast to TV"), fontWeight = FontWeight.Bold, color = settingsTextColor())
                Text(
                    getLocalized("Chromecast y DLNA. Apagado, se quita el botón del reproductor", "Chromecast and DLNA. When off, the player button is removed"),
                    fontSize = 12.sp,
                    color = settingsTextMutedColor()
                )
            }
            Switch(checked = castEnabled, onCheckedChange = { CastController.setEnabled(context, it) })
        }
    }
}
