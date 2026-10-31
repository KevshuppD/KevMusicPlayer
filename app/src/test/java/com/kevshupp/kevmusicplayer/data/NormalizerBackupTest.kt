package com.kevshupp.kevmusicplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NormalizerBackupTest {
    private val song = AudioFile(1, "Amor Eterno En el Palacio de Bellas Artes", "Juan Gabriel", "Lo Mejor", "", 422_000, "content://x/1")

    @Test
    fun acceptsSameArtistSimilarTitleAndDuration() {
        val picked = LibraryNormalizer.pickRecording(song, listOf(
            MbRecording("Amor eterno", "Juan Gabriel", "Querida", 542, ""),
            MbRecording("Amor Eterno (En Vivo)", "Juan Gabriel", "Amor del Bueno", 422, "2010")
        ))
        assertEquals("Amor del Bueno", picked!!.album)
    }

    @Test
    fun rejectsOtherArtistOrUnknownLength() {
        assertNull(LibraryNormalizer.pickRecording(song, listOf(
            MbRecording("Amor eterno", "Otro", "A", 422, ""),
            MbRecording("Amor eterno", "Juan Gabriel", "B", 0, "")
        )))
    }
}
