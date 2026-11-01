package com.kevshupp.kevmusic.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class YtTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val thumbnail: String,
    val year: String,
    /** Posición en el álbum (0 si no se abrió desde uno). */
    val trackNo: Int = 0,
    /** Dónde está el audio, si no es un video de YouTube (SoundCloud). */
    val url: String = "",
    /** Los datos ya están revisados (disco armado a mano): no se piden ni se completan con Deezer. */
    val fixedTags: Boolean = false,
    /** Carátula de repuesto si [thumbnail] no se puede descargar. */
    val altCover: String = ""
)

/** Subida de SoundCloud candidata a una pista. */
data class ScCandidate(val id: String, val title: String, val uploader: String, val durationSec: Int, val url: String, val artwork: String)

data class YtAlbum(val id: String, val title: String, val artist: String, val kind: String, val thumbnail: String, val tracks: List<YtTrack>)

data class YtArtist(val id: String, val name: String, val songs: List<YtTrack>)

enum class YtStage { Queued, Installing, Downloading, Tagging, WaitingPhone, Sending, Done, Failed }

data class YtJob(val track: YtTrack, val stage: YtStage, val percent: Int = 0, val message: String = "")

/**
 * YouTube Music con yt-dlp: se descarga en el PC y se envía al teléfono con `PUT /upload`, como Soulseek.
 * yt-dlp se instala en la carpeta de configuración y se actualiza solo, porque YouTube cambia a menudo y una
 * versión de hace semanas deja de funcionar (la de los repositorios de Debian/Ubuntu suele serlo). No se usa
 * ninguna cuenta de Google. Las descargas pendientes no se conservan al cerrar la app.
 */
object YtMusic {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val assetName = if (Platform.isWindows) "yt-dlp.exe" else "yt-dlp_linux"
    private val tool = File(Platform.configDir, "bin/" + if (Platform.isWindows) "yt-dlp.exe" else "yt-dlp")
    private val workDir = File(Platform.cacheDir, "ytmusic")
    private val toolLock = Mutex()
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var updateChecked = false
    private val idPattern = Regex("[A-Za-z0-9_-]{6,20}")
    private val browseId = Regex("[A-Za-z0-9_-]{6,80}")

    private val _jobs = MutableStateFlow<List<YtJob>>(emptyList())
    val jobs: StateFlow<List<YtJob>> = _jobs

    private val ffmpegLock = Mutex()
    internal var ffmpegDir = File(Platform.configDir, "bin")
    internal var ignoreSystemFfmpeg = false
    /** Lo que se ejecuta: "ffmpeg" del sistema o la copia propia, tras [ensureFfmpeg]. */
    private var ffmpeg = "ffmpeg"
    private var ffmpegChecked = false
    private val exe = if (Platform.isWindows) ".exe" else ""

    private fun works(command: String) = runCatching {
        val p = ProcessBuilder(command, "-version").redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        p.waitFor() == 0
    }.getOrDefault(false)

    /**
     * Usa el ffmpeg del sistema si hay; si no, instala una vez la compilación que publica yt-dlp para este uso
     * (ffmpeg y ffprobe, ~160–200 MB de descarga, verificada con SHA-256) junto a yt-dlp, sin pedir permisos.
     */
    internal suspend fun ensureFfmpeg(onPercent: (Int) -> Unit = {}) = ffmpegLock.withLock {
        if (ffmpegChecked) return@withLock
        val own = File(ffmpegDir, "ffmpeg$exe")
        ffmpeg = when {
            !ignoreSystemFfmpeg && works("ffmpeg") -> "ffmpeg"
            own.exists() && File(ffmpegDir, "ffprobe$exe").exists() && works(own.path) -> own.path
            else -> { installFfmpeg(onPercent); own.path }
        }
        ffmpegChecked = true
    }

