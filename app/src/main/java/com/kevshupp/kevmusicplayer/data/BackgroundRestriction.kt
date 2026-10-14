package com.kevshupp.kevmusicplayer.data

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * Indica si Android tiene la app en "Restringida" (no puede ejecutarse ni pasar a primer plano en segundo plano).
 *
 * `ActivityManager.isBackgroundRestricted` no basta: en el moto g35 la app puede estar exenta de la
 * optimización de batería ("Sin restricciones" en Ajustes) y a la vez tener denegado el permiso
 * RUN_ANY_IN_BACKGROUND; entonces la API devuelve false pero Android rechaza startForeground().
 * Se lee también ese permiso directamente.
 */
object BackgroundRestriction {
    private const val OPSTR_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background"

    fun isRestricted(context: Context): Boolean {
        val byActivityManager = try {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
        } catch (e: Exception) {
            false
        }
        if (byActivityManager) return true
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            appOps.unsafeCheckOpNoThrow(OPSTR_RUN_ANY_IN_BACKGROUND, Process.myUid(), context.packageName) !=
                AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }
}
