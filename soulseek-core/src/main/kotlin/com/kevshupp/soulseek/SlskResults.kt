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

// ---- Canciones y artistas reconocidos en una búsqueda ----

/** Minúsculas, sin tildes y con solo letras y números separados por un espacio. */
fun slskKey(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

private fun tokens(s: String) = slskKey(s).split(' ').filter { it.isNotEmpty() }

/** Palabras que separan versiones distintas de una canción: si el título no las lleva, el archivo tampoco. */
private val VERSION_WORDS = setOf("instrumental", "karaoke", "inst", "remix", "acapella", "live", "mr")

/**
 * ¿La búsqueda nombra esta canción? Todas las palabras del título deben estar en la búsqueda
 * y las que sobran deben ser del artista ("butter bts" → *Butter* de BTS; "bts" solo no).
 */
fun queryNamesSong(query: String, title: String, artist: String): Boolean {
    val q = tokens(query)
    val t = tokens(title.substringBefore(" (").substringBefore(" - "))
    if (t.isEmpty() || !q.containsAll(t)) return false
    val rest = q - t.toSet()
    val a = tokens(artist).toSet()
    return rest.all { it in a }
}

/** La ruta (carpetas y nombre) menciona al artista. */
fun pathHasArtist(path: String, artist: String): Boolean {
    val key = slskKey(artist)
    return key.isNotEmpty() && " ${slskKey(path)} ".contains(" $key ")
}

/**
 * Archivos que son esta canción: el nombre contiene todas las palabras del título, la ruta nombra
 * al artista, sin versiones que el título no pide, y con duración ±4 s si se conoce. Mejor primero:
 * disponibles, sin pérdida, mayor bitrate, más rápidos.
 */
fun songMatches(groups: List<FolderGroup>, title: String, artist: String, durationSec: Int, limit: Int = 12): List<Pair<FolderGroup, SlskFile>> {
    val t = tokens(title.substringBefore(" (")).toSet()
    if (t.isEmpty()) return emptyList()
    val forbidden = VERSION_WORDS - tokens(title).toSet()
    return groups.asSequence()
        .flatMap { g -> g.files.asSequence().map { g to it } }
        .filter { (_, f) ->
            val name = tokens(f.name.substringBeforeLast('.')).toSet()
            name.containsAll(t) && name.none { it in forbidden } && pathHasArtist(f.path, artist) &&
                (durationSec <= 0 || f.durationSec == null || kotlin.math.abs(f.durationSec - durationSec) <= 4)
        }
        .sortedWith(
            compareByDescending<Pair<FolderGroup, SlskFile>> { it.first.free }
                .thenByDescending { it.second.extension in LOSSLESS }
                .thenByDescending { it.second.bitrate ?: 0 }
                .thenByDescending { it.first.speed }
        )
        .distinctBy { it.first.user }
        .take(limit)
        .toList()
}
