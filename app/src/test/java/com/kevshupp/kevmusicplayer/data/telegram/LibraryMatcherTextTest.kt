package com.kevshupp.kevmusicplayer.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryMatcherTextTest {
    @Test
    fun repairsWrongEncoding() {
        assertEquals("Fría Como El Viento", LibraryMatcher.fixMojibake("FrÃ­a Como El Viento"))
        assertEquals("Hasta Que Te Conocí", LibraryMatcher.fixMojibake("Hasta Que Te ConocÃ­"))
        assertEquals("Miño", LibraryMatcher.fixMojibake("ï»¿MinÌƒo"))
    }

    @Test
    fun leavesGoodTextAlone() {
        assertEquals("Canción Ñandú", LibraryMatcher.fixMojibake("Canción Ñandú"))
        assertEquals("피 땀 눈물", LibraryMatcher.fixMojibake("피 땀 눈물"))
        assertEquals("Ãngel", LibraryMatcher.fixMojibake("Ãngel"))
    }

    @Test
    fun ignoresRemasterAndGuests() {
        assertEquals(LibraryMatcher.normalizeTitle("Dirty Diana"), LibraryMatcher.normalizeTitle("Dirty Diana (2012 Remaster)"))
        assertEquals(LibraryMatcher.normalizeTitle("La Tirana"), LibraryMatcher.normalizeTitle("La Tirana (w. Nathy Peluso)"))
        assertEquals(LibraryMatcher.normalizeTitle("Formula"), LibraryMatcher.normalizeTitle("Formula (From \"Euphoria\" Soundtrack)"))
        assertEquals(LibraryMatcher.normalizeTitle("Almohada (Mix 2024)"), "almohadamix2024")
    }
}
