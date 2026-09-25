package com.kevshupp.kevmusicplayer.ui.screens.dialogs

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.data.cloud.CloudBackupMetadata
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun UserProfileDialog(
    viewModel: MediaBrowserViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val cloudUser by viewModel.cloudUser.collectAsState()

    val systemLang = remember { context.resources.configuration.locales[0].language }
    val isEs = systemLang == "es"
    fun getLocalized(es: String, en: String) = if (isEs) es else en

    var isProcessing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf("") }
    var showRestoreConfirmDialog by remember { mutableStateOf(false) }
    var restoreMetadata by remember { mutableStateOf<CloudBackupMetadata?>(null) }
    var showSignOutConfirmDialog by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
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
                onError = { error ->
                    isProcessing = false
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                }
            )
        } else {
            isProcessing = false
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.updateCustomUserPhoto(context, uri.toString())
            Toast.makeText(
                context,
                getLocalized("Foto de perfil actualizada", "Profile picture updated"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (MaterialTheme.colorScheme.background == Color.White) Color(0xFFF2F4F8) else Color(0xFF161824)
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            ),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Top header with close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = getLocalized("Perfil y Sincronización", "Profile & Sync"),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Cerrar",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                if (cloudUser == null) {
                    // Not connected state
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.dp)
                        )
                    }

                    Text(
                        text = getLocalized("Conecta tu cuenta de Google", "Connect your Google Account"),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = getLocalized(
                            "Inicia sesión para sincronizar tus playlists, letras, ecualización y estadísticas en tiempo real.",
                            "Sign in to sync your playlists, lyrics, equalizer, and statistics across devices in real time."
                        ),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )

                    Button(
                        onClick = {
                            val signInIntent = viewModel.getGoogleSignInIntent(context)
                            googleSignInLauncher.launch(signInIntent)
                        },
                        enabled = !isProcessing,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(processingMessage, fontSize = 13.sp)
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = getLocalized("Iniciar sesión con Google", "Sign in with Google"),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    val user = cloudUser!!

                    // Avatar with edit photo overlay
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .clip(CircleShape)
                            .border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable { photoPickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (!user.photoUrl.isNullOrBlank()) {
                            SubcomposeAsyncImage(
                                model = user.photoUrl,
                                contentDescription = "Foto de perfil",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                error = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = user.displayName?.take(1)?.uppercase() ?: "U",
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = user.displayName?.take(1)?.uppercase() ?: "U",
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // Small edit badge in corner
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Cambiar foto",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = user.displayName ?: getLocalized("Usuario de Google", "Google User"),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = user.email,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = if (user.lastSyncTimestamp > 0) {
                                "☁️ ${getLocalized("Último sync:", "Last sync:")} ${dateFormat.format(Date(user.lastSyncTimestamp))}"
                            } else {
                                "☁️ ${getLocalized("Sin sincronizaciones previas", "No previous sync")}"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Auto sync toggle row
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
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
                                            "realtime" -> getLocalized("En tiempo real y al salir", "Real-time and on exit")
                                            "on_exit" -> getLocalized("Solo al cerrar o salir", "Only on close or exit")
                                            "daily" -> getLocalized("Periódicamente al día", "Periodically once a day")
                                            "manual" -> getLocalized("Solo al pedir respaldo manual", "Only on manual backup")
                                            else -> getLocalized("Sincronización activa", "Sync active")
                                        }
                                    } else {
                                        getLocalized("Sube tus cambios manualmente", "Upload changes manually")
                                    },
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Switch(
                                checked = user.isAutoSyncEnabled,
                                onCheckedChange = { viewModel.setCloudAutoSync(context, it) }
                            )
                        }
                    }

                    // Detailed options when Auto Sync is enabled
                    AnimatedVisibility(visible = user.isAutoSyncEnabled) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Sync Mode Chips
                            Text(
                                text = getLocalized("Modo de sincronización", "Sync Mode"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.fillMaxWidth()
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
                                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
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

                            // Wi-Fi Only Row
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
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
                                        tint = if (user.syncWifiOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
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
                                            text = getLocalized("Ahorra datos móviles", "Save mobile data"),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
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
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    AnimatedVisibility(visible = isContentExpanded) {
                                        Column(
                                            modifier = Modifier.padding(top = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
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

                    // Action buttons (Respaldar / Restaurar)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                isProcessing = true
                                processingMessage = getLocalized("Subiendo copia...", "Uploading backup...")
                                viewModel.uploadCloudBackup(
                                    context = context,
                                    onSuccess = {
                                        isProcessing = false
                                        Toast.makeText(
                                            context,
                                            getLocalized("Respaldo en la nube guardado con éxito", "Cloud backup saved successfully"),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    },
                                    onError = { error ->
                                        isProcessing = false
                                        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            enabled = !isProcessing,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
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

                        OutlinedButton(
                            onClick = {
                                isProcessing = true
                                processingMessage = getLocalized("Consultando nube...", "Checking cloud...")
                                viewModel.getLatestCloudBackupInfo(context) { metadata ->
                                    isProcessing = false
                                    if (metadata != null) {
                                        restoreMetadata = metadata
                                        showRestoreConfirmDialog = true
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
                            modifier = Modifier.fillMaxWidth()
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

                    // Sign out text button
                    TextButton(
                        onClick = { showSignOutConfirmDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = getLocalized("Cerrar Sesión de Google", "Sign Out from Google"),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    // Restore confirmation dialog
    if (showRestoreConfirmDialog && restoreMetadata != null) {
        val meta = restoreMetadata!!
        AlertDialog(
            onDismissRequest = { showRestoreConfirmDialog = false },
            title = {
                Text(getLocalized("Restaurar desde la Nube", "Restore from Cloud"), fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = getLocalized(
                            "¿Deseas restaurar tus datos desde la copia guardada en la nube?",
                            "Do you want to restore your data from the cloud backup?"
                        ),
                        fontSize = 13.sp
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "📅 ${getLocalized("Fecha:", "Date:")} ${dateFormat.format(Date(meta.timestamp))}",
                                fontSize = 12.sp
                            )
                            if (meta.device.isNotBlank()) {
                                Text(
                                    text = "📱 ${getLocalized("Dispositivo:", "Device:")} ${meta.device}",
                                    fontSize = 12.sp
                                )
                            }
                            if (meta.playlistsCount > 0 || meta.tracksCount > 0) {
                                Text(
                                    text = "🎵 ${meta.playlistsCount} ${getLocalized("playlists", "playlists")} • ${meta.tracksCount} ${getLocalized("canciones", "tracks")}",
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreConfirmDialog = false
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
                                onDismiss()
                            },
                            onError = { error ->
                                isProcessing = false
                                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
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
                TextButton(onClick = { showRestoreConfirmDialog = false }) {
                    Text(getLocalized("Cancelar", "Cancel"))
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    // Sign out confirmation dialog
    if (showSignOutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirmDialog = false },
            title = {
                Text(getLocalized("Cerrar Sesión", "Sign Out"), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    getLocalized(
                        "¿Deseas cerrar sesión de tu cuenta de Google? Tus copias en la nube se mantendrán a salvo.",
                        "Do you want to sign out? Your cloud backups will remain safe."
                    ),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSignOutConfirmDialog = false
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
                TextButton(onClick = { showSignOutConfirmDialog = false }) {
                    Text(getLocalized("Cancelar", "Cancel"))
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}
