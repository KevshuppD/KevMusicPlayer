package com.kevshupp.kevmusicplayer.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastMediaServerTest {
    @Test
    fun parsesRanges() {
        assertEquals(0L to 99L, CastMediaServer.parseRange("bytes=0-", 100))
        assertEquals(10L to 20L, CastMediaServer.parseRange("bytes=10-20", 100))
        assertEquals(90L to 99L, CastMediaServer.parseRange("bytes=-10", 100))
        assertEquals(10L to 99L, CastMediaServer.parseRange("bytes=10-500", 100))
    }

    @Test
    fun rejectsInvalidRanges() {
        assertNull(CastMediaServer.parseRange("bytes=200-", 100))
        assertNull(CastMediaServer.parseRange("items=0-1", 100))
        assertNull(CastMediaServer.parseRange(null, 100))
    }

    @Test
    fun onlySupportedFormats() {
        assertEquals("audio/mpeg", CastMediaServer.mimeOf("a.MP3"))
        assertEquals("audio/flac", CastMediaServer.mimeOf("a.flac"))
        assertNull(CastMediaServer.mimeOf("a.ape"))
    }
}
