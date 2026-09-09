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
fun PerformanceSettingsSection(
    selectedRefreshRate: String,
    onRefreshRateSelected: (String) -> Unit,
    disableAnimations: Boolean,
    onDisableAnimationsChanged: (Boolean) -> Unit,
    getLocalized: (String, String) -> String,
    settingsPrefs: android.content.SharedPreferences,
    context: android.content.Context
) {
    var performanceProfile by remember { mutableStateOf(settingsPrefs.getString("performance_profile", "max") ?: "max") }
    var preloadCount by remember { mutableStateOf(settingsPrefs.getInt("preload_art_count", 5)) }
    var cacheCapacity by remember { mutableStateOf(settingsPrefs.getInt("cover_cache_capacity", 150)) }
    var artResolution by remember { mutableStateOf(settingsPrefs.getInt("art_resolution", 500)) }
    var diskCacheQuality by remember { mutableStateOf(settingsPrefs.getInt("disk_cache_quality", 85)) }
    var hapticEnabled by remember { mutableStateOf(settingsPrefs.getBoolean("haptic_feedback_enabled", true)) }
    var lazyReplayGain by remember { mutableStateOf(settingsPrefs.getBoolean("lazy_replay_gain", true)) }
    var ipcQueueLimit by remember { mutableStateOf(settingsPrefs.getInt("ipc_queue_limit", 1500)) }
    var autoCleanTemp by remember { mutableStateOf(settingsPrefs.getBoolean("auto_clean_temp", true)) }

    fun applyProfile(profileKey: String) {
        performanceProfile = profileKey
        val edit = settingsPrefs.edit()
        edit.putString("performance_profile", profileKey)

        when (profileKey) {
            "max" -> {
                onRefreshRateSelected("120")
                edit.putString("refresh_rate", "120")
                onDisableAnimationsChanged(false)
                edit.putBoolean("disable_animations", false)
                hapticEnabled = true
                edit.putBoolean("haptic_feedback_enabled", true)
                preloadCount = 5
                edit.putInt("preload_art_count", 5)
                cacheCapacity = 300
                edit.putInt("cover_cache_capacity", 300)
                updateAlbumArtCacheSize(300)
                artResolution = 500
                edit.putInt("art_resolution", 500)
                diskCacheQuality = 85
                edit.putInt("disk_cache_quality", 85)
                lazyReplayGain = true
                edit.putBoolean("lazy_replay_gain", true)
                ipcQueueLimit = 3000
                edit.putInt("ipc_queue_limit", 3000)
                autoCleanTemp = true
                edit.putBoolean("auto_clean_temp", true)
            }
            "turbo" -> {
                onRefreshRateSelected("120")
                edit.putString("refresh_rate", "120")
                onDisableAnimationsChanged(true)
                edit.putBoolean("disable_animations", true)
                hapticEnabled = false
                edit.putBoolean("haptic_feedback_enabled", false)
                preloadCount = 2
                edit.putInt("preload_art_count", 2)
                cacheCapacity = 150
                edit.putInt("cover_cache_capacity", 150)
                updateAlbumArtCacheSize(150)
                artResolution = 400
                edit.putInt("art_resolution", 400)
                diskCacheQuality = 70
                edit.putInt("disk_cache_quality", 70)
                lazyReplayGain = true
                edit.putBoolean("lazy_replay_gain", true)
                ipcQueueLimit = 3000
                edit.putInt("ipc_queue_limit", 3000)
                autoCleanTemp = true
                edit.putBoolean("auto_clean_temp", true)
            }
            "balanced" -> {
                onRefreshRateSelected("90")
                edit.putString("refresh_rate", "90")
                onDisableAnimationsChanged(false)
                edit.putBoolean("disable_animations", false)
                hapticEnabled = true
                edit.putBoolean("haptic_feedback_enabled", true)
                preloadCount = 3
                edit.putInt("preload_art_count", 3)
                cacheCapacity = 150
                edit.putInt("cover_cache_capacity", 150)
                updateAlbumArtCacheSize(150)
                artResolution = 500
                edit.putInt("art_resolution", 500)
                diskCacheQuality = 80
                edit.putInt("disk_cache_quality", 80)
                lazyReplayGain = true
                edit.putBoolean("lazy_replay_gain", true)
                ipcQueueLimit = 1500
                edit.putInt("ipc_queue_limit", 1500)
                autoCleanTemp = true
                edit.putBoolean("auto_clean_temp", true)
            }
            "battery" -> {
                onRefreshRateSelected("60")
                edit.putString("refresh_rate", "60")
                onDisableAnimationsChanged(true)
                edit.putBoolean("disable_animations", true)
                hapticEnabled = false
                edit.putBoolean("haptic_feedback_enabled", false)
                preloadCount = 0
                edit.putInt("preload_art_count", 0)
                cacheCapacity = 60
                edit.putInt("cover_cache_capacity", 60)
                updateAlbumArtCacheSize(60)
                artResolution = 300
                edit.putInt("art_resolution", 300)
                diskCacheQuality = 70
                edit.putInt("disk_cache_quality", 70)
                lazyReplayGain = true
                edit.putBoolean("lazy_replay_gain", true)
                ipcQueueLimit = 500
                edit.putInt("ipc_queue_limit", 500)
                autoCleanTemp = false
                edit.putBoolean("auto_clean_temp", false)
            }
            "custom" -> {}
        }
        edit.apply()
        albumArtCache.evictAll()
        clearDiskAlbumArtCache(context)
        albumArtVersion++
    }

    fun markCustom() {
        if (performanceProfile != "custom") {
            performanceProfile = "custom"
            settingsPrefs.edit().putString("performance_profile", "custom").apply()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
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
                        val badge: String,
                        val desc: String
                    )

                    val profiles = listOf(
                        ProfileItem(
                            key = "max",
                            title = "⚡ " + getLocalized("Máxima Fidelidad", "Max Fidelity"),
                            badge = "120 Hz • Full",
                            desc = getLocalized(
                                "Tasa a 120Hz con animaciones visuales completas, pre-carga agresiva (5 temas) y carátulas en máxima resolución.",
                                "120Hz rate with full visual animations, aggressive preloading (5 tracks) and maximum resolution artwork."
                            )
                        ),
                        ProfileItem(
                            key = "turbo",
                            title = "🚀 " + getLocalized("Ultra Rápido (Optimizado)", "Ultra Fast (Optimized)"),
                            badge = "120 Hz • Turbo",
                            desc = getLocalized(
                                "Fluidez a 120Hz sin latencia: suprime animaciones y optimiza la decodificación (400px, WebP 70%) para una respuesta táctil instantánea.",
                                "Fluid 120Hz with zero latency: disables animations and optimizes decoding (400px, 70% WebP) for instant touch response."
                            )
                        ),
                        ProfileItem(
                            key = "balanced",
                            title = "⚖️ " + getLocalized("Equilibrado", "Balanced"),
                            badge = "90 Hz",
                            desc = getLocalized(
                                "Punto óptimo a 90Hz: desplazamiento suave, pre-carga moderada (3 temas) y consumo energético equilibrado.",
                                "Optimal balance at 90Hz: smooth scrolling, moderate preloading (3 tracks) and balanced power consumption."
                            )
                        ),
                        ProfileItem(
                            key = "battery",
                            title = "🔋 " + getLocalized("Ahorro de Batería", "Battery Saver"),
                            badge = "60 Hz",
                            desc = getLocalized(
                                "Refresco a 60Hz, animaciones mínimas y sin pre-carga para maximizar la autonomía durante horas de reproducción.",
                                "60Hz refresh rate, disabled animations and no preloading to maximize battery during long listening sessions."
                            )
                        ),
                        ProfileItem(
                            key = "custom",
                            title = "⚙️ " + getLocalized("Personalizado", "Custom"),
                            badge = getLocalized("Manual", "Manual"),
                            desc = getLocalized(
                                "Ajusta individualmente la tasa de refresco (60/90/120Hz), caché, resolución de carátulas y animaciones.",
                                "Manually configure refresh rate (60/90/120Hz), cache limits, artwork resolution and animation speeds."
                            )
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
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(
                                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = item.badge,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = item.desc,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
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
                                            markCustom()
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
                                markCustom()
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
                                markCustom()
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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
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
                            listOf(0, 3, 5, 10).forEach { count ->
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
                                            markCustom()
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
                            listOf(50, 150, 300).forEach { cap ->
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
                                            markCustom()
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
                            listOf(250, 500, 800).forEach { res ->
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
                                            markCustom()
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
                            listOf(70, 85, 100).forEach { qual ->
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
                                            markCustom()
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
                text = getLocalized("OPTIMIZACIÓN DE PROCESAMIENTO Y AUDIO", "AUDIO & PROCESSING OPTIMIZATION"),
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
                    // 1. ReplayGain Processing Mode
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
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Normalización ReplayGain", "ReplayGain Normalization"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = getLocalized(
                                    "Modo de lectura diferido ahorra procesador al escanear la biblioteca",
                                    "Lazy reading mode saves CPU processing during initial library scanning"
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Switch(
                            checked = lazyReplayGain,
                            onCheckedChange = { checked ->
                                lazyReplayGain = checked
                                settingsPrefs.edit().putBoolean("lazy_replay_gain", checked).apply()
                                markCustom()
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

                    // 2. Media3 IPC Buffer Limit
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
                                text = getLocalized("Límite Búfer IPC Media3", "Media3 IPC Buffer Limit"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = getLocalized(
                                    "Máximo de elementos en memoria del reproductor (evita picos de RAM)",
                                    "Max items serialized in player buffer (prevents RAM spikes on low memory)"
                                ),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(500, 1500, 3000).forEach { limit ->
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
                                            markCustom()
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

                    // 3. Auto Clean Temp Files on Startup
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
                                    "Elimina instaladores APK antiguos y logs temporales automáticamente al abrir la app",
                                    "Purges old temporary APK installers and temporary logs automatically on startup"
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
                                markCustom()
                            }
                        )
                    }
                }
            }
        }
    }
}

