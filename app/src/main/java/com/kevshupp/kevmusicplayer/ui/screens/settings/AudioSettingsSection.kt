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

