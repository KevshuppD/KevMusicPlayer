package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.DzAlbum
import com.kevshupp.kevmusic.desktop.formatTime
import kotlinx.coroutines.launch
import com.kevshupp.kevmusic.desktop.soulseek.*
import java.io.File
import javax.swing.JFileChooser

val LocalSoulseek = staticCompositionLocalOf<SoulseekClient> { error("sin Soulseek") }

private enum class SlskTab(val label: String) { Search("Buscar"), Downloads("Descargas"), Uploads("Subidas"), Share("Compartir y ajustes") }

/** Una carpeta de un usuario dentro de los resultados (normalmente un álbum). */
/** Orden de las carpetas que no son álbumes reconocidos (los álbumes ordenan sus fuentes con [rankSources]). */
private enum class ResultSort(val label: String) { Best("Mejor fuente"), Fastest("Más rápidos"), Quality("Mayor calidad"), Tracks("Más pistas") }

private data class FolderGroup(val user: String, val folder: String, val files: List<SlskFile>, val free: Boolean, val speed: Long, val queue: Long) {
    val key get() = "$user|$folder"
    val title get() = folder.substringAfterLast('\\').ifBlank { folder }
    val formats get() = files.map { it.extension.uppercase() }.distinct().joinToString("/")
    val bitrate get() = files.mapNotNull { it.bitrate }.maxOrNull()
    val depth get() = files.mapNotNull { it.bitDepth }.maxOrNull()
    val rate get() = files.mapNotNull { it.sampleRate }.maxOrNull()
}

@Composable
fun SoulseekScreen() {
    val slsk = LocalSoulseek.current
    val status by slsk.status.collectAsState()
    var tab by remember { mutableStateOf(SlskTab.Search) }

    // Sin cuenta, desconectado a propósito o con un error de inicio de sesión (no se reintenta solo)
    if (status !is SlskStatus.Online && (!SoulseekConfig.hasAccount || status is SlskStatus.Off || (status is SlskStatus.Failed && !slsk.isRunning))) {
        LoginCard(status)
        return
    }
    val downloads by slsk.downloads.collectAsState()
    val uploads by slsk.uploads.collectAsState()
    val activeDl = downloads.count { !it.finished }
    val activeUl = uploads.count { it.state == TransferState.Transferring || it.state == TransferState.Connecting }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            SlskTab.entries.forEach { t ->
                val label = when {
                    t == SlskTab.Downloads && activeDl > 0 -> "${t.label} ($activeDl)" +
                        downloads.filter { it.state == TransferState.Transferring }.sumOf { it.speed }.takeIf { it > 0 }?.let { " · ${formatSpeed(it)}" }.orEmpty()
                    t == SlskTab.Uploads && activeUl > 0 -> "${t.label} ($activeUl)"
                    else -> t.label
                }
                FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            StatusPill(status)
        }
        when (tab) {
            SlskTab.Search -> SearchTab(downloads)
            SlskTab.Downloads -> TransferList(downloads, upload = false)
            SlskTab.Uploads -> TransferList(uploads, upload = true)
            SlskTab.Share -> ShareSettings()
        }
    }
}

