package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.telegram.*
import kotlinx.coroutines.launch

/** Buscador de canciones en Deezer que las descarga a la biblioteca a través del bot de Telegram. */
@Composable
fun TelegramDownloadDialog(
    librarySongs: List<AudioFile>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TelegramMusicClient.start(context) }
    val authState by TelegramMusicClient.authState.collectAsState()
    val error by TelegramMusicClient.lastError.collectAsState()
    val bot by TelegramMusicClient.bot.collectAsState()
    var showBotSettings by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CloudDownload, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Descargar música", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (authState == TelegramAuthState.Ready) {
                        IconButton(onClick = { showBotSettings = true }) { Icon(Icons.Rounded.Tune, "Bot") }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cerrar") }
                }
                Spacer(Modifier.height(8.dp))
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                when (val s = authState) {
                    TelegramAuthState.NotConfigured -> CenterMessage("Esta compilación no tiene credenciales de la API de Telegram (TELEGRAM_API_ID / TELEGRAM_API_HASH).")
                    TelegramAuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    TelegramAuthState.WaitPhone -> AuthStep(
                        step = 1,
                        title = "Teléfono",
                        intro = "Inicia sesión con tu cuenta de Telegram. La app le pedirá las canciones al bot en tu nombre; la sesión se guarda solo en este teléfono.",
                        label = "Número con prefijo (+56…)",
                        keyboardType = KeyboardType.Phone,
                        onSubmit = TelegramMusicClient::submitPhone
                    )
                    is TelegramAuthState.WaitCode -> AuthStep(
                        step = 2,
                        title = "Código",
                        intro = if (s.sentVia == "la app de Telegram") "Te llegó un código en la app de Telegram (chat \"Telegram\") para ${s.phone}."
                        else "Te enviamos el código por ${s.sentVia} a ${s.phone}.",
                        label = "Código",
                        keyboardType = KeyboardType.NumberPassword,
                        onSubmit = TelegramMusicClient::submitCode,
                        extra = { ResendCodeButton(s) }
                    )
                    is TelegramAuthState.WaitPassword -> AuthStep(
                        step = 3,
                        title = "Contraseña",
                        intro = "Tu cuenta tiene verificación en dos pasos." + if (s.hint.isNotBlank()) " Pista: ${s.hint}" else "",
                        label = "Contraseña",
                        keyboardType = KeyboardType.Password,
                        isPassword = true,
                        onSubmit = TelegramMusicClient::submitPassword
                    )
                    is TelegramAuthState.Unsupported -> CenterMessage("Paso de inicio de sesión no soportado (${s.what}). Termina el inicio de sesión desde la app de Telegram.")
                    TelegramAuthState.Ready -> {
                        if (bot.isBlank()) {
                            BotUsernameForm(showSteps = true, onSaved = {})
                        } else {
                            SearchContent(librarySongs)
                        }
                    }
                }
            }
        }
    }

    if (showBotSettings) {
        AlertDialog(
            onDismissRequest = { showBotSettings = false },
            title = { Text("Bot de descargas") },
            text = { BotUsernameForm(showSteps = false, onSaved = { showBotSettings = false }) },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { TelegramMusicClient.logOut(); showBotSettings = false }) {
                    Text("Cerrar sesión de Telegram", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StepHeader(step: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
        (1..4).forEach { i ->
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .height(4.dp)
                    .width(if (i == step) 28.dp else 14.dp)
                    .clip(CircleShape)
                    .background(
                        if (i <= step) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("Paso $step de 4 · $title", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SubmitButton(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(text)
        }
    }
}

@Composable
private fun AuthStep(
    step: Int,
    title: String,
    intro: String,
    label: String,
    keyboardType: KeyboardType,
    isPassword: Boolean = false,
    onSubmit: (String) -> Unit,
    extra: @Composable () -> Unit = {}
) {
    val busy by TelegramMusicClient.busy.collectAsState()
    var value by remember(label) { mutableStateOf("") }
    val submit = { if (value.isNotBlank() && !busy) onSubmit(value) }
    Column(Modifier.fillMaxWidth()) {
        StepHeader(step, title)
        Spacer(Modifier.height(12.dp))
        Text(intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(label) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        SubmitButton("Continuar", value.isNotBlank(), busy, submit)
        extra()
    }
}

/** "Enviar por SMS" (o llamada): solo si Telegram ofrece otro método, tras la cuenta atrás que exige. */
@Composable
private fun ResendCodeButton(state: TelegramAuthState.WaitCode) {
    val next = state.nextVia ?: return
    val busy by TelegramMusicClient.busy.collectAsState()
    var remaining by remember(state) { mutableIntStateOf(state.timeoutSec) }
    LaunchedEffect(state) {
        while (remaining > 0) {
            kotlinx.coroutines.delay(1000)
            remaining--
        }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = { TelegramMusicClient.resendCode() },
        enabled = remaining == 0 && !busy,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (remaining > 0) "Enviar por $next en ${remaining} s" else "Enviar el código por $next")
    }
}

@Composable
private fun BotUsernameForm(showSteps: Boolean, onSaved: () -> Unit) {
    val context = LocalContext.current
    val busy by TelegramMusicClient.busy.collectAsState()
    var value by remember { mutableStateOf(TelegramMusicClient.botUsername(context)) }
    val submit = { if (value.isNotBlank() && !busy) TelegramMusicClient.setBotUsername(value, onSaved) }
    Column(Modifier.fillMaxWidth()) {
        if (showSteps) {
            StepHeader(4, "Bot")
            Spacer(Modifier.height(12.dp))
        }
        Text(
            "Escribe el @usuario del bot (o pega su enlace t.me). Lo verás en su perfil de Telegram; la app le enviará los enlaces de Deezer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("Usuario del bot") },
            prefix = { Text("@") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        SubmitButton("Verificar y guardar", value.isNotBlank(), busy, submit)
    }
}

private enum class SearchMode(val label: String) { Tracks("Canciones"), Albums("Álbumes"), Artists("Artistas") }

private sealed interface SearchDetail {
    data class Album(val album: DeezerAlbum) : SearchDetail
    data class Artist(val artist: DeezerArtist) : SearchDetail
}

@Composable
private fun SearchContent(librarySongs: List<AudioFile>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(SearchMode.Tracks) }
    var tracks by remember { mutableStateOf<List<DeezerTrack>>(emptyList()) }
    var albums by remember { mutableStateOf<List<DeezerAlbum>>(emptyList()) }
    var artists by remember { mutableStateOf<List<DeezerArtist>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchedFor by remember { mutableStateOf<Pair<String, SearchMode>?>(null) }
    val details = remember { mutableStateListOf<SearchDetail>() }
    val statuses by TelegramDownloadQueue.statuses.collectAsState()

    val downloads by TelegramDownloadQueue.downloads.collectAsState()
    var showQueue by rememberSaveable { mutableStateOf(false) }

    // Qué resultados ya están en la biblioteca (comparación tolerante a nombres distintos)
    val matcher = remember(librarySongs) { LibraryMatcher(librarySongs) }
    // Primero los artistas que ya escuchas, luego los que tienes y al final el resto
    val affinity = remember(librarySongs) { ArtistAffinity(librarySongs) }

    fun runSearch() {
        val q = query.trim()
        if (q.isBlank()) return
        keyboard?.hide()
        details.clear()
        loading = true
        searchError = null
        val m = mode
        scope.launch {
            try {
                when (m) {
                    SearchMode.Tracks -> tracks = affinity.rank(DeezerTrackSearch.search(q)) { it.artist }
                    SearchMode.Albums -> albums = affinity.rank(DeezerTrackSearch.searchAlbums(q)) { it.artist }
                    SearchMode.Artists -> artists = affinity.rank(DeezerTrackSearch.searchArtists(q)) { it.name }
                }
                searchedFor = q to m
            } catch (e: Exception) {
                searchError = "No se pudo buscar: ${e.message}"
            }
            loading = false
        }
    }

    // Cambiar de filtro repite la búsqueda actual en el nuevo tipo
    LaunchedEffect(mode) {
        if (query.isNotBlank() && searchedFor?.let { it.first == query.trim() && it.second == mode } != true) runSearch()
    }

    androidx.activity.compose.BackHandler(enabled = details.isNotEmpty()) { details.removeAt(details.lastIndex) }

    val enqueue: (DeezerTrack) -> Unit = { TelegramDownloadQueue.enqueue(context, it) }

    Column(Modifier.fillMaxSize()) {
        val active = downloads.count { statuses[it.id].let { st -> st == DownloadStatus.Queued || st == DownloadStatus.Requesting || st is DownloadStatus.Downloading } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            FilterChip(
                selected = !showQueue,
                onClick = { showQueue = false },
                label = { Text("Buscar") },
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                shape = RoundedCornerShape(12.dp)
            )
            FilterChip(
                selected = showQueue,
                onClick = { showQueue = true },
                label = { Text(if (active > 0) "Cola · $active" else "Cola") },
                leadingIcon = {
                    if (active > 0) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                },
                shape = RoundedCornerShape(12.dp)
            )
        }
        if (showQueue) {
            QueueContent(downloads, statuses, matcher, enqueue)
            return@Column
        }

        val detail = details.lastOrNull()
        if (detail != null) {
            when (detail) {
                is SearchDetail.Album -> AlbumDetail(detail.album, matcher, statuses, enqueue, onBack = { details.removeAt(details.lastIndex) })
                is SearchDetail.Artist -> ArtistDetail(
                    detail.artist, matcher, statuses, enqueue,
                    onOpenAlbum = { details.add(SearchDetail.Album(it)) },
                    onBack = { details.removeAt(details.lastIndex) }
                )
            }
            return@Column
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Canción, artista o álbum") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { mode = m },
                    label = { Text(m.label) },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            when (mode) {
                SearchMode.Tracks -> items(tracks, key = { it.id }) { track ->
                    TrackRow(track, statuses[track.id], matcher.hasTrack(track)) { enqueue(track) }
                }
                SearchMode.Albums -> items(albums, key = { it.id }) { album ->
                    EntityRow(
                        image = album.cover,
                        title = album.title,
                        subtitle = listOf(album.artist, album.year, "${album.trackCount} canciones").filter { it.isNotBlank() && it != "0 canciones" }.joinToString(" · "),
                        circle = false,
                        owned = matcher.albumSongCount(album)
                    ) { details.add(SearchDetail.Album(album)) }
                }
                SearchMode.Artists -> items(artists, key = { it.id }) { artist ->
                    EntityRow(
                        image = artist.picture,
                        title = artist.name,
                        subtitle = "${artist.albumCount} álbumes · ${artist.fans} fans",
                        circle = true
                    ) { details.add(SearchDetail.Artist(artist)) }
                }
            }
        }
    }
}

@Composable
private fun EntityRow(image: String, title: String, subtitle: String, circle: Boolean, owned: Int = 0, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(if (circle) CircleShape else RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (owned > 0) {
                Text(
                    if (owned == 1) "Tienes 1 canción" else "Tienes $owned canciones",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailHeader(image: String, title: String, subtitle: String, circle: Boolean, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver") }
        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(if (circle) CircleShape else RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Descargar todo": solo las que faltan en la biblioteca y no están ya en cola o descargadas. */
@Composable
private fun DownloadAllButton(tracks: List<DeezerTrack>, matcher: LibraryMatcher, statuses: Map<Long, DownloadStatus>, enqueue: (DeezerTrack) -> Unit) {
    val missing = tracks.filter { t ->
        !matcher.hasTrack(t) && (statuses[t.id] == null || statuses[t.id] is DownloadStatus.Failed)
    }
    Button(
        onClick = { missing.forEach(enqueue) },
        enabled = missing.isNotEmpty(),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
    ) {
        Icon(Icons.Rounded.Download, null)
        Spacer(Modifier.width(8.dp))
        Text(if (missing.isEmpty()) "Todo descargado o en cola" else "Descargar ${missing.size} canciones")
    }
}

@Composable
private fun AlbumDetail(
    album: DeezerAlbum,
    matcher: LibraryMatcher,
    statuses: Map<Long, DownloadStatus>,
    enqueue: (DeezerTrack) -> Unit,
    onBack: () -> Unit
) {
    var tracks by remember(album.id) { mutableStateOf<List<DeezerTrack>?>(null) }
    var error by remember(album.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(album.id) {
        try { tracks = DeezerTrackSearch.albumTracks(album) } catch (e: Exception) { error = e.message ?: "Error" }
    }
    Column(Modifier.fillMaxSize()) {
        DetailHeader(album.cover, album.title, listOf(album.artist, album.year).filter { it.isNotBlank() }.joinToString(" · "), circle = false, onBack = onBack)
        val list = tracks
        when {
            error != null -> Text("No se pudo cargar el álbum: $error", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                DownloadAllButton(list, matcher, statuses, enqueue)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { track ->
                        TrackRow(track, statuses[track.id], matcher.hasTrack(track)) { enqueue(track) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistDetail(
    artist: DeezerArtist,
    matcher: LibraryMatcher,
    statuses: Map<Long, DownloadStatus>,
    enqueue: (DeezerTrack) -> Unit,
    onOpenAlbum: (DeezerAlbum) -> Unit,
    onBack: () -> Unit
) {
    var top by remember(artist.id) { mutableStateOf<List<DeezerTrack>?>(null) }
    var albums by remember(artist.id) { mutableStateOf<List<DeezerAlbum>>(emptyList()) }
    var error by remember(artist.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(artist.id) {
        try {
            top = DeezerTrackSearch.artistTopTracks(artist.id)
            albums = DeezerTrackSearch.artistAlbums(artist.id, artist.name)
        } catch (e: Exception) {
            error = e.message ?: "Error"
        }
    }
    Column(Modifier.fillMaxSize()) {
        DetailHeader(artist.picture, artist.name, "${artist.albumCount} álbumes · ${artist.fans} fans", circle = true, onBack = onBack)
        val list = top
        when {
            error != null -> Text("No se pudo cargar el artista: $error", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                item { SectionTitle("Populares") }
                item { DownloadAllButton(list, matcher, statuses, enqueue) }
                items(list, key = { "t${it.id}" }) { track ->
                    TrackRow(track, statuses[track.id], matcher.hasTrack(track)) { enqueue(track) }
                }
                if (albums.isNotEmpty()) {
                    item { SectionTitle("Álbumes y sencillos") }
                    items(albums, key = { "a${it.id}" }) { album ->
                        EntityRow(
                            image = album.cover,
                            title = album.title,
                            subtitle = listOf(album.year, album.recordType.replaceFirstChar { it.uppercase() }).filter { it.isNotBlank() }.joinToString(" · "),
                            circle = false,
                            owned = matcher.albumSongCount(album)
                        ) { onOpenAlbum(album) }
                    }
                }
            }
        }
    }
}

/** Pestaña "Cola": lo que se está descargando, lo que espera y cómo terminó cada descarga. */
@Composable
private fun QueueContent(
    downloads: List<DeezerTrack>,
    statuses: Map<Long, DownloadStatus>,
    matcher: LibraryMatcher,
    enqueue: (DeezerTrack) -> Unit
) {
    if (downloads.isEmpty()) {
        CenterMessage("No hay descargas. Busca una canción, álbum o artista y pulsa descargar: aquí verás su progreso.")
        return
    }
    fun order(status: DownloadStatus?) = when (status) {
        DownloadStatus.Requesting, is DownloadStatus.Downloading -> 0
        DownloadStatus.Queued, null -> 1
        is DownloadStatus.Failed -> 2
        is DownloadStatus.Done -> 3
    }
    val sorted = downloads.sortedBy { order(statuses[it.id]) }
    val working = downloads.count { order(statuses[it.id]) == 0 }
    val waiting = downloads.count { order(statuses[it.id]) == 1 }
    val failed = downloads.count { statuses[it.id] is DownloadStatus.Failed }
    val done = downloads.count { statuses[it.id] is DownloadStatus.Done }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOfNotNull(
                "$working descargando".takeIf { working > 0 },
                "$waiting en espera".takeIf { waiting > 0 },
                (if (done == 1) "1 lista" else "$done listas").takeIf { done > 0 },
                (if (failed == 1) "1 fallida" else "$failed fallidas").takeIf { failed > 0 }
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (done + failed > 0) {
            TextButton(onClick = { TelegramDownloadQueue.clearFinished() }) { Text("Limpiar terminadas") }
        }
    }
    if (failed > 0) {
        TextButton(onClick = { downloads.filter { statuses[it.id] is DownloadStatus.Failed }.forEach(enqueue) }) {
            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Reintentar las fallidas")
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        items(sorted, key = { it.id }) { track ->
            val status = statuses[track.id]
            TrackRow(track, status, status !is DownloadStatus.Done && matcher.hasTrack(track)) { enqueue(track) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun TrackRow(track: DeezerTrack, status: DownloadStatus?, inLibrary: Boolean, onDownload: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(10.dp)
    ) {
        AsyncImage(
            model = track.coverSmall,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${track.artist} · ${track.album} · ${track.durationSec / 60}:${"%02d".format(track.durationSec % 60)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            when (status) {
                is DownloadStatus.Failed -> Text(status.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                DownloadStatus.Queued -> Text("En cola", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                DownloadStatus.Requesting -> Text("Pidiendo al bot…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                is DownloadStatus.Downloading -> LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                is DownloadStatus.Done -> Text("Guardada en la biblioteca", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                null -> if (inLibrary) Text("Ya está en tu biblioteca", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        val busy = status is DownloadStatus.Queued || status is DownloadStatus.Requesting || status is DownloadStatus.Downloading
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (status is DownloadStatus.Done) 0.2f else 0.9f))
                .clickable(enabled = !busy && status !is DownloadStatus.Done, onClick = onDownload)
        ) {
            when {
                busy -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                status is DownloadStatus.Done -> Icon(Icons.Rounded.Check, "Descargada", tint = MaterialTheme.colorScheme.primary)
                status is DownloadStatus.Failed -> Icon(Icons.Rounded.Refresh, "Reintentar", tint = MaterialTheme.colorScheme.onPrimary)
                else -> Icon(Icons.Rounded.Download, "Descargar", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
