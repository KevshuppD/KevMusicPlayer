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
fun SystemSettingsSection(
    audioGranted: Boolean,
    notificationGranted: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    audioPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    notificationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    getLocalized: (String, String) -> String,
    settingsPrefs: android.content.SharedPreferences,
    context: android.content.Context
) {
    // 1. Diagnóstico y Servicios (Diagnostics & Services Card)
    Column {
        Text(
            text = getLocalized("DIAGNÓSTICO Y SERVICIOS", "DIAGNOSTICS & SERVICES"),
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
                // 1. Automatic Translation Toggle
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

                // 2. Remember Lyrics Open State Toggle
                var rememberLyricsOpen by remember { mutableStateOf(settingsPrefs.getBoolean("remember_lyrics_open", true)) }
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
                            imageVector = Icons.Rounded.ChatBubbleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Recordar Estado de Letras", "Remember Lyrics View State"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = getLocalized(
                                "Mantiene la pantalla de letras activa al cambiar de canción, bloquear o reabrir la app",
                                "Keeps lyrics screen active when changing songs, locking screen or reopening the app"
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    Switch(
                        checked = rememberLyricsOpen,
                        onCheckedChange = { checked ->
                            rememberLyricsOpen = checked
                            settingsPrefs.edit().putBoolean("remember_lyrics_open", checked).apply()
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

                // 2. Telemetry Switch
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

