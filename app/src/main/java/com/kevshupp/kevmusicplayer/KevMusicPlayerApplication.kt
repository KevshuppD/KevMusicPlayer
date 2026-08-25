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
        instance = this
        
        // Setup global uncaught exception handler to log any app crashes to telemetry
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                TelemetryLogger.logError(
                    this,
                    "Global_Crash_${thread.name}",
                    "App crashed due to uncaught exception: ${throwable.localizedMessage ?: throwable.message}",
                    throwable
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            defaultHandler?.uncaughtException(thread, throwable)
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
