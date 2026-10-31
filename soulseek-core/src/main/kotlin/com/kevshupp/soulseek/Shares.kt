package com.kevshupp.soulseek

import java.io.File

/**
 * Archivos compartidos: cada carpeta compartida aparece con su nombre como raíz virtual
 * (`Música\Artista\Álbum\canción.flac`). Solo se comparte audio. Las búsquedas usan un índice de palabras
 * completas, como Nicotine+, porque por la red distribuida llegan decenas por segundo.
 */
class Shares(private val cacheFile: File? = null) {
    /** Lo leído de una carpeta. Si su fecha no cambió, tampoco sus nombres: no hace falta volver a listarla. */
    private class DirSnap(val modified: Long, val files: List<Pair<String, Long>>, val subdirs: List<String>)

    private var dirCache: Map<String, DirSnap>? = null

    class Entry(val virtualPath: String, val file: File, val size: Long)

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var byPath: Map<String, Entry> = emptyMap()
    @Volatile private var words: Map<String, IntArray> = emptyMap()
    @Volatile private var folders: Map<String, List<Entry>> = emptyMap()
    @Volatile private var cachedList: ByteArray? = null

    val fileCount get() = entries.size
    val folderCount get() = folders.size

    @Synchronized
    fun rescan(roots: List<String>) {
        val previous = dirCache ?: loadCache().also { dirCache = it }
        val seen = HashMap<String, DirSnap>()
        val list = ArrayList<Entry>()
        val usedNames = HashSet<String>()
        roots.map { File(it) }.filter { it.isDirectory }.forEach { root ->
            // Dos carpetas con el mismo nombre no pueden tener la misma raíz virtual
            var name = root.name.ifBlank { "Compartido" }
            var n = 2
            while (!usedNames.add(name.lowercase())) name = "${root.name} ($n)".also { n++ }
            collect(root, name, previous, seen, list)
        }
        saveCache(seen, previous)
        dirCache = seen
        // Sin cambios (lo normal tras una descarga que ya se movió) no se rehace el índice
        val current = entries
        if (list.size == current.size && list.indices.all { list[it].virtualPath == current[it].virtualPath && list[it].size == current[it].size }) return
        val index = HashMap<String, MutableList<Int>>()
        list.forEachIndexed { i, e -> tokenize(e.virtualPath).forEach { w -> index.getOrPut(w) { ArrayList(2) }.add(i) } }
        entries = list
        byPath = list.associateBy { it.virtualPath.lowercase() }
        words = index.mapValues { (_, v) -> v.toIntArray() }
        folders = list.groupBy { it.virtualPath.substringBeforeLast('\\') }
        cachedList = null
    }

