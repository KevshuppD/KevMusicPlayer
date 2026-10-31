package com.kevshupp.kevmusicplayer.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotSearchResultTest {
    private fun result(label: String) = BotSearchResult(1L, label, ByteArray(0))

    @Test
    fun splitsNumberArtistAndTitle() {
        val t = result("5. BTS - We Are Bulletproof Pt.2").toTrack()
        assertEquals("BTS", t.artist)
        assertEquals("We Are Bulletproof Pt.2", t.title)
        assertTrue(t.id < 0)
        assertTrue(t.link.startsWith(BotSearchResult.SOUNDCLOUD_LINK))
    }

    @Test
    fun labelWithoutSeparatorKeepsWholeTitle() {
        val t = result("2. Cancion rara").toTrack()
        assertEquals("", t.artist)
        assertEquals("Cancion rara", t.title)
    }

    @Test
    fun idIsStableAcrossSearches() = assertEquals(result("1. A - B").toTrack().id, result("7. A - B").toTrack().id)
}
