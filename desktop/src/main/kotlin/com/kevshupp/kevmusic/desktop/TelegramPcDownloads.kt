package com.kevshupp.kevmusic.desktop

import com.kevshupp.kevmusic.desktop.local.LocalLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Descargar › Telegram sin el teléfono: el PC busca en Deezer directamente y pide cada pista al bot con su propia
 * sesión de Telegram ([TelegramPc]). Lo descargado se guarda en la carpeta de música del PC
 * (`Artista/Álbum/Artista - Título.ext`) y entra en su biblioteca.
 */
object PcTelegramDownloads {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<DzTrack>(Channel.UNLIMITED)

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads

    /** Se llama al guardar una canción (el PC vuelve a escanear su música). */
    @Volatile var onSaved: () -> Unit = {}

    init {
        scope.launch { for (t in queue) process(t) }
    }

    // ---------- Deezer ----------

    private fun get(url: String): JSONObject =
        http.newCall(Request.Builder().url(url).build()).execute().use { JSONObject(it.body!!.string()) }

    private fun arr(j: JSONObject, key: String = "data"): List<JSONObject> =
        j.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

    private fun track(o: JSONObject, albumTitle: String? = null, cover: String? = null, coverXl: String? = null): DzTrack {
        val al = o.optJSONObject("album")
        return DzTrack(
            o.getLong("id"), o.optString("title"), o.optJSONObject("artist")?.optString("name").orEmpty(),
            albumTitle ?: al?.optString("title").orEmpty(), o.optInt("duration"),
            cover ?: al?.optString("cover_medium").orEmpty(), coverXl ?: al?.optString("cover_xl").orEmpty(),
            o.optString("link").ifBlank { "https://www.deezer.com/track/${o.getLong("id")}" }, owned = false
        )
    }

    private fun album(o: JSONObject, artist: String? = null) = DzAlbum(
        o.getLong("id"), o.optString("title"), artist ?: o.optJSONObject("artist")?.optString("name").orEmpty(),
        o.optString("cover_medium"), o.optInt("nb_tracks"), o.optString("record_type"), o.optString("release_date").take(4), 0
    )

    private fun artist(o: JSONObject) = DzArtist(o.getLong("id"), o.optString("name"), o.optString("picture_medium"), o.optInt("nb_album"), o.optInt("nb_fan"))

    private fun dz(path: String, q: String) =
        get("https://api.deezer.com/search$path".toHttpUrl().newBuilder().addQueryParameter("q", q).addQueryParameter("limit", "40").build().toString())

    private suspend fun safe(block: () -> DzResult): DzResult = withContext(Dispatchers.IO) {
        runCatching(block).getOrElse { DzResult(emptyList(), emptyList(), emptyList(), "No se pudo buscar en Deezer: ${it.message}") }
    }

    suspend fun search(query: String, mode: String): DzResult = safe {
        when (mode) {
            "albums" -> DzResult(emptyList(), arr(dz("/album", query)).map { album(it) }, emptyList(), null)
            "artists" -> DzResult(emptyList(), emptyList(), arr(dz("/artist", query)).map { artist(it) }, null)
            "soundcloud" -> DzResult(emptyList(), emptyList(), emptyList(), "SoundCloud necesita el teléfono conectado.")
            else -> DzResult(arr(dz("", query)).map { track(it) }, emptyList(), emptyList(), null)
        }
    }

    suspend fun album(a: DzAlbum): DzResult = safe {
        val j = get("https://api.deezer.com/album/${a.id}")
        val tracks = arr(j.optJSONObject("tracks") ?: JSONObject())
            .map { track(it, j.optString("title"), j.optString("cover_medium"), j.optString("cover_xl")) }
        DzResult(tracks, emptyList(), emptyList(), null)
    }

    /** Populares y discografía; con [id] 0 busca al artista por nombre. */
    suspend fun artist(id: Long, name: String): DzResult = safe {
        val artistId = id.takeIf { it > 0 } ?: arr(dz("/artist", name))
            .firstOrNull { com.kevshupp.kevmusic.desktop.YtMusic.sameText(it.optString("name"), name) }?.getLong("id")
            ?: return@safe DzResult(emptyList(), emptyList(), emptyList(), "No se encontró a $name en Deezer")
        val top = arr(get("https://api.deezer.com/artist/$artistId/top?limit=20")).map { track(it) }
        val albums = arr(get("https://api.deezer.com/artist/$artistId/albums?limit=100")).map { album(it, name) }
            .sortedByDescending { it.year }
        DzResult(top, albums, emptyList(), null)
    }

    // ---------- Descargas ----------

    fun download(tracks: List<DzTrack>) {
        val busy = _downloads.value.filter { it.status != DlStatus.Failed }.map { it.track.id }.toSet()
        tracks.filter { it.id !in busy }.forEach { t ->
            set(t, DlStatus.Queued)
            queue.trySend(t)
        }
    }

    fun clearFinished() {
        _downloads.value = _downloads.value.filter { it.status != DlStatus.Done && it.status != DlStatus.Failed }
    }

    private fun set(t: DzTrack, status: DlStatus, reason: String = "") {
        val item = DownloadItem(t, status, if (status == DlStatus.Done) 1f else 0f, reason)
        val list = _downloads.value
        _downloads.value = if (list.any { it.track.id == t.id }) list.map { if (it.track.id == t.id) item else it } else list + item
    }

    private suspend fun process(t: DzTrack) {
        set(t, DlStatus.Requesting)
        runCatching {
            if (!TelegramPc.ready) throw IllegalStateException("Telegram del PC no está listo")
            val got = TelegramPc.request(t.id, t.durationSec)
            set(t, DlStatus.Downloading)
            val safe = YtMusic::safeName
            val folder = File(Settings.localFolders.firstOrNull()?.let(::File) ?: LocalLibrary.defaultFolder(),
                "${safe(t.artist.substringBefore(","))}/${safe(t.album.ifBlank { t.title })}")
            folder.mkdirs()
            val ext = got.extension.ifBlank { "mp3" }
            var dest = File(folder, "${safe(t.artist)} - ${safe(t.title)}.$ext")
            var n = 2
            while (dest.exists()) dest = File(folder, "${safe(t.artist)} - ${safe(t.title)} ($n).$ext").also { n++ }
            got.copyTo(dest)
            got.delete()
        }.onSuccess {
            set(t, DlStatus.Done)
            onSaved()
        }.onFailure { set(t, DlStatus.Failed, it.message ?: "Error") }
    }
}
