package com.kevshupp.kevmusic.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/** Cuenta de Google con la que el PC entra en la nube de KevMusicPlayer. */
data class CloudAccount(val uid: String, val email: String, val name: String, val photo: String)

/**
 * Sesión propia de Firebase en el PC, con la misma cuenta que el teléfono y sin SDK (API REST).
 *
 * El teléfono no puede entregar su sesión de Firebase (el refresh token no es público en Android), así que envía
 * un ID token de Google recién emitido (`cloudLink`, protocolo 7) y el PC lo cambia por una sesión suya con
 * `accounts:signInWithIdp`. Después se renueva sola con el refresh token, también con el teléfono apagado.
 * Se guarda en `<config>/cloud.json` (permisos 600).
 */
object CloudSession {
    // Del google-services.json de la app (son públicos: los protegen las reglas de Firestore)
    private const val API_KEY = "AIzaSyApyX3cqIc5wnPy1t1Y2BiHlimBBp-Mk7A"
    private const val PROJECT = "kevmusicplayer"
    private const val DOCS = "projects/$PROJECT/databases/(default)/documents"

    private val file = File(Config.dir, "cloud.json")
    private val pendingFile = File(Config.dir, "cloud_pending.json")
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _account = MutableStateFlow<CloudAccount?>(null)
    val account: StateFlow<CloudAccount?> = _account
    /** Último error al enlazar o renovar (para Ajustes); null si todo bien. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var refreshToken: String? = null
    /** "Incluir estadísticas" del teléfono: si está apagado, el PC tampoco sube reproducciones. */
    @Volatile private var syncStats = true
    @Volatile private var idToken: String? = null
    @Volatile private var idTokenExpiry = 0L

    init {
        runCatching {
            val j = JSONObject(file.readText())
            refreshToken = j.getString("refreshToken")
            syncStats = j.optBoolean("stats", true)
            _account.value = CloudAccount(j.getString("uid"), j.optString("email"), j.optString("name"), j.optString("photo"))
        }
    }

    val uid: String? get() = _account.value?.uid

    /** El teléfono informó su cuenta (o ninguna). Devuelve true si hace falta pedirle un token nuevo. */
    fun needsLink(phoneUid: String, stats: Boolean): Boolean {
        if (phoneUid.isEmpty()) return false
        if (phoneUid == uid) {
            if (stats != syncStats) { syncStats = stats; save() }
            return false
        }
        syncStats = stats
        return true
    }

    /** Cambia el ID token de Google que mandó el teléfono por una sesión de Firebase del PC. */
    fun link(googleIdToken: String, email: String, name: String, photo: String) = scope.launch {
        if (googleIdToken.isBlank()) { _error.value = "El teléfono no pudo renovar la sesión de Google."; return@launch }
        mutex.withLock {
            runCatching {
                val body = JSONObject()
                    .put("postBody", "id_token=$googleIdToken&providerId=google.com")
                    .put("requestUri", "http://localhost")
                    .put("returnSecureToken", true)
                    .put("returnIdpCredential", true)
                val r = post("https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$API_KEY", body.toString().toRequestBody(json))
                refreshToken = r.getString("refreshToken")
                idToken = r.getString("idToken")
                idTokenExpiry = System.currentTimeMillis() + (r.optString("expiresIn", "3600").toLong() - 120) * 1000
                _account.value = CloudAccount(
                    r.getString("localId"), r.optString("email", email),
                    name.ifBlank { r.optString("displayName") }, photo.ifBlank { r.optString("photoUrl") }
                )
                _error.value = null
                save()
            }.onFailure { _error.value = "No se pudo iniciar sesión en la nube: ${it.message}" }
        }
        flushPending()
    }

    fun signOut() {
        refreshToken = null; idToken = null; idTokenExpiry = 0
        _account.value = null
        _error.value = null
        file.delete(); pendingFile.delete()
    }

    /** ID token de Firebase vigente (lo renueva si le quedan menos de 2 min). */
    suspend fun token(): String? = mutex.withLock {
        val rt = refreshToken ?: return null
        idToken?.takeIf { System.currentTimeMillis() < idTokenExpiry }?.let { return it }
        runCatching {
            val r = post(
                "https://securetoken.googleapis.com/v1/token?key=$API_KEY",
                FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", rt).build()
            )
            idToken = r.getString("id_token")
            idTokenExpiry = System.currentTimeMillis() + (r.optString("expires_in", "3600").toLong() - 120) * 1000
            r.optString("refresh_token").takeIf { it.isNotBlank() && it != rt }?.let { refreshToken = it; save() }
            _error.value = null
            idToken
        }.getOrElse { e ->
            // TOKEN_EXPIRED / USER_DISABLED / INVALID_REFRESH_TOKEN: la sesión ya no sirve (cerrada en Google)
            if (e is CloudHttpException && e.code == 400) {
                _error.value = "La sesión de la nube caducó. Conecta el teléfono para renovarla."
                refreshToken = null; idToken = null; _account.value = null; file.delete()
            } else _error.value = "Sin conexión con la nube: ${e.message}"
            null
        }
    }

    // ---------- Reproducciones ----------

