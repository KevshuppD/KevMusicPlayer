package com.kevshupp.kevmusicplayer.playback.managers

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val pixelArtMode: PixelArtMode = PixelArtMode.PALETTE,
    val pixelScene: PixelScene = PixelScene.STARS
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
 * Video a medio hacer: el fragmento y el diseño elegidos en el creador. Se guarda al salir del creador (o de la app)
 * sin haber creado el video, y al empezar a crearlo; si Android cierra la app a mitad, queda [interrupted].
 */
data class ClipDraft(
    val id: String,
    val songId: Long,
    val title: String,
    val artist: String,
    val startMs: Long,
    val endMs: Long,
    val updatedAt: Long,
    val settings: ClipSettings,
    val generating: Boolean = false,
    val interrupted: Boolean = false
) {
    /** Para abrir el creador con él, igual que al editar un video. */
    fun toEntry() = ClipEntry(id, songId, title, artist, startMs, endMs, updatedAt, 0, 0, 0, settings, "")
}

/** Video que se está creando (uno a la vez). Vive fuera del creador: cerrarlo no lo detiene. */
data class ClipJob(
    val draftId: String,
    val title: String,
    val progress: Float = 0f,
    val startedAt: Long = System.currentTimeMillis(),
    val result: File? = null,
    val error: String? = null
) {
    val running get() = result == null && error == null
}

/**
 * Historial de videos creados ("Mis videos"): el MP4, una miniatura y sus datos en `filesDir/clips/`
 * (`<id>.mp4`, `<id>.jpg` e `index.json`). Se conservan hasta que el usuario los borra; no entran en las copias.
 */
object ClipHistory {
    private val _entries = MutableStateFlow<List<ClipEntry>>(emptyList())
    val entries: StateFlow<List<ClipEntry>> = _entries
    private val _drafts = MutableStateFlow<List<ClipDraft>>(emptyList())
    val drafts: StateFlow<List<ClipDraft>> = _drafts
    private val _job = MutableStateFlow<ClipJob?>(null)
    val job: StateFlow<ClipJob?> = _job
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val draftsLock = Mutex()
    private const val MAX_DRAFTS = 20
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
        // Un borrador que estaba creándose al cargar es de un proceso anterior: la app se cerró a mitad
        _drafts.value = runCatching {
            val arr = JSONArray(File(dir(context), "drafts.json").readText())
            (0 until arr.length()).mapNotNull { parseDraft(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
            .map { if (it.generating) it.copy(generating = false, interrupted = true) else it }
            .sortedByDescending { it.updatedAt }
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

    /** Guarda o actualiza un borrador (mismo id = mismo borrador). */
    fun saveDraft(context: Context, draft: ClipDraft) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            load(app)
            draftsLock.withLock {
                _drafts.value = (listOf(draft) + _drafts.value.filterNot { it.id == draft.id }).take(MAX_DRAFTS)
                saveDrafts(app)
            }
        }
    }

    fun deleteDraft(context: Context, id: String) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            load(app)
            draftsLock.withLock {
                _drafts.value = _drafts.value.filterNot { it.id == id }
                saveDrafts(app)
            }
        }
    }

    /** Borrador más reciente de esa canción, para retomarlo al abrir el creador. */
    fun latestDraftFor(songId: Long): ClipDraft? =
        _drafts.value.filter { it.songId == songId && songId != 0L }.maxByOrNull { it.updatedAt }

    /**
     * Crea el video en segundo plano. Antes de empezar se guarda como borrador (si la app muere a mitad, queda
     * ahí); al terminar pasa a Mis videos y el borrador se borra. Devuelve false si ya hay otro creándose.
     */
    fun startGeneration(context: Context, draft: ClipDraft, config: ShareClipConfig): Boolean {
        if (_job.value?.running == true) return false
        val app = context.applicationContext
        _job.value = ClipJob(draft.id, draft.title)
        scope.launch {
            load(app)
            draftsLock.withLock {
                _drafts.value = listOf(draft.copy(generating = true, interrupted = false)) + _drafts.value.filterNot { it.id == draft.id }
                saveDrafts(app)
            }
            val result = VideoClipGenerator.generateClip(app, config) { progress ->
                _job.value = _job.value?.copy(progress = progress)
            }
            val stored = result.getOrNull()?.let { runCatching { add(app, it, draft.songId, config, draft.settings) }.getOrNull() }
            if (stored != null) {
                draftsLock.withLock {
                    _drafts.value = _drafts.value.filterNot { it.id == draft.id }
                    saveDrafts(app)
                }
                _job.value = _job.value?.copy(progress = 1f, result = stored)
            } else {
                val msg = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: "no se pudo guardar"
                draftsLock.withLock {
                    _drafts.value = _drafts.value.map { if (it.id == draft.id) it.copy(generating = false) else it }
                    saveDrafts(app)
                }
                _job.value = _job.value?.copy(error = msg)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    app,
                    if (stored != null) "Video listo · guardado en Mis videos" else "Error al crear el video: ${_job.value?.error}",
                    if (stored != null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }
        return true
    }

    private fun saveDrafts(context: Context) {
        val arr = JSONArray()
        _drafts.value.forEach { d ->
            arr.put(JSONObject()
                .put("id", d.id).put("songId", d.songId).put("title", d.title).put("artist", d.artist)
                .put("start", d.startMs).put("end", d.endMs).put("updated", d.updatedAt)
                .put("generating", d.generating).put("interrupted", d.interrupted)
                .put("settings", settingsJson(d.settings)))
        }
        val tmp = File(dir(context), "drafts.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(dir(context), "drafts.json"))
    }

    private fun parseDraft(j: JSONObject): ClipDraft? = runCatching {
        ClipDraft(
            j.getString("id"), j.optLong("songId"), j.optString("title"), j.optString("artist"),
            j.optLong("start"), j.optLong("end"), j.optLong("updated"),
            parseSettings(j.optJSONObject("settings") ?: JSONObject()),
            j.optBoolean("generating"), j.optBoolean("interrupted")
        )
    }.getOrNull()

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
        .put("settings", settingsJson(e.settings))

    private fun settingsJson(st: ClipSettings) = JSONObject()
        .put("template", st.template.name).put("format", st.format.name)
        .put("quality", st.quality.name).put("font", st.font.name)
        .put("upper", st.uppercase).put("scale", st.textScale.toDouble())
        .put("bg", st.background.name).put("wave", st.waveform)
        .put("palette", st.pixelPalette).put("trans", st.translation)
        .put("dedication", st.dedication).put("fps", st.fps)
        .put("pixelArt", st.pixelArtMode.name).put("scene", st.pixelScene.name)

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E) =
        runCatching { enumValueOf<E>(name ?: "") }.getOrDefault(default)

    private fun parseSettings(s: JSONObject) = ClipSettings(
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
        enumOr(s.optString("pixelArt"), PixelArtMode.PALETTE),
        enumOr(s.optString("scene"), PixelScene.STARS)
    )

    private fun parse(j: JSONObject): ClipEntry? = runCatching {
        val s = j.optJSONObject("settings") ?: JSONObject()
        ClipEntry(
            j.getString("id"), j.optLong("songId"), j.optString("title"), j.optString("artist"),
            j.optLong("start"), j.optLong("end"), j.optLong("created"), j.optInt("w"), j.optInt("h"), j.optLong("size"),
            parseSettings(s),
            j.optString("file").ifEmpty { j.getString("id") + ".mp4" }
        )
    }.getOrNull()
}
