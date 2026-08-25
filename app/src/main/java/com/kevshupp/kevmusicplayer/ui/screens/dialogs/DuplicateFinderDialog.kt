package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kevshupp.kevmusicplayer.playback.DuplicateGroup
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel

sealed class DuplicateScanState {
    object Scanning : DuplicateScanState()
    data class Success(val groups: List<DuplicateGroup>) : DuplicateScanState()
    data class Deleting(val count: Int) : DuplicateScanState()
    data class Complete(val deletedCount: Int, val sizeSavedBytes: Long) : DuplicateScanState()
}

@Composable
fun DuplicateFinderDialog(
    viewModel: MediaBrowserViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val systemLang = remember { context.resources.configuration.locales[0].language }
    val getLocalized = { es: String, en: String ->
        if (systemLang == "es") es else en
    }

    var scanState by remember { mutableStateOf<DuplicateScanState>(DuplicateScanState.Scanning) }
    val selectedIds = remember { mutableStateMapOf<Long, Boolean>() }

    LaunchedEffect(Unit) {
        val dupGroups = viewModel.findDuplicates(context)
        scanState = DuplicateScanState.Success(dupGroups)
    }

    LaunchedEffect(scanState) {
        if (scanState is DuplicateScanState.Success) {
            val groups = (scanState as DuplicateScanState.Success).groups
            groups.forEach { group ->
                group.duplicates.forEach { dup ->
                    selectedIds[dup.file.id] = true
                }
            }
        }
    }

    val selectedCount = selectedIds.values.count { it }
    val sizeSavedBytes = remember(selectedIds.toMap(), scanState) {
        if (scanState is DuplicateScanState.Success) {
            val groups = (scanState as DuplicateScanState.Success).groups
            groups.flatMap { it.duplicates }
                .filter { selectedIds[it.file.id] == true }
                .sumOf { it.sizeBytes }
        } else {
            0L
        }
    }
    val sizeSavedMB = sizeSavedBytes / (1024f * 1024f)
    val sizeSavedText = String.format("%.2f MB", sizeSavedMB)

    var showConfirmDelete by remember { mutableStateOf(false) }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = {
                Text(
                    text = getLocalized("Confirmar Eliminación", "Confirm Deletion"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = getLocalized(
                        "Se eliminarán físicamente $selectedCount archivos seleccionados de tu almacenamiento ($sizeSavedText).\n\nEsta acción no se puede deshacer. ¿Estás seguro?",
                        "This will physically delete $selectedCount selected files from your storage ($sizeSavedText).\n\nThis action cannot be undone. Are you sure?"
                    ),
                    color = Color.White.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDelete = false
                        scanState = DuplicateScanState.Deleting(selectedCount)
                        val toDeleteList = selectedIds.filter { it.value }.keys.toList()
                        viewModel.deleteSongs(context, toDeleteList) {
                            scanState = DuplicateScanState.Complete(selectedCount, sizeSavedBytes)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(getLocalized("Eliminar", "Delete"), color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) {
                    Text(getLocalized("Cancelar", "Cancel"), color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF1E213A),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    if (scanState is DuplicateScanState.Deleting) {
        Dialog(onDismissRequest = {}) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161829)),
                modifier = Modifier.padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = getLocalized("Eliminando archivos duplicados...", "Deleting duplicate files..."),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (scanState is DuplicateScanState.Complete) {
        val completeState = scanState as DuplicateScanState.Complete
        AlertDialog(
            onDismissRequest = {
                scanState = DuplicateScanState.Scanning
                onDismiss()
            },
            title = {
                Text(
                    text = getLocalized("¡Eliminación Completada!", "Deletion Complete!"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = getLocalized(
                        "Se han eliminado correctamente ${completeState.deletedCount} archivos duplicados.\n\nEspacio liberado: ${String.format("%.2f", completeState.sizeSavedBytes / (1024.0 * 1024.0))} MB.",
                        "Successfully deleted ${completeState.deletedCount} duplicate files.\n\nSpace freed: ${String.format("%.2f", completeState.sizeSavedBytes / (1024.0 * 1024.0))} MB."
                    ),
                    color = Color.White.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scanState = DuplicateScanState.Scanning
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(getLocalized("Aceptar", "OK"), color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF161829),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    AlertDialog(
        onDismissRequest = {
            if (scanState !is DuplicateScanState.Deleting) {
                onDismiss()
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = getLocalized("Buscador de Duplicados", "Duplicate Finder"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
            ) {
                when (val state = scanState) {
                    is DuplicateScanState.Scanning -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = getLocalized("Analizando biblioteca...", "Analyzing library..."),
                                color = Color.White
                            )
                        }
                    }
                    is DuplicateScanState.Success -> {
                        val groups = state.groups
                        if (groups.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LibraryMusic,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.4f),
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = getLocalized("No se encontraron canciones duplicadas.", "No duplicate songs found."),
                                    color = Color.White.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = getLocalized(
                                        "Se conservará el archivo original y se eliminarán las copias seleccionadas.",
                                        "The original file will be kept and selected copies will be deleted."
                                    ),
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )

                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(groups) { group ->
                                        Card(
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Text(
                                                    text = group.original.file.title,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = group.original.file.artist,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))

                                                // Original to keep
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.CheckCircle,
                                                        contentDescription = null,
                                                        tint = Color(0xFF00E676),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = getLocalized("Conservar original:", "Keep original:"),
                                                            fontSize = 10.sp,
                                                            color = Color(0xFF00E676),
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        Text(
                                                            text = group.original.path,
                                                            fontSize = 9.sp,
                                                            color = Color.White.copy(alpha = 0.5f),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }

                                                HorizontalDivider(
                                                    color = Color.White.copy(alpha = 0.08f),
                                                    modifier = Modifier.padding(vertical = 6.dp)
                                                )

                                                // Duplicates to delete
                                                group.duplicates.forEach { duplicate ->
                                                    val isChecked = selectedIds[duplicate.file.id] ?: false
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { selectedIds[duplicate.file.id] = !isChecked }
                                                            .padding(vertical = 2.dp)
                                                    ) {
                                                        Checkbox(
                                                            checked = isChecked,
                                                            onCheckedChange = { selectedIds[duplicate.file.id] = it },
                                                            colors = CheckboxDefaults.colors(
                                                                checkedColor = MaterialTheme.colorScheme.error,
                                                                uncheckedColor = Color.White.copy(alpha = 0.4f)
                                                            ),
                                                            modifier = Modifier.scale(0.85f)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                modifier = Modifier.fillMaxWidth()
                                                            ) {
                                                                Text(
                                                                    text = getLocalized("Eliminar copia:", "Delete copy:"),
                                                                    fontSize = 10.sp,
                                                                    color = MaterialTheme.colorScheme.error,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                                val sizeMB = duplicate.sizeBytes / (1024.0 * 1024.0)
                                                                Text(
                                                                    text = String.format("%.2f MB", sizeMB),
                                                                    fontSize = 10.sp,
                                                                    color = Color.White.copy(alpha = 0.7f),
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                            Text(
                                                                text = duplicate.path,
                                                                fontSize = 9.sp,
                                                                color = Color.White.copy(alpha = 0.5f),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = getLocalized("Seleccionados: $selectedCount", "Selected: $selectedCount"),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = getLocalized("Liberar: $sizeSavedText", "Free: $sizeSavedText"),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            val state = scanState
            if (state is DuplicateScanState.Success && state.groups.isNotEmpty()) {
                Button(
                    onClick = { showConfirmDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = selectedCount > 0
                ) {
                    Text(getLocalized("Eliminar", "Delete"), color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else if (state is DuplicateScanState.Success && state.groups.isEmpty()) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(getLocalized("Aceptar", "OK"), color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            val state = scanState
            if (state is DuplicateScanState.Success && state.groups.isNotEmpty()) {
                TextButton(onClick = onDismiss) {
                    Text(getLocalized("Cancelar", "Cancel"), color = Color.White.copy(alpha = 0.6f))
                }
            }
        },
        containerColor = Color(0xFF161829),
        titleContentColor = Color.White,
        textContentColor = Color.White
    )
}
