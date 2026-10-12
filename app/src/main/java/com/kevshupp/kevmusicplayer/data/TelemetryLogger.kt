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
    private const val MAX_LOG_SIZE_BYTES = 250 * 1024L // 250 KB max

    // All file access happens on this single background thread, in call order, so logging from the
    // main thread (player callbacks, UI) never blocks on disk I/O
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TelemetryLogger").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // Only ever called from the writer thread (or synchronously from the crash handler)
    private fun formatTimestamp(timeMs: Long): String = synchronized(timestampFormat) {
        timestampFormat.format(Date(timeMs))
    }

    private fun enqueue(context: Context, timeMs: Long, body: String) {
        val appContext = context.applicationContext ?: context
        try {
            writer.execute { appendToFile(appContext, "[${formatTimestamp(timeMs)}] $body") }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("telemetry_enabled", true) // Default to true for diagnostics
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

    @Synchronized
    private fun appendToFile(context: Context, text: String) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                // Trim older logs, keep newest half
                val existing = file.readText()
                val lines = existing.lines()
                val keptLines = lines.takeLast(lines.size / 2).joinToString("\n")
                file.writeText(keptLines + "\n")
            }
            file.appendText(text)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * @param synchronous write before returning. Only for the crash handler, where the process is about to die
     * and a queued write would be lost.
     */
    fun logError(context: Context, category: String, message: String, throwable: Throwable? = null, synchronous: Boolean = false) {
        android.util.Log.e("TelemetryLogger", "[$category] $message", throwable)
        if (!isEnabled(context)) return
        val now = System.currentTimeMillis()

        val logLine = StringBuilder()
        logLine.append("[$category] [ERROR] $message\n")
        if (throwable != null) {
            var current: Throwable? = throwable
            while (current != null) {
                if (current != throwable) {
                    logLine.append("  Caused by: ${current.javaClass.name}: ${current.message}\n")
                } else {
                    logLine.append("  Exception: ${current.javaClass.name}: ${current.message}\n")
                }
                val stackTrace = current.stackTrace.take(15).joinToString("\n") { "    at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                logLine.append("$stackTrace\n")
                current = current.cause
            }
        }
        logLine.append("\n")
        if (synchronous) {
            appendToFile(context, "[${formatTimestamp(now)}] $logLine")
        } else {
            enqueue(context, now, logLine.toString())
        }
    }

    fun logInfo(category: String, message: String) {
        try {
            logInfo(KevMusicPlayerApplication.instance, category, message)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun logInfo(context: Context, category: String, message: String) {
        android.util.Log.i("TelemetryLogger", "[$category] $message")
        if (!isEnabled(context)) return
        enqueue(context, System.currentTimeMillis(), "[$category] [INFO] $message\n")
    }

    fun logWarn(category: String, message: String) {
        try {
            logWarn(KevMusicPlayerApplication.instance, category, message)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun logWarn(context: Context, category: String, message: String) {
        android.util.Log.w("TelemetryLogger", "[$category] $message")
        if (!isEnabled(context)) return
        enqueue(context, System.currentTimeMillis(), "[$category] [WARN] $message\n")
    }

    // Waits (briefly) for queued lines so readers see everything logged so far
    private fun flushPendingWrites() {
        try {
            writer.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Reading slightly stale logs is fine
        }
    }

    fun getLogs(context: Context): String {
        flushPendingWrites()
        return try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun clearLogs(context: Context) {
        flushPendingWrites()
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
