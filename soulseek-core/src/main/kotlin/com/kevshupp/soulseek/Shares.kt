package com.kevshupp.soulseek

import java.io.File

/**
 * Archivos compartidos: cada carpeta compartida aparece con su nombre como raíz virtual
 * (`Música\Artista\Álbum\canción.flac`). Solo se comparte audio. Las búsquedas usan un índice de palabras
 * completas, como Nicotine+, porque por la red distribuida llegan decenas por segundo.
 */
class Shares {
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
        val list = ArrayList<Entry>()
        val usedNames = HashSet<String>()
        roots.map { File(it) }.filter { it.isDirectory }.forEach { root ->
            // Dos carpetas con el mismo nombre no pueden tener la misma raíz virtual
            var name = root.name.ifBlank { "Compartido" }
            var n = 2
            while (!usedNames.add(name.lowercase())) name = "${root.name} ($n)".also { n++ }
            root.walkTopDown()
                .onEnter { !it.name.startsWith(".") }
                .filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in AUDIO_EXTENSIONS }
                .forEach { f ->
                    val rel = f.relativeTo(root).path.replace('/', '\\')
                    list += Entry("$name\\$rel", f, f.length())
                }
        }
        val index = HashMap<String, MutableList<Int>>()
        list.forEachIndexed { i, e -> tokenize(e.virtualPath).forEach { w -> index.getOrPut(w) { ArrayList(2) }.add(i) } }
        entries = list
        byPath = list.associateBy { it.virtualPath.lowercase() }
        words = index.mapValues { (_, v) -> v.toIntArray() }
        folders = list.groupBy { it.virtualPath.substringBeforeLast('\\') }
        cachedList = null
    }

    fun find(virtualPath: String): Entry? = byPath[virtualPath.lowercase()]?.takeIf { it.file.isFile }

    /** Todas las palabras incluidas deben aparecer en la ruta; las que empiezan por `-` la excluyen. */
    fun search(query: String, limit: Int = 100): List<Entry> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
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
        fun tokenize(text: String): List<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

        /** En resultados de búsqueda va la ruta completa; en listas de carpeta, solo el nombre. */
        fun writeFiles(w: MsgWriter, files: List<Entry>, fullPath: Boolean) {
            w.u32(files.size)
            files.forEach { e ->
                w.u8(1).str(if (fullPath) e.virtualPath else e.virtualPath.substringAfterLast('\\')).u64(e.size).str(e.file.extension.lowercase()).u32(0)
            }
        }
    }
}
