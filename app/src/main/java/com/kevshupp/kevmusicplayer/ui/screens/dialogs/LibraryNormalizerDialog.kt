package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kevshupp.kevmusicplayer.data.LibraryNormalizer
import com.kevshupp.kevmusicplayer.data.LibraryNormalizer.Field
import com.kevshupp.kevmusicplayer.data.LibraryNormalizer.State
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import com.kevshupp.kevmusicplayer.ui.screens.rememberAlbumArt

/**
 * "Normalizar biblioteca": analiza con Deezer, muestra los cambios por canción para revisarlos y aplica los
 * elegidos. El análisis sigue aunque se cierre el diálogo (vive en [LibraryNormalizer]).
 */
@Composable
fun LibraryNormalizerDialog(viewModel: MediaBrowserViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by LibraryNormalizer.state.collectAsState()
    val fields = remember { mutableStateMapOf<Field, Boolean>().apply { Field.entries.forEach { put(it, true) } } }
    val unselected = remember { mutableStateMapOf<Long, Boolean>() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f)
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Normalizar biblioteca", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cerrar") }
                }
                Spacer(Modifier.height(8.dp))

                when (val s = state) {
                    State.Idle -> Column(Modifier.weight(1f)) {
                        Text(
                            "Busca cada canción en Deezer y propone su título, artista, álbum, género, año y número de pista oficiales. " +
                                "Solo se usa una coincidencia segura (mismo título y artista, duración parecida); lo demás no se toca.\n\n" +
                                "Antes de cambiar nada podrás revisar cada canción. Se reescriben solo las etiquetas, nunca el audio, " +
                                "y se conservan reproducciones, letras y listas.",
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "El género solo se completa si falta: Deezer usa géneros amplios (Pop, Rock…) y uno puesto a mano suele ser más preciso. " +
                                "Álbum, año y pista solo se corrigen si es el mismo disco.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        val total = viewModel.localAudioFiles.size
                        val pending by produceState<Int?>(null, total) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                LibraryNormalizer.pendingForQuick(context, viewModel.localAudioFiles.toList().filter { it.id > 0 && it.title.isNotBlank() }).size
                            }
                        }
                        Button(
                            onClick = { unselected.clear(); viewModel.startNormalizeScan(full = false) },
                            enabled = pending != null && pending!! > 0,
                            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(
                                when (pending) {
                                    null -> "Calculando…"
                                    0 -> "Todo revisado"
                                    else -> "Rápida: $pending sin revisar"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            "La rápida se salta las canciones ya revisadas que no han cambiado desde entonces.",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)
                        )
                        OutlinedButton(
                            onClick = { unselected.clear(); viewModel.startNormalizeScan(full = true) },
                            enabled = total > 0,
                            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("Completa: revisar las $total canciones") }
                    }

                    is State.Scanning -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.weight(1f))
                        LinearProgressIndicator(
                            progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Buscando en Deezer… ${s.done} de ${s.total}", fontWeight = FontWeight.SemiBold)
                        Text("${s.found} encontradas · puedes cerrar, el análisis sigue", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { LibraryNormalizer.reset() }, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
                    }

                    is State.Review -> {
                        val activeFields = fields.filterValues { it }.keys
                        val visible = remember(s, activeFields.toSet()) { s.proposals.filter { p -> p.changes.keys.any { it in activeFields } } }
                        val selected = visible.filter { unselected[it.song.id] != true }
                        Text(
                            "${s.proposals.size} con cambios · ${s.unchanged} ya correctas · ${s.notFound} no encontradas en Deezer" +
                                if (s.skipped > 0) " · ${s.skipped} ya revisadas antes" else "",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(Field.entries) { f ->
                                val count = s.proposals.count { f in it.changes }
                                FilterChip(
                                    selected = fields[f] == true, onClick = { fields[f] = fields[f] != true },
                                    label = { Text("${f.label} ($count)") }, enabled = count > 0
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${selected.size} de ${visible.size} seleccionadas", fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                if (selected.size == visible.size) visible.forEach { unselected[it.song.id] = true } else unselected.clear()
                            }) { Text(if (selected.size == visible.size) "Ninguna" else "Todas") }
                        }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(visible, key = { it.song.id }) { p ->
                                val checked = unselected[p.song.id] != true
                                ProposalRow(p, activeFields, checked) { unselected[p.song.id] = checked }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { LibraryNormalizer.reset() }, modifier = Modifier.weight(1f)) { Text("Descartar") }
                            Button(
                                onClick = { viewModel.applyNormalization(context, selected, activeFields) },
                                enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f)
                            ) { Text("Aplicar (${selected.size})", fontWeight = FontWeight.Bold) }
                        }
                    }

                    is State.Applying -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.weight(1f))
                        LinearProgressIndicator(
                            progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Escribiendo etiquetas… ${s.done} de ${s.total}", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                    }

                    is State.Done -> Column(Modifier.weight(1f)) {
                        Text("${s.updated} canciones actualizadas", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (s.failed.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("${s.failed.size} no se pudieron cambiar:", color = MaterialTheme.colorScheme.error)
                            LazyColumn(Modifier.weight(1f)) {
                                items(s.failed, key = { it.first.id }) { (song, reason) ->
                                    Column(Modifier.padding(vertical = 4.dp)) {
                                        Text("${song.artist} - ${song.title}", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(reason, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        } else Spacer(Modifier.weight(1f))
                        Button(onClick = { LibraryNormalizer.reset(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Aceptar") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProposalRow(p: LibraryNormalizer.Proposal, fields: Set<Field>, checked: Boolean, onToggle: () -> Unit) {
    val art = rememberAlbumArt(p.song.uriString, thumbnail = true)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onToggle).padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.Gray.copy(alpha = 0.2f))) {
            art?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.song.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.song.artist, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            p.changes.filterKeys { it in fields }.forEach { (field, value) ->
                val old = p.oldValue(field).takeUnless { LibraryNormalizer.isUnknown(it) } ?: "—"
                Text(
                    "${field.label}: $old → $value", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
