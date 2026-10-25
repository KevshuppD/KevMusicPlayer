package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kevshupp.kevmusicplayer.playback.managers.ClipDraft
import com.kevshupp.kevmusicplayer.playback.managers.ClipEntry
import com.kevshupp.kevmusicplayer.playback.managers.ClipHistory
import com.kevshupp.kevmusicplayer.playback.managers.ClipTemplateStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Mis videos": los videos creados, con reproducir, compartir, guardar, editar y eliminar. */
@Composable
fun ClipHistoryDialog(
    onEdit: (ClipEntry) -> Unit,
    onResumeDraft: (ClipDraft) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by ClipHistory.entries.collectAsState()
    val drafts by ClipHistory.drafts.collectAsState()
    val job by ClipHistory.job.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<ClipEntry?>(null) }
    var toDelete by remember { mutableStateOf<List<ClipEntry>?>(null) }
    // Selección múltiple: se entra manteniendo pulsado un video
    val picked = remember { mutableStateListOf<String>() }
    val selecting = picked.isNotEmpty()
    val pickedEntries = entries.filter { it.id in picked }
    val primary = MaterialTheme.colorScheme.primary

    LaunchedEffect(Unit) { ClipHistory.load(context); loading = false }

    Dialog(
        onDismissRequest = { if (selecting) picked.clear() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0B10))
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            if (selecting) Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { picked.clear() }) { Icon(Icons.Rounded.Close, "Cancelar selección", tint = Color.White) }
                Text("${picked.size} seleccionado${if (picked.size == 1) "" else "s"}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    if (picked.size == entries.size) picked.clear() else { picked.clear(); picked.addAll(entries.map { it.id }) }
                }) { Icon(Icons.Rounded.SelectAll, "Seleccionar todo", tint = Color.White) }
                IconButton(onClick = { shareVideoFiles(context, pickedEntries) }) {
                    Icon(Icons.Rounded.Share, "Compartir", tint = Color.White)
                }
                IconButton(onClick = {
                    val ok = pickedEntries.count { saveVideoToGallery(context, ClipHistory.videoFile(context, it), it.title) }
                    Toast.makeText(context, "$ok de ${pickedEntries.size} guardados en Películas/KevMusic", Toast.LENGTH_SHORT).show()
                    picked.clear()
                }) { Icon(Icons.Rounded.Download, "Guardar en galería", tint = Color.White) }
                IconButton(onClick = { toDelete = pickedEntries }) { Icon(Icons.Rounded.Delete, "Eliminar", tint = Color(0xFFFF6B6B)) }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cerrar", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text("Mis videos", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    val total = entries.sumOf { it.sizeBytes }
                    Text(
                        if (entries.isEmpty()) "Aún no has creado videos" else "${entries.size} video${if (entries.size == 1) "" else "s"} · ${formatSize(total)}",
                        color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = primary) }
                entries.isEmpty() && drafts.isEmpty() -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Rounded.VideoLibrary, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Los videos que crees aparecerán aquí", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                    Text("Reproductor › Más › Crear fragmento en video", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (drafts.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("Borradores", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        items(drafts, key = { "draft_" + it.id }, span = { GridItemSpan(maxLineSpan) }) { draft ->
                            DraftRow(
                                draft, primary,
                                progress = job?.takeIf { it.draftId == draft.id && it.running }?.progress,
                                onClick = { onResumeDraft(draft) },
                                onDelete = { ClipHistory.deleteDraft(context, draft.id) }
                            )
                        }
                        if (entries.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("Videos", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    items(entries, key = { it.id }) { entry ->
                        ClipCard(
                            entry, primary,
                            picked = entry.id in picked,
                            selecting = selecting,
                            onClick = {
                                if (selecting) { if (entry.id in picked) picked.remove(entry.id) else picked.add(entry.id) }
                                else selected = entry
                            },
                            onLongClick = { if (entry.id !in picked) picked.add(entry.id) }
                        )
                    }
                }
            }
        }

        // Acciones del video elegido
        selected?.let { entry ->
            val file = ClipHistory.videoFile(context, entry)
            AlertDialog(
                onDismissRequest = { selected = null },
                containerColor = Color(0xFF181A28),
                title = {
                    Column {
                        Text(entry.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ActionRow(Icons.Rounded.PlayArrow, "Reproducir") {
                            playVideo(context, file)
                        }
                        ActionRow(Icons.Rounded.Share, "Compartir") {
                            shareVideoFile(context, file, entry.title, entry.artist, entry.settings.dedication)
                        }
                        ActionRow(Icons.Rounded.Download, "Guardar en galería") {
                            val ok = saveVideoToGallery(context, file, entry.title)
                            Toast.makeText(context, if (ok) "Guardado en Películas/KevMusic" else "No se pudo guardar", Toast.LENGTH_SHORT).show()
                        }
                        ActionRow(Icons.Rounded.Edit, "Editar (crear uno nuevo con estos ajustes)") {
                            selected = null
                            onEdit(entry)
                        }
                        ActionRow(Icons.Rounded.Delete, "Eliminar", Color(0xFFFF6B6B)) {
                            selected = null
                            toDelete = listOf(entry)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { selected = null }) { Text("Cerrar", color = Color.White.copy(alpha = 0.7f)) } }
            )
        }

        toDelete?.let { list ->
            AlertDialog(
                onDismissRequest = { toDelete = null },
                containerColor = Color(0xFF181A28),
                title = { Text(if (list.size == 1) "Eliminar video" else "Eliminar ${list.size} videos", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        (if (list.size == 1) "Se borra \"${list[0].title}\" de Mis videos." else "Se borran ${list.size} videos de Mis videos.") +
                            " Las copias que ya compartiste o guardaste en la galería no se tocan.",
                        color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch { list.forEach { ClipHistory.delete(context, it) } }
                            toDelete = null
                            picked.clear()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                    ) { Text("Eliminar", fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar", color = Color.White.copy(alpha = 0.7f)) } }
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ClipCard(
    entry: ClipEntry,
    accent: Color,
    picked: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val thumb by produceState<android.graphics.Bitmap?>(null, entry.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(ClipHistory.thumbFile(context, entry.id).absolutePath) }.getOrNull()
        }
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (picked) accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
            .then(if (picked) Modifier.border(2.dp, accent, RoundedCornerShape(14.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            thumb?.let {
                Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } ?: Icon(Icons.Rounded.Movie, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(36.dp))
            if (selecting) {
                Icon(
                    if (picked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                    tint = if (picked) accent else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(24.dp)
                )
            } else Box(
                Modifier.size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White) }
            Text(
                "${(entry.endMs - entry.startMs) / 1000} s",
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Column(Modifier.padding(10.dp)) {
            Text(entry.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                "${templateName(entry.settings.template)} · ${minOf(entry.width, entry.height)}p · ${formatDate(entry.createdAt)}",
                color = accent.copy(alpha = 0.85f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Borrador: tocarlo abre el creador con lo elegido; mientras se crea muestra el progreso. */
@Composable
private fun DraftRow(draft: ClipDraft, accent: Color, progress: Float?, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            if (progress != null) CircularProgressIndicator(progress = { progress }, color = accent, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
            else Icon(Icons.Rounded.EditNote, null, tint = accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(draft.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${draft.artist} · ${formatTime(draft.startMs)}–${formatTime(draft.endMs)} · ${templateName(draft.settings.template)}",
                color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    progress != null -> "Creándose… ${(progress * 100).toInt()}%"
                    draft.interrupted -> "Se interrumpió al crearlo · toca para continuar"
                    else -> "Sin terminar · ${formatDate(draft.updatedAt)}"
                },
                color = if (draft.interrupted && progress == null) Color(0xFFFFB74D) else accent.copy(alpha = 0.85f),
                fontSize = 10.sp, maxLines = 1
            )
        }
        if (progress == null) IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, "Eliminar borrador", tint = Color.White.copy(alpha = 0.5f))
        }
    }
}

private fun formatTime(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = tint, fontSize = 14.sp)
    }
}

private fun templateName(t: ClipTemplateStyle) = when (t) {
    ClipTemplateStyle.LYRICS_STORY -> "Letras"
    ClipTemplateStyle.PLAYER_CANVAS -> "Portada"
    ClipTemplateStyle.VINYL_VIBES -> "Vinilo"
    ClipTemplateStyle.PIXEL_ART -> "Pixel art"
}

private fun formatDate(ms: Long) = SimpleDateFormat("d MMM, HH:mm", Locale("es")).format(Date(ms))

private fun formatSize(bytes: Long) = when {
    bytes >= 1_000_000 -> String.format(Locale("es"), "%.1f MB", bytes / 1_000_000.0)
    else -> "${bytes / 1000} KB"
}

private fun playVideo(context: android.content.Context, file: java.io.File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }.onFailure { Toast.makeText(context, "No hay una app para reproducir videos", Toast.LENGTH_SHORT).show() }
}

/** Comparte varios videos a la vez (ACTION_SEND_MULTIPLE). */
private fun shareVideoFiles(context: android.content.Context, entries: List<ClipEntry>) {
    if (entries.isEmpty()) return
    if (entries.size == 1) {
        val e = entries[0]
        shareVideoFile(context, ClipHistory.videoFile(context, e), e.title, e.artist, e.settings.dedication)
        return
    }
    val files = entries.map { ClipHistory.videoFile(context, it) }
    runCatching {
        val uris = ArrayList(files.map { androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/mp4"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir ${files.size} videos"))
    }.onFailure { Toast.makeText(context, "No se pudieron compartir: ${it.message}", Toast.LENGTH_SHORT).show() }
}
