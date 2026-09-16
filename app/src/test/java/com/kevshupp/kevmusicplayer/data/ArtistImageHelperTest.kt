package com.kevshupp.kevmusicplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistImageHelperTest {

    @Test
    fun cleanArtistSearchName_preservesLegendaryBandNames() {
        assertEquals("AC/DC", ArtistImageHelper.cleanArtistSearchName("AC/DC"))
        assertEquals("P!nk", ArtistImageHelper.cleanArtistSearchName("P!nk"))
    }

    @Test
    fun cleanArtistSearchName_filtersOutGenericAndUnknownNames() {
        assertNull(ArtistImageHelper.cleanArtistSearchName("unknown"))
        assertNull(ArtistImageHelper.cleanArtistSearchName("<unknown>"))
        assertNull(ArtistImageHelper.cleanArtistSearchName("Various Artists"))
        assertNull(ArtistImageHelper.cleanArtistSearchName("Soundtrack"))
        assertNull(ArtistImageHelper.cleanArtistSearchName("artista desconocido"))
        assertNull(ArtistImageHelper.cleanArtistSearchName(""))
        assertNull(ArtistImageHelper.cleanArtistSearchName("   "))
    }

    @Test
    fun cleanArtistSearchName_removesFeaturingAndCollaborations() {
        assertEquals("Drake", ArtistImageHelper.cleanArtistSearchName("Drake ft. Rihanna"))
        assertEquals("Eminem", ArtistImageHelper.cleanArtistSearchName("Eminem feat. 50 Cent"))
        assertEquals("Calvin Harris", ArtistImageHelper.cleanArtistSearchName("Calvin Harris & Dua Lipa"))
        assertEquals("Coldplay", ArtistImageHelper.cleanArtistSearchName("Coldplay x BTS"))
        assertEquals("The Weeknd", ArtistImageHelper.cleanArtistSearchName("The Weeknd (feat. Daft Punk)"))
        assertEquals("Marshmello", ArtistImageHelper.cleanArtistSearchName("Marshmello ; Bastille"))
    }

    @Test
    fun cleanArtistSearchName_cleansOfficialVideoAndAudioTags() {
        assertEquals("Queen", ArtistImageHelper.cleanArtistSearchName("Queen (Official Video)"))
        assertEquals("Metallica", ArtistImageHelper.cleanArtistSearchName("Metallica [Remastered]"))
        assertEquals("Nirvana", ArtistImageHelper.cleanArtistSearchName("Nirvana (Live)"))
    }
}
