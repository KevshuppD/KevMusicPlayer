package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.remote.SoulseekAccount
import com.kevshupp.kevmusicplayer.data.soulseek.SoulseekManager
import com.kevshupp.kevmusicplayer.data.telegram.DeezerAlbum
import com.kevshupp.kevmusicplayer.data.telegram.DeezerTrackSearch
import com.kevshupp.soulseek.*
import kotlinx.coroutines.launch

private enum class SlskTab { Search, Transfers }

/**
 * Buscar y descargar de Soulseek (módulo opcional, Ajustes › Biblioteca). Usa el mismo cliente que KevMusic
 * Desktop; lo descargado entra en la biblioteca como una descarga de Telegram.
 */
@Composable
fun SoulseekDialog(librarySongs: List<AudioFile>, onDismiss: () -> Unit) {
    val client = SoulseekManager.client
    val status by client.status.collectAsState()
    val downloads by client.downloads.collectAsState()
    var tab by remember { mutableStateOf(SlskTab.Search) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Hub, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Soulseek", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(statusLabel(status), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cerrar") }
                }
                Spacer(Modifier.height(8.dp))

                // Sin cuenta, desconectado o con un error de inicio de sesión (no se reintenta solo)
                if (status !is SlskStatus.Online && (!SoulseekManager.hasAccount || status is SlskStatus.Off || (status is SlskStatus.Failed && !client.isRunning))) {
                    LoginForm(status)
                    return@Column
                }

                val active = downloads.count { !it.finished }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(tab == SlskTab.Search, { tab = SlskTab.Search }, label = { Text("Buscar") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) })
                    FilterChip(tab == SlskTab.Transfers, { tab = SlskTab.Transfers },
                        label = { Text(if (active > 0) "Descargas ($active)" else "Descargas") },
                        leadingIcon = { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)) })
                }
                Spacer(Modifier.height(10.dp))
                when (tab) {
                    SlskTab.Search -> SearchTab(librarySongs, downloads)
                    SlskTab.Transfers -> TransfersTab(downloads)
                }
            }
        }
    }
}

private fun statusLabel(status: SlskStatus) = when (status) {
    SlskStatus.Off -> "Desconectado"
    SlskStatus.Connecting -> "Conectando…"
    is SlskStatus.Online -> "Conectado como ${status.username}" + if (SoulseekManager.sharing) " · compartiendo" else ""
    is SlskStatus.Failed -> status.message
}

