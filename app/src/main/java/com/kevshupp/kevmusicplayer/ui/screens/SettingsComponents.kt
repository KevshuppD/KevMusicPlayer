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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
fun GeneralSettingsSection(
    selectedTheme: String,
    onThemeSelected: (String) -> Unit,
    selectedLanguage: String,
    applyLanguage: (String) -> Unit,
    sortBy: String,
    onSortByChanged: (String) -> Unit,
    getLocalized: (String, String) -> String,
    settingsPrefs: android.content.SharedPreferences
) {
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
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                val themes = listOf(
                    Triple("cyberpunk", getLocalized("Cyberpunk Púrpura", "Cyberpunk Purple"), getLocalized("Vibrante tono púrpura y rosa neón", "Vibrant purple and neon pink style")),
                    Triple("petrol", getLocalized("Azul Petróleo", "Petrol Blue"), getLocalized("Sofisticado azul petróleo y cian minimalista", "Sophisticated petrol blue and clean cyan")),
                    Triple("turquoise", getLocalized("Turquesa", "Turquoise"), getLocalized("Estilo turquesa y verde menta refrescante", "Refreshing turquoise and mint green style")),
                    Triple("obsidian", getLocalized("Obsidiana Oscuro", "Deep Obsidian"), getLocalized("Fondo negro puro de alto contraste (AMOLED)", "Pure black background with high contrast (AMOLED)"))
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
                                color = Color.White
                            )
                            Text(
                                text = desc,
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }

                        // Visual Indicator Badge of theme colors
                        Box(
                            modifier = Modifier
                                .size(36.dp, 20.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when (tag) {
                                        "petrol" -> Brush.horizontalGradient(listOf(Color(0xFF0A1E24), Color(0xFF00E5FF)))
                                        "obsidian" -> Brush.horizontalGradient(listOf(Color(0xFF0E0E0E), Color(0xFFFFFFFF)))
                                        "turquoise" -> Brush.horizontalGradient(listOf(Color(0xFF071F1B), Color(0xFF00F5D4)))
                                        else -> Brush.horizontalGradient(listOf(Color(0xFF121422), Color(0xFFFF4081)))
                                    }
                                )
                        )
                    }

                    if (index < themes.lastIndex) {
                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.08f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

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
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val languages = listOf(
                    "en" to R.string.language_english,
                    "es" to R.string.language_spanish
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
                            color = Color.White
                        )
                    }
                    if (idx < languages.lastIndex) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
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
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
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
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = pref.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = pref.desc,
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                    if (index < sortPreferences.size - 1) {
                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.08f),
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
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
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
                            color = Color.White
                        )
                        Text(
                            text = getLocalized(
                                "Forzar tasa alta (120Hz) para máxima fluidez o 60Hz para ahorrar batería",
                                "Enforce high rate (120Hz) for fluid scrolling or 60Hz to save battery"
                            ),
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.5f)
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
                                    color = if (isSelected) Color.Black else Color.White
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

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
                            color = Color.White
                        )
                        Text(
                            text = getLocalized(
                                "Desactiva transiciones y efectos visuales para máxima velocidad en la app",
                                "Disable transitions and visual effects for absolute speed and efficiency"
                            ),
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.5f)
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
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
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
                            color = Color.White
                        )
                        Text(
                            text = if (audioGranted) getLocalized("Permitido. Almacenamiento escaneado con éxito.", "Granted. Storage scanned successfully.") 
                                   else getLocalized("Requerido para buscar y reproducir archivos MP3/FLAC locales.", "Required to discover and play local MP3/FLAC music files."),
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.5f)
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
                
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                
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
                            color = Color.White
                        )
                        Text(
                            text = if (notificationGranted) getLocalized("Permitido. Controlador del reproductor activo.", "Granted. Background player controller active.") 
                                   else getLocalized("Requerido para mostrar la canción actual en la barra de tareas.", "Required to show current track in system tray."),
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.5f)
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

                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

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
                            color = Color.White
                        )
                        Text(
                            text = getLocalized("Mantiene la reproducción persistente y el procesador activo.", "Enforces persistent playback thread and CPU Wake Lock."),
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                    
                    Text(
                        text = getLocalized("Activo", "Running"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF)
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

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
                            color = Color.White
                        )
                        Text(
                            text = if (isIgnoringBatteryOptimizations) {
                                getLocalized("Sin restricciones. El sistema no suspenderá la reproducción.", "Unrestricted. The system won't kill playback.")
                            } else {
                                getLocalized("Optimizado. Puede cerrarse al estar en segundo plano.", "Optimized. Playback may be killed in background.")
                            },
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.5f)
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
    performExportToFolder: (String) -> Unit,
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
    setExcludedFolders: (List<String>) -> Unit
) {
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
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
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
                    color = Color.White.copy(alpha = 0.5f),
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
                                                draggingIndex = index
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
                                                if (draggingIndex == null || itemHeightPx == 0f) return@detectDragGestures
                                                dragOffset += dragAmount.y
                                                val offsetIndexes = (dragOffset / itemHeightPx).roundToInt()
                                                if (offsetIndexes != 0) {
                                                    val currentIndex = draggingIndex!!
                                                    val targetIndex = (currentIndex + offsetIndexes).coerceIn(0, enabledOrder.lastIndex)
                                                    if (targetIndex != currentIndex) {
                                                        enabledOrder.removeAt(currentIndex)
                                                        enabledOrder.add(targetIndex, name)
                                                        draggingIndex = targetIndex
                                                        dragOffset -= offsetIndexes * itemHeightPx
                                                        onEnabledTabsChanged(enabledOrder.toList())
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
                                        color = Color.White
                                    )
                                    Text(
                                        text = stringResource(cat.descRes),
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.5f)
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
                                color = Color.White.copy(alpha = 0.08f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }

                if (disabledOrder.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.library_categories_disabled),
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.5f),
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
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(cat.labelRes),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                            Text(
                                text = stringResource(cat.descRes),
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.4f)
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
                            color = Color.White.copy(alpha = 0.08f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // 2. Maintenance / Re-scan button Section (Glowing and premium)
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
                color = Color.White.copy(alpha = 0.6f)
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
                        color = Color.White,
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

            // Organize Files Section
            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
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
                    "Renombra automáticamente los archivos físicos de música en base a sus metadatos al formato: <Número de pista>. <Artista> - <Título>.<ext>.",
                    "Automatically rename physical music files based on metadata to: <Track number>. <Artist> - <Title>.<ext>."
                ),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
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
                enabled = !isRenaming && !isScanning,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isRenaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "$renamingCurrent/$renamingTotal: $renamingCurrentName",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color.Black
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.DriveFileRenameOutline,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("Renombrar Archivos", "Rename Files"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.Black
                    )
                }
            }

            // Excluded Folders Section
            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
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
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
                Icon(
                    imageVector = if (showFolderList) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = Color.White
                )
            }

            if (showFolderList) {
                Spacer(modifier = Modifier.height(8.dp))
                if (deviceFolders.isEmpty()) {
                    Text(
                        text = getLocalized("No se encontraron carpetas con música.", "No music folders found."),
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.5f)
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
                                    .background(Color.White.copy(alpha = 0.04f))
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
                                        uncheckedColor = Color.White.copy(alpha = 0.4f)
                                    )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = folderName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = folderPath,
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.4f)
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
    val folderName = remember(backupDirUri) {
        if (backupDirUri != null) {
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
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
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
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                // Folder status indicator
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                    modifier = Modifier.fillMaxWidth().clickable {
                        selectBackupFolderLauncher.launch(null)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderSpecial,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Carpeta de Destino Fija", "Fixed Target Folder"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = folderName ?: getLocalized("Toca para configurar una carpeta...", "Tap to configure a folder..."),
                                fontSize = 11.sp,
                                color = if (folderName != null) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Export Backup Button
                    Button(
                        onClick = {
                            val currentDirUri = backupDirUri
                            if (currentDirUri != null) {
                                performExportToFolder(currentDirUri)
                            } else {
                                selectBackupFolderLauncher.launch(null)
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
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = getLocalized("Exportar", "Backup"),
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }

                    // 2. Import Restore Button
                    var showRestoreOptions by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = {
                                if (backupDirUri != null) {
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
                            containerColor = Color(0xFF1E213A)
                        ) {
                            DropdownMenuItem(
                                text = { Text(getLocalized("Restaurar desde carpeta fija", "Restore from fixed folder"), color = Color.White) },
                                onClick = {
                                    showRestoreOptions = false
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
                                                            (context as? Activity)?.recreate()
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
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(getLocalized("Seleccionar archivo...", "Select file..."), color = Color.White) },
                                onClick = {
                                    showRestoreOptions = false
                                    openDocumentLauncher.launch(arrayOf("application/json"))
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.FileOpen, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
                                }
                            )
                        }
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
            title = { Text(text = updateDialogTitle, fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column {
                    Text(text = updateDialogMessage, color = Color.White.copy(alpha = 0.8f))
                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = getLocalized(
                                "Descargando: ${(downloadProgress * 100).toInt()}%",
                                "Downloading: ${(downloadProgress * 100).toInt()}%"
                            ),
                            color = Color.White.copy(alpha = 0.6f),
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
                        Text(text = getLocalized("Cancelar", "Cancel"), color = Color.White.copy(alpha = 0.6f))
                    }
                }
            },
            containerColor = Color(0xFF1E2135),
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
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
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
                    color = Color.White
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
                    color = Color.White.copy(alpha = 0.6f),
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
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("GitHub del Desarrollador", "Developer GitHub Profile"),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Spacer(modifier = Modifier.height(12.dp))

                // Used libraries tag listing
                Text(
                    text = getLocalized("Tecnologías Utilizadas", "Libraries & Frameworks"),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.5f),
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
                                .background(Color.White.copy(alpha = 0.06f))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = library,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}
