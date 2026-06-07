package com.kevshupp.kevmusicplayer

import org.junit.Test
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File

class ExampleUnitTest {
    @Test
    fun testFlacArtwork() {
        val file = File("scratch/06. Labrinth - Nate Growing Up.flac")
        println("File exists: ${file.exists()}")
        
        val audioFile = AudioFileIO.read(file)
        println("AudioFile class: ${audioFile.javaClass.name}")
        
        val tag = audioFile.getTagOrCreateAndSetDefault()
        println("Tag class: ${tag?.javaClass?.name}")
        
        // Let's create dummy artwork bytes (e.g. 100 bytes)
        val dummyBytes = ByteArray(100) { 1 }
        
        tag.deleteArtworkField()
        val artwork = ArtworkFactory.getNew()
        artwork.binaryData = dummyBytes
        artwork.mimeType = "image/jpeg"
        artwork.pictureType = PictureTypes.DEFAULT_ID
        
        tag.setField(artwork)
        audioFile.tag = tag
        
        println("Tag artwork size before write: ${tag.artworkList?.size}")
        
        AudioFileIO.write(audioFile)
        
        val audioFile2 = AudioFileIO.read(file)
        val tag2 = audioFile2.tag
        println("Tag2 artwork size after re-read: ${tag2?.artworkList?.size}")
        
        val flacPictures = if (audioFile2 is org.jaudiotagger.audio.flac.FlacFile) {
            audioFile2.images
        } else {
            null
        }
        println("FlacFile images count: ${flacPictures?.size}")
    }
}