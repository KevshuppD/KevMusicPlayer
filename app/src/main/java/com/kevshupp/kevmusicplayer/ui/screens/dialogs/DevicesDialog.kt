package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.AudioOutputs
import com.kevshupp.kevmusicplayer.data.cast.CastController
import com.kevshupp.kevmusicplayer.data.remote.RemoteControlServer

/**
 * Dónde suena la música, en una sola hoja (como el selector de Spotify): arriba lo que suena ahora con su
 * volumen; debajo las salidas de este teléfono (altavoz, Bluetooth, cable; `AudioOutputs`) y los otros
 * dispositivos: PCs con KevMusic Desktop (con KevMusic Connect activo, protocolo 7) y TVs por Chromecast o DLNA
 * (antes en un diálogo aparte, "Transmitir"). Al final, el acceso a Escuchar juntos.
 *
 * [queue] da la cola desde la canción actual y su posición, para enviarla a la TV.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesDialog(
    castEnabled: Boolean,
    togetherVisible: Boolean,
    queue: () -> Pair<List<AudioFile>, Long>,
    onCastStarted: () -> Unit,
    onOpenTogether: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val es = remember { context.resources.configuration.locales[0].language == "es" }
    fun t(a: String, b: String) = if (es) a else b

    LaunchedEffect(Unit) { AudioOutputs.start(context) }
    val outputs by AudioOutputs.outputs.collectAsState()
    val connectOn = remember { RemoteControlServer.isEnabled(context) }
    val pcs by RemoteControlServer.handoffTargets.collectAsState()
    val pcPlayback by RemoteControlServer.pcPlayback.collectAsState()
    val pc = pcPlayback
    val cast by CastController.state.collectAsState()
    val tvs by CastController.devices.collectAsState()
    var castAvailable by remember { mutableStateOf(true) }
    DisposableEffect(castEnabled) {
        if (castEnabled) castAvailable = CastController.startDiscovery(context)
        onDispose { if (castEnabled) CastController.stopDiscovery() }
    }
    val casting = cast.device != null
    val onPhone = !casting && pc == null
    val currentOutput = outputs.firstOrNull { it.current }

    // La hoja es otra ventana: los botones de volumen le llegan a ella y no a MainActivity
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .focusRequester(focus).focusable().onPreviewKeyEvent { event ->
                    val up = event.key == Key.VolumeUp
                    if (!up && event.key != Key.VolumeDown || !CastController.isCasting) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) CastController.adjustVolume(up)
                    true
                }
                .padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Lo que suena ahora
            NowCard(
                icon = when {
                    casting -> Icons.Rounded.Tv
                    pc != null -> Icons.Rounded.Computer
                    else -> outputIcon(currentOutput?.kind)
                },
                label = when {
                    cast.connecting -> t("Conectando", "Connecting")
                    casting -> t("Transmitiendo en", "Casting to")
                    else -> t("Sonando en", "Playing on")
                },
                name = when {
                    casting -> cast.device.orEmpty()
                    pc != null -> pc.pc
                    else -> currentOutput?.let { outputName(it, ::t) } ?: t("Este teléfono", "This phone")
                },
                detail = when {
                    casting -> cast.title.takeIf { it.isNotBlank() }
                    pc != null -> pc.title.takeIf { it.isNotBlank() }
                    else -> null
                }
            ) {
                when {
                    casting -> {
                        CastVolumeSlider(t("Volumen de la TV", "TV volume"))
                        Text(
                            if (CastController.supportsLyrics) t("El ecualizador y el crossfade no se aplican al transmitir.", "Equalizer and crossfade don't apply while casting.")
                            else t("El ecualizador, el crossfade y la letra en pantalla no se aplican al transmitir.", "Equalizer, crossfade and on-screen lyrics don't apply while casting."),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = { CastController.disconnect() }, modifier = Modifier.fillMaxWidth()) {
                            Text(t("Dejar de transmitir", "Stop casting"))
                        }
                    }
                    pc == null -> PhoneVolumeSlider(context, t("Volumen", "Volume"))
                }
            }
            cast.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

            // Salidas de este teléfono
            Column {
                SheetLabel(t("Este teléfono", "This phone"))
                if (outputs.isEmpty()) {
                    DeviceRow(Icons.Rounded.PhoneAndroid, t("Este teléfono", "This phone"), null, onPhone) {
                        bringHere(casting, pc?.pc)
                        onDismiss()
                    }
                }
                outputs.forEach { out ->
                    val here = onPhone && out.current
                    DeviceRow(
                        outputIcon(out.kind), outputName(out, ::t),
                        if (out.kind == AudioOutputs.Kind.BLUETOOTH) "Bluetooth" else null,
                        here
                    ) {
                        AudioOutputs.select(out.id)
                        bringHere(casting, pc?.pc)
                        onDismiss()
                    }
                }
            }

            // PCs y TVs
            val showPcs = connectOn || pc != null
            if (showPcs || castEnabled) Column {
                SheetLabel(t("Otros dispositivos", "Other devices"))
                if (showPcs) pcs.forEach { name ->
                    val here = pc?.pc == name
                    DeviceRow(Icons.Rounded.Computer, name, "KevMusic Desktop", here) {
                        if (!here) {
                            if (casting) CastController.disconnect()
                            RemoteControlServer.handoffRequests.tryEmit(name)
                        }
                        onDismiss()
                    }
                }
                if (castEnabled) tvs.forEach { tv ->
                    val here = cast.device == tv.name
                    val kind = if (tv.dlna != null) "DLNA" else "Chromecast"
                    DeviceRow(
                        if (tv.dlna != null) Icons.Rounded.Tv else Icons.Rounded.Cast, tv.name,
                        if (tv.id == CastController.lastDeviceId() && !here) "TV · $kind · " + t("Último usado", "Last used") else "TV · $kind",
                        here
                    ) {
                        if (!here && !cast.connecting) {
                            val (songs, position) = queue()
                            CastController.castTo(tv, songs, position)
                            onCastStarted()
                        }
                    }
                }
                when {
                    castEnabled && !castAvailable ->
                        SheetHint(t("No se pudo iniciar la búsqueda de TVs.", "Couldn't start looking for TVs."))
                    castEnabled && tvs.isEmpty() -> Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(t("Buscando TVs en tu Wi-Fi…", "Looking for TVs on your Wi-Fi…"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    showPcs && pcs.isEmpty() && !castEnabled ->
                        SheetHint(t("Abre KevMusic Desktop en tu PC para que aparezca aquí.", "Open KevMusic Desktop on your PC to see it here."))
                }
            }

            if (togetherVisible) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f))
                        .clickable { onOpenTogether() }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Groups, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t("Escuchar juntos", "Listen together"), fontWeight = FontWeight.Medium)
                        Text(t("Con otro teléfono, cerca o a distancia", "With another phone, nearby or far apart"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Devuelve la música al teléfono si sonaba en una TV o en un PC. */
