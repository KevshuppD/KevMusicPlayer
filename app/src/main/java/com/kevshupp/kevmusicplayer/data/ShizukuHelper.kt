package com.kevshupp.kevmusicplayer.data

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Quita la restricción de segundo plano con Shizuku (permisos de `adb shell`).
 *
 * El gestor de batería de Motorola deja RUN_ANY_IN_BACKGROUND denegado aunque Ajustes muestre
 * "Sin restricciones", y la app no puede cambiarlo por sí misma. Con Shizuku se ejecuta el mismo
 * `cmd appops set` que desde adb. Ver [BackgroundRestriction].
 */
object ShizukuHelper {
    private const val REQUEST_CODE = 4711

    fun isRunning(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun hasPermission(): Boolean = try {
        isRunning() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** Pide el permiso de Shizuku si hace falta; devuelve si quedó concedido. */
    suspend fun requestPermission(): Boolean {
        if (!isRunning()) return false
        if (hasPermission()) return true
        return suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            try {
                Shizuku.requestPermission(REQUEST_CODE)
            } catch (e: Throwable) {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    /** Permite ejecutar en segundo plano y exime de la optimización de batería. Devuelve si dejó de estar restringida. */
    suspend fun removeBackgroundRestriction(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext false
        val pkg = context.packageName
        listOf(
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
            "cmd deviceidle whitelist +$pkg"
        ).forEach { command ->
            val exit = runShell(command)
            if (exit != 0) TelemetryLogger.logWarn(context, "Shizuku", "'$command' exited with $exit")
        }
        !BackgroundRestriction.isRestricted(context)
    }

    // Shizuku.newProcess es privado desde la API 13, pero sigue siendo la forma más simple de
    // ejecutar un comando de shell sin declarar un UserService.
    private fun runShell(command: String): Int = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        process.inputStream.readBytes()
        process.errorStream.readBytes()
        process.waitFor()
    } catch (e: Throwable) {
        e.printStackTrace()
        -1
    }
}
