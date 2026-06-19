package com.kevshupp.kevmusicplayer.data

import android.content.Context
import com.kevshupp.kevmusicplayer.KevMusicPlayerApplication
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TelemetryLogger {
    private const val LOG_FILE_NAME = "telemetry_errors.log"

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("telemetry_enabled", false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("telemetry_enabled", enabled).apply()
    }

    fun logError(category: String, message: String, throwable: Throwable? = null) {
        try {
            logError(KevMusicPlayerApplication.instance, category, message, throwable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getExceptionHandler(category: String): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            logError(category, "Uncaught coroutine exception: ${throwable.localizedMessage ?: throwable.message}", throwable)
        }
    }

    fun logError(context: Context, category: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled(context)) return
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            val timestamp = sdf.format(Date())
            
            val logLine = StringBuilder()
            logLine.append("[$timestamp] [$category] $message\n")
            if (throwable != null) {
                logLine.append("  Exception: ${throwable.javaClass.name}: ${throwable.message}\n")
                val stackTrace = throwable.stackTrace.take(5).joinToString("\n") { "    at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                logLine.append("$stackTrace\n")
            }
            logLine.append("\n")
            
            file.appendText(logLine.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLogs(context: Context): String {
        return try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun clearLogs(context: Context) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
