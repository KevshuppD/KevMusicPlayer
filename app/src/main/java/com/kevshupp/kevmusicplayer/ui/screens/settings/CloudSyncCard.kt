package com.kevshupp.kevmusicplayer.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.data.cloud.CloudBackupMetadata
import com.kevshupp.kevmusicplayer.data.cloud.CloudUser
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CloudSyncCard(
    viewModel: MediaBrowserViewModel,
    getLocalized: (String, String) -> String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val cloudUser by viewModel.cloudUser.collectAsState()

    var isProcessing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf("") }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreMetadata by remember { mutableStateOf<CloudBackupMetadata?>(null) }
    var showSignOutDialog by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    val googleSignInLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.data != null) {
            isProcessing = true
            processingMessage = getLocalized("Autenticando con Google...", "Authenticating with Google...")
            viewModel.handleGoogleSignInResult(
                context = context,
                intent = result.data,
                onSuccess = {
                    isProcessing = false
                    Toast.makeText(
                        context,
                        getLocalized("Sesión iniciada con éxito", "Signed in successfully"),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onError = { errorMsg ->
                    isProcessing = false
                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                }
            )
        } else {
            isProcessing = false
        }
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = settingsCardContainerColor()
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudSync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = getLocalized("Google Cloud Sync", "Google Cloud Sync"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (cloudUser != null) {
                            getLocalized("Conectado con Google", "Connected with Google")
                        } else {
                            getLocalized("Copia de seguridad en la nube", "Cloud backup & sync")
                        },
                        fontSize = 11.sp,
                        color = if (cloudUser != null) Color(0xFF4CAF50) else settingsTextMutedColor()
                    )
                }

                if (cloudUser != null) {
                    IconButton(
                        onClick = { showSignOutDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
                            contentDescription = getLocalized("Cerrar sesión", "Sign out"),
                            tint = settingsTextMutedColor(),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

            if (cloudUser == null) {
                // Not logged in state
                Text(
                    text = getLocalized(
                        "Inicia sesión con Google para respaldar automáticamente tus listas, canciones favoritas, letras y estadísticas en la nube.",
                        "Sign in with Google to automatically backup and sync your playlists, favorites, lyrics, and playback stats across devices."
                    ),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = settingsTextMutedColor()
                )

                Button(
                    onClick = {
                        val signInIntent = viewModel.getGoogleSignInIntent(context)
                        googleSignInLauncher.launch(signInIntent)
                    },
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(processingMessage, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = getLocalized("Continuar con Google", "Continue with Google"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                val user = cloudUser!!

                // Logged in user banner
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(12.dp)
                ) {
                    if (!user.photoUrl.isNullOrBlank()) {
                        SubcomposeAsyncImage(
                            model = user.photoUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
                            error = {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = user.displayName?.take(1)?.uppercase() ?: "U",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = user.displayName?.take(1)?.uppercase() ?: "U",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user.displayName ?: getLocalized("Usuario de Google", "Google User"),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = user.email,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = settingsTextMutedColor()
                        )
                    }
                }

                // Last sync status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = settingsTextMutedColor(),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (user.lastSyncTimestamp > 0) {
                            "${getLocalized("Última sincronización:", "Last sync:")} ${dateFormat.format(Date(user.lastSyncTimestamp))}"
                        } else {
                            getLocalized("Sin respaldos previos en la nube", "No previous cloud backups")
                        },
                        fontSize = 11.sp,
                        color = settingsTextMutedColor()
                    )
                }

                // Auto sync main switch
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getLocalized("Sincronización Automática", "Auto Sync"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (user.isAutoSyncEnabled) {
                                when (user.autoSyncMode) {
                                    "realtime" -> getLocalized("Sincroniza en tiempo real y al salir", "Syncs in real-time and on exit")
                                    "on_exit" -> getLocalized("Sincroniza solo al cerrar o salir", "Syncs only on close or exit")
                                    "daily" -> getLocalized("Sincroniza periódicamente cada día", "Syncs periodically once a day")
                                    "manual" -> getLocalized("Solo sincroniza manualmente", "Only syncs manually")
                                    else -> getLocalized("Sincronización activada", "Auto sync enabled")
                                }
                            } else {
                                getLocalized("Sincronización automática desactivada", "Auto sync disabled")
                            },
                            fontSize = 11.sp,
                            color = settingsTextMutedColor()
                        )
                    }

                    Switch(
                        checked = user.isAutoSyncEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setCloudAutoSync(context, enabled)
                        }
                    )
                }

                // Advanced sync options when auto-sync is enabled
                AnimatedVisibility(visible = user.isAutoSyncEnabled) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Modo de sincronización (Segmented chips)
                        Text(
                            text = getLocalized("Modo de sincronización", "Sync Mode"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )

                        val syncModes = listOf(
                            Triple("realtime", getLocalized("Tiempo Real", "Real-Time"), Icons.Rounded.Sync),
                            Triple("on_exit", getLocalized("Al Salir", "On Exit"), Icons.AutoMirrored.Rounded.ExitToApp),
                            Triple("daily", getLocalized("Diario", "Daily"), Icons.Rounded.Today),
                            Triple("manual", getLocalized("Manual", "Manual"), Icons.Rounded.TouchApp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            syncModes.forEach { (modeKey, modeTitle, modeIcon) ->
                                val isSelected = user.autoSyncMode == modeKey
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    },
                                    border = androidx.compose.foundation.BorderStroke(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { viewModel.setCloudAutoSyncMode(context, modeKey) }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = modeIcon,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else settingsTextMutedColor(),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = modeTitle,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        // Wi-Fi Only Switch
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (user.syncWifiOnly) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                                    contentDescription = null,
                                    tint = if (user.syncWifiOnly) MaterialTheme.colorScheme.primary else settingsTextMutedColor(),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = getLocalized("Solo con Wi-Fi", "Wi-Fi Only"),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = getLocalized("No usar datos móviles para subir copias", "Don't use mobile data for backups"),
                                        fontSize = 10.sp,
                                        color = settingsTextMutedColor()
                                    )
                                }
                                Switch(
                                    checked = user.syncWifiOnly,
                                    onCheckedChange = { viewModel.setCloudSyncWifiOnly(context, it) }
                                )
                            }
                        }

                        // Granular content selection
                        var isContentExpanded by remember { mutableStateOf(false) }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isContentExpanded = !isContentExpanded },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Layers,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = getLocalized("Contenido a sincronizar", "Content to sync"),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = if (isContentExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = settingsTextMutedColor(),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                AnimatedVisibility(visible = isContentExpanded) {
                                    Column(
                                        modifier = Modifier.padding(top = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Playlists & Favorites
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.QueueMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(getLocalized("Playlists y Favoritos", "Playlists & Favorites"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                            Checkbox(
                                                checked = user.includePlaylists,
                                                onCheckedChange = { viewModel.setCloudSyncIncludePlaylists(context, it) }
                                            )
                                        }

                                        // Lyrics & Translations
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.Lyrics, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(getLocalized("Letras y Traducciones", "Lyrics & Translations"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                            Checkbox(
                                                checked = user.includeLyrics,
                                                onCheckedChange = { viewModel.setCloudSyncIncludeLyrics(context, it) }
                                            )
                                        }

                                        // Statistics & History
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.Leaderboard, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(getLocalized("Estadísticas e Historial", "Statistics & History"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                            Checkbox(
                                                checked = user.includeStats,
                                                onCheckedChange = { viewModel.setCloudSyncIncludeStats(context, it) }
                                            )
                                        }

                                        // Settings & Equalizer
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(getLocalized("Ajustes y Ecualizador", "Settings & Equalizer"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                            Checkbox(
                                                checked = user.includeSettings,
                                                onCheckedChange = { viewModel.setCloudSyncIncludeSettings(context, it) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Action buttons (Upload / Restore)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Upload Button
                    Button(
                        onClick = {
                            isProcessing = true
                            processingMessage = getLocalized("Subiendo respaldo...", "Uploading backup...")
                            viewModel.uploadCloudBackup(
                                context = context,
                                onSuccess = {
                                    isProcessing = false
                                    Toast.makeText(
                                        context,
                                        getLocalized("Copia guardada en la nube con éxito", "Cloud backup saved successfully"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
                                onError = { errorMsg ->
                                    isProcessing = false
                                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                                }
                            )
                        },
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = getLocalized("Respaldar", "Backup"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Restore Button
                    OutlinedButton(
                        onClick = {
                            isProcessing = true
                            processingMessage = getLocalized("Consultando nube...", "Checking cloud...")
                            viewModel.getLatestCloudBackupInfo(context) { metadata ->
                                isProcessing = false
                                if (metadata != null) {
                                    restoreMetadata = metadata
                                    showRestoreDialog = true
                                } else {
                                    Toast.makeText(
                                        context,
                                        getLocalized("No se encontró ningún respaldo en la nube", "No cloud backup found"),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        enabled = !isProcessing,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = getLocalized("Restaurar", "Restore"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (isProcessing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = processingMessage,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    // Confirmation dialog before restoring cloud backup
    if (showRestoreDialog && restoreMetadata != null) {
        val meta = restoreMetadata!!
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(getLocalized("Restaurar desde la Nube", "Restore from Cloud"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = getLocalized(
                            "Se restaurarán tus configuraciones, listas de reproducción y letras desde la copia de seguridad:",
                            "Your settings, playlists, and lyrics will be restored from the backup:"
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "📅 ${getLocalized("Fecha:", "Date:")} ${dateFormat.format(Date(meta.timestamp))}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (meta.device.isNotBlank()) {
                                Text(
                                    text = "📱 ${getLocalized("Dispositivo:", "Device:")} ${meta.device}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            if (meta.playlistsCount > 0 || meta.tracksCount > 0) {
                                Text(
                                    text = "🎵 ${meta.playlistsCount} ${getLocalized("playlists", "playlists")} • ${meta.tracksCount} ${getLocalized("canciones", "tracks")}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Text(
                        text = getLocalized(
                            "⚠️ La aplicación actualizará tu biblioteca inmediatamente.",
                            "⚠️ The application will refresh your library immediately."
                        ),
                        fontSize = 11.sp,
                        color = settingsTextMutedColor()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreDialog = false
                        isProcessing = true
                        processingMessage = getLocalized("Restaurando...", "Restoring...")
                        viewModel.restoreCloudBackup(
                            context = context,
                            onSuccess = {
                                isProcessing = false
                                Toast.makeText(
                                    context,
                                    getLocalized("Copia restaurada con éxito", "Backup restored successfully"),
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            onError = { errorMsg ->
                                isProcessing = false
                                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(getLocalized("Restaurar Ahora", "Restore Now"), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text(getLocalized("Cancelar", "Cancel"), color = settingsTextMutedColor())
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    // Sign out confirmation dialog
    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = {
                Text(getLocalized("Cerrar Sesión", "Sign Out"), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    getLocalized(
                        "¿Deseas cerrar sesión de tu cuenta de Google? Tus copias previas en la nube se mantendrán seguras.",
                        "Do you want to sign out of your Google account? Your previous cloud backups will remain safe."
                    ),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSignOutDialog = false
                        viewModel.signOutGoogle(context)
                        Toast.makeText(
                            context,
                            getLocalized("Sesión cerrada", "Signed out"),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(getLocalized("Cerrar Sesión", "Sign Out"), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(getLocalized("Cancelar", "Cancel"), color = settingsTextMutedColor())
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}
