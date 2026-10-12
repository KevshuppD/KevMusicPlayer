package com.kevshupp.kevmusicplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongIdentityTest {
    @Test
    fun remapIdList_replacesOnlyRemappedIds() {
        assertEquals("1,20,3", SongIdentity.remapIdList("1,2,3", mapOf(2L to 20L)))
    }

    @Test
    fun remapIdList_returnsNullWhenNothingChanges() {
        assertNull(SongIdentity.remapIdList("1,2,3", mapOf(9L to 90L)))
        assertNull(SongIdentity.remapIdList("", mapOf(1L to 2L)))
    }

    @Test
    fun remapIdList_dropsDuplicatesCreatedByTheRemap() {
        // Two old copies of a song collapsing into the same new id must not appear twice in a playlist
        assertEquals("7,3", SongIdentity.remapIdList("1,2,3", mapOf(1L to 7L, 2L to 7L)))
    }

    @Test
    fun remapIdList_keepsDeadIdsUntouched() {
        // An id with no known replacement stays, so a later scan can still recover it
        assertEquals("20,999", SongIdentity.remapIdList("2,999", mapOf(2L to 20L)))
    }

    @Test
    fun sameSong_ignoresCaseSpacesAndSmallDurationDrift() {
        assertTrue(SongIdentity.sameSong(" Dimple ", "BTS", 197_000, "dimple", "bts", 199_500))
    }

    @Test
    fun sameSong_rejectsDifferentLengthOrArtist() {
        assertFalse(SongIdentity.sameSong("Intro", "BTS", 60_000, "Intro", "BTS", 180_000))
        assertFalse(SongIdentity.sameSong("Intro", "BTS", 60_000, "Intro", "Ado", 60_000))
    }

    @Test
    fun matchScore_prefersSameFolderOverSameAlbum() {
        val sameFolder = SongIdentity.matchScore("/a/b", "X", "/a/b", "Y")
        val sameAlbum = SongIdentity.matchScore("/a/b", "X", "/a/c", "x")
        assertTrue(sameFolder > sameAlbum)
        assertEquals(3, SongIdentity.matchScore("/a/b", "X", "/a/b", "x"))
    }
}
