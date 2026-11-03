package com.kevshupp.kevmusicplayer.data.cast

import android.content.Context
import android.net.ConnectivityManager
import com.kevshupp.kevmusicplayer.data.AppDatabase
import com.kevshupp.kevmusicplayer.playback.getPhysicalPath
import com.kevshupp.kevmusicplayer.ui.screens.loadAlbumArtBitmap
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.security.SecureRandom

/**
 * Da al Chromecast el audio y las carátulas, que solo existen en el teléfono. Vive mientras dura la sesión, en un
 * puerto libre y con una ruta aleatoria: quien esté en el mismo Wi-Fi no puede pedir canciones sin conocerla.
 */
class CastMediaServer(private val context: Context) : NanoHTTPD(0) {
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** La IPv4 de la red actual, o null sin Wi-Fi ni red local. */
    private fun host(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    fun baseUrl(): String? = host()?.let { "http://$it:$listeningPort/$token" }

    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.trim('/').split('/')
        if (parts.size != 3 || parts[0] != token) return notFound()
        val id = parts[2].substringBefore('.').toLongOrNull() ?: return notFound()
        val song = runBlocking(Dispatchers.IO) { AppDatabase.getDatabase(context).audioDao().getAudioFileById(id) } ?: return notFound()
        return try {
            when (parts[1]) {
                "song" -> serveFile(File(getPhysicalPath(context, song.id, song.uriString) ?: return notFound()), session.headers["range"], session.method)
                "art" -> serveArt(song.uriString)
                else -> notFound()
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error")
        }
    }

    private fun serveFile(file: File, range: String?, method: Method): Response {
        if (!file.isFile) return notFound()
        val mime = mimeOf(file.name) ?: "application/octet-stream"
        val length = file.length()
        val (start, end) = parseRange(range, length) ?: (0L to length - 1)
        val input = FileInputStream(file)
        input.channel.position(start)
        val size = end - start + 1
        val status = if (range != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        // Las teles DLNA suelen preguntar con HEAD antes de pedir el audio
        val body = if (method == Method.HEAD) ByteArrayInputStream(ByteArray(0)).also { input.close() } else input
        return newFixedLengthResponse(status, mime, body, size).apply {
            addHeader("transferMode.dlna.org", "Streaming")
            addHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
            addHeader("Accept-Ranges", "bytes")
            addHeader("Access-Control-Allow-Origin", "*")
            if (range != null) addHeader("Content-Range", "bytes $start-$end/$length")
        }
    }

    private fun serveArt(uri: String): Response {
        val bitmap = loadAlbumArtBitmap(context, uri) ?: return notFound()
        val out = ByteArrayOutputStream()
        // Nunca reciclar: viene de la caché de carátulas
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
            .apply { addHeader("Access-Control-Allow-Origin", "*") }
    }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")

    companion object {
        /** Formatos que el Chromecast reproduce; el resto se salta, sin convertirlo. */
        fun mimeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac", "mp4" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> null
        }

        /** `bytes=a-b`, `bytes=a-` o `bytes=-n`; null si no se entiende o queda fuera del archivo. */
        fun parseRange(header: String?, length: Long): Pair<Long, Long>? {
            val m = Regex("""bytes=(\d*)-(\d*)""").matchEntire(header?.trim() ?: return null) ?: return null
            val (a, b) = m.destructured
            if (length <= 0 || (a.isEmpty() && b.isEmpty())) return null
            val start = if (a.isEmpty()) (length - b.toLong()).coerceAtLeast(0) else a.toLong()
            val end = if (a.isEmpty() || b.isEmpty()) length - 1 else b.toLong().coerceAtMost(length - 1)
            return if (start > end) null else start to end
        }
    }
}
