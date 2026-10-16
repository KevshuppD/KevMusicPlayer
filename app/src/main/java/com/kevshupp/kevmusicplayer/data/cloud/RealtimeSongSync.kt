package com.kevshupp.kevmusicplayer.data.cloud

import android.content.Context
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.kevshupp.kevmusicplayer.data.AppDatabase
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Reproducciones y letras sincronizadas en tiempo real, un documento por canción en `users/<uid>/songs/<id>`
 * (id = SHA-1 de la clave artista|título de [RealtimePlaylistSync.songKey]).
 *
 * - Cada reproducción suma 1 con `FieldValue.increment`, así las de dos teléfonos se suman en lugar de pisarse.
 * - El oyente solo pide los documentos cambiados desde la última vez (`updatedAt` > marca guardada), para no
 *   volver a leer toda la biblioteca en cada arranque.
 * - La primera vez por cuenta y dispositivo une lo local con la nube tomando el **máximo** de reproducciones
 *   (sumar contaría dos veces el historial de un teléfono restaurado desde la misma copia).
 */
object RealtimeSongSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var registration: ListenerRegistration? = null
    private var startJob: kotlinx.coroutines.Job? = null
    private var uid: String? = null
    private var syncStats = true
    private var syncLyrics = true

    /** Lo asigna el ViewModel: recarga estadísticas y letras en memoria tras aplicar cambios de la nube. */
    @Volatile var onRemoteApplied: ((lyricsChanged: Set<Long>) -> Unit)? = null

    /** Lo asigna el ViewModel: otro dispositivo reinició las estadísticas, hay que borrar las de este por completo. */
    @Volatile var onRemoteStatsReset: (() -> Unit)? = null

    private var resetRegistration: ListenerRegistration? = null
    private fun resetSeenKey(uid: String) = "realtime_stats_reset_seen_$uid"
    private fun metaDoc(uid: String) = firestore().collection("users").document(uid).collection("meta").document("stats")

    private fun firestore() = FirebaseFirestore.getInstance()
    private fun prefs(context: Context) = context.getSharedPreferences("cloud_sync_state", Context.MODE_PRIVATE)
    private fun migratedKey(uid: String) = "realtime_songs_$uid"
    private fun lastSeenKey(uid: String) = "realtime_songs_seen_$uid"
    private fun collection(uid: String) = firestore().collection("users").document(uid).collection("songs")

    fun start(context: Context, userId: String, stats: Boolean, lyrics: Boolean) {
        val app = context.applicationContext
        if (uid == userId && syncStats == stats && syncLyrics == lyrics && (registration != null || startJob?.isActive == true)) return
        stop()
        uid = userId
        syncStats = stats
        syncLyrics = lyrics
        if (!stats && !lyrics) return
        startJob = scope.launch {
            if (!prefs(app).getBoolean(migratedKey(userId), false)) {
                try {
                    migrate(app, userId)
                } catch (e: Exception) {
                    TelemetryLogger.logError(app, "RealtimeSync", "No se pudo unir estadísticas y letras con la nube: ${e.message}", e)
                    return@launch
                }
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (uid == userId && registration == null) listen(app, userId)
            }
        }
    }

    fun stop() {
        resetRegistration?.remove()
        resetRegistration = null
        startJob?.cancel()
        startJob = null
        registration?.remove()
        registration = null
        uid = null
    }

    /** Tras restaurar una copia de seguridad: vuelve a unir lo local (que ahora es la copia) con la nube. */
    fun mergeLocalIntoCloud(context: Context) {
        val id = uid ?: return
        prefs(context).edit().putBoolean(migratedKey(id), false).apply()
        val (s, l) = syncStats to syncLyrics
        stop()
        start(context, id, s, l)
    }

    private fun activeUid(context: Context): String? {
        // El servicio de reproducción puede vivir sin la pantalla: entonces se toma la sesión de Firebase
        val id = uid ?: FirebaseAuth.getInstance().currentUser?.uid ?: return null
        return id.takeIf { prefs(context).getBoolean(migratedKey(it), false) }
    }

    /** Lo llama el servicio después de sumar la reproducción en Room. */
    fun recordPlay(context: Context, song: AudioFile, timestamp: Long) {
        if (uid != null && !syncStats) return
        val id = activeUid(context) ?: return
        val key = RealtimePlaylistSync.songKey(song)
        if (key.isBlank()) return
        collection(id).document(RealtimePlaylistSync.docId(key)).set(
            mapOf(
                "key" to key, "artist" to song.artist, "title" to song.title,
                "playCount" to FieldValue.increment(1), "lastPlayed" to timestamp,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).addOnFailureListener { e -> TelemetryLogger.logError(context, "RealtimeSync", "Reproducción no sincronizada: ${e.message}", e) }
    }

    fun recordLyrics(context: Context, song: AudioFile, lyrics: String?, translated: String?) {
        if (!syncLyrics) return
        val id = activeUid(context) ?: return
        val key = RealtimePlaylistSync.songKey(song)
        if (key.isBlank()) return
        collection(id).document(RealtimePlaylistSync.docId(key)).set(
            mapOf(
                "key" to key, "artist" to song.artist, "title" to song.title,
                "lyrics" to lyrics, "translatedLyrics" to translated,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).addOnFailureListener { e -> TelemetryLogger.logError(context, "RealtimeSync", "Letra no sincronizada: ${e.message}", e) }
    }

    /** Reinicio de estadísticas del Wrapped: pone a cero todas las canciones de la nube. */
    fun resetStats(context: Context) {
        if (!syncStats) return
        val id = activeUid(context) ?: return
        // Evento de reinicio: cada dispositivo borra todas sus estadísticas, también las de canciones sin documento
        val now = System.currentTimeMillis()
        prefs(context).edit().putLong(resetSeenKey(id), now).apply()
        metaDoc(id).set(mapOf("statsResetAt" to now), SetOptions.merge())
        scope.launch {
            try {
                val docs = collection(id).whereGreaterThan("playCount", 0).get().await().documents
                docs.chunked(400).forEach { chunk ->
                    val batch = firestore().batch()
                    chunk.forEach { batch.update(it.reference, mapOf("playCount" to 0, "lastPlayed" to 0L, "updatedAt" to FieldValue.serverTimestamp())) }
                    batch.commit().await()
                }
            } catch (e: Exception) {
                TelemetryLogger.logError(context, "RealtimeSync", "No se pudieron reiniciar las estadísticas en la nube: ${e.message}", e)
            }
        }
    }

    private suspend fun migrate(context: Context, userId: String) = mutex.withLock {
        val dao = AppDatabase.getDatabase(context).audioDao()
        val remote = collection(userId).get(com.google.firebase.firestore.Source.SERVER).await().documents
            .associateBy { it.getString("key").orEmpty() }
        val local = dao.getAllAudioFiles()
        val writes = ArrayList<Pair<String, Map<String, Any?>>>()
        val seenKeys = HashSet<String>()
        for (song in local) {
            val key = RealtimePlaylistSync.songKey(song)
            if (key.isBlank() || !seenKeys.add(key)) continue
            val doc = remote[key]
            val data = HashMap<String, Any?>()
            if (syncStats && (song.playCount > 0 || song.lastPlayed > 0)) {
                val remoteCount = doc?.getLong("playCount")?.toInt() ?: 0
                val remoteLast = doc?.getLong("lastPlayed") ?: 0L
                if (song.playCount > remoteCount) data["playCount"] = song.playCount
                if (song.lastPlayed > remoteLast) data["lastPlayed"] = song.lastPlayed
            }
            if (syncLyrics && doc?.getString("lyrics").isNullOrBlank() && !song.lyrics.isNullOrBlank()) {
                data["lyrics"] = song.lyrics
                if (!song.translatedLyrics.isNullOrBlank()) data["translatedLyrics"] = song.translatedLyrics
            }
            if (data.isEmpty()) continue
            data["key"] = key; data["artist"] = song.artist; data["title"] = song.title
            data["updatedAt"] = FieldValue.serverTimestamp()
            writes += RealtimePlaylistSync.docId(key) to data
        }
        // Lotes pequeños: las letras pueden ocupar varios KB cada una
        writes.chunked(200).forEach { chunk ->
            val batch = firestore().batch()
            chunk.forEach { (docId, data) -> batch.set(collection(userId).document(docId), data, SetOptions.merge()) }
            batch.commit().await()
        }
        // Se vuelve a leer tras subir: así se aplica el máximo ya unido y no la versión vieja de la nube
        val merged = if (writes.isEmpty()) remote.values.toList()
        else collection(userId).get(com.google.firebase.firestore.Source.SERVER).await().documents
        applyDocs(context, merged)
        prefs(context).edit()
            .putBoolean(migratedKey(userId), true)
            .putLong(resetSeenKey(userId), maxOf(prefs(context).getLong(resetSeenKey(userId), 0L), System.currentTimeMillis()))
            .putLong(lastSeenKey(userId), System.currentTimeMillis() - 60_000L)
            .apply()
        TelemetryLogger.logInfo(context, "RealtimeSync", "Estadísticas y letras unidas con la nube: ${writes.size} subidas, ${merged.size} en la nube")
    }

    private fun listen(context: Context, userId: String) {
        if (syncStats) listenResets(context, userId)
        val since = prefs(context).getLong(lastSeenKey(userId), 0L)
        registration = collection(userId)
            .whereGreaterThan("updatedAt", Timestamp(java.util.Date(since)))
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    TelemetryLogger.logError(context, "RealtimeSync", "Oyente de canciones: ${error.message}", error)
                    return@addSnapshotListener
                }
                val changed = snapshot?.documentChanges?.map { it.document } ?: return@addSnapshotListener
                if (changed.isEmpty()) return@addSnapshotListener
                scope.launch {
                    mutex.withLock { applyDocs(context, changed) }
                    val newest = changed.filter { !it.metadata.hasPendingWrites() }
                        .mapNotNull { it.getTimestamp("updatedAt")?.toDate()?.time }.maxOrNull()
                    if (newest != null && newest > prefs(context).getLong(lastSeenKey(userId), 0L)) {
                        prefs(context).edit().putLong(lastSeenKey(userId), newest).apply()
                    }
                }
            }
    }

    private fun listenResets(context: Context, userId: String) {
        resetRegistration = metaDoc(userId).addSnapshotListener { snapshot, _ ->
            val resetAt = snapshot?.getLong("statsResetAt") ?: return@addSnapshotListener
            if (resetAt <= prefs(context).getLong(resetSeenKey(userId), 0L)) return@addSnapshotListener
            prefs(context).edit().putLong(resetSeenKey(userId), resetAt).apply()
            TelemetryLogger.logInfo(context, "RealtimeSync", "Estadísticas reiniciadas desde otro dispositivo")
            scope.launch {
                mutex.withLock { AppDatabase.getDatabase(context).audioDao().resetAllPlayStats() }
                onRemoteStatsReset?.invoke()
            }
        }
    }

    /** Copia a Room lo que dice la nube para las canciones de la biblioteca con esa clave. */
    private suspend fun applyDocs(context: Context, docs: List<DocumentSnapshot>) {
        if (docs.isEmpty()) return
        val dao = AppDatabase.getDatabase(context).audioDao()
        val library = dao.getAllAudioFilesLightweight()
        if (library.isEmpty()) return
        val byKey = library.groupBy { RealtimePlaylistSync.songKey(it) }
        var statsChanged = false
        val lyricsChanged = HashSet<Long>()
        for (doc in docs) {
            val songs = byKey[doc.getString("key") ?: continue] ?: continue
            for (song in songs) {
                if (syncStats && doc.contains("playCount")) {
                    val count = doc.getLong("playCount")?.toInt() ?: 0
                    val last = doc.getLong("lastPlayed") ?: 0L
                    // Un reinicio pone ambos a cero; si no, la fecha más reciente gana
                    val newLast = if (count == 0 && last == 0L) 0L else maxOf(last, song.lastPlayed)
                    if (count != song.playCount || newLast != song.lastPlayed) {
                        dao.setPlayStats(song.id, count, newLast)
                        statsChanged = true
                    }
                }
                if (syncLyrics && doc.contains("lyrics")) {
                    val lyrics = doc.getString("lyrics")
                    val translated = doc.getString("translatedLyrics")
                    val stored = dao.getLyricsOnly(song.id)
                    if (stored != null && (stored.lyrics != lyrics || stored.translatedLyrics != translated)) {
                        dao.updateLyrics(song.id, lyrics)
                        dao.updateTranslatedLyrics(song.id, translated)
                        lyricsChanged += song.id
                    }
                }
            }
        }
        if (statsChanged || lyricsChanged.isNotEmpty()) onRemoteApplied?.invoke(lyricsChanged)
    }
}
