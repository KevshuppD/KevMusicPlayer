package com.kevshupp.kevmusic.desktop.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Cyberpunk Rosa, el tema por defecto de la app Android. */
val NeonPink = Color(0xFFFF4081)
val NeonViolet = Color(0xFFB388FF)
val SpaceBlack = Color(0xFF08090F)
val Panel = Color(0xFF11131C)
val PanelHigh = Color(0xFF1A1D29)
val TextMuted = Color(0xFF9AA0B4)

private val scheme = darkColorScheme(
    primary = NeonPink,
    onPrimary = Color.White,
    secondary = NeonViolet,
    background = SpaceBlack,
    onBackground = Color(0xFFECEEF5),
    surface = Panel,
    onSurface = Color(0xFFECEEF5),
    surfaceVariant = PanelHigh,
    onSurfaceVariant = TextMuted,
    outline = Color(0xFF2B2F40)
)

@Composable
fun KevTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme) {
    // Sin Surface de fondo el texto heredaría el negro por defecto
    // La barra de desplazamiento por defecto es negra translúcida: invisible sobre fondo oscuro
    val scrollbar = ScrollbarStyle(
        minimalHeight = 48.dp,
        thickness = 10.dp,
        shape = RoundedCornerShape(5.dp),
        hoverDurationMillis = 250,
        unhoverColor = Color.White.copy(alpha = 0.22f),
        hoverColor = NeonPink.copy(alpha = 0.85f)
    )
    CompositionLocalProvider(LocalContentColor provides scheme.onBackground, LocalScrollbarStyle provides scrollbar, content = content)
}
