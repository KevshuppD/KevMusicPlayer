package com.kevshupp.kevmusic.desktop

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtMusicTest {
    @Test
    fun parsesMusicMetadata() {
        val t = YtMusic.parseTrack("""{"id":"NxxjLD2pmlk","title":"x","track":"Feel Good Inc.","artist":"Gorillaz, De La Soul","album":"Demon Days","duration":223,"release_year":2005}""")!!
        assertEquals("Feel Good Inc.", t.title)
        assertEquals("Gorillaz, De La Soul", t.artist)
        assertEquals("2005", t.year)
    }

    @Test
    fun fallsBackToChannelAndRejectsBadIds() {
        assertEquals("Foo", YtMusic.parseTrack("""{"id":"abcdefghijk","title":"T","channel":"Foo - Topic"}""")!!.artist)
        assertNull(YtMusic.parseTrack("""{"id":"--../x;rm","title":"T"}"""))
        assertNull(YtMusic.parseTrack("no json"))
    }

    @Test
    fun safeNames() = assertEquals("AC_DC - Who_", YtMusic.safeName("AC/DC - Who?"))

    @Test
    fun parsesAlbumAndArtist() {
        val entries = """"entries":[{"id":"QylI7_zJb_E","title":"Intro","uploader":"Gorillaz - Topic","duration":68},{"id":"GNJvu-DiiXc","title":"Last Living Souls","uploader":"Gorillaz","duration":196}]"""
        val album = YtMusic.parseAlbum("""{"id":"x","title":"Album - Demon Days",$entries}""")!!
        assertEquals("Demon Days", album.title)
        assertEquals("Gorillaz", album.artist)
        assertEquals(2, album.tracks[1].trackNo)
        assertEquals("Demon Days", album.tracks[0].album)
        assertEquals("Gorillaz", YtMusic.parseArtist("""{"id":"x","uploader":"Gorillaz","channel":"Gorillaz",$entries}""")!!.name)
        assertNull(YtMusic.parseAlbum("""{"id":"x","title":"Album - Vacio","entries":[]}"""))
    }

    @Test
    fun featDoesNotBreakMatch() = assertTrue(YtMusic.sameText("Dirty Harry (feat. Bootie Brown)", "Dirty Harry"))

    @Test
    fun videoTitlesAndLinks() {
        assertEquals("농담 (Joke)" to "RM", YtMusic.titleAndArtistFromVideo("RM '농담 (Joke)' Official MV", "HYBE LABELS"))
        assertEquals("Song" to "Artist", YtMusic.titleAndArtistFromVideo("Artist - Song (Official Video)", "x"))
        assertEquals("Song" to "Canal", YtMusic.titleAndArtistFromVideo("Song", "Canal - Topic"))
        assertEquals("05T31WQ3HVk", YtMusic.videoIdFrom("https://www.youtube.com/watch?v=05T31WQ3HVk&t=3"))
        assertEquals("05T31WQ3HVk", YtMusic.videoIdFrom("https://youtu.be/05T31WQ3HVk"))
        assertNull(YtMusic.videoIdFrom("https://evil.com/watch?v=05T31WQ3HVk"))
        assertNull(YtMusic.videoIdFrom("gorillaz"))
    }

    private fun sc(title: String, up: String, d: Int) = ScCandidate("sc_$d", title, up, d, "https://soundcloud.com/x", "")

    @Test
    fun picksTheOriginalUpload() {
        val list = listOf(sc("RM - joke (nightcore)", "Blue", 199), sc("RM - Joke", "BANGTAN MGL", 199), sc("Joke", "otro", 120))
        assertEquals("BANGTAN MGL", YtMusic.pickCandidate("Joke", "RM", 199, list)!!.uploader)
        assertNull(YtMusic.pickCandidate("Joke", "RM", 300, list))
        assertEquals("BANGTAN MGL", YtMusic.pickCandidate("冗談", "RM", 199, list)!!.uploader)
    }

    @Test
    fun choosesReadableEdition() {
        val cjk = MbRelease("a", "RM", "2015", true, listOf(MbTrack(1, "冗談", 199), MbTrack(2, "Do You", 181)))
        val latin = MbRelease("b", "RM", "2016", false, listOf(MbTrack(1, "Joke", 199), MbTrack(2, "Do You", 181)))
        assertEquals("b", MusicBrainz.chooseRelease(listOf(cjk, latin))!!.id)
    }

    /** Red real, sin enviar nada al teléfono: KEV_YT_E2E=1 ./gradlew test */
    @Test
    fun endToEnd() {
        if (System.getenv("KEV_YT_E2E") != "1") return
        runBlocking {
            val t = YtMusic.search("gorillaz feel good inc", 1).first()
            val audio = YtMusic.download(t) {}
            val out = YtMusic.tagged(t, audio)
            val p = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,bit_rate:stream_disposition=attached_pic:format_tags", "-of", "default=nw=1", out.path).redirectErrorStream(true).start()
            val info = p.inputStream.bufferedReader().readText()
            p.waitFor(20, TimeUnit.SECONDS)
            println("E2E ${out.name} ${out.length()}\n$info")
            assertTrue("codec_name=aac" in info && "TAG:title=Feel Good Inc." in info && "attached_pic=1" in info && "TAG:track=" in info, info)
            audio.delete(); out.delete()
            val video = YtMusic.search("https://www.youtube.com/watch?v=05T31WQ3HVk").first()
            val vAudio = YtMusic.download(video) {}
            val vOut = YtMusic.tagged(video, vAudio)
            println("E2E video ${video.title} / ${video.artist} / ${video.year} / ${video.durationSec}s -> ${vOut.name} ${vOut.length()}")
            assertEquals("RM", video.artist)
            vAudio.delete(); vOut.delete()
            println("E2E search " + YtMusic.searchVideos("RM Joke official MV", 3).toList().map { it.title + "/" + it.artist })
            val artist = MusicBrainz.searchArtists("RM").first { "BTS" in it.note }
            val group = MusicBrainz.releaseGroups(artist.id).first { it.title == "RM" && "Mixtape" in it.type }
            val rel = MusicBrainz.bestRelease(group.id)!!
            println("E2E mb ${group.type} ${group.date} ${rel.tracks.size}")
            for (t in rel.tracks) {
                val c = YtMusic.pickCandidate(t.title, artist.name, t.lengthSec, YtMusic.searchSoundCloud("${artist.name} ${t.title}"))
                println("E2E slot ${t.position} ${t.title} ${t.lengthSec}s -> ${c?.title} | ${c?.uploader} | ${c?.durationSec}")
            }
            val first = YtMusic.pickCandidate("Do You", "RM", 181, YtMusic.searchSoundCloud("RM Do You"))!!
            val st = YtTrack(first.id, "Do You", "RM", "RM", 181, group.coverUrl, "2015", 2, first.url, true, first.artwork)
            val sAudio = YtMusic.download(st) {}
            val sOut = YtMusic.tagged(st, sAudio)
            val sp = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,bit_rate:stream_disposition=attached_pic:format_tags", "-of", "default=nw=1", sOut.path).redirectErrorStream(true).start()
            println("E2E sc " + sp.inputStream.bufferedReader().readText().replace("\n", " "))
            sAudio.delete(); sOut.delete()
            val albums = YtMusic.searchAlbums("gorillaz demon days", 5).toList()
            println("E2E albums " + albums.sortedBy { it.first }.map { "${it.second.title}/${it.second.artist}/${it.second.kind}/${it.second.tracks.size}" })
            assertTrue(albums.any { it.second.title == "Demon Days" && it.second.tracks.size >= 10 })
            val artists = YtMusic.searchArtists("gorillaz", 3).toList()
            println("E2E artists " + artists.sortedBy { it.first }.map { "${it.second.name}/${it.second.songs.size}" })
            assertTrue(artists.any { it.second.name == "Gorillaz" && it.second.songs.isNotEmpty() })
        }
    }
}
