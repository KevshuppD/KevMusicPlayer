package com.kevshupp.kevmusicplayer

import org.junit.Test
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File

class MyAndroidArtwork : org.jaudiotagger.tag.images.Artwork {
    private var binaryData: ByteArray = ByteArray(0)
    private var mimeType: String = ""
    private var description: String = ""
    private var height: Int = 0
    private var width: Int = 0
    private var pictureType: Int = 0
    private var imageUrl: String = ""
    private var linked: Boolean = false

    override fun getBinaryData(): ByteArray = binaryData
    override fun setBinaryData(p0: ByteArray) { binaryData = p0 }
    override fun getMimeType(): String = mimeType
    override fun setMimeType(p0: String) { mimeType = p0 }
    override fun getDescription(): String = description
    override fun setDescription(p0: String) { description = p0 }
    override fun getHeight(): Int = height
    override fun setHeight(p0: Int) { height = p0 }
    override fun getWidth(): Int = width
    override fun setWidth(p0: Int) { width = p0 }
    override fun getPictureType(): Int = pictureType
    override fun setPictureType(p0: Int) { pictureType = p0 }
    override fun getImageUrl(): String = imageUrl
    override fun setImageUrl(p0: String) { imageUrl = p0 }
    override fun isLinked(): Boolean = linked
    override fun setLinked(p0: Boolean) { linked = p0 }
    
    override fun setImageFromData(): Boolean {
        // Bypass AWT/ImageIO completely!
        return true
    }
    
    override fun getImage(): Any? = null
    
    override fun setFromFile(p0: File) {
        binaryData = p0.readBytes()
    }
    
    override fun setFromMetadataBlockDataPicture(p0: org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture) {
        binaryData = p0.imageData
        mimeType = p0.mimeType
        description = p0.description
        height = p0.height
        width = p0.width
        pictureType = p0.pictureType
    }
}

class ExampleUnitTest {
    @Test
    fun testFlacArtwork() {
        val file = File("../scratch/06. Labrinth - Nate Growing Up.flac")
        println("File exists: ${file.exists()}")
        
        val audioFile = AudioFileIO.read(file)
        println("AudioFile class: ${audioFile.javaClass.name}")
        
        val tag = audioFile.getTagOrCreateAndSetDefault()
        println("Tag class: ${tag?.javaClass?.name}")
        
        // Let's create dummy artwork bytes (e.g. 100 bytes)
        val dummyBytes = ByteArray(100) { 1 }
        
        tag.deleteArtworkField()
        val artwork = MyAndroidArtwork()
        artwork.setBinaryData(dummyBytes)
        artwork.setMimeType("image/jpeg")
        artwork.setPictureType(PictureTypes.DEFAULT_ID)
        artwork.setWidth(100)
        artwork.setHeight(100)
        
        tag.setField(artwork)
        audioFile.tag = tag
        
        println("Tag artwork size before write: ${tag.artworkList?.size}")
        
        AudioFileIO.write(audioFile)
        
        val audioFile2 = AudioFileIO.read(file)
        val tag2 = audioFile2.tag
        println("Tag2 artwork size after re-read: ${tag2?.artworkList?.size}")
        if (tag2?.artworkList?.isNotEmpty() == true) {
            val art = tag2.artworkList[0]
            println("Re-read artwork class: ${art.javaClass.name}")
            println("Re-read artwork width: ${art.width}, height: ${art.height}, bytes: ${art.binaryData?.size}")
        }
    }
}