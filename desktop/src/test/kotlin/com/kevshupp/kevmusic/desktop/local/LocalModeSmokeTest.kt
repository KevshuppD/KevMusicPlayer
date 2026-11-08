package com.kevshupp.kevmusic.desktop.local

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** Prueba de humo del modo local con la música real del PC: escanea y reproduce 3 s con volumen 0. */
class LocalModeSmokeTest {
    @Test fun scanAndPlay() = runBlocking {
        val folder = LocalLibrary.defaultFolder()
        if (!folder.isDirectory) return@runBlocking
        val tracks = LocalLibrary.scan(listOf(folder))
        println("Escaneadas: ${tracks.size} en $folder")
        tracks.take(3).forEach { println("  ${it.song.artist} - ${it.song.title} [${it.song.album}] ${it.song.duration} ms, art=${LocalLibrary.artBytes(it)?.size}") }
        if (tracks.isEmpty()) return@runBlocking
        val mpv = Mpv.create()
        println("mpv: ${mpv != null} ${Mpv.unavailableReason ?: ""}")
        assertTrue(mpv != null)
        var pos = 0.0; var dur = 0.0
        mpv.listener = object : Mpv.Listener {
            override fun onPosition(seconds: Double) { pos = seconds }
            override fun onDuration(seconds: Double) { dur = seconds }
            override fun onPause(paused: Boolean) {}
            override fun onEndOfFile() {}
            override fun onError(message: String) { println("ERROR $message") }
        }
        mpv.setVolume(0)
        mpv.load(tracks.first().path)
        Thread.sleep(3000)
        println("posición %.2f s de %.2f s".format(pos, dur))
        assertTrue(pos > 1.0)
        mpv.seek(30.0)
        Thread.sleep(800)
        println("tras seek: %.2f s".format(pos))
        assertTrue(pos >= 30.0)
        mpv.close()
    }
}
