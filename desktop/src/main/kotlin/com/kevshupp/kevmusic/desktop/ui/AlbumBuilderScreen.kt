package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusic.desktop.*
import kotlinx.coroutines.launch

private data class Slot(val title: String, val lengthSec: Int, val source: ScCandidate? = null, val searching: Boolean = false, val searched: Boolean = false)

private data class AlbumDraft(val artist: String, val album: String, val year: String, val cover: String, val group: MbGroup?)

private fun time(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

/** Arma un disco que Deezer no tiene: datos de MusicBrainz (o a mano) y audio de SoundCloud, revisado pista a pista. */
@Composable
fun AlbumBuilderScreen() {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var artists by remember { mutableStateOf(listOf<MbArtist>()) }
    var artist by remember { mutableStateOf<MbArtist?>(null) }
    var groups by remember { mutableStateOf(listOf<MbGroup>()) }
    var draft by remember { mutableStateOf<AlbumDraft?>(null) }

    fun guarded(block: suspend () -> Unit) {
        loading = true
        error = null
        scope.launch {
            try { block() } catch (e: Exception) { error = e.message ?: "Error" }
            loading = false
        }
    }

    draft?.let { d ->
        AlbumEditor(d) { draft = null }
        return
    }

    Column(Modifier.fillMaxSize()) {
        val current = artist
        if (current == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val go = { if (query.isNotBlank()) guarded { artists = MusicBrainz.searchArtists(query.trim()) } }
                SearchField(query, { query = it }, "Nombre del artista y pulsa Enter", Modifier.weight(1f).onPreviewKeyEvent { if (it.key == Key.Enter) { go(); true } else false })
                Spacer(Modifier.width(10.dp))
                Button(onClick = { go() }, enabled = query.isNotBlank(), shape = RoundedCornerShape(14.dp)) { Text("Buscar") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { draft = AlbumDraft(query.trim(), "", "", "", null) }, shape = RoundedCornerShape(14.dp)) { Text("Crear disco a mano") }
            }
            Spacer(Modifier.height(10.dp))
        } else {
            TextButton(onClick = { artist = null; groups = emptyList() }) { Text("← Volver") }
            Text(current.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (current == null) items(artists, key = { it.id }) { a ->
                Column(Modifier.fillMaxWidth().clickable { artist = a; guarded { groups = MusicBrainz.releaseGroups(a.id) } }.padding(vertical = 8.dp)) {
                    Text(a.name, fontWeight = FontWeight.Medium)
                    if (a.note.isNotBlank()) Text(a.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else items(groups, key = { it.id }) { g ->
                Row(Modifier.fillMaxWidth().clickable { draft = AlbumDraft(current.name, g.title, g.year, g.coverUrl, g) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    UrlImage(g.coverUrl.replace("front-500", "front-250"), 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(g.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(listOf(g.type, g.year).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumEditor(initial: AlbumDraft, onBack: () -> Unit) {
    val client = LocalClient.current
    val library by client.library.collectAsState()
    val jobs by YtMusic.jobs.collectAsState()
    val scope = rememberCoroutineScope()
    var artist by remember { mutableStateOf(initial.artist) }
    var album by remember { mutableStateOf(initial.album) }
    var year by remember { mutableStateOf(initial.year) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(initial.group != null) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val slots = remember { mutableStateListOf<Slot>() }
    val jobById = jobs.associateBy { it.track.id }
    val owned = remember(library) { library?.songs.orEmpty().mapTo(HashSet()) { ownedKey(it.title, it.artist) } }

    /** Propone una subida para la pista; si no aparece por su título, prueba con el nombre del disco. */
    suspend fun findSource(i: Int) {
        val s = slots.getOrNull(i) ?: return
        slots[i] = s.copy(searching = true)
        val found = runCatching {
            YtMusic.pickCandidate(s.title, artist, s.lengthSec, YtMusic.searchSoundCloud("$artist ${s.title}"))
                ?: if (s.lengthSec > 0) YtMusic.pickCandidate(s.title, artist, s.lengthSec, YtMusic.searchSoundCloud("$artist $album", 20)) else null
        }.getOrNull()
        slots.getOrNull(i)?.let { slots[i] = it.copy(source = it.source ?: found, searching = false, searched = true) }
    }

    LaunchedEffect(initial.group?.id) {
        val g = initial.group ?: return@LaunchedEffect
        try {
            val release = MusicBrainz.bestRelease(g.id)
            if (release == null) error = "MusicBrainz no tiene las pistas de este disco: añádelas a mano."
            else slots.addAll(release.tracks.map { Slot(it.title, it.lengthSec) })
        } catch (e: Exception) {
            error = e.message ?: "Error"
        }
        loading = false
        for (i in slots.indices) findSource(i)
    }

    fun trackOf(i: Int, s: Slot): YtTrack? = s.source?.let { c ->
        YtTrack(c.id, s.title.trim(), artist.trim(), album.trim(), c.durationSec, initial.cover, year.trim(), i + 1, c.url, true, c.artwork)
    }
    val ready = slots.mapIndexedNotNull { i, s -> trackOf(i, s) }.filter { it.id !in jobById || jobById[it.id]?.stage == YtStage.Failed }

    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← Volver") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (initial.cover.isNotBlank()) UrlImage(initial.cover.replace("front-500", "front-250"), 72.dp, corner = 12.dp)
            OutlinedTextField(artist, { artist = it }, label = { Text("Artista") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(album, { album = it }, label = { Text("Álbum") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(year, { y -> year = y.filter(Char::isDigit).take(4) }, label = { Text("Año") }, singleLine = true, modifier = Modifier.width(90.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Son subidas de usuarios de SoundCloud (AAC de 96 a 160 kbps): revisa cada una antes de descargar. Puedes corregir los títulos.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { ready.forEach { YtMusic.enqueue(it, client) } },
                enabled = ready.isNotEmpty() && artist.isNotBlank() && album.isNotBlank(), shape = RoundedCornerShape(14.dp)
            ) { Text("Descargar (${ready.size})") }
            OutlinedButton(onClick = { slots.add(Slot("", 0)); picking = slots.lastIndex }, shape = RoundedCornerShape(14.dp)) { Text("Añadir canción") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(slots) { i, s ->
                SlotRow(
                    i, s, s.source?.let { jobById[it.id] }, ownedKey(s.title, artist) in owned, picking == i,
                    onTitle = { slots[i] = slots[i].copy(title = it) },
                    onPick = { picking = if (picking == i) null else i },
                    onChosen = { c -> slots[i] = slots[i].copy(source = c, searched = true); picking = null },
                    onClear = { slots[i] = slots[i].copy(source = null) },
                    onRemove = { slots.removeAt(i); picking = null },
                    defaultQuery = "$artist ${s.title}".trim()
                )
            }
        }
    }
}

@Composable
private fun SlotRow(
    index: Int, slot: Slot, job: YtJob?, have: Boolean, open: Boolean,
    onTitle: (String) -> Unit, onPick: () -> Unit, onChosen: (ScCandidate) -> Unit, onClear: () -> Unit, onRemove: () -> Unit, defaultQuery: String
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}", Modifier.width(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(slot.title, onTitle, singleLine = true, label = { Text(if (slot.lengthSec > 0) "Título · ${time(slot.lengthSec)}" else "Título") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1.2f)) {
                val c = slot.source
                when {
                    c != null -> {
                        Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                        Text("${c.uploader} · ${time(c.durationSec)}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    slot.searching -> Text("Buscando en SoundCloud…", fontSize = 12.sp)
                    slot.searched -> Text("Sin subida segura: elige una", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                when {
                    job != null -> Text(jobText(job), fontSize = 12.sp, maxLines = 2, color = if (job.stage == YtStage.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    have -> Text("La tienes", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            TextButton(onClick = onPick) { Text(if (open) "Cerrar" else "Elegir") }
            if (slot.source != null) IconButton(onClick = onClear) { Icon(Icons.Rounded.LinkOff, "Quitar la subida") }
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Quitar la pista") }
        }
        if (open) SourcePicker(defaultQuery, slot.lengthSec, onChosen)
    }
}

private fun jobText(j: YtJob) = when (j.stage) {
    YtStage.Queued -> "En espera"
    YtStage.Installing -> "Instalando ffmpeg, solo esta vez · ${j.percent} %"
    YtStage.Downloading -> "Descargando · ${j.percent} %"
    YtStage.Tagging -> "Poniendo etiquetas y carátula"
    YtStage.WaitingPhone -> j.message
    YtStage.Sending -> "Enviando al teléfono · ${j.percent} %"
    YtStage.Done -> "En el teléfono"
    YtStage.Failed -> "Falló: ${j.message}"
}

@Composable
private fun SourcePicker(defaultQuery: String, lengthSec: Int, onChosen: (ScCandidate) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(defaultQuery) }
    var results by remember { mutableStateOf(listOf<ScCandidate>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun go() {
        if (query.isBlank()) return
        loading = true
        error = null
        scope.launch {
            try { results = YtMusic.searchSoundCloud(query.trim(), 15) } catch (e: Exception) { error = e.message ?: "No se pudo buscar" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { go() }
    Column(Modifier.fillMaxWidth().padding(start = 28.dp, top = 4.dp, bottom = 8.dp)) {
        SearchField(query, { query = it }, "Buscar en SoundCloud y pulsa Enter", Modifier.fillMaxWidth().onPreviewKeyEvent { if (it.key == Key.Enter) { go(); true } else false })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        results.forEach { c ->
            Row(Modifier.fillMaxWidth().clickable { onChosen(c) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    Text(c.uploader, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val off = lengthSec > 0 && kotlin.math.abs(c.durationSec - lengthSec) > 5
                Text(time(c.durationSec), fontSize = 12.sp, color = if (off) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
