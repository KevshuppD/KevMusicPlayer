package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel

@Composable
fun DeleteSongDialog(
    song: AudioFile,
    viewModel: MediaBrowserViewModel?,
    context: android.content.Context,
    onDeleted: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete Track?", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Text("Are you sure you want to permanently delete this track from your device?", color = Color.White.copy(alpha = 0.8f))
                Spacer(modifier = Modifier.height(8.dp))
                Text(song.title, fontWeight = FontWeight.Bold, color = Color.White)
                Text(song.artist, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel?.deleteSong(context, song.id)
                    onDeleted()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White.copy(alpha = 0.6f))
            }
        },
        containerColor = Color(0xFF161829),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}
