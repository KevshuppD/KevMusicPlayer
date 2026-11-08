package com.kevshupp.kevmusicplayer.ui

import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.ui.screens.searchRelevance
import com.kevshupp.soulseek.FolderGroup
import com.kevshupp.soulseek.SlskFile
import com.kevshupp.soulseek.queryNamesSong
import com.kevshupp.soulseek.songMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRelevanceTest {
    private fun song(title: String, artist: String) =
        AudioFile(id = 1, title = title, artist = artist, album = "a", duration = 1000, uriString = "")

    @Test fun titleBeforeArtist() {
        val songs = listOf(song("Grenade", "Bruno Mars"), song("Run", "BTS"), song("Run Away", "X"))
        assertEquals(listOf("Run", "Run Away", "Grenade"), songs.sortedBy { searchRelevance(it, "run") }.map { it.title })
    }

    @Test fun titlePlusArtist() {
        val songs = listOf(song("Butter", "Other"), song("Dynamite", "BTS"), song("Butter", "BTS"))
        assertEquals("BTS", songs.sortedBy { searchRelevance(it, "butter bts") }.first().artist)
    }

    @Test fun namesSong() {
        assertTrue(queryNamesSong("butter bts", "Butter", "BTS"))
        assertTrue(queryNamesSong("Butter", "Butter", "BTS"))
        assertFalse(queryNamesSong("bts", "Butter", "BTS"))
        assertFalse(queryNamesSong("butter coldplay", "Butter", "BTS"))
    }

    @Test fun matchesFiles() {
        val g = FolderGroup("u", "Music\\BTS\\Butter", listOf(
            SlskFile("Music\\BTS\\Butter\\01 Butter.flac", 1, null, 164),
            SlskFile("Music\\BTS\\Butter\\02 Butter (Instrumental).flac", 1, null, 164)
        ), true, 1, 0)
        val other = FolderGroup("v", "Music\\Misc", listOf(SlskFile("Music\\Misc\\Butter.mp3", 1, 320, 164)), true, 1, 0)
        val m = songMatches(listOf(g, other), "Butter", "BTS", 164)
        assertEquals(listOf("01 Butter.flac"), m.map { it.second.name })
    }
}

class WithGuestsTest {
    @Test fun mainFirstNoDuplicates() {
        assertEquals("Polima WestCoast, Pablo Chill-E",
            com.kevshupp.kevmusicplayer.data.LibraryNormalizer.withGuests("Polima WestCoast", listOf("Polima WestCoast", "Pablo Chill-E")))
        assertEquals("BTS", com.kevshupp.kevmusicplayer.data.LibraryNormalizer.withGuests("BTS", emptyList()))
    }
}
