package com.kevshupp.kevmusicplayer.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.ActionParameters
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.ContentScale
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.ColorFilter
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.currentState
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.kevshupp.kevmusicplayer.MainActivity
import com.kevshupp.kevmusicplayer.playback.PlaybackService
import androidx.core.content.ContextCompat
import android.content.ComponentName
import androidx.media3.session.SessionToken
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MusicWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        android.util.Log.d("WidgetDebug", "provideGlance called")
        try {
            provideContent {
                WidgetContent(context)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.printStackTrace()
            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                context,
                "Widget_ProvideGlance",
                "Failed inside provideGlance content",
                e
            )
            throw e
        }
    }

    @Composable
    private fun WidgetContent(context: Context) {
        android.util.Log.d("WidgetDebug", "WidgetContent: Start composition")
        val prefs = currentState<Preferences>()
        val title = prefs[stringPreferencesKey("title")] ?: ""
        val artist = prefs[stringPreferencesKey("artist")] ?: ""
        val isPlaying = prefs[booleanPreferencesKey("isPlaying")] ?: false

        android.util.Log.d("WidgetDebug", "WidgetContent: title = $title, artist = $artist, isPlaying = $isPlaying")

        val artFile = java.io.File(context.cacheDir, "current_widget_art.png")
        val bitmap = if (artFile.exists()) {
            try {
                android.graphics.BitmapFactory.decodeFile(artFile.absolutePath)
            } catch (e: Exception) {
                android.util.Log.e("WidgetDebug", "Error decoding art file", e)
                null
            }
        } else {
            null
        }

        android.util.Log.d("WidgetDebug", "WidgetContent: bitmap decoded, isNull = ${bitmap == null}")

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFF161829)))
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album Cover image / placeholder (Click to launch main app)
                Box(
                    modifier = GlanceModifier
                        .size(52.dp)
                        .background(ColorProvider(Color(0xFF1E1B4B)))
                        .cornerRadius(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            provider = ImageProvider(bitmap),
                            contentDescription = "Cover",
                            modifier = GlanceModifier.fillMaxSize().cornerRadius(12.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // Vibrant glowing neon center placeholder
                        Box(
                            modifier = GlanceModifier
                                .size(36.dp)
                                .background(ColorProvider(Color(0xFF7C4DFF)))
                                .cornerRadius(18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                provider = ImageProvider(android.R.drawable.ic_media_play),
                                contentDescription = "Cover Placeholder",
                                modifier = GlanceModifier.size(16.dp),
                                colorFilter = ColorFilter.tint(ColorProvider(Color.Black))
                            )
                        }
                    }
                }

                Spacer(modifier = GlanceModifier.width(12.dp))

                // Track details
                Column(
                    modifier = GlanceModifier.defaultWeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (title.isEmpty()) "Kev Music Player" else title,
                        style = TextStyle(
                            color = ColorProvider(Color.White),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                    Spacer(modifier = GlanceModifier.height(2.dp))
                    Text(
                        text = if (artist.isEmpty()) "Dispositivo Listo" else artist,
                        style = TextStyle(
                            color = ColorProvider(Color(0xFFA9B2C3)),
                            fontSize = 12.sp
                        ),
                        maxLines = 1
                    )
                }

                Spacer(modifier = GlanceModifier.width(8.dp))

                // Playback Control Buttons (Right aligned)
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous Track Button
                    Box(
                        modifier = GlanceModifier
                            .size(36.dp)
                            .cornerRadius(18.dp)
                            .background(ColorProvider(Color(0xFF222436)))
                            .clickable(actionRunCallback<PreviousCallback>()),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(android.R.drawable.ic_media_previous),
                            contentDescription = "Previous",
                            modifier = GlanceModifier.size(18.dp),
                            colorFilter = ColorFilter.tint(ColorProvider(Color.White))
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(8.dp))

                    // Play / Pause Circle Button
                    Box(
                        modifier = GlanceModifier
                            .size(44.dp)
                            .cornerRadius(22.dp)
                            .background(ColorProvider(Color(0xFF7C4DFF)))
                            .clickable(actionRunCallback<PlayPauseCallback>()),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(
                                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                            ),
                            contentDescription = "Play/Pause",
                            modifier = GlanceModifier.size(20.dp),
                            colorFilter = ColorFilter.tint(ColorProvider(Color.Black))
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(8.dp))

                    // Next Track Button
                    Box(
                        modifier = GlanceModifier
                            .size(36.dp)
                            .cornerRadius(18.dp)
                            .background(ColorProvider(Color(0xFF222436)))
                            .clickable(actionRunCallback<NextCallback>()),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            provider = ImageProvider(android.R.drawable.ic_media_next),
                            contentDescription = "Next",
                            modifier = GlanceModifier.size(18.dp),
                            colorFilter = ColorFilter.tint(ColorProvider(Color.White))
                        )
                    }
                }
            }
        }
    }
}

class PlayPauseCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val appContext = context.applicationContext
                val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
                val controller = MediaController.Builder(appContext, sessionToken).buildAsync().await()
                if (controller.isPlaying) {
                    controller.pause()
                } else {
                    controller.play()
                }
                kotlinx.coroutines.delay(500)
                controller.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                context,
                "Widget_PlayPause",
                "Failed to toggle play/pause from widget",
                e
            )
        }
    }
}

class NextCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val appContext = context.applicationContext
                val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
                val controller = MediaController.Builder(appContext, sessionToken).buildAsync().await()
                if (controller.hasNextMediaItem()) {
                    controller.seekToNextMediaItem()
                }
                kotlinx.coroutines.delay(500)
                controller.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                context,
                "Widget_Next",
                "Failed to skip to next from widget",
                e
            )
        }
    }
}

class PreviousCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val appContext = context.applicationContext
                val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
                val controller = MediaController.Builder(appContext, sessionToken).buildAsync().await()
                if (controller.hasPreviousMediaItem()) {
                    controller.seekToPreviousMediaItem()
                }
                kotlinx.coroutines.delay(500)
                controller.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            com.kevshupp.kevmusicplayer.data.TelemetryLogger.logError(
                context,
                "Widget_Previous",
                "Failed to skip to previous from widget",
                e
            )
        }
    }
}

// Suspend extension function to await ListenableFuture in coroutines
suspend fun <T> ListenableFuture<T>.await(): T {
    return suspendCancellableCoroutine { continuation ->
        addListener({
            try {
                continuation.resume(get())
            } catch (e: ExecutionException) {
                continuation.resumeWithException(e.cause ?: e)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }, { command -> command.run() })
        continuation.invokeOnCancellation {
            cancel(true)
        }
    }
}

