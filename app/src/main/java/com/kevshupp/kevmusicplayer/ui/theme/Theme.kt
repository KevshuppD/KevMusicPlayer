package com.kevshupp.kevmusicplayer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

import androidx.compose.ui.graphics.Color
import android.content.Context

private val CyberpunkPurpuraColorScheme = darkColorScheme(
    primary = Color(0xFFD000FF),
    onPrimary = Color(0xFF1A0033),
    primaryContainer = Color(0xFF4D007A),
    onPrimaryContainer = Color(0xFFF3E5F5),
    secondary = Color(0xFFFF007F),
    onSecondary = Color(0xFF2D0014),
    secondaryContainer = Color(0xFF5A002C),
    onSecondaryContainer = Color(0xFFFFD8E4),
    tertiary = Color(0xFF00F0FF),
    onTertiary = Color(0xFF00363C),
    tertiaryContainer = Color(0xFF004F58),
    onTertiaryContainer = Color(0xFFCBF8FF),
    background = DarkBg,
    onBackground = DarkOnBg,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurface
)

private val PetrolColorScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00363C),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFFCBF8FF),
    secondary = Color(0xFF80F1FF),
    onSecondary = Color(0xFF00363A),
    background = Color(0xFF040B0E),
    onBackground = Color(0xFFE0ECEF),
    surface = Color(0xFF0A1E24),
    onSurface = Color(0xFFE0ECEF),
    surfaceVariant = Color(0xFF122C34),
    onSurfaceVariant = Color(0xFFE0ECEF)
)

private val ObsidianColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1C1C1C),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFB0B3C6),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0E0E0E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFFFFFFF)
)

private val MonochromeColorScheme = lightColorScheme(
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

private val TurquoiseColorScheme = darkColorScheme(
    primary = Color(0xFF00F5D4),
    onPrimary = Color(0xFF003830),
    primaryContainer = Color(0xFF005A4E),
    onPrimaryContainer = Color(0xFFB3FFF5),
    secondary = Color(0xFF8FFFEF),
    onSecondary = Color(0xFF003830),
    background = Color(0xFF020E0C),
    onBackground = Color(0xFFE0F7F4),
    surface = Color(0xFF071F1B),
    onSurface = Color(0xFFE0F7F4),
    surfaceVariant = Color(0xFF0D322C),
    onSurfaceVariant = Color(0xFFE0F7F4)
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = DarkBg,
    onBackground = DarkOnBg,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurface
)

val LocalDisableAnimations = androidx.compose.runtime.staticCompositionLocalOf { false }
val LocalSongImageRounded = androidx.compose.runtime.staticCompositionLocalOf { true }

@Composable
fun KevMusicPlayerTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE) }
    // Observe dynamic changes instantly
    var currentTheme by remember { mutableStateOf(prefs.getString("app_theme", "cyberpunk") ?: "cyberpunk") }
    var currentDisableAnimations by remember { mutableStateOf(prefs.getBoolean("disable_animations", false)) }
    var currentSongImageRounded by remember { mutableStateOf(prefs.getBoolean("song_image_rounded", true)) }
    
    // Store strong references to the change listeners so they are not garbage collected
    val listener = remember {
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "app_theme") {
                currentTheme = p.getString("app_theme", "cyberpunk") ?: "cyberpunk"
            } else if (key == "disable_animations") {
                currentDisableAnimations = p.getBoolean("disable_animations", false)
            } else if (key == "song_image_rounded") {
                currentSongImageRounded = p.getBoolean("song_image_rounded", true)
            }
        }
    }
    
    // Lifecycle-controlled registration to avoid memory leaks and maintain strong reference
    DisposableEffect(prefs) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val colorScheme = when (currentTheme) {
        "petrol" -> PetrolColorScheme
        "obsidian" -> ObsidianColorScheme
        "turquoise" -> TurquoiseColorScheme
        "monochrome" -> MonochromeColorScheme
        "cyberpunk_purpura" -> CyberpunkPurpuraColorScheme
        else -> DarkColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val darkTheme = currentTheme != "monochrome"
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    CompositionLocalProvider(
        LocalDisableAnimations provides currentDisableAnimations,
        LocalSongImageRounded provides currentSongImageRounded
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

