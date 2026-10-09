package com.kevshupp.kevmusicplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateRegexTest {

    @Test
    fun regex_detectsDuplicateSuffixes() {
        assertTrue(REGEX_SUFFIX_PARENTHESIS.containsMatchIn("Song (1).mp3"))
        assertTrue(REGEX_SUFFIX_PARENTHESIS.containsMatchIn("My Track (22).flac"))
        assertTrue(REGEX_SUFFIX_COPIA.containsMatchIn("Song - copia.mp3"))
        assertTrue(REGEX_SUFFIX_COPIA.containsMatchIn("Song - Copia.m4a"))
        assertTrue(REGEX_SUFFIX_UNDERSCORE.containsMatchIn("Song_1.mp3"))
        assertTrue(REGEX_SUFFIX_UNDERSCORE.containsMatchIn("Track_99.wav"))
    }

    @Test
    fun regex_cleansDuplicateSuffixesFromTitle() {
        val title1 = "In The End (1)".replace(REGEX_CLEAN_PARENTHESIS, "").trim()
        assertEquals("In The End", title1)

        val title2 = "Numb - Copia".replace(REGEX_CLEAN_COPIA, "").trim()
        assertEquals("Numb", title2)

        val title3 = "Faint_2".replace(REGEX_CLEAN_UNDERSCORE, "").trim()
        assertEquals("Faint", title3)
    }
}
