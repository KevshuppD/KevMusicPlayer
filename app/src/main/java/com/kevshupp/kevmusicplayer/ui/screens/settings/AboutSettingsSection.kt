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
            title = { Text(text = updateDialogTitle, fontWeight = FontWeight.Bold, color = settingsTextColor()) },
            text = {
                Column {
                    Text(text = updateDialogMessage, color = settingsTextColor().copy(alpha = 0.8f))
                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = settingsTextColor().copy(alpha = 0.1f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = getLocalized(
                                "Descargando: ${(downloadProgress * 100).toInt()}%",
                                "Downloading: ${(downloadProgress * 100).toInt()}%"
                            ),
                            color = settingsTextMutedColor(),
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
                        Text(text = getLocalized("Cancelar", "Cancel"), color = settingsTextMutedColor())
                    }
                }
            },
            containerColor = if (MaterialTheme.colorScheme.background == Color.White) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF1E2135),
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
                containerColor = settingsCardContainerColor()
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
                    color = settingsTextColor()
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
                    color = settingsTextMutedColor(),
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
                    border = BorderStroke(1.dp, settingsDividerColor()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = settingsTextColor(),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("GitHub del Desarrollador", "Developer GitHub Profile"),
                        fontWeight = FontWeight.Bold,
                        color = settingsTextColor()
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                HorizontalDivider(color = settingsDividerColor())

                Spacer(modifier = Modifier.height(12.dp))

                // Used libraries tag listing
                Text(
                    text = getLocalized("Tecnologías Utilizadas", "Libraries & Frameworks"),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = settingsTextMutedColor(),
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
                                .background(settingsDividerColor())
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = library,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = settingsTextMutedColor()
                            )
                        }
                    }
                }
            }
        }
    }
}

