package com.kevshupp.kevmusicplayer.data.cast

import android.content.Context
import android.net.ConnectivityManager
import com.kevshupp.kevmusicplayer.data.AppDatabase
import com.kevshupp.kevmusicplayer.data.telegram.OnDemandTracks
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
        // Bajo demanda: el archivo es el de la caché o la biblioteca, que DlnaSession ya aseguró con ensureFile
        if (OnDemandTracks.isRemoteId(id)) {
            val deezerId = OnDemandTracks.deezerIdOf(id)
            return try {
                when (parts[1]) {
                    "song" -> serveFile(OnDemandTracks.readyFile(context, deezerId) ?: return notFound(), session.headers["range"], session.method)
                    "art" -> serveArt("kmpremote://deezer/$deezerId", session.parameters["shape"]?.firstOrNull() == "square")
                    else -> notFound()
                }
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error")
            }
        }
        val song = runBlocking(Dispatchers.IO) { AppDatabase.getDatabase(context).audioDao().getAudioFileById(id) } ?: return notFound()
        return try {
            when (parts[1]) {
                "song" -> serveFile(File(getPhysicalPath(context, song.id, song.uriString) ?: return notFound()), session.headers["range"], session.method)
                "art" -> serveArt(song.uriString, session.parameters["shape"]?.firstOrNull() == "square")
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

    private fun serveArt(uri: String, square: Boolean = false): Response {
        val bitmap = loadAlbumArtBitmap(context, uri) ?: return notFound()
        val out = ByteArrayOutputStream()
        // Nunca reciclar el original: viene de la caché de carátulas. Las teles recortan por el centro para llenar su
        // recuadro (16:9 o cuadrado según el modelo): la carátula va entera, a toda la altura y centrada, sobre un
        // lienzo 16:9 negro; así no pierde nada ni recortando a 16:9 ni a cuadrado
        // Chromecast ([square]): su receptor ya coloca la carátula a su manera; si recibe una imagen 16:9 con bandas la achica en una
        // esquina, así que se le da la carátula cuadrada, sin bandas ni recorte
        val height = if (square) maxOf(bitmap.width, bitmap.height).coerceIn(1, 800) else 720
        val width = if (square) height else 1280
        val canvasBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(canvasBitmap)
            canvas.drawColor(android.graphics.Color.BLACK)
            val scale = minOf(height.toFloat() / bitmap.height, width.toFloat() / bitmap.width)
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val left = (width - w) / 2
            val top = (height - h) / 2
            canvas.drawBitmap(
                bitmap, null, android.graphics.Rect(left, top, left + w, top + h),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            )
            canvasBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        } finally {
            canvasBitmap.recycle()
        }
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