    private fun collect(dir: File, virtual: String, previous: Map<String, DirSnap>, seen: MutableMap<String, DirSnap>, out: MutableList<Entry>) {
        val modified = dir.lastModified()
        val old = previous[dir.path]
        // Una carpeta cambiada hace un instante podría volver a cambiar con la misma fecha
        val snap = if (old != null && old.modified == modified && System.currentTimeMillis() - modified > RECENT_MS) old else {
            val children = dir.listFiles().orEmpty().filterNot { it.name.startsWith(".") }.sortedBy { it.name }
            DirSnap(
                modified,
                children.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }.map { it.name to it.length() },
                children.filter { it.isDirectory }.map { it.name }
            )
        }
        seen[dir.path] = snap
        snap.subdirs.forEach { collect(File(dir, it), "$virtual\\$it", previous, seen, out) }
        snap.files.forEach { (name, size) -> out += Entry("$virtual\\$name", File(dir, name), size) }
    }

    /** Formato por líneas: `D<tab>ruta<tab>fecha`, seguida de `F<tab>nombre<tab>tamaño` y `S<tab>nombre`. */
    private fun loadCache(): Map<String, DirSnap> {
        val file = cacheFile?.takeIf { it.isFile } ?: return emptyMap()
        return runCatching {
            val result = HashMap<String, DirSnap>()
            var path = ""
            var modified = 0L
            var files = ArrayList<Pair<String, Long>>()
            var subdirs = ArrayList<String>()
            fun flush() { if (path.isNotEmpty()) result[path] = DirSnap(modified, files, subdirs) }
            file.forEachLine { line ->
                val f = line.split('\t')
                when (f[0]) {
                    "D" -> { flush(); path = f[1]; modified = f[2].toLong(); files = ArrayList(); subdirs = ArrayList() }
                    "F" -> files += f[1] to f[2].toLong()
                    "S" -> subdirs += f[1]
                }
            }
            flush()
            result as Map<String, DirSnap>
        }.getOrDefault(emptyMap())
    }

    private fun saveCache(now: Map<String, DirSnap>, previous: Map<String, DirSnap>) {
        val file = cacheFile ?: return
        if (now.size == previous.size && now.all { (k, v) -> previous[k] === v }) return
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                now.forEach { (path, s) ->
                    // Un nombre con tabulador rompería el formato: esa carpeta se vuelve a leer la próxima vez
                    if (path.contains('\t') || s.files.any { it.first.contains('\t') } || s.subdirs.any { it.contains('\t') }) return@forEach
                    w.write("D\t$path\t${s.modified}\n")
                    s.files.forEach { (n, size) -> w.write("F\t$n\t$size\n") }
                    s.subdirs.forEach { w.write("S\t$it\n") }
                }
            }
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    fun find(virtualPath: String): Entry? = byPath[virtualPath.lowercase()]?.takeIf { it.file.isFile }

    /** Todas las palabras incluidas deben aparecer en la ruta; las que empiezan por `-` la excluyen. */
    fun search(query: String, limit: Int = 100): List<Entry> {
        val terms = query.lowercase().split(WHITESPACE).filter { it.isNotBlank() }
        val include = terms.filterNot { it.startsWith("-") }.flatMap { tokenize(it) }.distinct()
        val exclude = terms.filter { it.startsWith("-") && it.length > 1 }.flatMap { tokenize(it.drop(1)) }.toSet()
        if (include.isEmpty()) return emptyList()
        val index = words
        val lists = include.map { index[it] ?: return emptyList() }.sortedBy { it.size }
        var result = lists.first().toHashSet()
        for (l in lists.drop(1)) { result = l.filterTo(HashSet()) { it in result }; if (result.isEmpty()) return emptyList() }
        val all = entries
        return result.sorted().asSequence()
            .map { all[it] }
            .filter { e -> exclude.isEmpty() || tokenize(e.virtualPath).none { it in exclude } }
            .take(limit).toList()
    }

    fun folder(virtualFolder: String): List<Entry> =
        folders.entries.firstOrNull { it.key.equals(virtualFolder, ignoreCase = true) }?.value.orEmpty()

    /** Respuesta a "ver archivos del usuario" (SharedFileListResponse), comprimida y guardada hasta el próximo escaneo. */
    fun sharedListMessage(): ByteArray {
        cachedList?.let { return it }
        val w = MsgWriter().u32(folders.size)
        folders.toSortedMap().forEach { (dir, files) -> w.str(dir); writeFiles(w, files, fullPath = false) }
        w.u32(0) // sin carpetas privadas
        return w.frameZlib(PeerCode.SHARED_FILE_LIST_RESPONSE).also { cachedList = it }
    }

    companion object {
        private const val RECENT_MS = 3000L
        // Se llaman por cada búsqueda de la red: compilarlas una sola vez
        private val WHITESPACE = Regex("\\s+")
        private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

        fun tokenize(text: String): List<String> =
            text.lowercase().split(NOT_WORD).filter { it.isNotEmpty() }

        /** En resultados de búsqueda va la ruta completa; en listas de carpeta, solo el nombre. */
        fun writeFiles(w: MsgWriter, files: List<Entry>, fullPath: Boolean) {
            w.u32(files.size)
            files.forEach { e ->
                w.u8(1).str(if (fullPath) e.virtualPath else e.virtualPath.substringAfterLast('\\')).u64(e.size).str(e.file.extension.lowercase()).u32(0)
            }
        }
    }
}
