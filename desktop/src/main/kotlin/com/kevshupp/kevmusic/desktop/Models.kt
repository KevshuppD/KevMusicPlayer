package com.kevshupp.kevmusic.desktop

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val track: Int,
    val year: String,
    val genre: String,
    val plays: Int,
    val added: Long,
    val lastPlayed: Long = 0L
)

/** [smart]: lista inteligente de la app, ya calculada por el teléfono (no se edita desde el PC). */
data class Playlist(val name: String, val songIds: List<Long>, val smart: Boolean = false)

data class Library(val device: String, val songs: List<Song>, val playlists: List<Playlist>) {
    val byId: Map<Long, Song> = songs.associateBy { it.id }
}

data class PlayerState(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val position: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val index: Int,
    val repeat: Int,
    /** Aleatorio propio de la app (reordena la cola en el teléfono). */
    val shuffle: Boolean,
    val volume: Int,
    val volumeMax: Int,
    /** Hora local de llegada: la posición se interpola desde aquí mientras suena. */
    val receivedAt: Long
) {
    fun positionAt(now: Long): Long =
        if (playing) (position + (now - receivedAt)).coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE) else position
}

data class QueueItem(val id: Long, val title: String, val artist: String)

data class QueueState(val index: Int = -1, val items: List<QueueItem> = emptyList())

data class LyricLine(val timeMs: Long, val text: String)

/** [translations]: inicio de la línea en ms → texto traducido, como lo guarda la app (`LyricsRepository.serializeTranslations`). */
data class Lyrics(val id: Long, val lines: List<LyricLine>, val synced: Boolean, val translations: Map<Long, String>) {
    companion object {
        private val tag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

        fun parse(id: Long, raw: String, translatedRaw: String): Lyrics {
            val synced = tag.containsMatchIn(raw)
            return Lyrics(id, parseLines(raw, synced), synced, parseTranslations(translatedRaw))
        }

        // La app guarda la traducción como JSON {"<ms>": "texto"}, no como LRC
        private fun parseTranslations(raw: String): Map<Long, String> = runCatching {
            if (raw.isBlank()) return emptyMap()
            val json = org.json.JSONObject(raw)
            json.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { it to json.optString(k) } }
                .filter { it.second.isNotBlank() }.toMap()
        }.getOrDefault(emptyMap())

        private fun parseLines(raw: String, synced: Boolean): List<LyricLine> {
            if (raw.isBlank()) return emptyList()
            if (!synced) return raw.lines().map { LyricLine(-1, it.trim()) }
            val out = mutableListOf<LyricLine>()
            raw.lines().forEach { line ->
                val matches = tag.findAll(line).toList()
                if (matches.isEmpty()) return@forEach
                val text = line.substring(matches.last().range.last + 1).trim()
                matches.forEach { m ->
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                    out.add(LyricLine(min * 60_000 + sec * 1000 + ms, text))
                }
            }
            return out.sortedBy { it.timeMs }
        }
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ---------- Descargas de Telegram (las hace el teléfono) ----------

data class DzTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val cover: String,
    val coverXl: String,
    val link: String,
    /** Ya está en la biblioteca del teléfono. */
    val owned: Boolean
)

data class DzAlbum(
    val id: Long,
    val title: String,
    val artist: String,
    val cover: String,
    val tracks: Int,
    val type: String,
    val year: String,
    val owned: Int
)

data class DzArtist(val id: Long, val name: String, val picture: String, val albums: Int, val fans: Int)

enum class DlStatus { Queued, Requesting, Downloading, Done, Failed }

data class DownloadItem(val track: DzTrack, val status: DlStatus, val progress: Float, val reason: String)

data class TelegramStatus(val ready: Boolean, val reason: String)

data class DzResult(val tracks: List<DzTrack>, val albums: List<DzAlbum>, val artists: List<DzArtist>, val error: String?)

/** Aviso tras una edición; [at] distingue dos avisos con el mismo texto. */
data class Notice(val text: String, val error: Boolean = false, val at: Long = System.nanoTime())
