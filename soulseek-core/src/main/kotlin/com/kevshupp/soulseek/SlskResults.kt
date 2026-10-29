package com.kevshupp.soulseek

// Agrupado y orden de resultados, compartido por la pantalla del PC y la del teléfono

data class FolderGroup(val user: String, val folder: String, val files: List<SlskFile>, val free: Boolean, val speed: Long, val queue: Long) {
    val key get() = "$user|$folder"
    val title get() = folder.substringAfterLast('\\').ifBlank { folder }
    val formats get() = files.map { it.extension.uppercase() }.distinct().joinToString("/")
    val bitrate get() = files.mapNotNull { it.bitrate }.maxOrNull()
    val depth get() = files.mapNotNull { it.bitDepth }.maxOrNull()
    val rate get() = files.mapNotNull { it.sampleRate }.maxOrNull()
}

/** Resultados agrupados por usuario y carpeta, solo con los archivos que pasan [keep]. */
fun folderGroups(results: List<SlskResult>, keep: (SlskFile) -> Boolean = { true }): List<FolderGroup> =
    results.asSequence()
        .flatMap { r ->
            r.files.filter(keep).groupBy { it.folder }
                .map { (folder, files) -> FolderGroup(r.username, folder, files.sortedBy { it.path }, r.freeSlot, r.speed, r.queue) }
        }
        .distinctBy { it.key }
        .toList()

val LOSSLESS = setOf("flac", "wav", "aiff", "aif", "alac", "ape")

/**
 * Sin pérdida, o con pérdida a 256 kbps o más. Muchos no envían el bitrate: se estima con tamaño y duración,
 * y sin ninguno de los dos el archivo no cuenta.
 */
fun isHighQuality(f: SlskFile): Boolean {
    if (f.extension in LOSSLESS) return true
    val kbps = f.bitrate ?: f.durationSec?.takeIf { it > 0 }?.let { (f.size * 8 / 1000 / it).toInt() } ?: return false
    return kbps >= 256
}

/**
 * MP3 a 320 kbps. Con el bitrate que envía el usuario basta 316 o más; estimado con tamaño y duración, 300
 * (la carátula y las etiquetas lo inflan un poco y un V0 queda en ~245–260).
 */
fun isMp3_320(f: SlskFile): Boolean {
    if (f.extension != "mp3") return false
    f.bitrate?.let { return it >= 316 }
    val sec = f.durationSec?.takeIf { it > 0 } ?: return false
    return f.size * 8 / 1000 / sec >= 300
}

/** Mejores fuentes primero: completas, disponibles ahora, sin pérdida, mayor bitrate, más rápidas. */
fun rankSources(albumTracks: Int, sources: List<FolderGroup>): List<FolderGroup> = sources.sortedWith(
    compareByDescending<FolderGroup> { albumTracks > 0 && it.files.size >= albumTracks }
        .thenByDescending { it.free }
        .thenByDescending { g -> g.files.any { it.extension in LOSSLESS } }
        .thenByDescending { it.bitrate ?: 0 }
        .thenByDescending { it.speed }
        .thenBy { it.queue }
)

/** Nombre de la canción sin número de pista, artista ni extensión, para emparejarla entre carpetas de distintos usuarios. */
fun songKey(fileName: String, artist: Set<String>): String =
    AlbumMatcher.tokens(fileName.substringBeforeLast('.')).filterNot { it.all(Char::isDigit) || it in artist }.sorted().joinToString(" ")

/**
 * Reparto para "Descargar repartido": fuentes completas, de usuarios distintos y con los mismos formatos que la
 * mejor (para no mezclar FLAC con MP3); por cada canción de la mejor, las fuentes que tienen la misma canción.
 * Null si no hay al menos dos fuentes que compartan la mayoría de las canciones.
 */
fun splitPlan(albumTracks: Int, albumArtist: String, sources: List<FolderGroup>): List<List<Pair<String, SlskFile>>>? {
    val complete = sources.filter { albumTracks <= 0 || it.files.size >= albumTracks }
    val base = complete.firstOrNull() ?: return null
    val compatible = complete.filter { it.formats == base.formats }.distinctBy { it.user }
    if (compatible.size < 2) return null
    val artist = AlbumMatcher.tokens(albumArtist)
    val byKey = compatible.map { g -> g.user to g.files.associateBy { songKey(it.name, artist) } }
    val plan = base.files.map { f ->
        val key = songKey(f.name, artist)
        byKey.mapNotNull { (user, files) -> files[key]?.let { user to it } }.ifEmpty { listOf(base.user to f) }
    }
    return plan.takeIf { p -> p.count { it.size >= 2 } * 2 >= p.size }
}

fun quality(bitrate: Int?, depth: Int?, rate: Int?): String? = when {
    depth != null && rate != null -> "$depth bit / ${"%.1f".format(rate / 1000.0)} kHz"
    bitrate != null && bitrate > 0 -> "$bitrate kbps"
    else -> null
}

/** "1:05", "12 s" o "1 h 3 min". */
fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds.coerceAtLeast(1)} s"
    seconds < 3600 -> "%d:%02d".format(seconds / 60, seconds % 60)
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
}

fun formatSpeed(bytesPerSec: Long) =
    if (bytesPerSec >= 1L shl 20) "%.1f MB/s".format(bytesPerSec / (1L shl 20).toDouble()) else "${bytesPerSec / 1024} KB/s"
