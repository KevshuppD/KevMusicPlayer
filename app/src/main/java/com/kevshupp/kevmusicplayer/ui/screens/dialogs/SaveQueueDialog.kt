package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel

@Composable
fun SaveQueueDialog(
    viewModel: MediaBrowserViewModel?,
    onDismiss: () -> Unit
) {
    var playlistNameInput by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar cola de reproducción", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Introduce un nombre para la nueva lista:", color = Color.White.copy(alpha = 0.8f))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = playlistNameInput,
                    onValueChange = { playlistNameInput = it },
                    label = { Text("Nombre de lista") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (playlistNameInput.isNotBlank() && viewModel != null) {
                        val queue = viewModel.getPlayerQueue()
                        viewModel.createPlaylist(playlistNameInput)
                        queue.forEach { song ->
                            viewModel.addSongToPlaylist(playlistNameInput, song.id)
                        }
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Guardar", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = Color.White.copy(alpha = 0.7f))
            }
        },
        containerColor = Color(0xFF161829),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}
