package com.kevshupp.kevmusicplayer.data.telegram

import android.content.Context
import android.net.Uri
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import com.kevshupp.kevmusicplayer.playback.copyAudioVerified
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * Modo bajo demanda: canciones de Deezer que se añaden a la cola sin estar en el dispositivo.
 *
 * Una canción remota es un [AudioFile] con id negativo (`-idDeezer`, nunca coincide con un `_id` de MediaStore)
 * y uri `kmpremote://deezer/<id>`. Así la cola, su guardado y las estadísticas la aceptan sin cambios.
 * El reproductor resuelve esa uri con [ensureFile], que pide el audio al bot de Telegram; mientras tanto
 * muestra "cargando". Lo descargado va a una caché con límite o a la biblioteca, según elija el usuario.
 */
object OnDemandTracks {
    const val KEY_ENABLED = "ondemand_enabled"
    /** "cache" (temporal, con límite) o "library" (se guarda en la carpeta de música). */
    const val KEY_STORAGE = "ondemand_storage"
    const val KEY_CACHE_MB = "ondemand_cache_mb"
    const val STORAGE_CACHE = "cache"
    const val STORAGE_LIBRARY = "library"
    const val DEFAULT_CACHE_MB = 1024
    const val SCHEME = "kmpremote"

    private const val PREFS = "ondemand_prefs"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val inFlight = HashMap<Long, CompletableDeferred<File>>()

    private val _statuses = MutableStateFlow<Map<Long, DownloadStatus>>(emptyMap())
    /** Estado por id de Deezer (positivo), para la búsqueda y la cola. */
    val statuses: StateFlow<Map<Long, DownloadStatus>> = _statuses.asStateFlow()

    fun isEnabled(context: Context) = settings(context).getBoolean(KEY_ENABLED, false)
    fun storageMode(context: Context) = settings(context).getString(KEY_STORAGE, STORAGE_CACHE) ?: STORAGE_CACHE

    fun isRemoteId(id: Long) = id < 0
    fun deezerIdOf(id: Long) = -id
    fun deezerIdOf(uri: Uri): Long? = if (uri.scheme == SCHEME) uri.lastPathSegment?.toLongOrNull() else null

    /** Guarda los datos de la canción (para restaurar la cola tras reiniciar) y devuelve su [AudioFile] remoto. */
    fun register(context: Context, track: DeezerTrack): AudioFile {
        val json = JSONObject()
            .put("id", track.id).put("title", track.title).put("artist", track.artist).put("album", track.album)
            .put("duration", track.durationSec).put("coverSmall", track.coverSmall).put("coverXl", track.coverXl)
            .put("link", track.link)
        prefs(context).edit().putString("track_${track.id}", json.toString()).apply()
        return toAudioFile(track)
    }

    fun track(context: Context, deezerId: Long): DeezerTrack? {
        val raw = prefs(context).getString("track_$deezerId", null) ?: return null
        return try {
            val j = JSONObject(raw)
            DeezerTrack(
                id = j.getLong("id"), title = j.optString("title"), artist = j.optString("artist"),
                album = j.optString("album"), durationSec = j.optInt("duration"), coverSmall = j.optString("coverSmall"),
                coverXl = j.optString("coverXl"), link = j.optString("link")
            )
        } catch (e: Exception) {
            null
        }
    }

    /** El [AudioFile] remoto de un id negativo de la cola guardada, o null si ya no se conoce. */
    fun audioFileFor(context: Context, id: Long): AudioFile? =
        if (isRemoteId(id)) track(context, deezerIdOf(id))?.let { toAudioFile(it) } else null

    fun toAudioFile(track: DeezerTrack) = AudioFile(
        id = -track.id,
        title = track.title,
        artist = track.artist,
        album = track.album,
        duration = track.durationSec * 1000L,
        uriString = "$SCHEME://deezer/${track.id}",
        folderPath = "Deezer",
        folderName = "Deezer"
    )

    /** Url de la carátula de una canción remota (por su id negativo), para el reproductor y las listas. */
    fun coverUrl(context: Context, id: Long, large: Boolean = true): String? =
        if (!isRemoteId(id)) null else track(context, deezerIdOf(id))?.let { if (large) it.coverXl else it.coverSmall }?.takeIf { it.isNotBlank() }

