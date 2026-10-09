package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.ui.screens.DetailedAudioFileInfo
import com.kevshupp.kevmusicplayer.ui.screens.getDetailedAudioFileInfo

@Composable
fun AudioSpecsDialog(
    mediaId: String?,
    context: android.content.Context,
    onDismiss: () -> Unit
) {
    val currentSongUriString = remember(mediaId) {
        if (mediaId != null) "content://media/external/audio/media/$mediaId" else null
    }
    val detailedInfo by produceState(
        initialValue = DetailedAudioFileInfo("Loading...", "Loading...", "Loading...", "Loading...", "Loading...", "Loading...", "Loading..."),
        key1 = mediaId
    ) {
        value = getDetailedAudioFileInfo(context, currentSongUriString)
    }
    val isEs = java.util.Locale.getDefault().language == "es"
    val getLocalized = { es: String, en: String -> if (isEs) es else en }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(getLocalized("Especificaciones de Audio", "Audio Specifications"), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(getLocalized("Título: ${detailedInfo.title}", "Title: ${detailedInfo.title}"), fontWeight = FontWeight.Bold, color = Color.White)
                Text(getLocalized("Artista: ${detailedInfo.artist}", "Artist: ${detailedInfo.artist}"), color = Color.White.copy(alpha = 0.8f))
                Text(getLocalized("Álbum: ${detailedInfo.album}", "Album: ${detailedInfo.album}"), color = Color.White.copy(alpha = 0.8f))
                Spacer(modifier = Modifier.height(4.dp))
                Text(getLocalized("Ubicación: ${detailedInfo.location}", "Location: ${detailedInfo.location}"), fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                Text(getLocalized("Tipo: ${detailedInfo.type}", "Type: ${detailedInfo.type}"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(getLocalized("Tasa de bits: ${detailedInfo.bitrate}", "Bitrate: ${detailedInfo.bitrate}"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(getLocalized("Tamaño: ${detailedInfo.size}", "Size: ${detailedInfo.size}"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(getLocalized("Cerrar", "Close"), fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF161829),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}
