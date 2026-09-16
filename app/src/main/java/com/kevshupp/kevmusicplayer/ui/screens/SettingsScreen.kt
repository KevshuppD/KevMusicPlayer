@file:Suppress("DEPRECATION")

package com.kevshupp.kevmusicplayer.ui.screens

import com.kevshupp.kevmusicplayer.ui.screens.dialogs.*
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import android.app.Activity
import android.app.LocaleManager
import android.os.LocaleList
import android.content.Intent
import android.net.Uri
import com.kevshupp.kevmusicplayer.R
import com.kevshupp.kevmusicplayer.ui.screens.settings.*
import java.util.Locale

data class SettingSearchItem(
    val title: String,
    val description: String,
    val categoryKey: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val action: (() -> Unit)? = null
)

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
    val focusManager = LocalFocusManager.current
    var isScanning by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val localeManager = remember { context.getSystemService(LocaleManager::class.java) }
    val settingsPrefs = remember { context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE) }
    
    var selectedLanguage by remember {
        val systemLang = Locale.getDefault().language
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
    var showIntegrityChecker by remember { mutableStateOf(false) }
    var showShortSongsFinder by remember { mutableStateOf(false) }
    var showMissingCoverFinder by remember { mutableStateOf(false) }
    val deviceFolders = remember { viewModel.getAllDeviceFolders(context) }
    var excludedFolders by remember { mutableStateOf(viewModel.getExcludedFolders()) }

    // Navigation state: null = Hub view, "general" / "audio" / "performance" / "system" / "library" / "about" = Focused subpage
    var activeCategory by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // Handle Back Press hierarchy
    val handleBack = {
        if (isSearchActive || searchQuery.isNotEmpty()) {
            searchQuery = ""
            isSearchActive = false
            focusManager.clearFocus()
        } else if (activeCategory != null) {
            activeCategory = null
        } else {
            onBack()
        }
    }

    BackHandler(enabled = activeCategory != null || isSearchActive || searchQuery.isNotEmpty()) {
        handleBack()
    }

    val categories = remember(selectedLanguage) {
        listOf(
            Triple("general", getLocalized("Apariencia y Tema", "Appearance & Theme"), Icons.Rounded.Palette),
            Triple("library", getLocalized("Biblioteca y Música", "Library & Music"), Icons.Rounded.LibraryMusic),
            Triple("audio", getLocalized("Audio y Sonido", "Audio & Sound"), Icons.Rounded.Equalizer),
            Triple("performance", getLocalized("Rendimiento y Memoria", "Performance & Memory"), Icons.Rounded.Speed),
            Triple("system", getLocalized("Sistema y Permisos", "System & Permissions"), Icons.Rounded.Tune),
            Triple("about", getLocalized("Acerca de KevMusic", "About KevMusic"), Icons.Rounded.Info)
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
                            (context as? Activity)?.recreate()
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

    fun performExportToFolder(
        folderUriStr: String,
        includeSettings: Boolean = true,
        includeEqualizer: Boolean = true,
        includePlaylists: Boolean = true,
        includeLyrics: Boolean = true,
        includeStatistics: Boolean = true
    ) {
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
                        includeSettings = includeSettings,
                        includeEqualizer = includeEqualizer,
                        includePlaylists = includePlaylists,
                        includeLyrics = includeLyrics,
                        includeStatistics = includeStatistics,
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
            true
        }
    }

    var audioGranted by remember { mutableStateOf(hasAudioPermission()) }
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission()) }

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

    LaunchedEffect(Unit) {
        while (true) {
            audioGranted = hasAudioPermission()
            notificationGranted = hasNotificationPermission()
            delay(1000)
        }
    }

    val isMonochrome = selectedTheme == "monochrome"

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

    val backgroundBrush = remember(isMonochrome) {
        if (isMonochrome) {
            Brush.verticalGradient(
                colors = listOf(Color(0xFFFFFFFF), Color(0xFFFFFFFF))
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(Color(0xFF121422), Color(0xFF08090F))
            )
        }
    }

    // Dynamic stats summary
    val totalSongs = viewModel.localAudioFiles.size

    // Searchable Index of Settings
    val searchableSettings = remember(selectedLanguage) {
        listOf(
            SettingSearchItem(
                title = getLocalized("Temas de Colores", "Color Themes"),
                description = getLocalized("Cyberpunk, Petróleo, Turquesa, Obsidiana, Monocromo", "Cyberpunk, Petrol, Turquoise, Obsidian, Monochrome"),
                categoryKey = "general",
                icon = Icons.Rounded.Palette
            ),
            SettingSearchItem(
                title = getLocalized("Transparencias y Efectos", "Transparency & Effects"),
                description = getLocalized("Efecto Glassmorphism translúcido en barra y tarjetas", "Translucent glassmorphism effect on bar and cards"),
                categoryKey = "general",
                icon = Icons.Rounded.BlurOn
            ),
            SettingSearchItem(
                title = getLocalized("Personalizar Reproductor", "Customize Player"),
                description = getLocalized("Fondo dinámico, intensidad del glow, bordes y visualizador", "Dynamic background, glow intensity, borders, and visualizer"),
                categoryKey = "general",
                icon = Icons.Rounded.Tune
            ),
            SettingSearchItem(
                title = getLocalized("Idioma de la Aplicación", "App Language"),
                description = getLocalized("Español, English", "Spanish, English"),
                categoryKey = "general",
                icon = Icons.Rounded.Language
            ),
            SettingSearchItem(
                title = getLocalized("Criterio de Ordenación", "Sort Preference"),
                description = getLocalized("Alfabético, artista, duración de canciones", "Alphabetical, artist, track duration"),
                categoryKey = "general",
                icon = Icons.Rounded.SortByAlpha
            ),
            SettingSearchItem(
                title = getLocalized("Sincronización en la Nube", "Cloud Synchronization"),
                description = getLocalized("Copia de seguridad en Firestore vinculada a tu cuenta de Google", "Cloud backup on Firestore linked to your Google account"),
                categoryKey = "library",
                icon = Icons.Rounded.CloudSync
            ),
            SettingSearchItem(
                title = getLocalized("Escanear Biblioteca", "Scan Music Library"),
                description = getLocalized("Buscar nuevas canciones, audios y actualizar etiquetas", "Scan for new songs, audio files, and update tags"),
                categoryKey = "library",
                icon = Icons.Rounded.Refresh,
                action = { onRescan() }
            ),
            SettingSearchItem(
                title = getLocalized("Carpetas de Música", "Music Folders"),
                description = getLocalized("Seleccionar carpetas específicas y excluir carpetas no deseadas", "Select specific folders and exclude unwanted directories"),
                categoryKey = "library",
                icon = Icons.Rounded.Folder
            ),
            SettingSearchItem(
                title = getLocalized("Pestañas Visibles", "Visible Tabs"),
                description = getLocalized("Mostrar u ocultar Canciones, Artistas, Álbumes, Listas, Géneros", "Show or hide Songs, Artists, Albums, Playlists, Genres"),
                categoryKey = "library",
                icon = Icons.Rounded.ViewColumn
            ),
            SettingSearchItem(
                title = getLocalized("Descarga de Carátulas", "Cover Artwork Provider"),
                description = getLocalized("Proveedor preferido de portadas (Deezer, Spotify, iTunes)", "Preferred artwork provider (Deezer, Spotify, iTunes)"),
                categoryKey = "library",
                icon = Icons.Rounded.Image
            ),
            SettingSearchItem(
                title = getLocalized("Buscador de Duplicados", "Duplicate Finder"),
                description = getLocalized("Detectar pistas duplicadas o repetidas en el dispositivo", "Detect duplicate or repeated tracks on device"),
                categoryKey = "library",
                icon = Icons.Rounded.ContentCopy,
                action = { showDuplicateFinder = true }
            ),
            SettingSearchItem(
                title = getLocalized("Comprobador de Integridad", "Audio Integrity Checker"),
                description = getLocalized("Encontrar archivos corruptos o ilegibles", "Find corrupt or unreadable audio files"),
                categoryKey = "library",
                icon = Icons.Rounded.HealthAndSafety,
                action = { showIntegrityChecker = true }
            ),
            SettingSearchItem(
                title = getLocalized("Filtrar Audios Cortos", "Short Audio Filter"),
                description = getLocalized("Ignorar audios de WhatsApp, notas de voz y tonos de llamada", "Ignore WhatsApp audios, voice notes, and ringtones"),
                categoryKey = "library",
                icon = Icons.Rounded.Timer,
                action = { showShortSongsFinder = true }
            ),
            SettingSearchItem(
                title = getLocalized("Ecualizador y Presets", "Equalizer & Presets"),
                description = getLocalized("Ecualizador de 5 bandas, perfiles Rock, Pop, Jazz, Bass, Heavy", "5-band equalizer, Rock, Pop, Jazz, Bass, Heavy profiles"),
                categoryKey = "audio",
                icon = Icons.Rounded.Equalizer
            ),
            SettingSearchItem(
                title = getLocalized("Refuerzo de Graves y Sonido 3D", "Bass Boost & Virtualizer"),
                description = getLocalized("Intensidad de bajos y sonido envolvente espacial", "Bass intensity and spatial virtualizer surround"),
                categoryKey = "audio",
                icon = Icons.Rounded.SurroundSound
            ),
            SettingSearchItem(
                title = getLocalized("Normalización de Volumen", "Volume Normalization"),
                description = getLocalized("Evita saltos bruscos de volumen entre canciones (ReplayGain)", "Avoid abrupt volume jumps between songs (ReplayGain)"),
                categoryKey = "audio",
                icon = Icons.Rounded.VolumeUp
            ),
            SettingSearchItem(
                title = getLocalized("Fundido entre Canciones (Crossfade)", "Crossfade Transition"),
                description = getLocalized("Transición suave y mezcla progresiva entre pistas", "Smooth transition and blending between tracks"),
                categoryKey = "audio",
                icon = Icons.Rounded.CompareArrows
            ),
            SettingSearchItem(
                title = getLocalized("Tasa de Refresco (120 Hz)", "Refresh Rate (120 Hz)"),
                description = getLocalized("Configura la pantalla a 120Hz para máxima fluidez táctil", "Set screen to 120Hz for maximum touch fluidity"),
                categoryKey = "performance",
                icon = Icons.Rounded.Speed
            ),
            SettingSearchItem(
                title = getLocalized("Caché y Calidad de Portadas", "Artwork Cache & Memory"),
                description = getLocalized("Ajusta la resolución de carátulas y capacidad de la memoria", "Adjust cover resolution and cache memory capacity"),
                categoryKey = "performance",
                icon = Icons.Rounded.Memory
            ),
            SettingSearchItem(
                title = getLocalized("Optimización de Batería", "Battery Optimization"),
                description = getLocalized("Permite reproducción fluida en segundo plano sin cortes", "Allows smooth background playback without interruptions"),
                categoryKey = "system",
                icon = Icons.Rounded.BatteryChargingFull
            ),
            SettingSearchItem(
                title = getLocalized("Permisos del Sistema", "System Permissions"),
                description = getLocalized("Permiso de audio, almacenamiento y notificaciones", "Audio, storage, and notification permissions"),
                categoryKey = "system",
                icon = Icons.Rounded.Security
            ),
            SettingSearchItem(
                title = getLocalized("Copia de Seguridad Local JSON", "Local JSON Backup"),
                description = getLocalized("Exporta o restaura tus datos a un archivo en tu almacenamiento", "Export or restore your data to a file on storage"),
                categoryKey = "library",
                icon = Icons.Rounded.Save
            ),
            SettingSearchItem(
                title = getLocalized("Acerca de KevMusic Player", "About KevMusic Player"),
                description = getLocalized("Versión, novedades, licencias y código abierto en GitHub", "Version, changelog, licenses, and GitHub open source"),
                categoryKey = "about",
                icon = Icons.Rounded.Info
            )
        )
    }

    val filteredSearch = remember(searchQuery, searchableSettings) {
        if (searchQuery.isBlank()) emptyList()
        else {
            val q = searchQuery.trim().lowercase()
            searchableSettings.filter {
                it.title.lowercase().contains(q) || it.description.lowercase().contains(q)
            }
        }
    }

    MaterialTheme(colorScheme = localColorScheme) {
        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isMonochrome) Color(0xFFFFFFFF) else Color(0xFF121422))
                ) {
                    TopAppBar(
                        title = {
                            if (isSearchActive) {
                                TextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = {
                                        Text(
                                            getLocalized("Buscar en ajustes...", "Search settings..."),
                                            fontSize = 15.sp,
                                            color = if (isMonochrome) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.5f)
                                        )
                                    },
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        focusedTextColor = if (isMonochrome) Color.Black else Color.White,
                                        unfocusedTextColor = if (isMonochrome) Color.Black else Color.White
                                    ),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                val currentCategory = categories.find { it.first == activeCategory }
                                if (activeCategory == null) {
                                    Column {
                                        Text(
                                            text = stringResource(R.string.settings_title),
                                            fontWeight = FontWeight.Black,
                                            fontSize = 22.sp,
                                            color = if (isMonochrome) Color.Black else Color.White
                                        )
                                        Text(
                                            text = getLocalized("Personalización y control de tu música", "Customization and audio controls"),
                                            fontSize = 11.sp,
                                            color = if (isMonochrome) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.5f)
                                        )
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (currentCategory != null) {
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = currentCategory.third,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                        }
                                        Text(
                                            text = currentCategory?.second ?: stringResource(R.string.settings_title),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp,
                                            color = if (isMonochrome) Color.Black else Color.White
                                        )
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = handleBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back",
                                    tint = if (isMonochrome) Color.Black else Color.White
                                )
                            }
                        },
                        actions = {
                            if (isSearchActive) {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Clear",
                                            tint = if (isMonochrome) Color.Black else Color.White
                                        )
                                    }
                                }
                            } else if (activeCategory == null) {
                                IconButton(onClick = { isSearchActive = true }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = "Search",
                                        tint = if (isMonochrome) Color.Black else Color.White
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = if (isMonochrome) Color.Black else Color.White,
                            navigationIconContentColor = if (isMonochrome) Color.Black else Color.White
                        )
                    )
                }
            },
            containerColor = Color.Transparent,
            modifier = modifier.background(backgroundBrush)
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isSearchActive && searchQuery.isNotEmpty()) {
                    // Search Results View
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${getLocalized("Resultados para", "Results for")} \"$searchQuery\" (${filteredSearch.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )

                        if (filteredSearch.isEmpty()) {
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = settingsCardContainerColor()),
                                modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Rounded.SearchOff, contentDescription = null, tint = settingsTextMutedColor(), modifier = Modifier.size(44.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        getLocalized("No se encontraron ajustes con ese término", "No settings found matching that keyword"),
                                        fontSize = 13.sp,
                                        color = settingsTextMutedColor()
                                    )
                                }
                            }
                        } else {
                            filteredSearch.forEach { item ->
                                Card(
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(containerColor = settingsCardContainerColor()),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (item.action != null) {
                                                item.action.invoke()
                                            } else {
                                                activeCategory = item.categoryKey
                                                isSearchActive = false
                                                searchQuery = ""
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                        }
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = settingsTextColor())
                                            Text(item.description, fontSize = 11.sp, color = settingsTextMutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = settingsTextMutedColor().copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                } else if (activeCategory == null) {
                    // MAIN SETTINGS HUB (Categorized Index)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 1. Google Cloud Backup & Sync Hero Card
                        CloudSyncCard(
                            viewModel = viewModel,
                            getLocalized = getLocalized
                        )

                        // 2. Settings Hub Section Categories
                        Text(
                            text = getLocalized("CATEGORÍAS DE AJUSTES", "SETTINGS CATEGORIES"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(start = 6.dp, top = 6.dp)
                        )

                        // --- 1. APARIENCIA ---
                        SettingsCategoryHubCard(
                            icon = Icons.Rounded.Palette,
                            iconColor = Color(0xFFFF4081),
                            title = getLocalized("Apariencia y Tema", "Appearance & Theme"),
                            subtitle = "${when (selectedTheme) {
                                "cyberpunk_purpura" -> getLocalized("Cyberpunk Púrpura", "Cyberpunk Purple")
                                "petrol" -> getLocalized("Azul Petróleo", "Petrol Blue")
                                "turquoise" -> getLocalized("Turquesa", "Turquoise")
                                "obsidian" -> getLocalized("Obsidiana Oscuro", "Deep Obsidian")
                                "monochrome" -> getLocalized("Monocromo", "Monochrome")
                                else -> getLocalized("Cyberpunk Rosa", "Cyberpunk Pink")
                            }} • ${if (selectedLanguage == "es") "Español" else "English"}",
                            chips = listOf(
                                getLocalized("Temas", "Themes"),
                                getLocalized("Glassmorphism", "Glassmorphism"),
                                getLocalized("Reproductor", "Player")
                            ),
                            onClick = { activeCategory = "general" }
                        )

                        // --- 2. BIBLIOTECA ---
                        SettingsCategoryHubCard(
                            icon = Icons.Rounded.LibraryMusic,
                            iconColor = Color(0xFF00E5FF),
                            title = getLocalized("Biblioteca y Música", "Library & Music"),
                            subtitle = "$totalSongs ${getLocalized("canciones detectadas", "songs detected")} • ${deviceFolders.size} ${getLocalized("carpetas", "folders")}",
                            chips = listOf(
                                getLocalized("Escanear", "Scan"),
                                getLocalized("Carpetas", "Folders"),
                                getLocalized("Carátulas", "Covers"),
                                getLocalized("Pestañas", "Tabs")
                            ),
                            onClick = { activeCategory = "library" }
                        )

                        // --- 3. AUDIO Y EFECTOS ---
                        SettingsCategoryHubCard(
                            icon = Icons.Rounded.Equalizer,
                            iconColor = Color(0xFF76FF03),
                            title = getLocalized("Audio y Sonido", "Audio & Sound"),
                            subtitle = getLocalized("Ecualizador 5 bandas, graves, normalización y crossfade", "5-band equalizer, bass, normalization and crossfade"),
                            chips = listOf(
                                getLocalized("Ecualizador", "Equalizer"),
                                getLocalized("Graves", "Bass"),
                                getLocalized("ReplayGain", "ReplayGain"),
                                getLocalized("Crossfade", "Crossfade")
                            ),
                            onClick = { activeCategory = "audio" }
                        )

                        // --- 4. RENDIMIENTO ---
                        SettingsCategoryHubCard(
                            icon = Icons.Rounded.Speed,
                            iconColor = Color(0xFFFFD600),
                            title = getLocalized("Rendimiento y Memoria", "Performance & Memory"),
                            subtitle = "$selectedRefreshRate Hz • ${if (disableAnimations) getLocalized("Sin animaciones", "No animations") else getLocalized("Animaciones fluidas", "Smooth animations")}",
                            chips = listOf(
                                "120 Hz",
                                getLocalized("Caché", "Cache"),
                                getLocalized("Optimización", "Optimization")
                            ),
                            onClick = { activeCategory = "performance" }
                        )

                        // --- 5. SISTEMA Y PERMISOS ---
                        SettingsCategoryHubCard(
                            icon = Icons.Rounded.Tune,
                            iconColor = Color(0xFFE040FB),
                            title = getLocalized("Sistema y Permisos", "System & Permissions"),
                            subtitle = if (audioGranted && notificationGranted) getLocalized("Todos los permisos concedidos", "All permissions granted") else getLocalized("Revisar permisos pendientes", "Review pending permissions"),
                            chips = listOf(
                                getLocalized("Permisos", "Permissions"),
                                getLocalized("Batería", "Battery"),
                                getLocalized("Segundo plano", "Background")
                            ),
                            onClick = { activeCategory = "system" }
                        )

                        // --- 6. HERRAMIENTAS DE MANTENIMIENTO ---
                        Card(
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(containerColor = settingsCardContainerColor()),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFFFF9100).copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.BuildCircle, contentDescription = null, tint = Color(0xFFFF9100), modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            getLocalized("Herramientas de Limpieza", "Cleaning Tools"),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = settingsTextColor()
                                        )
                                        Text(
                                            getLocalized("Diagnosticar y depurar canciones", "Diagnose and optimize tracks"),
                                            fontSize = 11.sp,
                                            color = settingsTextMutedColor()
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { showDuplicateFinder = true },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(getLocalized("Duplicados", "Duplicates"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = { showIntegrityChecker = true },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Rounded.HealthAndSafety, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(getLocalized("Integridad", "Integrity"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { showShortSongsFinder = true },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(getLocalized("Audios cortos", "Short audio"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = { showMissingCoverFinder = true },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Rounded.ImageNotSupported, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(getLocalized("Sin carátula", "No cover"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // --- 7. ACERCA DE ---
                        SettingsCategoryHubCard(
                            icon = Icons.Rounded.Info,
                            iconColor = Color(0xFF2979FF),
                            title = getLocalized("Acerca de KevMusic", "About KevMusic"),
                            subtitle = "${getLocalized("Versión", "Version")} 1.0.0 • Desarrollado por Kevshupp",
                            chips = listOf(
                                "GitHub",
                                getLocalized("Novedades", "Changelog"),
                                getLocalized("Licencias", "Licenses")
                            ),
                            onClick = { activeCategory = "about" }
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                } else {
                    // SUBPAGE FOCUSED VIEW
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
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
                                    settingsPrefs = settingsPrefs,
                                    viewModel = viewModel
                                )
                            }
                            "audio" -> {
                                AudioSettingsSection(
                                    context = context,
                                    scope = scope,
                                    getLocalized = getLocalized
                                )
                            }
                            "performance" -> {
                                PerformanceSettingsSection(
                                    selectedRefreshRate = selectedRefreshRate,
                                    onRefreshRateSelected = { selectedRefreshRate = it },
                                    disableAnimations = disableAnimations,
                                    onDisableAnimationsChanged = { disableAnimations = it },
                                    getLocalized = getLocalized,
                                    settingsPrefs = settingsPrefs,
                                    context = context
                                )
                            }
                            "system" -> {
                                SystemSettingsSection(
                                    audioGranted = audioGranted,
                                    notificationGranted = notificationGranted,
                                    isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                                    audioPermissionLauncher = audioPermissionLauncher,
                                    notificationPermissionLauncher = notificationPermissionLauncher,
                                    getLocalized = getLocalized,
                                    settingsPrefs = settingsPrefs,
                                    context = context,
                                    viewModel = viewModel
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
                                    onFindDuplicates = { showDuplicateFinder = true },
                                    onCheckIntegrity = { showIntegrityChecker = true },
                                    onFindShortSongs = { showShortSongsFinder = true },
                                    onFindMissingCovers = { showMissingCoverFinder = true }
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

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    // Dialogs
    if (showDuplicateFinder) {
        DuplicateFinderDialog(
            viewModel = viewModel,
            onDismiss = { showDuplicateFinder = false }
        )
    }

    if (showIntegrityChecker) {
        SongIntegrityDialog(
            viewModel = viewModel,
            onDismiss = { showIntegrityChecker = false }
        )
    }

    if (showShortSongsFinder) {
        ShortSongsDialog(
            viewModel = viewModel,
            onDismiss = { showShortSongsFinder = false }
        )
    }

    if (showMissingCoverFinder) {
        MissingCoverFinderDialog(
            viewModel = viewModel,
            onDismiss = { showMissingCoverFinder = false }
        )
    }
}

@Composable
private fun SettingsCategoryHubCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    chips: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = settingsCardContainerColor()),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = settingsTextColor()
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = settingsTextMutedColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = settingsTextMutedColor().copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }

            if (chips.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    chips.forEach { chipText ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = chipText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
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