    /** Archivo ya disponible (caché o biblioteca), sin pedir nada al bot. */
    fun readyFile(context: Context, deezerId: Long): File? {
        prefs(context).getString("path_$deezerId", null)?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }?.let {
            if (it.parentFile == cacheDir(context)) it.setLastModified(System.currentTimeMillis())
            return it
        }
        return null
    }

    /**
     * Devuelve el archivo de la canción, pidiéndolo al bot si hace falta. Varias llamadas a la vez
     * (reproductor y precarga) comparten la misma descarga.
     */
    suspend fun ensureFile(context: Context, deezerId: Long): File {
        val app = context.applicationContext
        readyFile(app, deezerId)?.let { return it }
        val (deferred, owner) = synchronized(inFlight) {
            inFlight[deezerId]?.let { it to false } ?: CompletableDeferred<File>().also { inFlight[deezerId] = it }.let { it to true }
        }
        if (owner) {
            scope.launch {
                try {
                    deferred.complete(download(app, deezerId))
                } catch (e: Throwable) {
                    val reason = when (e) {
                        is kotlinx.coroutines.TimeoutCancellationException -> "El bot no respondió"
                        else -> e.message ?: "Error"
                    }
                    _statuses.update { it + (deezerId to DownloadStatus.Failed(reason)) }
                    TelemetryLogger.logError(app, "OnDemand", "No se pudo obtener $deezerId: $reason", e)
                    deferred.completeExceptionally(java.io.IOException(reason, e))
                } finally {
                    synchronized(inFlight) { inFlight.remove(deezerId) }
                }
            }
        }
        return deferred.await()
    }

    /** Descarga en segundo plano las próximas canciones remotas de la cola. */
    fun prefetch(context: Context, deezerIds: List<Long>) {
        val app = context.applicationContext
        for (id in deezerIds) {
            if (readyFile(app, id) != null) continue
            scope.launch { runCatching { ensureFile(app, id) } }
        }
    }

    private suspend fun download(context: Context, deezerId: Long): File {
        val track = track(context, deezerId) ?: throw IllegalStateException("Canción desconocida")
        TelegramMusicClient.start(context)
        // El servicio puede arrancar antes que TDLib termine de cargar la sesión
        withTimeoutOrNull(15_000) { TelegramMusicClient.authState.first { it !is TelegramAuthState.Loading } }
        if (!TelegramMusicClient.isReady) throw IllegalStateException("Inicia sesión en Telegram y configura el bot")

        _statuses.update { it + (deezerId to DownloadStatus.Requesting) }
        val audio = TelegramMusicClient.requestTrack(track) { p ->
            _statuses.update { it + (deezerId to DownloadStatus.Downloading(p)) }
        }
        val source = File(audio.localPath)
        val saved = withContext(Dispatchers.IO) {
            if (storageMode(context) == STORAGE_LIBRARY) {
                TelegramDownloadQueue.saveToMusicFolder(context, source, track).also { file ->
                    val localId = TelegramDownloadQueue.scanFile(context, file)?.lastPathSegment?.toLongOrNull()
                    if (localId != null) adoptLocalSong(context, track, file, localId)
                    withContext(Dispatchers.Main) { TelegramDownloadQueue.onSongSaved?.invoke() }
                }
            } else {
                val dir = cacheDir(context).apply { mkdirs() }
                val target = File(dir, "$deezerId.${source.extension.ifBlank { "mp3" }.lowercase()}")
                val temp = File(dir, ".tmp_${target.name}")
                if (!copyAudioVerified(source, temp) || !temp.renameTo(target)) {
                    temp.delete()
                    throw java.io.IOException("No se pudo guardar en la caché")
                }
                trimCache(context, keep = target)
                target
            }
        }
        TelegramMusicClient.deleteCachedFile(audio.fileId)
        prefs(context).edit().putString("path_$deezerId", saved.absolutePath).apply()
        _statuses.update { it + (deezerId to DownloadStatus.Done(saved.absolutePath)) }
        TelemetryLogger.logInfo(context, "OnDemand", "Lista ${track.artist} - ${track.title} (${storageMode(context)})")
        return saved
    }

    /**
     * La canción ya está en el teléfono: se le asegura una fila en Room (si la app está cerrada el escaneo
     * aún no la ha añadido; solo se inserta si falta) y se avisa al servicio para que cambie en la cola el
     * elemento remoto por la canción local. Desde ahí cuenta reproducciones, tiene letra y carátula como cualquier otra.
     */
    private suspend fun adoptLocalSong(context: Context, track: DeezerTrack, file: File, localId: Long) {
        val dao = com.kevshupp.kevmusicplayer.data.AppDatabase.getDatabase(context).audioDao()
        if (dao.getAudioFileById(localId) == null) {
            val now = System.currentTimeMillis()
            dao.insertAll(
                listOf(
                    AudioFile(
                        id = localId, title = track.title, artist = track.artist, album = track.album,
                        duration = track.durationSec * 1000L,
                        uriString = "content://media/external/audio/media/$localId",
                        folderPath = file.parent.orEmpty(), folderName = file.parentFile?.name.orEmpty(),
                        dateAdded = now / 1000, dateModified = now / 1000
                    )
                )
            )
        }
        prefs(context).edit().putLong("local_${track.id}", localId).apply()
        savedToLibrary.tryEmit(track.id to localId)
    }

    /** (id de Deezer, id local) de cada canción bajo demanda que se acaba de guardar en la biblioteca. */
    val savedToLibrary = kotlinx.coroutines.flow.MutableSharedFlow<Pair<Long, Long>>(extraBufferCapacity = 32)

    /** Id local de una canción de Deezer ya descargada a la biblioteca, si su archivo sigue existiendo. */
    fun localIdFor(context: Context, deezerId: Long): Long? {
        val id = prefs(context).getLong("local_$deezerId", 0L).takeIf { it > 0 } ?: return null
        val path = prefs(context).getString("path_$deezerId", null) ?: return null
        return id.takeIf { File(path).exists() }
    }

    /** La canción está en la caché temporal (no en la biblioteca). */
    fun isCached(context: Context, deezerId: Long): Boolean =
        prefs(context).getString("path_$deezerId", null)?.let { File(it) }
            ?.takeIf { it.parentFile == cacheDir(context) && it.exists() && it.length() > 0 } != null

    /**
     * Guarda la canción en la biblioteca. Si ya está en la caché temporal se copia desde ahí (sin volver a
     * pedirla al bot) y se borra de la caché; si no, entra en la cola normal de descargas de Telegram.
     */
    fun saveToLibrary(context: Context, track: DeezerTrack) {
        val app = context.applicationContext
        if (localIdFor(app, track.id) != null) return
        val cached = prefs(app).getString("path_${track.id}", null)?.let { File(it) }
            ?.takeIf { it.parentFile == cacheDir(app) && it.exists() && it.length() > 0 }
        if (cached == null) {
            TelegramDownloadQueue.enqueue(app, track)
            return
        }
        register(app, track)
        _statuses.update { it + (track.id to DownloadStatus.Downloading(1f)) }
        scope.launch {
            try {
                val file = TelegramDownloadQueue.saveToMusicFolder(app, cached, track)
                prefs(app).edit().putString("path_${track.id}", file.absolutePath).apply()
                cached.delete()
                val localId = TelegramDownloadQueue.scanFile(app, file)?.lastPathSegment?.toLongOrNull()
                if (localId != null) adoptLocalSong(app, track, file, localId)
                _statuses.update { it + (track.id to DownloadStatus.Done(file.absolutePath)) }
                TelemetryLogger.logInfo(app, "OnDemand", "Guardada en la biblioteca ${track.artist} - ${track.title}")
                withContext(Dispatchers.Main) { TelegramDownloadQueue.onSongSaved?.invoke() }
            } catch (e: Exception) {
                _statuses.update { it + (track.id to DownloadStatus.Failed(e.message ?: "Error")) }
                TelemetryLogger.logError(app, "OnDemand", "No se pudo guardar ${track.id} en la biblioteca", e)
            }
        }
    }

    /** Borra las canciones de la caché usadas hace más tiempo hasta quedar por debajo del límite. */
    private fun trimCache(context: Context, keep: File) {
        val limit = settings(context).getInt(KEY_CACHE_MB, DEFAULT_CACHE_MB).toLong() * 1024 * 1024
        val files = cacheDir(context).listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= limit) break
            if (f == keep) continue
            total -= f.length()
            f.delete()
        }
    }

    fun cacheSizeBytes(context: Context): Long = cacheDir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache(context: Context) {
        cacheDir(context).listFiles()?.forEach { it.delete() }
        _statuses.value = emptyMap()
    }

    private fun cacheDir(context: Context) = File(context.cacheDir, "ondemand")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun settings(context: Context) = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
}
