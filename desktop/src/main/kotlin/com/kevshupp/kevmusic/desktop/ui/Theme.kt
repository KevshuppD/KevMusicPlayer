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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/** Los mismos temas que la app Android (ui/theme/Theme.kt), con las mismas claves de `app_theme`. */
val Themes: List<Pair<String, String>> = listOf(
    "cyberpunk" to "Cyberpunk Rosa",
    "cyberpunk_purpura" to "Cyberpunk Púrpura",
    "petrol" to "Azul Petróleo",
    "turquoise" to "Turquesa",
    "obsidian" to "Obsidiana AMOLED",
    "monochrome" to "Monocromo",
    "pixel" to "Pixel Retro · PICO-8",
    "pixel_gameboy" to "Pixel Retro · Game Boy",
    "pixel_nes" to "Pixel Retro · NES Arcade",
    "pixel_c64" to "Pixel Retro · Commodore 64",
    "pixel_virtualboy" to "Pixel Retro · Virtual Boy",
    "pixel_amber" to "Pixel Retro · Monitor ámbar",
    "pixel_phosphor" to "Pixel Retro · Fósforo verde",
    "pixel_dungeon" to "Pixel Retro · Mazmorra"
)

fun isPixelTheme(theme: String) = theme == "pixel" || theme.startsWith("pixel_")

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
    // Paletas de Pixel Retro, como en la app (ui/theme/PixelPalettes.kt)
    "pixel" -> darkColorScheme(
        primary = Color(0xFFFF77A8), onPrimary = Color(0xFF1D2B53), primaryContainer = Color(0xFF7E2553), onPrimaryContainer = Color(0xFFFFCCAA),
        secondary = Color(0xFF29ADFF), tertiary = Color(0xFFFFA300),
        background = Color(0xFF130E26), onBackground = Color(0xFFFFF1E8), surface = Color(0xFF1D2B53), onSurface = Color(0xFFFFF1E8),
        surfaceVariant = Color(0xFF2E2350), onSurfaceVariant = Color(0xFFC2C3C7), outline = Color(0xFF5F574F), error = Color(0xFFFF004D)
    )
    "pixel_gameboy" -> darkColorScheme(
        primary = Color(0xFF9BBC0F), onPrimary = Color(0xFF0F380F), primaryContainer = Color(0xFF306230), onPrimaryContainer = Color(0xFF9BBC0F),
        secondary = Color(0xFF8BAC0F), tertiary = Color(0xFF8BAC0F),
        background = Color(0xFF0A2A0A), onBackground = Color(0xFF9BBC0F), surface = Color(0xFF0F380F), onSurface = Color(0xFF9BBC0F),
        surfaceVariant = Color(0xFF306230), onSurfaceVariant = Color(0xFF8BAC0F), outline = Color(0xFF306230), error = Color(0xFFE0F8D0)
    )
    "pixel_nes" -> darkColorScheme(
        primary = Color(0xFFF83800), onPrimary = Color(0xFFFCFCFC), primaryContainer = Color(0xFFA81000), onPrimaryContainer = Color(0xFFFCE0A8),
        secondary = Color(0xFF3CBCFC), tertiary = Color(0xFFF8B800),
        background = Color(0xFF0B0B1E), onBackground = Color(0xFFFCFCFC), surface = Color(0xFF16163A), onSurface = Color(0xFFFCFCFC),
        surfaceVariant = Color(0xFF24245A), onSurfaceVariant = Color(0xFFBCBCBC), outline = Color(0xFF34346A), error = Color(0xFFF83800)
    )
    "pixel_c64" -> darkColorScheme(
        primary = Color(0xFF67B6BD), onPrimary = Color(0xFF2A1F66), primaryContainer = Color(0xFF40318D), onPrimaryContainer = Color(0xFFA0E0E6),
        secondary = Color(0xFFB8C76F), tertiary = Color(0xFF9AD284),
        background = Color(0xFF2A1F66), onBackground = Color(0xFFDCD8FF), surface = Color(0xFF40318D), onSurface = Color(0xFFDCD8FF),
        surfaceVariant = Color(0xFF5546A8), onSurfaceVariant = Color(0xFFB6AEEA), outline = Color(0xFF6C5EB5), error = Color(0xFFD87B6E)
    )
    "pixel_virtualboy" -> darkColorScheme(
        primary = Color(0xFFFF1A1A), onPrimary = Color(0xFF000000), primaryContainer = Color(0xFF500000), onPrimaryContainer = Color(0xFFFF8080),
        secondary = Color(0xFFC00000), tertiary = Color(0xFFFF5050),
        background = Color(0xFF000000), onBackground = Color(0xFFFF3030), surface = Color(0xFF0E0000), onSurface = Color(0xFFFF3030),
        surfaceVariant = Color(0xFF2A0000), onSurfaceVariant = Color(0xFFC03030), outline = Color(0xFF500000), error = Color(0xFFFF8080)
    )
    "pixel_amber" -> darkColorScheme(
        primary = Color(0xFFFFB000), onPrimary = Color(0xFF000000), primaryContainer = Color(0xFF3D2600), onPrimaryContainer = Color(0xFFFFD480),
        secondary = Color(0xFFFFCC66), tertiary = Color(0xFFFF8C00),
        background = Color(0xFF050300), onBackground = Color(0xFFFFB000), surface = Color(0xFF120B00), onSurface = Color(0xFFFFB000),
        surfaceVariant = Color(0xFF2B1C00), onSurfaceVariant = Color(0xFFC98A00), outline = Color(0xFF4A3000), error = Color(0xFFFF5F1F)
    )
    "pixel_phosphor" -> darkColorScheme(
        primary = Color(0xFF33FF33), onPrimary = Color(0xFF000000), primaryContainer = Color(0xFF003B00), onPrimaryContainer = Color(0xFFA0FFA0),
        secondary = Color(0xFF7DFF7D), tertiary = Color(0xFF00CC66),
        background = Color(0xFF000500), onBackground = Color(0xFF33FF33), surface = Color(0xFF001000), onSurface = Color(0xFF33FF33),
        surfaceVariant = Color(0xFF002400), onSurfaceVariant = Color(0xFF22B522), outline = Color(0xFF004A00), error = Color(0xFFFFFF33)
    )
    "pixel_dungeon" -> darkColorScheme(
        primary = Color(0xFFB13E53), onPrimary = Color(0xFFF4F4F4), primaryContainer = Color(0xFF5D275D), onPrimaryContainer = Color(0xFFFFCD75),
        secondary = Color(0xFF41A6F6), tertiary = Color(0xFFFFCD75),
        background = Color(0xFF0B0A12), onBackground = Color(0xFFF4F4F4), surface = Color(0xFF15131F), onSurface = Color(0xFFF4F4F4),
        surfaceVariant = Color(0xFF1E1B2E), onSurfaceVariant = Color(0xFF94B0C2), outline = Color(0xFF333C57), error = Color(0xFFEF7D57)
    )
    else -> darkColorScheme(
        primary = Color(0xFFFF4081), onPrimary = Color.White, secondary = Color(0xFFB388FF), tertiary = Color(0xFF18FFFF),
        background = DarkBg, onBackground = DarkOnBg, surface = DarkSurface, onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant, onSurfaceVariant = MutedDark, outline = Color(0xFF2B2F40)
    )
}

