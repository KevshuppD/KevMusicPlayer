package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.data.telegram.DownloadStatus
import com.kevshupp.kevmusicplayer.data.telegram.TelegramAuthState
import com.kevshupp.kevmusicplayer.data.telegram.TelegramDownloadQueue
import com.kevshupp.kevmusicplayer.data.telegram.TelegramMusicClient

/** Arranca la sesión de Telegram y dice si se pueden reparar canciones (sesión iniciada y bot configurado). */
@Composable
fun rememberSongRepairAvailable(): Boolean {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TelegramMusicClient.start(context) }
    val auth by TelegramMusicClient.authState.collectAsState()
    val bot by TelegramMusicClient.bot.collectAsState()
    val enabled by TelegramMusicClient.enabled.collectAsState()
    return enabled && auth == TelegramAuthState.Ready && bot.isNotBlank()
}

/** Estado de la reparación de una canción, o nada si no se ha pedido. */
@Composable
fun SongRepairStatus(songId: Long) {
    val repairs by TelegramDownloadQueue.repairs.collectAsState()
    val (text, color) = when (val s = repairs[songId]) {
        null -> return
        DownloadStatus.Queued -> "En cola para volver a descargar" to MaterialTheme.colorScheme.primary
        DownloadStatus.Requesting -> "Buscando y pidiendo al bot…" to MaterialTheme.colorScheme.primary
        is DownloadStatus.Downloading -> "Descargando ${(s.progress * 100).toInt()} %" to MaterialTheme.colorScheme.primary
        is DownloadStatus.Done -> "Reemplazada por una copia nueva" to Color(0xFF00E676)
        is DownloadStatus.Failed -> "No se pudo reparar: ${s.reason}" to MaterialTheme.colorScheme.error
    }
    Text(text, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold)
}

/** Botón por canción: volver a descargarla y reemplazarla. */
@Composable
fun SongRepairButton(song: com.kevshupp.kevmusicplayer.data.AudioFile, enabled: Boolean) {
    val context = LocalContext.current
    val repairs by TelegramDownloadQueue.repairs.collectAsState()
    val status = repairs[song.id]
    val busy = status != null && status !is DownloadStatus.Failed && status !is DownloadStatus.Done
    IconButton(
        onClick = { com.kevshupp.kevmusicplayer.data.telegram.SongRepair.repair(context, listOf(song)) },
        enabled = enabled && !busy && status !is DownloadStatus.Done,
        modifier = Modifier.size(36.dp)
    ) {
        when {
            busy -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            status is DownloadStatus.Done -> Icon(Icons.Rounded.Check, "Reparada", tint = Color(0xFF00E676), modifier = Modifier.size(18.dp))
            status is DownloadStatus.Failed -> Icon(Icons.Rounded.Refresh, "Reintentar", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            else -> Icon(
                Icons.Rounded.CloudDownload,
                "Volver a descargar",
                tint = if (enabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
