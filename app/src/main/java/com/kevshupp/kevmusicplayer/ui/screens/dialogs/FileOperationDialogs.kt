package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.playback.managers.FileOpFailure

/** Resultado de organizar o migrar: cuántas se movieron y, una por una, las que fallaron con su motivo. */
@Composable
fun FileOperationResultDialog(title: String, successCount: Int, failures: List<FileOpFailure>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "$successCount ${if (successCount == 1) "canción movida" else "canciones movidas"}" +
                        if (failures.isNotEmpty()) " · ${failures.size} con error" else "",
                    fontWeight = FontWeight.Bold
                )
                if (failures.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Estos archivos se quedaron donde estaban, sin cambios:",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(failures) { f ->
                            Row {
                                Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(f.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(f.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Aceptar") } }
    )
}

/**
 * Migrar música de una carpeta a otra (p. ej. de la SD a la memoria interna) sin renombrar ni reordenar:
 * mismas subcarpetas y nombres. Elige origen y destino entre las carpetas de música configuradas.
 */
@Composable
fun MigrateFolderDialog(
    folders: List<String>,
    running: Boolean,
    progress: String,
    onDismiss: () -> Unit,
    onMigrate: (from: String, to: String, removeOrigin: Boolean) -> Unit
) {
    var from by remember { mutableStateOf(folders.getOrNull(1) ?: folders.firstOrNull()) }
    var to by remember { mutableStateOf(folders.firstOrNull { it != from }) }
    var removeOrigin by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Migrar música") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Mueve todas las canciones de una carpeta a otra (por ejemplo, de la SD a la memoria interna) con las " +
                        "mismas subcarpetas y nombres, junto a sus letras .lrc y carátulas. No cambia nada más y nunca " +
                        "sobrescribe. Estadísticas, letras y listas se conservan.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (folders.size < 2) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Añade primero la carpeta de destino con \"Añadir otra carpeta\": hacen falta al menos dos.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    Text("Desde", fontWeight = FontWeight.Bold)
                    folders.forEach { f -> FolderOption(f, from == f, enabled = !running) { from = f; if (to == f) to = folders.firstOrNull { it != f } } }
                    Spacer(Modifier.height(10.dp))
                    Text("Hacia", fontWeight = FontWeight.Bold)
                    folders.filter { it != from }.forEach { f -> FolderOption(f, to == f, enabled = !running) { to = f } }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !running) { removeOrigin = !removeOrigin }) {
                        Checkbox(removeOrigin, { removeOrigin = it }, enabled = !running)
                        Text("Quitar la carpeta de origen de la lista al terminar", fontSize = 13.sp)
                    }
                }
                if (running) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(progress, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { val f = from; val t = to; if (f != null && t != null) onMigrate(f, t, removeOrigin) },
                enabled = !running && from != null && to != null && from != to
            ) { Text("Migrar") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("Cancelar") } }
    )
}

@Composable
private fun FolderOption(path: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected, onClick, enabled = enabled)
        Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(path.substringAfterLast('/'), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(path, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
