package com.kevshupp.kevmusic.desktop

import java.io.File

/** Diferencias entre Linux y Windows: carpetas de configuración y caché. */
object Platform {
    val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private val home = System.getProperty("user.home")

    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    /** %APPDATA%\kevmusic-desktop en Windows; $XDG_CONFIG_HOME o ~/.config/kevmusic-desktop en Linux. */
    val configDir: File = File(
        if (isWindows) env("APPDATA") ?: "$home\\AppData\\Roaming" else env("XDG_CONFIG_HOME") ?: "$home/.config",
        "kevmusic-desktop"
    )

    /** %LOCALAPPDATA%\kevmusic-desktop\cache en Windows; ~/.cache/kevmusic-desktop en Linux. */
    val cacheDir: File =
        if (isWindows) File(env("LOCALAPPDATA") ?: "$home\\AppData\\Local", "kevmusic-desktop\\cache")
        else File(env("XDG_CACHE_HOME") ?: "$home/.cache", "kevmusic-desktop")
}
