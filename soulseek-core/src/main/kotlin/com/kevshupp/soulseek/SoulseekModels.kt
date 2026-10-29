package com.kevshupp.soulseek

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

/** Descarga (de otro usuario a este equipo) o subida (de este equipo a otro usuario). */
data class Transfer(
    val id: Long,
    val username: String,
    val path: String,
    val size: Long,
    val state: TransferState = TransferState.Queued,
    val done: Long = 0,
    val speed: Long = 0,
    val detail: String = "",
    val localPath: String = "",
    /** Carpeta de destino fija (descarga repartida: todas las canciones del álbum juntas aunque vengan de varios usuarios). */
    val folder: String = "",
    /** Descargada pero el envío a su destino falló; se guarda en la cola para reintentarlo. */
    val deliveryFailed: Boolean = false
) {
    val name get() = path.substringAfterLast('\\')
    val progress get() = if (size > 0) (done.toFloat() / size).coerceIn(0f, 1f) else 0f
    val finished get() = state == TransferState.Done || state == TransferState.Failed
    /** Descargada pero todavía no entregada a su destino (esperando o con el envío fallido). */
    val awaitingPhone get() = localPath.isNotEmpty() && (state == TransferState.Sending || (state == TransferState.Failed && deliveryFailed))
}

sealed interface SlskStatus {
    data object Off : SlskStatus
    data object Connecting : SlskStatus
    data class Online(val username: String) : SlskStatus
    data class Failed(val message: String) : SlskStatus
}

/**
 * Ajustes que lee el cliente. En el PC los guarda `SoulseekConfig` (soulseek.json); en el teléfono, las
 * preferencias de la app. Se leen en cada uso, así que un cambio vale al instante (salvo el puerto).
 */
interface SlskSettings {
    val username: String
    val password: String
    /** Carpetas compartidas; vacía = no se comparte nada. */
    val sharedFolders: List<String>
    val downloadDir: String
    val port: Int
    val useUpnp: Boolean
    val uploadSlots: Int
    /** KB/s; 0 sin límite. */
    val uploadLimit: Int
    /** Entregar cada descarga terminada a [SoulseekClient.phone] (en el PC: subirla al teléfono). */
    val sendToPhone: Boolean
    /** Conservar el archivo descargado tras entregarlo. */
    val keepOnPc: Boolean
    /**
     * Participar en la red distribuida (recibir y reenviar búsquedas de otros). Sin compartir nada no aporta y
     * en el teléfono gasta datos y batería.
     */
    val joinDistributed: Boolean get() = sharedFolders.isNotEmpty()

    val hasAccount get() = username.isNotBlank() && password.isNotBlank()
}

/** Textos que dependen de dónde corre el cliente. */
data class SlskLabels(
    val clientName: String = "KevMusic Desktop",
    val downloaded: String = "En el PC",
    val waiting: String = "Esperando al teléfono",
    val sending: String = "Enviando al teléfono",
    val delivered: String = "En el teléfono",
    val deliveryFailed: String = "No se pudo enviar al teléfono",
    val localMissing: String = "El archivo ya no está en el PC"
)
