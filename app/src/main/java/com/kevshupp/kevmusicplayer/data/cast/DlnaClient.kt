package com.kevshupp.kevmusicplayer.data.cast

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

/** Una tele o altavoz DLNA: lo justo para enviarle audio y controlarlo. */
data class DlnaRenderer(val id: String, val name: String, val avTransportUrl: String, val renderingControlUrl: String?)

/**
 * Cliente DLNA mínimo, sin dependencias: búsqueda SSDP y órdenes SOAP.
 * El HTTP va por sockets porque la app no permite tráfico sin cifrar en OkHttp, y aquí solo se habla con la red local.
 */
object DlnaClient {
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private const val TIMEOUT_MS = 5000

    class SoapException(val code: String?, message: String) : IOException(message)

    /** Bloquea unos segundos. Pide a la vez a toda la red y lee la descripción de quien responda. */
    fun discover(waitMs: Long = 3000): List<DlnaRenderer> {
        val locations = LinkedHashSet<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 500
            val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $RENDERER\r\n\r\n").toByteArray()
            // UDP puede perderse, y algunas teles no responden a la primera
            repeat(2) { socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900)) }
            val buf = ByteArray(2048)
            val end = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (e: SocketTimeoutException) { continue }
                String(packet.data, 0, packet.length).lineSequence()
                    .firstOrNull { it.startsWith("location:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.let { locations += it }
            }
        }
        return locations.mapNotNull { location -> runCatching { parseDescription(location, request(location, null, emptyMap())) }.getOrNull() }
            .distinctBy { it.id }
    }

    internal fun parseDescription(location: String, xml: String): DlnaRenderer? {
        val base = tag(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: location
        var transport: String? = null
        var rendering: String? = null
        Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { match ->
            val service = match.groupValues[1]
            val type = tag(service, "serviceType").orEmpty()
            val control = tag(service, "controlURL") ?: return@forEach
            val url = runCatching { URI(base).resolve(control.trim()).toString() }.getOrNull() ?: return@forEach
            if (type.contains(":AVTransport:")) transport = url
            if (type.contains(":RenderingControl:")) rendering = url
        }
        val avTransport = transport ?: return null
        val name = tag(xml, "friendlyName")?.let(::unescape)?.trim().orEmpty().ifBlank { "Dispositivo DLNA" }
        return DlnaRenderer(tag(xml, "UDN")?.trim() ?: location, name, avTransport, rendering)
    }

    fun setUri(r: DlnaRenderer, url: String, metadata: String) {
        soap(r.avTransportUrl, AV_TRANSPORT, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>${escape(url)}</CurrentURI><CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>")
    }

    fun play(r: DlnaRenderer) { soap(r.avTransportUrl, AV_TRANSPORT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") }
    fun pause(r: DlnaRenderer) { soap(r.avTransportUrl, AV_TRANSPORT, "Pause", "<InstanceID>0</InstanceID>") }
    fun stop(r: DlnaRenderer) { soap(r.avTransportUrl, AV_TRANSPORT, "Stop", "<InstanceID>0</InstanceID>") }

    fun seek(r: DlnaRenderer, positionMs: Long) {
        soap(r.avTransportUrl, AV_TRANSPORT, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${formatTime(positionMs)}</Target>")
    }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING o NO_MEDIA_PRESENT. */
    fun transportState(r: DlnaRenderer): String =
        tag(soap(r.avTransportUrl, AV_TRANSPORT, "GetTransportInfo", "<InstanceID>0</InstanceID>"), "CurrentTransportState").orEmpty()

    fun positionMs(r: DlnaRenderer): Long =
        parseTime(tag(soap(r.avTransportUrl, AV_TRANSPORT, "GetPositionInfo", "<InstanceID>0</InstanceID>"), "RelTime"))

    /** De 0 a 100; null si el dispositivo no deja controlarlo. */
    fun volume(r: DlnaRenderer): Int? {
        val url = r.renderingControlUrl ?: return null
        return runCatching {
            tag(soap(url, RENDERING_CONTROL, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>"), "CurrentVolume")?.trim()?.toIntOrNull()
        }.getOrNull()
    }

    fun setVolume(r: DlnaRenderer, value: Int) {
        val url = r.renderingControlUrl ?: return
        soap(url, RENDERING_CONTROL, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>${value.coerceIn(0, 100)}</DesiredVolume>")
    }

    /** Descripción DIDL-Lite: algunas teles, como las Samsung, rechazan o no muestran una canción sin ella. */
    internal fun didl(title: String, artist: String, album: String, url: String, mime: String, artUrl: String, durationMs: Long): String =
        """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="0" parentID="-1" restricted="1"><dc:title>${escape(title)}</dc:title><dc:creator>${escape(artist)}</dc:creator>""" +
            """<upnp:artist>${escape(artist)}</upnp:artist><upnp:album>${escape(album)}</upnp:album><upnp:albumArtURI>${escape(artUrl)}</upnp:albumArtURI>""" +
            """<upnp:class>object.item.audioItem.musicTrack</upnp:class>""" +
            """<res protocolInfo="http-get:*:$mime:*" duration="${formatTime(durationMs)}">${escape(url)}</res></item></DIDL-Lite>"""

    internal fun formatTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    /** `H:MM:SS` con fracción opcional; 0 si no se entiende (`NOT_IMPLEMENTED`). */
    internal fun parseTime(text: String?): Long {
        val m = Regex("""(\d+):(\d{1,2}):(\d{1,2})(?:\.\d+)?""").matchEntire(text?.trim() ?: return 0) ?: return 0
        val (h, min, s) = m.destructured
        return (h.toLong() * 3600 + min.toLong() * 60 + s.toLong()) * 1000
    }

    internal fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    internal fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** El contenido de una etiqueta, con o sin prefijo de espacio de nombres. */
    internal fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    private fun soap(url: String, service: String, action: String, args: String): String {
        val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$service">$args</u:$action></s:Body></s:Envelope>"""
        return request(url, body, mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPACTION" to "\"$service#$action\""))
    }

    private fun request(url: String, body: String?, headers: Map<String, String>): String {
        val uri = URI(url)
        val port = if (uri.port > 0) uri.port else 80
        val path = uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        val payload = body?.toByteArray() ?: ByteArray(0)
        Socket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.connect(InetSocketAddress(uri.host, port), TIMEOUT_MS)
            val head = StringBuilder("${if (body != null) "POST" else "GET"} $path HTTP/1.1\r\nHost: ${uri.host}:$port\r\nConnection: close\r\nUser-Agent: KevMusicPlayer DLNADOC/1.50\r\n")
            headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            if (body != null) head.append("Content-Length: ${payload.size}\r\n")
            head.append("\r\n")
            socket.getOutputStream().apply { write(head.toString().toByteArray()); write(payload); flush() }
            val (status, text) = parseResponse(socket.getInputStream())
            if (status !in 200..299) throw SoapException(tag(text, "errorCode")?.trim(), "HTTP $status ${tag(text, "errorDescription").orEmpty()}".trim())
            return text
        }
    }

    internal fun parseResponse(input: InputStream): Pair<Int, String> {
        val raw = input.readBytes()
        val split = (0..raw.size - 4).firstOrNull { raw[it] == 13.toByte() && raw[it + 1] == 10.toByte() && raw[it + 2] == 13.toByte() && raw[it + 3] == 10.toByte() }
            ?: throw IOException("Respuesta incompleta")
        val head = String(raw, 0, split)
        val status = head.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Respuesta no válida")
        val rest = raw.copyOfRange(split + 4, raw.size)
        val chunked = head.lineSequence().any { it.startsWith("transfer-encoding:", true) && it.contains("chunked", true) }
        return status to String(if (chunked) dechunk(rest) else rest)
    }

    private fun dechunk(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            var end = i
            while (end + 1 < data.size && !(data[end] == 13.toByte() && data[end + 1] == 10.toByte())) end++
            val size = String(data, i, end - i).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = end + 2
            out.write(data, start, size.coerceAtMost((data.size - start).coerceAtLeast(0)))
            i = start + size + 2
        }
        return out.toByteArray()
    }
}
