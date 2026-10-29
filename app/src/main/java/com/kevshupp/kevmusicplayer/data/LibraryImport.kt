package com.kevshupp.kevmusicplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import com.kevshupp.kevmusicplayer.data.telegram.DeezerTrack
import com.kevshupp.kevmusicplayer.data.telegram.TelegramDownloadQueue
import java.io.File

/**
 * Mete en la biblioteca un audio que llegó de fuera (enviado desde el PC o descargado de Soulseek): toma
 * título, artista y álbum de sus etiquetas (o de las pistas si faltan), lo guarda en
 * `<carpeta principal>/Artista/Álbum/Artista - Título.ext` con copia verificada y lo escanea.
 */
object LibraryImport {
    class Imported(val file: File, val title: String, val artist: String)

    /** Bloquea hasta terminar el escaneo del archivo; llamar fuera del hilo principal. */
    fun import(context: Context, file: File, titleHint: String, artistHint: String, albumHint: String): Imported {
        val tags = readTags(file)
        val track = DeezerTrack(
            id = 0,
            title = tags[0].ifBlank { titleHint },
            artist = tags[1].ifBlank { artistHint },
            album = tags[2].ifBlank { albumHint },
            durationSec = 0, coverSmall = "", coverXl = "", link = ""
        )
        val saved = TelegramDownloadQueue.saveToMusicFolder(context, file, track)
        kotlinx.coroutines.runBlocking { TelegramDownloadQueue.scanFile(context, saved) }
        // El escaneo de la biblioteca lo hace el ViewModel (con la app cerrada, entra en el siguiente)
        Handler(Looper.getMainLooper()).post { TelegramDownloadQueue.onSongSaved?.invoke() }
        return Imported(saved, track.title, track.artist)
    }

    /** Título, artista (o el del álbum) y álbum de las etiquetas. */
    private fun readTags(file: File): List<String> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            listOf(
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim(),
                (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)).orEmpty().trim(),
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim()
            )
        } catch (e: Exception) {
            listOf("", "", "")
        } finally {
            r.release()
        }
    }
}
