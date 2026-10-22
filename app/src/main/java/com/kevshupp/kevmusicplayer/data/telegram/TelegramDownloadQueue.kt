package com.kevshupp.kevmusicplayer.data.telegram

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.MusicFolders
import com.kevshupp.kevmusicplayer.data.SongIdentity
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import com.kevshupp.kevmusicplayer.playback.copyAudioVerified
import kotlin.coroutines.resume

sealed interface DownloadStatus {
    data object Queued : DownloadStatus
    data object Requesting : DownloadStatus
    data class Downloading(val progress: Float) : DownloadStatus
    data class Done(val path: String) : DownloadStatus
    data class Failed(val reason: String) : DownloadStatus
}

/**
 * Cola en serie de descargas a través del bot: una petición a la vez, para que cada respuesta de audio
 * corresponda a su enlace. Vive en el ámbito de la app, así que cerrar la pantalla no la cancela.
 */
object TelegramDownloadQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Una descarga; con [replace] sustituye a una canción dañada de la biblioteca en lugar de añadir otra. */
    private class Request(
        val track: DeezerTrack?,
        val replace: AudioFile?,
        val replacePath: String?,
        /** Sin [track]: se busca este texto en el propio bot y [choose] elige uno de sus resultados. */
        val textQuery: String? = null,
        val choose: ((List<String>) -> Int?)? = null
    )

    private val pending = Channel<Request>(Channel.UNLIMITED)

    private val _statuses = MutableStateFlow<Map<Long, DownloadStatus>>(emptyMap())
    val statuses: StateFlow<Map<Long, DownloadStatus>> = _statuses.asStateFlow()

    /** Descargas pedidas en esta sesión, en el orden en que se encolaron (la pestaña "Cola" las muestra). */
    private val _downloads = MutableStateFlow<List<DeezerTrack>>(emptyList())
    val downloads: StateFlow<List<DeezerTrack>> = _downloads.asStateFlow()

    /** Quita de la cola visible las descargas terminadas o fallidas. */
    fun clearFinished() {
        val statuses = _statuses.value
        _downloads.update { list ->
            list.filterNot { statuses[it.id] is DownloadStatus.Done || statuses[it.id] is DownloadStatus.Failed }
        }
    }

    /** Estado de las reparaciones, por id de la canción de la biblioteca. */
    private val _repairs = MutableStateFlow<Map<Long, DownloadStatus>>(emptyMap())
    val repairs: StateFlow<Map<Long, DownloadStatus>> = _repairs.asStateFlow()

    /** Se llama tras cada descarga correcta (el ViewModel vuelve a escanear la biblioteca). */
    var onSongSaved: (() -> Unit)? = null

    private var started = false

    fun enqueue(context: Context, track: DeezerTrack) {
        val current = _statuses.value[track.id]
        if (current != null && current !is DownloadStatus.Failed) return
        _downloads.update { list -> if (list.any { it.id == track.id }) list else list + track }
        submit(context, Request(track, null, null))
    }

    /**
     * Vuelve a descargar [song] (dañada o cortada) y la reemplaza en su misma carpeta y con su mismo nombre.
     * El archivo viejo solo se borra cuando la copia nueva ya está completa.
     */
    fun enqueueReplacement(context: Context, song: AudioFile, track: DeezerTrack, physicalPath: String) =
        submit(context, Request(track, song, physicalPath))

    /** Como [enqueueReplacement], pero buscando con texto en el bot (canciones que no están en Deezer). */
    fun enqueueReplacementByText(context: Context, song: AudioFile, query: String, choose: (List<String>) -> Int?, physicalPath: String) =
        submit(context, Request(null, song, physicalPath, query, choose))

    fun repairInProgress(songId: Long): Boolean = when (_repairs.value[songId]) {
        null, is DownloadStatus.Failed, is DownloadStatus.Done -> false
        else -> true
    }

    fun markRepairFailed(songId: Long, reason: String) = setRepair(songId, DownloadStatus.Failed(reason))
    fun markRepairSearching(songId: Long) = setRepair(songId, DownloadStatus.Requesting)

    @Synchronized
    private fun submit(context: Context, request: Request) {
        setFor(request, DownloadStatus.Queued)
        if (!started) {
            started = true
            val app = context.applicationContext
            scope.launch { for (r in pending) process(app, r) }
        }
        pending.trySend(request)
    }

    private fun setRepair(id: Long, status: DownloadStatus) = _repairs.update { it + (id to status) }

    private fun setFor(request: Request, status: DownloadStatus) {
        val song = request.replace
        if (song != null) setRepair(song.id, status) else request.track?.let { t -> _statuses.update { it + (t.id to status) } }
    }

    private suspend fun process(context: Context, request: Request) {
        val track = request.track
        try {
            setFor(request, DownloadStatus.Requesting)
            val onProgress: (Float) -> Unit = { p -> setFor(request, DownloadStatus.Downloading(p)) }
            val audio = if (track != null) {
                TelegramMusicClient.requestTrack(track, onProgress)
            } else {
                TelegramMusicClient.requestByText(request.textQuery.orEmpty(), request.choose ?: { null }, onProgress)
            }
            val song = request.replace
            val saved = if (song != null && request.replacePath != null) {
                replaceSongFile(File(audio.localPath), File(request.replacePath))
            } else {
                saveToMusicFolder(context, File(audio.localPath), track ?: throw IllegalStateException("Sin canción"))
            }
            TelegramMusicClient.deleteCachedFile(audio.fileId)
            val newUri = scanFile(context, saved)
            if (song != null) {
                // El escaneo hereda estadísticas, letras y listas de la canción antigua
                newUri?.lastPathSegment?.toLongOrNull()?.takeIf { it != song.id }?.let { SongIdentity.addReplacement(context, it, song.id) }
                TelemetryLogger.logInfo(context, "Telegram", "Reparada ${song.artist} - ${song.title} con ${track?.link ?: "búsqueda \"${request.textQuery}\""}")
            } else {
                TelemetryLogger.logInfo(context, "Telegram", "Descargada ${track?.artist} - ${track?.title}")
            }
            setFor(request, DownloadStatus.Done(saved.absolutePath))
            kotlinx.coroutines.withContext(Dispatchers.Main) { onSongSaved?.invoke() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            setFor(request, DownloadStatus.Failed("El bot no respondió"))
        } catch (e: Exception) {
            TelemetryLogger.logError(context, "Telegram", "Fallo al descargar ${track?.link ?: request.textQuery}", e)
            setFor(request, DownloadStatus.Failed(e.message ?: "Error"))
        }
    }

    /**
     * Copia la descarga junto al original (hermano oculto), verifica el contenido, borra el original y renombra.
     * Mismo nombre y carpeta; si la extensión cambia (p. ej. .m4a → .mp3) se usa la nueva.
     */
    private fun replaceSongFile(source: File, original: File): File {
        val dir = original.parentFile ?: throw java.io.IOException("Carpeta no válida")
        val ext = source.extension.ifBlank { original.extension }.lowercase()
        val target = File(dir, "${original.nameWithoutExtension}.$ext")
        val temp = File(dir, ".kmp_dl_${target.name}")
        if (!copyAudioVerified(source, temp)) {
            throw java.io.IOException("No se pudo copiar la canción nueva")
        }
        if (original.exists() && !original.delete()) {
            temp.delete()
            throw java.io.IOException("No se pudo borrar el archivo dañado")
        }
        if (target.exists() && target != original) {
            // Otro archivo ya ocupa el nombre con la extensión nueva: nunca se sobrescribe
            val alt = File(dir, "${original.nameWithoutExtension} (2).$ext")
            if (!temp.renameTo(alt)) throw java.io.IOException("No se pudo guardar el archivo")
            return alt
        }
        if (!temp.renameTo(target)) throw java.io.IOException("No se pudo guardar el archivo")
        return target
    }

    /** `<carpeta principal>/Artista/Álbum/Artista - Título.ext`; nunca sobrescribe un archivo existente. */
    internal fun saveToMusicFolder(context: Context, source: File, track: DeezerTrack): File {
        val prefs = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
        val base = MusicFolders.main(prefs)?.let { File(it) }
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val artist = clean(track.artist).ifBlank { "Artista Desconocido" }
        val album = clean(track.album).ifBlank { "Álbum Desconocido" }
        val dir = File(File(base, artist), album)
        if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("No se pudo crear ${dir.absolutePath}")

        val ext = source.extension.ifBlank { "mp3" }.lowercase()
        val stem = clean("${track.artist} - ${track.title}")
        var target = File(dir, "$stem.$ext")
        var n = 2
        while (target.exists()) target = File(dir, "$stem ($n).$ext").also { n++ }

        // Copia a un hermano oculto, verifica el contenido y solo entonces renombra
        val temp = File(dir, ".kmp_dl_${target.name}")
        if (!copyAudioVerified(source, temp) || !temp.renameTo(target)) {
            temp.delete()
            throw java.io.IOException("No se pudo guardar el archivo")
        }
        return target
    }

    private fun clean(text: String) = text.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.')

    internal suspend fun scanFile(context: Context, file: File): android.net.Uri? = suspendCancellableCoroutine { cont ->
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, uri ->
            if (cont.isActive) cont.resume(uri)
        }
    }
}
