package com.kevshupp.kevmusicplayer.ui.screens.settings

import com.kevshupp.kevmusicplayer.ui.screens.*

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.R
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import com.kevshupp.kevmusicplayer.data.AudioFile
import kotlinx.coroutines.*
import kotlin.math.*
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

