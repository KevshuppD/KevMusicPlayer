package com.kevshupp.kevmusicplayer

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.kevshupp.kevmusicplayer.data.TelemetryLogger

class KevMusicPlayerApplication : Application(), ImageLoaderFactory {
    companion object {
        lateinit var instance: KevMusicPlayerApplication
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        com.kevshupp.kevmusicplayer.data.telegram.DeezerTrackSearch.cacheDir = cacheDir
        instance = this
        com.kevshupp.kevmusicplayer.data.soulseek.SoulseekManager.init(this)
        
        // Setup global uncaught exception handler to log any app crashes to telemetry
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                TelemetryLogger.logError(
                    this,
                    "Global_Crash_${thread.name}",
                    "App crashed due to uncaught exception: ${throwable.localizedMessage ?: throwable.message}",
                    throwable,
                    synchronous = true
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        applyPerformanceSettings()
        Thread { com.kevshupp.kevmusicplayer.data.ArtistImageHelper.migrateIfNeeded(this) }.start()
    }

    // Performance settings that only take effect if something applies them when the app starts
    private fun applyPerformanceSettings() {
        val prefs = getSharedPreferences("settings_prefs", MODE_PRIVATE)
        com.kevshupp.kevmusicplayer.ui.screens.updateAlbumArtCacheSize(prefs.getInt("cover_cache_capacity", 150))

        if (prefs.getBoolean("auto_clean_temp", true)) {
            Thread {
                try {
                    // Only files older than an hour: a tag edit or an update download may be using a newer one
                    val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
                    cacheDir.listFiles()?.forEach { file ->
                        val name = file.name
                        val isTemp = name.endsWith(".apk") || name.startsWith("temp_mp3_") ||
                            name.startsWith("temp_jaudiotagger_") || name.startsWith("kmp_backup_")
                        if (file.isFile && isTemp && file.lastModified() < cutoff) file.delete()
                    }
                    com.kevshupp.kevmusicplayer.playback.managers.VideoClipGenerator.cleanOldClips(this)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.05)
                    .build()
            }
            .bitmapConfig(Bitmap.Config.HARDWARE)
            .crossfade(true)
            .crossfade(200)
            .respectCacheHeaders(false)
            .build()
    }
}
