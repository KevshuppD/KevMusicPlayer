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
    ),
    // Atardecer synthwave: rosa neón y cian sobre violeta noche
    PixelPalette(
        "pixel_synthwave", "Synthwave", "Synthwave",
        listOf(Color(0xFF0D0221), Color(0xFF2D1B69), Color(0xFFFF2A6D), Color(0xFF05D9E8)),
        darkColorScheme(
            primary = Color(0xFFFF2A6D), onPrimary = Color(0xFF0D0221),
            primaryContainer = Color(0xFF7A0F3D), onPrimaryContainer = Color(0xFFF7E8FF),
            secondary = Color(0xFF05D9E8), onSecondary = Color(0xFF0D0221),
            secondaryContainer = Color(0xFF1A0B3B), onSecondaryContainer = Color(0xFF05D9E8),
            tertiary = Color(0xFFF9C80E), onTertiary = Color(0xFF0D0221),
            background = Color(0xFF0D0221), onBackground = Color(0xFFF7E8FF),
            surface = Color(0xFF1A0B3B), onSurface = Color(0xFFF7E8FF),
            surfaceVariant = Color(0xFF2D1B69), onSurfaceVariant = Color(0xFFB39DDB),
            outline = Color(0xFF3D2A75),
            error = Color(0xFFFF6B6B), onError = Color(0xFF0D0221)
        )
    ),
    // Vaporwave: rosa, celeste y verde menta
    PixelPalette(
        "pixel_vaporwave", "Vaporwave", "Vaporwave",
        listOf(Color(0xFF1B1035), Color(0xFF3B2667), Color(0xFFFF71CE), Color(0xFF01CDFE)),
        darkColorScheme(
            primary = Color(0xFFFF71CE), onPrimary = Color(0xFF1B1035),
            primaryContainer = Color(0xFF6B2A5E), onPrimaryContainer = Color(0xFFFFF5FF),
            secondary = Color(0xFF01CDFE), onSecondary = Color(0xFF1B1035),
            secondaryContainer = Color(0xFF2A1B4D), onSecondaryContainer = Color(0xFF01CDFE),
            tertiary = Color(0xFF05FFA1), onTertiary = Color(0xFF1B1035),
            background = Color(0xFF1B1035), onBackground = Color(0xFFFFF5FF),
            surface = Color(0xFF2A1B4D), onSurface = Color(0xFFFFF5FF),
            surfaceVariant = Color(0xFF3B2667), onSurfaceVariant = Color(0xFFC9B6FF),
            outline = Color(0xFF4A3580),
            error = Color(0xFFFFFB96), onError = Color(0xFF1B1035)
        )
    ),
    // Océano: azules profundos, aguamarina y un toque de arena
    PixelPalette(
        "pixel_ocean", "Océano", "Océano",
        listOf(Color(0xFF04122B), Color(0xFF19376D), Color(0xFF4CC9F0), Color(0xFF80FFDB)),
        darkColorScheme(
            primary = Color(0xFF4CC9F0), onPrimary = Color(0xFF04122B),
            primaryContainer = Color(0xFF0B4F6C), onPrimaryContainer = Color(0xFFE0F7FF),
            secondary = Color(0xFF80FFDB), onSecondary = Color(0xFF04122B),
            secondaryContainer = Color(0xFF0B2447), onSecondaryContainer = Color(0xFF80FFDB),
            tertiary = Color(0xFFFFD166), onTertiary = Color(0xFF04122B),
            background = Color(0xFF04122B), onBackground = Color(0xFFE0F7FF),
            surface = Color(0xFF0B2447), onSurface = Color(0xFFE0F7FF),
            surfaceVariant = Color(0xFF19376D), onSurfaceVariant = Color(0xFF8FB8DE),
            outline = Color(0xFF1F4E79),
            error = Color(0xFFFF6F59), onError = Color(0xFF04122B)
        )
    ),
    // Lava: naranja, amarillo y rojo sobre roca oscura
    PixelPalette(
        "pixel_lava", "Lava", "Lava",
        listOf(Color(0xFF120404), Color(0xFF4A1208), Color(0xFFFF6B1A), Color(0xFFFFD23F)),
        darkColorScheme(
            primary = Color(0xFFFF6B1A), onPrimary = Color(0xFF120404),
            primaryContainer = Color(0xFF7A1E05), onPrimaryContainer = Color(0xFFFFE8D6),
            secondary = Color(0xFFFFD23F), onSecondary = Color(0xFF120404),
            secondaryContainer = Color(0xFF2A0A06), onSecondaryContainer = Color(0xFFFFD23F),
            tertiary = Color(0xFFFF2E2E), onTertiary = Color(0xFF120404),
            background = Color(0xFF120404), onBackground = Color(0xFFFFE8D6),
            surface = Color(0xFF2A0A06), onSurface = Color(0xFFFFE8D6),
            surfaceVariant = Color(0xFF4A1208), onSurfaceVariant = Color(0xFFE0A080),
            outline = Color(0xFF5C1A0A),
            error = Color(0xFFFFF3B0), onError = Color(0xFF120404)
        )
    ),
    // Sakura: rosa cerezo y verde hoja sobre noche
    PixelPalette(
        "pixel_sakura", "Sakura", "Sakura",
        listOf(Color(0xFF1A0F14), Color(0xFF432634), Color(0xFFFFA7C4), Color(0xFF9EE6CF)),
        darkColorScheme(
            primary = Color(0xFFFFA7C4), onPrimary = Color(0xFF1A0F14),
            primaryContainer = Color(0xFF7D3C55), onPrimaryContainer = Color(0xFFFFF0F5),
            secondary = Color(0xFF9EE6CF), onSecondary = Color(0xFF1A0F14),
            secondaryContainer = Color(0xFF2B1820), onSecondaryContainer = Color(0xFF9EE6CF),
            tertiary = Color(0xFFFFE5A8), onTertiary = Color(0xFF1A0F14),
            background = Color(0xFF1A0F14), onBackground = Color(0xFFFFF0F5),
            surface = Color(0xFF2B1820), onSurface = Color(0xFFFFF0F5),
            surfaceVariant = Color(0xFF432634), onSurfaceVariant = Color(0xFFD9A6B9),
            outline = Color(0xFF5A3346),
            error = Color(0xFFFF6B81), onError = Color(0xFF1A0F14)
        )
    ),
    // Hielo: celeste glaciar y blanco sobre azul polar
    PixelPalette(
        "pixel_ice", "Hielo", "Hielo",
        listOf(Color(0xFF06121C), Color(0xFF1C3A52), Color(0xFF9BE7FF), Color(0xFFFFFFFF)),
        darkColorScheme(
            primary = Color(0xFF9BE7FF), onPrimary = Color(0xFF06121C),
            primaryContainer = Color(0xFF1E5A7A), onPrimaryContainer = Color(0xFFEAF8FF),
            secondary = Color(0xFFFFFFFF), onSecondary = Color(0xFF06121C),
            secondaryContainer = Color(0xFF0E2233), onSecondaryContainer = Color(0xFFFFFFFF),
            tertiary = Color(0xFF7FB5FF), onTertiary = Color(0xFF06121C),
            background = Color(0xFF06121C), onBackground = Color(0xFFEAF8FF),
            surface = Color(0xFF0E2233), onSurface = Color(0xFFEAF8FF),
            surfaceVariant = Color(0xFF1C3A52), onSurfaceVariant = Color(0xFFA0C4DA),
            outline = Color(0xFF2A4F6B),
            error = Color(0xFFFF8FA3), onError = Color(0xFF06121C)
        )
    ),
    // Golosina: chicle rosa, celeste y limón
    PixelPalette(
        "pixel_candy", "Golosina", "Golosina",
        listOf(Color(0xFF1E1029), Color(0xFF45285C), Color(0xFFFF5CA8), Color(0xFF7CF5FF)),
        darkColorScheme(
            primary = Color(0xFFFF5CA8), onPrimary = Color(0xFF1E1029),
            primaryContainer = Color(0xFF7A2453), onPrimaryContainer = Color(0xFFFFF5FB),
            secondary = Color(0xFF7CF5FF), onSecondary = Color(0xFF1E1029),
            secondaryContainer = Color(0xFF2E1A3D), onSecondaryContainer = Color(0xFF7CF5FF),
            tertiary = Color(0xFFFFE45C), onTertiary = Color(0xFF1E1029),
            background = Color(0xFF1E1029), onBackground = Color(0xFFFFF5FB),
            surface = Color(0xFF2E1A3D), onSurface = Color(0xFFFFF5FB),
            surfaceVariant = Color(0xFF45285C), onSurfaceVariant = Color(0xFFD6A8E8),
            outline = Color(0xFF553071),
            error = Color(0xFFFF8A5C), onError = Color(0xFF1E1029)
        )
    )
)

fun pixelPalette(theme: String): PixelPalette = PixelPalettes.firstOrNull { it.key == theme } ?: PixelPalettes.first()

/** Paleta Pixel Retro activa, o null con cualquier otro tema (lo da KevMusicPlayerTheme). */
val LocalPixelPalette = androidx.compose.runtime.staticCompositionLocalOf<PixelPalette?> { null }
