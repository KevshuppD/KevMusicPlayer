package com.kevshupp.kevmusicplayer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.sp
import com.kevshupp.kevmusicplayer.R

// Set of Material typography styles to start with
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
    /* Other default text styles to override
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
)
// Tema Pixel Retro: Pixelify Sans (Google Fonts, licencia OFL), fuente variable con un archivo para todos los pesos
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun pixelify(weight: FontWeight) = Font(
    R.font.pixelify_sans,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

val PixelFontFamily = FontFamily(
    pixelify(FontWeight.Normal),
    pixelify(FontWeight.Medium),
    pixelify(FontWeight.SemiBold),
    pixelify(FontWeight.Bold)
)

/** Todos los estilos de Material con la fuente pixel; los Text sin estilo propio heredan bodyLarge. */
val PixelTypography: Typography = Typography().let { t ->
    fun TextStyle.px() = copy(fontFamily = PixelFontFamily)
    Typography(
        displayLarge = t.displayLarge.px(), displayMedium = t.displayMedium.px(), displaySmall = t.displaySmall.px(),
        headlineLarge = t.headlineLarge.px(), headlineMedium = t.headlineMedium.px(), headlineSmall = t.headlineSmall.px(),
        titleLarge = t.titleLarge.px(), titleMedium = t.titleMedium.px(), titleSmall = t.titleSmall.px(),
        bodyLarge = Typography.bodyLarge.px(), bodyMedium = t.bodyMedium.px(), bodySmall = t.bodySmall.px(),
        labelLarge = t.labelLarge.px(), labelMedium = t.labelMedium.px(), labelSmall = t.labelSmall.px()
    )
}