@Composable
private fun LoginForm(status: SlskStatus) {
    val context = LocalContext.current
    val account = remember { SoulseekAccount.get(context) }
    var user by remember { mutableStateOf(account?.first.orEmpty()) }
    var pass by remember { mutableStateOf(account?.second.orEmpty()) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Inicia sesión con tu cuenta de Soulseek. Si el usuario no existe, el servidor lo crea con esa contraseña. " +
                "Es la misma cuenta que usa KevMusic Desktop.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(user, { user = it }, label = { Text("Usuario") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Contraseña") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        if (status is SlskStatus.Failed) Text(status.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        Button(
            onClick = { SoulseekManager.login(user, pass) },
            enabled = user.isNotBlank() && pass.isNotEmpty() && status !is SlskStatus.Connecting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (status is SlskStatus.Connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Conectar")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchTab(librarySongs: List<AudioFile>, downloads: List<Transfer>) {
    val client = SoulseekManager.client
    val results by client.results.collectAsState()
    val searching by client.searching.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    // Formato: "all", "flac" (sin pérdida) o "mp3_320"; se recuerda entre búsquedas
    val prefs = LocalContext.current.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE)
    var format by remember { mutableStateOf(prefs.getString("soulseek_format", "all") ?: "all") }
    var onlyFree by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var albums by remember { mutableStateOf<List<DeezerAlbum>>(emptyList()) }
    var openAlbum by remember { mutableStateOf<DeezerAlbum?>(null) }
    var expanded by remember(results.isEmpty()) { mutableStateOf(setOf<String>()) }

    val groups = remember(results, format, onlyFree) {
        folderGroups(results.filter { !onlyFree || it.freeSlot }) {
            when (format) {
                "flac" -> it.extension in LOSSLESS
                "mp3_320" -> isMp3_320(it)
                else -> true
            }
        }
            .sortedWith(compareByDescending<FolderGroup> { it.free }.thenByDescending { it.speed }.thenBy { it.queue })
    }
    val state = remember(downloads) { downloads.associateBy { it.username + "|" + it.path } }
    // Lo que ya tienes, para "Ya lo tienes" / "Tienes N de M"
    val localIndex = remember(librarySongs.size) {
        AlbumMatcher.LocalIndex(librarySongs.map { AlbumMatcher.LocalSong(it.album, it.artist, it.id) })
    }
    val owned = remember(albums, localIndex) { albums.associate { it.id to AlbumMatcher.ownedCount(it.title, it.artist, localIndex) } }
    val (byAlbum, others) = remember(groups, albums) {
        val prepared = AlbumMatcher.prepare(albums.map { AlbumMatcher.Album(it, it.title, it.artist, it.trackCount) })
        val matched = LinkedHashMap<Long, MutableList<FolderGroup>>()
        val rest = ArrayList<FolderGroup>()
        groups.forEach { g ->
            val a = AlbumMatcher.match(prepared, g.folder, g.files.map { it.name }, g.files.size)
            if (a != null) matched.getOrPut(a.id) { ArrayList() }.add(g) else rest += g
        }
        albums.mapNotNull { a -> matched[a.id]?.let { a to rankSources(a.trackCount, it) } } to rest
    }

    fun search() {
        val q = query.trim()
        if (q.isBlank()) return
        keyboard?.hide()
        error = null; expanded = emptySet(); openAlbum = null; albums = emptyList()
        client.search(q)
        scope.launch { albums = runCatching { DeezerTrackSearch.searchAlbums(q, 25) }.getOrDefault(emptyList()) }
    }

    openAlbum?.let { album ->
        BackHandler { openAlbum = null }
        val sources = byAlbum.firstOrNull { it.first.id == album.id }?.second.orEmpty()
        AlbumSourcesPage(album, sources, state, owned[album.id] ?: 0, onBack = { openAlbum = null }) { error = it }
        return
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Artista, álbum o canción") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, "Borrar") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to "Todos", "flac" to "FLAC", "mp3_320" to "MP3 320").forEach { (key, label) ->
                FilterChip(format == key, {
                    format = key
                    prefs.edit().putString("soulseek_format", key).apply()
                }, label = { Text(label) })
            }
            FilterChip(onlyFree, { onlyFree = !onlyFree }, label = { Text("Disponible ahora") })
        }
        if (results.isNotEmpty() || searching) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                if (searching) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(
                    (if (byAlbum.isNotEmpty()) "${byAlbum.size} álbumes reconocidos · " else "") + "${groups.size} carpetas de ${results.size} usuarios",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(8.dp))
        if (groups.isEmpty()) {
            Text(
                when {
                    searching -> "Buscando… los resultados van llegando de cada usuario."
                    results.isEmpty() -> "Busca en lo que comparten los usuarios de Soulseek. Las carpetas suelen ser álbumes completos."
                    else -> "Ningún resultado con esos filtros."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
            )
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (byAlbum.isNotEmpty()) {
                item(key = "h-albums") { ListHeader("Álbumes") }
                items(byAlbum, key = { "album-" + it.first.id }) { (album, sources) ->
                    AlbumCard(album, sources, owned[album.id] ?: 0) { openAlbum = album }
                }
                if (others.isNotEmpty()) item(key = "h-others") { ListHeader("Otras carpetas") }
            }
            folders(others, expanded, state, 0, { k -> expanded = if (k in expanded) expanded - k else expanded + k }) { error = it }
        }
    }
}

private fun LazyListScope.folders(
    groups: List<FolderGroup>, expanded: Set<String>, state: Map<String, Transfer>, total: Int,
    toggle: (String) -> Unit, onError: (String) -> Unit
) {
    groups.forEach { g ->
        item(key = g.key) {
            FolderCard(g, g.key in expanded, state, total, onToggle = { toggle(g.key) }) {
                SoulseekManager.client.downloadFolder(g.user, g.folder, g.files, onError)
            }
        }
        if (g.key in expanded) items(g.files, key = { g.key + "|" + it.path }) { f -> FileRow(g.user, f, state[g.user + "|" + f.path]) }
    }
}

@Composable
private fun ListHeader(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
}

private fun ownedLabel(album: DeezerAlbum, owned: Int) = when {
    owned <= 0 -> null
    album.trackCount in 1..owned -> "Ya lo tienes"
    album.trackCount > 0 -> "Tienes $owned de ${album.trackCount}"
    else -> "Tienes $owned"
}

@Composable
private fun AlbumCard(album: DeezerAlbum, sources: List<FolderGroup>, owned: Int, onOpen: () -> Unit) {
    val best = sources.first()
    Card(
        onClick = onOpen, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(album.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(album.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(album.artist, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${sources.size} ${if (sources.size == 1) "fuente" else "fuentes"} · mejor: " +
                        listOfNotNull(best.formats, quality(best.bitrate, best.depth, best.rate), if (best.free) "disponible" else null).joinToString(" · "),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                ownedLabel(album, owned)?.let { Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Un álbum reconocido con todas las carpetas que lo tienen, de mejor a peor. */
@Composable
private fun AlbumSourcesPage(
    album: DeezerAlbum, sources: List<FolderGroup>, state: Map<String, Transfer>, owned: Int,
    onBack: () -> Unit, onError: (String) -> Unit
) {
    val client = SoulseekManager.client
    var expanded by remember(album.id) { mutableStateOf(setOf<String>()) }
    val best = sources.firstOrNull()
    val plan = remember(album, sources) { splitPlan(album.trackCount, album.artist, sources) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "head") {
            Column {
                TextButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Volver a los resultados")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(album.cover, null, contentScale = ContentScale.Crop, modifier = Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(album.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(album.artist, if (album.trackCount > 0) "${album.trackCount} pistas" else "").filter { it.isNotBlank() }.joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                        )
                        ownedLabel(album, owned)?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
                    }
                }
                if (best != null) {
                    val queued = best.files.count { state[best.user + "|" + it.path] != null }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { client.downloadFolder(best.user, best.folder, best.files, onError) },
                        enabled = queued == 0, modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp))
                        Text(if (queued > 0) "En descargas" else "Descargar la mejor")
                    }
                    if (plan != null) {
                        val users = plan.flatten().map { it.first }.distinct().size
                        FilledTonalButton(onClick = { client.downloadSplit(best.title, plan) }, enabled = queued == 0, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.AutoMirrored.Rounded.CallSplit, null); Spacer(Modifier.width(6.dp))
                            Text("Descargar repartido ($users fuentes)")
                        }
                        Text(
                            "Repartido: varias canciones a la vez, una por usuario; si una fuente falla o tiene mucha cola, pasa a otra.",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        listOfNotNull(best.user, best.formats, quality(best.bitrate, best.depth, best.rate), if (best.free) "disponible ahora" else "cola ${best.queue}")
                            .joinToString(" · "),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                    )
                }
                ListHeader("${sources.size} ${if (sources.size == 1) "fuente" else "fuentes"}")
            }
        }
        folders(sources, expanded, state, album.trackCount, { k -> expanded = if (k in expanded) expanded - k else expanded + k }, onError)
    }
}

@Composable
private fun FolderCard(g: FolderGroup, expanded: Boolean, state: Map<String, Transfer>, total: Int, onToggle: () -> Unit, onDownload: () -> Unit) {
    val queued = g.files.count { state[g.user + "|" + it.path] != null }
    val bytes = g.files.sumOf { it.size }
    Card(
        onClick = onToggle, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
    ) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(g.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        g.user,
                        if (total > 0) "${g.files.size}/$total pistas" else "${g.files.size} ${if (g.files.size == 1) "archivo" else "archivos"}",
                        g.formats, quality(g.bitrate, g.depth, g.rate), formatSize(bytes)
                    ).joinToString(" · "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    (if (g.free) "Disponible ahora" else "Cola ${g.queue}") +
                        if (g.speed > 0) " · ${formatSpeed(g.speed)} · ≈ ${formatEta(bytes / g.speed)}" else "",
                    fontSize = 11.sp,
                    color = if (g.free) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onDownload, enabled = queued < g.files.size) {
                Icon(if (queued >= g.files.size) Icons.Rounded.DownloadDone else Icons.Rounded.Download, "Descargar carpeta")
            }
        }
    }
}

