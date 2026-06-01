package com.kevshupp.kevmusicplayer.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll

class MusicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MusicWidget()

    companion object {
        suspend fun updateAllWidgets(context: Context) {
            MusicWidget().updateAll(context)
        }
    }
}
