package com.kevshupp.kevmusicplayer.data.telegram

import com.kevshupp.kevmusicplayer.data.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongRepairTest {
    private fun song(title: String, artist: String) =
        AudioFile(id = 1, title = title, artist = artist, album = "", duration = 200_000, uriString = "")

    @Test fun eligeLaVersionExacta() {
        val buttons = listOf(
            "1. José José - Desesperado",
            "2. José José - Desesperado (Mix 2024)",
            "3. Otro - Desesperado (Mix 2024)"
        )
        assertEquals(1, SongRepair.chooseBotResult(song("Desesperado (Mix 2024)", "Jose Jose"), buttons))
    }

    @Test fun ningunResultadoDelArtista() {
        val buttons = listOf("1. Juan Gabriel - Hasta Que Te Conocí", "2. Maná - Hasta Que Te Conocí")
        assertNull(SongRepair.chooseBotResult(song("Hasta que te conocí", "Marc Anthony"), buttons))
    }

    @Test fun tildesYMayusculas() {
        val buttons = listOf("1. Juan Gabriel - Hasta Que Te Conocí (En Vivo)", "2. Maná - Hasta Que Te Conocí")
        assertEquals(1, SongRepair.chooseBotResult(song("hasta que te conoci", "Mana"), buttons))
    }
}