@Composable
private fun FileRow(user: String, f: SlskFile, t: Transfer?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(f.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                t?.let { stateLabel(it) } ?: listOfNotNull(
                    f.extension.uppercase(), quality(f.bitrate, f.bitDepth, f.sampleRate), formatSize(f.size),
                    f.durationSec?.let { "%d:%02d".format(it / 60, it % 60) }
                ).joinToString(" · "),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
            )
        }
        IconButton(onClick = { SoulseekManager.client.download(user, listOf(f)) }, enabled = t == null) {
            Icon(if (t == null) Icons.Rounded.Download else Icons.Rounded.DownloadDone, "Descargar", Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TransfersTab(downloads: List<Transfer>) {
    val client = SoulseekManager.client
    val uploads by client.uploads.collectAsState()
    if (downloads.isEmpty() && uploads.isEmpty()) {
        Text("Aún no hay descargas. Lo descargado se guarda en tu carpeta de música (Artista/Álbum) y entra en la biblioteca.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        return
    }
    val speed = downloads.filter { it.state == TransferState.Transferring }.sumOf { it.speed }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "dl-head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Descargas" + if (speed > 0) " · ${formatSpeed(speed)}" else "",
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                if (downloads.any { it.state == TransferState.Failed && !it.awaitingPhone }) {
                    TextButton(onClick = { downloads.filter { it.state == TransferState.Failed }.forEach { client.retry(it.id) } }) { Text("Reintentar") }
                }
                if (downloads.any { it.finished }) TextButton(onClick = { client.clearFinished() }) { Text("Limpiar") }
            }
        }
        items(downloads.reversed(), key = { "dl-" + it.id }) { t -> TransferRow(t, upload = false) }
        if (uploads.isNotEmpty()) {
            item(key = "ul-head") { ListHeader("Subidas") }
            items(uploads, key = { "ul-" + it.id }) { t -> TransferRow(t, upload = true) }
        }
    }
}

