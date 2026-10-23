package com.kevshupp.kevmusic.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlin.concurrent.thread

data class FoundPhone(val name: String, val host: String, val port: Int)

/** Busca teléfonos con KevMusicPlayer en la red local (mDNS `_kevmusic._tcp`). */
class Discovery {
    private val type = "_kevmusic._tcp.local."
    private val instances = mutableListOf<JmDNS>()
    private val _found = MutableStateFlow<List<FoundPhone>>(emptyList())
    val found: StateFlow<List<FoundPhone>> = _found

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            event.dns.requestServiceInfo(event.type, event.name, 3000)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            _found.value = _found.value.filterNot { it.name == event.name }
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info
            val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
            val phone = FoundPhone(event.name, host, info.port)
            _found.value = (_found.value.filterNot { it.name == phone.name } + phone).sortedBy { it.name }
        }
    }

    fun start() {
        // InetAddress.getLocalHost() en Debian suele ser 127.0.1.1: se abre un JmDNS por cada interfaz de la LAN
        thread(isDaemon = true, name = "mdns-start") {
            val addresses = NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
            addresses.forEach { addr ->
                runCatching {
                    val dns = JmDNS.create(addr)
                    dns.addServiceListener(type, listener)
                    synchronized(instances) { instances.add(dns) }
                }
            }
        }
    }

    fun stop() {
        thread(isDaemon = true, name = "mdns-stop") {
            synchronized(instances) {
                instances.forEach { runCatching { it.close() } }
                instances.clear()
            }
        }
    }
}
