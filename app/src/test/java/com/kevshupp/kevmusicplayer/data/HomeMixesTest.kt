package com.kevshupp.kevmusicplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMixesTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1000 * day

    private fun song(id: Long, artist: String, genre: String, plays: Int = 0, last: Long = 0L) =
        AudioFile(id, "T$id", artist, "A", genre, 180_000, "content://x/$id", playCount = plays, lastPlayed = last)

    private val library =
        (1L..10).map { song(it, "BTS", "K-Pop", plays = 20, last = now - day) } +
            (11L..20).map { song(it, "Agust D", "K-Pop", plays = 5, last = now - 60 * day) } +
            (21L..30).map { song(it, "Gorillaz", "Alternative") }

    @Test
    fun artistMixesFollowPlays() {
        val ids = HomeMixes.build(library, now).map { it.id }
        assertEquals(listOf("artist:BTS", "artist:Agust D"), ids.filter { it.startsWith("artist:") })
    }

    @Test
    fun artistMixStartsWithOwnSongsAndGenre() {
        val mix = HomeMixes.build(library, now).first { it.id == "artist:BTS" }
        assertTrue(mix.songs.all { it.artist == "BTS" || it.genre == "K-Pop" })
        assertTrue(mix.songs.count { it.artist == "BTS" } == 10)
    }

    @Test
    fun rediscoverAndUnplayed() {
        val mixes = HomeMixes.build(library, now).associateBy { it.id }
        assertTrue(mixes.getValue("rediscover").songs.all { it.artist == "Agust D" })
        assertTrue(mixes.getValue("unplayed").songs.all { it.artist == "Gorillaz" })
    }

    @Test
    fun sameDaySameOrder() {
        assertEquals(HomeMixes.build(library, now), HomeMixes.build(library, now + 1000))
    }

    @Test
    fun unknownGenreIsSkipped() {
        val mixes = HomeMixes.build(library.map { it.copy(genre = "Unknown Genre") }, now)
        assertTrue(mixes.none { it.id.startsWith("genre:") })
    }
}
