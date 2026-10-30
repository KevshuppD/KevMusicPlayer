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
/**
 * Every value a performance profile sets. The profile cards, the option chips below them and the detection of
 * the active profile all read this table, so a profile can only use values that exist among the options.
 */
private data class PerformancePreset(
    val refreshRate: String,
    val disableAnimations: Boolean,
    val haptics: Boolean,
    val preloadCount: Int,
    val cacheCapacity: Int,
    val artResolution: Int,
    val diskCacheQuality: Int,
    val queueLimit: Int
)

private val preloadOptions = listOf(0, 3, 5, 10)
private val cacheOptions = listOf(50, 150, 300)
private val resolutionOptions = listOf(250, 500, 800)
private val qualityOptions = listOf(70, 85, 100)
private val queueLimitOptions = listOf(500, 1000, 1500)

private val performancePresets = linkedMapOf(
    "max" to PerformancePreset("120", false, true, 5, 300, 500, 85, 1500),
    "turbo" to PerformancePreset("120", true, false, 3, 150, 500, 70, 1500),
    "balanced" to PerformancePreset("90", false, true, 3, 150, 500, 85, 1000),
    "battery" to PerformancePreset("60", true, false, 0, 50, 250, 70, 500)
)

@Composable
fun PerformanceSettingsSection(
    selectedRefreshRate: String,
    onRefreshRateSelected: (String) -> Unit,
    disableAnimations: Boolean,
    onDisableAnimationsChanged: (Boolean) -> Unit,
    getLocalized: (String, String) -> String,
    settingsPrefs: android.content.SharedPreferences,
    context: android.content.Context
) {
    // Values saved by older versions may not be one of the choices offered below (old profiles used e.g. 2 tracks
    // or 400 px): they are moved to the nearest choice so every row always shows what is selected.
    fun snappedPref(key: String, default: Int, options: List<Int>): Int {
        val stored = settingsPrefs.getInt(key, default)
        val snapped = options.minByOrNull { kotlin.math.abs(it - stored) } ?: default
        if (snapped != stored) settingsPrefs.edit().putInt(key, snapped).apply()
        return snapped
    }
    var preloadCount by remember { mutableStateOf(snappedPref("preload_art_count", 5, preloadOptions)) }
    var cacheCapacity by remember { mutableStateOf(snappedPref("cover_cache_capacity", 150, cacheOptions)) }
    var artResolution by remember { mutableStateOf(snappedPref("art_resolution", 500, resolutionOptions)) }
    var diskCacheQuality by remember { mutableStateOf(snappedPref("disk_cache_quality", 85, qualityOptions)) }
    var hapticEnabled by remember { mutableStateOf(settingsPrefs.getBoolean("haptic_feedback_enabled", true)) }
    var ipcQueueLimit by remember { mutableStateOf(snappedPref("ipc_queue_limit", 1500, queueLimitOptions)) }
    var autoCleanTemp by remember { mutableStateOf(settingsPrefs.getBoolean("auto_clean_temp", true)) }

    // The active profile is whichever preset matches the values on screen, never a separately stored label:
    // that way the highlighted card and the options below can not disagree.
    val currentValues = PerformancePreset(
        refreshRate = selectedRefreshRate,
        disableAnimations = disableAnimations,
        haptics = hapticEnabled,
        preloadCount = preloadCount,
        cacheCapacity = cacheCapacity,
        artResolution = artResolution,
        diskCacheQuality = diskCacheQuality,
        queueLimit = ipcQueueLimit
    )
    val performanceProfile = performancePresets.entries.firstOrNull { it.value == currentValues }?.key ?: "custom"
    LaunchedEffect(performanceProfile) {
        if (settingsPrefs.getString("performance_profile", null) != performanceProfile) {
            settingsPrefs.edit().putString("performance_profile", performanceProfile).apply()
        }
    }

    fun applyProfile(profileKey: String) {
        val preset = performancePresets[profileKey] ?: return
        val artChanged = preset.artResolution != artResolution || preset.diskCacheQuality != diskCacheQuality

        onRefreshRateSelected(preset.refreshRate)
        onDisableAnimationsChanged(preset.disableAnimations)
        hapticEnabled = preset.haptics
        preloadCount = preset.preloadCount
        cacheCapacity = preset.cacheCapacity
        artResolution = preset.artResolution
        diskCacheQuality = preset.diskCacheQuality
        ipcQueueLimit = preset.queueLimit

        settingsPrefs.edit()
            .putString("refresh_rate", preset.refreshRate)
            .putBoolean("disable_animations", preset.disableAnimations)
            .putBoolean("haptic_feedback_enabled", preset.haptics)
            .putInt("preload_art_count", preset.preloadCount)
            .putInt("cover_cache_capacity", preset.cacheCapacity)
            .putInt("art_resolution", preset.artResolution)
            .putInt("disk_cache_quality", preset.diskCacheQuality)
            .putInt("ipc_queue_limit", preset.queueLimit)
            .apply()
        updateAlbumArtCacheSize(preset.cacheCapacity)

        // Stored covers only need rebuilding when their size or quality actually changes
        if (artChanged) {
            albumArtCache.evictAll()
            clearDiskAlbumArtCache(context)
            albumArtVersion++
        }
    }

    fun presetSummary(preset: PerformancePreset): String {
        val yes = getLocalized("sí", "on")
        val no = getLocalized("no", "off")
        return listOf(
            "${preset.refreshRate} Hz",
            getLocalized("Animaciones", "Animations") + " " + (if (preset.disableAnimations) no else yes),
            getLocalized("Vibración", "Haptics") + " " + (if (preset.haptics) yes else no),
            getLocalized("Precarga", "Preload") + " " + preset.preloadCount,
            getLocalized("Caché", "Cache") + " " + preset.cacheCapacity,
            getLocalized("Carátulas", "Artwork") + " ${preset.artResolution} px",
            "WebP ${preset.diskCacheQuality}%",
            getLocalized("Cola", "Queue") + " " + preset.queueLimit
        ).joinToString("  ·  ")
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // CARD 0: PERFIL DE RENDIMIENTO (Performance Profile Selector)
        Column {
            Text(
                text = getLocalized("PERFIL DE RENDIMIENTO", "PERFORMANCE PROFILE"),
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = getLocalized(
                            "Selecciona un perfil predeterminado o personaliza los parámetros manualmente.",
                            "Choose a preset performance profile or customize settings manually."
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    data class ProfileItem(
                        val key: String,
                        val title: String,
                        val desc: String
                    )

                    val profiles = listOf(
                        ProfileItem(
                            "max",
                            "⚡ " + getLocalized("Máxima Fidelidad", "Max Fidelity"),
                            getLocalized("Todo activado: animaciones completas, más precarga y la caché más grande.", "Everything on: full animations, more preloading and the largest cache.")
                        ),
                        ProfileItem(
                            "turbo",
                            "🚀 " + getLocalized("Ultra Rápido", "Ultra Fast"),
                            getLocalized("120 Hz sin animaciones ni vibración, con carátulas más comprimidas en disco.", "120 Hz without animations or haptics, with more compressed artwork on disk.")
                        ),
                        ProfileItem(
                            "balanced",
                            "⚖️ " + getLocalized("Equilibrado", "Balanced"),
                            getLocalized("90 Hz con animaciones, precarga moderada y una cola más corta.", "90 Hz with animations, moderate preloading and a shorter queue.")
                        ),
                        ProfileItem(
                            "battery",
                            "🔋 " + getLocalized("Ahorro de Batería", "Battery Saver"),
                            getLocalized("60 Hz, sin animaciones ni precarga, carátulas pequeñas y caché mínima.", "60 Hz, no animations or preloading, small artwork and minimal cache.")
                        ),
                        ProfileItem(
                            "custom",
                            "⚙️ " + getLocalized("Personalizado", "Custom"),
                            getLocalized("Se activa solo cuando cambias cualquier opción de abajo.", "Turns on by itself when you change any option below.")
                        )
                    )

                    profiles.forEach { item ->
                        val isSelected = performanceProfile == item.key
                        Surface(
                            onClick = { applyProfile(item.key) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            border = BorderStroke(1.5.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(14.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = item.title,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = item.desc,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                    )
                                    Spacer(modifier = Modifier.height(5.dp))
                                    // Exactly what this profile sets (for Custom, the values currently in use)
                                    Text(
                                        text = presetSummary(performancePresets[item.key] ?: currentValues),
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { applyProfile(item.key) },
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }
                }
            }
        }
        // CARD 1: PANTALLA Y RENDIMIENTO GRÁFICO (Display & Graphics Card)
        Column {
            Text(
                text = getLocalized("RENDIMIENTO GRÁFICO Y PANTALLA", "GRAPHICS & DISPLAY PERFORMANCE"),
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                    "Forzar tasa de refresco (60Hz, 90Hz o 120Hz según soporte del dispositivo)",
                                    "Enforce refresh rate (60Hz, 90Hz or 120Hz based on device support)"
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("60", "90", "120").forEach { rate ->
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

                    // 3. Haptic Feedback Toggle
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
                                imageVector = Icons.Rounded.Vibration,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Respuesta Háptica en Desplazamiento", "Haptic Scroll Feedback"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = getLocalized(
                                    "Vibración táctil al usar el índice A-Z (desactivar economiza motor táctil)",
                                    "Tactile vibration on fast indexer scroll (disable to save haptic motor power)"
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Switch(
                            checked = hapticEnabled,
                            onCheckedChange = { checked ->
                                hapticEnabled = checked
                                settingsPrefs.edit().putBoolean("haptic_feedback_enabled", checked).apply()
                            }
                        )
                    }
                }
            }
        }

        // CARD 2: RECURSOS Y CACHÉ (Resources & Cache Card)
        Column {
            Text(
                text = getLocalized("RENDIMIENTO DE MEMORIA Y RECURSOS", "MEMORY & RESOURCES PERFORMANCE"),
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Artwork Preloading Toggle
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
                                imageVector = Icons.Rounded.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Precarga de Carátulas", "Artwork Preloading"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = getLocalized(
                                    "Precarga portadas de las siguientes canciones en cola para transiciones instantáneas",
                                    "Preload covers of upcoming songs in queue for instantaneous transitions"
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            preloadOptions.forEach { count ->
                                val isSelected = preloadCount == count
                                val label = if (count == 0) getLocalized("Off", "Off") else "$count"
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary 
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        )
                                        .clickable {
                                            preloadCount = count
                                            settingsPrefs.edit().putInt("preload_art_count", count).apply()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

                    // 2. Cache Capacity Option
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
                                imageVector = Icons.Rounded.SdStorage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Capacidad de Caché RAM", "RAM Cache Capacity"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = getLocalized(
                                    "Número máximo de portadas guardadas en RAM (más caché = scroll más rápido)",
                                    "Maximum number of album arts kept in RAM (more cache = faster list scrolling)"
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            cacheOptions.forEach { cap ->
                                val isSelected = cacheCapacity == cap
                                val label = when (cap) {
                                    50 -> getLocalized("Baja", "Low")
                                    150 -> getLocalized("Med", "Med")
                                    else -> getLocalized("Alta", "High")
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary 
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        )
                                        .clickable {
                                            cacheCapacity = cap
                                            settingsPrefs.edit().putInt("cover_cache_capacity", cap).apply()
                                            updateAlbumArtCacheSize(cap)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "$cap ($label)",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

                    // 3. Decoding Cover Quality Option
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
                                imageVector = Icons.Rounded.HighQuality,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Calidad de Carátulas", "Artwork Quality"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = getLocalized(
                                    "Resolución de decodificación (baja resolución ahorra RAM y CPU significativamente)",
                                    "Decoding resolution (lower resolution saves significant RAM and CPU usage)"
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            resolutionOptions.forEach { res ->
                                val isSelected = artResolution == res
                                val label = when (res) {
                                    250 -> getLocalized("250p", "250p")
                                    500 -> getLocalized("500p", "500p")
                                    else -> getLocalized("800p", "800p")
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary 
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        )
                                        .clickable {
                                            artResolution = res
                                            settingsPrefs.edit().putInt("art_resolution", res).apply()
                                            albumArtCache.evictAll()
                                            clearDiskAlbumArtCache(context)
                                            albumArtVersion++
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

                    // 4. Disk Thumbnail Compression Quality Option
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
                                imageVector = Icons.Rounded.Compress,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Compresión de Caché en Disco", "Disk Cache Compression"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = getLocalized(
                                    "Compresión WebP para miniaturas (calidad rápida ahorra lectura en almacenamiento)",
                                    "WebP thumbnail compression (faster quality reduces storage read overhead)"
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            qualityOptions.forEach { qual ->
                                val isSelected = diskCacheQuality == qual
                                val label = when (qual) {
                                    70 -> getLocalized("Rápida", "Fast")
                                    85 -> getLocalized("Norm", "Norm")
                                    else -> getLocalized("Max", "Max")
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary 
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        )
                                        .clickable {
                                            diskCacheQuality = qual
                                            settingsPrefs.edit().putInt("disk_cache_quality", qual).apply()
                                            albumArtCache.evictAll()
                                            clearDiskAlbumArtCache(context)
                                            albumArtVersion++
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

                    // 5. Disk Thumbnail Cache Cleanup
                    var diskCacheBytes by remember(albumArtVersion) { mutableLongStateOf(0L) }
                    LaunchedEffect(albumArtVersion) {
                        diskCacheBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            getDiskAlbumArtCacheSizeBytes(context)
                        }
                    }
                    val diskCacheMbStr = String.format(java.util.Locale.US, "%.1f MB", diskCacheBytes / (1024f * 1024f))

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
                                imageVector = Icons.Rounded.CleaningServices,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Caché de Miniaturas en Disco", "Disk Thumbnail Cache"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = getLocalized(
                                    "Guarda miniaturas para inicio ultrarrápido ($diskCacheMbStr)",
                                    "Saves persistent thumbnails for instant startup loading ($diskCacheMbStr)"
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Button(
                            onClick = {
                                clearDiskAlbumArtCache(context)
                                albumArtCache.evictAll()
                                albumArtVersion++
                                android.widget.Toast.makeText(
                                    context,
                                    getLocalized("Caché de portadas limpiada", "Artwork cache cleared"),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(getLocalized("Limpiar", "Clear"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // CARD 3: OPTIMIZACIÓN DE AUDIO Y SISTEMA (Audio & System Optimization Card)
        Column {
            Text(
                text = getLocalized("COLA Y LIMPIEZA", "QUEUE & CLEANUP"),
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Queue size limit
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
                                imageVector = Icons.Rounded.Memory,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Tamaño Máximo de la Cola", "Maximum Queue Size"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = getLocalized(
                                    "Canciones que se cargan en la cola al reproducir una lista grande (menos = menos RAM)",
                                    "Songs loaded into the queue when playing a large list (fewer = less RAM)"
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            queueLimitOptions.forEach { limit ->
                                val isSelected = ipcQueueLimit == limit
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary 
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        )
                                        .clickable {
                                            ipcQueueLimit = limit
                                            settingsPrefs.edit().putInt("ipc_queue_limit", limit).apply()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "$limit",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

                    // 2. Auto Clean Temp Files on Startup
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
                                imageVector = Icons.Rounded.CleaningServices,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Auto-Limpieza al Iniciar", "Auto-Clean Temp Files"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = getLocalized(
                                    "Borra al abrir la app los instaladores APK descargados y los archivos temporales de edición",
                                    "Deletes downloaded APK installers and leftover editing temp files when the app starts"
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Switch(
                            checked = autoCleanTemp,
                            onCheckedChange = { checked ->
                                autoCleanTemp = checked
                                settingsPrefs.edit().putBoolean("auto_clean_temp", checked).apply()
                            }
                        )
                    }
                }
            }
        }
    }
}

