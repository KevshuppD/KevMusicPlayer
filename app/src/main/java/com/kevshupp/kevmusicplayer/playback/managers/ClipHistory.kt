package com.kevshupp.kevmusicplayer.playback.managers

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Ajustes con los que se creó un video: "Editar" vuelve a abrir el creador con ellos. */
data class ClipSettings(
    val template: ClipTemplateStyle,
    val format: ClipFormat,
    val quality: ClipQuality,
    val font: ClipFont,
    val uppercase: Boolean,
    val textScale: Float,
    val background: ClipBackground,
    val waveform: Boolean,
    val pixelPalette: String,
    val translation: Boolean,
    val dedication: String,
    val fps: Int = 30,
    val pixelArtMode: PixelArtMode = PixelArtMode.PALETTE
)

data class ClipEntry(
    val id: String,
    val songId: Long,
    val title: String,
    val artist: String,
    val startMs: Long,
    val endMs: Long,
    val createdAt: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val settings: ClipSettings,
    /** Nombre del MP4 en la carpeta (es el que ve quien lo recibe al compartir). */
    val fileName: String = "$id.mp4"
)

/**
 * Historial de videos creados ("Mis videos"): el MP4, una miniatura y sus datos en `filesDir/clips/`
 * (`<id>.mp4`, `<id>.jpg` e `index.json`). Se conservan hasta que el usuario los borra; no entran en las copias.
 */
object ClipHistory {
    private val _entries = MutableStateFlow<List<ClipEntry>>(emptyList())
    val entries: StateFlow<List<ClipEntry>> = _entries
    @Volatile private var loaded = false

    private fun dir(context: Context) = File(context.filesDir, "clips").apply { mkdirs() }
    fun videoFile(context: Context, entry: ClipEntry) = File(dir(context), entry.fileName)
    fun thumbFile(context: Context, id: String) = File(dir(context), "$id.jpg")

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        val index = File(dir(context), "index.json")
        val list = runCatching {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).mapNotNull { parse(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        // Un video borrado a mano de la carpeta no se lista
        _entries.value = list.filter { videoFile(context, it).exists() }.sortedByDescending { it.createdAt }
        loaded = true
    }

    /** Mueve el video generado al historial, crea su miniatura y lo registra. Devuelve el archivo final. */
    suspend fun add(context: Context, generated: File, songId: Long, config: ShareClipConfig, settings: ClipSettings): File =
        withContext(Dispatchers.IO) {
            load(context)
            val id = System.currentTimeMillis().toString()
            val safeTitle = config.songTitle.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40).ifEmpty { "video" }
            val fileName = "KevMusic_${safeTitle}_$id.mp4"
            val target = File(dir(context), fileName)
            // Copia, no renameTo: el archivo de cacheDir conserva el grupo de caché y seguiría contando como caché
            generated.copyTo(target, overwrite = true)
            generated.delete()
            saveThumbnail(target, thumbFile(context, id), (config.endMs - config.startMs) / 2)
            val (w, h) = VideoClipGenerator.outputSize(config)
            val entry = ClipEntry(
                id, songId, config.songTitle, config.artistName, config.startMs, config.endMs,
                System.currentTimeMillis(), w, h, target.length(), settings, fileName
            )
            _entries.value = listOf(entry) + _entries.value
            save(context)
            com.kevshupp.kevmusicplayer.widget.WidgetTheme.refreshClips(context.applicationContext)
            target
        }

    suspend fun delete(context: Context, entry: ClipEntry) = withContext(Dispatchers.IO) {
        videoFile(context, entry).delete()
        thumbFile(context, entry.id).delete()
        _entries.value = _entries.value.filterNot { it.id == entry.id }
        save(context)
        com.kevshupp.kevmusicplayer.widget.WidgetTheme.refreshClips(context.applicationContext)
    }

    private fun saveThumbnail(video: File, out: File, atMs: Long) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val frame = retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return
            val scale = 360f / maxOf(frame.width, frame.height)
            val small = Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
            out.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (small !== frame) small.recycle()
            frame.recycle()
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        _entries.value.forEach { arr.put(toJson(it)) }
        val index = File(dir(context), "index.json")
        val tmp = File(dir(context), "index.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(index)
    }

    private fun toJson(e: ClipEntry) = JSONObject()
        .put("id", e.id).put("songId", e.songId).put("title", e.title).put("artist", e.artist)
        .put("start", e.startMs).put("end", e.endMs).put("created", e.createdAt)
        .put("w", e.width).put("h", e.height).put("size", e.sizeBytes).put("file", e.fileName)
        .put("settings", JSONObject()
            .put("template", e.settings.template.name).put("format", e.settings.format.name)
            .put("quality", e.settings.quality.name).put("font", e.settings.font.name)
            .put("upper", e.settings.uppercase).put("scale", e.settings.textScale.toDouble())
            .put("bg", e.settings.background.name).put("wave", e.settings.waveform)
            .put("palette", e.settings.pixelPalette).put("trans", e.settings.translation)
            .put("dedication", e.settings.dedication).put("fps", e.settings.fps)
            .put("pixelArt", e.settings.pixelArtMode.name))

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E) =
        runCatching { enumValueOf<E>(name ?: "") }.getOrDefault(default)

    private fun parse(j: JSONObject): ClipEntry? = runCatching {
        val s = j.optJSONObject("settings") ?: JSONObject()
        ClipEntry(
            j.getString("id"), j.optLong("songId"), j.optString("title"), j.optString("artist"),
            j.optLong("start"), j.optLong("end"), j.optLong("created"), j.optInt("w"), j.optInt("h"), j.optLong("size"),
            ClipSettings(
                enumOr(s.optString("template"), ClipTemplateStyle.LYRICS_STORY),
                enumOr(s.optString("format"), ClipFormat.STORY),
                enumOr(s.optString("quality"), ClipQuality.HD),
                enumOr(s.optString("font"), ClipFont.SANS),
                s.optBoolean("upper"),
                s.optDouble("scale", 1.0).toFloat(),
                enumOr(s.optString("bg"), ClipBackground.AMBIENT),
                s.optBoolean("wave", true),
                s.optString("palette", "pixel"),
                s.optBoolean("trans"),
                s.optString("dedication"),
                s.optInt("fps", 30),
                enumOr(s.optString("pixelArt"), PixelArtMode.PALETTE)
            ),
            j.optString("file").ifEmpty { j.getString("id") + ".mp4" }
        )
    }.getOrNull()
}
