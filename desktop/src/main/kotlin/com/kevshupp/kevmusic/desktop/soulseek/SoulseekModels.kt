package com.kevshupp.kevmusic.desktop.soulseek

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kevshupp.kevmusic.desktop.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "aiff", "aif", "wma", "alac", "ape")

/** Un archivo de un resultado de búsqueda o de una carpeta remota. Las rutas usan `\` como en Windows. */
data class SlskFile(
    val path: String,
    val size: Long,
    val bitrate: Int?,
    val durationSec: Int?,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null
) {
    val name get() = path.substringAfterLast('\\')
    val folder get() = path.substringBeforeLast('\\', "")
    val extension get() = name.substringAfterLast('.', "").lowercase()
}

/** Respuesta de un usuario a una búsqueda. */
data class SlskResult(
    val username: String,
    val files: List<SlskFile>,
    val freeSlot: Boolean,
    val speed: Long,
    val queue: Long
)

enum class TransferState { Queued, Waiting, Connecting, Transferring, Sending, Done, Failed }

/** Descarga (de otro usuario a este PC) o subida (de este PC a otro usuario). */
data class Transfer(
    val id: Long,
    val username: String,
    val path: String,
    val size: Long,
    val state: TransferState = TransferState.Queued,
    val done: Long = 0,
    val speed: Long = 0,
    val detail: String = "",
    val localPath: String = ""
) {
    val name get() = path.substringAfterLast('\\')
    val progress get() = if (size > 0) (done.toFloat() / size).coerceIn(0f, 1f) else 0f
    val finished get() = state == TransferState.Done || state == TransferState.Failed
    /** Descargada en el PC pero todavía no enviada al teléfono (esperando o con el envío fallido). */
    val awaitingPhone get() = localPath.isNotEmpty() && (state == TransferState.Sending || (state == TransferState.Failed && detail.contains("teléfono")))
}

sealed interface SlskStatus {
    data object Off : SlskStatus
    data object Connecting : SlskStatus
    data class Online(val username: String) : SlskStatus
    data class Failed(val message: String) : SlskStatus
}

/**
 * Ajustes y cuenta de Soulseek en ~/.config/kevmusic-desktop/soulseek.json (solo legible por el usuario,
 * contiene la contraseña).
 */
object SoulseekConfig {
    private val file = File(Config.dir, "soulseek.json")

    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var autoConnect by mutableStateOf(true)
    var sharedFolders by mutableStateOf<List<String>>(emptyList())
    var downloadDir by mutableStateOf("")
    var port by mutableStateOf(2234)
    var useUpnp by mutableStateOf(true)
    var uploadSlots by mutableStateOf(3)
    /** KB/s; 0 sin límite. */
    var uploadLimit by mutableStateOf(0)
    var sendToPhone by mutableStateOf(true)
    /** Conservar la copia del PC tras enviarla al teléfono (y seguir compartiéndola). */
    var keepOnPc by mutableStateOf(true)

    val hasAccount get() = username.isNotBlank() && password.isNotBlank()

    fun load() {
        val music = xdgMusicDir()
        runCatching {
            val j = JSONObject(file.readText())
            username = j.optString("username")
            password = j.optString("password")
            autoConnect = j.optBoolean("autoConnect", true)
            sharedFolders = j.optJSONArray("shared")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf(music.path)
            downloadDir = j.optString("downloadDir").ifBlank { File(music, "Soulseek").path }
            port = j.optInt("port", 2234)
            useUpnp = j.optBoolean("upnp", true)
            uploadSlots = j.optInt("uploadSlots", 3).coerceIn(1, 10)
            uploadLimit = j.optInt("uploadLimit", 0).coerceAtLeast(0)
            sendToPhone = j.optBoolean("sendToPhone", true)
            keepOnPc = j.optBoolean("keepOnPc", true)
        }.onFailure {
            // Primera vez: se comparte la carpeta de música y las descargas van dentro (también se comparten)
            sharedFolders = if (music.isDirectory) listOf(music.path) else emptyList()
            downloadDir = File(music, "Soulseek").path
        }
    }

    fun save() {
        runCatching {
            Config.dir.mkdirs()
            file.writeText(
                JSONObject().put("username", username).put("password", password).put("autoConnect", autoConnect)
                    .put("shared", JSONArray(sharedFolders)).put("downloadDir", downloadDir).put("port", port)
                    .put("upnp", useUpnp).put("uploadSlots", uploadSlots).put("uploadLimit", uploadLimit)
                    .put("sendToPhone", sendToPhone).put("keepOnPc", keepOnPc).toString(2)
            )
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
        }
    }

    /** XDG_MUSIC_DIR de ~/.config/user-dirs.dirs (p. ej. ~/Música), o ~/Music. */
    private fun xdgMusicDir(): File {
        val home = System.getProperty("user.home")
        val line = runCatching { File(home, ".config/user-dirs.dirs").readLines() }.getOrDefault(emptyList())
            .firstOrNull { it.startsWith("XDG_MUSIC_DIR=") }
        val value = line?.substringAfter('=')?.trim('"')?.replace("\$HOME", home)
        return value?.let { File(it) }?.takeIf { it.path != home } ?: File(home, "Music")
    }
}