@Composable
private fun TransferRow(t: Transfer, upload: Boolean) {
    val client = SoulseekManager.client
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (upload) "Para ${t.username}" else t.username) + " · " + stateLabel(t),
                        fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (t.state == TransferState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!upload) {
                    if (t.state == TransferState.Failed) IconButton(onClick = { client.retry(t.id) }) { Icon(Icons.Rounded.Refresh, "Reintentar", Modifier.size(20.dp)) }
                    if (!t.finished || t.state == TransferState.Failed) IconButton(onClick = { client.cancel(t.id) }) { Icon(Icons.Rounded.Close, "Cancelar", Modifier.size(20.dp)) }
                }
            }
            if (t.state == TransferState.Transferring || (t.state == TransferState.Sending && t.done > 0)) {
                LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp).clip(RoundedCornerShape(2.dp)))
            }
        }
    }
}

private fun stateLabel(t: Transfer): String = when (t.state) {
    TransferState.Queued -> t.detail.ifBlank { "Pendiente" }
    TransferState.Waiting -> t.detail.ifBlank { "En cola" }
    TransferState.Connecting -> "Conectando…"
    TransferState.Transferring -> "${(t.progress * 100).toInt()} %" +
        if (t.speed > 0) " · ${formatSpeed(t.speed)} · quedan ${formatEta((t.size - t.done).coerceAtLeast(0) / t.speed)}" else ""
    TransferState.Sending -> t.detail
    TransferState.Done -> t.detail.ifBlank { "Lista" }
    TransferState.Failed -> t.detail.ifBlank { "Falló" }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}
