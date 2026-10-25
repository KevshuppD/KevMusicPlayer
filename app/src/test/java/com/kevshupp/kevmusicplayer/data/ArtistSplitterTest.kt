package com.kevshupp.kevmusicplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistSplitterTest {
    private val library = listOf(
        "Bad Bunny", "Bad Bunny & Jhay Cortez", "Bad Bunny & Nicky Jam", "Bad Bunny feat. Drake",
        "Bad Bunny Feat. El Alfa", "Bad Bunny ft Chuwi", "Lady Gaga", "Lady Gaga, Bruno Mars",
        "Labrinth", "Labrinth, Zendaya", "Simon & Garfunkel", "Earth, Wind & Fire", "Rosalía",
        "ROSALIA y Bad Bunny", "Daft Punk (feat. Pharrell Williams)"
    )
    private val splitter = ArtistSplitter(library)

    @Test fun separaColaboracionesDeArtistasConocidos() {
        assertEquals(listOf("Bad Bunny", "Jhay Cortez"), splitter.split("Bad Bunny & Jhay Cortez"))
        assertEquals(listOf("Lady Gaga", "Bruno Mars"), splitter.split("Lady Gaga, Bruno Mars"))
        assertEquals(listOf("Labrinth", "Zendaya"), splitter.split("Labrinth, Zendaya"))
    }

    @Test fun featSiempreSepara() {
        assertEquals(listOf("Bad Bunny", "Drake"), splitter.split("Bad Bunny feat. Drake"))
        assertEquals(listOf("Bad Bunny", "El Alfa"), splitter.split("Bad Bunny Feat. El Alfa"))
        assertEquals(listOf("Bad Bunny", "Chuwi"), splitter.split("Bad Bunny ft Chuwi"))
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), splitter.split("Daft Punk (feat. Pharrell Williams)"))
    }

    @Test fun bandasConSeparadorSiguenEnteras() {
        assertEquals(listOf("Simon & Garfunkel"), splitter.split("Simon & Garfunkel"))
        assertEquals(listOf("Earth, Wind & Fire"), splitter.split("Earth, Wind & Fire"))
    }

    @Test fun usaLaFormaDelArtistaSoloYIgnoraTildes() {
        // "ROSALIA" se muestra como "Rosalía", la forma de su canción en solitario
        assertEquals(listOf("Rosalía", "Bad Bunny"), splitter.split("ROSALIA y Bad Bunny"))
        assertTrue(splitter.matches("ROSALIA y Bad Bunny", "rosalia"))
        assertEquals("Bad Bunny", splitter.primary("Bad Bunny & Nicky Jam"))
    }
}
