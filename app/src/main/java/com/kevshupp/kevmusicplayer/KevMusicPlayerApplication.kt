package com.kevshupp.kevmusicplayer

import android.app.Application
import com.kevshupp.kevmusicplayer.data.TelemetryLogger

class KevMusicPlayerApplication : Application() {
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
}