private fun bringHere(casting: Boolean, pc: String?) {
    if (casting) CastController.disconnect()
    pc?.let { RemoteControlServer.handoffBackRequests.tryEmit(it) }
}

private fun outputIcon(kind: AudioOutputs.Kind?): ImageVector = when (kind) {
    AudioOutputs.Kind.BLUETOOTH -> Icons.Rounded.Bluetooth
    AudioOutputs.Kind.WIRED -> Icons.Rounded.Headphones
    AudioOutputs.Kind.USB -> Icons.Rounded.Usb
    else -> Icons.Rounded.PhoneAndroid
}

private fun outputName(out: AudioOutputs.Output, t: (String, String) -> String): String = when (out.kind) {
    AudioOutputs.Kind.SPEAKER -> t("Altavoz del teléfono", "Phone speaker")
    AudioOutputs.Kind.BLUETOOTH -> out.name.ifBlank { "Bluetooth" }
    AudioOutputs.Kind.WIRED -> t("Audífonos con cable", "Wired headphones")
    AudioOutputs.Kind.USB -> out.name.ifBlank { "USB" }
}

/** Tarjeta de cabecera de las hojas del reproductor: icono en círculo, etiqueta, nombre y contenido propio. */
@Composable
internal fun NowCard(
    icon: ImageVector,
    label: String,
    name: String,
    detail: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(accent.copy(alpha = 0.13f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp, color = accent)
                Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        content()
    }
}

@Composable
internal fun SheetLabel(text: String) {
    Text(
        text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun SheetHint(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp))
}

/** Fila de la hoja: sin tarjeta; la actual va en el color principal con una marca. */
@Composable
internal fun DeviceRow(
    icon: ImageVector,
    name: String,
    detail: String?,
    current: Boolean,
    trailing: ImageVector? = null,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (current) primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name, fontSize = 16.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = if (current) primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            detail?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        }
        when {
            current -> Icon(Icons.Rounded.Check, null, tint = primary, modifier = Modifier.size(22.dp))
            trailing != null -> Icon(trailing, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** Volumen multimedia del teléfono; sigue a los botones físicos. */
@Composable
private fun PhoneVolumeSlider(context: Context, description: String) {
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
            kotlinx.coroutines.delay(300)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Slider(
            value = volume,
            onValueChange = {
                dragging = true
                volume = it
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * max).toInt(), 0)
            },
            onValueChangeFinished = { dragging = false }
        )
    }
}

/** Volumen de la TV; sigue a los botones físicos, al mando de la tele y al valor inicial, que llega por la red. */
@Composable
private fun CastVolumeSlider(description: String) {
    var volume by remember { mutableFloatStateOf(CastController.volume()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) volume = CastController.volume()
            kotlinx.coroutines.delay(200)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Slider(value = volume, onValueChange = { dragging = true; volume = it; CastController.setVolume(it) }, onValueChangeFinished = { dragging = false })
    }
}
