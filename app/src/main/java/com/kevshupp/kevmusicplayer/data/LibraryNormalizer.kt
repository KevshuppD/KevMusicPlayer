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
import kotlinx.coroutines.isActive
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
    data class Proposal(val song: AudioFile, val changes: Map<Field, String>, val doubt: String? = null) {
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
        data class Review(val proposals: List<Proposal>, val notFound: Int, val unchanged: Int, val total: Int, val skipped: Int = 0,
            /** Sin coincidencia segura, para poder ver cuáles son. */
            val notFoundSongs: List<AudioFile> = emptyList(),
            /** La búsqueda falló (red, límite de Deezer): no se sabe si están, y se revisan en la próxima rápida. */
            val errorSongs: List<AudioFile> = emptyList(),
            /** Las que pediste no volver a revisar. */
            val ignoredSongs: List<AudioFile> = emptyList()) : State
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

    // ---------- Memoria de lo ya revisado (normalización rápida) ----------

    /**
     * Por canción (id): huella de sus etiquetas cuando se revisó, resultado y fecha. La rápida se salta las que
     * conservan la misma huella: correctas o ya corregidas. Las que Deezer no tenía se vuelven a probar a los
     * [NOT_FOUND_RETRY_MS]. Si cambias las etiquetas a mano (o el escaneo trae otras), la huella cambia y se revisa.
     */
    private const val NOT_FOUND_RETRY_MS = 30L * 24 * 60 * 60 * 1000
    private const val STATUS_OK = "ok"
    private const val STATUS_NOT_FOUND = "nf"
    private val stateLock = Any()

    private fun stateFile(context: Context) = File(context.filesDir, "normalize_state.json")

    private fun fingerprint(s: AudioFile) = listOf(s.title, s.artist, s.album, s.genre, s.year, s.track.toString()).joinToString("\u0001").hashCode().toString(16)

    private fun loadState(context: Context): MutableMap<Long, Triple<String, String, Long>> = synchronized(stateLock) {
        val map = HashMap<Long, Triple<String, String, Long>>()
        runCatching {
            val j = JSONObject(stateFile(context).readText())
            j.keys().forEach { k ->
                val parts = j.getString(k).split('|')
                if (parts.size == 3) map[k.toLong()] = Triple(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L)
            }
        }
        map
    }

    private fun saveMarks(context: Context, marks: Map<Long, Pair<AudioFile, String>>) {
        if (marks.isEmpty()) return
        synchronized(stateLock) {
            val map = loadState(context)
            val now = System.currentTimeMillis()
            marks.forEach { (id, v) -> map[id] = Triple(fingerprint(v.first), v.second, now) }
            val j = JSONObject()
            map.forEach { (id, t) -> j.put(id.toString(), "${t.first}|${t.second}|${t.third}") }
            val file = stateFile(context)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(j.toString())
            if (!tmp.renameTo(file)) { file.writeText(j.toString()); tmp.delete() }
        }
    }

    /** Canciones que la normalización rápida revisaría (las demás ya están revisadas y sin cambios). */
    fun pendingForQuick(context: Context, songs: List<AudioFile>): List<AudioFile> {
        val state = loadState(context)
        val now = System.currentTimeMillis()
        return songs.filter { s ->
            val mark = state[s.id] ?: return@filter true
            mark.first != fingerprint(s) || (mark.second == STATUS_NOT_FOUND && now - mark.third > NOT_FOUND_RETRY_MS)
        }
    }

    /** [full]: revisa todo; si no, solo lo pendiente ([pendingForQuick]). */
    fun scan(context: Context, songs: List<AudioFile>, full: Boolean) {
        job?.cancel()
        val app = context.applicationContext
        val ignoredKeys = ignoredKeys(app)
        val (ignoredSongs, all) = songs.filter { it.id > 0 && it.title.isNotBlank() }.partition { ignoreKey(it) in ignoredKeys }
        val targets = if (full) all else pendingForQuick(app, all)
        val skipped = all.size - targets.size
        _state.value = State.Scanning(0, targets.size, 0)
        job = scope.launch {
            val albums = HashMap<Long, JSONObject?>()
            val albumLock = Mutex()
            val gate = Semaphore(3)
            var done = 0
            var found = 0
            var notFound = 0
            val errorIds = java.util.Collections.synchronizedSet(HashSet<Long>())
            val counter = Mutex()
            val results = targets.map { song ->
                async {
                    gate.withPermit {
                        ensureActive()
                        val proposal = try {
                            propose(song, albums, albumLock)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Un error no es "Deezer no la tiene": sin marca, para que no espere 30 días
                            errorIds.add(song.id)
                            null
                        }
                        counter.withLock {
                            done++
                            when {
                                song.id in errorIds -> Unit
                                proposal == null -> notFound++
                                else -> found++
                            }
                            _state.value = State.Scanning(done, targets.size, found)
                        }
                        proposal
                    }
                }
            }.awaitAll()
            val deezerFound = results.filterNotNull().map { it.song.id }.toHashSet()
            val backup = musicBrainzBackup(targets.filter { it.id !in deezerFound && it.id !in errorIds }.take(MB_BACKUP_MAX)) { _, _ -> }
            notFound -= backup.size
            val matched = unifyAlbumNames(all, results.filterNotNull() + backup)
            val withChanges = matched.filter { it.changes.isNotEmpty() }
            // Correctas y no encontradas quedan recordadas; las que tienen cambios, cuando se apliquen
            val marks = HashMap<Long, Pair<AudioFile, String>>()
            matched.filter { it.changes.isEmpty() }.forEach { marks[it.song.id] = it.song to STATUS_OK }
            val matchedIds = matched.map { it.song.id }.toHashSet()
            val notFoundSongs = targets.filter { it.id !in matchedIds && it.id !in errorIds }
            val errorSongs = targets.filter { it.id in errorIds }
            notFoundSongs.forEach { marks[it.id] = it to STATUS_NOT_FOUND }
            if (isActive) runCatching { saveMarks(app, marks) }
            _state.value = State.Review(withChanges, notFound, matched.size - withChanges.size, targets.size, skipped, notFoundSongs, errorSongs, ignoredSongs)
        }
    }

    /** Clave del álbum sin mayúsculas ni tildes: "WINGS" y "wings" son el mismo. */
    private val albumMarks = Regex("\\p{Mn}+")
    private val albumKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun albumKey(album: String): String = albumKeyCache[album]
        ?: java.text.Normalizer.normalize(album.trim(), java.text.Normalizer.Form.NFD).replace(albumMarks, "").lowercase()
            .also { if (albumKeyCache.size < 20_000) albumKeyCache[album] = it }

    /**
     * Un mismo disco escrito de varias formas (3 canciones "WINGS" y 12 "wings") queda con un solo nombre: el que
     * propone Deezer para alguna de sus canciones o, si no, el más repetido. Se añade a las propuestas (o crea una)
     * de las canciones que lo tienen escrito distinto, aunque Deezer no las haya encontrado.
     */
    private fun unifyAlbumNames(all: List<AudioFile>, proposals: List<Proposal>): List<Proposal> {
        val byId = proposals.associateBy { it.song.id }.toMutableMap()
        fun finalAlbum(s: AudioFile) = byId[s.id]?.changes?.get(Field.Album) ?: s.album
        all.filter { !isUnknown(it.album) }.groupBy { albumKey(it.album) }.values.forEach { group ->
            val names = group.map { finalAlbum(it) }
            if (names.distinct().size < 2) return@forEach
            val canonical = group.mapNotNull { byId[it.id]?.changes?.get(Field.Album) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                ?: names.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
            group.filter { finalAlbum(it) != canonical }.forEach { s ->
                val p = byId[s.id] ?: Proposal(s, emptyMap())
                val changes = LinkedHashMap(p.changes).apply { put(Field.Album, canonical) }
                if (s.album == canonical) changes.remove(Field.Album)
                byId[s.id] = p.copy(changes = changes)
            }
        }
        return byId.values.toList()
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

    // ---------- Ignoradas ----------

    private const val PREF_IGNORED = "normalize_ignored"
    private const val MB_BACKUP_MAX = 100

    /** Por artista y título, no por id: el `_id` de MediaStore cambia al reindexar. Viaja en la copia con los ajustes. */
    private fun ignoreKey(song: AudioFile) = LibraryMatcher.normalize(song.artist) + "|" + LibraryMatcher.normalize(song.title)

    private fun ignoredKeys(context: Context): Set<String> =
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).getStringSet(PREF_IGNORED, emptySet()).orEmpty()

    /** Normalizar deja de revisar esta canción; se puede deshacer con [setIgnored] en `false`. */
    fun setIgnored(context: Context, song: AudioFile, ignored: Boolean) {
        val keys = ignoredKeys(context).toMutableSet()
        if (ignored) keys += ignoreKey(song) else keys -= ignoreKey(song)
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).edit().putStringSet(PREF_IGNORED, keys).apply()
        val review = _state.value as? State.Review ?: return
        fun without(list: List<AudioFile>) = list.filter { it.id != song.id }
        _state.value = review.copy(
            proposals = review.proposals.filter { it.song.id != song.id },
            notFoundSongs = without(review.notFoundSongs),
            errorSongs = without(review.errorSongs),
            ignoredSongs = if (ignored) without(review.ignoredSongs) + song else without(review.ignoredSongs),
            notFound = review.notFound - review.notFoundSongs.count { it.id == song.id }
        )
    }

    /** Mismo artista, título parecido y ±3 s; la más cercana, primero la del mismo disco. */
    internal fun pickRecording(song: AudioFile, recordings: List<MbRecording>): MbRecording? {
        val artist = LibraryMatcher.normalize(song.artist)
        val durationSec = (song.duration / 1000).toInt()
        val album = LibraryMatcher.normalizeTitle(song.album)
        return recordings.filter {
            LibraryMatcher.normalize(it.artist) == artist && it.lengthSec > 0 && kotlin.math.abs(it.lengthSec - durationSec) <= 3 &&
                SongRepair.isSimilarTitle(song.title, it.title)
        }.minByOrNull { kotlin.math.abs(it.lengthSec - durationSec) + if (LibraryMatcher.normalizeTitle(it.album) == album) -10 else 0 }
    }

    /**
     * Lo que Deezer no tiene, una consulta por segundo. MusicBrainz guarda ediciones sueltas y títulos originales,
     * así que solo completa lo que falta: nunca cambia título, artista ni un álbum ya puesto. Un error no cuenta.
     */
    private suspend fun musicBrainzBackup(songs: List<AudioFile>, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Proposal> {
        val out = ArrayList<Proposal>()
        songs.forEachIndexed { i, song ->
            coroutineContext.ensureActive()
            onProgress(i, songs.size)
            val recording = runCatching {
                pickRecording(song, MetadataSearch.musicBrainzRecordings(LibraryMatcher.fixMojibake(song.title), LibraryMatcher.fixMojibake(song.artist)))
            }.getOrNull()
            if (recording != null) {
                val changes = LinkedHashMap<Field, String>()
                if (isUnknown(song.album) && recording.album.isNotBlank()) changes[Field.Album] = recording.album
                if (song.year.isBlank() && recording.year.length == 4) changes[Field.Year] = recording.year
                out += Proposal(song, changes, "Desde MusicBrainz")
            }
            delay(1100)
        }
        return out
    }

    /** Propuesta para la canción, o null si Deezer no la tiene (o no con seguridad). Sin cambios = mapa vacío. */
    private suspend fun propose(song: AudioFile, albums: HashMap<Long, JSONObject?>, albumLock: Mutex): Proposal? {
        val found = deezer { SongRepair.findMatchForTags(song) } ?: return null
        val match = found.track
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
        val localTitle = LibraryMatcher.fixMojibake(song.title)
        val localNorm = LibraryMatcher.normalize(localTitle)
        var doubt = found.doubt
        if (SongRepair.isLatin(localTitle) || LibraryMatcher.normalizeTitle(dzTitle) == LibraryMatcher.normalizeTitle(localTitle)) {
            offer(Field.Title, listOf(dzTitle, dzShort).firstOrNull { it.isNotBlank() && LibraryMatcher.normalize(it) == localNorm } ?: dzTitle)
        } else {
            // Hallada por duración (피 땀 눈물 = Blood Sweat & Tears): el título de Deezer se propone, pero como duda
            // (sin seleccionar), porque hay quien prefiere el original
            offer(Field.Title, dzTitle)
            if (Field.Title in changes) doubt = doubt ?: "Título en otro idioma"
        }

        // Artista: si el local ya trae colaboradores ("A, B") y el de Deezer es solo el principal, se conserva el local
        val dzArtist = track.optJSONObject("artist")?.optString("name").orEmpty().ifBlank { match.artist }
        val contributors = track.optJSONArray("contributors")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } } }.orEmpty().distinct()
        val joined = contributors.joinToString(", ")
        KnownArtists.add(contributors)
        val localArtist = LibraryMatcher.normalize(song.artist)
        val artist = when {
            isUnknown(song.artist) -> dzArtist
            contributors.size > 1 && localArtist == LibraryMatcher.normalize(joined) -> joined
            // Solo el principal y Deezer lista invitados: se añaden (MY BLOOD de Polima WestCoast con Pablo Chill-E
            // no salía en el perfil de Pablo Chill-E)
            localArtist == LibraryMatcher.normalize(dzArtist) -> if (contributors.size > 1) withGuests(dzArtist, contributors) else dzArtist
            localArtist.contains(LibraryMatcher.normalize(dzArtist)) -> null
            // La etiqueta solo nombra a un invitado: se propone el principal con los demás ("Milo j, Silvio Rodríguez")
            contributors.any { LibraryMatcher.normalize(it) == localArtist } -> joined
            else -> dzArtist
        }
        offer(Field.Artist, artist)

        // Álbum, año y pista solo si es el mismo lanzamiento (o el local no tiene álbum): la misma canción en un
        // recopilatorio o como sencillo no debe recibir el número de pista de otro disco
        val dzAlbum = album?.optString("title").orEmpty().ifBlank { track.optJSONObject("album")?.optString("title").orEmpty() }
        val localAlbumNorm = LibraryMatcher.normalizeTitle(song.album)
        val dzAlbumNorm = LibraryMatcher.normalizeTitle(dzAlbum)
        // Otra edición del mismo disco ("emails i can't send" → "emails i can't send fwd:", "… (Deluxe)"): Deezer solo
        // la tiene ahí, así que se propone, como duda
        val otherEdition = dzAlbum.isNotBlank() && !isUnknown(song.album) && localAlbumNorm.length >= 4 &&
            dzAlbumNorm != localAlbumNorm && dzAlbumNorm.startsWith(localAlbumNorm)
        val sameRelease = dzAlbum.isNotBlank() && (isUnknown(song.album) || localAlbumNorm == dzAlbumNorm || otherEdition)
        if (otherEdition) doubt = doubt ?: "Otra edición del disco"
        if (sameRelease) {
            offer(Field.Album, dzAlbum)
            offer(Field.Year, (track.optString("release_date").ifBlank { album?.optString("release_date").orEmpty() }).take(4).takeIf { it.length == 4 && it != "0000" })
            offer(Field.Track, track.optInt("track_position").takeIf { it > 0 }?.toString())
        }

        // Género: siempre el de Deezer (ya en el idioma del teléfono), porque a menudo viene mal o en otro idioma
        // ("Aziatische Muziek"). Se revisa antes de aplicar y se puede desmarcar
        offer(Field.Genre, album?.optJSONObject("genres")?.optJSONArray("data")?.optJSONObject(0)?.optString("name"))
        return Proposal(song, changes, doubt)
    }

    /**
     * Escribe las propuestas elegidas (solo los campos de [fields]) en el archivo, MediaStore y Room.
     * [onApplied] recibe las canciones ya actualizadas, en tandas de [MEMORY_BATCH], para reflejarlas en memoria:
     * cambiar la biblioteca en memoria canción a canción relanzaba cálculos sobre toda ella cientos de veces
     * seguidas y la app se quedó sin memoria a mitad (24/10/2026).
     */
    fun apply(context: Context, proposals: List<Proposal>, fields: Set<Field>, onApplied: suspend (List<AudioFile>) -> Unit, onFinished: suspend () -> Unit) {
        job?.cancel()
        val app = context.applicationContext
        val list = proposals.map { p -> p.copy(changes = p.changes.filterKeys { it in fields }) }.filter { it.changes.isNotEmpty() }
        _state.value = State.Applying(0, list.size)
        // Antes de tocar ningún archivo: claves nuevas de la nube y aviso de que falta volver a unir con ella,
        // por si la app se cierra a mitad (ver RealtimePlaylistSync.recordKeyChanges)
        com.kevshupp.kevmusicplayer.data.cloud.RealtimePlaylistSync.recordKeyChanges(app, list.associate { p ->
            val s = p.song
            val renamed = s.copy(title = p.changes[Field.Title] ?: s.title, artist = p.changes[Field.Artist] ?: s.artist)
            com.kevshupp.kevmusicplayer.data.cloud.RealtimePlaylistSync.songKey(s) to com.kevshupp.kevmusicplayer.data.cloud.RealtimePlaylistSync.songKey(renamed)
        })
        setMergePending(app, true)
        job = scope.launch {
            val dao = AppDatabase.getDatabase(app).audioDao()
            val failed = ArrayList<Pair<AudioFile, String>>()
            val batch = ArrayList<AudioFile>()
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
                    batch += s.copy(title = title, artist = artist, album = album, genre = genre, year = year, track = track, dateModified = modified)
                    if (batch.size >= MEMORY_BATCH) { flush(app, batch, onApplied) }
                    updated++
                } else {
                    failed += s to error
                    TelemetryLogger.logError(app, "Normalize", "No se pudo normalizar ${s.id} (${s.artist} - ${s.title}): $error")
                }
                _state.value = State.Applying(i + 1, list.size)
            }
            if (batch.isNotEmpty()) flush(app, batch, onApplied)
            onFinished()
            _state.value = State.Done(updated, failed)
        }
    }

    private const val MEMORY_BATCH = 50

    /** Refleja la tanda en memoria y la marca como revisada (con sus etiquetas nuevas). */
    private suspend fun flush(context: Context, batch: ArrayList<AudioFile>, onApplied: suspend (List<AudioFile>) -> Unit) {
        val done = batch.toList()
        batch.clear()
        onApplied(done)
        runCatching { saveMarks(context, done.associate { it.id to (it to STATUS_OK) }) }
    }
    private const val KEY_MERGE_PENDING = "normalize_merge_pending"

    /** Quedó una normalización sin volver a unir con la nube (la app se cerró a mitad). */
    fun isMergePending(context: Context) =
        context.getSharedPreferences("cloud_sync_state", Context.MODE_PRIVATE).getBoolean(KEY_MERGE_PENDING, false)

    fun setMergePending(context: Context, pending: Boolean) {
        context.getSharedPreferences("cloud_sync_state", Context.MODE_PRIVATE).edit().putBoolean(KEY_MERGE_PENDING, pending).commit()
    }

    /** null si se escribió; si no, el motivo. */
    /**
     * Tras una descarga del bot, deja las etiquetas oficiales de Deezer en el archivo: el bot a veces las manda
     * incompletas o distintas. Solo escribe si algo cambia; si falla, la canción se queda como llegó.
     */
    suspend fun tagDownloaded(context: Context, uri: Uri, file: File, track: com.kevshupp.kevmusicplayer.data.telegram.DeezerTrack) {
        val id = uri.lastPathSegment?.toLongOrNull() ?: return
        val r = android.media.MediaMetadataRetriever()
        fun tag(key: Int) = r.extractMetadata(key).orEmpty().trim()
        val current = try {
            r.setDataSource(file.absolutePath)
            listOf(
                tag(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE),
                tag(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST),
                tag(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM),
                tag(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE),
                tag(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR),
                tag(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/')
            )
        } finally {
            runCatching { r.release() }
        }
        val details = deezer { DeezerTrackSearch.trackDetails(track.id) }
        val albumId = details.optJSONObject("album")?.optLong("id") ?: 0L
        val album = if (albumId > 0) runCatching { deezer { DeezerTrackSearch.albumDetails(albumId) } }.getOrNull() else null
        val title = details.optString("title").ifBlank { track.title }
        val main = details.optJSONObject("artist")?.optString("name").orEmpty().ifBlank { track.artist }
        val full = withGuests(main, contributorsOf(details))
        KnownArtists.add(listOf(main) + contributorsOf(details))
        // Principal con invitados; si el archivo ya los nombra a todos ("A feat. B") se conserva su forma
        val currentNorm = LibraryMatcher.normalize(current[1])
        val artist = current[1].takeIf { c ->
            c.isNotBlank() && contributorsOf(details).ifEmpty { listOf(main) }.all { currentNorm.contains(LibraryMatcher.normalize(it)) }
        } ?: full
        val albumName = details.optJSONObject("album")?.optString("title").orEmpty().ifBlank { track.album }
        // El género de Deezer manda: el que trae el archivo a veces está mal o en otro idioma
        val genre = album?.optJSONObject("genres")?.optJSONArray("data")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
            ?: current[3]
        val year = details.optString("release_date").take(4).takeIf { it.length == 4 } ?: current[4]
        val position = details.optInt("track_position").takeIf { it > 0 } ?: current[5].toIntOrNull() ?: 0
        val wanted = listOf(title, artist, albumName, genre, year, if (position > 0) position.toString() else "")
        if (wanted == current) return
        val stub = AudioFile(id, current[0].ifBlank { title }, current[1].ifBlank { artist }, current[2], current[3], 0, uri.toString())
        writeTags(context, stub, title, artist, albumName, genre, year, position)?.let { throw java.io.IOException(it) }
    }

    private fun contributorsOf(details: org.json.JSONObject): List<String> =
        details.optJSONArray("contributors")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } } }.orEmpty().distinct()

    /** "Principal, Invitado1, Invitado2": el principal siempre primero. */
    internal fun withGuests(main: String, contributors: List<String>): String =
        (listOf(main) + contributors).distinctBy { LibraryMatcher.normalize(it) }.filter { it.isNotBlank() }.joinToString(", ")

    /**
     * Etiqueta un audio que llegó de fuera (Soulseek, PC) con los datos oficiales de Deezer, si se encuentra
     * con la coincidencia estricta de Normalizar. Sin coincidencia o con error queda como llegó.
     */
    suspend fun tagImported(context: Context, uri: Uri, file: File, title: String, artist: String, album: String) {
        if (title.isBlank() || artist.isBlank()) return
        val r = android.media.MediaMetadataRetriever()
        val durationMs = try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L } finally { runCatching { r.release() } }
        val id = uri.lastPathSegment?.toLongOrNull() ?: return
        val match = SongRepair.findMatch(AudioFile(id, title, artist, album, duration = durationMs, uriString = uri.toString())) ?: return
        tagDownloaded(context, uri, file, match)
    }

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