    private fun installFfmpeg(onPercent: (Int) -> Unit) {
        ffmpegDir.mkdirs()
        val asset = if (Platform.isWindows) "ffmpeg-master-latest-win64-gpl.zip" else "ffmpeg-master-latest-linux64-gpl.tar.xz"
        val base = "https://github.com/yt-dlp/FFmpeg-Builds/releases/latest/download/"
        val expected = get(base + "checksums.sha256").use { it.body!!.string() }.lineSequence().map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 2 && it[1] == asset }?.get(0) ?: throw IOException("No se encontró ffmpeg para este sistema")
        val archive = File(ffmpegDir, "$asset.part")
        val work = File(ffmpegDir, ".ffmpeg_install")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            get(base + asset).use { resp ->
                val total = resp.body!!.contentLength().coerceAtLeast(1)
                var done = 0L
                archive.outputStream().use { out ->
                    val input = resp.body!!.byteStream()
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                        done += n
                        onPercent((done * 100 / total).toInt())
                    }
                }
            }
            if (md.digest().joinToString("") { "%02x".format(it) } != expected.lowercase()) throw IOException("La descarga de ffmpeg no coincide con su SHA-256")
            work.deleteRecursively()
            work.mkdirs()
            extractFfmpeg(archive, work)
            for (name in listOf("ffmpeg$exe", "ffprobe$exe")) {
                val found = work.walkTopDown().firstOrNull { it.isFile && it.name == name } ?: throw IOException("El paquete de ffmpeg no trae $name")
                found.setExecutable(true, true)
                val target = File(ffmpegDir, name)
                target.delete()
                if (!found.renameTo(target)) throw IOException("No se pudo instalar $name")
            }
            if (!works(File(ffmpegDir, "ffmpeg$exe").path)) throw IOException("ffmpeg se instaló, pero no funciona en este sistema")
        } finally {
            archive.delete()
            work.deleteRecursively()
        }
    }

    private fun extractFfmpeg(archive: File, into: File) {
        if (Platform.isWindows) {
            java.util.zip.ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (name == "ffmpeg.exe" || name == "ffprobe.exe") File(into, name).outputStream().use { zip.copyTo(it) }
                }
            }
            return
        }
        val p = ProcessBuilder("tar", "-xJf", archive.path, "-C", into.path, "--wildcards", "*/bin/ffmpeg", "*/bin/ffprobe").redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) throw IOException("No se pudo descomprimir ffmpeg: ${log.take(200)}")
    }

    /** Descarga yt-dlp la primera vez (verificando su SHA-256) y lo actualiza cada 3 días como mucho. */
    private suspend fun ensureTool(): File = toolLock.withLock {
        if (!tool.exists()) {
            tool.parentFile.mkdirs()
            val base = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
            val sums = get(base + "SHA2-256SUMS").use { it.body!!.string() }
            val expected = sums.lineSequence().map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { it.size == 2 && it[1] == assetName }?.get(0)
                ?: throw IOException("yt-dlp no publica $assetName")
            val tmp = File(tool.parentFile, "$assetName.part")
            val md = MessageDigest.getInstance("SHA-256")
            get(base + assetName).use { resp ->
                tmp.outputStream().use { out ->
                    val input = resp.body!!.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
                }
            }
            if (md.digest().joinToString("") { "%02x".format(it) } != expected.lowercase()) {
                tmp.delete()
                throw IOException("La descarga de yt-dlp no coincide con su SHA-256")
            }
            tmp.setExecutable(true, true)
            if (!tmp.renameTo(tool)) throw IOException("No se pudo instalar yt-dlp")
        } else if (!updateChecked && System.currentTimeMillis() - tool.lastModified() > 3 * 24 * 3600_000L) {
            // Si falla (sin red) se sigue con la versión instalada
            runCatching {
                val p = ProcessBuilder(tool.path, "-U").redirectErrorStream(true).start()
                p.inputStream.readAllBytes()
                p.waitFor()
                tool.setLastModified(System.currentTimeMillis())
            }
        }
        updateChecked = true
        tool
    }

    private fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute().also {
        if (!it.isSuccessful) { it.close(); throw IOException("HTTP ${it.code} al descargar $url") }
    }

    /** Resultados de la sección Canciones, uno a uno a medida que yt-dlp los resuelve (~1,5 s cada uno). */
    fun search(query: String, limit: Int = 10): Flow<YtTrack> {
        val id = videoIdFrom(query)
        return when {
            id != null -> dump("https://www.youtube.com/watch?v=$id", 1)
            else -> dump("https://music.youtube.com/search".toHttpUrl().newBuilder().addQueryParameter("q", query).build().toString() + "#songs", limit)
        }
    }

    /** YouTube normal: videoclips y lo que no está en YouTube Music. */
    fun searchVideos(query: String, limit: Int = 10): Flow<YtTrack> = dump("ytsearch$limit:$query", limit)

    /** El id si el texto es un enlace de YouTube; un enlace a otra web no se abre. */
    fun videoIdFrom(text: String): String? = runCatching {
        val u = java.net.URI(text.trim())
        if (u.scheme != "https" && u.scheme != "http") return null
        val host = u.host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.").removePrefix("music.")
        val path = u.path.orEmpty()
        val id = when {
            host == "youtu.be" -> path.trim('/')
            host != "youtube.com" -> return null
            path.startsWith("/shorts/") || path.startsWith("/live/") -> path.split('/').getOrNull(2).orEmpty()
            path == "/watch" -> u.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=").orEmpty()
            else -> ""
        }
        id.takeIf { idPattern.matches(it) }
    }.getOrNull()

    /**
     * Sin datos de música (un video normal), título y artista salen del título: "RM '농담 (Joke)' Official MV" o
     * "Artista - Canción (Official Video)". Si no se reconoce, el título limpio y el nombre del canal.
     */
    internal fun titleAndArtistFromVideo(rawTitle: String, channel: String): Pair<String, String> {
        val clean = rawTitle
            .replace(Regex("\\s*[(\\[][^)\\]]*(official|oficial|lyric|video|audio|\\bmv\\b|\\bm/v\\b|visuali[sz]er)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(official|oficial)\\s*(music\\s*)?(video|mv|m/v|audio|lyric video)\\s*$|\\s+(mv|m/v)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
        Regex("^(.+?)\\s*['‘\"“](.+)['’\"”]$").find(clean)?.let { return it.groupValues[2].trim() to it.groupValues[1].trim() }
        Regex("^(.+?)\\s+[-–—]\\s+(.+)$").find(clean)?.let { return it.groupValues[2].trim() to it.groupValues[1].trim() }
        return clean.ifBlank { rawTitle } to channel.removeSuffix(" - Topic").removeSuffix("VEVO").trim()
    }

    private fun dump(url: String, limit: Int): Flow<YtTrack> = flow {
        val bin = ensureTool()
        val p = ProcessBuilder(
            bin.path, "--dump-json", "--skip-download", "--ignore-errors", "--ignore-no-formats-error",
            "--no-warnings", "--playlist-items", "1:$limit", url
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) parseTrack(line)?.let { emit(it) }
            }
        } finally {
            p.destroy()
        }
        if (p.waitFor() != 0 && p.exitValue() != 1) throw IOException("yt-dlp terminó con error ${p.exitValue()}")
    }.flowOn(Dispatchers.IO)

    /** Álbumes de la búsqueda con sus pistas. `index` es el puesto en YouTube Music, porque llegan desordenados. */
    fun searchAlbums(query: String, limit: Int = 12): Flow<Pair<Int, YtAlbum>> =
        searchSection(query, "albums", limit, null, ::parseAlbum)

    fun searchArtists(query: String, limit: Int = 6): Flow<Pair<Int, YtArtist>> =
        searchSection(query, "artists", limit, 20, ::parseArtist)

    /**
     * La búsqueda de álbumes y artistas de yt-dlp solo da ids, sin nombre: hay que abrir cada uno (~2 s), 4 a la vez.
     * [items] limita las canciones leídas de cada uno.
     */
    private fun <T : Any> searchSection(query: String, section: String, limit: Int, items: Int?, parse: (String) -> T?): Flow<Pair<Int, T>> = channelFlow {
        val bin = ensureTool()
        val url = "https://music.youtube.com/search".toHttpUrl().newBuilder().addQueryParameter("q", query).build().toString() + "#$section"
        val found = JSONObject(runTool(bin, "--flat-playlist", "-J", "--no-warnings", "--playlist-items", "1:$limit", url)).optJSONArray("entries") ?: return@channelFlow
        val gate = Semaphore(4)
        for (i in 0 until found.length()) {
            val id = found.optJSONObject(i)?.optString("id").orEmpty()
            if (!browseId.matches(id)) continue
            launch {
                gate.withPermit {
                    val args = mutableListOf("--flat-playlist", "-J", "--no-warnings")
                    if (items != null) args += listOf("--playlist-items", "1:$items")
                    args += "https://music.youtube.com/browse/$id"
                    runCatching { parse(runTool(bin, *args.toTypedArray())) }.getOrNull()?.let { send(i to withBrowseId(it, id)) }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    @Suppress("UNCHECKED_CAST")
    private fun <T> withBrowseId(item: T, id: String): T = when (item) {
        is YtAlbum -> item.copy(id = id) as T
        is YtArtist -> item.copy(id = id) as T
        else -> item
    }

    private fun runTool(bin: File, vararg args: String): String {
        val p = ProcessBuilder(listOf(bin.path) + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val text = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw IOException("yt-dlp terminó con error ${p.exitValue()}")
            return text
        } finally {
            p.destroy()
        }
    }

    private fun flatTracks(o: JSONObject, album: String, numbered: Boolean): List<YtTrack> {
        val entries = o.optJSONArray("entries") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { i ->
            val e = entries.optJSONObject(i) ?: return@mapNotNull null
            val id = e.optString("id")
            val title = e.optString("title")
            if (!idPattern.matches(id) || title.isBlank()) return@mapNotNull null
            YtTrack(
                id, title, e.optString("uploader").ifBlank { e.optString("channel") }.removeSuffix(" - Topic"), album,
                e.optInt("duration"), "https://i.ytimg.com/vi/$id/hqdefault.jpg", "", if (numbered) i + 1 else 0
            )
        }
    }

    internal fun parseAlbum(json: String): YtAlbum? = runCatching {
        val o = JSONObject(json)
        val full = o.optString("title")
        val prefix = Regex("^(Album|EP|Single) - ").find(full)
        val title = if (prefix != null) full.substring(prefix.range.last + 1) else full
        val tracks = flatTracks(o, title, numbered = true)
        if (title.isBlank() || tracks.isEmpty()) return null
        val kind = when (prefix?.groupValues?.get(1)) { "Album" -> "Álbum"; "EP" -> "EP"; "Single" -> "Sencillo"; else -> "" }
        YtAlbum(o.optString("id"), title, tracks.first().artist, kind, tracks.first().thumbnail, tracks)
    }.getOrNull()

    internal fun parseArtist(json: String): YtArtist? = runCatching {
        val o = JSONObject(json)
        val songs = flatTracks(o, "", numbered = false)
        val name = o.optString("uploader").ifBlank { o.optString("channel") }.removeSuffix(" - Topic")
        if (name.isBlank()) return null
        YtArtist(o.optString("id"), name, songs)
    }.getOrNull()

    /** Los datos de una lista de álbum o artista son mínimos: antes de descargar se piden los completos. */
    private suspend fun withDetails(track: YtTrack): YtTrack {
        if (track.year.isNotBlank() || track.fixedTags) return track
        val bin = ensureTool()
        val full = runCatching {
            parseTrack(runTool(bin, "--dump-json", "--skip-download", "--ignore-no-formats-error", "--no-warnings", "--no-playlist", "https://music.youtube.com/watch?v=${track.id}"))
        }.getOrNull() ?: return track
        return full.copy(album = track.album.ifBlank { full.album }, trackNo = track.trackNo)
    }

    internal fun parseTrack(json: String): YtTrack? = runCatching {
        val o = JSONObject(json)
        val id = o.optString("id")
        val rawTitle = o.optString("track").ifBlank { o.optString("title") }
        if (!idPattern.matches(id) || rawTitle.isBlank()) return null
        val isMusic = o.optString("track").isNotBlank()
        val (title, videoArtist) = if (isMusic) rawTitle to "" else titleAndArtistFromVideo(rawTitle, o.optString("channel"))
        YtTrack(
            id = id,
            title = title,
            artist = o.optString("artist").ifBlank { videoArtist }.ifBlank { o.optString("channel").removeSuffix(" - Topic") },
            album = o.optString("album"),
            durationSec = o.optInt("duration"),
            thumbnail = o.optString("thumbnail"),
            year = o.opt("release_year")?.toString()?.takeIf { it != "null" }.orEmpty().ifBlank { o.optString("upload_date").take(4) }
        )
    }.getOrNull()

    private var worker = false

    @Synchronized
    fun enqueue(track: YtTrack, phone: RemoteClient) {
        val current = _jobs.value.firstOrNull { it.track.id == track.id }
        if (current != null && current.stage != YtStage.Failed) return
        _jobs.update { list -> list.filter { it.track.id != track.id } + YtJob(track, YtStage.Queued) }
        queue.trySend(track.id)
        if (!worker) {
            worker = true
            scope.launch { for (id in queue) process(id, phone) }
        }
    }

    fun clearFinished() = _jobs.update { list -> list.filter { it.stage != YtStage.Done && it.stage != YtStage.Failed } }

    private fun set(id: String, stage: YtStage, percent: Int = 0, message: String = "") =
        _jobs.update { list -> list.map { if (it.track.id == id) it.copy(stage = stage, percent = percent, message = message) else it } }

    private suspend fun process(id: String, phone: RemoteClient) {
        val queued = _jobs.value.firstOrNull { it.track.id == id }?.track ?: return
        var file: File? = null
        try {
            workDir.mkdirs()
            set(id, YtStage.Downloading)
            val track = withDetails(queued)
            val audio = download(track, { set(id, YtStage.Installing, it) }) { set(id, YtStage.Downloading, it) }
            set(id, YtStage.Tagging)
            file = tagged(track, audio)
            audio.delete()
            while (true) {
                val blocker = phone.uploadBlocker() ?: break
                set(id, YtStage.WaitingPhone, message = blocker)
                delay(3000)
            }
            val size = file.length().coerceAtLeast(1)
            phone.uploadSong(file, track.artist, track.album) { sent -> set(id, YtStage.Sending, (sent * 100 / size).toInt()) }
            set(id, YtStage.Done)
        } catch (e: Exception) {
            set(id, YtStage.Failed, message = e.message ?: "Error desconocido")
        } finally {
            file?.delete()
            File(workDir, "$id.m4a").delete()
        }
    }

    /** Subidas de SoundCloud para una búsqueda, con su duración y su enlace. */
    suspend fun searchSoundCloud(query: String, limit: Int = 10): List<ScCandidate> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        parseSoundCloud(runTool(ensureTool(), "--flat-playlist", "-J", "--no-warnings", "scsearch$limit:$query"))
    }

    internal fun parseSoundCloud(json: String): List<ScCandidate> {
        val entries = JSONObject(json).optJSONArray("entries") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { i ->
            val e = entries.optJSONObject(i) ?: return@mapNotNull null
            val url = e.optString("webpage_url").ifBlank { e.optString("url") }
            val id = e.optString("id")
            if (!id.all(Char::isLetterOrDigit) || id.isBlank() || !url.startsWith("https://")) return@mapNotNull null
            val thumbs = e.optJSONArray("thumbnails")
            val art = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
            ScCandidate("sc_$id", e.optString("title"), e.optString("uploader"), e.optDouble("duration", 0.0).toInt(), url, art)
        }
    }

    private val notTheOriginal = Regex("nightcore|slowed|sped ?up|reverb|cover|remix|karaoke|instrumental|\\binst\\b|\\b8d\\b|piano|acoustic", RegexOption.IGNORE_CASE)

    /**
     * La subida que mejor parece ser esta pista, o null si no hay ninguna de fiar: mismo título (o el artista y una
     * duración casi exacta, para títulos en otro idioma), duración ±5 s si se conoce, y sin versiones alteradas.
     */
    internal fun pickCandidate(title: String, artist: String, lengthSec: Int, candidates: List<ScCandidate>): ScCandidate? {
        fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
        val wanted = norm(title)
        val who = norm(artist)
        val usable = candidates.filter { c ->
            (notTheOriginal.containsMatchIn(title) || !notTheOriginal.containsMatchIn(c.title)) &&
                (lengthSec <= 0 || kotlin.math.abs(c.durationSec - lengthSec) <= 5)
        }
        fun text(c: ScCandidate) = norm(c.title + " " + c.uploader)
        val named = usable.filter { wanted.isNotEmpty() && wanted in norm(c = it) }
        val byArtist = named.filter { who in text(it) }
        val best = byArtist.ifEmpty { named }
        if (best.isNotEmpty()) return best.minByOrNull { kotlin.math.abs(it.durationSec - lengthSec) }
        if (lengthSec <= 0) return null
        return usable.filter { who.isNotEmpty() && who in text(it) && kotlin.math.abs(it.durationSec - lengthSec) <= 2 }
            .minByOrNull { kotlin.math.abs(it.durationSec - lengthSec) }
    }

    private fun norm(c: ScCandidate) = java.text.Normalizer.normalize(c.title, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    /** Pide AAC, el formato que el teléfono etiqueta y reproduce sin recodificar. */
    internal suspend fun download(track: YtTrack, onInstalling: (Int) -> Unit = {}, onPercent: (Int) -> Unit): File {
        val bin = ensureTool()
        ensureFfmpeg(onInstalling)
        val out = File(workDir.apply { mkdirs() }, "${track.id}.m4a")
        val p = ProcessBuilder(
            bin.path, *(if (ffmpeg == "ffmpeg") emptyArray() else arrayOf("--ffmpeg-location", File(ffmpeg).parent)), "-f", "bestaudio[ext=m4a]/bestaudio", "-x", "--audio-format", "m4a", "--no-playlist", "--no-warnings",
            "--newline", "--progress-template", "download:%(progress._percent_str)s", "--force-overwrites",
            "-o", File(workDir, "${track.id}.%(ext)s").path, track.url.ifBlank { "https://music.youtube.com/watch?v=${track.id}" }
        ).redirectErrorStream(true).start()
        var last = ""
        p.inputStream.bufferedReader().forEachLine { line ->
            val pct = line.trim().removeSuffix("%").toDoubleOrNull()
            if (pct != null) onPercent(pct.toInt()) else if (line.isNotBlank()) last = line
        }
        if (p.waitFor() != 0 || !out.exists()) throw IOException(last.removePrefix("ERROR: ").ifBlank { "yt-dlp no pudo descargarla" })
        return out
    }

    /** Copia el audio tal cual con etiquetas y carátula. Sin carátula disponible, la canción se guarda igual. */
    internal fun tagged(track: YtTrack, audio: File): File {
        val dz = if (track.fixedTags) null else deezerMatch(track)
        val album = dz?.optJSONObject("album")
        val coverUrl = album?.optString("cover_xl")?.takeIf { it.isNotBlank() } ?: track.thumbnail
        val cover = File(workDir, "${track.id}.jpg")
        val hasCover = listOf(coverUrl, track.altCover).filter { it.isNotBlank() }.any { runCatching { fetchSquareCover(it, cover) }.getOrDefault(false) }
        val name = safeName("${track.artist} - ${track.title}") + ".m4a"
        val out = File(workDir, name)
        val args = mutableListOf(ffmpeg, "-y", "-v", "error", "-i", audio.path)
        if (hasCover) args += listOf("-i", cover.path, "-map", "0:a", "-map", "1:v", "-disposition:v:0", "attached_pic")
        args += listOf("-c", "copy", "-map_metadata", "-1", "-metadata", "title=${track.title}", "-metadata", "artist=${track.artist}")
        val albumName = track.album.ifBlank { album?.optString("title").orEmpty() }
        if (albumName.isNotBlank()) args += listOf("-metadata", "album=$albumName")
        if (track.year.isNotBlank()) args += listOf("-metadata", "date=${track.year}")
        (track.trackNo.takeIf { it > 0 } ?: dz?.optInt("track_position")?.takeIf { it > 0 })?.let { args += listOf("-metadata", "track=$it") }
        args += out.path
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        cover.delete()
        if (p.waitFor() != 0 || !out.exists()) throw IOException("No se pudieron escribir las etiquetas: ${log.take(200)}")
        return out
    }

    private fun fetchSquareCover(url: String, dest: File): Boolean {
        val raw = File(dest.path + ".src")
        try {
            get(url).use { resp -> raw.outputStream().use { resp.body!!.byteStream().copyTo(it) } }
            // Las miniaturas de YouTube suelen ser 16:9 con la portada cuadrada en el centro
            val p = ProcessBuilder(
                ffmpeg, "-y", "-v", "error", "-i", raw.path, "-vf", "crop='min(iw,ih)':'min(iw,ih)'", "-q:v", "2", dest.path
            ).redirectErrorStream(true).start()
            p.inputStream.readAllBytes()
            return p.waitFor() == 0 && dest.length() > 0
        } finally {
            raw.delete()
        }
    }

    /** La misma canción en Deezer (mismo título y artista, ±5 s), para una carátula mejor y el número de pista. */
    private fun deezerMatch(track: YtTrack): JSONObject? = runCatching {
        val mainArtist = track.artist.substringBefore(",").trim()
        // La búsqueda estricta falla con signos ("Feel Good Inc."): se repite como texto libre y se filtra aquí
        val match = listOf("artist:\"$mainArtist\" track:\"${track.title}\"", "$mainArtist ${track.title}").firstNotNullOfOrNull { q ->
            val url = "https://api.deezer.com/search".toHttpUrl().newBuilder().addQueryParameter("q", q).addQueryParameter("limit", "10").build()
            val data = get(url.toString()).use { JSONObject(it.body!!.string()).optJSONArray("data") } ?: return@firstNotNullOfOrNull null
            (0 until data.length()).map { data.getJSONObject(it) }.firstOrNull { o ->
                sameText(o.optString("title"), track.title) &&
                    sameText(o.optJSONObject("artist")?.optString("name").orEmpty(), mainArtist) &&
                    kotlin.math.abs(o.optInt("duration") - track.durationSec) <= 5
            }
        } ?: return null
        // track_position solo viene en el detalle de la pista
        runCatching { get("https://api.deezer.com/track/${match.optLong("id")}").use { JSONObject(it.body!!.string()) } }.getOrDefault(match)
    }.getOrNull()

    private val featPart = Regex("\\s*[(\\[](feat|ft|with)\\b[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)

    internal fun sameText(a: String, b: String): Boolean {
        fun norm(s: String) = java.text.Normalizer.normalize(s.replace(featPart, ""), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
        return norm(a) == norm(b)
    }

    internal fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').take(150).ifBlank { "cancion" }
}
