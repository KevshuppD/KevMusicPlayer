package com.kevshupp.kevmusicplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Enforce 120Hz display refresh rate for ultra-smooth fluid navigation (supported devices like Moto G35)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val window = this.window
                val params = window.attributes
                val display = this.display
                if (display != null) {
                    val supportedModes = display.supportedModes
                    val highestRefreshRateMode = supportedModes.maxByOrNull { it.refreshRate }
                    if (highestRefreshRateMode != null) {
                        params.preferredDisplayModeId = highestRefreshRateMode.modeId
                        window.attributes = params
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContent {
            KevMusicPlayerTheme {
                AppNavigation()
            }
        }
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
    val backStack = rememberNavBackStack(Screen.PermissionRequest as NavKey)
    val viewModel: MediaBrowserViewModel = viewModel()
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()

    // Intercept system back gestures to pop screens from backstack instead of closing the app!
    BackHandler(enabled = backStack.size > 1) {
        backStack.removeAt(backStack.size - 1)
    }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (checkInitialPermissions(context)) {
            viewModel.connect()
        }
    }

    NavDisplay(
        backStack = backStack,
        sceneStrategy = listDetailStrategy,
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
                        viewModel.connect()
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
                    viewModel = viewModel
                )
            }
            entry<Screen.Settings> {
                SettingsScreen(
                    enabledTabs = viewModel.enabledTabs.value,
                    onEnabledTabsChanged = { viewModel.enabledTabs.value = it },
                    sortBy = viewModel.sortBy.value,
                    onSortByChanged = { viewModel.sortBy.value = it },
                    onRescan = { viewModel.scanFiles(isManual = true) },
                    onBack = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.size - 1)
                        }
                    }
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
        permissionsGranted = checkInitialPermissions(context)
        if (permissionsGranted) {
            onPermissionsGranted()
        }
    }

    LaunchedEffect(permissionsGranted) {
        val standardGranted = checkStandardPermissions(context)
        if (!standardGranted) {
            val permissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            launcher.launch(permissions.toTypedArray())
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(intent)
                }
            } else {
                onPermissionsGranted()
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
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(text = "Se requiere acceso a todos los archivos para poder borrar canciones.", color = MaterialTheme.colorScheme.onBackground)
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
                Text(text = "Cargando Biblioteca...")
            } else {
                Text(text = "Por favor, concede permisos de almacenamiento para acceder a tu música.")
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
