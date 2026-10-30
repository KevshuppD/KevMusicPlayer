package com.kevshupp.kevmusicplayer.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.ui.theme.PixelPalette
import java.util.WeakHashMap

/**
 * Portada de una lista o mezcla hecha con las carátulas de sus canciones: 2×2 con cuatro álbumes distintos, o la
 * del primero si hay menos. Antes las listas sin portada propia mostraban solo un degradado con un icono genérico.
 * [thumbnail]: miniaturas (filas y tarjetas pequeñas) o carátulas completas (tarjetas grandes).
 */
@Composable
fun SongMosaic(songs: List<AudioFile>, modifier: Modifier = Modifier, thumbnail: Boolean = true) {
    val covers = remember(songs) { songs.distinctBy { it.album.trim().lowercase() }.take(4) }
    if (covers.isEmpty()) return
    if (covers.size < 4) {
        AlbumArtImage(rememberAlbumArt(covers.first().uriString, thumbnail), modifier, contentScale = ContentScale.Crop)
        return
    }
    Column(modifier) {
        for (row in 0..1) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0..1) {
                    AlbumArtImage(
                        rememberAlbumArt(covers[row * 2 + col].uriString, thumbnail = true),
                        Modifier.weight(1f).fillMaxHeight(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

/**
 * Carátulas en pixel art (Ajustes › Apariencia, "Carátulas en pixel art"): la imagen se reduce a unos bloques por
 * lado según el nivel (suave 64 … extremo 20; la mitad en miniaturas) y se vuelve a ampliar sin filtrar. Con
 * "Colores de la paleta" y un tema Pixel Retro, cada bloque toma además el color de la paleta que corresponde a su
 * luminosidad. Se calcula una vez por carátula, nivel y paleta, y se recuerda mientras la original siga en caché.
 */
object PixelArtCovers {
    /** Bloques por lado de cada nivel: suave, medio, fuerte, extremo. */
    private val BLOCKS = intArrayOf(64, 44, 30, 20)
    private val THUMB_BLOCKS = intArrayOf(32, 24, 16, 12)
    private val cache = WeakHashMap<Bitmap, Pair<String, Bitmap>>()

    fun of(src: Bitmap, thumbnail: Boolean, level: Int, palette: PixelPalette?): Bitmap {
        if (src.isRecycled) return src
        val lv = level.coerceIn(0, 3)
        val key = "$lv/${palette?.key}"
        synchronized(cache) { cache[src]?.takeIf { it.first == key }?.let { return it.second } }
        val blocks = if (thumbnail) THUMB_BLOCKS[lv] else BLOCKS[lv]
        val small = Bitmap.createScaledBitmap(src, blocks, blocks, true)
        val colored = if (palette != null) quantize(small, palette) else small
        // Ampliada sin filtrar (≈ 256 px, 128 en miniaturas): bordes nítidos al dibujarla a cualquier tamaño
        val side = blocks * ((if (thumbnail) 128 else 256) / blocks).coerceAtLeast(2)
        val out = Bitmap.createScaledBitmap(colored, side, side, false)
        // createScaledBitmap devuelve la misma si ya tiene ese tamaño: nunca reciclar la original de la caché
        if (colored !== small && colored !== out) colored.recycle()
        if (small !== out && small !== src) small.recycle()
        synchronized(cache) { cache[src] = key to out }
        return out
    }

    /** Cada píxel toma el color de la paleta (ordenada por luminosidad) que corresponde a la suya. */
    private fun quantize(src: Bitmap, palette: PixelPalette): Bitmap {
        fun luma(c: Int) = 0.299f * android.graphics.Color.red(c) + 0.587f * android.graphics.Color.green(c) + 0.114f * android.graphics.Color.blue(c)
        val ramp = palette.swatch.map { it.toArgb() }.distinct().sortedBy { luma(it) }
        if (ramp.size < 2) return src
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        // Contraste automático: del más oscuro al más claro de esta carátula
        val lumas = px.map { luma(it) }
        val lo = lumas.minOrNull() ?: 0f; val hi = (lumas.maxOrNull() ?: 255f).coerceAtLeast(lo + 1f)
        for (i in px.indices) {
            val t = ((lumas[i] - lo) / (hi - lo)).coerceIn(0f, 1f)
            px[i] = ramp[(t * (ramp.size - 1) + 0.5f).toInt()]
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }
}
