package com.kevshupp.kevmusicplayer.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Icono del launcher a juego con el tema. Cada tema tiene un `activity-alias` (`.LauncherIcon<Tema>`) con su
 * icono, generado por `tools/gen_launcher_icons.py`: "Nota K" en los temas normales y "Vinilo K" en pixel art en
 * los Pixel Retro. Solo hay un alias activo a la vez; con el ajuste apagado queda el de Cyberpunk Rosa.
 *
 * El cambio se aplica al salir de la app ([MainActivity.onStop]): desactivar el alias con el que se abrió la
 * tarea mientras está en pantalla hace que algunos launchers la cierren.
 */
object LauncherIcon {
    const val KEY_ENABLED = "launcher_icon_follows_theme"

    private const val DEFAULT = "Cyberpunk"
    private val ALIASES = listOf(
        "Cyberpunk", "Purpura", "Petrol", "Turquoise", "Obsidian", "Monochrome",
        "Pixel", "PixelGameboy", "PixelNes", "PixelC64", "PixelVirtualboy", "PixelAmber", "PixelPhosphor", "PixelDungeon",
        "PixelSynthwave", "PixelVaporwave", "PixelOcean", "PixelLava", "PixelSakura", "PixelIce", "PixelCandy"
    )

    private fun aliasFor(theme: String): String = when (theme) {
        "cyberpunk_purpura" -> "Purpura"
        else -> theme.split('_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    }.takeIf { it in ALIASES } ?: DEFAULT

    fun apply(context: Context) {
        val prefs = PreferenceConstants.getSettingsPrefs(context)
        val wanted = if (prefs.getBoolean(KEY_ENABLED, false)) {
            aliasFor(prefs.getString(PreferenceConstants.KEY_APP_THEME, "cyberpunk") ?: "cyberpunk")
        } else DEFAULT
        val pm = context.packageManager
        fun component(alias: String) = ComponentName(context.packageName, "com.kevshupp.kevmusicplayer.LauncherIcon$alias")
        // En el manifiesto solo el de Cyberpunk Rosa viene activado
        fun isEnabled(alias: String) = when (pm.getComponentEnabledSetting(component(alias))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> alias == DEFAULT
            else -> false
        }
        if (isEnabled(wanted) && ALIASES.none { it != wanted && isEnabled(it) }) return
        try {
            // Primero se activa el nuevo, para que nunca quede la app sin icono
            pm.setComponentEnabledSetting(component(wanted), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            for (alias in ALIASES) {
                if (alias == wanted) continue
                pm.setComponentEnabledSetting(component(alias), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            TelemetryLogger.logInfo("LauncherIcon", "Icono: $wanted")
        } catch (e: Exception) {
            TelemetryLogger.logError(context, "LauncherIcon", "No se pudo cambiar el icono", e)
        }
    }
}
