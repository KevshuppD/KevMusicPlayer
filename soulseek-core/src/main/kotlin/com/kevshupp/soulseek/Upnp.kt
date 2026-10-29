package com.kevshupp.soulseek

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Abre el puerto de escucha en el router por UPnP (IGD), para que otros usuarios puedan conectarse a este PC:
 * sin puerto abierto, quien también esté detrás de un router sin abrir no puede descargar de aquí ni enviarnos
 * archivos. Sin dependencias: búsqueda SSDP y una petición SOAP AddPortMapping.
 */
object Upnp {
    private class Gateway(val controlUrl: String, val serviceType: String, val localIp: String)

    @Volatile private var mapped: Pair<Gateway, Int>? = null

    /** Devuelve la IP externa si el puerto quedó abierto, o null. */
    fun open(port: Int): String? {
        val gw = discover() ?: return null
        val ok = addMapping(gw, port, lease = 0) || addMapping(gw, port, lease = 86400)
        if (!ok) return null
        mapped = gw to port
        return runCatching { externalIp(gw) }.getOrNull() ?: "?"
    }

    fun close() {
        val (gw, port) = mapped ?: return
        mapped = null
        runCatching {
            soap(gw, "DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort><NewProtocol>TCP</NewProtocol>")
        }
    }

    private fun discover(): Gateway? {
        val locations = LinkedHashSet<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 1500
            val search = { st: String ->
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900))
            }
            search("urn:schemas-upnp-org:device:InternetGatewayDevice:1")
            search("urn:schemas-upnp-org:service:WANIPConnection:1")
            val buf = ByteArray(2048)
            val end = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (e: SocketTimeoutException) { break }
                String(packet.data, 0, packet.length).lines()
                    .firstOrNull { it.startsWith("location:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.let { locations += it }
            }
        }
        for (location in locations) {
            val gw = runCatching { describe(location) }.getOrNull()
            if (gw != null) return gw
        }
        return null
    }

    private fun describe(location: String): Gateway? {
        val xml = http(location, null, null)
        val services = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] }
        for (service in services) {
            val type = tag(service, "serviceType") ?: continue
            if (!type.contains("WANIPConnection") && !type.contains("WANPPPConnection")) continue
            val control = tag(service, "controlURL") ?: continue
            val base = tag(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: location
            val url = URI(base).resolve(control).toString()
            val uri = URI(url)
            // IP local con la que se llega al router
            val localIp = Socket().use { s ->
                s.connect(InetSocketAddress(uri.host, if (uri.port > 0) uri.port else 80), 2000)
                s.localAddress.hostAddress ?: return null
            }
            return Gateway(url, type, localIp)
        }
        return null
    }

    private fun addMapping(gw: Gateway, port: Int, lease: Int): Boolean = runCatching {
        soap(
            gw, "AddPortMapping",
            "<NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort><NewProtocol>TCP</NewProtocol>" +
                "<NewInternalPort>$port</NewInternalPort><NewInternalClient>${gw.localIp}</NewInternalClient>" +
                "<NewEnabled>1</NewEnabled><NewPortMappingDescription>KevMusic Soulseek</NewPortMappingDescription>" +
                "<NewLeaseDuration>$lease</NewLeaseDuration>"
        )
        true
    }.getOrDefault(false)

    private fun externalIp(gw: Gateway): String? = tag(soap(gw, "GetExternalIPAddress", ""), "NewExternalIPAddress")

    private fun soap(gw: Gateway, action: String, args: String): String {
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"${gw.serviceType}\">" +
            "$args</u:$action></s:Body></s:Envelope>"
        return http(gw.controlUrl, body, "\"${gw.serviceType}#$action\"")
    }

    private fun http(url: String, body: String?, soapAction: String?): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        if (body != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPAction", soapAction)
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        if (code !in 200..299) throw java.io.IOException("UPnP HTTP $code")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun tag(xml: String, name: String) =
        Regex("<(?:\\w+:)?$name>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
}
