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

@Composable
fun KevMusicPlayerTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE) }
    // Observe dynamic changes instantly
    var currentTheme by remember { mutableStateOf(prefs.getString("app_theme", "cyberpunk") ?: "cyberpunk") }
    var currentDisableAnimations by remember { mutableStateOf(prefs.getBoolean("disable_animations", false)) }
    
    // Store strong references to the change listeners so they are not garbage collected
    val listener = remember {
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "app_theme") {
                currentTheme = p.getString("app_theme", "cyberpunk") ?: "cyberpunk"
            } else if (key == "disable_animations") {
                currentDisableAnimations = p.getBoolean("disable_animations", false)
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
        else -> DarkColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    CompositionLocalProvider(
        LocalDisableAnimations provides currentDisableAnimations
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

