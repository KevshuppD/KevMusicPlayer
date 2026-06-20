package com.kevshupp.kevmusicplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import com.kevshupp.kevmusicplayer.ui.screens.LibraryScreen
import com.kevshupp.kevmusicplayer.ui.screens.PlayerScreen
import com.kevshupp.kevmusicplayer.ui.screens.SettingsScreen
import com.kevshupp.kevmusicplayer.ui.theme.KevMusicPlayerTheme
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import android.app.Activity
import com.kevshupp.kevmusicplayer.ui.screens.getPhysicalPathFromTreeUri

class MainActivity : ComponentActivity() {
    private val refreshRateListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "refresh_rate") {
            runOnUiThread {
                applyRefreshRate(prefs.getString("refresh_rate", "120") ?: "120")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

                // Apply saved language preference before composing UI
        val settingsPrefs = getSharedPreferences("settings_prefs", MODE_PRIVATE)
        val languageTag = settingsPrefs.getString("language", "es") ?: "es"
        val localeManager = getSystemService(LocaleManager::class.java)
        localeManager?.applicationLocales = LocaleList.forLanguageTags(languageTag)

        // Apply initial refresh rate
        applyRefreshRate(settingsPrefs.getString("refresh_rate", "120") ?: "120")
        settingsPrefs.registerOnSharedPreferenceChangeListener(refreshRateListener)

        setContent {
            KevMusicPlayerTheme {
                AppNavigation()
            }
        }
    }

    private fun applyRefreshRate(rate: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val window = this.window
                val params = window.attributes
                val display = this.display
                if (display != null) {
                    val supportedModes = display.supportedModes
                    val targetMode = if (rate == "60") {
                        supportedModes.minByOrNull { Math.abs(it.refreshRate - 60f) }
                    } else {
                        supportedModes.maxByOrNull { it.refreshRate }
                    }
                    if (targetMode != null) {
                        params.preferredDisplayModeId = targetMode.modeId
                        window.attributes = params
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val settingsPrefs = getSharedPreferences("settings_prefs", MODE_PRIVATE)
        settingsPrefs.unregisterOnSharedPreferenceChangeListener(refreshRateListener)
    }
}

@Serializable
sealed interface Screen : NavKey {
    @Serializable
    data object PermissionRequest : Screen
    @Serializable
    data object Library : Screen
    @Serializable
    data class Player(val fileId: Long) : Screen
    @Serializable
    data object Settings : Screen
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val settingsPrefs = remember { context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE) }
    val initialScreen = remember(context) {
        if (checkInitialPermissions(context)) Screen.Library else Screen.PermissionRequest
    }
    val backStack = rememberNavBackStack(initialScreen as NavKey)
    val viewModel: MediaBrowserViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()

    // Intercept system back gestures to pop screens from backstack instead of closing the app!
    BackHandler(enabled = backStack.size > 1) {
        backStack.removeAt(backStack.size - 1)
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    if (checkInitialPermissions(context) && !settingsPrefs.getBoolean("is_first_run", true)) {
                        viewModel.connect()
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    val activity = context.findActivity()
                    if (activity == null || !activity.isChangingConfigurations) {
                        viewModel.disconnect()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val activity = context.findActivity()
            if (activity == null || !activity.isChangingConfigurations) {
                viewModel.disconnect()
            }
        }
    }

    val disableAnimations = com.kevshupp.kevmusicplayer.ui.theme.LocalDisableAnimations.current

    Box(modifier = Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            sceneStrategy = listDetailStrategy,
            transitionSpec = {
                if (disableAnimations) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) togetherWith 
                            androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300))
                }
            },
            popTransitionSpec = {
                if (disableAnimations) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) togetherWith 
                            androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300))
                }
            },
            onBack = { 
                if (backStack.size > 1) {
                    backStack.removeAt(backStack.size - 1)
                }
            },
            entryProvider = entryProvider {
                entry<Screen.PermissionRequest> {
                    PermissionRequestScreen(
                        onPermissionsGranted = {
                            backStack.clear()
                            backStack.add(Screen.Library)
                            if (!settingsPrefs.getBoolean("is_first_run", true)) {
                                viewModel.connect()
                            }
                        }
                    )
                }
                entry<Screen.Library>(
                    metadata = ListDetailSceneStrategy.listPane(
                        detailPlaceholder = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Select a song to start playing")
                            }
                        }
                    )
                ) {
                    LibraryScreen(
                        audioFiles = viewModel.localAudioFiles,
                        player = viewModel.browser.value,
                        onFileClick = { file, customQueue ->
                            viewModel.playFile(file, customQueue)
                            // In adaptive layout, adding Player to backstack shows it in detail pane
                            if (backStack.none { it is Screen.Player && it.fileId == file.id }) {
                                backStack.add(Screen.Player(file.id))
                            }
                        },
                        onMiniPlayerClick = {
                            val currentId = viewModel.browser.value?.currentMediaItem?.mediaId?.toLongOrNull() ?: 0L
                            if (backStack.none { it is Screen.Player && it.fileId == currentId }) {
                                backStack.add(Screen.Player(currentId))
                            }
                        },
                        onSettingsClick = {
                            backStack.add(Screen.Settings)
                        },
                        enabledTabs = viewModel.enabledTabs.value,
                        sortBy = viewModel.sortBy.value,
                        viewModel = viewModel,
                        isActive = backStack.lastOrNull() == Screen.Library
                    )
                }
                entry<Screen.Settings> {
                    SettingsScreen(
                        enabledTabs = viewModel.enabledTabs.value,
                        onEnabledTabsChanged = { viewModel.updateEnabledTabs(it) },
                        sortBy = viewModel.sortBy.value,
                        onSortByChanged = { viewModel.updateSortBy(it) },
                        onRescan = { viewModel.scanFiles(isManual = true) },
                        onBack = {
                            if (backStack.size > 1) {
                                backStack.removeAt(backStack.size - 1)
                            }
                        },
                        viewModel = viewModel
                    )
                }
                entry<Screen.Player>(
                    metadata = ListDetailSceneStrategy.detailPane()
                ) {
                    PlayerScreen(
                        player = viewModel.browser.value,
                        viewModel = viewModel,
                        onBack = {
                            if (backStack.size > 1) {
                                backStack.removeAt(backStack.size - 1)
                            }
                        },
                        onNavigateToArtist = { artistName ->
                            viewModel.requestedTab.value = "Artists"
                            viewModel.requestedSubViewType.value = "Artist"
                            viewModel.requestedSubViewName.value = artistName
                        },
                        onNavigateToAlbum = { albumName ->
                            viewModel.requestedTab.value = "Albums"
                            viewModel.requestedSubViewType.value = "Album"
                            viewModel.requestedSubViewName.value = albumName
                        }
                    )
                }
            }
        )

        var isFirstRun by remember { mutableStateOf(settingsPrefs.getBoolean("is_first_run", true)) }
        val permissionsGranted = checkInitialPermissions(context)
        val hasLibraryScreen = backStack.any { it is Screen.Library }
        val showOnboarding = isFirstRun && permissionsGranted && hasLibraryScreen

        if (showOnboarding) {
            OnboardingFlow(
                onDismiss = { 
                    settingsPrefs.edit().putBoolean("is_first_run", false).apply()
                    isFirstRun = false
                    viewModel.connect()
                },
                viewModel = viewModel,
                settingsPrefs = settingsPrefs
            )
        }

        var updateInfo by remember { mutableStateOf<com.kevshupp.kevmusicplayer.data.UpdateInfo?>(null) }
        var downloadProgress by remember { mutableStateOf<Float?>(null) }
        var showUpdateDialog by remember { mutableStateOf(false) }

        LaunchedEffect(permissionsGranted, isFirstRun) {
            if (permissionsGranted && !isFirstRun) {
                val info = com.kevshupp.kevmusicplayer.data.AppUpdater.checkUpdate(context)
                if (info != null) {
                    updateInfo = info
                    showUpdateDialog = true
                }
            }
        }

        if (showUpdateDialog && updateInfo != null) {
            val info = updateInfo!!
            val isDownloading = downloadProgress != null
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    if (!isDownloading) {
                        showUpdateDialog = false
                    }
                },
                title = {
                    Text(
                        text = "Actualización Disponible (${info.version})",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isDownloading) {
                            Text(
                                text = "Descargando actualización... ${(downloadProgress!! * 100).toInt()}%",
                                color = Color.White.copy(alpha = 0.7f)
                            )
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { downloadProgress!! },
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.White.copy(alpha = 0.1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = "¿Deseas descargar e instalar la nueva versión de Kev Music Player?",
                                color = Color.White
                            )
                            if (info.changelog.isNotBlank()) {
                                Text(
                                    text = "Novedades:",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 14.sp
                                )
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 150.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
                                ) {
                                    androidx.compose.foundation.lazy.LazyColumn(
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        item {
                                            Text(
                                                text = info.changelog,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color.White.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    if (!isDownloading) {
                        Button(
                            onClick = {
                                downloadProgress = 0f
                                scope.launch {
                                    val targetFile = java.io.File(context.cacheDir, "KevMusicPlayer-update.apk")
                                    val success = com.kevshupp.kevmusicplayer.data.AppUpdater.downloadApk(
                                        context = context,
                                        downloadUrl = info.downloadUrl,
                                        targetFile = targetFile
                                    ) { progress ->
                                        downloadProgress = progress
                                    }
                                    if (success) {
                                        showUpdateDialog = false
                                        downloadProgress = null
                                        com.kevshupp.kevmusicplayer.data.AppUpdater.installApk(context, targetFile)
                                    } else {
                                        downloadProgress = null
                                        android.widget.Toast.makeText(
                                            context,
                                            "Error al descargar la actualización",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.Black
                            )
                        ) {
                            Text("Descargar e Instalar", fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    if (!isDownloading) {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("Más tarde", color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                },
                containerColor = Color(0xFF161829),
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }


    }
}

@Composable
fun PermissionRequestScreen(onPermissionsGranted: () -> Unit) {
    val context = LocalContext.current
    var permissionsGranted by remember {
        mutableStateOf(checkInitialPermissions(context))
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val standardGranted = checkStandardPermissions(context)
        if (standardGranted) {
            val manageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else {
                true
            }
            if (manageGranted) {
                permissionsGranted = true
                onPermissionsGranted()
            } else {
                // If standard granted but manage is not, launch the Manage All Files settings screen immediately!
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        context.startActivity(intent)
                    }
                }
            }
        }
    }

    // Modern Lifecycle observer to automatically re-check permissions when returning to the app
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val granted = checkInitialPermissions(context)
                permissionsGranted = granted
                if (granted) {
                    onPermissionsGranted()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        val standardGranted = checkStandardPermissions(context)
        if (!standardGranted) {
            val permissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            launcher.launch(permissions.toTypedArray())
        } else {
            val manageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else {
                true
            }
            if (manageGranted) {
                permissionsGranted = true
                onPermissionsGranted()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        context.startActivity(intent)
                    }
                }
            }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            val isStandard = checkStandardPermissions(context)
            val isManage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else {
                true
            }
            if (isStandard && !isManage) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally, 
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = "Se requiere acceso a todos los archivos para poder leer y borrar canciones.", 
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Button(onClick = {
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            context.startActivity(intent)
                        }
                    }) {
                        Text("Dar Acceso a Todos los Archivos")
                    }
                }
            } else if (permissionsGranted) {
                Text(text = "Cargando Biblioteca...", color = MaterialTheme.colorScheme.onBackground)
            } else {
                Text(
                    text = "Por favor, concede permisos de almacenamiento para acceder a tu música.", 
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    }
}

private fun checkStandardPermissions(context: android.content.Context): Boolean {
    val permissions = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    return permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun checkInitialPermissions(context: android.content.Context): Boolean {
    val standard = checkStandardPermissions(context)
    val manage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else {
        true
    }
    return standard && manage
}

@Composable
fun OnboardingFlow(
    onDismiss: () -> Unit,
    viewModel: MediaBrowserViewModel,
    settingsPrefs: android.content.SharedPreferences
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(1) } // 1: Backup check, 2: Music library source, 3: Theme selection
    var selectedTheme by remember {
        mutableStateOf(settingsPrefs.getString("app_theme", "cyberpunk") ?: "cyberpunk")
    }

    // Dynamic listener to update the state when the theme changes
    val listener = remember {
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "app_theme") {
                selectedTheme = prefs.getString("app_theme", "cyberpunk") ?: "cyberpunk"
            }
        }
    }
    DisposableEffect(settingsPrefs) {
        settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val selectBackupFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                
                settingsPrefs.edit().putString("backup_dir_uri", uri.toString()).apply()
                
                val dirFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                val backupFile = dirFile?.findFile("kev_music_player_backup.json")
                if (backupFile != null) {
                    val inputStream = context.contentResolver.openInputStream(backupFile.uri)
                    if (inputStream != null) {
                        viewModel.importBackup(
                             context = context,
                             inputStream = inputStream,
                             onSuccess = {
                                 settingsPrefs.edit().putBoolean("is_first_run", false).apply()
                                 android.widget.Toast.makeText(context, "Copia de seguridad restaurada con éxito", android.widget.Toast.LENGTH_LONG).show()
                                 onDismiss()
                             },
                             onError = { error ->
                                 android.widget.Toast.makeText(context, "Error al restaurar: ${error.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                 step = 2
                             }
                        )
                    }
                } else {
                    android.widget.Toast.makeText(context, "No se encontró ningún archivo 'kev_music_player_backup.json' en la carpeta seleccionada.", android.widget.Toast.LENGTH_LONG).show()
                    step = 2
                }
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(context, "Error al acceder a la carpeta: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                step = 2
            }
        }
    }

    val selectOnboardingMusicFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                
                val path = getPhysicalPathFromTreeUri(context, uri)
                if (path != null) {
                    settingsPrefs.edit().putString("music_folder_path", path).apply()
                    android.widget.Toast.makeText(context, "Biblioteca configurada en: $path", android.widget.Toast.LENGTH_SHORT).show()
                    step = 3
                } else {
                    android.widget.Toast.makeText(context, "No se pudo obtener la ruta física de la carpeta", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(context, "Error al configurar carpeta: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.TopStart)
                .offset(x = (-100).dp, y = (-50).dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 100.dp, y = 100.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (selectedTheme == "monochrome") MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                if (step == 1) {
                    Icon(
                        imageVector = Icons.Rounded.Backup,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp)
                    )

                    Text(
                        text = "¡Bienvenido a Kev Music Player!",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "¿Tienes una copia de seguridad previa de tus ajustes y listas de reproducción?",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            selectBackupFolderLauncher.launch(null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Seleccionar Carpeta de Copia",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { step = 2 },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                    ) {
                        Text(
                            "Omitir y Configurar Biblioteca",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                } else if (step == 2) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp)
                    )

                    Text(
                        text = "¿Dónde está tu música?",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Puedes seleccionar una carpeta específica para tu música o permitir que la aplicación escanee todo el dispositivo.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            selectOnboardingMusicFolderLauncher.launch(null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Elegir carpeta específica",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            settingsPrefs.edit().remove("music_folder_path").apply()
                            step = 3
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                    ) {
                        Text(
                            "Escanear todo el dispositivo",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp)
                    )

                    Text(
                        text = "Elige tu Estilo",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Selecciona un tema de color que se adapte a tu personalidad:",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val themes = listOf(
                            Triple("cyberpunk", "Cyberpunk Rosa", Brush.horizontalGradient(listOf(Color(0xFF8A2BE2), Color(0xFFFF007F)))),
                            Triple("cyberpunk_purpura", "Cyberpunk Púrpura", Brush.horizontalGradient(listOf(Color(0xFF0C0514), Color(0xFFD000FF)))),
                            Triple("petrol", "Azul Petróleo", Brush.horizontalGradient(listOf(Color(0xFF005F73), Color(0xFF0A9396)))),
                            Triple("turquoise", "Turquesa", Brush.horizontalGradient(listOf(Color(0xFF00F5D4), Color(0xFF00BBF9)))),
                            Triple("obsidian", "Obsidiana Oscuro", Brush.horizontalGradient(listOf(Color(0xFF1A1A1A), Color(0xFF0A0A0A)))),
                            Triple("monochrome", "Blanco y Negro", Brush.horizontalGradient(listOf(Color(0xFFFFFFFF), Color(0xFF888888))))
                        )

                        themes.forEach { (tag, name, previewBrush) ->
                            val isSelected = selectedTheme == tag
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f) else Color.Transparent)
                                    .clickable {
                                        selectedTheme = tag
                                        settingsPrefs.edit().putString("app_theme", tag).apply()
                                    }
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(previewBrush)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = name,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selectedTheme = tag
                                        settingsPrefs.edit().putString("app_theme", tag).apply()
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary,
                                        unselectedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            settingsPrefs.edit().putBoolean("is_first_run", false).apply()
                            viewModel.scanFiles(isManual = true)
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            "Comenzar",
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var context = this
    while (context is android.content.ContextWrapper) {
        if (context is android.app.Activity) return context
        context = context.baseContext
    }
    return null
}
