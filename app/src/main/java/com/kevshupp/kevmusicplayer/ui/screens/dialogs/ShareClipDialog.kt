package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.kevshupp.kevmusicplayer.data.LyricsRepository
import com.kevshupp.kevmusicplayer.playback.managers.ClipBackground
import com.kevshupp.kevmusicplayer.playback.managers.PixelArtMode
import com.kevshupp.kevmusicplayer.playback.managers.PixelScene
import com.kevshupp.kevmusicplayer.playback.managers.PixelLayout
import com.kevshupp.kevmusicplayer.playback.managers.ClipDraft
import com.kevshupp.kevmusicplayer.playback.managers.ClipEntry
import com.kevshupp.kevmusicplayer.playback.managers.ClipHistory
import com.kevshupp.kevmusicplayer.playback.managers.ClipSettings
import com.kevshupp.kevmusicplayer.playback.managers.ClipFont
import com.kevshupp.kevmusicplayer.playback.managers.ClipFormat
import com.kevshupp.kevmusicplayer.playback.managers.ClipQuality
import com.kevshupp.kevmusicplayer.playback.managers.ClipRenderer
import com.kevshupp.kevmusicplayer.playback.managers.ClipTemplateStyle
import com.kevshupp.kevmusicplayer.playback.managers.ShareClipConfig
import com.kevshupp.kevmusicplayer.playback.managers.VideoClipGenerator
import com.kevshupp.kevmusicplayer.ui.theme.PixelFontFamily
import com.kevshupp.kevmusicplayer.ui.theme.PixelPalettes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Preferencias del último video: se recuerdan entre usos (settings_prefs/clip_*). */
private const val PREF_TEMPLATE = "clip_template"
private const val PREF_FORMAT = "clip_format"
private const val PREF_QUALITY = "clip_quality"
private const val PREF_FONT = "clip_font"
private const val PREF_UPPER = "clip_uppercase"
private const val PREF_SIZE = "clip_text_scale"
private const val PREF_BG = "clip_background"
private const val PREF_WAVE = "clip_waveform"
private const val PREF_PALETTE = "clip_pixel_palette"
private const val PREF_FPS = "clip_fps"
private const val PREF_PIXEL_ART = "clip_pixel_art"
private const val PREF_PIXEL_SCENE = "clip_pixel_scene"
private const val PREF_PIXEL_LAYOUT = "clip_pixel_layout"

