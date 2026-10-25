package com.kevshupp.kevmusicplayer.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.glance.appwidget.updateAll
import com.kevshupp.kevmusicplayer.ui.theme.appColorScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Colores de los widgets, sacados del tema de la app; antes eran fijos y no cambiaban con el tema. */
data class WidgetColors(
    val background: Color,
    val card: Color,
    val primary: Color,
    val onPrimary: Color,
    val text: Color,
    val muted: Color
)

object WidgetTheme {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun colors(context: Context): WidgetColors {
        val theme = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .getString("app_theme", "cyberpunk") ?: "cyberpunk"
        val s = appColorScheme(theme)
        return WidgetColors(
            background = s.surface,
            card = s.surfaceVariant,
            primary = s.primary,
            onPrimary = s.onPrimary,
            text = s.onSurface,
            muted = s.onSurface.copy(alpha = 0.65f)
        )
    }

    /** Vuelve a dibujar todos los widgets (al cambiar el tema). */
    fun refreshAll(context: Context) {
        scope.launch {
            runCatching { MusicWidget().updateAll(context) }
            runCatching { ClipsWidget().updateAll(context) }
        }
    }

    /** Solo el de videos (al crear o borrar uno). */
    fun refreshClips(context: Context) {
        scope.launch { runCatching { ClipsWidget().updateAll(context) } }
    }
}
