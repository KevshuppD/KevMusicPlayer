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
    performExportToFolder: (String, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
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
    onFindDuplicates: () -> Unit,
    onCheckIntegrity: () -> Unit,
    onFindShortSongs: () -> Unit
) {
    var activeOrganizerAction by remember { mutableStateOf("") }
    var showDeleteCoversDialog by remember { mutableStateOf(false) }
    var showDeleteNoMediaDialog by remember { mutableStateOf(false) }
    var showDeleteLyricsDialog by remember { mutableStateOf(false) }
    var isDeepScanning by remember { mutableStateOf(false) }

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
    var exportStatistics by remember { mutableStateOf(true) }

    var totalSongs by remember { mutableStateOf(settingsPrefs.getInt("cached_total_songs", viewModel.localAudioFiles.size)) }
    var totalSizeMb by remember { mutableStateOf(settingsPrefs.getFloat("cached_total_size_mb", 0f)) }
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
            val count = allSongs.size
            val size = totalBytes.toFloat() / (1024 * 1024)
            totalSongs = count
            totalSizeMb = size
            settingsPrefs.edit()
                .putInt("cached_total_songs", count)
                .putFloat("cached_total_size_mb", size)
                .apply()
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

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    scope.launch {
                        isDeepScanning = true
                        viewModel.forceDeepStorageScan(context) { count ->
                            isDeepScanning = false
                            android.widget.Toast.makeText(
                                context,
                                getLocalized(
                                    "Escaneo profundo completado: se escanearon $count archivos de audio.",
                                    "Deep scan completed: scanned $count audio files."
                                ),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                enabled = !isScanning && !isDeepScanning,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isDeepScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = getLocalized("Escaneando disco directamente...", "Scanning disk directly..."),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.FolderZip,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("Forzar Escaneo Profundo de Carpetas", "Force Deep Folder Scan"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = getLocalized(
                    "Escanea físicamente la carpeta seleccionada buscando archivos de audio (.mp3, .flac, etc.) para recuperar canciones de carpetas donde habías borrado un .nomedia.",
                    "Physically scans the selected folder looking for audio files (.mp3, .flac, etc.) to recover songs from folders where .nomedia was deleted."
                ),
                fontSize = 11.sp,
                color = settingsTextMutedColor()
            )

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

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    showDeleteCoversDialog = true
                },
                enabled = activeOrganizerAction == "" && !isScanning,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isRenaming && activeOrganizerAction == "delete_covers") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "$renamingCurrent/$renamingTotal: $renamingCurrentName",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("Borrar Imágenes de Portadas", "Delete Folder Covers"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    showDeleteNoMediaDialog = true
                },
                enabled = activeOrganizerAction == "" && !isScanning,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isRenaming && activeOrganizerAction == "delete_nomedia") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "$renamingCurrent/$renamingTotal: $renamingCurrentName",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("Borrar Archivos .nomedia", "Delete .nomedia Files"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    showDeleteLyricsDialog = true
                },
                enabled = activeOrganizerAction == "" && !isScanning,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isRenaming && activeOrganizerAction == "delete_lyrics") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "$renamingCurrent/$renamingTotal: $renamingCurrentName",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("Borrar Archivos de Letras", "Delete Lyrics Files"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
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

            // Integrity Checker Section
            HorizontalDivider(
                color = settingsDividerColor(),
                modifier = Modifier.padding(vertical = 16.dp)
            )

            Text(
                text = getLocalized("VERIFICADOR DE INTEGRIDAD", "INTEGRITY CHECKER"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = getLocalized(
                    "Busca archivos de audio dañados, corruptos o inaccesibles para mantener limpia tu biblioteca.",
                    "Search for damaged, corrupted, or inaccessible audio files to keep your library clean."
                ),
                fontSize = 12.sp,
                color = settingsTextMutedColor(),
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onCheckIntegrity,
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
                    imageVector = Icons.Rounded.OfflinePin,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = getLocalized("Verificar Integridad", "Verify Integrity"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            // Short Songs Finder Section
            HorizontalDivider(
                color = settingsDividerColor(),
                modifier = Modifier.padding(vertical = 16.dp)
            )

            Text(
                text = getLocalized("CANCIONES CORTAS / INCOMPLETAS", "SHORT / INCOMPLETE SONGS"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = getLocalized(
                    "Busca canciones con duración inusualmente corta (descargas truncadas o cortadas) para listar sus nombres y artistas y copiarlos fácilmente.",
                    "Search for songs with unusually short duration (truncated or incomplete downloads) to list their titles and artists for easy re-downloading."
                ),
                fontSize = 12.sp,
                color = settingsTextMutedColor(),
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onFindShortSongs,
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
                    imageVector = Icons.Rounded.HourglassBottom,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = getLocalized("Buscar Canciones Cortas", "Search Short Songs"),
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

    if (showDeleteCoversDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteCoversDialog = false },
            title = {
                Text(
                    text = getLocalized("¿Borrar imágenes de portadas?", "Delete cover images?"),
                    color = settingsTextColor(),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = getLocalized(
                        "Esta acción buscará y eliminará los archivos de imágenes de portada (cover.jpg, folder.jpg, etc.) de las carpetas de música para liberar espacio y limpiar la galería de fotos. Las portadas embebidas dentro de las canciones no se verán afectadas.",
                        "This will search and delete cover image files (cover.jpg, folder.jpg, etc.) from your music folders to free space and clean your photo gallery. Embedded artwork inside song files will not be affected."
                    ),
                    color = settingsTextMutedColor()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteCoversDialog = false
                        activeOrganizerAction = "delete_covers"
                        setIsRenaming(true)
                        viewModel.deleteAllFolderCoverImages(
                            context = context,
                            onProgress = { current, total ->
                                setRenamingCurrent(current)
                                setRenamingTotal(total)
                                setRenamingCurrentName(getLocalized("Eliminando...", "Deleting..."))
                            },
                            onComplete = { deletedCount ->
                                setIsRenaming(false)
                                activeOrganizerAction = ""
                                android.widget.Toast.makeText(
                                    context,
                                    getLocalized(
                                        "Se eliminaron $deletedCount imágenes de portadas.",
                                        "Deleted $deletedCount cover images."
                                    ),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                ) {
                    Text(
                        text = getLocalized("Borrar", "Delete"),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteCoversDialog = false }) {
                    Text(
                        text = getLocalized("Cancelar", "Cancel"),
                        color = settingsTextMutedColor()
                    )
                }
            }
        )
    }

    if (showDeleteNoMediaDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteNoMediaDialog = false },
            title = {
                Text(
                    text = getLocalized("¿Borrar archivos .nomedia?", "Delete .nomedia files?"),
                    color = settingsTextColor(),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = getLocalized(
                        "Esta acción buscará y eliminará en paralelo los archivos .nomedia de tus carpetas de música para volver a hacer visibles tus canciones en el sistema.",
                        "This will search and delete .nomedia files in parallel from your music folders to make your songs visible to the system."
                    ),
                    color = settingsTextMutedColor()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteNoMediaDialog = false
                        activeOrganizerAction = "delete_nomedia"
                        setIsRenaming(true)
                        viewModel.deleteAllNoMediaFiles(
                            context = context,
                            onProgress = { current, total ->
                                setRenamingCurrent(current)
                                setRenamingTotal(total)
                                setRenamingCurrentName(getLocalized("Eliminando .nomedia...", "Deleting .nomedia..."))
                            },
                            onComplete = { deletedCount ->
                                setIsRenaming(false)
                                activeOrganizerAction = ""
                                android.widget.Toast.makeText(
                                    context,
                                    getLocalized(
                                        "Se eliminaron $deletedCount archivos .nomedia.",
                                        "Deleted $deletedCount .nomedia files."
                                    ),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                ) {
                    Text(
                        text = getLocalized("Borrar", "Delete"),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteNoMediaDialog = false }) {
                    Text(
                        text = getLocalized("Cancelar", "Cancel"),
                        color = settingsTextMutedColor()
                    )
                }
            }
        )
    }

    if (showDeleteLyricsDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteLyricsDialog = false },
            title = {
                Text(
                    text = getLocalized("¿Borrar archivos de letras?", "Delete lyrics files?"),
                    color = settingsTextColor(),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = getLocalized(
                        "Esta acción buscará y eliminará en paralelo los archivos físicos de letras (.lrc y .txt) de tus carpetas de música. Las letras almacenadas en la base de datos de la app continuarán intactas.",
                        "This will search and delete physical lyrics files (.lrc and .txt) in parallel from your music folders. Lyrics saved inside the app database will remain intact."
                    ),
                    color = settingsTextMutedColor()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteLyricsDialog = false
                        activeOrganizerAction = "delete_lyrics"
                        setIsRenaming(true)
                        viewModel.deleteAllLyricsFiles(
                            context = context,
                            onProgress = { current, total ->
                                setRenamingCurrent(current)
                                setRenamingTotal(total)
                                setRenamingCurrentName(getLocalized("Eliminando letras...", "Deleting lyrics..."))
                            },
                            onComplete = { deletedCount ->
                                setIsRenaming(false)
                                activeOrganizerAction = ""
                                android.widget.Toast.makeText(
                                    context,
                                    getLocalized(
                                        "Se eliminaron $deletedCount archivos de letras.",
                                        "Deleted $deletedCount lyrics files."
                                    ),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                ) {
                    Text(
                        text = getLocalized("Borrar", "Delete"),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteLyricsDialog = false }) {
                    Text(
                        text = getLocalized("Cancelar", "Cancel"),
                        color = settingsTextMutedColor()
                    )
                }
            }
        )
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

                    // 5. Statistics Option
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { exportStatistics = !exportStatistics },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = getLocalized("Estadísticas e Historial", "Statistics & History"),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = settingsTextColor()
                            )
                            Text(
                                text = getLocalized("Contadores de reproducción, última fecha reproducida, ReplayGain y ediciones de metadatos.", "Play counts, last played timestamps, ReplayGain, and metadata edits."),
                                fontSize = 11.sp,
                                color = settingsTextMutedColor()
                            )
                        }
                        Switch(
                            checked = exportStatistics,
                            onCheckedChange = { exportStatistics = it }
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
                                        includeStatistics = exportStatistics,
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
                                performExportToFolder(currentDirUri, exportSettings, exportEqualizer, exportPlaylists, exportLyrics, exportStatistics)
                            }
                        }
                    },
                    enabled = exportSettings || exportEqualizer || exportPlaylists || exportLyrics || exportStatistics,
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

