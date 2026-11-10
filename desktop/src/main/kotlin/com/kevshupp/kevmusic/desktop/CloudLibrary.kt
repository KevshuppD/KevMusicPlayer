package com.kevshupp.kevmusic.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Biblioteca del teléfono leída de la nube, para verla y escucharla en el PC con el teléfono apagado.
 *
 * - Canciones: `library_songs` de la copia comprimida `users/<uid>/backups/latest` (`payloadGzip`, Base64 de GZIP),
 *   con los **mismos ids** que el teléfono: las carátulas ya guardadas en `art_cache` y las listas del teléfono
 *   en caché siguen valiendo. Solo trae canciones si el teléfono sincroniza estadísticas (`includeStats`).
 * - Listas: `users/<uid>/playlists` (todos los documentos) (tiempo real), con canciones por clave artista|título; se traducen a ids
 *   con [CloudSession.songKey]. Las lápidas (`deleted`) se omiten.
 *
 * Se guarda en `<caché>/cloud_library.json` y se muestra al abrir mientras llega la nueva.
 */
object CloudLibrary {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheFile = File(Platform.cacheDir, "cloud_library.json")
    private val _library = MutableStateFlow<Library?>(null)
    val library: StateFlow<Library?> = _library
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private var job: Job? = null
    private var loadedFor: String? = null

    init {
        runCatching {
            val j = JSONObject(cacheFile.readText())
            if (j.optString("uid") == CloudSession.uid) _library.value = fromJson(j).also { it.warm() }
        }
        scope.launch {
            // Al iniciar o cambiar de sesión se vuelve a leer; al cerrarla se olvida
            CloudSession.account.collect { a ->
                if (a == null) { _library.value = null; loadedFor = null; cacheFile.delete() }
                else if (a.uid != loadedFor) refresh()
            }
        }
    }

    fun refresh() {
        if (job?.isActive == true) return
        val uid = CloudSession.uid ?: return
        job = scope.launch {
            _loading.value = true
            try {
                val songs = loadSongs() ?: _library.value?.songs ?: emptyList()
                val playlists = loadPlaylists(songs)
                val lib = Library("Nube", songs, playlists).also { it.warm() }
                _library.value = lib
                loadedFor = uid
                runCatching { cacheFile.parentFile.mkdirs(); cacheFile.writeText(toJson(uid, lib).toString()) }
            } catch (e: Exception) {
                System.err.println("CloudLibrary: ${e.message}")
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun loadSongs(): List<Song>? {
        val doc = CloudSession.getDocument("backups/latest") ?: return null
        val gz = doc.optJSONObject("fields")?.optJSONObject("payloadGzip")?.optString("stringValue").orEmpty()
        if (gz.isEmpty()) return null
        val text = GZIPInputStream(Base64.getMimeDecoder().decode(gz).inputStream()).bufferedReader().use { it.readText() }
        val arr = JSONObject(text).optJSONArray("library_songs") ?: return null
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Song(
                o.getLong("id"), o.optString("title"), o.optString("artist"), o.optString("album"),
                o.optLong("duration"), o.optInt("track"), o.opt("year")?.toString()?.takeIf { it != "0" && it != "null" }.orEmpty(),
                o.optString("genre"), o.optInt("playCount"), o.optLong("dateAdded"), o.optLong("lastPlayed")
            )
        }
    }

    private suspend fun loadPlaylists(songs: List<Song>): List<Playlist> {
        val byKey = songs.groupBy { CloudSession.songKey(it.artist, it.title) }
        val out = mutableListOf<Playlist>()
        var pageToken: String? = null
        do {
            val page = CloudSession.listCollection("playlists", pageToken) ?: break
            page.optJSONArray("documents")?.let { docs ->
                for (i in 0 until docs.length()) {
                    val f = docs.getJSONObject(i).optJSONObject("fields") ?: continue
                    if (f.optJSONObject("deleted")?.optBoolean("booleanValue") == true) continue
                    // Las inteligentes se calculan en el teléfono: sin él no hay forma de evaluarlas
                    if (f.has("smart")) continue
                    val name = f.optJSONObject("name")?.optString("stringValue").orEmpty()
                    if (name.isEmpty()) continue
                    val keys = f.optJSONObject("songs")?.optJSONObject("arrayValue")?.optJSONArray("values")
                    val ids = (0 until (keys?.length() ?: 0)).mapNotNull { k ->
                        byKey[keys!!.getJSONObject(k).optString("stringValue")]?.firstOrNull()?.id
                    }.distinct()
                    out += Playlist(name, ids)
                }
            }
            pageToken = page.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        // Favoritos primero, como en el teléfono
        return out.sortedWith(compareBy({ it.name != "Favoritos" }, { it.name.lowercase() }))
    }

    private fun toJson(uid: String, lib: Library) = JSONObject().put("uid", uid)
        .put("songs", JSONArray().apply {
            lib.songs.forEach { s ->
                put(JSONObject().put("id", s.id).put("title", s.title).put("artist", s.artist).put("album", s.album)
                    .put("duration", s.duration).put("track", s.track).put("year", s.year).put("genre", s.genre)
                    .put("plays", s.plays).put("added", s.added).put("last", s.lastPlayed))
            }
        })
        .put("playlists", JSONArray().apply { lib.playlists.forEach { p -> put(JSONObject().put("name", p.name).put("ids", JSONArray(p.songIds))) } })

    private fun fromJson(j: JSONObject): Library {
        val arr = j.getJSONArray("songs")
        val songs = (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Song(o.getLong("id"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optLong("duration"),
                o.optInt("track"), o.optString("year"), o.optString("genre"), o.optInt("plays"), o.optLong("added"), o.optLong("last"))
        }
        val pls = j.optJSONArray("playlists") ?: JSONArray()
        val playlists = (0 until pls.length()).map { pls.getJSONObject(it) }.map { o ->
            val ids = o.getJSONArray("ids"); Playlist(o.getString("name"), (0 until ids.length()).map { ids.getLong(it) })
        }
        return Library("Nube", songs, playlists)
    }
}
