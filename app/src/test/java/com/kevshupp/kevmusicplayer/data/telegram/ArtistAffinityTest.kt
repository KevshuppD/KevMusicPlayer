package com.kevshupp.kevmusicplayer.data.telegram

import com.kevshupp.kevmusicplayer.data.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistAffinityTest {
    private var nextId = 1L
    private fun song(artist: String, plays: Int) =
        AudioFile(id = nextId++, title = "x", artist = artist, album = "a", duration = 1000, uriString = "", playCount = plays)

    private val affinity = ArtistAffinity(listOf(song("BTS", 40), song("Twenty One Pilots", 0), song("Bad Bunny, Rosalía", 3)))

    @Test fun escuchadosPrimeroLuegoBibliotecaLuegoResto() =
        assertEquals(
            listOf("BTS", "ROSALÍA", "twenty one pilots", "BLACKPINK", "L2B"),
            affinity.rank(listOf("BLACKPINK", "twenty one pilots", "BTS", "L2B", "ROSALÍA")) { it }
        )

    @Test fun colaboracionCuentaParaCadaArtista() = assertEquals(0, affinity.tier("Jung Kook feat. BTS"))
}
