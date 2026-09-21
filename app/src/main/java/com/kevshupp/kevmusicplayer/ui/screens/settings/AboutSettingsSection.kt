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
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = getLocalized("INFORMACIÓN Y ACTUALIZACIONES", "ABOUT & UPDATES"),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 8.dp)
        )

        // 1. Main App Identity & Update Card
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
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = settingsTextColor()
                )

                Text(
                    text = getLocalized("Versión v$versionName ($buildTypeText) • 120Hz Native", "Version v$versionName ($buildTypeText) • 120Hz Native"),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = getLocalized(
                        "Reproductor de música nativo de alto rendimiento, ligero y offline con interfaz moderna en Jetpack Compose y sincronización en la nube vía Google Firebase.",
                        "High-performance, lightweight and offline native music player with modern Jetpack Compose UI and Google Firebase cloud synchronization."
                    ),
                    fontSize = 12.sp,
                    color = settingsTextMutedColor(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))

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

                Spacer(modifier = Modifier.height(12.dp))

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
                        text = getLocalized("GitHub del Desarrollador (@KevshuppD)", "Developer GitHub (@KevshuppD)"),
                        fontWeight = FontWeight.Bold,
                        color = settingsTextColor()
                    )
                }
            }
        }

        // 2. Licencia y Derechos de Autor (License Card)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = settingsCardContainerColor()),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = getLocalized("Licencia de Software", "Software License"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = settingsTextColor()
                        )
                        Text(
                            text = getLocalized("Código Abierto • Licencia MIT", "Open Source • MIT License"),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = getLocalized(
                        "KevMusicPlayer es un proyecto de código abierto desarrollado bajo la Licencia MIT. Tienes total libertad para usar, estudiar, modificar y compartir el código respetando los términos de autoría y atribución original.",
                        "KevMusicPlayer is an open-source project released under the MIT License. You have complete freedom to use, study, modify, and distribute the code while preserving original copyright and attribution."
                    ),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = settingsTextMutedColor()
                )
            }
        }

        // 3. Privacidad y Protección de Datos (Privacy & Data Protection Card)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = settingsCardContainerColor()),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E5FF).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = getLocalized("Privacidad y Protección de Datos", "Privacy & Data Protection"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = settingsTextColor()
                        )
                        Text(
                            text = getLocalized("Arquitectura Local-First & Nube Privada", "Local-First Architecture & Private Cloud"),
                            fontSize = 12.sp,
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                val privacyPoints = listOf(
                    Triple(
                        Icons.Rounded.Lock,
                        getLocalized("Cero Rastreadores Comerciales", "Zero Commercial Trackers"),
                        getLocalized("No se recopilan ni venden datos de uso, hábitos ni perfiles a empresas publicitarias o analíticas invasivas.", "No usage data, habits, or user profiles are collected or sold to advertising or invasive tracking companies.")
                    ),
                    Triple(
                        Icons.Rounded.FolderShared,
                        getLocalized("Archivos de Música 100% Locales", "100% Local Music Files"),
                        getLocalized("Tus archivos de audio nunca se suben ni transfieren a servidores externos. Todo el procesamiento y reproducción ocurre en tu dispositivo.", "Your audio files are never uploaded or transferred to external servers. All processing and playback occurs strictly on your device.")
                    ),
                    Triple(
                        Icons.Rounded.CloudQueue,
                        getLocalized("Respaldo Cifrado en Google Firestore", "Encrypted Google Firestore Backup"),
                        getLocalized("Las listas, letras y estadísticas se sincronizan comprimidas en GZIP únicamente bajo la ruta privada de tu cuenta de Google.", "Playlists, lyrics, and listening stats are synced with GZIP compression solely inside your private Google account path.")
                    ),
                    Triple(
                        Icons.AutoMirrored.Rounded.Rule,
                        getLocalized("Permisos Estrictamente Necesarios", "Strictly Necessary Permissions"),
                        getLocalized("Solo se solicitan permisos de lectura multimedia de Android y notificaciones en segundo plano.", "Only Android media read permissions and background audio notification permissions are requested.")
                    )
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    privacyPoints.forEach { (icon, title, desc) ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .padding(top = 2.dp)
                            )
                            Column {
                                Text(
                                    text = title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = settingsTextColor()
                                )
                                Text(
                                    text = desc,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = settingsTextMutedColor()
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Tecnologías y Arquitectura Utilizadas (Professional Tech Stack Showcase)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = settingsCardContainerColor()),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF0055).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Code,
                            contentDescription = null,
                            tint = Color(0xFFFF0055),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = getLocalized("Stack Tecnológico y Arquitectura", "Tech Stack & Architecture"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = settingsTextColor()
                        )
                        Text(
                            text = getLocalized("Ingeniería Android Moderna", "Modern Android Engineering"),
                            fontSize = 12.sp,
                            color = Color(0xFFFF0055),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                val techCards = listOf(
                    Pair("Jetpack Compose & Material 3", getLocalized("Interfaz declarativa reactiva con diseño Glassmorphism y soporte de tasa de refresco a 120Hz.", "Reactive declarative UI with Glassmorphism design and 120Hz refresh rate support.")),
                    Pair("AndroidX Media3 ExoPlayer", getLocalized("Servicio multimedia de baja latencia con reproducción en segundo plano y control de notificaciones.", "Low-latency multimedia service with background playback and notification controls.")),
                    Pair("DSP Audio FX Processing", getLocalized("Ecualizador de 5 bandas, refuerzo de graves (Bass Boost), sonido virtualizado y normalización ReplayGain.", "5-band equalizer, Bass Boost, Virtualizer surround sound, and ReplayGain normalization.")),
                    Pair("Room SQLite Database", getLocalized("Almacenamiento local indexado para búsqueda instantánea en colecciones de miles de canciones.", "Indexed local storage for instant search across collections of thousands of songs.")),
                    Pair("Google Firebase Cloud Services", getLocalized("Autenticación con Google Play Services y respaldos NoSQL comprimidos en Cloud Firestore.", "Google Play Services authentication and GZIP compressed NoSQL backups on Cloud Firestore.")),
                    Pair("Jaudiotagger & Mp3agic", getLocalized("Edición física de etiquetas ID3v1/v2, FLAC, Vorbis y MP4 sin alterar la fidelidad sonora.", "Physical tag editing for ID3v1/v2, FLAC, Vorbis, and MP4 without altering sound fidelity.")),
                    Pair("Coil & Multi-Level Art Cache", getLocalized("Caché en 5 niveles (RAM LruCache + WebP en disco + aceleración MediaStore por hardware).", "5-tier cache hierarchy (RAM LruCache + WebP on disk + hardware MediaStore thumbnails).")),
                    Pair("Deezer API & OkHttp", getLocalized("Búsqueda y descarga de carátulas en ultra alta definición (1000x1000) y letras sincronizadas LRC.", "Ultra high-resolution cover search (1000x1000) and synchronized LRC lyrics fetching."))
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    techCards.forEach { (techName, techDesc) ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(settingsDividerColor().copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Column {
                                Text(
                                    text = techName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = techDesc,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
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

