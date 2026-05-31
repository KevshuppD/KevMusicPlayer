package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    enabledTabs: Set<String>,
    onEnabledTabsChanged: (Set<String>) -> Unit,
    sortBy: String,
    onSortByChanged: (String) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var isScanning by remember { mutableStateOf(false) }

    val context = LocalContext.current

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
            kotlinx.coroutines.delay(1000)
        }
    }

    // Dynamic background brush
    val backgroundBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF121422),
                Color(0xFF08090F)
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Black,
                        fontSize = 24.sp,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF121422),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
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
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Screen Refresh Rate (120Hz Indicator Card)
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF00E5FF),
                                        Color(0xFF7C4DFF)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Display Performance",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "120Hz refresh rate mode is enforced for ultra-smooth fluid navigation",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                    
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF00E5FF).copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "120 HZ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF)
                        )
                    }
                }
            }

            // Permissions & Access Dashboard Card (Fully Interactive)
            Column {
                Text(
                    text = "SYSTEM PERMISSIONS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                )
                
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // 1. Audio Storage Permission Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (audioGranted) Color(0xFF00E676).copy(alpha = 0.15f) 
                                        else Color(0xFFFF1744).copy(alpha = 0.15f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (audioGranted) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                                    contentDescription = null,
                                    tint = if (audioGranted) Color(0xFF00E676) else Color(0xFFFF1744),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(14.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Music Files Access",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (audioGranted) "Granted. Storage scanned successfully." 
                                           else "Required to discover and play local MP3/FLAC music files.",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                            }
                            
                            if (!audioGranted) {
                                Button(
                                    onClick = {
                                        val perm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                            Manifest.permission.READ_MEDIA_AUDIO
                                        } else {
                                            Manifest.permission.READ_EXTERNAL_STORAGE
                                        }
                                        audioPermissionLauncher.launch(perm)
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Text(
                                    text = "Active",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00E676)
                                )
                            }
                        }
                        
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        
                        // 2. Notification Permission Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (notificationGranted) Color(0xFF00E676).copy(alpha = 0.15f) 
                                        else Color(0xFFFF9100).copy(alpha = 0.15f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (notificationGranted) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                                    contentDescription = null,
                                    tint = if (notificationGranted) Color(0xFF00E676) else Color(0xFFFF9100),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(14.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Playback Notifications",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (notificationGranted) "Granted. Background player controller active." 
                                           else "Required to show current track in system tray.",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                            }
                            
                            if (!notificationGranted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                Button(
                                    onClick = {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Text(
                                    text = "Active",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00E676)
                                )
                            }
                        }

                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

                        // 3. Background Services Status
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF00E5FF).copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(14.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Background Foreground Service",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Enforces persistent playback thread and CPU Wake Lock.",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                            }
                            
                            Text(
                                text = "Running",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E5FF)
                              )
                        }
                    }
                }
            }

            // Visible Navigation Categories Section
            Column {
                Text(
                    text = "LIBRARY CATEGORIES",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                )
                
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        val categories = listOf(
                            CategoryItem("Songs", "View all raw audio tracks", Icons.Rounded.MusicNote),
                            CategoryItem("Albums", "Group tracks by albums", Icons.Rounded.Album),
                            CategoryItem("Artists", "View catalog by artist name", Icons.Rounded.Person),
                            CategoryItem("Genres", "Filter tracks by audio genres", Icons.Rounded.Category),
                            CategoryItem("Folders", "Browse tracks by system folders", Icons.Rounded.Folder)
                        )
                        
                        categories.forEachIndexed { index, cat ->
                            val isChecked = cat.name in enabledTabs
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val newTabs = if (isChecked) {
                                            if (enabledTabs.size > 1) enabledTabs - cat.name else enabledTabs
                                        } else {
                                            enabledTabs + cat.name
                                        }
                                        onEnabledTabsChanged(newTabs)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = cat.icon,
                                        contentDescription = null,
                                        tint = if (isChecked) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            text = cat.name,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = cat.desc,
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                                Switch(
                                    checked = isChecked,
                                    onCheckedChange = {
                                        val newTabs = if (it) {
                                            enabledTabs + cat.name
                                        } else {
                                            if (enabledTabs.size > 1) enabledTabs - cat.name else enabledTabs
                                        }
                                        onEnabledTabsChanged(newTabs)
                                    }
                                )
                            }
                            if (index < categories.size - 1) {
                                HorizontalDivider(
                                    color = Color.White.copy(alpha = 0.08f),
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Track Sorting Settings Section
            Column {
                Text(
                    text = "DEFAULT SORT PREFERENCE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                )
                
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        val sortPreferences = listOf(
                            SortPrefItem("Alphabetical", "Alphabetical", "Sort alphabetically by song title", Icons.Rounded.SortByAlpha),
                            SortPrefItem("Artist", "Artist Name", "Sort alphabetically by artist name", Icons.Rounded.Person),
                            SortPrefItem("Duration", "Track Duration", "Sort by track length (longest first)", Icons.Rounded.HourglassEmpty)
                        )
                        
                        sortPreferences.forEachIndexed { index, pref ->
                            val isSelected = sortBy == pref.value
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSortByChanged(pref.value) }
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onSortByChanged(pref.value) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = pref.icon,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = pref.name,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = pref.desc,
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                            if (index < sortPreferences.size - 1) {
                                HorizontalDivider(
                                    color = Color.White.copy(alpha = 0.08f),
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Maintenance / Re-scan button Section (Glowing and premium)
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Music Library Maintenance",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "If new downloads or transferred MP3 files are missing from the library lists, tap below to execute a deep media store scanning cycle.",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = {
                            scope.launch {
                                isScanning = true
                                onRescan()
                                delay(1200) // Visual feedback delay
                                isScanning = false
                            }
                        },
                        enabled = !isScanning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White,
                            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            disabledContentColor = Color.White.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Scanning Files...",
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
                                text = "Re-scan Library",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

private data class CategoryItem(
    val name: String,
    val desc: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private data class SortPrefItem(
    val value: String,
    val name: String,
    val desc: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
