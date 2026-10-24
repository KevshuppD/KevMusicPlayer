package com.kevshupp.kevmusicplayer.data.cloud

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import com.kevshupp.kevmusicplayer.data.telegram.LibraryMatcher
import java.security.MessageDigest

/**
 * Listas de reproducción sincronizadas en tiempo real con Firestore, un documento por lista en
 * `users/<uid>/playlists/<id>`, como en el Diario: cada cambio se escribe solo (sin subir la copia entera) y
 * un oyente aplica al instante los cambios hechos en otros dispositivos. Firestore guarda los cambios sin
 * conexión y los sube al volver la red.
 *
 * Las canciones se guardan por clave (artista|título normalizados) y no por el `_id` de MediaStore, que es
 * distinto en cada teléfono. Las claves que no existen en este dispositivo se conservan en la nube.
 * Las listas borradas quedan como lápida (`deleted = true`) para que otro dispositivo no las resucite.
 */
class RealtimePlaylistSync(
    private val context: Context,
    private val library: () -> List<AudioFile>,
    private val onRemoteApplied: () -> Unit
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val prefs = context.getSharedPreferences("playlists_prefs", Context.MODE_PRIVATE)
    private val syncPrefs = context.getSharedPreferences("cloud_sync_state", Context.MODE_PRIVATE)
    private var registration: ListenerRegistration? = null
    private var uid: String? = null
    private var lastSnapshot: QuerySnapshot? = null

    val isActive: Boolean get() = uid != null

    fun start(userId: String) {
        if (uid == userId && registration != null) return
        stop()
        uid = userId
        registration = collection(userId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                TelemetryLogger.logError(context, "RealtimeSync", "Oyente de listas: ${error.message}", error)
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            lastSnapshot = snapshot
            if (!syncPrefs.getBoolean(migratedKey(userId), false)) {
                // Sin la caché del servidor no se sabe qué hay en la nube: se espera para no duplicar ni perder nada
                if (snapshot.metadata.isFromCache) return@addSnapshotListener
                migrateLocal(userId, snapshot)
                return@addSnapshotListener
            }
            apply(snapshot)
        }
    }

    fun stop() {
        registration?.remove()
        registration = null
        uid = null
        lastSnapshot = null
    }

    /**
     * Tras restaurar una copia de seguridad (local o la antigua de la nube): une sus listas con las de la nube
     * en lugar de que el oyente las sustituya por lo que ya había allí.
     */
    fun mergeLocalIntoCloud() {
        val id = uid ?: return
        syncPrefs.edit().putBoolean(migratedKey(id), false).apply()
        stop()
        start(id)
    }

    /** Vuelve a aplicar la última versión de la nube (tras un escaneo que añadió o cambió canciones). */
    fun reapply() {
        val snapshot = lastSnapshot ?: return
        val id = uid ?: return
        if (syncPrefs.getBoolean(migratedKey(id), false)) apply(snapshot)
    }

    fun onPlaylistCreated(name: String) = write(name) { ref ->
        ref.set(mapOf("name" to name, "deleted" to false, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
    }

    fun onSmartPlaylistCreated(name: String, configJson: String) = write(name) { ref ->
        ref.set(
            mapOf("name" to name, "smart" to configJson, "deleted" to false, "updatedAt" to FieldValue.serverTimestamp()),
            SetOptions.merge()
        )
    }

    fun onSongsAdded(name: String, songs: List<AudioFile>) {
        val keys = songs.map(::songKey).filter { it.isNotBlank() }
        if (keys.isEmpty()) return
        write(name) { ref ->
            ref.set(
                mapOf(
                    "name" to name,
                    "deleted" to false,
                    "songs" to FieldValue.arrayUnion(*keys.toTypedArray()),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
        }
    }

    fun onSongsRemoved(name: String, songs: List<AudioFile>) {
        val keys = songs.map(::songKey).filter { it.isNotBlank() }
        if (keys.isEmpty()) return
        write(name) { ref ->
            ref.set(
                mapOf("songs" to FieldValue.arrayRemove(*keys.toTypedArray()), "updatedAt" to FieldValue.serverTimestamp()),
                SetOptions.merge()
            )
        }
    }

    fun onPlaylistDeleted(name: String) = write(name) { ref ->
        ref.set(
            mapOf("name" to name, "deleted" to true, "songs" to emptyList<String>(), "updatedAt" to FieldValue.serverTimestamp()),
            SetOptions.merge()
        )
    }

    private fun write(name: String, block: (com.google.firebase.firestore.DocumentReference) -> com.google.android.gms.tasks.Task<Void>) {
        val id = uid ?: return
        if (!syncPrefs.getBoolean(migratedKey(id), false)) return
        block(collection(id).document(docId(name))).addOnFailureListener { e ->
            TelemetryLogger.logError(context, "RealtimeSync", "No se pudo guardar la lista $name: ${e.message}", e)
        }
    }

    /**
     * Primera vez de esta cuenta en este dispositivo: une las listas locales con las de la nube (nunca borra).
     * Una lista local que en la nube está borrada no se resucita. Las escrituras llegan al oyente al instante,
     * que entonces aplica el resultado.
     */
    private fun migrateLocal(userId: String, snapshot: QuerySnapshot) {
        val remote = snapshot.documents.associateBy { it.getString("name").orEmpty() }
        val byId = library().associateBy { it.id }
        val batch = firestore.batch()
        var writes = 0
        (prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()).forEach { name ->
            val doc = remote[name]
            if (doc?.getBoolean("deleted") == true) return@forEach
            val keys = storedIds(name).mapNotNull { byId[it] }.map(::songKey).filter { it.isNotBlank() }.distinct()
            val data = mutableMapOf<String, Any>("name" to name, "deleted" to false, "updatedAt" to FieldValue.serverTimestamp())
            if (keys.isNotEmpty()) data["songs"] = FieldValue.arrayUnion(*keys.toTypedArray())
            batch.set(collection(userId).document(docId(name)), data, SetOptions.merge())
            writes++
        }
        (prefs.getStringSet("smart_playlist_names", emptySet()) ?: emptySet()).forEach { name ->
            if (remote[name]?.getBoolean("deleted") == true) return@forEach
            val config = prefs.getString("smart_playlist_config_$name", null) ?: return@forEach
            batch.set(
                collection(userId).document(docId(name)),
                mapOf("name" to name, "smart" to config, "deleted" to false, "updatedAt" to FieldValue.serverTimestamp()),
                SetOptions.merge()
            )
            writes++
        }
        syncPrefs.edit().putBoolean(migratedKey(userId), true).apply()
        TelemetryLogger.logInfo(context, "RealtimeSync", "Listas locales unidas con la nube: $writes")
        if (writes == 0) apply(snapshot) else batch.commit().addOnFailureListener { e ->
            // Si la unión no llega a la nube se repetirá la próxima vez
            syncPrefs.edit().putBoolean(migratedKey(userId), false).apply()
            TelemetryLogger.logError(context, "RealtimeSync", "No se pudieron subir las listas locales: ${e.message}", e)
        }
    }

    /** Reescribe las listas locales con lo que dice la nube. Sin biblioteca cargada no hace nada. */
    private fun apply(snapshot: QuerySnapshot) {
        val songs = library()
        if (songs.isEmpty()) return
        val byKey = HashMap<String, MutableList<AudioFile>>()
        songs.forEach { byKey.getOrPut(songKey(it)) { ArrayList(1) } += it }
        val aliases = keyAliases(context)
        // Solo para el respaldo de artista desconocido: canciones por título
        val byTitle by lazy { songs.groupBy { songKey(it).substringAfter('|') } }
        fun resolve(key: String): List<AudioFile>? =
            byKey[key] ?: aliases[key]?.let { byKey[it] } ?: run {
                // Clave escrita cuando la canción no tenía artista ("unknown|título"): si ahora hay una sola canción
                // con ese título, es ella con el artista corregido (p. ej. por Normalizar biblioteca)
                val artist = key.substringBefore('|', "")
                if (artist !in UNKNOWN_ARTIST_KEYS) null else byTitle[key.substringAfter('|')]?.takeIf { it.size == 1 }
            }

        val names = HashSet<String>()
        val smartNames = HashSet<String>()
        val editor = prefs.edit()
        snapshot.documents.forEach { doc ->
            val name = doc.getString("name").orEmpty()
            if (name.isBlank() || doc.getBoolean("deleted") == true) return@forEach
            val smart = doc.getString("smart")
            if (smart != null) {
                smartNames += name
                editor.putString("smart_playlist_config_$name", smart)
                return@forEach
            }
            names += name
            val previous = storedIds(name).toHashSet()
            @Suppress("UNCHECKED_CAST")
            val keys = (doc.get("songs") as? List<String>).orEmpty()
            val ids = keys.mapNotNull { key ->
                val matches = resolve(key) ?: return@mapNotNull null
                // Con canciones repetidas se mantiene la que ya estaba en la lista
                (matches.firstOrNull { it.id in previous } ?: matches.first()).id
            }.distinct()
            editor.putString("playlist_$name", ids.joinToString(","))
        }
        val oldNames = prefs.getStringSet("playlist_names", emptySet()) ?: emptySet()
        (oldNames - names).forEach { editor.remove("playlist_$it").remove("playlist_cover_$it") }
        val oldSmart = prefs.getStringSet("smart_playlist_names", emptySet()) ?: emptySet()
        (oldSmart - smartNames).forEach { editor.remove("smart_playlist_config_$it") }
        editor.putStringSet("playlist_names", names).putStringSet("smart_playlist_names", smartNames).apply()
        onRemoteApplied()
    }

    private fun storedIds(name: String): List<Long> =
        (prefs.getString("playlist_$name", "") ?: "").split(",").mapNotNull { it.trim().toLongOrNull() }

    private fun collection(userId: String) =
        firestore.collection("users").document(userId).collection("playlists")

    private fun migratedKey(userId: String) = "realtime_playlists_$userId"

    companion object {
        private const val ALIAS_PREFS = "song_key_aliases"
        private val UNKNOWN_ARTIST_KEYS = setOf("", "unknown", "unknownartist", "desconocido", "artistadesconocido")

        /**
         * Claves viejas → nuevas de canciones a las que se les cambió el artista o el título (Normalizar biblioteca).
         * Se guardan **antes** de reescribir el archivo: si la app se cierra antes de volver a unir con la nube,
         * el oyente sigue encontrando la canción por la clave que aún tiene la lista en la nube.
         */
        fun recordKeyChanges(context: Context, changes: Map<String, String>) {
            val real = changes.filter { (old, new) -> old.isNotBlank() && new.isNotBlank() && old != new }
            if (real.isEmpty()) return
            val editor = context.getSharedPreferences(ALIAS_PREFS, Context.MODE_PRIVATE).edit()
            real.forEach { (old, new) -> editor.putString(old, new) }
            editor.commit()
        }

        fun keyAliases(context: Context): Map<String, String> =
            context.getSharedPreferences(ALIAS_PREFS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

        fun songKey(song: AudioFile): String {
            val title = LibraryMatcher.normalizeTitle(song.title)
            if (title.isBlank()) return ""
            return LibraryMatcher.normalize(song.artist) + "|" + title
        }

        fun docId(name: String): String =
            MessageDigest.getInstance("SHA-1").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }
}
