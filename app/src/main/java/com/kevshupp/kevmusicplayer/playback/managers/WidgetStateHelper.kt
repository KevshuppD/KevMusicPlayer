package com.kevshupp.kevmusicplayer.playback.managers

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import com.kevshupp.kevmusicplayer.ui.screens.albumArtCache
import com.kevshupp.kevmusicplayer.ui.screens.loadAlbumArtBitmap
import com.kevshupp.kevmusicplayer.widget.MusicWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Handles updating Glance widget state and generating compressed cover art previews asynchronously.
 */
class WidgetStateHelper(
    private val context: Context,
    private val scope: CoroutineScope
) {
    fun updateWidgetState(title: String, artist: String, isPlaying: Boolean, uriString: String? = null) {
        Log.d("WidgetDebug", "updateWidgetState: title = $title, artist = $artist, isPlaying = $isPlaying, uri = $uriString")

        scope.launch(Dispatchers.IO) {
            val artFile = File(context.cacheDir, "current_widget_art.png")
            if (uriString != null) {
                var success = false
                try {
                    val cachedBmp = albumArtCache.get(uriString)
                    val targetSize = 200

                    if (cachedBmp != null && !cachedBmp.isRecycled) {
                        val scaledBitmap = Bitmap.createScaledBitmap(cachedBmp, targetSize, targetSize, true)
                        val tmpFile = File(context.cacheDir, "current_widget_art_tmp.png")
                        FileOutputStream(tmpFile).use { out ->
                            scaledBitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
                        }
                        if (tmpFile.exists()) {
                            tmpFile.renameTo(artFile)
                        }
                        if (scaledBitmap != cachedBmp) {
                            scaledBitmap.recycle()
                        }
                        success = true
                    } else {
                        val loadedBmp = loadAlbumArtBitmap(context, uriString)
                        if (loadedBmp != null && !loadedBmp.isRecycled) {
                            val scaledBitmap = Bitmap.createScaledBitmap(loadedBmp, targetSize, targetSize, true)
                            val tmpFile = File(context.cacheDir, "current_widget_art_tmp.png")
                            FileOutputStream(tmpFile).use { out ->
                                scaledBitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
                            }
                            if (tmpFile.exists()) {
                                tmpFile.renameTo(artFile)
                            }
                            if (scaledBitmap != loadedBmp) {
                                scaledBitmap.recycle()
                            }
                            success = true
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    e.printStackTrace()
                    TelemetryLogger.logError(
                        context,
                        "Widget_Art_Extract",
                        "Failed to extract art from uri $uriString for widget",
                        e
                    )
                }
                if (!success && artFile.exists()) {
                    artFile.delete()
                }
            } else {
                if (artFile.exists()) artFile.delete()
            }

            try {
                val manager = GlanceAppWidgetManager(context)
                val glanceIds = manager.getGlanceIds(MusicWidget::class.java)
                Log.d("WidgetDebug", "Found ${glanceIds.size} active widget instances to update")
                glanceIds.forEach { glanceId ->
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[stringPreferencesKey("title")] = title
                            this[stringPreferencesKey("artist")] = artist
                            this[booleanPreferencesKey("isPlaying")] = isPlaying
                        }
                    }
                    MusicWidget().update(context, glanceId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.printStackTrace()
                TelemetryLogger.logError(
                    context,
                    "Widget_State_Update",
                    "Failed to update Glance widget state for $title - $artist",
                    e
                )
            }
        }
    }
}
