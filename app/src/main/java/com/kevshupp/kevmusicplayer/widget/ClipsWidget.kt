package com.kevshupp.kevmusicplayer.widget

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kevshupp.kevmusicplayer.MainActivity
import com.kevshupp.kevmusicplayer.R
import com.kevshupp.kevmusicplayer.playback.managers.ClipHistory

/** Widget "Mis videos": los últimos videos creados; tocarlo abre la app en Mis videos. */
class ClipsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        ClipHistory.load(context)
        val entries = ClipHistory.entries.value
        val thumbs = entries.take(3).mapNotNull { e ->
            runCatching { BitmapFactory.decodeFile(ClipHistory.thumbFile(context, e.id).absolutePath) }.getOrNull()
        }
        val colors = WidgetTheme.colors(context)
        provideContent { Content(context, entries.size, thumbs, colors) }
    }

    @Composable
    private fun Content(context: Context, count: Int, thumbs: List<android.graphics.Bitmap>, c: WidgetColors) {
        val open = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_CLIPS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(c.background))
                .cornerRadius(18.dp)
                .padding(12.dp)
                .clickable(actionStartActivity(open))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                Box(
                    modifier = GlanceModifier.size(32.dp).background(ColorProvider(c.primary)).cornerRadius(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_videos),
                        contentDescription = null,
                        modifier = GlanceModifier.size(18.dp),
                        colorFilter = ColorFilter.tint(ColorProvider(c.onPrimary))
                    )
                }
                Spacer(GlanceModifier.width(10.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text("Mis videos", style = TextStyle(color = ColorProvider(c.text), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    Text(
                        if (count == 0) "Crea uno desde el reproductor" else "$count video${if (count == 1) "" else "s"}",
                        style = TextStyle(color = ColorProvider(c.muted), fontSize = 11.sp)
                    )
                }
            }
            if (thumbs.isNotEmpty()) {
                Spacer(GlanceModifier.height(10.dp))
                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                    thumbs.forEachIndexed { i, bmp ->
                        if (i > 0) Spacer(GlanceModifier.width(6.dp))
                        Image(
                            provider = ImageProvider(bmp),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = GlanceModifier.defaultWeight().fillMaxSize().cornerRadius(10.dp).background(ColorProvider(c.card))
                        )
                    }
                }
            }
        }
    }
}

class ClipsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ClipsWidget()
}
