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

    @Test
    fun strayGenreSongDoesNotBringForeignArtists() {
        val lib = (1L..10).map { song(it, "BTS", "K-Pop", plays = 20) } +
            song(11, "BTS", "Reggaeton", plays = 1) +
            (12L..20).map { song(it, "Bad Bunny", "Reggaeton", plays = 50) } +
            (21L..25).map { song(it, "Twice", "K-Pop", plays = 3) }
        val mix = HomeMixes.build(lib, now).first { it.id == "artist:BTS" }
        assertTrue(mix.songs.none { it.artist == "Bad Bunny" })
        assertTrue(mix.songs.any { it.artist == "Twice" })
    }

    @Test
    fun oneMatchingSongFromForeignArtistIsNotEnough() {
        val lib = (1L..10).map { song(it, "BTS", "K-Pop", plays = 20) } +
            song(11, "Metallica", "K-Pop", plays = 90) +
            (12L..20).map { song(it, "Metallica", "Metal", plays = 90) }
        val mix = HomeMixes.build(lib, now).first { it.id == "artist:BTS" }
        assertTrue(mix.songs.all { it.artist == "BTS" })
    }

    @Test
    fun broadGenreDoesNotBringOtherArtists() {
        val lib = (1L..10).map { song(it, "BTS", "Pop", plays = 20) } +
            (11L..20).map { song(it, "Taylor Swift", "Pop", plays = 5) }
        val mix = HomeMixes.build(lib, now).first { it.id == "artist:BTS" }
        assertTrue(mix.songs.all { it.artist == "BTS" })
    }

    @Test
    fun otherArtistsNeverOutnumberTheMixArtist() {
        val lib = (1L..5).map { song(it, "BTS", "K-Pop", plays = 20) } +
            (6L..50).map { song(it, "Artist ${it / 2}", "K-Pop", plays = 3) }
        val songs = HomeMixes.build(lib, now).first { it.id == "artist:BTS" }.songs
        assertEquals(5, songs.count { it.artist == "BTS" })
        assertEquals(10, songs.size)
    }

    @Test
    fun storedMixesSurvivePlaysAndKeepTheirSongs() {
        val mixes = HomeMixes.build(library, now)
        val played = library.map { it.copy(playCount = it.playCount + 7) }
        val restored = HomeMixes.resolve(HomeMixes.encode(mixes), played)!!
        assertEquals(mixes.map { it.id }, restored.map { it.id })
        assertEquals(mixes.map { m -> m.songs.map { it.id } }, restored.map { m -> m.songs.map { it.id } })
        assertEquals(mixes[0].songs[0].playCount + 7, restored[0].songs[0].playCount)
    }

    @Test
    fun storedMixesDropDeletedSongsAndRebuildWhenIdsChanged() {
        val stored = HomeMixes.encode(HomeMixes.build(library, now))
        val restored = HomeMixes.resolve(stored, library.filter { it.id != 1L })!!
        assertTrue(restored.flatMap { it.songs }.none { it.id == 1L })
        assertEquals(null, HomeMixes.resolve(stored, library.map { it.copy(id = it.id + 1000) }))
    }

    @Test
    fun mixesSavedWithoutHistoryAreRebuiltOnceThereIsHistory() {
        val stored = HomeMixes.encode(HomeMixes.build(library.map { it.copy(playCount = 0, lastPlayed = 0) }, now))
        assertEquals(null, HomeMixes.resolve(stored, library))
    }
}