@Composable
private fun StatusPill(status: SlskStatus) {
    val (text, color) = when (status) {
        is SlskStatus.Online -> "Conectado como ${status.username}" to Color(0xFF00E676)
        SlskStatus.Connecting -> "Conectando…" to MaterialTheme.colorScheme.secondary
        is SlskStatus.Failed -> "Sin conexión" to MaterialTheme.colorScheme.error
        SlskStatus.Off -> "Desconectado" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoginCard(status: SlskStatus) {
    val slsk = LocalSoulseek.current
    var user by remember { mutableStateOf(SoulseekConfig.username) }
    var pass by remember { mutableStateOf(SoulseekConfig.password) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 560.dp)) {
        Column(Modifier.padding(24.dp)) {
            Text("Inicia sesión en Soulseek", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Si el nombre no existe, Soulseek crea la cuenta con esa contraseña. Lo que descargues queda en el PC " +
                    "(se comparte con los demás) y se envía al teléfono.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(user, { user = it.trim() }, label = { Text("Usuario") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                pass, { pass = it }, label = { Text("Contraseña") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            if (status is SlskStatus.Failed) {
                Spacer(Modifier.height(10.dp))
                Text(status.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        SoulseekConfig.username = user; SoulseekConfig.password = pass; SoulseekConfig.autoConnect = true
                        SoulseekConfig.save()
                        slsk.restart()
                    },
                    enabled = user.isNotBlank() && pass.isNotBlank() && status != SlskStatus.Connecting,
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Conectar") }
                if (status == SlskStatus.Connecting) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

// ---------- Buscar ----------

private val LOSSLESS = setOf("flac", "wav", "aiff", "aif", "alac", "ape")

private enum class FormatFilter(val label: String, val extensions: Set<String>?) {
    All("Todos", null),
    Mp3("MP3", setOf("mp3")),
    Flac("FLAC", setOf("flac")),
    Lossless("Sin pérdida", LOSSLESS),
    Aac("M4A/AAC", setOf("m4a", "aac")),
    Ogg("OGG/Opus", setOf("ogg", "opus"));

    fun matches(f: SlskFile) = extensions == null || f.extension in extensions
}

/**
 * Sin pérdida, o con pérdida a 256 kbps o más. Muchos no envían el bitrate: se estima con tamaño y duración,
 * y sin ninguno de los dos el archivo no cuenta.
 */
private fun isHighQuality(f: SlskFile): Boolean {
    if (f.extension in LOSSLESS) return true
    val kbps = f.bitrate ?: f.durationSec?.takeIf { it > 0 }?.let { (f.size * 8 / 1000 / it).toInt() } ?: return false
    return kbps >= 256
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchTab(downloads: List<Transfer>) {
    val slsk = LocalSoulseek.current
    val results by slsk.results.collectAsState()
    val searching by slsk.searching.collectAsState()
    var query by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(FormatFilter.All) }
    var hq by remember { mutableStateOf(false) }
    var onlyFree by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(ResultSort.Best) }
    var error by remember { mutableStateOf<String?>(null) }

    val groups = remember(results, format, hq, onlyFree, sort) {
        results.asSequence()
            .filter { !onlyFree || it.freeSlot }
            .flatMap { r ->
                r.files.filter { format.matches(it) && (!hq || isHighQuality(it)) }
                    .groupBy { it.folder }
                    .map { (folder, files) -> FolderGroup(r.username, folder, files.sortedBy { it.path }, r.freeSlot, r.speed, r.queue) }
            }
            .distinctBy { it.key }
            .sortedWith(
                when (sort) {
                    // Primero quien está disponible ahora (hueco de subida libre), después los más rápidos
                    ResultSort.Best -> compareByDescending<FolderGroup> { it.free }.thenByDescending { it.speed }.thenBy { it.queue }
                    ResultSort.Fastest -> compareByDescending<FolderGroup> { it.speed }.thenByDescending { it.free }
                    ResultSort.Quality -> compareByDescending<FolderGroup> { g -> g.files.any { it.extension in LOSSLESS_EXT } }
                        .thenByDescending { it.bitrate ?: 0 }.thenByDescending { it.free }.thenByDescending { it.speed }
                    ResultSort.Tracks -> compareByDescending<FolderGroup> { it.files.size }.thenByDescending { it.free }.thenByDescending { it.speed }
                }
            )
            .toList()
    }
    val state = remember(downloads) { downloads.associateBy { it.username + "|" + it.path } }
    var expanded by remember(results.isEmpty()) { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()
    var albums by remember { mutableStateOf<List<DzAlbum>>(emptyList()) }
    var openAlbum by remember { mutableStateOf<DzAlbum?>(null) }
    // Lo que ya está en el teléfono, para avisar "Ya lo tienes" en los álbumes reconocidos
    val library by LocalClient.current.library.collectAsState()
    val localIndex = remember(library) { AlbumMatcher.LocalIndex(library?.songs.orEmpty()) }
    val owned = remember(albums, localIndex) { albums.associate { it.id to AlbumMatcher.ownedCount(it, localIndex) } }

    // Carpetas reconocidas como un álbum de Deezer (en el orden de Deezer) y el resto
    val (byAlbum, others) = remember(groups, albums) {
        val prepared = AlbumMatcher.prepare(albums)
        val matched = LinkedHashMap<Long, MutableList<FolderGroup>>()
        val rest = ArrayList<FolderGroup>()
        groups.forEach { g ->
            val a = AlbumMatcher.match(prepared, g.folder, g.files.map { it.name }, g.files.size)
            if (a != null) matched.getOrPut(a.id) { ArrayList() }.add(g) else rest += g
        }
        albums.mapNotNull { a -> matched[a.id]?.let { a to rankSources(a, it) } } to rest
    }

    fun search() {
        error = null; expanded = emptySet(); openAlbum = null; albums = emptyList()
        slsk.search(query)
        val q = query.trim()
        scope.launch { albums = AlbumMatcher.searchAlbums(q) }
    }

    openAlbum?.let { album ->
        val sources = byAlbum.firstOrNull { it.first.id == album.id }?.second.orEmpty()
        AlbumSourcesPage(album, sources, state, owned[album.id] ?: 0, onBack = { openAlbum = null }) { error = it }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(
                query, { query = it }, "Artista, álbum o canción y pulsa Enter",
                Modifier.weight(1f).onPreviewKeyEvent { if (it.key == Key.Enter) { search(); true } else false }
            )
            Spacer(Modifier.width(10.dp))
            Button(onClick = { search() }, enabled = query.isNotBlank(), shape = RoundedCornerShape(14.dp)) { Text("Buscar") }
        }
        Spacer(Modifier.height(10.dp))
        // Los chips bajan a otra línea si la ventana es estrecha
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FormatFilter.entries.forEach { f -> FilterChip(format == f, { format = f }, label = { Text(f.label) }) }
            Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant).align(Alignment.CenterVertically))
            FilterChip(hq, { hq = !hq }, label = { Text("Alta calidad") })
            FilterChip(onlyFree, { onlyFree = !onlyFree }, label = { Text("Disponible ahora") })
            Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outlineVariant).align(Alignment.CenterVertically))
            Box {
                var open by remember { mutableStateOf(false) }
                AssistChip(onClick = { open = true }, label = { Text("Orden: ${sort.label}") }, leadingIcon = { Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp)) })
                DropdownMenu(open, { open = false }) {
                    ResultSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; open = false }) }
                }
            }
        }
        if (results.isNotEmpty() || searching) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                if (searching) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    (if (byAlbum.isNotEmpty()) "${byAlbum.size} álbumes reconocidos · " else "") + "${groups.size} carpetas de ${results.size} usuarios",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(10.dp))
        if (groups.isEmpty()) {
            Text(
                if (searching) "Buscando… los resultados van llegando de cada usuario."
                else if (results.isEmpty()) "Busca en lo que comparten los usuarios de Soulseek. Las carpetas suelen ser álbumes completos."
                else "Ningún resultado con esos filtros.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        Scrolling {
            if (byAlbum.isNotEmpty()) {
                item(key = "h-albums") { ListHeader("Álbumes") }
                items(byAlbum, key = { "album-" + it.first.id }) { (album, sources) -> AlbumCard(album, sources, owned[album.id] ?: 0) { openAlbum = album } }
                if (others.isNotEmpty()) item(key = "h-others") { ListHeader("Otras carpetas") }
            }
            others.forEach { g ->
                item(key = g.key) {
                    FolderCard(g, g.key in expanded, state, onToggle = { expanded = if (g.key in expanded) expanded - g.key else expanded + g.key }) {
                        slsk.downloadFolder(g.user, g.folder, g.files) { error = it }
                    }
                }
                if (g.key in expanded) items(g.files, key = { g.key + "|" + it.path }) { f -> FileRow(g.user, f, state[g.user + "|" + f.path]) }
            }
        }
    }
}

