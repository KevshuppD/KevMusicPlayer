package com.kevshupp.kevmusicplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Paletas del tema Pixel Retro. Cada una es su propia clave de `app_theme` (`pixel`, `pixel_gameboy`…): así
 * viajan sin cambios en la copia y al PC, que tiene las mismas (desktop/.../ui/Theme.kt).
 */
data class PixelPalette(
    val key: String,
    val nameEs: String,
    val nameEn: String,
    /** Colores de la muestra del selector, de fondo a acento. */
    val swatch: List<Color>,
    val scheme: ColorScheme
)

fun isPixelTheme(theme: String) = theme == "pixel" || theme.startsWith("pixel_")

val PixelPalettes: List<PixelPalette> = listOf(
    // PICO-8 (https://pico-8.fandom.com/wiki/Palette)
    PixelPalette(
        "pixel", "PICO-8", "PICO-8",
        listOf(Color(0xFF1D2B53), Color(0xFF7E2553), Color(0xFFFF77A8), Color(0xFFFFA300)),
        darkColorScheme(
            primary = Color(0xFFFF77A8), onPrimary = Color(0xFF1D2B53),
            primaryContainer = Color(0xFF7E2553), onPrimaryContainer = Color(0xFFFFCCAA),
            secondary = Color(0xFF29ADFF), onSecondary = Color(0xFF1D2B53),
            secondaryContainer = Color(0xFF1D2B53), onSecondaryContainer = Color(0xFF29ADFF),
            tertiary = Color(0xFFFFA300), onTertiary = Color(0xFF1D2B53),
            background = Color(0xFF130E26), onBackground = Color(0xFFFFF1E8),
            surface = Color(0xFF1D2B53), onSurface = Color(0xFFFFF1E8),
            surfaceVariant = Color(0xFF2E2350), onSurfaceVariant = Color(0xFFC2C3C7),
            error = Color(0xFFFF004D), onError = Color(0xFFFFF1E8)
        )
    ),
    // Los 4 verdes de la Game Boy original (DMG)
    PixelPalette(
        "pixel_gameboy", "Game Boy", "Game Boy",
        listOf(Color(0xFF0F380F), Color(0xFF306230), Color(0xFF8BAC0F), Color(0xFF9BBC0F)),
        darkColorScheme(
            primary = Color(0xFF9BBC0F), onPrimary = Color(0xFF0F380F),
            primaryContainer = Color(0xFF306230), onPrimaryContainer = Color(0xFF9BBC0F),
            secondary = Color(0xFF8BAC0F), onSecondary = Color(0xFF0F380F),
            secondaryContainer = Color(0xFF306230), onSecondaryContainer = Color(0xFF9BBC0F),
            tertiary = Color(0xFF8BAC0F), onTertiary = Color(0xFF0F380F),
            background = Color(0xFF0A2A0A), onBackground = Color(0xFF9BBC0F),
            surface = Color(0xFF0F380F), onSurface = Color(0xFF9BBC0F),
            surfaceVariant = Color(0xFF306230), onSurfaceVariant = Color(0xFF8BAC0F),
            error = Color(0xFFE0F8D0), onError = Color(0xFF0F380F)
        )
    ),
    // Colores de la paleta de la NES: rojo de Mario, celeste y amarillo sobre negro azulado
    PixelPalette(
        "pixel_nes", "NES Arcade", "NES Arcade",
        listOf(Color(0xFF0B0B1E), Color(0xFF0058F8), Color(0xFFF83800), Color(0xFFF8B800)),
        darkColorScheme(
            primary = Color(0xFFF83800), onPrimary = Color(0xFFFCFCFC),
            primaryContainer = Color(0xFFA81000), onPrimaryContainer = Color(0xFFFCE0A8),
            secondary = Color(0xFF3CBCFC), onSecondary = Color(0xFF0B0B1E),
            secondaryContainer = Color(0xFF0058F8), onSecondaryContainer = Color(0xFFFCFCFC),
            tertiary = Color(0xFFF8B800), onTertiary = Color(0xFF0B0B1E),
            background = Color(0xFF0B0B1E), onBackground = Color(0xFFFCFCFC),
            surface = Color(0xFF16163A), onSurface = Color(0xFFFCFCFC),
            surfaceVariant = Color(0xFF24245A), onSurfaceVariant = Color(0xFFBCBCBC),
            error = Color(0xFFF83800), onError = Color(0xFFFCFCFC)
        )
    ),
    // Commodore 64: el azul de su pantalla de arranque, cian y amarillo verdoso
    PixelPalette(
        "pixel_c64", "Commodore 64", "Commodore 64",
        listOf(Color(0xFF2A1F66), Color(0xFF40318D), Color(0xFF7869C4), Color(0xFF67B6BD)),
        darkColorScheme(
            primary = Color(0xFF67B6BD), onPrimary = Color(0xFF2A1F66),
            primaryContainer = Color(0xFF40318D), onPrimaryContainer = Color(0xFFA0E0E6),
            secondary = Color(0xFFB8C76F), onSecondary = Color(0xFF2A1F66),
            secondaryContainer = Color(0xFF40318D), onSecondaryContainer = Color(0xFFB8C76F),
            tertiary = Color(0xFF9AD284), onTertiary = Color(0xFF2A1F66),
            background = Color(0xFF2A1F66), onBackground = Color(0xFFDCD8FF),
            surface = Color(0xFF40318D), onSurface = Color(0xFFDCD8FF),
            surfaceVariant = Color(0xFF5546A8), onSurfaceVariant = Color(0xFFB6AEEA),
            error = Color(0xFFD87B6E), onError = Color(0xFF2A1F66)
        )
    ),
    // Virtual Boy (1995): rojo sobre negro puro
    PixelPalette(
        "pixel_virtualboy", "Virtual Boy", "Virtual Boy",
        listOf(Color(0xFF000000), Color(0xFF500000), Color(0xFFA00000), Color(0xFFFF1A1A)),
        darkColorScheme(
            primary = Color(0xFFFF1A1A), onPrimary = Color(0xFF000000),
            primaryContainer = Color(0xFF500000), onPrimaryContainer = Color(0xFFFF8080),
            secondary = Color(0xFFC00000), onSecondary = Color(0xFF000000),
            secondaryContainer = Color(0xFF2A0000), onSecondaryContainer = Color(0xFFC00000),
            tertiary = Color(0xFFFF5050), onTertiary = Color(0xFF000000),
            background = Color(0xFF000000), onBackground = Color(0xFFFF3030),
            surface = Color(0xFF0E0000), onSurface = Color(0xFFFF3030),
            surfaceVariant = Color(0xFF2A0000), onSurfaceVariant = Color(0xFFC03030),
            outline = Color(0xFF500000),
            error = Color(0xFFFF8080), onError = Color(0xFF000000)
        )
    ),
    // Monitor de fósforo ámbar: todo en tonos de ámbar sobre negro
    PixelPalette(
        "pixel_amber", "Monitor ámbar", "Monitor ámbar",
        listOf(Color(0xFF000000), Color(0xFF3D2600), Color(0xFFB37400), Color(0xFFFFB000)),
        darkColorScheme(
            primary = Color(0xFFFFB000), onPrimary = Color(0xFF000000),
            primaryContainer = Color(0xFF3D2600), onPrimaryContainer = Color(0xFFFFD480),
            secondary = Color(0xFFFFCC66), onSecondary = Color(0xFF050300),
            secondaryContainer = Color(0xFF2B1C00), onSecondaryContainer = Color(0xFFFFCC66),
            tertiary = Color(0xFFFF8C00), onTertiary = Color(0xFF050300),
            background = Color(0xFF050300), onBackground = Color(0xFFFFB000),
            surface = Color(0xFF120B00), onSurface = Color(0xFFFFB000),
            surfaceVariant = Color(0xFF2B1C00), onSurfaceVariant = Color(0xFFC98A00),
            outline = Color(0xFF4A3000),
            error = Color(0xFFFF5F1F), onError = Color(0xFF050300)
        )
    ),
    // Terminal CRT verde de los 80
    PixelPalette(
        "pixel_phosphor", "Fósforo verde", "Fósforo verde",
        listOf(Color(0xFF000000), Color(0xFF003B00), Color(0xFF00A000), Color(0xFF33FF33)),
        darkColorScheme(
            primary = Color(0xFF33FF33), onPrimary = Color(0xFF000000),
            primaryContainer = Color(0xFF003B00), onPrimaryContainer = Color(0xFFA0FFA0),
            secondary = Color(0xFF7DFF7D), onSecondary = Color(0xFF000500),
            secondaryContainer = Color(0xFF002400), onSecondaryContainer = Color(0xFF7DFF7D),
            tertiary = Color(0xFF00CC66), onTertiary = Color(0xFF000500),
            background = Color(0xFF000500), onBackground = Color(0xFF33FF33),
            surface = Color(0xFF001000), onSurface = Color(0xFF33FF33),
            surfaceVariant = Color(0xFF002400), onSurfaceVariant = Color(0xFF22B522),
            outline = Color(0xFF004A00),
            error = Color(0xFFFFFF33), onError = Color(0xFF000500)
        )
    ),
    // Mazmorra: Sweetie 16 (GrafxKid) sobre negro violáceo
    PixelPalette(
        "pixel_dungeon", "Mazmorra", "Mazmorra",
        listOf(Color(0xFF0B0A12), Color(0xFF29366F), Color(0xFFB13E53), Color(0xFFFFCD75)),
        darkColorScheme(
            primary = Color(0xFFB13E53), onPrimary = Color(0xFFF4F4F4),
            primaryContainer = Color(0xFF5D275D), onPrimaryContainer = Color(0xFFFFCD75),
            secondary = Color(0xFF41A6F6), onSecondary = Color(0xFF0B0A12),
            secondaryContainer = Color(0xFF1E1B2E), onSecondaryContainer = Color(0xFF41A6F6),
            tertiary = Color(0xFFFFCD75), onTertiary = Color(0xFF0B0A12),
            background = Color(0xFF0B0A12), onBackground = Color(0xFFF4F4F4),
            surface = Color(0xFF15131F), onSurface = Color(0xFFF4F4F4),
            surfaceVariant = Color(0xFF1E1B2E), onSurfaceVariant = Color(0xFF94B0C2),
            outline = Color(0xFF333C57),
            error = Color(0xFFEF7D57), onError = Color(0xFF0B0A12)
        )
    )
)

fun pixelPalette(theme: String): PixelPalette = PixelPalettes.firstOrNull { it.key == theme } ?: PixelPalettes.first()

/** Paleta Pixel Retro activa, o null con cualquier otro tema (lo da KevMusicPlayerTheme). */
val LocalPixelPalette = androidx.compose.runtime.staticCompositionLocalOf<PixelPalette?> { null }
