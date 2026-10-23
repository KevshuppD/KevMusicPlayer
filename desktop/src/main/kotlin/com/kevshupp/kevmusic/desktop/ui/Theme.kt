package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Los mismos temas que la app Android (ui/theme/Theme.kt), con las mismas claves de `app_theme`. */
val Themes: List<Pair<String, String>> = listOf(
    "cyberpunk" to "Cyberpunk Rosa",
    "cyberpunk_purpura" to "Cyberpunk Púrpura",
    "petrol" to "Azul Petróleo",
    "turquoise" to "Turquesa",
    "obsidian" to "Obsidiana AMOLED",
    "monochrome" to "Monocromo"
)

private val DarkBg = Color(0xFF08090F)
private val DarkSurface = Color(0xFF121422)
private val DarkSurfaceVariant = Color(0xFF1E2135)
private val DarkOnBg = Color(0xFFF0F1FA)
private val DarkOnSurface = Color(0xFFE1E2EC)
private val MutedDark = Color(0xFF9AA0B4)

fun colorSchemeFor(theme: String): ColorScheme = when (theme) {
    "cyberpunk_purpura" -> darkColorScheme(
        primary = Color(0xFFD000FF), onPrimary = Color(0xFF1A0033), secondary = Color(0xFFFF007F), tertiary = Color(0xFF00F0FF),
        background = DarkBg, onBackground = DarkOnBg, surface = DarkSurface, onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant, onSurfaceVariant = MutedDark, outline = Color(0xFF2B2F40)
    )
    "petrol" -> darkColorScheme(
        primary = Color(0xFF00E5FF), onPrimary = Color(0xFF00363C), secondary = Color(0xFF80F1FF),
        background = Color(0xFF040B0E), onBackground = Color(0xFFE0ECEF), surface = Color(0xFF0A1E24), onSurface = Color(0xFFE0ECEF),
        surfaceVariant = Color(0xFF122C34), onSurfaceVariant = Color(0xFF8FA9B0), outline = Color(0xFF1E3D46)
    )
    "turquoise" -> darkColorScheme(
        primary = Color(0xFF00F5D4), onPrimary = Color(0xFF003830), secondary = Color(0xFF8FFFEF),
        background = Color(0xFF020E0C), onBackground = Color(0xFFE0F7F4), surface = Color(0xFF071F1B), onSurface = Color(0xFFE0F7F4),
        surfaceVariant = Color(0xFF0D322C), onSurfaceVariant = Color(0xFF8DB3AD), outline = Color(0xFF16443C)
    )
    "obsidian" -> darkColorScheme(
        primary = Color(0xFFFFFFFF), onPrimary = Color(0xFF000000), secondary = Color(0xFFB0B3C6),
        background = Color(0xFF000000), onBackground = Color(0xFFFFFFFF), surface = Color(0xFF0E0E0E), onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF1C1C1C), onSurfaceVariant = Color(0xFF9E9E9E), outline = Color(0xFF2A2A2A)
    )
    "monochrome" -> lightColorScheme(
        primary = Color(0xFF000000), onPrimary = Color(0xFFFFFFFF), secondary = Color(0xFF555555),
        background = Color(0xFFFFFFFF), onBackground = Color(0xFF000000), surface = Color(0xFFF6F6F6), onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFEEEEEE), onSurfaceVariant = Color(0xFF666666), outline = Color(0xFFDDDDDD)
    )
    else -> darkColorScheme(
        primary = Color(0xFFFF4081), onPrimary = Color.White, secondary = Color(0xFFB388FF), tertiary = Color(0xFF18FFFF),
        background = DarkBg, onBackground = DarkOnBg, surface = DarkSurface, onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant, onSurfaceVariant = MutedDark, outline = Color(0xFF2B2F40)
    )
}

@Composable
fun KevTheme(theme: String, content: @Composable () -> Unit) {
    val scheme = colorSchemeFor(theme)
    MaterialTheme(colorScheme = scheme) {
        // La barra de desplazamiento por defecto es negra translúcida: invisible sobre fondo oscuro
        val scrollbar = ScrollbarStyle(
            minimalHeight = 48.dp,
            thickness = 10.dp,
            shape = RoundedCornerShape(5.dp),
            hoverDurationMillis = 250,
            unhoverColor = scheme.onBackground.copy(alpha = 0.22f),
            hoverColor = scheme.primary.copy(alpha = 0.85f)
        )
        // Sin Surface de fondo el texto heredaría el negro por defecto
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, LocalScrollbarStyle provides scrollbar, content = content)
    }
}
