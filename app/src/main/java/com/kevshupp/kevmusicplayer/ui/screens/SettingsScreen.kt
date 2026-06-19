package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import android.app.LocaleManager
import android.os.LocaleList
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.kevshupp.kevmusicplayer.R
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    enabledTabs: List<String>,
    onEnabledTabsChanged: (List<String>) -> Unit,
    sortBy: String,
    onSortByChanged: (String) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit,
    viewModel: com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var isScanning by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val localeManager = remember { context.getSystemService(LocaleManager::class.java) }
    val settingsPrefs = remember { context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE) }
    var selectedLanguage by remember {
        val systemLang = java.util.Locale.getDefault().language
        val defaultLang = if (systemLang in listOf("es", "en", "fr", "pt")) systemLang else "es"
        mutableStateOf(settingsPrefs.getString("language", defaultLang) ?: defaultLang)
    }

    val getLocalized = { es: String, en: String ->
        if (selectedLanguage == "es") es else en
    }

    var selectedTheme by remember {
        mutableStateOf(settingsPrefs.getString("app_theme", "cyberpunk") ?: "cyberpunk")
    }

    var disableAnimations by remember {
        mutableStateOf(settingsPrefs.getBoolean("disable_animations", false))
    }

    var selectedRefreshRate by remember {
        mutableStateOf(settingsPrefs.getString("refresh_rate", "120") ?: "120")
    }

    var backupDirUri by remember {
        mutableStateOf(settingsPrefs.getString("backup_dir_uri", null))
    }

    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(run {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        })
    }

    LaunchedEffect(Unit) {
        while (true) {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            isIgnoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName)
            delay(2000)
        }
    }

    var isRenaming by remember { mutableStateOf(false) }
    var renamingCurrent by remember { mutableStateOf(0) }
    var renamingTotal by remember { mutableStateOf(0) }
    var renamingCurrentName by remember { mutableStateOf("") }

    var showFolderList by remember { mutableStateOf(false) }
    var showDuplicateFinder by remember { mutableStateOf(false) }
    val deviceFolders = remember { viewModel.getAllDeviceFolders(context) }
    var excludedFolders by remember { mutableStateOf(viewModel.getExcludedFolders()) }

    var activeCategory by remember { mutableStateOf("general") }

    val categories = remember(selectedLanguage) {
        listOf(
            Triple("general", getLocalized("General", "General"), Icons.Rounded.Settings),
            Triple("audio", getLocalized("Audio", "Audio"), Icons.Rounded.Equalizer),
            Triple("system", getLocalized("Sistema", "System"), Icons.Rounded.Tune),
            Triple("library", getLocalized("Biblioteca", "Library"), Icons.Rounded.LibraryMusic),
            Triple("about", getLocalized("Acerca de", "About"), Icons.Rounded.Info)
        )
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                if (outputStream != null) {
                    viewModel.exportBackup(
                        context = context,
                        outputStream = outputStream,
                        onSuccess = {
                            android.widget.Toast.makeText(context, getLocalized("Copia de seguridad creada con éxito", "Backup created successfully"), android.widget.Toast.LENGTH_LONG).show()
                        },
                        onError = { error ->
                            android.widget.Toast.makeText(context, "${getLocalized("Error al crear copia:", "Failed to create backup:")} ${error.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    )
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "${getLocalized("Error de archivo:", "File error:")} ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
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
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "${getLocalized("Error de archivo:", "File error:")} ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
            }
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
            settingsPrefs.edit().putString("backup_dir_uri", uri.toString()).apply()
            backupDirUri = uri.toString()
            android.widget.Toast.makeText(context, getLocalized("Carpeta fija de copia configurada con éxito", "Fixed backup folder configured successfully"), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun performExportToFolder(folderUriStr: String) {
        try {
            val folderUri = Uri.parse(folderUriStr)
            val dirFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
            if (dirFile == null || !dirFile.exists()) {
                android.widget.Toast.makeText(context, getLocalized("La carpeta seleccionada ya no existe o no tiene permisos. Configúrala de nuevo.", "The selected folder no longer exists or lacks permissions. Configure it again."), android.widget.Toast.LENGTH_LONG).show()
                return
            }

            var backupFile = dirFile.findFile("kev_music_player_backup.json")
            if (backupFile == null) {
                backupFile = dirFile.createFile("application/json", "kev_music_player_backup.json")
            }

            val fileUri = backupFile?.uri
            if (fileUri != null) {
                val outputStream = context.contentResolver.openOutputStream(fileUri, "rwt")
                if (outputStream != null) {
                    viewModel.exportBackup(
                        context = context,
                        outputStream = outputStream,
                        onSuccess = {
                            android.widget.Toast.makeText(context, getLocalized("Copia de seguridad guardada y sobrescrita con éxito en la carpeta fija", "Backup saved and overwritten successfully in the fixed folder"), android.widget.Toast.LENGTH_LONG).show()
                        },
                        onError = { error ->
                            android.widget.Toast.makeText(context, "${getLocalized("Error al crear copia:", "Failed to create backup:")} ${error.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    )
                } else {
                    android.widget.Toast.makeText(context, getLocalized("No se pudo abrir el archivo para escribir", "Could not open file for writing"), android.widget.Toast.LENGTH_LONG).show()
                }
            } else {
                android.widget.Toast.makeText(context, getLocalized("No se pudo crear el archivo de copia", "Could not create backup file"), android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(context, "${getLocalized("Error de carpeta:", "Folder error:")} ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun applyLanguage(languageTag: String) {
        selectedLanguage = languageTag
        settingsPrefs.edit().putString("language", languageTag).apply()
        localeManager?.applicationLocales = LocaleList.forLanguageTags(languageTag)
        (context as? Activity)?.recreate()
    }

    // Helper functions for dynamic status queries
    fun hasAudioPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Automatic on Android versions < 13 (API 33)
        }
    }

    var audioGranted by remember { mutableStateOf(hasAudioPermission()) }
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission()) }

    // Launcher bindings for requesting permissions directly from settings
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        audioGranted = isGranted
        if (isGranted) {
            onRescan()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationGranted = isGranted
    }

    // Dynamic background worker to check permissions every second (covers external system settings modifications)
    LaunchedEffect(Unit) {
        while (true) {
            audioGranted = hasAudioPermission()
            notificationGranted = hasNotificationPermission()
            delay(1000)
        }
    }

    val isMonochrome = selectedTheme == "monochrome"

    // Overriding colorScheme for monochrome to prevent "plomo" bug
    val localColorScheme = if (isMonochrome) {
        lightColorScheme(
            primary = Color(0xFF000000),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE5E5E5),
            onPrimaryContainer = Color(0xFF000000),
            secondary = Color(0xFF555555),
            onSecondary = Color(0xFFFFFFFF),
            background = Color(0xFFFFFFFF),
            onBackground = Color(0xFF000000),
            surface = Color(0xFFF6F6F6),
            onSurface = Color(0xFF000000),
            surfaceVariant = Color(0xFFEEEEEE),
            onSurfaceVariant = Color(0xFF000000)
        )
    } else {
        MaterialTheme.colorScheme
    }

    // Settings screen is dark for dark themes, white for monochrome theme
    val backgroundBrush = remember(isMonochrome) {
        if (isMonochrome) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFFFFFFF),
                    Color(0xFFFFFFFF)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF121422),
                    Color(0xFF08090F)
                )
            )
        }
    }

    MaterialTheme(colorScheme = localColorScheme) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.settings_title),
                            fontWeight = FontWeight.Black,
                            fontSize = 24.sp,
                            color = if (isMonochrome) Color.Black else Color.White
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = if (isMonochrome) Color.Black else Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (isMonochrome) Color(0xFFFFFFFF) else Color(0xFF121422),
                        titleContentColor = if (isMonochrome) Color.Black else Color.White,
                        navigationIconContentColor = if (isMonochrome) Color.Black else Color.White
                    )
                )
            },
            containerColor = Color.Transparent,
            modifier = modifier.background(backgroundBrush)
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Horizontal Category Selector (Sticky at the top, just below TopBar)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .background(if (isMonochrome) Color(0xFFFFFFFF) else Color(0xFF121422))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    categories.forEach { (tag, label, icon) ->
                        val isSelected = activeCategory == tag
                        val containerColor = if (isSelected) {
                            if (isMonochrome) Color.Black else MaterialTheme.colorScheme.primary
                        } else {
                            if (isMonochrome) Color.Black.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.05f)
                        }
                        val contentColor = if (isSelected) {
                            if (isMonochrome) Color.White else Color.Black
                        } else {
                            if (isMonochrome) Color.Black else Color.White
                        }
                        val borderStroke = if (isSelected) null else BorderStroke(1.dp, if (isMonochrome) Color.Black.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.1f))

                        Surface(
                            onClick = { activeCategory = tag },
                            shape = RoundedCornerShape(20.dp),
                            color = containerColor,
                            contentColor = contentColor,
                            border = borderStroke,
                            modifier = Modifier.height(40.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    when (activeCategory) {
                        "general" -> {
                            GeneralSettingsSection(
                                selectedTheme = selectedTheme,
                                onThemeSelected = { selectedTheme = it },
                                selectedLanguage = selectedLanguage,
                                applyLanguage = { applyLanguage(it) },
                                sortBy = sortBy,
                                onSortByChanged = onSortByChanged,
                                getLocalized = getLocalized,
                                settingsPrefs = settingsPrefs
                            )
                        }
                        "audio" -> {
                            AudioSettingsSection(
                                context = context,
                                scope = scope,
                                getLocalized = getLocalized
                            )
                        }
                        "system" -> {
                            SystemSettingsSection(
                                selectedRefreshRate = selectedRefreshRate,
                                onRefreshRateSelected = { selectedRefreshRate = it },
                                disableAnimations = disableAnimations,
                                onDisableAnimationsChanged = { disableAnimations = it },
                                audioGranted = audioGranted,
                                notificationGranted = notificationGranted,
                                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                                audioPermissionLauncher = audioPermissionLauncher,
                                notificationPermissionLauncher = notificationPermissionLauncher,
                                getLocalized = getLocalized,
                                settingsPrefs = settingsPrefs,
                                context = context
                            )
                        }
                        "library" -> {
                            LibrarySettingsSection(
                                enabledTabs = enabledTabs,
                                onEnabledTabsChanged = onEnabledTabsChanged,
                                viewModel = viewModel,
                                context = context,
                                scope = scope,
                                isScanning = isScanning,
                                onRescan = onRescan,
                                setIsScanning = { isScanning = it },
                                backupDirUri = backupDirUri,
                                selectBackupFolderLauncher = selectBackupFolderLauncher,
                                openDocumentLauncher = openDocumentLauncher,
                                createDocumentLauncher = createDocumentLauncher,
                                performExportToFolder = ::performExportToFolder,
                                getLocalized = getLocalized,
                                isRenaming = isRenaming,
                                setIsRenaming = { isRenaming = it },
                                renamingCurrent = renamingCurrent,
                                setRenamingCurrent = { renamingCurrent = it },
                                renamingTotal = renamingTotal,
                                setRenamingTotal = { renamingTotal = it },
                                renamingCurrentName = renamingCurrentName,
                                setRenamingCurrentName = { renamingCurrentName = it },
                                showFolderList = showFolderList,
                                setShowFolderList = { showFolderList = it },
                                deviceFolders = deviceFolders,
                                excludedFolders = excludedFolders,
                                setExcludedFolders = { excludedFolders = it },
                                onFindDuplicates = { showDuplicateFinder = true }
                            )
                        }
                        "about" -> {
                            AboutSettingsSection(
                                context = context,
                                scope = scope,
                                getLocalized = getLocalized
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDuplicateFinder) {
        DuplicateFinderDialog(
            viewModel = viewModel,
            onDismiss = { showDuplicateFinder = false }
        )
    }
}