private inline fun <reified E : Enum<E>> enumPref(prefs: android.content.SharedPreferences, key: String, default: E): E =
    runCatching { enumValueOf<E>(prefs.getString(key, default.name) ?: default.name) }.getOrDefault(default)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ShareClipDialog(
    songTitle: String,
    artistName: String,
    albumTitle: String? = null,
    songDurationMs: Long,
    currentPositionMs: Long,
    audioUriString: String,
    albumArtBitmap: Bitmap?,
    lyrics: String?,
    translatedLyrics: Map<Long, String>? = null,
    songId: Long = 0L,
    /** Video del historial que se edita: se parte de su fragmento y su diseño. */
    initial: ClipEntry? = null,
    /** Borrador que se retoma: se sigue guardando con el mismo id. */
    draftId: String? = null,
    onOpenHistory: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE) }
    val parsedLyrics = remember(lyrics) { LyricsRepository.parseLrc(lyrics) }
    val hasTranslation = !translatedLyrics.isNullOrEmpty()

    // Al editar, se recupera la selección por letras si el fragmento empieza y acaba en líneas
    val initialStartIdx = remember { initial?.let { e -> parsedLyrics.indexOfFirst { it.timeMs == e.startMs } } ?: -1 }
    val initialEndIdx = remember {
        if (initialStartIdx < 0 || initial == null) -1
        else (initialStartIdx until parsedLyrics.size).lastOrNull { i ->
            val end = if (i + 1 < parsedLyrics.size) parsedLyrics[i + 1].timeMs else Long.MAX_VALUE
            end <= initial.endMs || i == initialStartIdx
        } ?: -1
    }
    val saved = initial?.settings

    // Selection mode: 0 = Por Letras, 1 = Manual
    var selectedTab by remember {
        mutableIntStateOf(if (parsedLyrics.isEmpty() || (initial != null && initialStartIdx < 0)) 1 else 0)
    }
    // Panel inferior: 0 = Fragmento, 1 = Diseño
    var panel by remember { mutableIntStateOf(0) }

    // Línea que suena al abrir el diálogo: solo sirve para situar la lista, no se preselecciona.
    val initialIdx = remember {
        if (parsedLyrics.isNotEmpty()) {
            parsedLyrics.indexOfLast { it.timeMs <= currentPositionMs }.coerceAtLeast(0)
        } else 0
    }
    // Selección por letras; -1 = sin selección.
    var selectedLyricStartIdx by remember { mutableIntStateOf(initialStartIdx) }
    var selectedLyricEndIdx by remember { mutableIntStateOf(if (initialStartIdx >= 0) initialEndIdx else -1) }
    val hasLyricSelection = selectedLyricStartIdx >= 0

    // Range state
    var startMs by remember {
        mutableLongStateOf(initial?.startMs ?: currentPositionMs.coerceIn(0L, max(0L, songDurationMs - 15000L)))
    }
    var endMs by remember { mutableLongStateOf(initial?.endMs ?: min(songDurationMs, startMs + 15000L)) }

    // Diseño (se recuerda el último usado)
    var templateStyle by remember {
        mutableStateOf(saved?.template ?: enumPref(prefs, PREF_TEMPLATE, if (parsedLyrics.isNotEmpty()) ClipTemplateStyle.LYRICS_STORY else ClipTemplateStyle.PLAYER_CANVAS))
    }
    var format by remember { mutableStateOf(saved?.format ?: enumPref(prefs, PREF_FORMAT, ClipFormat.STORY)) }
    var quality by remember { mutableStateOf(saved?.quality ?: enumPref(prefs, PREF_QUALITY, ClipQuality.HD)) }
    var font by remember { mutableStateOf(saved?.font ?: enumPref(prefs, PREF_FONT, ClipFont.SANS)) }
    var uppercase by remember { mutableStateOf(saved?.uppercase ?: prefs.getBoolean(PREF_UPPER, false)) }
    var textScale by remember { mutableFloatStateOf(saved?.textScale ?: prefs.getFloat(PREF_SIZE, 1f)) }
    var background by remember { mutableStateOf(saved?.background ?: enumPref(prefs, PREF_BG, ClipBackground.AMBIENT)) }
    var showWaveform by remember { mutableStateOf(saved?.waveform ?: prefs.getBoolean(PREF_WAVE, true)) }
    var pixelPaletteKey by remember { mutableStateOf(saved?.pixelPalette ?: prefs.getString(PREF_PALETTE, "pixel") ?: "pixel") }
    var fps by remember { mutableIntStateOf(saved?.fps ?: prefs.getInt(PREF_FPS, 30)) }
    var pixelArtMode by remember { mutableStateOf(saved?.pixelArtMode ?: enumPref(prefs, PREF_PIXEL_ART, PixelArtMode.PALETTE)) }
    var pixelScene by remember { mutableStateOf(saved?.pixelScene ?: enumPref(prefs, PREF_PIXEL_SCENE, PixelScene.STARS)) }
    var pixelLayout by remember { mutableStateOf(saved?.pixelLayout ?: enumPref(prefs, PREF_PIXEL_LAYOUT, PixelLayout.SCENE)) }
    var showTranslation by remember { mutableStateOf(if (saved != null) saved.translation else hasTranslation) }
    var dedicationText by remember { mutableStateOf(saved?.dedication ?: "") }
    var showDedicationDialog by remember { mutableStateOf(false) }

    LaunchedEffect(templateStyle, format, quality, font, uppercase, textScale, background, showWaveform, pixelPaletteKey, fps, pixelArtMode, pixelScene, pixelLayout) {
        prefs.edit()
            .putString(PREF_TEMPLATE, templateStyle.name).putString(PREF_FORMAT, format.name)
            .putString(PREF_QUALITY, quality.name).putString(PREF_FONT, font.name)
            .putBoolean(PREF_UPPER, uppercase).putFloat(PREF_SIZE, textScale)
            .putString(PREF_BG, background.name).putBoolean(PREF_WAVE, showWaveform)
            .putString(PREF_PALETTE, pixelPaletteKey).putInt(PREF_FPS, fps).putString(PREF_PIXEL_ART, pixelArtMode.name)
            .putString(PREF_PIXEL_SCENE, pixelScene.name).putString(PREF_PIXEL_LAYOUT, pixelLayout.name).apply()
    }

    // El video se crea en ClipHistory (fuera del diálogo): cerrar el creador o salir de la app no lo corta
    val sessionDraftId = remember { draftId ?: System.currentTimeMillis().toString() }
    val clipJob by ClipHistory.job.collectAsState()
    val myJob = clipJob?.takeIf { it.draftId == sessionDraftId }
    val otherJobRunning = clipJob?.let { it.running && it.draftId != sessionDraftId } == true
    val isGenerating = myJob?.running == true
    val generationProgress = myJob?.progress ?: 0f
    val generationStartedAt = myJob?.startedAt ?: 0L
    var generatedFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(myJob?.result) { myJob?.result?.let { generatedFile = it } }

    // Preview Player for pre-listening the snippet
    val previewPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            try {
                setMediaItem(MediaItem.fromUri(Uri.parse(audioUriString)))
                prepare()
            } catch (e: Exception) {}
        }
    }
    var isPreviewPlaying by remember { mutableStateOf(false) }
    var previewCurrentPositionMs by remember { mutableLongStateOf(startMs) }

    DisposableEffect(Unit) {
        onDispose {
            previewPlayer.release()
        }
    }

    // Preview playback loop within startMs and endMs
    LaunchedEffect(isPreviewPlaying, startMs, endMs) {
        if (isPreviewPlaying) {
            previewPlayer.seekTo(startMs)
            previewPlayer.play()
            while (isPreviewPlaying) {
                val pos = previewPlayer.currentPosition
                previewCurrentPositionMs = pos
                if (pos >= endMs || pos < startMs) {
                    previewPlayer.seekTo(startMs)
                }
                delay(40)
            }
        } else {
            previewPlayer.pause()
            previewCurrentPositionMs = startMs
        }
    }

    // Lyrics selection handler
    fun lineEndMs(index: Int): Long =
        if (index + 1 < parsedLyrics.size) parsedLyrics[index + 1].timeMs
        else min(songDurationMs, parsedLyrics[index].timeMs + 4000L)

    fun applyLyricRange() {
        startMs = parsedLyrics[selectedLyricStartIdx].timeMs
        endMs = min(lineEndMs(selectedLyricEndIdx), startMs + 60000L)
        isPreviewPlaying = false
    }

    fun clearLyricSelection() {
        selectedLyricStartIdx = -1
        selectedLyricEndIdx = -1
        isPreviewPlaying = false
    }

    // Toque sin selección: marca esa línea como inicio. Toque fuera de la selección: la amplía
    // hasta esa línea (en cualquier dirección). Toque en un extremo: lo quita (si era la única
    // línea, borra la selección). Toque en una línea intermedia: la fija como final.
    // Mantener pulsado empieza una selección nueva en esa línea.
    fun handleLyricClick(index: Int, startNew: Boolean = false) {
        val start = selectedLyricStartIdx
        val end = selectedLyricEndIdx
        when {
            startNew || start < 0 -> { selectedLyricStartIdx = index; selectedLyricEndIdx = index }
            index < start || index > end -> {
                val newStart = min(start, index)
                val newEnd = max(end, index)
                if (lineEndMs(newEnd) - parsedLyrics[newStart].timeMs <= 60000L) {
                    selectedLyricStartIdx = newStart
                    selectedLyricEndIdx = newEnd
                } else {
                    Toast.makeText(context, "El fragmento no puede superar 60 s", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            start == end -> { clearLyricSelection(); return }
            index == start -> selectedLyricStartIdx = start + 1
            index == end -> selectedLyricEndIdx = end - 1
            else -> selectedLyricEndIdx = index
        }
        applyLyricRange()
    }

    // En "Por Letras" hace falta elegir al menos una línea; el modo manual siempre tiene rango.
    val canGenerate = selectedTab != 0 || parsedLyrics.isEmpty() || hasLyricSelection

    fun currentSettings() = ClipSettings(
        templateStyle, format, quality, font, uppercase, textScale, background,
        showWaveform, pixelPaletteKey, showTranslation && hasTranslation, dedicationText, fps, pixelArtMode, pixelScene, pixelLayout
    )
    fun currentDraft() = ClipDraft(
        sessionDraftId, songId, songTitle, artistName, startMs, endMs, System.currentTimeMillis(), currentSettings()
    )
    // Al salir sin crear el video (cerrar el creador o la app), lo elegido queda en borradores
    fun saveDraftIfPending() {
        if (canGenerate && generatedFile == null && !isGenerating && songId != 0L) ClipHistory.saveDraft(context, currentDraft())
    }
    fun dismiss() {
        saveDraftIfPending()
        if (isGenerating) Toast.makeText(context, "El video se sigue creando · lo verás en Mis videos", Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { saveDraftIfPending() }

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    // Sin selección, la vista previa muestra la línea que sonaba al abrir
    val previewStart = if (canGenerate) startMs else parsedLyrics.getOrNull(initialIdx)?.timeMs ?: startMs
    val previewEnd = if (canGenerate) endMs else previewStart + 8000L

    val config = remember(
        templateStyle, format, quality, font, uppercase, textScale, background, showWaveform, pixelPaletteKey,
        showTranslation, dedicationText, previewStart, previewEnd, fps, pixelArtMode, pixelScene, pixelLayout
    ) {
        val palette = PixelPalettes.firstOrNull { it.key == pixelPaletteKey } ?: PixelPalettes.first()
        ShareClipConfig(
            songTitle = songTitle,
            artistName = artistName,
            albumTitle = albumTitle,
            albumArtBitmap = albumArtBitmap,
            startMs = previewStart,
            endMs = previewEnd,
            audioUri = Uri.parse(audioUriString),
            lyricsLines = parsedLyrics,
            translatedLyrics = translatedLyrics,
            showTranslation = showTranslation && hasTranslation,
            dedicationText = dedicationText.takeIf { it.isNotBlank() },
            format = format,
            quality = quality,
            templateStyle = templateStyle,
            background = background,
            font = font,
            uppercase = uppercase,
            textScale = textScale,
            showWaveform = showWaveform,
            fps = fps,
            pixelArtMode = pixelArtMode,
            pixelScene = pixelScene,
            pixelLayout = pixelLayout,
            primaryColor = primaryColor.toArgb(),
            accentColor = secondaryColor.toArgb(),
            pixelColors = palette.scheme.let {
                intArrayOf(it.background.toArgb(), it.surface.toArgb(), it.primary.toArgb(), it.secondary.toArgb(), it.onBackground.toArgb())
            }
        )
    }

    // Vista previa real: cuadros del mismo ClipRenderer que el video, a media resolución. Dos bitmaps
    // alternos para no dibujar sobre el que se está mostrando.
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(config) {
        val renderer = withContext(Dispatchers.Default) { ClipRenderer(context, config) }
        // Pixel art a su resolución real de píxeles (la imagen se amplía sin filtro); el resto a media resolución
        val div = if (config.templateStyle == ClipTemplateStyle.PIXEL_ART) ClipRenderer.PX else 2f
        val pw = (renderer.width / div).toInt()
        val ph = (renderer.height / div).toInt()
        val buffers = arrayOf(
            Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        )
        var which = 0
        var frame = 0
        try {
            while (true) {
                val time = if (isPreviewPlaying) previewCurrentPositionMs.coerceIn(config.startMs, config.endMs) else config.startMs + 300
                val target = buffers[which]
                withContext(Dispatchers.Default) { VideoClipGenerator.renderPreviewFrame(renderer, target, time, frame.toFloat()) }
                previewBitmap = target
                which = 1 - which
                frame += 2
                delay(if (isPreviewPlaying) 50 else 120)
            }
        } finally {
            renderer.release()
        }
    }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0B10))
                .systemBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                // 1. Cabecera
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { dismiss() }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (draftId != null) "Continuar video" else if (initial != null) "Editar video" else "Crear video", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        val (vw, vh) = VideoClipGenerator.outputSize(config)
                        Text(
                            "${formatLabel(format)} · ${vw}×$vh · $fps fps · ${if (canGenerate) "${(endMs - startMs) / 1000} s" else "sin fragmento"}",
                            color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp
                        )
                    }
                    if (onOpenHistory != null) {
                        TextButton(onClick = onOpenHistory) {
                            Icon(Icons.Rounded.VideoLibrary, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Mis videos", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 2. Vista previa (cuadros reales del video)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = previewBitmap
                    val ratio = config.format.logicalWidth.toFloat() / config.format.logicalHeight
                    Box(
                        modifier = Modifier
                            .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black)
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                    ) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Vista previa",
                                contentScale = ContentScale.Fit,
                                // Pixel art: ampliar sin suavizar
                                filterQuality = if (templateStyle == ClipTemplateStyle.PIXEL_ART) FilterQuality.None else FilterQuality.Medium,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        // Preescuchar
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 10.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                                .clickable(enabled = canGenerate) { isPreviewPlaying = !isPreviewPlaying }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                if (isPreviewPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "Preescuchar",
                                tint = primaryColor,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (isPreviewPlaying) "Pausar" else if (!canGenerate) "Elige las líneas" else "Escuchar",
                                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 3. Fragmento | Diseño
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF161824))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(Icons.Rounded.ContentCut to "Fragmento", Icons.Rounded.Palette to "Diseño").forEachIndexed { i, (icon, name) ->
                        val sel = panel == i
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) primaryColor else Color.Transparent)
                                .clickable { panel = i }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(icon, null, tint = if (sel) Color.Black else Color.White.copy(alpha = 0.65f), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(name, color = if (sel) Color.Black else Color.White.copy(alpha = 0.75f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (panel == 0) {
                // 4. Fragment Selector (Letras, Deslizar o Manual)
                run {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF161824))
                                .padding(2.dp)
                        ) {
                            val tabs = buildList {
                                if (parsedLyrics.isNotEmpty()) add(0 to "Por letras")
                                add(2 to "Deslizar")
                                add(1 to "Manual")
                            }
                            tabs.forEach { (tab, label) ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selectedTab == tab) primaryColor.copy(alpha = 0.2f) else Color.Transparent)
                                        .clickable { selectedTab = tab; isPreviewPlaying = false }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        label,
                                        color = if (selectedTab == tab) primaryColor else Color.White.copy(alpha = 0.6f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (canGenerate) "${((endMs - startMs) / 1000)} s" else "Sin selección",
                                color = if (canGenerate) primaryColor else Color.White.copy(alpha = 0.5f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (selectedTab == 0 && hasLyricSelection) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF161824))
                                        .clickable { clearLyricSelection() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("Borrar", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    if (selectedTab == 0 && parsedLyrics.isNotEmpty()) {
                        Text(
                            text = when {
                                !hasLyricSelection -> "Toca la primera línea, o mantén pulsado y arrastra"
                                selectedLyricStartIdx == selectedLyricEndIdx -> "Ahora toca la última línea (máx. 60 s)"
                                else -> "Toca fuera para ampliar · un extremo para quitarlo · mantén pulsado y arrastra para elegir varias"
                            },
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Fragment Selector Body
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF141624))
                        .padding(8.dp)
                ) {
                    if (selectedTab == 0 && parsedLyrics.isNotEmpty()) {
                        val listState = rememberLazyListState(
                            initialFirstVisibleItemIndex = max(0, initialIdx - 1)
                        )
                        // Mantener pulsado y arrastrar: la selección va desde la línea donde se pulsó hasta la que
                        // queda bajo el dedo (en cualquier dirección), y la lista se desplaza sola cerca de los bordes.
                        // Antes el mantener pulsado solo marcaba esa línea y arrastrar no seleccionaba nada
                        var dragAnchor by remember { mutableIntStateOf(-1) }
                        var dragY by remember { mutableFloatStateOf(-1f) }
                        fun indexAt(y: Float): Int? = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { y >= it.offset && y < it.offset + it.size }?.index
                            ?: listState.layoutInfo.visibleItemsInfo.let { items ->
                                when {
                                    items.isEmpty() -> null
                                    y < items.first().offset -> items.first().index
                                    else -> items.last().index
                                }
                            }
                        fun dragTo(index: Int) {
                            val a = dragAnchor.takeIf { it >= 0 } ?: return
                            var lo = min(a, index)
                            var hi = max(a, index)
                            // Máximo 60 s: se recorta por el lado que se está arrastrando
                            while (hi > lo && lineEndMs(hi) - parsedLyrics[lo].timeMs > 60000L) { if (index >= a) hi-- else lo++ }
                            if (lo != selectedLyricStartIdx || hi != selectedLyricEndIdx) {
                                selectedLyricStartIdx = lo
                                selectedLyricEndIdx = hi
                                applyLyricRange()
                            }
                        }
                        LaunchedEffect(dragAnchor) {
                            if (dragAnchor < 0) return@LaunchedEffect
                            val edge = 48f
                            while (dragAnchor >= 0) {
                                val h = listState.layoutInfo.viewportSize.height
                                val y = dragY
                                val delta = when {
                                    y in 0f..edge -> -(edge - y) / 3f
                                    y > h - edge -> (y - (h - edge)) / 3f
                                    else -> 0f
                                }
                                if (delta != 0f) {
                                    listState.scrollBy(delta)
                                    indexAt(y)?.let { dragTo(it) }
                                }
                                kotlinx.coroutines.delay(16)
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(parsedLyrics) {
                                    // En la pasada inicial: si no, tras mantener pulsado el arrastre se lo quedaba el
                                    // desplazamiento de la lista y la selección no crecía
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        // Pulsación larga: el dedo quieto (dentro del margen de toque) durante el tiempo del sistema
                                        var lastPos = down.position
                                        val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                            while (true) {
                                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                                val ch = e.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                                                if (!ch.pressed) return@withTimeoutOrNull false
                                                if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                                                lastPos = ch.position
                                            }
                                            @Suppress("UNREACHABLE_CODE") false
                                        }
                                        if (held != null) return@awaitEachGesture
                                        val i = indexAt(lastPos.y) ?: return@awaitEachGesture
                                        dragY = lastPos.y
                                        dragAnchor = i
                                        selectedLyricStartIdx = i
                                        selectedLyricEndIdx = i
                                        applyLyricRange()
                                        try {
                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                if (!change.pressed) { change.consume(); break }
                                                change.consume()
                                                dragY = change.position.y
                                                indexAt(change.position.y)?.let { dragTo(it) }
                                            }
                                        } finally {
                                            dragAnchor = -1
                                        }
                                    }
                                }
                        ) {
                            itemsIndexed(parsedLyrics) { index, line ->
                                val isSelected = hasLyricSelection && index in selectedLyricStartIdx..selectedLyricEndIdx
                                val isNowPlaying = !hasLyricSelection && index == initialIdx

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            when {
                                                isSelected -> primaryColor.copy(alpha = 0.18f)
                                                isNowPlaying -> Color.White.copy(alpha = 0.05f)
                                                else -> Color.Transparent
                                            }
                                        )
                                        .clickable { handleLyricClick(index) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Selection Circle Icon
                                    Icon(
                                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSelected) primaryColor else Color.White.copy(alpha = 0.3f),
                                        modifier = Modifier.size(16.dp)
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Text(
                                        text = formatTime(line.timeMs),
                                        color = if (isSelected) primaryColor else Color.White.copy(alpha = 0.4f),
                                        fontSize = 11.sp,
                                        modifier = Modifier.width(38.dp)
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        val edgeLabel = when {
                                            !isSelected -> null
                                            selectedLyricStartIdx == selectedLyricEndIdx -> "INICIO · FIN"
                                            index == selectedLyricStartIdx -> "INICIO"
                                            index == selectedLyricEndIdx -> "FIN"
                                            else -> null
                                        }
                                        if (edgeLabel != null) {
                                            Text(edgeLabel, color = primaryColor, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                        }
                                        Text(
                                            text = line.text,
                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.65f),
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (showTranslation && translatedLyrics != null) {
                                            val tText = translatedLyrics[line.timeMs]
                                            if (!tText.isNullOrBlank()) {
                                                Text(
                                                    text = tText,
                                                    color = if (isSelected) primaryColor.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.45f),
                                                    fontSize = 11.sp,
                                                    fontStyle = FontStyle.Italic,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (selectedTab == 2) {
                        SwipeFragmentPicker(
                            startMs = startMs,
                            endMs = endMs,
                            songDurationMs = songDurationMs,
                            lyrics = parsedLyrics,
                            primaryColor = primaryColor,
                            seed = songTitle.hashCode(),
                            onChange = { s0, e0 -> startMs = s0; endMs = e0; isPreviewPlaying = false }
                        )
                    } else {
                        // Manual Slider Selector
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Inicio: ${formatTime(startMs)}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "Fin: ${formatTime(endMs)}",
                                    color = primaryColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Slider(
                                value = startMs.toFloat(),
                                onValueChange = { newStart ->
                                    val duration = endMs - startMs
                                    startMs = newStart.toLong()
                                    endMs = min(songDurationMs, startMs + duration)
                                },
                                valueRange = 0f..max(0L, songDurationMs - 5000L).toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = primaryColor,
                                    activeTrackColor = primaryColor,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                )
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(15, 30, 45, 60).forEach { sec ->
                                    val isCurrentDuration = ((endMs - startMs) / 1000L).toInt() == sec
                                    FilterChip(
                                        selected = isCurrentDuration,
                                        onClick = {
                                            endMs = min(songDurationMs, startMs + sec * 1000L)
                                        },
                                        label = { Text("${sec}s", fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = primaryColor,
                                            selectedLabelColor = Color.Black
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                } else {
                    // Panel de diseño
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(262.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF141624))
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OptionSection("Plantilla") {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    Triple(ClipTemplateStyle.LYRICS_STORY, "Letras", Icons.Rounded.FormatQuote),
                                    Triple(ClipTemplateStyle.PLAYER_CANVAS, "Portada", Icons.Rounded.Image),
                                    Triple(ClipTemplateStyle.VINYL_VIBES, "Vinilo", Icons.Rounded.Album),
                                    Triple(ClipTemplateStyle.PIXEL_ART, "Pixel art", Icons.Rounded.GridOn)
                                ).forEach { (style, name, icon) ->
                                    TemplateCard(name, icon, templateStyle == style, primaryColor, Modifier.weight(1f)) {
                                        templateStyle = style
                                        // Pixel art va con fuente pixel; al salir de ella se vuelve a la normal
                                        if (style == ClipTemplateStyle.PIXEL_ART) font = ClipFont.PIXEL
                                        else if (font == ClipFont.PIXEL) font = ClipFont.SANS
                                    }
                                }
                            }
                        }

                        if (templateStyle == ClipTemplateStyle.PIXEL_ART) {
                            OptionSection("Estilo") {
                                ChipRow(PixelLayout.entries.toList(), pixelLayout, {
                                    when (it) {
                                        PixelLayout.SCENE -> "Portada"
                                        PixelLayout.LYRICS -> "Letras"
                                        PixelLayout.VINYL -> "Vinilo"
                                    }
                                }, primaryColor) { pixelLayout = it }
                            }
                        }

                        if (templateStyle == ClipTemplateStyle.PIXEL_ART) {
                            OptionSection("Escena") {
                                ChipRow(PixelScene.entries.toList(), pixelScene, {
                                    when (it) {
                                        PixelScene.STARS -> "Estrellas"
                                        PixelScene.FOREST -> "Bosque"
                                        PixelScene.ROAD -> "Carretera"
                                        PixelScene.CYBER_CITY -> "Ciudad futurista"
                                        PixelScene.DISCO -> "Disco robot"
                                        PixelScene.CITY -> "Ciudad"
                                        PixelScene.JAPAN -> "Japón"
                                    }
                                }, primaryColor) { pixelScene = it }
                            }
                        }

                        if (templateStyle == ClipTemplateStyle.PIXEL_ART) {
                            OptionSection("Paleta") {
                                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PixelPalettes.forEach { p ->
                                        val sel = p.key == pixelPaletteKey
                                        Column(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .border(if (sel) 2.dp else 1.dp, if (sel) primaryColor else Color.White.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                                .clickable { pixelPaletteKey = p.key }
                                                .padding(6.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Row(Modifier.height(12.dp).width(56.dp)) {
                                                p.swatch.forEach { c -> Box(Modifier.weight(1f).fillMaxHeight().background(c)) }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(p.nameEs, color = if (sel) primaryColor else Color.White.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }

                        if (templateStyle == ClipTemplateStyle.PIXEL_ART) {
                            OptionSection("Portada") {
                                ChipRow(listOf(PixelArtMode.PALETTE, PixelArtMode.PIXELATED, PixelArtMode.ORIGINAL), pixelArtMode, {
                                    when (it) {
                                        PixelArtMode.PALETTE -> "Colores de la paleta"
                                        PixelArtMode.PIXELATED -> "Pixelada"
                                        PixelArtMode.ORIGINAL -> "Sin alterar"
                                    }
                                }, primaryColor) { pixelArtMode = it }
                            }
                        }

                        OptionSection("Formato") {
                            ChipRow(
                                listOf(ClipFormat.STORY, ClipFormat.PORTRAIT, ClipFormat.SQUARE, ClipFormat.LANDSCAPE),
                                format, { formatLabel(it) }, primaryColor
                            ) { format = it }
                        }

                        OptionSection("Calidad") {
                            ChipRow(listOf(ClipQuality.HD, ClipQuality.FULL_HD), quality, {
                                if (it == ClipQuality.HD) "720p · rápido" else "1080p · más nítido"
                            }, primaryColor) { quality = it }
                        }

                        OptionSection("Fluidez") {
                            // 60 fps solo si el codificador del teléfono lo admite a esta resolución
                            val supports60 = remember(format, quality) { VideoClipGenerator.supportsFrameRate(config.copy(fps = 60), 60) }
                            LaunchedEffect(supports60) { if (!supports60 && fps == 60) fps = 30 }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                StyledChip("24 fps · cine", fps == 24, primaryColor) { fps = 24 }
                                StyledChip("30 fps", fps == 30, primaryColor) { fps = 30 }
                                if (supports60) StyledChip("60 fps · fluido", fps == 60, primaryColor) { fps = 60 }
                                else Text("60 fps no disponible en ${if (quality == ClipQuality.FULL_HD) "1080p" else "esta resolución"}", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                            }
                        }

                        OptionSection("Fuente") {
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    Triple(ClipFont.SANS, "Moderna", FontFamily.SansSerif),
                                    Triple(ClipFont.SERIF, "Clásica", FontFamily.Serif),
                                    Triple(ClipFont.MONO, "Máquina", FontFamily.Monospace),
                                    Triple(ClipFont.CONDENSED, "Condensada", FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))),
                                    Triple(ClipFont.PIXEL, "Pixel", PixelFontFamily)
                                ).forEach { (f, name, family) ->
                                    StyledChip(name, font == f, primaryColor, family) { font = f }
                                }
                            }
                        }

                        OptionSection("Texto") {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                StyledChip("Aa", !uppercase, primaryColor) { uppercase = false }
                                StyledChip("AA", uppercase, primaryColor) { uppercase = true }
                                Spacer(Modifier.width(8.dp))
                                listOf(0.85f to "S", 1f to "M", 1.2f to "L").forEach { (scale, label) ->
                                    StyledChip(label, textScale == scale, primaryColor) { textScale = scale }
                                }
                            }
                        }

                        if (templateStyle != ClipTemplateStyle.PIXEL_ART) {
                            OptionSection("Fondo") {
                                ChipRow(listOf(ClipBackground.AMBIENT, ClipBackground.BLURRED_ART, ClipBackground.AMOLED), background, {
                                    when (it) {
                                        ClipBackground.AMBIENT -> "Ambiente"
                                        ClipBackground.BLURRED_ART -> "Portada difuminada"
                                        ClipBackground.AMOLED -> "Negro AMOLED"
                                    }
                                }, primaryColor) { background = it }
                            }
                        }

                        OptionSection("Extras") {
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (hasTranslation) {
                                    StyledChip("Traducción", showTranslation, primaryColor, icon = Icons.Rounded.Translate) { showTranslation = !showTranslation }
                                }
                                StyledChip("Ondas", showWaveform, primaryColor, icon = Icons.Rounded.GraphicEq) { showWaveform = !showWaveform }
                                StyledChip(
                                    if (dedicationText.isNotBlank()) "Dedicatoria ✓" else "Dedicar",
                                    dedicationText.isNotBlank(), primaryColor, icon = Icons.Rounded.Favorite
                                ) { showDedicationDialog = true }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 4. Generar / resultado
                val done = generatedFile
                if (done != null && !isGenerating) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (saveVideoToGallery(context, done, songTitle)) {
                                    Toast.makeText(context, "Guardado en Películas/KevMusic", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "No se pudo guardar el video", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Rounded.Download, null, tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text("Guardar", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { shareVideoFile(context, done, songTitle, artistName, dedicationText) },
                            modifier = Modifier.weight(1.4f).height(50.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                        ) {
                            Icon(Icons.Rounded.Share, null, tint = Color.Black)
                            Spacer(Modifier.width(6.dp))
                            Text(if (dedicationText.isNotBlank()) "Dedicar" else "Compartir", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = { generatedFile = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Hacer otro con otros ajustes", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = {
                            if (isGenerating) return@Button
                            isPreviewPlaying = false
                            val finalConfig = config.copy(startMs = startMs, endMs = endMs)
                            if (!ClipHistory.startGeneration(context, currentDraft(), finalConfig)) {
                                Toast.makeText(context, "Espera a que termine el otro video", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !isGenerating && !otherJobRunning && canGenerate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                progress = { generationProgress },
                                color = Color.Black,
                                trackColor = Color.Black.copy(alpha = 0.2f),
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            val elapsed = System.currentTimeMillis() - generationStartedAt
                            val remaining = if (generationProgress > 0.08f) ((elapsed / generationProgress) * (1 - generationProgress) / 1000).toInt() else -1
                            Text(
                                text = "Creando video… ${(generationProgress * 100).toInt()}%" + if (remaining >= 0) " · quedan ${remaining + 1} s" else "",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        } else {
                            Icon(Icons.Rounded.MovieFilter, contentDescription = null, tint = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    otherJobRunning -> "Creando otro video… ${((clipJob?.progress ?: 0f) * 100).toInt()}%"
                                    !canGenerate -> "Elige las líneas del fragmento"
                                    else -> "Crear video"
                                },
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        // Dedication Dialog Modal
        if (showDedicationDialog) {
            var tempDedication by remember { mutableStateOf(dedicationText) }
            AlertDialog(
                onDismissRequest = { showDedicationDialog = false },
                containerColor = Color(0xFF181A28),
                shape = RoundedCornerShape(20.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Favorite, contentDescription = null, tint = primaryColor, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Dedicar este fragmento", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column {
                        Text(
                            "Agrega un mensaje o dedicatoria personalizada. Aparecerá en el encabezado del video y en el mensaje al compartir.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Suggestion Pills
                        Text("Sugerencias rápidas:", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val suggestions = listOf("Para ti ❤️", "Te dedico esta parte ✨", "Mi favorita 🎧", "Escucha esto 💫", "Con amor 💖")
                            items(suggestions) { tag ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .clickable { tempDedication = tag }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(tag, color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = tempDedication,
                            onValueChange = { if (it.length <= 45) tempDedication = it },
                            placeholder = { Text("Escribe un mensaje o para quién es...", fontSize = 13.sp, color = Color.White.copy(alpha = 0.4f)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedContainerColor = Color.Black.copy(alpha = 0.3f),
                                unfocusedContainerColor = Color.Black.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            dedicationText = tempDedication.trim()
                            showDedicationDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Guardar", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    if (dedicationText.isNotBlank()) {
                        TextButton(
                            onClick = {
                                dedicationText = ""
                                tempDedication = ""
                                showDedicationDialog = false
                            }
                        ) {
                            Text("Quitar", color = Color(0xFFFF5252))
                        }
                    } else {
                        TextButton(onClick = { showDedicationDialog = false }) {
                            Text("Cancelar", color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            )
        }
    }
}

private fun formatLabel(f: ClipFormat) = when (f) {
    ClipFormat.STORY -> "9:16 Historia"
    ClipFormat.PORTRAIT -> "4:5 Feed"
    ClipFormat.SQUARE -> "1:1 Cuadrado"
    ClipFormat.LANDSCAPE -> "16:9 Horizontal"
}

@Composable
private fun OptionSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun TemplateCard(name: String, icon: ImageVector, selected: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) accent else Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = if (selected) accent else Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(name, color = if (selected) accent else Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, accent: Color, onSelect: (T) -> Unit) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> StyledChip(label(o), o == selected, accent) { onSelect(o) } }
    }
}

@Composable
private fun StyledChip(
    label: String,
    selected: Boolean,
    accent: Color,
    family: FontFamily? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent else Color.White.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) Color.Black else Color.White.copy(alpha = 0.75f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, color = if (selected) Color.Black else Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = family)
    }
}

/** Copia el video a Películas/KevMusic (MediaStore). Lo pide el usuario con "Guardar". */
internal fun saveVideoToGallery(context: Context, file: File, title: String): Boolean = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "KevMusic_${title.replace(Regex("[^\\p{L}\\p{N}]+"), "_").take(40)}_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/KevMusic")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return false
    values.clear()
    values.put(MediaStore.Video.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    true
}.getOrDefault(false)

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

internal fun shareVideoFile(
    context: Context,
    videoFile: File,
    songTitle: String,
    artistName: String,
    dedicationText: String
) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            videoFile
        )
        val shareMessage = buildString {
            if (dedicationText.isNotBlank()) {
                val prefix = if (dedicationText.startsWith("💌") || dedicationText.startsWith("✨") || dedicationText.startsWith("💖")) "" else "💌 "
                appendLine("$prefix$dedicationText")
                appendLine()
            }
            appendLine("🎵 $songTitle - $artistName")
            append("✨ Compartido desde KevMusicPlayer")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, shareMessage)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, if (dedicationText.isNotBlank()) "Dedicar historia" else "Compartir historia"))
    } catch (e: Exception) {
        Toast.makeText(context, "No se pudo compartir el archivo: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}


/**
 * Selector de fragmento estilo Instagram: una franja con la canción entera que se arrastra de lado bajo una
 * ventana fija del largo elegido (15, 30, 45 o 60 s). Arriba se ve la línea de la letra con la que empieza.
 * Las barras son decorativas (estables por canción), no la forma de onda real.
 */
@Composable
private fun SwipeFragmentPicker(
    startMs: Long,
    endMs: Long,
    songDurationMs: Long,
    lyrics: List<com.kevshupp.kevmusicplayer.data.LyricLine>,
    primaryColor: Color,
    seed: Int,
    onChange: (Long, Long) -> Unit
) {
    val lengthMs = (endMs - startMs).coerceIn(5000L, 60000L).coerceAtMost(songDurationMs.coerceAtLeast(1L))
    val maxStart = (songDurationMs - lengthMs).coerceAtLeast(0L)
    val currentLine = lyrics.lastOrNull { it.timeMs <= startMs + 300 }?.text?.takeIf { it.isNotBlank() }
    val latestStart by rememberUpdatedState(startMs)
    val latestLength by rememberUpdatedState(lengthMs)
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${formatTime(startMs)} – ${formatTime(startMs + lengthMs)}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Desliza para elegir", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }
        Text(
            currentLine ?: "♪",
            color = primaryColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            // La ventana ocupa el 60 % del ancho y está centrada; la franja se mueve debajo
            val windowPx = widthPx * 0.6f
            val pxPerMs = windowPx / lengthMs
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(songDurationMs, pxPerMs) {
                        var acc = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { acc = latestStart.toFloat() },
                            onHorizontalDrag = { change, dx ->
                                change.consume()
                                acc = (acc - dx / pxPerMs).coerceIn(0f, maxStart.toFloat())
                                val s = (acc.toLong() / 250L) * 250L
                                onChange(s, s + latestLength)
                            }
                        )
                    }
            ) {
                val left = (size.width - windowPx) / 2f
                val barStepMs = 400L
                val firstMs = ((startMs - (left / pxPerMs).toLong()) / barStepMs).coerceAtLeast(0L) * barStepMs
                val barW = (barStepMs * pxPerMs * 0.55f).coerceIn(2f, 10f)
                var t = firstMs
                while (t <= songDurationMs) {
                    val x = left + (t - startMs) * pxPerMs
                    if (x > size.width) break
                    val n = ((t / barStepMs).toInt() * 1103515245 + seed) ushr 8
                    val h = size.height * (0.25f + (n % 70) / 100f)
                    val inside = t in startMs until (startMs + lengthMs)
                    drawRoundRect(
                        color = if (inside) primaryColor else Color.White.copy(alpha = 0.25f),
                        topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2f),
                        size = androidx.compose.ui.geometry.Size(barW, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f)
                    )
                    t += barStepMs
                }
                drawRoundRect(
                    color = primaryColor,
                    topLeft = androidx.compose.ui.geometry.Offset(left - 4f, 0f),
                    size = androidx.compose.ui.geometry.Size(windowPx + 8f, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45, 60).forEach { sec ->
                FilterChip(
                    selected = (lengthMs / 1000L).toInt() == sec,
                    onClick = {
                        val len = min(sec * 1000L, songDurationMs)
                        val s = startMs.coerceAtMost((songDurationMs - len).coerceAtLeast(0L))
                        onChange(s, s + len)
                    },
                    label = { Text("${sec}s", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = primaryColor, selectedLabelColor = Color.Black)
                )
            }
        }
    }
}
