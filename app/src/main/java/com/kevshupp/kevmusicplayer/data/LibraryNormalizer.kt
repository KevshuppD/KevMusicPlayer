package com.kevshupp.kevmusicplayer.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.kevshupp.kevmusicplayer.data.telegram.DeezerTrackSearch
import com.kevshupp.kevmusicplayer.data.telegram.LibraryMatcher
import com.kevshupp.kevmusicplayer.data.telegram.SongRepair
import com.kevshupp.kevmusicplayer.playback.getPhysicalPath
import com.kevshupp.kevmusicplayer.playback.writeMetadataWithTempFile
import com.kevshupp.kevmusicplayer.playback.writeMp3TagsWithMp3Agic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jaudiotagger.tag.FieldKey
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * "Normalizar biblioteca": busca cada canción en Deezer (misma coincidencia estricta que [SongRepair.findMatch])
 * y propone título, artista, álbum, género, año y número de pista oficiales. Nada se escribe hasta que el
 * usuario revisa y aplica; la escritura usa las mismas rutas seguras que el editor de etiquetas.
 *
 * El estado vive aquí (no en el diálogo) para que cerrar la pantalla no pierda un análisis largo.
 */
object LibraryNormalizer {
    enum class Field(val label: String) { Title("Título"), Artist("Artista"), Album("Álbum"), Genre("Género"), Year("Año"), Track("Pista") }

    /** Cambios propuestos para una canción: campo → valor nuevo (solo los que difieren). */
    data class Proposal(val song: AudioFile, val changes: Map<Field, String>) {
        fun oldValue(field: Field): String = when (field) {
            Field.Title -> song.title
            Field.Artist -> song.artist
            Field.Album -> song.album
            Field.Genre -> song.genre
            Field.Year -> song.year
            Field.Track -> if (song.track > 0) song.track.toString() else ""
        }
    }

    sealed interface State {
        data object Idle : State
        data class Scanning(val done: Int, val total: Int, val found: Int) : State
        data class Review(val proposals: List<Proposal>, val notFound: Int, val unchanged: Int, val total: Int) : State
        data class Applying(val done: Int, val total: Int) : State
        data class Done(val updated: Int, val failed: List<Pair<AudioFile, String>>) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    private val UNKNOWN = setOf("", "unknown", "unknown genre", "unknown artist", "unknown album", "<unknown>", "desconocido", "other", "otro")
    fun isUnknown(value: String) = value.trim().lowercase() in UNKNOWN || value.trim().matches(Regex("\\(?\\d+\\)?"))

    fun reset() {
        job?.cancel()
        _state.value = State.Idle
    }

    fun scan(songs: List<AudioFile>) {
        job?.cancel()
        val targets = songs.filter { it.id > 0 && it.title.isNotBlank() }
        _state.value = State.Scanning(0, targets.size, 0)
        job = scope.launch {
            val albums = HashMap<Long, JSONObject?>()
            val albumLock = Mutex()
            val gate = Semaphore(3)
            var done = 0
            var found = 0
            var notFound = 0
            val counter = Mutex()
            val results = targets.map { song ->
                async {
                    gate.withPermit {
                        ensureActive()
                        val proposal = runCatching { propose(song, albums, albumLock) }.getOrNull()
                        counter.withLock {
                            done++
                            when {
                                proposal == null -> notFound++
                                else -> found++
                            }
                            _state.value = State.Scanning(done, targets.size, found)
                        }
                        proposal
                    }
                }
            }.awaitAll()
            val matched = results.filterNotNull()
            val withChanges = matched.filter { it.changes.isNotEmpty() }
            _state.value = State.Review(withChanges, notFound, matched.size - withChanges.size, targets.size)
        }
    }