private val LOSSLESS_EXT = setOf("flac", "wav", "aiff", "aif", "alac", "ape")

/** Mejores fuentes primero: completas, disponibles ahora, sin pérdida, mayor bitrate, más rápidas. */
private fun rankSources(album: DzAlbum, sources: List<FolderGroup>): List<FolderGroup> = sources.sortedWith(
    compareByDescending<FolderGroup> { album.tracks > 0 && it.files.size >= album.tracks }
        .thenByDescending { it.free }
        .thenByDescending { g -> g.files.any { it.extension in LOSSLESS_EXT } }
        .thenByDescending { it.bitrate ?: 0 }
        .thenByDescending { it.speed }
        .thenBy { it.queue }
)

@Composable
private fun ListHeader(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
}

@Composable
private fun AlbumCard(album: DzAlbum, sources: List<FolderGroup>, owned: Int, onOpen: () -> Unit) {
    val free = sources.count { it.free }
    val complete = sources.count { album.tracks > 0 && it.files.size >= album.tracks }
    Surface(onClick = onOpen, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            UrlImage(album.cover, 64.dp, corner = 10.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(album.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (owned > 0) { Spacer(Modifier.width(8.dp)); OwnedBadge(album, owned) }
                }
                Text(
                    listOf(album.artist, albumTypeLabel(album.type), if (album.tracks > 0) "${album.tracks} pistas" else "").filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
                Text(
                    listOfNotNull(
                        "${sources.size} ${if (sources.size == 1) "fuente" else "fuentes"}",
                        sources.flatMap { g -> g.files.map { it.extension.uppercase() } }.distinct().joinToString("/"),
                        if (complete > 0) "$complete completas" else null,
                        if (free > 0) "$free disponibles ahora" else null
                    ).joinToString(" · "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Ya lo tienes" si están todas las pistas (o más: otra edición), si no "Tienes N de M". */
private fun ownedLabel(album: DzAlbum, owned: Int) = when {
    album.tracks in 1..owned -> "Ya lo tienes"
    album.tracks > 0 -> "Tienes $owned de ${album.tracks}"
    else -> "Tienes $owned"
}

@Composable
private fun OwnedBadge(album: DzAlbum, owned: Int) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(ownedLabel(album, owned), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
}

private fun albumTypeLabel(type: String) = when (type) { "single" -> "Sencillo"; "ep" -> "EP"; "compile" -> "Recopilatorio"; "album" -> "Álbum"; else -> "" }

/** Nombre de la canción sin número de pista, artista ni extensión, para emparejarla entre carpetas de distintos usuarios. */
private fun songKey(fileName: String, artist: Set<String>): String =
    AlbumMatcher.tokens(fileName.substringBeforeLast('.')).filterNot { it.all(Char::isDigit) || it in artist }.sorted().joinToString(" ")

/**
 * Reparto para "Descargar repartido": fuentes completas, de usuarios distintos y con los mismos formatos que la
 * mejor (para no mezclar FLAC con MP3); por cada canción de la mejor, las fuentes que tienen la misma canción.
 * Null si no hay al menos dos fuentes que compartan la mayoría de las canciones.
 */
private fun splitPlan(album: DzAlbum, sources: List<FolderGroup>): List<List<Pair<String, SlskFile>>>? {
    val complete = sources.filter { album.tracks <= 0 || it.files.size >= album.tracks }
    val base = complete.firstOrNull() ?: return null
    val compatible = complete.filter { it.formats == base.formats }.distinctBy { it.user }
    if (compatible.size < 2) return null
    val artist = AlbumMatcher.tokens(album.artist)
    val byKey = compatible.map { g -> g.user to g.files.associateBy { songKey(it.name, artist) } }
    val plan = base.files.map { f ->
        val key = songKey(f.name, artist)
        byKey.mapNotNull { (user, files) -> files[key]?.let { user to it } }.ifEmpty { listOf(base.user to f) }
    }
    return plan.takeIf { p -> p.count { it.size >= 2 } * 2 >= p.size }
}

/** Un álbum reconocido con todas las carpetas que lo tienen, de mejor a peor. */
@Composable
private fun AlbumSourcesPage(album: DzAlbum, sources: List<FolderGroup>, state: Map<String, Transfer>, owned: Int, onBack: () -> Unit, onError: (String) -> Unit) {
    val slsk = LocalSoulseek.current
    var expanded by remember(album.id) { mutableStateOf(setOf<String>()) }
    val best = sources.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Volver a los resultados") }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 14.dp)) {
            UrlImage(album.cover, 140.dp, corner = 16.dp)
            Spacer(Modifier.width(20.dp))
            Column {
                Text(album.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(album.artist, albumTypeLabel(album.type), if (album.tracks > 0) "${album.tracks} pistas" else "").filter { it.isNotBlank() }.joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (owned > 0) { Spacer(Modifier.height(6.dp)); OwnedBadge(album, owned) }
                Spacer(Modifier.height(10.dp))
                if (best != null) {
                    val queued = best.files.count { state[best.user + "|" + it.path] != null }
                    val plan = remember(album, sources) { splitPlan(album, sources) }
                    Row {
                        Button(onClick = { slsk.downloadFolder(best.user, best.folder, best.files, onError) }, enabled = queued == 0, shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp))
                            Text(if (queued > 0) "En descargas" else "Descargar la mejor")
                        }
                        if (plan != null) {
                            val users = plan.flatten().map { it.first }.distinct().size
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(onClick = { slsk.downloadSplit(best.title, plan) }, enabled = queued == 0, shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Rounded.CallSplit, null); Spacer(Modifier.width(6.dp))
                                Text("Descargar repartido ($users fuentes)")
                            }
                        }
                    }
                    if (plan != null) Text(
                        "Repartido: varias canciones a la vez, una por usuario; si una fuente falla o tiene mucha cola, pasa a otra.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        listOfNotNull(best.user, best.formats, quality(best.bitrate, best.depth, best.rate), if (best.free) "disponible ahora" else "cola ${best.queue}")
                            .joinToString(" · "),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
        ListHeader("${sources.size} ${if (sources.size == 1) "fuente" else "fuentes"}")
        Scrolling {
            sources.forEach { g ->
                item(key = g.key) {
                    FolderCard(g, g.key in expanded, state, total = album.tracks, onToggle = { expanded = if (g.key in expanded) expanded - g.key else expanded + g.key }) {
                        slsk.downloadFolder(g.user, g.folder, g.files, onError)
                    }
                }
                if (g.key in expanded) items(g.files, key = { g.key + "|" + it.path }) { f -> FileRow(g.user, f, state[g.user + "|" + f.path]) }
            }
        }
    }
}

@Composable
private fun Scrolling(content: LazyListScope.() -> Unit) {
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun FolderCard(g: FolderGroup, expanded: Boolean, state: Map<String, Transfer>, total: Int = 0, onToggle: () -> Unit, onDownload: () -> Unit) {
    val queued = g.files.count { state[g.user + "|" + it.path] != null }
    Surface(
        onClick = onToggle, shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(g.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(g.folder, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        if (total > 0) "${g.files.size}/$total pistas" else "${g.files.size} ${if (g.files.size == 1) "archivo" else "archivos"}",
                        g.formats,
                        quality(g.bitrate, g.depth, g.rate),
                        formatSize(g.files.sumOf { it.size }),
                        g.user,
                        if (g.free) "disponible ahora" else "cola ${g.queue}"
                    ).joinToString(" · "),
                    fontSize = 12.sp, color = if (g.free) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            SpeedBadge(g.speed, g.files.sumOf { it.size })
            Spacer(Modifier.width(10.dp))
            if (queued > 0) Text("$queued en descargas", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            else FilledTonalButton(onClick = onDownload, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Carpeta completa", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FileRow(user: String, f: SlskFile, t: Transfer?) {
    val slsk = LocalSoulseek.current
    Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(f.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(f.extension.uppercase(), quality(f.bitrate, f.bitDepth, f.sampleRate), formatSize(f.size), f.durationSec?.let { formatTime(it * 1000L) })
                    .joinToString(" · "),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (t != null) Text(stateLabel(t), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        else IconButton(onClick = { slsk.download(user, listOf(f)) }) { Icon(Icons.Rounded.Download, "Descargar", tint = MaterialTheme.colorScheme.primary) }
    }
}

// ---------- Descargas y subidas ----------

@Composable
private fun TransferList(list: List<Transfer>, upload: Boolean) {
    val slsk = LocalSoulseek.current
    val finished = list.count { it.finished }
    val failed = list.filter { it.state == TransferState.Failed }
    Column(Modifier.fillMaxSize()) {
        if (list.isEmpty()) {
            Text(
                if (upload) "Nadie está descargando de ti ahora. Cuanto más compartas, más te encontrarán."
                else "No hay descargas. Busca algo y pulsa descargar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        val active = list.filter { it.state == TransferState.Transferring }
        val total = active.sumOf { it.speed }
        val remaining = active.sumOf { (it.size - it.done).coerceAtLeast(0) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            if (active.isNotEmpty()) {
                Icon(if (upload) Icons.Rounded.Upload else Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    formatSpeed(total) + (if (!upload && total > 0) " · quedan ${formatEta(remaining / total)}" else ""),
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
                )
                Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${list.size} en total · $finished terminadas", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (!upload && failed.isNotEmpty()) TextButton(onClick = { failed.forEach { slsk.retry(it.id) } }) { Text("Reintentar las fallidas") }
            TextButton(onClick = { slsk.clearFinished() }, enabled = finished > 0) { Text("Limpiar terminadas") }
        }
        Scrolling { items(list, key = { it.id }) { TransferRow(it, upload) } }
    }
}

@Composable
private fun TransferRow(t: Transfer, upload: Boolean) {
    val slsk = LocalSoulseek.current
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (t.state) {
                    TransferState.Done -> Icons.Rounded.CheckCircle
                    TransferState.Failed -> Icons.Rounded.ErrorOutline
                    TransferState.Sending -> Icons.Rounded.PhoneAndroid
                    TransferState.Transferring -> if (upload) Icons.Rounded.Upload else Icons.Rounded.Download
                    else -> Icons.Rounded.Schedule
                },
                null,
                tint = when (t.state) {
                    TransferState.Done -> Color(0xFF00E676)
                    TransferState.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${if (upload) "Para" else "De"} ${t.username} · ${stateLabel(t)}",
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (t.state == TransferState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (t.state == TransferState.Transferring || (t.state == TransferState.Sending && t.done > 0)) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(formatSize(t.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!upload) {
                if (t.state == TransferState.Failed) IconButton(onClick = { slsk.retry(t.id) }) { Icon(Icons.Rounded.Refresh, "Reintentar") }
                if (!t.finished && t.state != TransferState.Sending) IconButton(onClick = { slsk.cancel(t.id) }) { Icon(Icons.Rounded.Close, "Cancelar") }
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
    TransferState.Sending -> if (t.done > 0) "Enviando al teléfono · ${(t.progress * 100).toInt()} %" else t.detail
    TransferState.Done -> t.detail.ifBlank { "Lista" }
    TransferState.Failed -> t.detail.ifBlank { "Falló" }
}

// ---------- Compartir y ajustes ----------

@Composable
private fun ShareSettings() {
    val slsk = LocalSoulseek.current
    val (folders, files) = slsk.shareStats.collectAsState().value
    val portInfo by slsk.portInfo.collectAsState()
    var portText by remember { mutableStateOf(SoulseekConfig.port.toString()) }
    var limitText by remember { mutableStateOf(SoulseekConfig.uploadLimit.toString()) }
    val scroll = rememberScrollState()

    fun changed() = SoulseekConfig.save()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsCard("Carpetas compartidas", "$folders carpetas · $files canciones. Compartir es lo que hace que no te bloqueen y que te den prioridad.") {
                SoulseekConfig.sharedFolders.forEach { path ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(path, modifier = Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = {
                            SoulseekConfig.sharedFolders = SoulseekConfig.sharedFolders - path; changed(); Thread { slsk.rescanShares() }.start()
                        }) { Icon(Icons.Rounded.Close, "Dejar de compartir") }
                    }
                }
                Row {
                    OutlinedButton(onClick = {
                        chooseFolder()?.let { dir ->
                            if (dir !in SoulseekConfig.sharedFolders) {
                                SoulseekConfig.sharedFolders = SoulseekConfig.sharedFolders + dir; changed(); Thread { slsk.rescanShares() }.start()
                            }
                        }
                    }) { Icon(Icons.Rounded.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text("Añadir carpeta") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { Thread { slsk.rescanShares() }.start() }) { Text("Volver a escanear") }
                }
            }
            SettingsCard("Descargas", "Lo descargado se guarda aquí, en una subcarpeta por álbum (si está dentro de una carpeta compartida, también se comparte).") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(SoulseekConfig.downloadDir, modifier = Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { chooseFolder()?.let { SoulseekConfig.downloadDir = it; changed() } }) { Text("Cambiar") }
                }
                SwitchRow("Enviar al teléfono", "Cada canción descargada se sube al teléfono y entra en su biblioteca.", SoulseekConfig.sendToPhone) {
                    SoulseekConfig.sendToPhone = it; changed()
                }
                SwitchRow("Conservar la copia en el PC", "Si lo apagas, se borra del PC tras enviarla (y deja de compartirse).", SoulseekConfig.keepOnPc) {
                    SoulseekConfig.keepOnPc = it; changed()
                }
            }
            SettingsCard("Subidas y conexión", portInfo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Subidas a la vez", modifier = Modifier.weight(1f))
                    IconButton(onClick = { SoulseekConfig.uploadSlots = (SoulseekConfig.uploadSlots - 1).coerceAtLeast(1); changed() }) { Icon(Icons.Rounded.Remove, null) }
                    Text("${SoulseekConfig.uploadSlots}", fontWeight = FontWeight.Bold)
                    IconButton(onClick = { SoulseekConfig.uploadSlots = (SoulseekConfig.uploadSlots + 1).coerceAtMost(10); changed() }) { Icon(Icons.Rounded.Add, null) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Límite de subida (KB/s, 0 = sin límite)", modifier = Modifier.weight(1f))
                    OutlinedTextField(limitText, { v ->
                        limitText = v.filter(Char::isDigit).take(6)
                        SoulseekConfig.uploadLimit = limitText.toIntOrNull() ?: 0; changed()
                    }, singleLine = true, modifier = Modifier.width(120.dp))
                }
                SwitchRow("Abrir el puerto en el router (UPnP)", "Deja que otros se conecten a ti: más velocidad y más gente puede descargar de ti.", SoulseekConfig.useUpnp) {
                    SoulseekConfig.useUpnp = it; changed()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Puerto de escucha", modifier = Modifier.weight(1f))
                    OutlinedTextField(portText, { portText = it.filter(Char::isDigit).take(5) }, singleLine = true, modifier = Modifier.width(120.dp))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        portText.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { SoulseekConfig.port = it; changed(); slsk.restart() }
                    }) { Text("Aplicar") }
                }
            }
            SettingsCard("Cuenta", "Usuario: ${SoulseekConfig.username}") {
                SwitchRow("Conectar al abrir KevMusic Desktop", "Para seguir compartiendo aunque no estés en esta pantalla.", SoulseekConfig.autoConnect) {
                    SoulseekConfig.autoConnect = it; changed()
                }
                Row {
                    OutlinedButton(onClick = { slsk.restart() }) { Text("Reconectar") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        slsk.stop(); SoulseekConfig.password = ""; changed()
                    }) { Text("Cerrar sesión", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

private fun chooseFolder(): String? {
    val chooser = JFileChooser(System.getProperty("user.home")).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Elegir carpeta"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.let { File(it.path).absolutePath } else null
}

private fun quality(bitrate: Int?, depth: Int?, rate: Int?): String? = when {
    depth != null && rate != null -> "$depth bit / ${"%.1f".format(rate / 1000.0)} kHz"
    bitrate != null && bitrate > 0 -> "$bitrate kbps"
    else -> null
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}

/** "1:05", "12 s" o "1 h 3 min". */
private fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds.coerceAtLeast(1)} s"
    seconds < 3600 -> "%d:%02d".format(seconds / 60, seconds % 60)
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
}

/**
 * Velocidad de subida que el usuario anuncia en sus resultados (la mide el servidor de Soulseek con sus últimas
 * subidas) y cuánto tardaría la carpeta a esa velocidad. Es una estimación: el reparto real depende de su cola.
 */
@Composable
private fun SpeedBadge(speed: Long, bytes: Long) {
    val color = when {
        speed <= 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        speed >= 1L shl 20 -> Color(0xFF00E676)
        speed >= 200L * 1024 -> MaterialTheme.colorScheme.primary
        else -> Color(0xFFFFB300)
    }
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(92.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Speed, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (speed > 0) formatSpeed(speed) else "—", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        }
        if (speed > 0 && bytes > 0) Text("≈ ${formatEta(bytes / speed)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

private fun formatSpeed(bytesPerSec: Long) =
    if (bytesPerSec >= 1L shl 20) "%.1f MB/s".format(bytesPerSec / (1L shl 20).toDouble()) else "${bytesPerSec / 1024} KB/s"