/** True con una paleta de Pixel Retro: las tarjetas de colores fijos toman los de la paleta. */
val LocalPixelTheme = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun KevTheme(theme: String, content: @Composable () -> Unit) {
    val scheme = colorSchemeFor(theme)
    MaterialTheme(colorScheme = scheme, typography = if (isPixelTheme(theme)) PixelTypography else Typography()) {
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
        // Los Text sin estilo toman LocalTextStyle: con el tema pixel también llevan la fuente
        CompositionLocalProvider(
            LocalContentColor provides scheme.onBackground,
            LocalScrollbarStyle provides scrollbar,
            LocalTextStyle provides MaterialTheme.typography.bodyLarge,
            LocalPixelTheme provides isPixelTheme(theme),
            content = content
        )
    }
}

// Tema Pixel Retro: Pixelify Sans (Google Fonts, licencia OFL) en resources/font
private val PixelFontFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { w ->
        Font("font/PixelifySans.ttf", weight = w, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
            androidx.compose.ui.text.font.FontVariation.weight(w.weight)
        ))
    }
)

private val PixelTypography: Typography = Typography().let { t ->
    fun TextStyle.px() = copy(fontFamily = PixelFontFamily)
    Typography(
        displayLarge = t.displayLarge.px(), displayMedium = t.displayMedium.px(), displaySmall = t.displaySmall.px(),
        headlineLarge = t.headlineLarge.px(), headlineMedium = t.headlineMedium.px(), headlineSmall = t.headlineSmall.px(),
        titleLarge = t.titleLarge.px(), titleMedium = t.titleMedium.px(), titleSmall = t.titleSmall.px(),
        bodyLarge = t.bodyLarge.px(), bodyMedium = t.bodyMedium.px(), bodySmall = t.bodySmall.px(),
        labelLarge = t.labelLarge.px(), labelMedium = t.labelMedium.px(), labelSmall = t.labelSmall.px()
    )
}
