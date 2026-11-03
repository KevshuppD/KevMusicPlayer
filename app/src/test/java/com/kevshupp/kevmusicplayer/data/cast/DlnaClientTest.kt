package com.kevshupp.kevmusicplayer.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaClientTest {
    private val description = """
        <root><device><friendlyName>[TV] Samsung Q7 &amp; Co</friendlyName><UDN>uuid:abc</UDN>
        <serviceList>
        <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/upnp/control/RenderingControl1</controlURL></service>
        <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>upnp/control/AVTransport1</controlURL></service>
        </serviceList></device></root>
    """.trimIndent()

    @Test
    fun readsRendererDescription() {
        val r = DlnaClient.parseDescription("http://192.168.1.9:9197/dmr", description)!!
        assertEquals("[TV] Samsung Q7 & Co", r.name)
        assertEquals("uuid:abc", r.id)
        assertEquals("http://192.168.1.9:9197/upnp/control/AVTransport1", r.avTransportUrl)
        assertEquals("http://192.168.1.9:9197/upnp/control/RenderingControl1", r.renderingControlUrl)
    }

    @Test
    fun deviceWithoutTransportIsIgnored() {
        assertNull(DlnaClient.parseDescription("http://x/", "<root><friendlyName>Router</friendlyName></root>"))
    }

    @Test
    fun readsTimes() {
        assertEquals(3_723_000L, DlnaClient.parseTime("1:02:03"))
        assertEquals(65_000L, DlnaClient.parseTime("00:01:05.500"))
        assertEquals(0L, DlnaClient.parseTime("NOT_IMPLEMENTED"))
        assertEquals("01:02:03", DlnaClient.formatTime(3_723_999))
    }

    @Test
    fun readsTagsWithPrefix() {
        assertEquals("PLAYING", DlnaClient.tag("<u:R><CurrentTransportState>PLAYING</CurrentTransportState></u:R>", "CurrentTransportState"))
        assertEquals("718", DlnaClient.tag("<e:UPnPError><e:errorCode>718</e:errorCode></e:UPnPError>", "errorCode"))
    }

    @Test
    fun metadataEscapesText() {
        val xml = DlnaClient.didl("A & B", "C<D", "E", "http://h/s.mp3", "audio/mpeg", "http://h/a.jpg", 61_000)
        assertTrue(xml.contains("<dc:title>A &amp; B</dc:title>"))
        assertTrue(xml.contains("C&lt;D"))
        assertTrue(xml.contains("""protocolInfo="http-get:*:audio/mpeg:*" duration="00:01:01""""))
    }

    @Test
    fun readsChunkedResponses() {
        val raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n1\r\n!\r\n0\r\n\r\n"
        assertEquals(200 to "hello!", DlnaClient.parseResponse(raw.byteInputStream()))
        assertEquals(500 to "x", DlnaClient.parseResponse("HTTP/1.1 500 Error\r\n\r\nx".byteInputStream()))
    }
}