    /**
     * Suma una reproducción del PC en `users/<uid>/songs/<id>`, como `RealtimeSongSync.recordPlay()` del teléfono
     * (incremento atómico, así se suman a las del teléfono, que las recibe por su oyente). Sin conexión queda
     * en `cloud_pending.json` y se envía con la siguiente.
     */
    fun recordPlay(artist: String, title: String, at: Long) {
        if (uid == null || !syncStats) return
        val key = songKey(artist, title)
        if (key.isBlank()) return
        scope.launch {
            synchronized(pendingFile) {
                val arr = readPending().put(JSONObject().put("key", key).put("artist", artist).put("title", title).put("at", at))
                runCatching { pendingFile.writeText(arr.toString()) }
            }
            flushPending()
        }
    }

    private suspend fun flushPending() {
        val id = uid ?: return
        val items = synchronized(pendingFile) { readPending() }
        if (items.length() == 0) return
        val token = token() ?: return
        val writes = JSONArray()
        // Varias reproducciones de la misma canción: un solo documento con la suma
        (0 until items.length()).map { items.getJSONObject(it) }.groupBy { it.getString("key") }.forEach { (key, plays) ->
            val last = plays.maxBy { it.getLong("at") }
            writes.put(
                JSONObject()
                    .put("update", JSONObject().put("name", "$DOCS/users/$id/songs/${docId(key)}").put("fields", JSONObject()
                        .put("key", str(key)).put("artist", str(last.getString("artist"))).put("title", str(last.getString("title")))
                        .put("lastPlayed", JSONObject().put("integerValue", last.getLong("at").toString()))))
                    .put("updateMask", JSONObject().put("fieldPaths", JSONArray(listOf("key", "artist", "title", "lastPlayed"))))
                    .put("updateTransforms", JSONArray()
                        .put(JSONObject().put("fieldPath", "playCount").put("increment", JSONObject().put("integerValue", plays.size.toString())))
                        .put(JSONObject().put("fieldPath", "updatedAt").put("setToServerValue", "REQUEST_TIME")))
            )
        }
        runCatching {
            post("https://firestore.googleapis.com/v1/$DOCS:commit", JSONObject().put("writes", writes).toString().toRequestBody(json), token)
            synchronized(pendingFile) {
                // Solo se quitan las enviadas: pudo llegar otra mientras tanto
                val now = readPending()
                val rest = JSONArray()
                for (i in items.length() until now.length()) rest.put(now.get(i))
                runCatching { if (rest.length() == 0) pendingFile.delete() else pendingFile.writeText(rest.toString()) }
            }
        }.onFailure { _error.value = "Reproducciones sin subir a la nube: ${it.message}" }
    }

    private fun readPending(): JSONArray = runCatching { JSONArray(pendingFile.readText()) }.getOrDefault(JSONArray())

    // ---------- Firestore (lectura, para la biblioteca de la nube) ----------

    /** Documento de Firestore en formato REST (`fields`), o null si no existe. */
    suspend fun getDocument(path: String): JSONObject? {
        val id = uid ?: return null
        val token = token() ?: return null
        return runCatching { get("https://firestore.googleapis.com/v1/$DOCS/users/$id/$path", token) }
            .getOrElse { if (it is CloudHttpException && it.code == 404) null else throw it }
    }

    // ---------- Utilidades ----------

    private fun str(v: String) = JSONObject().put("stringValue", v)

    private fun save() {
        val a = _account.value ?: return
        val rt = refreshToken ?: return
        runCatching {
            Config.dir.mkdirs()
            file.writeText(JSONObject().put("uid", a.uid).put("email", a.email).put("name", a.name).put("photo", a.photo)
                .put("refreshToken", rt).put("stats", syncStats).toString(2))
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
        }
    }

    class CloudHttpException(val code: Int, message: String) : Exception(message)

    private fun post(url: String, body: okhttp3.RequestBody, bearer: String? = null): JSONObject =
        exec(Request.Builder().url(url).post(body).apply { bearer?.let { header("Authorization", "Bearer $it") } }.build())

    private fun get(url: String, bearer: String): JSONObject =
        exec(Request.Builder().url(url).header("Authorization", "Bearer $bearer").build())

    private fun exec(req: Request): JSONObject = http.newCall(req).execute().use { r ->
        val text = r.body?.string().orEmpty()
        if (!r.isSuccessful) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("HTTP ${r.code}")
            throw CloudHttpException(r.code, msg)
        }
        JSONObject(text.ifBlank { "{}" })
    }

    // Copia exacta de RealtimePlaylistSync.songKey/docId y LibraryMatcher.normalize/normalizeTitle del teléfono:
    // si difieren, la reproducción del PC cae en otro documento
    private val marks = Regex("\\p{Mn}+")
    private val nonAlnum = Regex("[^\\p{L}\\p{N}]+")
    private val extras = Regex(
        "\\s*[(\\[]\\s*(feat|ft|with|w\\.|from|\\d{4}\\s+remaster|remaster|remastered|deluxe|explicit|clean|radio edit|bonus|version|live|en vivo)(\\b|\\s)[^)\\]]*[)\\]]" +
            "|\\s+-\\s+(remaster|remastered|radio edit|live|en vivo|bonus)\\b.*$" +
            "|\\s+(feat|ft)\\.?\\s+.*$",
        RegexOption.IGNORE_CASE
    )
    private fun normalize(text: String) = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(marks, "").replace(nonAlnum, "")

    fun songKey(artist: String, title: String): String {
        val t = normalize(title.replace(extras, ""))
        return if (t.isBlank()) "" else normalize(artist) + "|" + t
    }

    fun docId(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
