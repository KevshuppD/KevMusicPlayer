package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.Library
import com.kevshupp.kevmusic.desktop.Song
import kotlinx.coroutines.delay

/**
 * Ediciones de la biblioteca del teléfono desde el PC: listas (crear, añadir, quitar, eliminar) y borrar canciones.
 * Las hace el teléfono (MediaBrowserViewModel); aquí solo se piden y se confirman con un diálogo.
 */
class LibraryEdits {
    sealed interface Dialog {
        data class AddToPlaylist(val ids: List<Long>) : Dialog
        data class NewPlaylist(val ids: List<Long>) : Dialog
        data class DeleteSongs(val songs: List<Song>, val what: String) : Dialog
        data class DeletePlaylist(val name: String) : Dialog
    }

    var dialog by mutableStateOf<Dialog?>(null)

    fun addToPlaylist(ids: List<Long>) { if (ids.isNotEmpty()) dialog = Dialog.AddToPlaylist(ids) }
    fun newPlaylist(ids: List<Long> = emptyList()) { dialog = Dialog.NewPlaylist(ids) }
    fun deleteSongs(songs: List<Song>, what: String) { if (songs.isNotEmpty()) dialog = Dialog.DeleteSongs(songs, what) }
    fun deletePlaylist(name: String) { dialog = Dialog.DeletePlaylist(name) }

    /** Entradas de edición para el menú contextual de una o varias canciones. */
    fun songMenuItems(client: com.kevshupp.kevmusic.desktop.RemoteClient, songs: List<Song>, what: String, playlist: String? = null) = buildList {
        add(ContextMenuItem("Añadir a una lista…") { addToPlaylist(songs.map { it.id }) })
        if (playlist != null) add(ContextMenuItem("Quitar de esta lista") { client.removeFromPlaylist(playlist, songs.map { it.id }) })
        add(ContextMenuItem("Eliminar del teléfono…") { deleteSongs(songs, what) })
    }
}

val LocalEdits = staticCompositionLocalOf { LibraryEdits() }

/** Listas en las que se pueden poner canciones a mano (las inteligentes se llenan con sus reglas). */
private fun editablePlaylists(lib: Library) =
    lib.playlists.filterNot { it.smart }.sortedWith(compareBy({ it.name != "Favoritos" }, { normalize(it.name) }))

@Composable
fun LibraryEditDialogs(lib: Library?) {
    val edits = LocalEdits.current
    val client = LocalClient.current
    val close = { edits.dialog = null }
    when (val d = edits.dialog) {
        is LibraryEdits.Dialog.AddToPlaylist -> AlertDialog(
            onDismissRequest = close,
            title = { Text(if (d.ids.size == 1) "Añadir a una lista" else "Añadir ${d.ids.size} canciones a una lista") },
            text = {
                val lists = lib?.let(::editablePlaylists) ?: emptyList()
                LazyColumn(Modifier.heightIn(max = 380.dp).width(380.dp)) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { edits.newPlaylist(d.ids) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text("Nueva lista…", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        }
                    }
                    items(lists, key = { it.name }) { pl ->
                        val already = d.ids.all { it in pl.songIds }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !already) { client.addToPlaylist(pl.name, d.ids); close() }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (pl.name == "Favoritos") Icons.Rounded.Favorite else Icons.Rounded.QueueMusic, null,
                                tint = if (pl.name == "Favoritos") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pl.name)
                                Text(
                                    if (already) "Ya está en la lista" else "${pl.songIds.size} canciones",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = close) { Text("Cancelar") } }
        )
        is LibraryEdits.Dialog.NewPlaylist -> {
            var name by remember { mutableStateOf("") }
            val taken = lib?.playlists?.any { normalize(it.name) == normalize(name.trim()) } == true
            val valid = name.isNotBlank() && !taken
            val create = { if (valid) { client.createPlaylist(name, d.ids); close() } }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            AlertDialog(
                onDismissRequest = close,
                title = { Text("Nueva lista") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it }, singleLine = true,
                            label = { Text("Nombre") }, isError = taken,
                            supportingText = {
                                Text(
                                    when {
                                        taken -> "Ya existe una lista con ese nombre"
                                        d.ids.isNotEmpty() -> if (d.ids.size == 1) "Se creará con esta canción" else "Se creará con ${d.ids.size} canciones"
                                        else -> "Se creará vacía"
                                    }
                                )
                            },
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { create() }),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            modifier = Modifier.width(360.dp).focusRequester(focus)
                        )
                    }
                },
                confirmButton = { Button(onClick = create, enabled = valid) { Text("Crear") } },
                dismissButton = { TextButton(onClick = close) { Text("Cancelar") } }
            )
        }
        is LibraryEdits.Dialog.DeleteSongs -> AlertDialog(
            onDismissRequest = close,
            icon = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("¿Eliminar ${d.what}?") },
            text = {
                Text(
                    (if (d.songs.size == 1) "Se borrará el archivo de \"${d.songs.first().title}\"" else "Se borrarán ${d.songs.size} archivos") +
                        " del teléfono. No se puede deshacer."
                )
            },
            confirmButton = {
                Button(
                    onClick = { client.deleteSongs(d.songs.map { it.id }); close() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancelar") } }
        )
        is LibraryEdits.Dialog.DeletePlaylist -> AlertDialog(
            onDismissRequest = close,
            title = { Text("¿Eliminar la lista \"${d.name}\"?") },
            text = { Text("Las canciones siguen en el teléfono; solo se borra la lista.") },
            confirmButton = {
                Button(
                    onClick = { client.deletePlaylist(d.name); close() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancelar") } }
        )
        null -> {}
    }
}

/** Aviso flotante con el resultado de la última edición; se va solo a los 4 s. */
@Composable
fun NoticeBanner(modifier: Modifier = Modifier) {
    val client = LocalClient.current
    val notice by client.notice.collectAsState()
    val n = notice ?: return
    LaunchedEffect(n) { delay(4000); client.dismissNotice() }
    Row(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(if (n.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable { client.dismissNotice() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (n.error) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null,
            tint = if (n.error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(n.text, color = if (n.error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
    }
}
