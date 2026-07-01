package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.key
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.kevshupp.kevmusicplayer.R
import coil.compose.SubcomposeAsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.shadow

data class CategoryItem(
    val name: String,
    val labelRes: Int,
    val descRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

data class SortPrefItem(
    val value: String,
    val name: String,
    val desc: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun settingsTextColor(): Color {
    val isMonochrome = MaterialTheme.colorScheme.background == Color.White
    return if (isMonochrome) Color.Black else Color.White
}

@Composable
fun settingsTextMutedColor(): Color {
    val isMonochrome = MaterialTheme.colorScheme.background == Color.White
    return if (isMonochrome) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.5f)
}

@Composable
fun settingsDividerColor(): Color {
    val isMonochrome = MaterialTheme.colorScheme.background == Color.White
    return if (isMonochrome) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.08f)
}

@Composable
fun settingsCardContainerColor(): Color {
    val isMonochrome = MaterialTheme.colorScheme.background == Color.White
    return if (isMonochrome) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
}

@Composable
fun GeneralSettingsSection(
    selectedTheme: String,
    onThemeSelected: (String) -> Unit,
    selectedLanguage: String,
    applyLanguage: (String) -> Unit,
    sortBy: String,
    onSortByChanged: (String) -> Unit,
    getLocalized: (String, String) -> String,
    settingsPrefs: android.content.SharedPreferences,
    viewModel: com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
) {
    var showPlayerCustomizer by remember { mutableStateOf(false) }

    // 1. Temas de colores (Aesthetic Color Themes Selector)
    Column {
        Text(
            text = getLocalized("TEMA DE COLORES", "COLOR THEME"),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                val themes = listOf(
                    Triple("cyberpunk", getLocalized("Cyberpunk Rosa", "Cyberpunk Pink"), getLocalized("Vibrante tono rosa neón y púrpura", "Vibrant neon pink and purple style")),
                    Triple("cyberpunk_purpura", getLocalized("Cyberpunk Púrpura", "Cyberpunk Purple"), getLocalized("Vibrante tono púrpura eléctrico y rosa", "Vibrant electric purple and neon pink style")),
                    Triple("petrol", getLocalized("Azul Petróleo", "Petrol Blue"), getLocalized("Sofisticado azul petróleo y cian minimalista", "Sophisticated petrol blue and clean cyan")),
                    Triple("turquoise", getLocalized("Turquesa", "Turquoise"), getLocalized("Estilo turquesa y verde menta refrescante", "Refreshing turquoise and mint green style")),
                    Triple("obsidian", getLocalized("Obsidiana Oscuro", "Deep Obsidian"), getLocalized("Fondo negro puro de alto contraste (AMOLED)", "Pure black background with high contrast (AMOLED)")),
                    Triple("monochrome", getLocalized("Blanco y Negro", "Monochrome"), getLocalized("Elegante diseño minimalista en escala de grises", "Elegant minimalist grayscale design"))
                )

                themes.forEachIndexed { index, (tag, name, desc) ->
                    val isSelected = selectedTheme == tag
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onThemeSelected(tag)
                                settingsPrefs.edit().putString("app_theme", tag).apply()
                            }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                onThemeSelected(tag)
                                settingsPrefs.edit().putString("app_theme", tag).apply()
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = desc,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        // Visual Indicator Badge of theme colors
                        Box(
                            modifier = Modifier
                                .size(36.dp, 20.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when (tag) {
                                        "cyberpunk_purpura" -> Brush.horizontalGradient(listOf(Color(0xFF0C0514), Color(0xFFD000FF)))
                                        "petrol" -> Brush.horizontalGradient(listOf(Color(0xFF0A1E24), Color(0xFF00E5FF)))
                                        "obsidian" -> Brush.horizontalGradient(listOf(Color(0xFF0E0E0E), Color(0xFFFFFFFF)))
                                        "turquoise" -> Brush.horizontalGradient(listOf(Color(0xFF071F1B), Color(0xFF00F5D4)))
                                        "monochrome" -> Brush.horizontalGradient(listOf(Color(0xFF000000), Color(0xFFFFFFFF)))
                                        else -> Brush.horizontalGradient(listOf(Color(0xFF121422), Color(0xFFFF4081)))
                                    }
                                )
                        )
                    }

                    if (index < themes.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Column {
        Text(
            text = getLocalized("PERSONALIZACIÓN DEL REPRODUCTOR", "PLAYER CUSTOMIZATION"),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .clickable { showPlayerCustomizer = true }
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Personalizar Reproductor", "Customize Player"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = getLocalized(
                                "Configura y previsualiza el fondo dinámico, el visualizador y los bordes de la portada.",
                                "Configure and preview the dynamic background, visualizer, and cover borders."
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }

    if (showPlayerCustomizer) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showPlayerCustomizer = false }) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF161829)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = getLocalized("Personalizar Reproductor", "Customize Player"),
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        color = Color.White
                    )

                    // --- PREVIEW PLAYER CARD ---
                    var glowEnabled by remember { mutableStateOf(settingsPrefs.getBoolean("ambient_glow_enabled", true)) }
                    var glowIntensity by remember { mutableStateOf(settingsPrefs.getString("ambient_glow_intensity", "normal") ?: "normal") }
                    var visualizerEnabled by remember { mutableStateOf(settingsPrefs.getBoolean("show_visualizer", false)) }
                    var imageRounded by remember { mutableStateOf(settingsPrefs.getBoolean("song_image_rounded", true)) }

                    val mockSongs = remember {
                        listOf(
                            MockSongItem("Femme Fatale", "Mon Laferte", listOf(Color(0xFFE91E63), Color(0xFF880E4F))),
                            MockSongItem("Acuario", "Manuel García", listOf(Color(0xFF00BCD4), Color(0xFF006064))),
                            MockSongItem("Purple Haze", "Jimi Hendrix", listOf(Color(0xFF9C27B0), Color(0xFF4A148C))),
                            MockSongItem("Neon Lights", "Kraftwerk", listOf(Color(0xFF4CAF50), Color(0xFF1B5E20))),
                            MockSongItem("Acid Rain", "Lorn", listOf(Color(0xFF607D8B), Color(0xFF263238))),
                            MockSongItem("Golden Years", "David Bowie", listOf(Color(0xFFFFC107), Color(0xFFFF8F00)))
                        )
                    }

                    val localSongs = viewModel.localAudioFiles
                    val hasLocalSongs = localSongs.isNotEmpty()

                    var currentMockIndex by remember { mutableStateOf(0) }
                    var currentLocalIndex by remember { mutableStateOf(0) }

                    val currentTitle = if (hasLocalSongs) localSongs[currentLocalIndex].title else mockSongs[currentMockIndex].title
                    val currentArtist = if (hasLocalSongs) localSongs[currentLocalIndex].artist else mockSongs[currentMockIndex].artist
                    val currentUriString = if (hasLocalSongs) localSongs[currentLocalIndex].uriString else null
                    val currentMockColors = if (hasLocalSongs) null else mockSongs[currentMockIndex].colors

                    val artBytes = rememberAlbumArt(currentUriString)
                    val realDominantColor = rememberDominantColor(artBytes)
                    val mockDominantColor = if (currentMockColors != null && currentMockColors.isNotEmpty()) currentMockColors[0] else Color(0xFFFF4081)
                    val dominantColor = if (hasLocalSongs) realDominantColor else mockDominantColor

                    Card(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(380.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        val intensityAlpha = if (glowIntensity == "strong") 0.85f else 0.35f
                        val previewBrush = if (glowEnabled) {
                            Brush.verticalGradient(
                                colors = listOf(
                                    dominantColor.copy(alpha = intensityAlpha),
                                    Color(0xFF0C0514)
                                )
                            )
                        } else {
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF121422),
                                    Color(0xFF0C0514)
                                )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(previewBrush)
                                .padding(20.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                // 1. Large Cover Art Card
                                Card(
                                    modifier = Modifier
                                        .size(160.dp)
                                        .shadow(
                                            elevation = 16.dp,
                                            shape = if (imageRounded) RoundedCornerShape(20.dp) else RectangleShape,
                                            clip = false,
                                            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                            spotColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                        ),
                                    shape = if (imageRounded) RoundedCornerShape(20.dp) else RectangleShape,
                                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(if (hasLocalSongs) getGradientForString(currentTitle) else Brush.linearGradient(currentMockColors!!)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (hasLocalSongs) {
                                            SubcomposeAsyncImage(
                                                model = artBytes,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                                error = {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize(0.9f)
                                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                                            .background(Color.Black.copy(alpha = 0.08f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.MusicNote,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(60.dp),
                                                            tint = Color.White.copy(alpha = 0.95f)
                                                        )
                                                    }
                                                }
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.MusicNote,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.8f),
                                                modifier = Modifier.size(60.dp)
                                            )
                                        }
                                    }
                                }

                                // 2. Title and Artist
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = currentTitle,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 16.sp,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = currentArtist,
                                        fontSize = 13.sp,
                                        color = Color.White.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                // 3. Seekbar Representation
                                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color.White.copy(alpha = 0.15f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.35f)
                                                .fillMaxHeight()
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "01:24",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White.copy(alpha = 0.5f)
                                        )
                                        Text(
                                            text = "03:45",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White.copy(alpha = 0.5f)
                                        )
                                    }
                                }

                                // 4. Playback Controls Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = {}) {
                                        Icon(
                                            imageVector = Icons.Rounded.SkipPrevious,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(Color.White),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            tint = Color(0xFF0C0514),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    IconButton(onClick = {}) {
                                        Icon(
                                            imageVector = Icons.Rounded.SkipNext,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                // 5. Visualizer Section
                                if (visualizerEnabled) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.Bottom,
                                        modifier = Modifier
                                            .height(30.dp)
                                            .padding(vertical = 4.dp)
                                    ) {
                                        listOf(0.4f, 0.9f, 0.6f, 0.8f, 0.3f, 0.7f, 0.5f, 0.8f, 0.4f, 0.6f).forEach { scale ->
                                            Box(
                                                modifier = Modifier
                                                    .width(3.dp)
                                                    .fillMaxHeight(scale)
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                        }
                                    }
                                } else {
                                    Spacer(modifier = Modifier.height(1.dp))
                                }
                            }
                        }
                    }

                    // --- BUTTON TO CHANGE ARTWORK RANDOM / CYCLE ---
                    Button(
                        onClick = {
                            if (hasLocalSongs) {
                                currentLocalIndex = (currentLocalIndex + 1) % localSongs.size
                            } else {
                                currentMockIndex = (currentMockIndex + 1) % mockSongs.size
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.1f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(getLocalized("Probar otra carátula", "Try another cover"), fontSize = 13.sp)
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                    // --- CONTROLS ---

                    // 1. Palette background switch
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Fondo de Paleta de Colores", "Palette Color Background"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = getLocalized("Fondo dinámico según carátula", "Dynamic background based on art"),
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = glowEnabled,
                            onCheckedChange = { checked ->
                                glowEnabled = checked
                                settingsPrefs.edit().putBoolean("ambient_glow_enabled", checked).apply()
                            }
                        )
                    }

                    // 2. Intensity selection (if background enabled)
                    if (glowEnabled) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = getLocalized("Intensidad del Fondo", "Background Intensity"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                listOf(
                                    "normal" to getLocalized("Normal (Suave)", "Normal (Soft)"),
                                    "strong" to getLocalized("Fuerte (Intenso)", "Strong (Intense)")
                                ).forEach { (tag, label) ->
                                    val isSelected = glowIntensity == tag
                                    Surface(
                                        onClick = {
                                            glowIntensity = tag
                                            settingsPrefs.edit().putString("ambient_glow_intensity", tag).apply()
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.05f),
                                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.weight(1f).height(38.dp)
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Audio visualizer switch
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Visualizador de Audio", "Audio Visualizer"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = getLocalized("Barras al ritmo de la música", "Animated bars to the beat"),
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = visualizerEnabled,
                            onCheckedChange = { checked ->
                                visualizerEnabled = checked
                                settingsPrefs.edit().putBoolean("show_visualizer", checked).apply()
                            }
                        )
                    }

                    // 4. Rounded borders switch
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Bordes de Portadas Redondeados", "Rounded Cover Borders"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = getLocalized("Bordes redondeados vs cuadrados", "Rounded borders vs square"),
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = imageRounded,
                            onCheckedChange = { checked ->
                                imageRounded = checked
                                settingsPrefs.edit().putBoolean("song_image_rounded", checked).apply()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Close button
                    TextButton(
                        onClick = { showPlayerCustomizer = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(getLocalized("Cerrar", "Close"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // 2. Language Settings Section
    Column {
        Text(
            text = stringResource(R.string.language_title),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val languages = listOf(
                    "es" to R.string.language_spanish,
                    "en" to R.string.language_english
                )
                languages.forEachIndexed { idx, (tag, resId) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { applyLanguage(tag) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedLanguage == tag,
                            onClick = { applyLanguage(tag) },
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(resId),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = settingsTextColor()
                        )
                    }
                    if (idx < languages.lastIndex) {
                        HorizontalDivider(color = settingsDividerColor())
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 3. Track Sorting Settings Section
    Column {
        Text(
            text = getLocalized("PREFERENCIA DE ORDENACIÓN", "DEFAULT SORT PREFERENCE"),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                val sortPreferences = listOf(
                    SortPrefItem("Alphabetical", getLocalized("Alfabético", "Alphabetical"), getLocalized("Ordenar alfabéticamente por título de canción", "Sort alphabetically by song title"), Icons.Rounded.SortByAlpha),
                    SortPrefItem("Artist", getLocalized("Nombre de Artista", "Artist Name"), getLocalized("Ordenar alfabéticamente por nombre de artista", "Sort alphabetically by artist name"), Icons.Rounded.Person),
                    SortPrefItem("Duration", getLocalized("Duración de Pista", "Track Duration"), getLocalized("Ordenar por duración (más largas primero)", "Sort by track length (longest first)"), Icons.Rounded.HourglassEmpty)
                )
                
                sortPreferences.forEachIndexed { index, pref ->
                    val isSelected = sortBy == pref.value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSortByChanged(pref.value) }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSortByChanged(pref.value) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = pref.icon,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else settingsTextMutedColor(),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = pref.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = settingsTextColor()
                                )
                                Text(
                                    text = pref.desc,
                                    fontSize = 12.sp,
                                    color = settingsTextMutedColor()
                                )
                            }
                        }
                    }
                    if (index < sortPreferences.size - 1) {
                        HorizontalDivider(
                            color = settingsDividerColor(),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SystemSettingsSection(
    selectedRefreshRate: String,
    onRefreshRateSelected: (String) -> Unit,
    disableAnimations: Boolean,
    onDisableAnimationsChanged: (Boolean) -> Unit,
    audioGranted: Boolean,
    notificationGranted: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    audioPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    notificationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    getLocalized: (String, String) -> String,
    settingsPrefs: android.content.SharedPreferences,
    context: android.content.Context
) {
    // 1. Rendimiento & Animaciones (Display & Performance Card)
    Column {
        Text(
            text = getLocalized("RENDIMIENTO Y PANTALLA", "PERFORMANCE & DISPLAY"),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Refresh Rate Selector
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Tasa de Refresco", "Refresh Rate"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = getLocalized(
                                "Forzar tasa alta (120Hz) para máxima fluidez o 60Hz para ahorrar batería",
                                "Enforce high rate (120Hz) for fluid scrolling or 60Hz to save battery"
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("60", "120").forEach { rate ->
                            val isSelected = selectedRefreshRate == rate
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary 
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                    )
                                    .clickable {
                                        onRefreshRateSelected(rate)
                                        settingsPrefs.edit().putString("refresh_rate", rate).apply()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "$rate Hz",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

                // 2. Disable Animations Mode Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FlashOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Modo sin Animaciones", "Disable Animations Mode"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = getLocalized(
                                "Desactiva transiciones y efectos visuales para máxima velocidad en la app",
                                "Disable transitions and visual effects for absolute speed and efficiency"
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    Switch(
                        checked = disableAnimations,
                        onCheckedChange = { checked ->
                            onDisableAnimationsChanged(checked)
                            settingsPrefs.edit().putBoolean("disable_animations", checked).apply()
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

                // 3. Automatic Translation Toggle
                var autoTranslate by remember { mutableStateOf(settingsPrefs.getBoolean("auto_translate", false)) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Traducción Automática", "Auto Translation"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = getLocalized(
                                "Traduce automáticamente las letras si están en un idioma diferente",
                                "Automatically translate lyrics if they are in a different language"
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    Switch(
                        checked = autoTranslate,
                        onCheckedChange = { checked ->
                            autoTranslate = checked
                            settingsPrefs.edit().putBoolean("auto_translate", checked).apply()
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

                // 4. Telemetry Switch
                var telemetryEnabled by remember { mutableStateOf(com.kevshupp.kevmusicplayer.data.TelemetryLogger.isEnabled(context)) }
                var showTelemetryDialog by remember { mutableStateOf(false) }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Registro de Errores", "Error Telemetry"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = getLocalized(
                                "Guarda localmente los fallos y errores de audio para facilitar su análisis y solución.",
                                "Save local audio playback errors and exceptions to help diagnose and resolve issues."
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    Switch(
                        checked = telemetryEnabled,
                        onCheckedChange = { checked ->
                            telemetryEnabled = checked
                            com.kevshupp.kevmusicplayer.data.TelemetryLogger.setEnabled(context, checked)
                        }
                    )
                }

                if (telemetryEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 54.dp)
                    ) {
                        Button(
                            onClick = { showTelemetryDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(getLocalized("Ver", "View"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val logFile = java.io.File(context.filesDir, "telemetry_errors.log")
                                if (logFile.exists() && logFile.length() > 0) {
                                    try {
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "com.kevshupp.kevmusicplayer.fileprovider",
                                            logFile
                                        )
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, getLocalized("Compartir Registro", "Share Log")))
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        android.widget.Toast.makeText(context, "${getLocalized("Error al compartir:", "Failed to share:")} ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    android.widget.Toast.makeText(context, getLocalized("El registro está vacío", "Log is empty"), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(getLocalized("Compartir", "Share"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                com.kevshupp.kevmusicplayer.data.TelemetryLogger.clearLogs(context)
                                android.widget.Toast.makeText(context, getLocalized("Registro limpiado", "Log cleared"), android.widget.Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(36.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(getLocalized("Limpiar", "Clear"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (showTelemetryDialog) {
                    val logs = remember(showTelemetryDialog) { com.kevshupp.kevmusicplayer.data.TelemetryLogger.getLogs(context) }
                    AlertDialog(
                        onDismissRequest = { showTelemetryDialog = false },
                        title = {
                            Text(
                                text = getLocalized("Registro de Errores de la App", "App Error Log"),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column {
                                Text(
                                    text = getLocalized(
                                        "Copia este registro y pégalo en el chat para que el asistente pueda analizar y corregir los problemas.",
                                        "Copy this log and paste it into the chat so the assistant can analyze and fix the issues."
                                    ),
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth().height(250.dp)
                                ) {
                                    Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                                        if (logs.isBlank()) {
                                            Text(
                                                text = getLocalized("El registro está vacío.", "The log is empty."),
                                                color = Color.White.copy(alpha = 0.4f),
                                                fontSize = 12.sp,
                                                modifier = Modifier.align(Alignment.Center)
                                            )
                                        } else {
                                            val scroll = rememberScrollState()
                                            Text(
                                                text = logs,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                modifier = Modifier.fillMaxSize().verticalScroll(scroll)
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (logs.isNotBlank()) {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("Telemetry Log", logs)
                                        clipboard.setPrimaryClip(clip)
                                        android.widget.Toast.makeText(context, getLocalized("Copiado al portapapeles", "Copied to clipboard"), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = logs.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(getLocalized("Copiar", "Copy"), color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showTelemetryDialog = false }) {
                                Text(getLocalized("Cerrar", "Close"), color = Color.White.copy(alpha = 0.6f))
                            }
                        },
                        containerColor = Color(0xFF161829),
                        titleContentColor = Color.White,
                        textContentColor = Color.White
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 2. Permissions & Access Dashboard Card
    Column {
        Text(
            text = getLocalized("PERMISOS DEL SISTEMA", "SYSTEM PERMISSIONS"),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 1. Audio Storage Permission Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (audioGranted) Color(0xFF00E676).copy(alpha = 0.15f) 
                                else Color(0xFFFF1744).copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (audioGranted) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                            contentDescription = null,
                            tint = if (audioGranted) Color(0xFF00E676) else Color(0xFFFF1744),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(14.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Acceso a Música", "Music Files Access"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = settingsTextColor()
                        )
                        Text(
                            text = if (audioGranted) getLocalized("Permitido. Almacenamiento escaneado con éxito.", "Granted. Storage scanned successfully.") 
                                   else getLocalized("Requerido para buscar y reproducir archivos MP3/FLAC locales.", "Required to discover and play local MP3/FLAC music files."),
                            fontSize = 11.sp,
                            color = settingsTextMutedColor()
                        )
                    }
                    
                    if (!audioGranted) {
                        Button(
                            onClick = {
                                val perm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    Manifest.permission.READ_MEDIA_AUDIO
                                } else {
                                    Manifest.permission.READ_EXTERNAL_STORAGE
                                }
                                audioPermissionLauncher.launch(perm)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(getLocalized("Permitir", "Grant"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    } else {
                        Text(
                            text = getLocalized("Activo", "Active"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E676)
                        )
                    }
                }
                
                HorizontalDivider(color = settingsDividerColor())
                
                // 2. Notification Permission Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (notificationGranted) Color(0xFF00E676).copy(alpha = 0.15f) 
                                else Color(0xFFFF9100).copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (notificationGranted) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                            contentDescription = null,
                            tint = if (notificationGranted) Color(0xFF00E676) else Color(0xFFFF9100),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(14.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Notificaciones de Reproducción", "Playback Notifications"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = settingsTextColor()
                        )
                        Text(
                            text = if (notificationGranted) getLocalized("Permitido. Controlador del reproductor activo.", "Granted. Background player controller active.") 
                                   else getLocalized("Requerido para mostrar la canción actual en la barra de tareas.", "Required to show current track in system tray."),
                            fontSize = 11.sp,
                            color = settingsTextMutedColor()
                        )
                    }
                    
                    if (!notificationGranted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        Button(
                            onClick = {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(getLocalized("Permitir", "Grant"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    } else {
                        Text(
                            text = getLocalized("Activo", "Active"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E676)
                        )
                    }
                }

                HorizontalDivider(color = settingsDividerColor())

                // 3. Background Services Status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF00E5FF).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayCircle,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(14.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Servicio en Segundo Plano", "Background Foreground Service"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = settingsTextColor()
                        )
                        Text(
                            text = getLocalized("Mantiene la reproducción persistente y el procesador activo.", "Enforces persistent playback thread and CPU Wake Lock."),
                            fontSize = 11.sp,
                            color = settingsTextMutedColor()
                        )
                    }
                    
                    Text(
                        text = getLocalized("Activo", "Running"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF)
                    )
                }

                HorizontalDivider(color = settingsDividerColor())

                // 4. Battery Optimization Exemption Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isIgnoringBatteryOptimizations) Color(0xFF00E676).copy(alpha = 0.15f)
                                else Color(0xFFFF9100).copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isIgnoringBatteryOptimizations) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryAlert,
                            contentDescription = null,
                            tint = if (isIgnoringBatteryOptimizations) Color(0xFF00E676) else Color(0xFFFF9100),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(14.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Optimización de Batería", "Battery Optimization"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = settingsTextColor()
                        )
                        Text(
                            text = if (isIgnoringBatteryOptimizations) {
                                getLocalized("Sin restricciones. El sistema no suspenderá la reproducción.", "Unrestricted. The system won't kill playback.")
                            } else {
                                getLocalized("Optimizado. Puede cerrarse al estar en segundo plano.", "Optimized. Playback may be killed in background.")
                            },
                            fontSize = 11.sp,
                            color = settingsTextMutedColor()
                        )
                    }
                    
                    if (!isIgnoringBatteryOptimizations) {
                        Button(
                            onClick = {
                                var launched = false
                                try {
                                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                    launched = true
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }

                                if (!launched) {
                                    try {
                                        val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent)
                                        launched = true
                                    } catch (ex: Exception) {
                                        ex.printStackTrace()
                                    }
                                }

                                if (!launched) {
                                    try {
                                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (ex: Exception) {
                                        ex.printStackTrace()
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(getLocalized("Configurar", "Configure"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    } else {
                        Text(
                            text = getLocalized("Ilimitado", "Unrestricted"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E676)
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // 3. Bluetooth Auto-Resume Card
    Column {
        Text(
            text = getLocalized("CONEXIÓN BLUETOOTH", "BLUETOOTH CONNECTION"),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )

        var bluetoothResumeEnabled by remember {
            mutableStateOf(settingsPrefs.getBoolean("bluetooth_resume_enabled", false))
        }
        var resumeAllBluetooth by remember {
            mutableStateOf(settingsPrefs.getBoolean("bluetooth_resume_all", true))
        }

        val bluetoothAdapter = remember {
            try {
                val bm = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                bm?.adapter
            } catch (e: Exception) {
                null
            }
        }

        var hasBluetoothConnectPermission by remember {
            mutableStateOf(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.BLUETOOTH_CONNECT
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
            )
        }

        val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            hasBluetoothConnectPermission = isGranted
            if (isGranted) {
                bluetoothResumeEnabled = true
                settingsPrefs.edit().putBoolean("bluetooth_resume_enabled", true).apply()
            } else {
                bluetoothResumeEnabled = false
                settingsPrefs.edit().putBoolean("bluetooth_resume_enabled", false).apply()
            }
        }

        val bondedDevicesList = remember(hasBluetoothConnectPermission) {
            if (hasBluetoothConnectPermission) {
                try {
                    bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
                } catch (e: SecurityException) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }

        var allowedDevices by remember {
            mutableStateOf(settingsPrefs.getStringSet("bluetooth_resume_devices", emptySet()) ?: emptySet())
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Main Toggle Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bluetooth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Autoreanudar por Bluetooth", "Bluetooth Auto-Resume"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = getLocalized(
                                "Reanudar música automáticamente al conectar un dispositivo Bluetooth",
                                "Resume music automatically when connecting a Bluetooth device"
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    Switch(
                        checked = bluetoothResumeEnabled,
                        onCheckedChange = { checked ->
                            if (checked && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !hasBluetoothConnectPermission) {
                                bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                            } else {
                                bluetoothResumeEnabled = checked
                                settingsPrefs.edit().putBoolean("bluetooth_resume_enabled", checked).apply()
                            }
                        }
                    )
                }

                if (bluetoothResumeEnabled) {
                    HorizontalDivider(color = settingsDividerColor())

                    // "Resume on All Devices" Switch Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Todos los dispositivos", "All Devices"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = getLocalized(
                                    "Reanudar con cualquier dispositivo Bluetooth conectado",
                                    "Resume with any connected Bluetooth device"
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Switch(
                            checked = resumeAllBluetooth,
                            onCheckedChange = { checked ->
                                resumeAllBluetooth = checked
                                settingsPrefs.edit().putBoolean("bluetooth_resume_all", checked).apply()
                            }
                        )
                    }

                    // If not "All Devices", show the checkable list of bonded devices
                    if (!resumeAllBluetooth) {
                        HorizontalDivider(color = settingsDividerColor())

                        Text(
                            text = getLocalized("Dispositivos Permitidos", "Allowed Devices"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 0.5.sp
                        )

                        if (!hasBluetoothConnectPermission && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            Text(
                                text = getLocalized(
                                    "Permiso de Bluetooth Connect no concedido para ver dispositivos.",
                                    "Bluetooth Connect permission not granted to view devices."
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (bondedDevicesList.isEmpty()) {
                            Text(
                                text = getLocalized(
                                    "No hay dispositivos vinculados detectados.",
                                    "No paired devices detected."
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                bondedDevicesList.forEach { device ->
                                    val name = try { device.name } catch (e: SecurityException) { null } ?: device.address
                                    val address = device.address
                                    val isChecked = allowedDevices.contains(address) || allowedDevices.contains(name)

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val newSet = allowedDevices.toMutableSet()
                                                if (isChecked) {
                                                    newSet.remove(address)
                                                    newSet.remove(name)
                                                } else {
                                                    newSet.add(address)
                                                }
                                                settingsPrefs.edit().putStringSet("bluetooth_resume_devices", newSet).apply()
                                                allowedDevices = newSet
                                            }
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                val newSet = allowedDevices.toMutableSet()
                                                if (!checked) {
                                                    newSet.remove(address)
                                                    newSet.remove(name)
                                                } else {
                                                    newSet.add(address)
                                                }
                                                settingsPrefs.edit().putStringSet("bluetooth_resume_devices", newSet).apply()
                                                allowedDevices = newSet
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = MaterialTheme.colorScheme.primary,
                                                uncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Column {
                                            Text(
                                                text = name,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = address,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LibrarySettingsSection(
    enabledTabs: List<String>,
    onEnabledTabsChanged: (List<String>) -> Unit,
    viewModel: com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    isScanning: Boolean,
    onRescan: () -> Unit,
    setIsScanning: (Boolean) -> Unit,
    backupDirUri: String?,
    selectBackupFolderLauncher: androidx.activity.result.ActivityResultLauncher<Uri?>,
    openDocumentLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    createDocumentLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    performExportToFolder: (String, Boolean, Boolean, Boolean, Boolean) -> Unit,
    getLocalized: (String, String) -> String,
    isRenaming: Boolean,
    setIsRenaming: (Boolean) -> Unit,
    renamingCurrent: Int,
    setRenamingCurrent: (Int) -> Unit,
    renamingTotal: Int,
    setRenamingTotal: (Int) -> Unit,
    renamingCurrentName: String,
    setRenamingCurrentName: (String) -> Unit,
    showFolderList: Boolean,
    setShowFolderList: (Boolean) -> Unit,
    deviceFolders: List<String>,
    excludedFolders: List<String>,
    setExcludedFolders: (List<String>) -> Unit,
    onFindDuplicates: () -> Unit
) {
    var activeOrganizerAction by remember { mutableStateOf("") }

    val settingsPrefs = remember { context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE) }
    var selectedMusicFolder by remember {
        mutableStateOf(settingsPrefs.getString("music_folder_path", null))
    }
    var useSameFolderForBackup by remember {
        mutableStateOf(settingsPrefs.getBoolean("use_same_folder_for_backup", false))
    }

    var showExportCustomDialog by remember { mutableStateOf(false) }
    var exportSettings by remember { mutableStateOf(true) }
    var exportEqualizer by remember { mutableStateOf(true) }
    var exportPlaylists by remember { mutableStateOf(true) }
    var exportLyrics by remember { mutableStateOf(true) }

    var totalSongs by remember { mutableStateOf(0) }
    var totalSizeMb by remember { mutableStateOf(0f) }
    LaunchedEffect(viewModel.localAudioFiles.toList(), isScanning) {
        val filesCopy = viewModel.localAudioFiles.toList()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val allSongs = viewModel.audioDao.getAllAudioFiles()
            var totalBytes = 0L
            allSongs.forEach { audioFile ->
                try {
                    val uri = Uri.parse(audioFile.uriString)
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                        totalBytes += fd.length
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
            totalSongs = allSongs.size
            totalSizeMb = totalBytes.toFloat() / (1024 * 1024)
        }
    }

    // Single unified card for Statistics and Maintenance Sections
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = settingsCardContainerColor()
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Library Statistics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = getLocalized("ESTADÍSTICAS DE BIBLIOTECA", "LIBRARY STATISTICS"),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val sizeText = if (totalSizeMb >= 1024) {
                        String.format("%.2f GB", totalSizeMb / 1024)
                    } else {
                        String.format("%.2f MB", totalSizeMb)
                    }
                    Text(
                        text = "$totalSongs ${getLocalized("canciones", "songs")} • $sizeText",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = settingsTextColor()
                    )
                }
            }

            HorizontalDivider(
                color = settingsDividerColor(),
                modifier = Modifier.padding(vertical = 20.dp)
            )

            // 2. Maintenance / Re-scan button Section (Glowing and premium)
            Text(
                text = getLocalized("MANTENIMIENTO DE BIBLIOTECA", "LIBRARY MAINTENANCE"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = getLocalized("Forzar la actualización completa de tu biblioteca de audio y reescanear el almacenamiento", "Force refresh your entire audio library and re-scan device storage"),
                fontSize = 12.sp,
                color = settingsTextMutedColor()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    scope.launch {
                        setIsScanning(true)
                        onRescan()
                        delay(3000)
                        setIsScanning(false)
                    }
                },
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = getLocalized("Escaneando archivos...", "Scanning Files..."),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("Escanear Biblioteca", "Re-scan Library"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider(
                color = settingsDividerColor(),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = getLocalized("DESCARGADOR DE LETRAS", "LYRICS DOWNLOADER"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = getLocalized(
                    "Descarga automáticamente letras (sincronizadas si están disponibles) de internet para toda tu música.",
                    "Automatically download lyrics (synchronized if available) from the internet for all your music."
                ),
                fontSize = 12.sp,
                color = settingsTextMutedColor(),
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.downloadAllLyrics(context)
                },
                enabled = !viewModel.isDownloadingAllLyrics.value && !isScanning && !isRenaming,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (viewModel.isDownloadingAllLyrics.value) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "${viewModel.downloadAllLyricsCurrent.value}/${viewModel.downloadAllLyricsTotal.value}: ${viewModel.downloadAllLyricsCurrentName.value}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    IconButton(
                        onClick = { viewModel.cancelDownloadAllLyrics() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Cancel",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("Descargar Letras de la Biblioteca", "Download Library Lyrics"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    viewModel.deleteAllLyrics(context) {
                        android.widget.Toast.makeText(
                            context,
                            getLocalized("Todas las letras han sido eliminadas", "All lyrics have been deleted"),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                enabled = !viewModel.isDownloadingAllLyrics.value && !isScanning && !isRenaming && !viewModel.isDeletingAllLyrics.value,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (viewModel.isDeletingAllLyrics.value) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = getLocalized("Eliminando...", "Deleting..."),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onError
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onError
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("Eliminar Todas las Letras", "Delete All Lyrics"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onError
                    )
                }
            }

            // Organize Files Section
            HorizontalDivider(
                color = settingsDividerColor(),
                modifier = Modifier.padding(vertical = 16.dp)
            )

            Text(
                text = getLocalized("ORGANIZADOR DE ARCHIVOS", "FILE ORGANIZER"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = getLocalized(
                    "Organiza tu música física. Puedes renombrar los archivos en base a sus metadatos o agruparlos físicamente en carpetas por artista.",
                    "Organize your physical music. You can rename files based on their metadata or physically group them into folders by artist."
                ),
                fontSize = 12.sp,
                color = settingsTextMutedColor(),
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    activeOrganizerAction = "rename"
                    setIsRenaming(true)
                    viewModel.renameSongFilesToMetadata(
                        context = context,
                        onProgress = { current, total, name ->
                            setRenamingCurrent(current)
                            setRenamingTotal(total)
                            setRenamingCurrentName(name)
                        },
                        onComplete = { success, error ->
                            setIsRenaming(false)
                            activeOrganizerAction = ""
                            android.widget.Toast.makeText(
                                context,
                                getLocalized(
                                    "Renombrado finalizado: $success éxito(s), $error error(es)",
                                    "Renaming complete: $success success, $error errors"
                                ),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                },
                enabled = activeOrganizerAction == "" && !isScanning,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isRenaming && activeOrganizerAction == "rename") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "$renamingCurrent/$renamingTotal: $renamingCurrentName",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.DriveFileRenameOutline,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("Renombrar Archivos", "Rename Files"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    activeOrganizerAction = "organize"
                    setIsRenaming(true)
                    viewModel.organizeMusicByArtistFolder(
                        context = context,
                        onProgress = { current, total, name ->
                            setRenamingCurrent(current)
                            setRenamingTotal(total)
                            setRenamingCurrentName(name)
                        },
                        onComplete = { success, error ->
                            setIsRenaming(false)
                            activeOrganizerAction = ""
                            android.widget.Toast.makeText(
                                context,
                                getLocalized(
                                    "Organización finalizada: $success éxito(s), $error error(es)",
                                    "Organization complete: $success success, $error errors"
                                ),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                },
                enabled = activeOrganizerAction == "" && !isScanning,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isRenaming && activeOrganizerAction == "organize") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "$renamingCurrent/$renamingTotal: $renamingCurrentName",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.FolderCopy,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("Organizar por Artista", "Organize by Artist"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            // Duplicate Finder Section
            HorizontalDivider(
                color = settingsDividerColor(),
                modifier = Modifier.padding(vertical = 16.dp)
            )

            Text(
                text = getLocalized("BUSCADOR DE DUPLICADOS", "DUPLICATE FINDER"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = getLocalized(
                    "Busca y elimina canciones duplicadas en tu almacenamiento para liberar espacio.",
                    "Search and delete duplicate songs on your storage to free up space."
                ),
                fontSize = 12.sp,
                color = settingsTextMutedColor(),
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onFindDuplicates,
                enabled = !isScanning && !isRenaming,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = getLocalized("Buscar Canciones Duplicadas", "Search Duplicate Songs"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            // Excluded Folders Section
            HorizontalDivider(
                color = settingsDividerColor(),
                modifier = Modifier.padding(vertical = 16.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { setShowFolderList(!showFolderList) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = getLocalized("CARPETAS EXCLUIDAS", "EXCLUDED FOLDERS"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = getLocalized(
                            "Oculta carpetas de la biblioteca (ej: WhatsApp Audio)",
                            "Hide folders from library (e.g. WhatsApp Audio)"
                        ),
                        fontSize = 11.sp,
                        color = settingsTextMutedColor()
                    )
                }
                Icon(
                    imageVector = if (showFolderList) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = settingsTextColor()
                )
            }

            if (showFolderList) {
                Spacer(modifier = Modifier.height(8.dp))
                if (deviceFolders.isEmpty()) {
                    Text(
                        text = getLocalized("No se encontraron carpetas con música.", "No music folders found."),
                        fontSize = 12.sp,
                        color = settingsTextMutedColor()
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        deviceFolders.forEach { folderPath ->
                            val isExcluded = excludedFolders.contains(folderPath)
                            val folderName = folderPath.substringAfterLast("/")
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(settingsDividerColor())
                                    .clickable {
                                        val newList = if (isExcluded) {
                                            excludedFolders - folderPath
                                        } else {
                                            excludedFolders + folderPath
                                        }
                                        setExcludedFolders(newList)
                                        viewModel.setExcludedFolders(newList)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isExcluded,
                                    onCheckedChange = { checked ->
                                        val newList = if (checked) {
                                            excludedFolders + folderPath
                                        } else {
                                            excludedFolders - folderPath
                                        }
                                        setExcludedFolders(newList)
                                        viewModel.setExcludedFolders(newList)
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.primary,
                                        uncheckedColor = settingsTextMutedColor()
                                    )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = folderName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = settingsTextColor()
                                    )
                                    Text(
                                        text = folderPath,
                                        fontSize = 10.sp,
                                        color = settingsTextMutedColor()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 3. Copias de Seguridad (Backup & Restore Section)
    val folderName = remember(backupDirUri, useSameFolderForBackup, selectedMusicFolder) {
        if (useSameFolderForBackup) {
            if (selectedMusicFolder != null) {
                selectedMusicFolder!!.substringAfterLast("/")
            } else {
                getLocalized("Sin carpeta de música", "No music folder set")
            }
        } else if (backupDirUri != null) {
            try {
                val dirFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, Uri.parse(backupDirUri))
                dirFile?.name ?: getLocalized("Carpeta seleccionada", "Selected Folder")
            } catch (e: Exception) {
                getLocalized("Carpeta seleccionada", "Selected Folder")
            }
        } else null
    }

    Column {
        Text(
            text = getLocalized("COPIAS DE SEGURIDAD", "BACKUP & RESTORE"),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = getLocalized(
                        "Resguarda tus listas de reproducción, preferencias de visualización y letras traducidas a un archivo local en una carpeta fija de manera persistente.",
                        "Safeguard your custom playlists, visual settings, and translated lyrics to a local file in a fixed folder persistently."
                    ),
                    fontSize = 12.sp,
                    color = settingsTextMutedColor(),
                    textAlign = TextAlign.Center
                )

                // Folder status indicator
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, settingsDividerColor()),
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (!useSameFolderForBackup) {
                            selectBackupFolderLauncher.launch(null)
                        } else {
                            android.widget.Toast.makeText(context, getLocalized("Desactiva 'Utilizar la misma carpeta' para cambiar de carpeta", "Disable 'Use same folder' to change folder"), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (useSameFolderForBackup) Icons.Rounded.Link else Icons.Rounded.FolderSpecial,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (useSameFolderForBackup) getLocalized("Carpeta de Copia Vinculada", "Linked Backup Folder") else getLocalized("Carpeta de Destino Fija", "Fixed Target Folder"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = settingsTextColor()
                            )
                            Text(
                                text = folderName ?: getLocalized("Toca para configurar una carpeta...", "Tap to configure a folder..."),
                                fontSize = 11.sp,
                                color = if (folderName != null) MaterialTheme.colorScheme.primary else settingsTextMutedColor()
                            )
                        }
                        if (!useSameFolderForBackup) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Edit",
                                tint = settingsTextMutedColor(),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Export Backup Button
                    Button(
                        onClick = {
                            if (useSameFolderForBackup) {
                                val currentMusicFolder = selectedMusicFolder
                                if (currentMusicFolder != null) {
                                    showExportCustomDialog = true
                                } else {
                                    android.widget.Toast.makeText(context, getLocalized("Por favor, selecciona primero una carpeta de música", "Please select a music folder first"), android.widget.Toast.LENGTH_LONG).show()
                                }
                            } else {
                                val currentDirUri = backupDirUri
                                if (currentDirUri != null) {
                                    showExportCustomDialog = true
                                } else {
                                    selectBackupFolderLauncher.launch(null)
                                }
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Backup,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = getLocalized("Exportar", "Backup"),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }

                    // 2. Import Restore Button
                    var showRestoreOptions by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = {
                                if (useSameFolderForBackup) {
                                    showRestoreOptions = true
                                } else if (backupDirUri != null) {
                                    showRestoreOptions = true
                                } else {
                                    openDocumentLauncher.launch(arrayOf("application/json"))
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Restore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = getLocalized("Restaurar", "Restore"),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        DropdownMenu(
                            expanded = showRestoreOptions,
                            onDismissRequest = { showRestoreOptions = false },
                            containerColor = if (MaterialTheme.colorScheme.background == Color.White) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF1E213A)
                        ) {
                            DropdownMenuItem(
                                text = { 
                                    val itemText = if (useSameFolderForBackup) {
                                        getLocalized("Restaurar desde carpeta de música", "Restore from music folder")
                                    } else {
                                        getLocalized("Restaurar desde carpeta fija", "Restore from fixed folder")
                                    }
                                    Text(itemText, color = settingsTextColor()) 
                                },
                                onClick = {
                                    showRestoreOptions = false
                                    if (useSameFolderForBackup) {
                                        val currentMusicFolder = selectedMusicFolder
                                        if (currentMusicFolder != null) {
                                            try {
                                                val backupFile = java.io.File(currentMusicFolder, "kev_music_player_backup.json")
                                                if (backupFile.exists()) {
                                                    val inputStream = backupFile.inputStream()
                                                    viewModel.importBackup(
                                                        context = context,
                                                        inputStream = inputStream,
                                                        onSuccess = {
                                                            android.widget.Toast.makeText(context, getLocalized("Copia de seguridad restaurada con éxito", "Backup restored successfully"), android.widget.Toast.LENGTH_LONG).show()
                                                            viewModel.connect()
                                                        },
                                                        onError = { error ->
                                                            android.widget.Toast.makeText(context, "${getLocalized("Error al restaurar:", "Failed to restore:")} ${error.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                                        }
                                                    )
                                                } else {
                                                    android.widget.Toast.makeText(context, getLocalized("No se encontró el archivo 'kev_music_player_backup.json' en la carpeta de música.", "No 'kev_music_player_backup.json' file found in music folder."), android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            } catch (e: Exception) {
                                                android.widget.Toast.makeText(context, "${getLocalized("Error de lectura:", "Read error:")} ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            android.widget.Toast.makeText(context, getLocalized("Por favor, selecciona primero una carpeta de música", "Please select a music folder first"), android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        val currentDirUri = backupDirUri
                                        if (currentDirUri != null) {
                                            try {
                                                val dirFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, Uri.parse(currentDirUri))
                                                val backupFile = dirFile?.findFile("kev_music_player_backup.json")
                                                if (backupFile != null && backupFile.exists()) {
                                                    val inputStream = context.contentResolver.openInputStream(backupFile.uri)
                                                    if (inputStream != null) {
                                                        viewModel.importBackup(
                                                            context = context,
                                                            inputStream = inputStream,
                                                            onSuccess = {
                                                                android.widget.Toast.makeText(context, getLocalized("Copia de seguridad restaurada con éxito", "Backup restored successfully"), android.widget.Toast.LENGTH_LONG).show()
                                                                viewModel.connect()
                                                            },
                                                            onError = { error ->
                                                                android.widget.Toast.makeText(context, "${getLocalized("Error al restaurar:", "Failed to restore:")} ${error.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                                            }
                                                        )
                                                    }
                                                } else {
                                                    android.widget.Toast.makeText(context, getLocalized("No se encontró el archivo de copia 'kev_music_player_backup.json' en la carpeta.", "No 'kev_music_player_backup.json' backup file found in folder."), android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            } catch (e: Exception) {
                                                android.widget.Toast.makeText(context, "${getLocalized("Error de lectura:", "Read error:")} ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(getLocalized("Seleccionar archivo...", "Select file..."), color = settingsTextColor()) },
                                onClick = {
                                    showRestoreOptions = false
                                    openDocumentLauncher.launch(arrayOf("application/json"))
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.FileOpen, contentDescription = null, tint = settingsTextMutedColor())
                                }
                            )
                        }
                    }
                } // Closes the buttons Row (started at line 2473)

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = settingsDividerColor()
                )

                // Auto Backup Setting Selector
                var autoBackupInterval by remember {
                    mutableStateOf(settingsPrefs.getString("auto_backup_interval", "off") ?: "off")
                }
                var showAutoBackupMenu by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Copia de Seguridad Automática", "Automatic Backup"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = settingsTextColor()
                        )
                        Text(
                            text = getLocalized(
                                "Guarda automáticamente una copia al iniciar la app.",
                                "Saves a backup automatically when starting the app."
                            ),
                            fontSize = 11.sp,
                            color = settingsTextMutedColor()
                        )
                    }

                    Box {
                        OutlinedButton(
                            onClick = { showAutoBackupMenu = true },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, settingsDividerColor()),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = settingsTextColor()
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(
                                text = when (autoBackupInterval) {
                                    "daily" -> getLocalized("Diario", "Daily")
                                    "weekly" -> getLocalized("Semanal", "Weekly")
                                    "monthly" -> getLocalized("Mensual", "Monthly")
                                    else -> getLocalized("Desactivado", "Off")
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showAutoBackupMenu,
                            onDismissRequest = { showAutoBackupMenu = false },
                            containerColor = if (MaterialTheme.colorScheme.background == Color.White) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF1E213A)
                        ) {
                            DropdownMenuItem(
                                text = { Text(getLocalized("Desactivado", "Off"), color = settingsTextColor()) },
                                onClick = {
                                    autoBackupInterval = "off"
                                    settingsPrefs.edit().putString("auto_backup_interval", "off").apply()
                                    showAutoBackupMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(getLocalized("Diario", "Daily"), color = settingsTextColor()) },
                                onClick = {
                                    autoBackupInterval = "daily"
                                    settingsPrefs.edit().putString("auto_backup_interval", "daily").apply()
                                    showAutoBackupMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(getLocalized("Semanal", "Weekly"), color = settingsTextColor()) },
                                onClick = {
                                    autoBackupInterval = "weekly"
                                    settingsPrefs.edit().putString("auto_backup_interval", "weekly").apply()
                                    showAutoBackupMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(getLocalized("Mensual", "Monthly"), color = settingsTextColor()) },
                                onClick = {
                                    autoBackupInterval = "monthly"
                                    settingsPrefs.edit().putString("auto_backup_interval", "monthly").apply()
                                    showAutoBackupMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showExportCustomDialog) {
        AlertDialog(
            onDismissRequest = { showExportCustomDialog = false },
            title = {
                Text(
                    text = getLocalized("Personalizar Copia", "Customize Backup"),
                    color = settingsTextColor(),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = getLocalized(
                            "Selecciona qué elementos deseas incluir en este archivo de copia de seguridad:",
                            "Select which items you want to include in this backup file:"
                        ),
                        fontSize = 13.sp,
                        color = settingsTextMutedColor()
                    )

                    // 1. App Settings Option
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { exportSettings = !exportSettings },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Ajustes de la Aplicación", "App Settings"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = settingsTextColor()
                            )
                            Text(
                                text = getLocalized("Temas, idioma, visualización y directorios.", "Themes, language, layout, and directories."),
                                fontSize = 11.sp,
                                color = settingsTextMutedColor()
                            )
                        }
                        Switch(
                            checked = exportSettings,
                            onCheckedChange = { exportSettings = it }
                        )
                    }

                    // 2. Equalizer Option
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { exportEqualizer = !exportEqualizer },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Ajustes del Ecualizador", "Equalizer Settings"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = settingsTextColor()
                            )
                            Text(
                                text = getLocalized("Habilitación, bandas y presets personalizados.", "Enabled status, bands, and custom presets."),
                                fontSize = 11.sp,
                                color = settingsTextMutedColor()
                            )
                        }
                        Switch(
                            checked = exportEqualizer,
                            onCheckedChange = { exportEqualizer = it }
                        )
                    }

                    // 3. Playlists Option
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { exportPlaylists = !exportPlaylists },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Listas de Reproducción", "Playlists"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = settingsTextColor()
                            )
                            Text(
                                text = getLocalized("Tus listas de reproducción personalizadas e inteligentes.", "Your custom and smart playlists."),
                                fontSize = 11.sp,
                                color = settingsTextMutedColor()
                            )
                        }
                        Switch(
                            checked = exportPlaylists,
                            onCheckedChange = { exportPlaylists = it }
                        )
                    }

                    // 4. Saved Lyrics Option
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { exportLyrics = !exportLyrics },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Letras de Canciones", "Song Lyrics"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = settingsTextColor()
                            )
                            Text(
                                text = getLocalized("Todas las letras guardadas y traducidas localmente.", "All saved and locally translated lyrics."),
                                fontSize = 11.sp,
                                color = settingsTextMutedColor()
                            )
                        }
                        Switch(
                            checked = exportLyrics,
                            onCheckedChange = { exportLyrics = it }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportCustomDialog = false
                        if (useSameFolderForBackup) {
                            val currentMusicFolder = selectedMusicFolder
                            if (currentMusicFolder != null) {
                                val backupFile = java.io.File(currentMusicFolder, "kev_music_player_backup.json")
                                try {
                                    val outputStream = backupFile.outputStream()
                                    viewModel.exportBackup(
                                        context = context,
                                        outputStream = outputStream,
                                        includeSettings = exportSettings,
                                        includeEqualizer = exportEqualizer,
                                        includePlaylists = exportPlaylists,
                                        includeLyrics = exportLyrics,
                                        onSuccess = {
                                            android.widget.Toast.makeText(context, getLocalized("Copia de seguridad creada con éxito", "Backup created successfully"), android.widget.Toast.LENGTH_LONG).show()
                                        },
                                        onError = { error ->
                                            android.widget.Toast.makeText(context, "${getLocalized("Error al crear copia:", "Failed to create backup:")} ${error.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    )
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "${getLocalized("Error de archivo:", "File error:")} ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            val currentDirUri = backupDirUri
                            if (currentDirUri != null) {
                                performExportToFolder(currentDirUri, exportSettings, exportEqualizer, exportPlaylists, exportLyrics)
                            }
                        }
                    },
                    enabled = exportSettings || exportEqualizer || exportPlaylists || exportLyrics,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(getLocalized("Crear Copia", "Create Backup"), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportCustomDialog = false }) {
                    Text(getLocalized("Cancelar", "Cancel"), color = settingsTextMutedColor())
                }
            },
            containerColor = if (MaterialTheme.colorScheme.background == Color.White) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF1E213A)
        )
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Switch card for "Utilizar la misma carpeta"
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = settingsCardContainerColor()
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = getLocalized("Utilizar la misma carpeta", "Use same folder"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = settingsTextColor()
                )
                Text(
                    text = getLocalized(
                        "Guarda y restaura la copia de seguridad directamente en la raíz de tu carpeta de música.",
                        "Save and restore the backup directly in the root of your music folder."
                    ),
                    fontSize = 11.sp,
                    color = settingsTextMutedColor()
                )
            }
            Switch(
                checked = useSameFolderForBackup,
                onCheckedChange = { checked ->
                    if (checked && selectedMusicFolder == null) {
                        android.widget.Toast.makeText(context, getLocalized("Por favor, selecciona primero una carpeta de música específica abajo.", "Please select a specific music folder below first."), android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        useSameFolderForBackup = checked
                        settingsPrefs.edit().putBoolean("use_same_folder_for_backup", checked).apply()
                    }
                }
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    val selectMusicFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val path = getPhysicalPathFromTreeUri(context, uri)
            if (path != null) {
                settingsPrefs.edit().putString("music_folder_path", path).apply()
                selectedMusicFolder = path

                // Auto-detect existing backup in the selected music folder root
                val backupFile = java.io.File(path, "kev_music_player_backup.json")
                if (backupFile.exists()) {
                    useSameFolderForBackup = true
                    settingsPrefs.edit().putBoolean("use_same_folder_for_backup", true).apply()
                    android.widget.Toast.makeText(context, getLocalized("Copia de seguridad existente vinculada automáticamente", "Existing backup linked automatically"), android.widget.Toast.LENGTH_LONG).show()
                }

                android.widget.Toast.makeText(context, getLocalized("Carpeta de música establecida", "Music folder set"), android.widget.Toast.LENGTH_SHORT).show()
                onRescan()
            } else {
                android.widget.Toast.makeText(context, getLocalized("No se pudo obtener la ruta física de la carpeta", "Could not get physical path of the folder"), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    Column {
        Text(
            text = getLocalized("CARPETA DE MÚSICA ESPECÍFICA", "SPECIFIC MUSIC FOLDER"),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = getLocalized(
                        "Selecciona una carpeta para buscar música. Si se selecciona, solo se escaneará esta carpeta.",
                        "Select a folder to search for music. If selected, only this folder will be scanned."
                    ),
                    fontSize = 12.sp,
                    color = settingsTextMutedColor()
                )

                if (selectedMusicFolder != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(settingsDividerColor())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedMusicFolder!!.substringAfterLast("/"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = settingsTextColor()
                            )
                            Text(
                                text = selectedMusicFolder!!,
                                fontSize = 10.sp,
                                color = settingsTextMutedColor()
                            )
                        }
                        IconButton(
                            onClick = {
                                settingsPrefs.edit().remove("music_folder_path").apply()
                                selectedMusicFolder = null
                                if (useSameFolderForBackup) {
                                    useSameFolderForBackup = false
                                    settingsPrefs.edit().putBoolean("use_same_folder_for_backup", false).apply()
                                }
                                android.widget.Toast.makeText(context, getLocalized("Escaneo predeterminado restaurado", "Default scan restored"), android.widget.Toast.LENGTH_SHORT).show()
                                onRescan()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = { selectMusicFolderLauncher.launch(null) },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = getLocalized("Seleccionar Carpeta", "Select Folder"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    // 1. Visible Navigation Categories Section (Drag-to-Reorder)
    Column {
        Text(
            text = stringResource(R.string.library_categories_title),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                val allCategories = remember {
                    listOf(
                        CategoryItem("Songs", R.string.category_songs, R.string.category_songs_desc, Icons.Rounded.MusicNote),
                        CategoryItem("Albums", R.string.category_albums, R.string.category_albums_desc, Icons.Rounded.Album),
                        CategoryItem("Artists", R.string.category_artists, R.string.category_artists_desc, Icons.Rounded.Person),
                        CategoryItem("Genres", R.string.category_genres, R.string.category_genres_desc, Icons.Rounded.Category),
                        CategoryItem("Folders", R.string.category_folders, R.string.category_folders_desc, Icons.Rounded.Folder),
                        CategoryItem("Playlists", R.string.category_playlists, R.string.category_playlists_desc, Icons.AutoMirrored.Rounded.PlaylistPlay)
                    )
                }
                val categoryMap = remember(allCategories) { allCategories.associateBy { it.name } }
                val enabledOrder = remember { mutableStateListOf<String>() }

                LaunchedEffect(enabledTabs) {
                    if (enabledOrder.toList() != enabledTabs) {
                        enabledOrder.clear()
                        enabledOrder.addAll(enabledTabs)
                    }
                }

                val disabledOrder = remember(enabledOrder.toList(), allCategories) {
                    allCategories.map { it.name }.filter { it !in enabledOrder }
                }

                var draggingIndex by remember { mutableStateOf<Int?>(null) }
                var dragOffset by remember { mutableStateOf(0f) }
                var itemHeightPx by remember { mutableStateOf(0f) }

                Text(
                    text = stringResource(R.string.library_categories_drag_hint),
                    fontSize = 11.sp,
                    color = settingsTextMutedColor(),
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                )

                enabledOrder.forEachIndexed { index, name ->
                    key(name) {
                        val cat = categoryMap[name]
                        if (cat != null) {
                            val isDragging = draggingIndex == index
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { size ->
                                        if (itemHeightPx == 0f) {
                                            itemHeightPx = size.height.toFloat()
                                        }
                                    }
                                    .offset { IntOffset(0, if (isDragging) dragOffset.toInt() else 0) }
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (isDragging) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        else Color.Transparent
                                    )
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragStart = { 
                                                draggingIndex = enabledOrder.indexOf(name)
                                                dragOffset = 0f
                                            },
                                            onDragCancel = {
                                                draggingIndex = null
                                                dragOffset = 0f
                                            },
                                            onDragEnd = {
                                                draggingIndex = null
                                                dragOffset = 0f
                                                onEnabledTabsChanged(enabledOrder.toList())
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val currentIndex = enabledOrder.indexOf(name)
                                                if (currentIndex == -1 || itemHeightPx == 0f) return@detectDragGestures
                                                dragOffset += dragAmount.y
                                                val offsetIndexes = (dragOffset / itemHeightPx).roundToInt()
                                                if (offsetIndexes != 0) {
                                                    val targetIndex = (currentIndex + offsetIndexes).coerceIn(0, enabledOrder.lastIndex)
                                                    if (targetIndex != currentIndex) {
                                                        enabledOrder.removeAt(currentIndex)
                                                        enabledOrder.add(targetIndex, name)
                                                        draggingIndex = targetIndex
                                                        dragOffset -= offsetIndexes * itemHeightPx
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = cat.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(cat.labelRes),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = settingsTextColor()
                                    )
                                    Text(
                                        text = stringResource(cat.descRes),
                                        fontSize = 12.sp,
                                        color = settingsTextMutedColor()
                                    )
                                }
                                Switch(
                                    checked = true,
                                    onCheckedChange = { checked ->
                                        if (!checked && enabledOrder.size > 1) {
                                            enabledOrder.remove(name)
                                            onEnabledTabsChanged(enabledOrder.toList())
                                        }
                                    }
                                )
                            }
                            HorizontalDivider(
                                color = settingsDividerColor(),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }

                if (disabledOrder.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.library_categories_disabled),
                        fontSize = 11.sp,
                        color = settingsTextMutedColor(),
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
                    )
                }

                disabledOrder.forEachIndexed { index, name ->
                    val cat = categoryMap[name] ?: return@forEachIndexed
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = cat.icon,
                            contentDescription = null,
                            tint = settingsTextMutedColor(),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(cat.labelRes),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = settingsTextColor()
                            )
                            Text(
                                text = stringResource(cat.descRes),
                                fontSize = 12.sp,
                                color = settingsTextMutedColor()
                            )
                        }
                        Switch(
                            checked = false,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    enabledOrder.add(name)
                                    onEnabledTabsChanged(enabledOrder.toList())
                                }
                            }
                        )
                    }
                    if (index < disabledOrder.size - 1) {
                        HorizontalDivider(
                            color = settingsDividerColor(),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AboutSettingsSection(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    getLocalized: (String, String) -> String
) {
    // Dialog for Update Status
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateDialogTitle by remember { mutableStateOf("") }
    var updateDialogMessage by remember { mutableStateOf("") }
    var updateDownloadUrl by remember { mutableStateOf<String?>(null) }
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }

    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
    }
    val versionName = remember(packageInfo) { packageInfo?.versionName ?: "1.0.2" }
    val isDebug = remember { (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    val buildTypeText = remember(isDebug) {
        if (isDebug) "Debug" else getLocalized("Estable", "Stable")
    }

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDownloading) showUpdateDialog = false },
            title = { Text(text = updateDialogTitle, fontWeight = FontWeight.Bold, color = settingsTextColor()) },
            text = {
                Column {
                    Text(text = updateDialogMessage, color = settingsTextColor().copy(alpha = 0.8f))
                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = settingsTextColor().copy(alpha = 0.1f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = getLocalized(
                                "Descargando: ${(downloadProgress * 100).toInt()}%",
                                "Downloading: ${(downloadProgress * 100).toInt()}%"
                            ),
                            color = settingsTextMutedColor(),
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                if (!isDownloading) {
                    TextButton(
                        onClick = {
                            val url = updateDownloadUrl
                            if (url != null) {
                                if (url.endsWith(".apk") || url.contains("/releases/download/")) {
                                    isDownloading = true
                                    downloadProgress = 0f
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val client = okhttp3.OkHttpClient()
                                            val request = okhttp3.Request.Builder().url(url).build()
                                            client.newCall(request).execute().use { response ->
                                                if (!response.isSuccessful) {
                                                    throw java.io.IOException("HTTP Error: ${response.code}")
                                                }
                                                val body = response.body ?: throw java.io.IOException("Empty body")
                                                val totalBytes = body.contentLength()
                                                val apkFile = java.io.File(context.cacheDir, "update.apk")
                                                if (apkFile.exists()) apkFile.delete()
                                                
                                                body.byteStream().use { inputStream ->
                                                    java.io.FileOutputStream(apkFile).use { outputStream ->
                                                        val buffer = ByteArray(8192)
                                                        var bytesRead: Int
                                                        var totalBytesRead = 0L
                                                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                                            outputStream.write(buffer, 0, bytesRead)
                                                            totalBytesRead += bytesRead
                                                            if (totalBytes > 0) {
                                                                withContext(Dispatchers.Main) {
                                                                    downloadProgress = totalBytesRead.toFloat() / totalBytes
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                
                                                withContext(Dispatchers.Main) {
                                                    isDownloading = false
                                                    showUpdateDialog = false
                                                    val authority = "${context.packageName}.fileprovider"
                                                    val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, apkFile)
                                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                                        setDataAndType(uri, "application/vnd.android.package-archive")
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(intent)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            withContext(Dispatchers.Main) {
                                                isDownloading = false
                                                updateDialogTitle = getLocalized("Error de descarga", "Download Error")
                                                updateDialogMessage = getLocalized(
                                                    "No se pudo descargar la actualización: ${e.localizedMessage}",
                                                    "Failed to download update: ${e.localizedMessage}"
                                                )
                                                updateDownloadUrl = null 
                                            }
                                        }
                                    }
                                } else {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                    showUpdateDialog = false
                                }
                            } else {
                                showUpdateDialog = false
                            }
                        }
                    ) {
                        Text(
                            text = if (updateDownloadUrl != null) getLocalized("Descargar", "Download") else getLocalized("Aceptar", "OK"),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            dismissButton = {
                if (updateDownloadUrl != null && !isDownloading) {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text(text = getLocalized("Cancelar", "Cancel"), color = settingsTextMutedColor())
                    }
                }
            },
            containerColor = if (MaterialTheme.colorScheme.background == Color.White) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF1E2135),
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Acerca de la Aplicación & Actualizaciones (About & GitHub Updates Card)
    Column {
        Text(
            text = getLocalized("INFORMACIÓN Y ACTUALIZACIONES", "ABOUT & UPDATES"),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Custom Brand Logo Indicator
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF0A1E24),
                                    Color(0xFF00E5FF)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Kev Music Player",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = settingsTextColor()
                )

                Text(
                    text = getLocalized("Versión v$versionName ($buildTypeText)", "Version v$versionName ($buildTypeText)"),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = getLocalized(
                        "Un reproductor de música minimalista, rápido y optimizado para una navegación fluida a 120Hz.",
                        "A minimalist, fast, and optimized music player designed for fluid 120Hz navigation."
                    ),
                    fontSize = 12.sp,
                    color = settingsTextMutedColor(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // GitHub Update Button
                Button(
                    onClick = {
                        if (!isCheckingUpdates) {
                            scope.launch {
                                isCheckingUpdates = true
                                withContext(Dispatchers.IO) {
                                    try {
                                        val client = okhttp3.OkHttpClient()
                                        val request = okhttp3.Request.Builder()
                                            .url("https://api.github.com/repos/KevshuppD/KevMusicPlayer/releases/latest")
                                            .header("User-Agent", "KevMusicPlayer")
                                            .build()
                                        client.newCall(request).execute().use { response ->
                                            val body = response.body?.string() ?: ""
                                            val json = org.json.JSONObject(body)
                                            val latestTag = json.optString("tag_name", "1.0")
                                            val htmlUrl = json.optString("html_url", "https://github.com/KevshuppD/KevMusicPlayer")
                                            
                                            // Parse assets to find APK
                                            val assets = json.optJSONArray("assets")
                                            var apkUrl: String? = null
                                            if (assets != null) {
                                                for (i in 0 until assets.length()) {
                                                    val asset = assets.optJSONObject(i)
                                                    if (asset != null) {
                                                        val name = asset.optString("name", "")
                                                        if (name.endsWith(".apk")) {
                                                            val browserUrl = asset.optString("browser_download_url")
                                                            if (browserUrl.isNotEmpty()) {
                                                                apkUrl = browserUrl
                                                                break
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            val downloadUrl = apkUrl ?: htmlUrl

                                            withContext(Dispatchers.Main) {
                                                val cleanLatest = latestTag.replace(Regex("[^0-9.]"), "")
                                                val cleanCurrent = versionName.replace(Regex("[^0-9.]"), "")
                                                val latestParts = cleanLatest.split(".")
                                                val currentParts = cleanCurrent.split(".")
                                                var isNewer = false
                                                for (i in 0 until minOf(latestParts.size, currentParts.size)) {
                                                    val l = latestParts[i].toIntOrNull() ?: 0
                                                    val c = currentParts[i].toIntOrNull() ?: 0
                                                    if (l > c) {
                                                        isNewer = true
                                                        break
                                                    } else if (l < c) {
                                                        break
                                                    }
                                                }
                                                if (!isNewer && latestParts.size > currentParts.size) {
                                                    isNewer = true
                                                }

                                                if (isNewer) {
                                                    updateDialogTitle = getLocalized("¡Nueva versión disponible!", "Update Available!")
                                                    updateDialogMessage = getLocalized(
                                                        "Una versión más reciente (${latestTag}) está disponible en GitHub. ¿Deseas descargarla?",
                                                        "A newer version (${latestTag}) is available on GitHub. Do you want to download it?"
                                                    )
                                                    updateDownloadUrl = downloadUrl
                                                } else {
                                                    updateDialogTitle = getLocalized("Aplicación al Día", "App Up to Date")
                                                    updateDialogMessage = getLocalized(
                                                        "¡Felicidades! Ya estás usando la versión más reciente (v$versionName).",
                                                        "Congratulations! You are already running the newest version (v$versionName)."
                                                    )
                                                    updateDownloadUrl = null
                                                }
                                                showUpdateDialog = true
                                            }
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        withContext(Dispatchers.Main) {
                                            updateDialogTitle = getLocalized("Buscar Actualizaciones", "Check for Updates")
                                            updateDialogMessage = getLocalized(
                                                "No se pudo conectar a GitHub Releases. Si es la primera versión, estás al día (v$versionName).",
                                                "Could not connect to GitHub Releases. If this is the initial version, you are up to date (v$versionName)."
                                            )
                                            updateDownloadUrl = null
                                            showUpdateDialog = true
                                        }
                                    } finally {
                                        isCheckingUpdates = false
                                    }
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    if (isCheckingUpdates) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.CloudDownload,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = getLocalized("Buscar Actualizaciones", "Check for Updates"),
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Developer GitHub Link Button
                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/KevshuppD"))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, settingsDividerColor()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = settingsTextColor(),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("GitHub del Desarrollador", "Developer GitHub Profile"),
                        fontWeight = FontWeight.Bold,
                        color = settingsTextColor()
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                HorizontalDivider(color = settingsDividerColor())

                Spacer(modifier = Modifier.height(12.dp))

                // Used libraries tag listing
                Text(
                    text = getLocalized("Tecnologías Utilizadas", "Libraries & Frameworks"),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = settingsTextMutedColor(),
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(8.dp))

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Compose M3", "Media3 ExoPlayer", "Room DB", "Jaudiotagger", "OkHttp", "Coil").forEach { library ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(settingsDividerColor())
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = library,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = settingsTextMutedColor()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AudioSettingsSection(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    getLocalized: (String, String) -> String
) {
    val eqPrefs = remember { context.getSharedPreferences("equalizer_prefs", android.content.Context.MODE_PRIVATE) }
    val settingsPrefs = remember { context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE) }

    var eqEnabled by remember { mutableStateOf(eqPrefs.getBoolean("eq_enabled", false)) }
    var bbEnabled by remember { mutableStateOf(eqPrefs.getBoolean("bb_enabled", false)) }
    var bbStrength by remember { mutableStateOf(eqPrefs.getInt("bb_strength", 0)) }
    var virtEnabled by remember { mutableStateOf(eqPrefs.getBoolean("virt_enabled", false)) }
    var virtStrength by remember { mutableStateOf(eqPrefs.getInt("virt_strength", 0)) }

    val eqBands = remember {
        val bandsStr = eqPrefs.getString("eq_bands", "0,0,0,0,0") ?: "0,0,0,0,0"
        val initialList = bandsStr.split(",").map { it.toInt() / 100f }
        val list = mutableStateListOf<Float>()
        for (i in 0 until 5) {
            list.add(initialList.getOrNull(i) ?: 0f)
        }
        list
    }

    var selectedPreset by remember { mutableStateOf(eqPrefs.getString("eq_preset", "Flat") ?: "Flat") }

    val presets = remember {
        listOf(
            Triple("Flat", getLocalized("Plano", "Flat"), listOf(0f, 0f, 0f, 0f, 0f)),
            Triple("Classical", getLocalized("Clásica", "Classical"), listOf(4f, 3f, -2f, 3f, 4f)),
            Triple("Dance", getLocalized("Dance", "Dance"), listOf(5f, 4f, 1f, 3f, 0f)),
            Triple("Heavy Metal", getLocalized("Heavy Metal", "Heavy Metal"), listOf(4f, 2f, -1f, 3f, 1f)),
            Triple("Hip Hop", getLocalized("Hip Hop", "Hip Hop"), listOf(5f, 3f, 0f, 1f, 3f)),
            Triple("Jazz", getLocalized("Jazz", "Jazz"), listOf(4f, 2f, -3f, 2f, 4f)),
            Triple("Pop", getLocalized("Pop", "Pop"), listOf(-1f, 1f, 3f, 2f, -1f)),
            Triple("Rock", getLocalized("Rock", "Rock"), listOf(5f, 3f, -2f, 4f, 5f)),
            Triple("Bass", getLocalized("Graves", "Bass"), listOf(6f, 4f, 0f, 0f, 0f)),
            Triple("Vocal", getLocalized("Voz", "Vocal"), listOf(-2f, -1f, 3f, 4f, 1f))
        )
    }

    var crossfade by remember { mutableStateOf(settingsPrefs.getInt("crossfade_duration", 0)) }

    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 0. Audio Normalization Card
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Text(
                text = getLocalized("NORMALIZACIÓN DE AUDIO", "AUDIO NORMALIZATION"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
            var normalizeEnabled by remember { mutableStateOf(settingsPrefs.getBoolean("normalize_sound", false)) }
            Switch(
                checked = normalizeEnabled,
                onCheckedChange = {
                    normalizeEnabled = it
                    settingsPrefs.edit().putBoolean("normalize_sound", it).apply()
                }
            )
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Normalizar Volumen", "Normalize Volume"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = settingsTextColor()
                        )
                        Text(
                            text = getLocalized(
                                "Mantiene un nivel de volumen constante y uniforme entre canciones",
                                "Keeps a consistent and uniform volume level across all tracks"
                            ),
                            fontSize = 12.sp,
                            color = settingsTextMutedColor()
                        )
                    }
                }
            }
        }

        // 1. Crossfade & Gapless Card
        Text(
            text = getLocalized("REPRODUCCIÓN ININTERRUMPIDA", "SEAMLESS PLAYBACK"),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
        
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ShuffleOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Transición Cruzada (Crossfade)", "Crossfade Transition"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = settingsTextColor()
                        )
                        Text(
                            text = if (crossfade > 0) {
                                getLocalized("Fundido de $crossfade segundos entre canciones", "Fades $crossfade seconds between tracks")
                            } else {
                                getLocalized("Desactivado (cambio abrupto)", "Disabled (abrupt track change)")
                            },
                            fontSize = 12.sp,
                            color = settingsTextMutedColor()
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Slider(
                    value = crossfade.toFloat(),
                    onValueChange = {
                        crossfade = it.toInt()
                    },
                    onValueChangeFinished = {
                        settingsPrefs.edit().putInt("crossfade_duration", crossfade).apply()
                    },
                    valueRange = 0f..10f,
                    steps = 9,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = settingsTextColor().copy(alpha = 0.1f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 2. Equalizer 5 Bands Card
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Text(
                text = getLocalized("ECUALIZADOR GRÁFICO (5 BANDAS)", "GRAPHIC EQUALIZER (5 BANDS)"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
            Switch(
                checked = eqEnabled,
                onCheckedChange = {
                    eqEnabled = it
                    eqPrefs.edit().putBoolean("eq_enabled", it).apply()
                }
            )
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Equalizer Presets Row
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(presets.size, key = { index -> presets[index].first }) { index ->
                        val (tag, name, values) = presets[index]
                        val isPresetSelected = selectedPreset == tag
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isPresetSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                )
                                .clickable(enabled = eqEnabled) {
                                    selectedPreset = tag
                                    eqPrefs.edit().putString("eq_preset", tag).apply()
                                    // Update bands
                                    for (i in 0 until 5) {
                                        eqBands[i] = values[i]
                                    }
                                    val bandsStr = eqBands.map { (it * 100).toInt() }.joinToString(",")
                                    eqPrefs.edit().putString("eq_bands", bandsStr).apply()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = name,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isPresetSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                    // Custom option if selected manually
                    if (selectedPreset == "Custom") {
                        item {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = getLocalized("Personalizado", "Custom"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val bandLabels = listOf("60 Hz", "230 Hz", "910 Hz", "4 kHz", "14 kHz")
                    eqBands.forEachIndexed { idx, dbValue ->
                        VerticalFader(
                            value = dbValue,
                            onValueChange = { newValue ->
                                eqBands[idx] = newValue
                                selectedPreset = "Custom"
                            },
                            onValueChangeFinished = {
                                eqPrefs.edit().putString("eq_preset", "Custom").apply()
                                val bandsStr = eqBands.map { (it * 100).toInt() }.joinToString(",")
                                eqPrefs.edit().putString("eq_bands", bandsStr).apply()
                            },
                            label = bandLabels.getOrNull(idx) ?: "${idx + 1}",
                            enabled = eqEnabled,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 3. Audio Enhancements Card
        Text(
            text = getLocalized("MEJORAS DE AUDIO", "AUDIO ENHANCEMENTS"),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp)
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = settingsCardContainerColor()
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = getLocalized("Graves", "Bass Boost"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = settingsTextColor()
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = bbEnabled,
                            onCheckedChange = {
                                bbEnabled = it
                                eqPrefs.edit().putBoolean("bb_enabled", it).apply()
                            },
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularSlider(
                        value = bbStrength / 1000f,
                        onValueChange = {
                            bbStrength = (it * 1000).toInt()
                        },
                        onValueChangeFinished = {
                            eqPrefs.edit().putInt("bb_strength", bbStrength).apply()
                        },
                        enabled = bbEnabled,
                        label = getLocalized("Intensidad", "Strength"),
                        modifier = Modifier.size(90.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .height(120.dp)
                        .width(1.dp)
                        .background(settingsDividerColor())
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = getLocalized("Virtual 3D", "3D Virtualizer"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = settingsTextColor()
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = virtEnabled,
                            onCheckedChange = {
                                virtEnabled = it
                                eqPrefs.edit().putBoolean("virt_enabled", it).apply()
                            },
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularSlider(
                        value = virtStrength / 1000f,
                        onValueChange = {
                            virtStrength = (it * 1000).toInt()
                        },
                        onValueChangeFinished = {
                            eqPrefs.edit().putInt("virt_strength", virtStrength).apply()
                        },
                        enabled = virtEnabled,
                        label = getLocalized("Espacial", "Spacial"),
                        modifier = Modifier.size(90.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CircularSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = "",
    valueSuffix: String = "%",
    onValueChangeFinished: (() -> Unit)? = null
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        var width by remember { mutableStateOf(0) }
        var height by remember { mutableStateOf(0) }
        
        val activeColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
        val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        val handleColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        val textColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    width = it.width
                    height = it.height
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragEnd = { onValueChangeFinished?.invoke() },
                        onDragCancel = { onValueChangeFinished?.invoke() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val cx = width / 2f
                            val cy = height / 2f
                            val touchX = change.position.x
                            val touchY = change.position.y
                            
                            var angle = Math.toDegrees(atan2(touchY - cy, touchX - cx).toDouble()).toFloat()
                            if (angle < 0) {
                                angle += 360f
                            }
                            
                            var relativeAngle = angle - 135f
                            if (relativeAngle < 0) {
                                relativeAngle += 360f
                            }
                            
                            val newValue = when {
                                relativeAngle <= 270f -> relativeAngle / 270f
                                relativeAngle < 315f -> 1f
                                else -> 0f
                            }
                            onValueChange(newValue)
                        }
                    )
                }
        ) {
            val radius = minOf(this.size.width, this.size.height) / 2f - 8.dp.toPx()
            val center = androidx.compose.ui.geometry.Offset(this.size.width / 2f, this.size.height / 2f)
            
            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
            
            drawArc(
                color = activeColor,
                startAngle = 135f,
                sweepAngle = 270f * value,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
            
            val handleAngle = (135f + 270f * value) * (PI / 180f)
            val handleX = center.x + radius * cos(handleAngle).toFloat()
            val handleY = center.y + radius * sin(handleAngle).toFloat()
            
            drawCircle(
                color = handleColor,
                radius = 8.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(handleX, handleY)
            )
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${(value * 100).toInt()}$valueSuffix",
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                color = textColor
            )
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    color = labelColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun VerticalFader(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        val textColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        val activeColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
        val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        val handleColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

        Text(
            text = "${if (value > 0) "+" else ""}${value.toInt()}dB",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        var height by remember { mutableStateOf(0) }
        
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(150.dp)
                .onSizeChanged { height = it.height }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragEnd = { onValueChangeFinished?.invoke() },
                        onDragCancel = { onValueChangeFinished?.invoke() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val touchY = change.position.y
                            val progress = 1f - (touchY / height).coerceIn(0f, 1f)
                            val newValue = -15f + progress * 30f
                            onValueChange(newValue)
                        }
                    )
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = this.size.width / 2f
                drawLine(
                    color = trackColor,
                    start = androidx.compose.ui.geometry.Offset(cx, 0f),
                    end = androidx.compose.ui.geometry.Offset(cx, this.size.height),
                    strokeWidth = 6.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                
                val zeroY = this.size.height / 2f
                val progress = (value + 15f) / 30f
                val handleY = this.size.height * (1f - progress)
                
                drawLine(
                    color = activeColor,
                    start = androidx.compose.ui.geometry.Offset(cx, zeroY),
                    end = androidx.compose.ui.geometry.Offset(cx, handleY),
                    strokeWidth = 6.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                
                drawCircle(
                    color = handleColor,
                    radius = 8.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(cx, handleY)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = label,
            fontSize = 11.sp,
            color = labelColor,
            fontWeight = FontWeight.Bold
        )
    }
}

fun getPhysicalPathFromTreeUri(context: android.content.Context, treeUri: android.net.Uri): String? {
    val docId = try {
        androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)?.uri?.let { uri ->
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } ?: return null
    
    val split = docId.split(":")
    val type = split.getOrNull(0)
    val relativePath = split.getOrNull(1) ?: ""
    
    return if ("primary".equals(type, ignoreCase = true)) {
        val base = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (relativePath.isNotEmpty()) "$base/$relativePath" else base
    } else {
        val extStorages = context.getExternalFilesDirs(null)
        var path: String? = null
        for (file in extStorages) {
            if (file != null) {
                val absolutePath = file.absolutePath
                val index = absolutePath.indexOf("/Android/data/")
                if (index != -1) {
                    val root = absolutePath.substring(0, index)
                    if (type != null && root.contains(type)) {
                        path = if (relativePath.isNotEmpty()) "$root/$relativePath" else root
                        break
                    }
                }
            }
        }
        path ?: "/storage/$type/$relativePath"
    }
}

data class MockSongItem(
    val title: String,
    val artist: String,
    val colors: List<androidx.compose.ui.graphics.Color>
)
