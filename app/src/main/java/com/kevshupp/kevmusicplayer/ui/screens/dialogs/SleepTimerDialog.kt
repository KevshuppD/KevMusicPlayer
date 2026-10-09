package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SleepTimerDialog(
    sleepTimerMinutes: Int,
    onSelectMinutes: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Temporizador de apagado", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    0 to "Desactivado",
                    15 to "15 minutos",
                    30 to "30 minutos",
                    45 to "45 minutos",
                    60 to "60 minutos",
                    -1 to "Al finalizar esta canción"
                )
                options.forEach { (mins, label) ->
                    val isSelected = sleepTimerMinutes == mins
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectMinutes(mins)
                                onDismiss()
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                onSelectMinutes(mins)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(label, color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = Color.White.copy(alpha = 0.7f))
            }
        },
        containerColor = Color(0xFF161829),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}
