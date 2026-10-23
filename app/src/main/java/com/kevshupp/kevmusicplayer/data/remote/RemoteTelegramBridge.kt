package com.kevshupp.kevmusicplayer.data.remote

import android.content.Context
import com.kevshupp.kevmusicplayer.data.AppDatabase
import com.kevshupp.kevmusicplayer.data.telegram.ArtistAffinity
import com.kevshupp.kevmusicplayer.data.telegram.DeezerAlbum
import com.kevshupp.kevmusicplayer.data.telegram.DeezerArtist
import com.kevshupp.kevmusicplayer.data.telegram.DeezerTrack
import com.kevshupp.kevmusicplayer.data.telegram.DeezerTrackSearch
import com.kevshupp.kevmusicplayer.data.telegram.DownloadStatus
import com.kevshupp.kevmusicplayer.data.telegram.LibraryMatcher
import com.kevshupp.kevmusicplayer.data.telegram.TelegramAuthState
import com.kevshupp.kevmusicplayer.data.telegram.TelegramDownloadQueue
import com.kevshupp.kevmusicplayer.data.telegram.TelegramMusicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Descargas de Telegram para KevMusic Desktop: el PC busca y pide, pero todo ocurre en el teléfono
 * (Deezer, bot, [TelegramDownloadQueue]) y lo descargado se guarda en la carpeta principal como siempre.
 *
 * Mensajes: `tgSearch` (mode tracks/albums/artists), `tgAlbum`, `tgArtist` → `tgResult` con el mismo `req`;
 * `tgDownload`, `tgClearFinished`. El teléfono emite `tgStatus`, `tgQueue` y `libraryChanged`.
 */
class RemoteTelegramBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val broadcast: (JSONObject) -> Unit
) {
    private val jobs = mutableListOf<Job>()

    // LibraryMatcher y ArtistAffinity se construyen con la biblioteca de Room; se reutilizan hasta que cambia
    @Volatile private var matcherCache: Pair<LibraryMatcher, ArtistAffinity>? = null

    @OptIn(FlowPreview::class)
    fun start() {
        TelegramMusicClient.start(context)
        jobs += scope.launch {
            combine(TelegramMusicClient.enabled, TelegramMusicClient.authState, TelegramMusicClient.bot) { _, _, _ -> statusJson() }
                .collect { broadcast(it) }
        }
        jobs += scope.launch {
            combine(TelegramDownloadQueue.downloads, TelegramDownloadQueue.statuses) { list, statuses -> list to statuses }
                .sample(400) // el progreso cambia muchas veces por segundo
                .collect { (list, statuses) -> broadcast(queueJson(list, statuses)) }
        }
        // Una canción descargada (o borrada, o encontrada en un escaneo) cambia el número de filas:
        // el escritorio vuelve a pedir la biblioteca. Las reproducciones no cuentan.
        jobs += scope.launch {
            AppDatabase.getDatabase(context).audioDao().observeSongCount()
                .distinctUntilChanged()
                .drop(1)
                .debounce(1500)
                .collect {
                    matcherCache = null
                    broadcast(JSONObject().put("type", "libraryChanged"))
                }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /** Estado inicial para un escritorio que acaba de conectarse. */
    fun snapshot(): List<JSONObject> =
        listOf(statusJson(), queueJson(TelegramDownloadQueue.downloads.value, TelegramDownloadQueue.statuses.value))

    /** Devuelve true si el mensaje era de Telegram. Las respuestas van solo a [reply]. */
    fun handle(msg: JSONObject, reply: (JSONObject) -> Unit): Boolean {
        val type = msg.optString("type")
        if (!type.startsWith("tg")) return false
        when (type) {
            "tgDownload" -> {
                if (!TelegramMusicClient.isReady) return true
                val arr = msg.optJSONArray("tracks") ?: JSONArray()
                (0 until arr.length()).map { trackFromJson(arr.getJSONObject(it)) }
                    .forEach { TelegramDownloadQueue.enqueue(context, it) }
            }
            "tgClearFinished" -> TelegramDownloadQueue.clearFinished()
            "tgSearch", "tgAlbum", "tgArtist" -> scope.launch(Dispatchers.IO) {
                val req = msg.optLong("req")
                val result = JSONObject().put("type", "tgResult").put("req", req)
                try {
                    val (matcher, affinity) = matchers()
                    when (type) {
                        "tgSearch" -> {
                            val q = msg.optString("query").trim()
                            when (msg.optString("mode")) {
                                "albums" -> result.put("albums", albumsJson(affinity.rank(DeezerTrackSearch.searchAlbums(q)) { it.artist }, matcher))
                                "artists" -> result.put("artists", artistsJson(affinity.rank(DeezerTrackSearch.searchArtists(q)) { it.name }))
                                else -> result.put("tracks", tracksJson(affinity.rank(DeezerTrackSearch.search(q)) { it.artist }, matcher))
                            }
                        }
                        "tgAlbum" -> {
                            val album = albumFromJson(msg.getJSONObject("album"))
                            result.put("tracks", tracksJson(DeezerTrackSearch.albumTracks(album), matcher))
                        }
                        "tgArtist" -> {
                            val id = msg.optLong("id")
                            val name = msg.optString("name")
                            result.put("tracks", tracksJson(DeezerTrackSearch.artistTopTracks(id), matcher))
                            result.put("albums", albumsJson(DeezerTrackSearch.artistAlbums(id, name), matcher))
                        }
                    }
                } catch (e: Exception) {
                    result.put("error", "No se pudo buscar: ${e.message ?: e.javaClass.simpleName}")
                }
                reply(result)
            }
        }
        return true
    }

    private suspend fun matchers(): Pair<LibraryMatcher, ArtistAffinity> {
        matcherCache?.let { return it }
        val songs = AppDatabase.getDatabase(context).audioDao().getAllAudioFilesLightweight()
        return (LibraryMatcher(songs) to ArtistAffinity(songs)).also { matcherCache = it }
    }

    private fun statusJson(): JSONObject {
        val enabled = TelegramMusicClient.enabled.value
        val auth = TelegramMusicClient.authState.value
        val bot = TelegramMusicClient.bot.value
        val reason = when {
            !enabled -> "Las funciones de Telegram están apagadas en el teléfono (Ajustes › Biblioteca)."
            auth == TelegramAuthState.NotConfigured -> "Esta versión de la app no tiene las credenciales de Telegram."
            auth == TelegramAuthState.Loading -> "Telegram está arrancando en el teléfono…"
            auth is TelegramAuthState.Unsupported -> "Telegram pide un paso no soportado (${auth.what}); revísalo en el teléfono."
            auth != TelegramAuthState.Ready -> "Inicia sesión en Telegram en el teléfono (botón de nube en Inicio)."
            bot.isBlank() -> "Configura el bot de descargas en el teléfono (botón de nube en Inicio)."
            else -> ""
        }
        return JSONObject().put("type", "tgStatus").put("ready", reason.isEmpty()).put("reason", reason).put("bot", bot)
            .put("auth", auth.javaClass.simpleName)
    }

    private fun queueJson(list: List<DeezerTrack>, statuses: Map<Long, DownloadStatus>): JSONObject {
        val items = JSONArray()
        list.forEach { t ->
            val status = statuses[t.id]
            val json = trackJson(t, owned = false)
            when (status) {
                null, DownloadStatus.Queued -> json.put("status", "queued")
                DownloadStatus.Requesting -> json.put("status", "requesting")
                is DownloadStatus.Downloading -> json.put("status", "downloading").put("progress", status.progress.toDouble())
                is DownloadStatus.Done -> json.put("status", "done")
                is DownloadStatus.Failed -> json.put("status", "failed").put("reason", status.reason)
            }
            items.put(json)
        }
        return JSONObject().put("type", "tgQueue").put("items", items)
    }

    private fun trackJson(t: DeezerTrack, owned: Boolean) = JSONObject()
        .put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album)
        .put("duration", t.durationSec).put("cover", t.coverSmall).put("coverXl", t.coverXl).put("link", t.link)
        .put("owned", owned)

    private fun tracksJson(tracks: List<DeezerTrack>, matcher: LibraryMatcher) =
        JSONArray().apply { tracks.forEach { put(trackJson(it, matcher.hasTrack(it))) } }

    private fun albumsJson(albums: List<DeezerAlbum>, matcher: LibraryMatcher) = JSONArray().apply {
        albums.forEach { a ->
            put(
                JSONObject().put("id", a.id).put("title", a.title).put("artist", a.artist).put("cover", a.cover)
                    .put("tracks", a.trackCount).put("type", a.recordType).put("year", a.year)
                    .put("owned", matcher.albumSongCount(a))
            )
        }
    }

    private fun artistsJson(artists: List<DeezerArtist>) = JSONArray().apply {
        artists.forEach { a ->
            put(JSONObject().put("id", a.id).put("name", a.name).put("picture", a.picture).put("albums", a.albumCount).put("fans", a.fans))
        }
    }

    private fun trackFromJson(o: JSONObject) = DeezerTrack(
        id = o.getLong("id"),
        title = o.optString("title"),
        artist = o.optString("artist"),
        album = o.optString("album"),
        durationSec = o.optInt("duration"),
        coverSmall = o.optString("cover"),
        coverXl = o.optString("coverXl"),
        link = o.optString("link").ifBlank { "https://www.deezer.com/track/${o.getLong("id")}" }
    )

    private fun albumFromJson(o: JSONObject) = DeezerAlbum(
        id = o.getLong("id"),
        title = o.optString("title"),
        artist = o.optString("artist"),
        cover = o.optString("cover"),
        trackCount = o.optInt("tracks"),
        recordType = o.optString("type"),
        year = o.optString("year")
    )
}
