package com.kevshupp.kevmusicplayer.data

import com.kevshupp.soulseek.Shares
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharesCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun old(f: File) { f.setLastModified(System.currentTimeMillis() - 60_000) }

    private fun library(): File {
        val root = tmp.newFolder("Music")
        val album = File(root, "BTS/Wings").apply { mkdirs() }
        File(album, "a.mp3").writeText("aaa")
        File(album, "cover.jpg").writeText("x")
        File(root, ".covers").mkdirs()
        File(root, ".covers/b.mp3").writeText("b")
        listOf(album, album.parentFile!!, root).forEach(::old)
        return root
    }

    @Test
    fun indexesAudioAndSkipsHidden() {
        val shares = Shares(tmp.newFile("c.txt"))
        val root = library()
        shares.rescan(listOf(root.path))
        assertEquals(1, shares.fileCount)
        assertNotNull(shares.find("Music\\BTS\\Wings\\a.mp3"))
        assertEquals("a.mp3", shares.search("wings").single().file.name)
    }

    @Test
    fun savedIndexIsUsedByNewInstanceWithoutListingUnchangedFolders() {
        val cache = File(tmp.root, "c.txt")
        val root = library()
        Shares(cache).rescan(listOf(root.path))
        // Archivo añadido restaurando la fecha: la carpeta "no cambió", así que no se relee
        val album = File(root, "BTS/Wings")
        val stamp = album.lastModified()
        File(album, "new.mp3").writeText("n")
        album.setLastModified(stamp)
        val shares = Shares(cache)
        shares.rescan(listOf(root.path))
        assertEquals(1, shares.fileCount)
    }

    @Test
    fun changedFolderIsReadAgainAndRemovedSongsLeave() {
        val cache = File(tmp.root, "c.txt")
        val root = library()
        val shares = Shares(cache)
        shares.rescan(listOf(root.path))
        val album = File(root, "BTS/Wings")
        File(album, "new.mp3").writeText("n")
        File(album, "a.mp3").delete()
        shares.rescan(listOf(root.path))
        assertEquals(1, shares.fileCount)
        assertNotNull(shares.find("Music\\BTS\\Wings\\new.mp3"))
        assertNull(shares.find("Music\\BTS\\Wings\\a.mp3"))
    }

    @Test
    fun corruptCacheIsIgnored() {
        val cache = File(tmp.root, "c.txt").apply { writeText("D\tbroken\nbasura") }
        val shares = Shares(cache)
        shares.rescan(listOf(library().path))
        assertEquals(1, shares.fileCount)
    }
}
