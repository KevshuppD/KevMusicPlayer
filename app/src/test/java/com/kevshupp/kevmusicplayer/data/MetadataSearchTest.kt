package com.kevshupp.kevmusicplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataSearchTest {
    @Test
    fun usesFieldsWhenQueryStartsWithArtist() =
        assertEquals("recording:\"Feel Good Inc.\" AND artist:\"Gorillaz\"", MetadataSearch.musicBrainzQuery("gorillaz Feel Good Inc.", "Gorillaz"))

    @Test
    fun durationFallbackNeedsArtistAndLength() {
        assertEquals("artist:\"RM\" AND dur:[196000 TO 202000]", MetadataSearch.musicBrainzDurationQuery("rm joke", "RM", 199000))
        assertEquals(null, MetadataSearch.musicBrainzDurationQuery("joke", "RM", 199000))
        assertEquals(null, MetadataSearch.musicBrainzDurationQuery("rm joke", "RM", 0))
    }

    @Test
    fun otherwiseSearchesTheTitleAndEscapesQuotes() =
        assertEquals("recording:\"a \\\"b\\\"\"", MetadataSearch.musicBrainzQuery("a \"b\"", "Otro"))
}