    /** Llamadas a Deezer con reintento si se pasa del límite de peticiones (50 cada 5 s). */
    private suspend fun <T> deezer(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: java.io.IOException) {
                if (attempt++ >= 3 || e.message?.contains("quota", ignoreCase = true) != true) throw e
                delay(5500L * attempt)
            }
        }
    }

    /** Propuesta para la canción, o null si Deezer no la tiene (o no con seguridad). Sin cambios = mapa vacío. */
    private suspend fun propose(song: AudioFile, albums: HashMap<Long, JSONObject?>, albumLock: Mutex): Proposal? {
        val match = deezer { SongRepair.findMatch(song) } ?: return null
        coroutineContext.ensureActive()
        val track = deezer { DeezerTrackSearch.trackDetails(match.id) }
        val albumId = track.optJSONObject("album")?.optLong("id") ?: 0L
        val album = if (albumId > 0) albumLock.withLock { albums[albumId] } ?: runCatching { deezer { DeezerTrackSearch.albumDetails(albumId) } }.getOrNull()
            .also { a -> albumLock.withLock { albums[albumId] = a } } else null

        val changes = LinkedHashMap<Field, String>()
        fun offer(field: Field, value: String?) {
            val v = value?.trim().orEmpty()
            if (v.isNotEmpty() && v != Proposal(song, emptyMap()).oldValue(field).trim()) changes[field] = v
        }

        // Título: el oficial de Deezer. Si una de sus variantes es la misma escrita distinto (tildes, mayúsculas),
        // esa; si no, el completo (con "(feat. …)" o la versión, como lo publica el sello)
        val dzTitle = track.optString("title").ifBlank { match.title }
        val dzShort = track.optString("title_short")
        val localNorm = LibraryMatcher.normalize(song.title)
        offer(Field.Title, listOf(dzTitle, dzShort).firstOrNull { it.isNotBlank() && LibraryMatcher.normalize(it) == localNorm } ?: dzTitle)

        // Artista: si el local ya trae colaboradores ("A, B") y el de Deezer es solo el principal, se conserva el local
        val dzArtist = track.optJSONObject("artist")?.optString("name").orEmpty().ifBlank { match.artist }
        val contributors = track.optJSONArray("contributors")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } } }.orEmpty().distinct()
        val joined = contributors.joinToString(", ")
        val localArtist = LibraryMatcher.normalize(song.artist)
        val artist = when {
            isUnknown(song.artist) -> dzArtist
            localArtist == LibraryMatcher.normalize(dzArtist) -> dzArtist
            contributors.size > 1 && localArtist == LibraryMatcher.normalize(joined) -> joined
            localArtist.contains(LibraryMatcher.normalize(dzArtist)) -> null
            else -> dzArtist
        }
        offer(Field.Artist, artist)

        // Álbum, año y pista solo si es el mismo lanzamiento (o el local no tiene álbum): la misma canción en un
        // recopilatorio o como sencillo no debe recibir el número de pista de otro disco
        val dzAlbum = album?.optString("title").orEmpty().ifBlank { track.optJSONObject("album")?.optString("title").orEmpty() }
        val sameRelease = dzAlbum.isNotBlank() &&
            (isUnknown(song.album) || LibraryMatcher.normalizeTitle(song.album) == LibraryMatcher.normalizeTitle(dzAlbum))
        if (sameRelease) {
            offer(Field.Album, dzAlbum)
            offer(Field.Year, (track.optString("release_date").ifBlank { album?.optString("release_date").orEmpty() }).take(4).takeIf { it.length == 4 && it != "0000" })
            offer(Field.Track, track.optInt("track_position").takeIf { it > 0 }?.toString())
        }

        // Género: es del álbum en Deezer. Se rellena el que falta; uno ya puesto (p. ej. "K-Pop") suele ser más fino que el de Deezer
        if (isUnknown(song.genre)) {
            val genre = album?.optJSONObject("genres")?.optJSONArray("data")?.optJSONObject(0)?.optString("name")
            offer(Field.Genre, genre)
        }
        return Proposal(song, changes)
    }

    /**
     * Escribe las propuestas elegidas (solo los campos de [fields]) en el archivo, MediaStore y Room.
     * [onApplied] recibe cada canción ya actualizada para reflejarla en memoria.
     */
    fun apply(context: Context, proposals: List<Proposal>, fields: Set<Field>, onApplied: suspend (AudioFile) -> Unit, onFinished: suspend () -> Unit) {
        job?.cancel()
        val app = context.applicationContext
        val list = proposals.map { p -> p.copy(changes = p.changes.filterKeys { it in fields }) }.filter { it.changes.isNotEmpty() }
        _state.value = State.Applying(0, list.size)
        job = scope.launch {
            val dao = AppDatabase.getDatabase(app).audioDao()
            val failed = ArrayList<Pair<AudioFile, String>>()
            var updated = 0
            list.forEachIndexed { i, p ->
                val s = p.song
                val c = p.changes
                val title = c[Field.Title] ?: s.title
                val artist = c[Field.Artist] ?: s.artist
                val album = c[Field.Album] ?: s.album
                val genre = c[Field.Genre] ?: s.genre
                val year = c[Field.Year] ?: s.year
                val track = c[Field.Track]?.toIntOrNull() ?: s.track
                val error = runCatching { writeTags(app, s, title, artist, album, genre, year, track) }.getOrElse { it.message ?: "Error al escribir" }
                if (error == null) {
                    val path = getPhysicalPath(app, s.id, s.uriString)
                    val modified = path?.let { File(it).lastModified() } ?: s.dateModified
                    dao.updateTagMetadata(s.id, title, artist, album, genre, year, track, modified)
                    onApplied(s.copy(title = title, artist = artist, album = album, genre = genre, year = year, track = track, dateModified = modified))
                    updated++
                } else {
                    failed += s to error
                    TelemetryLogger.logError(app, "Normalize", "No se pudo normalizar ${s.id} (${s.artist} - ${s.title}): $error")
                }
                _state.value = State.Applying(i + 1, list.size)
            }
            onFinished()
            _state.value = State.Done(updated, failed)
        }
    }

    /** null si se escribió; si no, el motivo. */
    private fun writeTags(context: Context, s: AudioFile, title: String, artist: String, album: String, genre: String, year: String, track: Int): String? {
        val path = getPhysicalPath(context, s.id, s.uriString) ?: return "No se encontró el archivo"
        val ok = if (path.endsWith(".mp3", ignoreCase = true)) {
            writeMp3TagsWithMp3Agic(context, s.id, s.uriString, path, title, artist, album, genre.takeUnless { isUnknown(it) }, null, year, track)
        } else {
            writeMetadataWithTempFile(context, s.id, s.uriString) { audioFile ->
                val tag = audioFile.getTagOrCreateAndSetDefault()
                tag.setField(FieldKey.TITLE, title)
                tag.setField(FieldKey.ARTIST, artist)
                tag.setField(FieldKey.ALBUM, album)
                if (!isUnknown(genre)) tag.setField(FieldKey.GENRE, genre)
                if (year.isNotBlank()) runCatching { tag.setField(FieldKey.YEAR, year) }
                if (track > 0) runCatching { tag.setField(FieldKey.TRACK, track.toString()) }
                audioFile.tag = tag
            }
        }
        if (!ok) return "No se pudieron escribir las etiquetas (archivo protegido o el audio no coincidió)"
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, album)
                if (!isUnknown(genre)) put(MediaStore.Audio.Media.GENRE, genre)
            }
            context.contentResolver.update(Uri.parse(s.uriString), values, null, null)
        }
        android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        return null
    }
}
