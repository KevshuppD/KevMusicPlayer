package com.kevshupp.kevmusicplayer.data.telegram

import com.kevshupp.kevmusicplayer.data.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMatcherTest {
    private var nextId = 1L
    private fun song(title: String, artist: String, album: String, durationMs: Long) =
        AudioFile(id = nextId++, title = title, artist = artist, album = album, duration = durationMs, uriString = "")

    private fun track(title: String, artist: String, album: String, durationSec: Int) =
        DeezerTrack(id = nextId++, title = title, artist = artist, album = album, durationSec = durationSec, coverSmall = "", coverXl = "", link = "")

    // Etiquetas reales de la biblioteca (WINGS de BTS) frente a los datos de Deezer
    private val library = listOf(
        song("Intro : Boy Meets Evil", "BTS", "WINGS", 121809),
        song("21세기 소녀", "BTS", "WINGS", 196911),
        song("Am I Wrong", "BTS", "WINGS", 149917),
        song("둘! 셋! (그래도 좋은 날이 더 많기를)", "방탄소년단", "WINGS", 272692),
        song("Begin", "BTS", "WINGS", 229773),
        song("Despacito (feat. Daddy Yankee)", "Luis Fonsi", "Vida", 229000)
    )
    private val matcher = LibraryMatcher(library)

    @Test fun puntuacionDistinta() = assertTrue(matcher.hasTrack(track("Intro: Boy Meets Evil", "BTS", "Wings", 121)))
    @Test fun tituloEnOtroIdioma() = assertTrue(matcher.hasTrack(track("21st Century Girl", "BTS", "Wings", 192)))
    @Test fun artistaEnCoreano() = assertTrue(matcher.hasTrack(track("2! 3!", "BTS", "Wings", 272)))
    @Test fun mismoTituloOtraDuracion() = assertTrue(matcher.hasTrack(track("Am I Wrong", "BTS", "Wings", 213)))
    @Test fun sinFeat() = assertTrue(matcher.hasTrack(track("Despacito", "Luis Fonsi", "Vida", 229)))
    @Test fun cancionQueNoTienes() = assertFalse(matcher.hasTrack(track("Interlude: Wings", "BTS", "Wings", 144)))
    @Test fun otroArtistaMismoAlbum() = assertFalse(matcher.hasTrack(track("Begin", "Otro", "Wings", 229)))
    @Test fun albumConOtrasMayusculas() = assertEquals(5, matcher.albumSongCount(DeezerAlbum(1, "Wings", "BTS", "", 15, "album")))
    @Test fun albumDeOtroArtista() = assertEquals(0, matcher.albumSongCount(DeezerAlbum(2, "Wings", "Little Mix", "", 10, "album")))
}
