package com.kevshupp.kevmusic.desktop.local

import com.kevshupp.kevmusic.desktop.Config
import com.kevshupp.kevmusic.desktop.Platform
import com.kevshupp.kevmusic.desktop.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Una canción del PC: los datos de [song] y su archivo. */
data class LocalTrack(val song: Song, val path: String, val size: Long, val modified: Long)

/**
 * Biblioteca del modo local: escanea las carpetas de música del PC, lee las etiquetas con jaudiotagger y guarda un
 * índice en `<config>/local_library.json` (por ruta, tamaño y fecha) para no releer los archivos sin cambios.
 * Los ids son negativos y grandes ([isLocalId]) para no chocar con los del teléfono ni con los bajo demanda.
 */
object LocalLibrary {
    private val indexFile = File(Config.dir, "local_library.json")
    private val statsFile = File(Config.dir, "local_stats.json")
    private val artDir = File(Platform.cacheDir, "local_art")
    val EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "aiff", "aif", "ape", "wv", "mka")

    init {
        // jaudiotagger escribe mucho por java.util.logging
        java.util.logging.Logger.getLogger("org.jaudiotagger").level = java.util.logging.Level.OFF
    }

    private const val LOCAL_BASE = 1L shl 61

    fun isLocalId(id: Long) = id <= -LOCAL_BASE

    fun idFor(path: String): Long {
        val d = MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
        var h = 0L
        for (i in 0 until 8) h = (h shl 8) or (d[i].toLong() and 0xFF)
        return -(LOCAL_BASE + (h and (LOCAL_BASE - 1)))
    }

    /** Carpeta de música por defecto (XDG_MUSIC_DIR o ~/Música / ~/Music). */
    fun defaultFolder(): File {
        val home = System.getProperty("user.home")
        runCatching {
            val p = ProcessBuilder("xdg-user-dir", "MUSIC").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && out.isNotBlank() && out != home) return File(out)
        }
        return listOf(File(home, "Música"), File(home, "Music")).firstOrNull { it.isDirectory } ?: File(home, "Music")
    }

    /**
     * Escanea [folders]; [progress] recibe (leídas, total). Devuelve las pistas ordenadas por artista, álbum y número.
     */
    suspend fun scan(folders: List<File>, progress: (Int, Int) -> Unit = { _, _ -> }): List<LocalTrack> = withContext(Dispatchers.IO) {
        val files = folders.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().onEnter { !it.name.startsWith(".") }
                .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }.toList()
        }.distinctBy { it.absolutePath }
        val cached = loadIndex().associateBy { it.path }
        val stats = loadStats()
        val done = java.util.concurrent.atomic.AtomicInteger()
        val gate = Semaphore(4)
        val tracks = coroutineScope {
            files.map { f ->
                async {
                    gate.withPermit {
                        val old = cached[f.absolutePath]
                        val t = if (old != null && old.size == f.length() && old.modified == f.lastModified()) old else readTrack(f)
                        progress(done.incrementAndGet(), files.size)
                        t?.let { tr -> stats[tr.song.id]?.let { (plays, last) -> tr.copy(song = tr.song.copy(plays = plays, lastPlayed = last)) } ?: tr }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        saveIndex(tracks)
        tracks.sortedWith(compareBy({ it.song.artist.lowercase() }, { it.song.album.lowercase() }, { it.song.track }, { it.song.title.lowercase() }))
    }

    /** Lo último escaneado, sin tocar los archivos (para mostrar la biblioteca al instante al abrir). */
    fun cachedTracks(): List<LocalTrack> {
        val stats = loadStats()
        return loadIndex().filter { File(it.path).isFile }.map { t ->
            stats[t.song.id]?.let { (plays, last) -> t.copy(song = t.song.copy(plays = plays, lastPlayed = last)) } ?: t
        }
    }

    private fun readTrack(f: File): LocalTrack? = runCatching {
        val audio = org.jaudiotagger.audio.AudioFileIO.read(f)
        val tag = audio.tag
        fun field(k: org.jaudiotagger.tag.FieldKey) = runCatching { tag?.getFirst(k).orEmpty().trim() }.getOrDefault("")
        val title = field(org.jaudiotagger.tag.FieldKey.TITLE).ifBlank { f.nameWithoutExtension }
        val artist = field(org.jaudiotagger.tag.FieldKey.ARTIST).ifBlank { field(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST) }
            .ifBlank { f.parentFile?.parentFile?.name ?: "Artista desconocido" }
        val album = field(org.jaudiotagger.tag.FieldKey.ALBUM).ifBlank { f.parentFile?.name.orEmpty() }
        val track = field(org.jaudiotagger.tag.FieldKey.TRACK).substringBefore('/').toIntOrNull() ?: 0
        val year = field(org.jaudiotagger.tag.FieldKey.YEAR).take(4)
        val genre = field(org.jaudiotagger.tag.FieldKey.GENRE)
        val duration = audio.audioHeader?.preciseTrackLength?.let { (it * 1000).toLong() } ?: 0L
        val song = Song(
            idFor(f.absolutePath), title, artist, album, duration, track, year, genre,
            plays = 0, added = f.lastModified() / 1000
        )
        LocalTrack(song, f.absolutePath, f.length(), f.lastModified())
    }.getOrNull()

    // ---------- Carátulas ----------

    /** JPEG/PNG de la carátula: la incrustada o una imagen de la carpeta (cover, folder, front). Se guarda en caché. */
    fun artBytes(track: LocalTrack): ByteArray? {
        val cache = File(artDir, "${-track.song.id}_${track.modified}.img")
        if (cache.isFile) return cache.readBytes().takeIf { it.isNotEmpty() }
        val bytes = runCatching {
            org.jaudiotagger.audio.AudioFileIO.read(File(track.path)).tag?.firstArtwork?.binaryData
        }.getOrNull() ?: folderImage(File(track.path).parentFile)?.readBytes()
        runCatching { artDir.mkdirs(); cache.writeBytes(bytes ?: ByteArray(0)) }
        return bytes
    }

    private fun folderImage(dir: File?): File? {
        val images = dir?.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png") } ?: return null
        return images.firstOrNull { it.nameWithoutExtension.lowercase() in setOf("cover", "folder", "front", "album", "albumart") }
            ?: images.singleOrNull()
    }

    // ---------- Letras ----------

    /** (letra, sincronizada?) del .lrc junto al archivo o de la etiqueta de letra. */
    fun lyrics(track: LocalTrack): String? {
        val f = File(track.path)
        File(f.parentFile, f.nameWithoutExtension + ".lrc").takeIf { it.isFile }?.let { return it.readText() }
        return runCatching { org.jaudiotagger.audio.AudioFileIO.read(f).tag?.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS) }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    // ---------- Reproducciones (solo del PC) ----------

    @Synchronized
    fun recordPlay(id: Long, at: Long): Pair<Int, Long> {
        val stats = loadStats().toMutableMap()
        val next = (stats[id]?.first ?: 0) + 1 to at
        stats[id] = next
        runCatching {
            Config.dir.mkdirs()
            statsFile.writeText(JSONObject().apply { stats.forEach { (k, v) -> put(k.toString(), JSONArray().put(v.first).put(v.second)) } }.toString())
        }
        return next
    }

    private fun loadStats(): Map<Long, Pair<Int, Long>> = runCatching {
        val j = JSONObject(statsFile.readText())
        j.keys().asSequence().associate { k -> val a = j.getJSONArray(k); k.toLong() to (a.getInt(0) to a.getLong(1)) }
    }.getOrDefault(emptyMap())

    // ---------- Índice ----------

    private fun saveIndex(tracks: List<LocalTrack>) = runCatching {
        Config.dir.mkdirs()
        val arr = JSONArray()
        tracks.forEach { t ->
            val s = t.song
            arr.put(JSONObject().put("p", t.path).put("sz", t.size).put("m", t.modified).put("t", s.title).put("ar", s.artist)
                .put("al", s.album).put("d", s.duration).put("n", s.track).put("y", s.year).put("g", s.genre).put("a", s.added))
        }
        val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(indexFile)
    }

    private fun loadIndex(): List<LocalTrack> = runCatching {
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val path = o.getString("p")
            LocalTrack(
                Song(idFor(path), o.optString("t"), o.optString("ar"), o.optString("al"), o.optLong("d"), o.optInt("n"),
                    o.optString("y"), o.optString("g"), 0, o.optLong("a")),
                path, o.optLong("sz"), o.optLong("m")
            )
        }
    }.getOrDefault(emptyList())
}
