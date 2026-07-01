package com.kevshupp.kevmusicplayer.widget

import android.content.Context
import android.content.ComponentName
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import androidx.media3.session.SessionToken
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.kevshupp.kevmusicplayer.playback.PlaybackService

class MusicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MusicWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            try {
                val appContext = context.applicationContext
                val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
                val controller = MediaController.Builder(appContext, sessionToken).buildAsync().await()
                // Wait to make sure the connection syncs and updates the widget, then release
                delay(500)
                controller.release()
            } catch (e: Exception) {
                e.printStackTrace()
                com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                    context,
                    "WidgetReceiver_onUpdate",
                    "Failed in Glance widget update",
                    e
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }

    companion object {
        suspend fun updateAllWidgets(context: Context) {
            MusicWidget().updateAll(context)
        }
    }
}
