package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.OfflinePin
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
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel

sealed class IntegrityScanState {
    object Scanning : IntegrityScanState()
    data class Success(val songs: List<Pair<AudioFile, String>>) : IntegrityScanState()
    data class Deleting(val count: Int) : IntegrityScanState()
    data class Complete(val deletedCount: Int) : IntegrityScanState()
}

@Composable
fun SongIntegrityDialog(
    viewModel: MediaBrowserViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val systemLang = remember { context.resources.configuration.locales[0].language }
    val getLocalized = { es: String, en: String ->
        if (systemLang == "es") es else en
    }

    var scanState by remember { mutableStateOf<IntegrityScanState>(IntegrityScanState.Scanning) }
    val selectedIds = remember { mutableStateMapOf<Long, Boolean>() }

    LaunchedEffect(Unit) {
        viewModel.verifySongsIntegrity(context) { damagedSongs ->
            scanState = IntegrityScanState.Success(damagedSongs)
        }
    }

    LaunchedEffect(scanState) {
        if (scanState is IntegrityScanState.Success) {
            val songs = (scanState as IntegrityScanState.Success).songs
            songs.forEach { (song, _) ->
                selectedIds[song.id] = true
            }
        }
    }

    val selectedCount = selectedIds.values.count { it }
    var showConfirmDelete by remember { mutableStateOf(false) }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = {
                Text(
                    text = getLocalized("Confirmar Eliminación", "Confirm Removal"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = getLocalized(
                        "Se eliminarán de la biblioteca $selectedCount canciones seleccionadas.\n\nEsta acción no se puede deshacer. ¿Estás seguro?",
                        "This will remove $selectedCount selected songs from the library.\n\nThis action cannot be undone. Are you sure?"
                    ),
                    color = Color.White.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDelete = false
                        scanState = IntegrityScanState.Deleting(selectedCount)
                        val toDeleteList = selectedIds.filter { it.value }.keys.toList()
                        viewModel.deleteSongs(context, toDeleteList) {
                            scanState = IntegrityScanState.Complete(selectedCount)
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

    if (scanState is IntegrityScanState.Deleting) {
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
                        text = getLocalized("Eliminando canciones dañadas...", "Removing damaged songs..."),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (scanState is IntegrityScanState.Complete) {
        val completeState = scanState as IntegrityScanState.Complete
        AlertDialog(
            onDismissRequest = {
                scanState = IntegrityScanState.Scanning
                onDismiss()
            },
            title = {
                Text(
                    text = getLocalized("¡Limpieza Completada!", "Cleanup Complete!"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = getLocalized(
                        "Se han removido con éxito ${completeState.deletedCount} canciones con problemas de tu biblioteca.",
                        "Successfully removed ${completeState.deletedCount} songs with issues from your library."
                    ),
                    color = Color.White.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scanState = IntegrityScanState.Scanning
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
            if (scanState !is IntegrityScanState.Deleting) {
                onDismiss()
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.OfflinePin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = getLocalized("Verificador de Integridad", "Integrity Checker"),
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
                    is IntegrityScanState.Scanning -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = getLocalized(
                                    "Analizando biblioteca (${viewModel.verifyIntegrityCurrent.value}/${viewModel.verifyIntegrityTotal.value})...",
                                    "Analyzing library (${viewModel.verifyIntegrityCurrent.value}/${viewModel.verifyIntegrityTotal.value})..."
                                ),
                                color = Color.White
                            )
                            if (viewModel.verifyIntegrityCurrentName.value.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = viewModel.verifyIntegrityCurrentName.value,
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    is IntegrityScanState.Success -> {
                        val songs = state.songs
                        if (songs.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = getLocalized("No se encontraron canciones dañadas o inaccesibles.", "No damaged or inaccessible songs found."),
                                    color = Color.White.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = getLocalized(
                                        "Se encontraron las siguientes canciones dañadas. Puedes removerlas de tu biblioteca.",
                                        "The following damaged songs were found. You can remove them from your library."
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
                                    items(songs) { (song, reason) ->
                                        val isChecked = selectedIds[song.id] ?: false
                                        Card(
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { selectedIds[song.id] = !isChecked }
                                                    .padding(12.dp)
                                            ) {
                                                Checkbox(
                                                    checked = isChecked,
                                                    onCheckedChange = { selectedIds[song.id] = it },
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = MaterialTheme.colorScheme.error,
                                                        uncheckedColor = Color.White.copy(alpha = 0.4f)
                                                    ),
                                                    modifier = Modifier.scale(0.85f)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = song.title,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        fontSize = 14.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = song.artist,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontSize = 12.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "${getLocalized("Problema:", "Issue:")} $reason",
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.error,
                                                        fontWeight = FontWeight.Bold
                                                    )
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
                                        text = getLocalized("Seleccionadas: $selectedCount", "Selected: $selectedCount"),
                                        color = Color.White,
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
            if (state is IntegrityScanState.Success && state.songs.isNotEmpty()) {
                Button(
                    onClick = { showConfirmDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = selectedCount > 0
                ) {
                    Text(getLocalized("Eliminar", "Delete"), color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else if (state is IntegrityScanState.Success && state.songs.isEmpty()) {
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
            if (state is IntegrityScanState.Success && state.songs.isNotEmpty()) {
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
