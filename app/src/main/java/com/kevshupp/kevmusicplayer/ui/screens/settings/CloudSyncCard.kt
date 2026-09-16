package com.kevshupp.kevmusicplayer.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
                            imageVector = Icons.AutoMirrored.Rounded.Logout,
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
                        if (activity != null) {
                            isProcessing = true
                            processingMessage = getLocalized("Iniciando sesión...", "Signing in...")
                            viewModel.signInWithGoogle(
                                activity = activity,
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
                        }
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

                // Auto sync switch
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
                            text = getLocalized("Sube tus cambios al editar listas o salir", "Uploads changes when editing playlists or closing"),
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
