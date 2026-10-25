package com.kevshupp.kevmusicplayer.playback.managers

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.core.content.res.ResourcesCompat
import androidx.palette.graphics.Palette
import com.kevshupp.kevmusicplayer.R
import com.kevshupp.kevmusicplayer.data.LyricLine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class ClipTemplateStyle {
    LYRICS_STORY,      // Tarjeta de letra (estilo Spotify / Apple Music)
    PLAYER_CANVAS,     // Carátula grande con la línea activa
    VINYL_VIBES,       // Vinilo girando junto a la carátula
    PIXEL_ART          // Escena a baja resolución ampliada sin suavizado, con fuente pixel
}

/** Proporción del video. El lienzo lógico mide 720 de ancho (1280 en horizontal) y se escala a la calidad elegida. */
enum class ClipFormat(val w: Int, val h: Int) {
    STORY(9, 16), PORTRAIT(4, 5), SQUARE(1, 1), LANDSCAPE(16, 9);

    val logicalWidth get() = if (this == LANDSCAPE) 1280 else 720
    val logicalHeight get() = if (this == LANDSCAPE) 720 else (720f * h / w / 16f).roundToInt() * 16
}

enum class ClipQuality(val scale: Float, val bitRate: Int) { HD(1f, 6_000_000), FULL_HD(1.5f, 10_000_000) }

enum class ClipFont { SANS, SERIF, MONO, CONDENSED, PIXEL }

enum class ClipBackground { AMBIENT, BLURRED_ART, AMOLED }

/** Carátula en Pixel art: con los colores de la paleta, pixelada con sus colores o tal cual (nítida). */
enum class PixelArtMode { PALETTE, PIXELATED, ORIGINAL }

data class ShareClipConfig(
    val songTitle: String,
    val artistName: String,
    val albumTitle: String? = null,
    val albumArtBitmap: Bitmap? = null,
    val startMs: Long,
    val endMs: Long,
    val audioUri: Uri,
    val lyricsLines: List<LyricLine> = emptyList(),
    val translatedLyrics: Map<Long, String>? = null,
    val showTranslation: Boolean = false,
    val dedicationText: String? = null,
    val format: ClipFormat = ClipFormat.STORY,
    val quality: ClipQuality = ClipQuality.HD,
    val templateStyle: ClipTemplateStyle = ClipTemplateStyle.LYRICS_STORY,
    val background: ClipBackground = ClipBackground.AMBIENT,
    val font: ClipFont = ClipFont.SANS,
    val uppercase: Boolean = false,
    /** Multiplica el tamaño de la letra (0.85 / 1 / 1.2). */
    val textScale: Float = 1f,
    val showWaveform: Boolean = true,
    /** Cuadros por segundo del video: 24, 30 o 60. */
    val fps: Int = 30,
    val primaryColor: Int = 0xFFFF4081.toInt(),
    val accentColor: Int = 0xFFB388FF.toInt(),
    /** Plantilla Pixel art: fondo, superficie, principal, secundario y texto (paletas del tema Pixel Retro). */
    val pixelArtMode: PixelArtMode = PixelArtMode.PALETTE,
    val pixelColors: IntArray = intArrayOf(0xFF130E26.toInt(), 0xFF1D2B53.toInt(), 0xFFFF77A8.toInt(), 0xFF29ADFF.toInt(), 0xFFFFF1E8.toInt())
) {
    val isVertical get() = format == ClipFormat.STORY
    val isLandscape get() = format == ClipFormat.LANDSCAPE
}

/**
 * Dibuja los cuadros del clip sobre un lienzo lógico ([ClipFormat.logicalWidth] × [ClipFormat.logicalHeight]); quien
 * lo usa escala el Canvas a la resolución real. Lo usan el codificador (en la superficie de MediaCodec, con GPU) y la
 * vista previa del diálogo, así que lo que se ve es lo que sale.
 *
 * Sin BlurMaskFilter ni otros efectos que el lienzo por hardware no admite: los brillos son RadialGradient y la
 * portada difuminada se calcula una vez (reducir y ampliar).
 */
class ClipRenderer(context: Context, val config: ShareClipConfig) {
    val width = config.format.logicalWidth.toFloat()
    val height = config.format.logicalHeight.toFloat()

    private val dominantColor: Int
    private val accentColor: Int
    private val regularTf: Typeface
    private val boldTf: Typeface
    private val italicTf: Typeface
    private val clipLines: List<LyricLine>
    private val blurredArt: Bitmap?

    // Pixel art: la escena se dibuja a 1/PX y se amplía sin filtro
    private val pixelBitmap: Bitmap?
    private val pixelCanvas: Canvas?
    private val pixelArt: Bitmap?
    private val nearest = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    /** Dónde quedó la carátula en la escena pixel (en píxeles de la escena), para dibujarla nítida encima. */
    private var lastPixelArtRect = RectF()
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    init {
        val colors = extractArtworkColors(config.albumArtBitmap, config.primaryColor, config.accentColor)
        dominantColor = colors.first
        accentColor = colors.second

        // Por nombre de familia y estilo: Typeface.create(base, peso, cursiva) volvía a la fuente normal en
        // algunos teléfonos (la Clásica salía sin serifas)
        val family = when (config.font) {
            ClipFont.SANS -> "sans-serif"
            ClipFont.SERIF -> "serif"
            ClipFont.MONO -> "monospace"
            ClipFont.CONDENSED -> "sans-serif-condensed"
            ClipFont.PIXEL -> null
        }
        if (family != null) {
            regularTf = Typeface.create(family, Typeface.NORMAL)
            boldTf = Typeface.create(family, Typeface.BOLD)
            italicTf = if (config.font == ClipFont.MONO) regularTf else Typeface.create(family, Typeface.ITALIC)
        } else {
            val pixel = runCatching { ResourcesCompat.getFont(context, R.font.pixelify_sans) }.getOrNull() ?: Typeface.MONOSPACE
            regularTf = Typeface.create(pixel, 400, false)
            boldTf = Typeface.create(pixel, 700, false)
            italicTf = regularTf
        }

        clipLines = config.lyricsLines.filter {
            it.timeMs >= config.startMs - LINE_TOLERANCE_MS && it.timeMs < config.endMs - LINE_TOLERANCE_MS
        }

        val art = config.albumArtBitmap?.takeIf { !it.isRecycled }
        blurredArt = if (config.background == ClipBackground.BLURRED_ART && art != null) {
            // Reducir a 20 px y volver a ampliar con filtro = desenfoque barato y suave
            val tiny = Bitmap.createScaledBitmap(art, 20, 20, true)
            Bitmap.createScaledBitmap(tiny, 240, 240, true).also { if (tiny !== art) tiny.recycle() }
        } else null

        if (config.templateStyle == ClipTemplateStyle.PIXEL_ART) {
            pixelBitmap = Bitmap.createBitmap((width / PX).toInt(), (height / PX).toInt(), Bitmap.Config.ARGB_8888)
            pixelCanvas = Canvas(pixelBitmap)
            val artPx = pixelArtSize().toInt().coerceAtLeast(8)
            pixelArt = art?.let {
                val small = Bitmap.createScaledBitmap(it, artPx, artPx, true)
                if (config.pixelArtMode == PixelArtMode.PALETTE) quantizeToPalette(small, config.pixelColors) else small
            }
        } else {
            pixelBitmap = null; pixelCanvas = null; pixelArt = null
        }
    }

    fun release() {
        blurredArt?.recycle()
        pixelBitmap?.recycle()
        pixelArt?.takeIf { it !== config.albumArtBitmap }?.recycle()
    }

    /**
     * Dibuja el cuadro del instante [timeMs] (dentro del fragmento) en un Canvas ya escalado al lienzo lógico.
     * [frame] cuenta en pasos de 1/30 s sea cual sea el fps del video, así las animaciones van a la misma
     * velocidad a 24, 30 o 60 fps (a 60 solo se ven más fluidas).
     */
    fun draw(canvas: Canvas, timeMs: Long, frame: Float) {
        val progress = ((timeMs - config.startMs).toFloat() / (config.endMs - config.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
        if (config.templateStyle == ClipTemplateStyle.PIXEL_ART) {
            val pc = pixelCanvas!!
            drawPixelScene(pc, width / PX, height / PX, timeMs, progress, frame)
            canvas.drawBitmap(pixelBitmap!!, null, RectF(0f, 0f, width, height), nearest)
            val original = config.albumArtBitmap
            if (config.pixelArtMode == PixelArtMode.ORIGINAL && original != null && !original.isRecycled) {
                val r = lastPixelArtRect
                canvas.drawBitmap(original, null, RectF(r.left * PX, r.top * PX, r.right * PX, r.bottom * PX), smooth)
            }
            return
        }
        drawBackground(canvas, frame)
        when (config.templateStyle) {
            ClipTemplateStyle.LYRICS_STORY -> drawLyricsStory(canvas, timeMs)
            ClipTemplateStyle.PLAYER_CANVAS -> drawPlayerCanvas(canvas, timeMs)
            ClipTemplateStyle.VINYL_VIBES -> drawVinyl(canvas, timeMs, frame)
            ClipTemplateStyle.PIXEL_ART -> Unit
        }
        if (config.showWaveform) drawWaveform(canvas, frame)
        drawProgressBar(canvas, progress)
        config.dedicationText?.takeIf { it.isNotBlank() }?.let {
            drawDedication(canvas, if (config.isVertical) 95f else 42f, it)
        }
    }

    // ---------- Texto ----------

    private fun cased(s: String) = if (config.uppercase) s.uppercase() else s

    private fun textPaint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) boldTf else regularTf
            textAlign = align
        }

    /** Traducción de la línea: exacta o la clave más cercana a menos de 400 ms (letra vuelta a descargar). */
    private fun translationOf(line: LyricLine): String? {
        if (!config.showTranslation) return null
        val map = config.translatedLyrics ?: return null
        map[line.timeMs]?.takeIf { it.isNotBlank() }?.let { return it }
        val near = map.keys.minByOrNull { abs(it - line.timeMs) } ?: return null
        return if (abs(near - line.timeMs) <= 400) map[near]?.takeIf { it.isNotBlank() } else null
    }

    private fun activeLine(timeMs: Long): LyricLine? =
        config.lyricsLines.lastOrNull { it.timeMs <= timeMs + 50 }

    // ---------- Fondo ----------

    private fun drawBackground(canvas: Canvas, frame: Float) {
        when (config.background) {
            ClipBackground.AMOLED -> canvas.drawColor(Color.BLACK)
            ClipBackground.BLURRED_ART -> {
                val art = blurredArt
                if (art == null) { drawAmbient(canvas, frame); return }
                // Zoom lento para que el fondo respire
                val zoom = 1.15f + 0.05f * sin(frame * 0.02f)
                val side = maxOf(width, height) * zoom
                val left = (width - side) / 2f
                val top = (height - side) / 2f
                canvas.drawBitmap(art, null, RectF(left, top, left + side, top + side), smooth)
                canvas.drawColor(Color.argb(120, 0, 0, 0))
            }
            ClipBackground.AMBIENT -> drawAmbient(canvas, frame)
        }
    }

    private fun drawAmbient(canvas: Canvas, frame: Float) {
        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width, height,
                intArrayOf(Color.rgb(10, 11, 20), Color.rgb(18, 14, 28), Color.rgb(6, 7, 14)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width, height, bg)
        val breathe = sin(frame * 0.06f) * 24f
        glow(canvas, width * 0.25f, height * 0.28f, 340f + breathe, dominantColor, 95)
        glow(canvas, width * 0.75f, height * 0.68f, 360f - breathe, accentColor, 80)
    }

    private fun glow(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Int) {
        val c = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, r, c, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, r, p)
    }

    // ---------- Plantilla Letras ----------

    private fun drawLyricsStory(canvas: Canvas, timeMs: Long) {
        val margin = if (config.isLandscape) 70f else 40f
        val cardTop = if (config.isVertical) 160f else 70f
        val cardBottom = height - if (config.isVertical) 180f else 80f
        val card = RectF(margin, cardTop, width - margin, cardBottom)
        canvas.drawRoundRect(card, 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 0, 0, 0); setShadowLayer(30f, 0f, 15f, Color.argb(160, 0, 0, 0))
        })
        canvas.drawRoundRect(card, 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 255, 255, 255) })
        canvas.drawRoundRect(card, 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f
        })

        val badgeHeight = 90f
        val badgeTop = cardBottom - badgeHeight - 24f
        val lines = clipLines.ifEmpty { config.lyricsLines.take(4) }
        if (lines.isNotEmpty()) {
            val current = lines.indexOfLast { it.timeMs <= timeMs + 50 }
            val left = margin + 35f
            val maxW = card.width() - 70f
            val top = cardTop + if (config.isVertical) 60f else 40f
            val available = badgeTop - 30f - top

            val tp = textPaint(10f, Color.WHITE)
            val trp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = italicTf }

            class Laid(val lines: List<String>, val trans: List<String>, val h: Float)
            fun layout(base: Float) = lines.mapIndexed { i, line ->
                val size = if (i == current) base * 1.15f else base
                tp.textSize = size
                tp.typeface = if (i == current) boldTf else regularTf
                val wrapped = wrapText(cased(line.text), tp, maxW)
                trp.textSize = base * 0.72f
                val trans = translationOf(line)?.let { wrapText(it, trp, maxW) } ?: emptyList()
                Laid(wrapped, trans, wrapped.size * size * 1.3f + trans.size * base * 0.95f + base * 0.45f)
            }

            var base = config.textScale * when {
                lines.size <= 2 -> if (config.isVertical) 42f else 34f
                lines.size <= 4 -> if (config.isVertical) 34f else 28f
                else -> if (config.isVertical) 28f else 23f
            }
            val minSize = if (config.isVertical) 18f else 15f
            var laid = layout(base)
            while (laid.sumOf { it.h.toDouble() } > available && base > minSize) {
                base = (base - 1f).coerceAtLeast(minSize)
                laid = layout(base)
            }
            // Si no cabe ni al mínimo, ventana que sigue a la línea activa
            var first = 0
            if (laid.sumOf { it.h.toDouble() } > available) {
                val anchor = current.coerceAtLeast(0)
                first = anchor
                var used = laid[anchor].h
                while (first > 0 && used + laid[first - 1].h <= available * 0.4f) { first--; used += laid[first].h }
            }
            var y = top
            for (i in first until laid.size) {
                val item = laid[i]
                if (y + item.h > top + available + base) break
                val active = i == current
                val size = if (active) base * 1.15f else base
                tp.apply {
                    color = if (active) Color.WHITE else Color.argb(130, 255, 255, 255)
                    textSize = size
                    typeface = if (active) boldTf else regularTf
                    if (active) setShadowLayer(16f, 0f, 0f, Color.argb(180, 255, 255, 255)) else clearShadowLayer()
                }
                for (t in item.lines) { y += size; canvas.drawText(t, left, y, tp); y += size * 0.3f }
                trp.color = if (active) Color.argb(235, 220, 230, 255) else Color.argb(110, 200, 210, 235)
                trp.textSize = base * 0.72f
                for (t in item.trans) { y += base * 0.72f; canvas.drawText(t, left, y, trp); y += base * 0.23f }
                y += base * 0.45f
            }
        }

        val badgeLeft = margin + 28f
        val artSize = 80f
        drawArt(canvas, RectF(badgeLeft, badgeTop, badgeLeft + artSize, badgeTop + artSize), 16f)
        val textLeft = badgeLeft + artSize + 20f
        val maxW = card.width() - (badgeLeft - margin) - artSize - 40f
        val title = textPaint(28f, Color.WHITE, bold = true)
        val artist = textPaint(22f, Color.argb(200, 220, 225, 240))
        canvas.drawText(truncate(cased(config.songTitle), title, maxW), textLeft, badgeTop + 36f, title)
        canvas.drawText(truncate(config.artistName, artist, maxW), textLeft, badgeTop + 70f, artist)
    }

    // ---------- Plantilla Portada ----------

    /** Posición de la carátula y de la columna de texto; en horizontal, carátula a la izquierda y texto a la derecha. */
    private class Layout(val art: RectF, val textCenterX: Float, val textWidth: Float, val textTop: Float)

    private fun mediaLayout(artFactorPortrait: Float, artFactorSquare: Float, leftShift: Float = 0f): Layout {
        if (config.isLandscape) {
            val size = height * 0.62f
            val top = (height - size) / 2f - 20f
            val left = 90f
            val art = RectF(left, top, left + size, top + size)
            val textLeft = art.right + 60f + leftShift
            return Layout(art, (textLeft + width - 60f) / 2f, width - 60f - textLeft, top + 70f)
        }
        val size = width * if (config.isVertical) artFactorPortrait else artFactorSquare
        // En 4:5 y 1:1 el bloque se centra en la altura que sobra
        val extra = if (config.isVertical) 0f else (height - 720f) / 2f
        val top = (if (config.isVertical) 160f else 90f) + extra
        val left = (width - size) / 2f - leftShift
        val art = RectF(left, top, left + size, top + size)
        return Layout(art, width / 2f, width - 80f, art.bottom + 48f)
    }

    private fun drawPlayerCanvas(canvas: Canvas, timeMs: Long) {
        val l = mediaLayout(0.62f, 0.42f)
        canvas.drawRoundRect(l.art, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 0, 0, 0); setShadowLayer(40f, 0f, 20f, Color.argb(200, 0, 0, 0))
        })
        drawArt(canvas, l.art, 28f)
        drawTitleBlock(canvas, l, 36f, 24f)
        drawActiveLyric(canvas, l, timeMs, l.textTop + if (config.isLandscape) 130f else 105f)
    }

    // ---------- Plantilla Vinilo ----------

    private fun drawVinyl(canvas: Canvas, timeMs: Long, frame: Float) {
        val l = mediaLayout(0.52f, 0.38f, leftShift = if (config.isLandscape) 0f else 45f)
        val art = l.art
        val vinylSize = art.width() * 0.94f
        val cx = art.left + art.width() * 0.90f
        val cy = art.centerY()
        canvas.save()
        canvas.rotate((frame * 3.5f) % 360f, cx, cy)
        canvas.drawCircle(cx, cy, vinylSize / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(245, 16, 16, 20) })
        val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255); strokeWidth = 2f; style = Paint.Style.STROKE }
        for (f in listOf(0.40f, 0.30f, 0.20f)) canvas.drawCircle(cx, cy, vinylSize * f, groove)
        // Reflejo para que se note el giro
        canvas.drawArc(RectF(cx - vinylSize * 0.45f, cy - vinylSize * 0.45f, cx + vinylSize * 0.45f, cy + vinylSize * 0.45f),
            -60f, 40f, false, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255); strokeWidth = 6f; style = Paint.Style.STROKE })
        canvas.drawCircle(cx, cy, vinylSize * 0.12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dominantColor })
        canvas.drawCircle(cx, cy, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        canvas.restore()

        canvas.drawRoundRect(art, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 0, 0, 0); setShadowLayer(30f, 0f, 15f, Color.argb(200, 0, 0, 0))
        })
        drawArt(canvas, art, 22f)
        // En horizontal el texto va a la derecha del vinilo
        val textLayout = if (config.isLandscape) {
            val textLeft = cx + vinylSize / 2f + 40f
            Layout(art, (textLeft + width - 50f) / 2f, width - 50f - textLeft, l.textTop)
        } else l
        drawTitleBlock(canvas, textLayout, 34f, 23f)
        drawActiveLyric(canvas, textLayout, timeMs, textLayout.textTop + if (config.isLandscape) 125f else 110f)
    }

    private fun drawTitleBlock(canvas: Canvas, l: Layout, titleSize: Float, artistSize: Float) {
        val title = textPaint(titleSize, Color.WHITE, bold = true, align = Paint.Align.CENTER)
        val artist = textPaint(artistSize, Color.argb(190, 220, 225, 245), align = Paint.Align.CENTER)
        canvas.drawText(truncate(cased(config.songTitle), title, l.textWidth), l.textCenterX, l.textTop, title)
        canvas.drawText(truncate(config.artistName, artist, l.textWidth), l.textCenterX, l.textTop + titleSize + 4f, artist)
    }

    private fun drawActiveLyric(canvas: Canvas, l: Layout, timeMs: Long, top: Float) {
        val line = activeLine(timeMs) ?: return
        val paint = textPaint(30f * config.textScale, Color.WHITE, bold = true, align = Paint.Align.CENTER).apply {
            setShadowLayer(16f, 0f, 0f, accentColor)
        }
        val bottom = drawWrappedCentered(canvas, "“${cased(line.text)}”", paint, l.textWidth - 20f, l.textCenterX, top, 3, 20f)
        translationOf(line)?.let { trans ->
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 21f * config.textScale; color = Color.argb(210, 220, 230, 255)
                typeface = italicTf; textAlign = Paint.Align.CENTER
            }
            drawWrappedCentered(canvas, trans, tp, l.textWidth - 30f, l.textCenterX, bottom + 34f * config.textScale, 2, 16f)
        }
    }

    private fun drawArt(canvas: Canvas, rect: RectF, radius: Float) {
        val art = config.albumArtBitmap
        if (art != null && !art.isRecycled) {
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
            canvas.drawBitmap(art, null, rect, smooth)
            canvas.restore()
            canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(50, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f
            })
        } else {
            canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 30, 35, 55) })
        }
    }

    // ---------- Comunes ----------

    private fun drawWaveform(canvas: Canvas, frame: Float) {
        val bottom = height - if (config.isVertical) 85f else 50f
        val count = if (config.isLandscape) 48 else 32
        val barW = 9f
        val spacing = (width - count * barW) / (count + 1)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until count) {
            val x = spacing + i * (barW + spacing)
            val phase = frame * 0.16f + i * 0.38f
            val h = 10f + (24f * (sin(phase.toDouble()) * cos(phase * 0.7) + 1.0)).toFloat()
            p.color = lerpColor(config.primaryColor, accentColor, i.toFloat() / count)
            canvas.drawRoundRect(RectF(x, bottom - h, x + barW, bottom), 4.5f, 4.5f, p)
        }
    }

    private fun drawProgressBar(canvas: Canvas, progress: Float) {
        val y = height - if (config.isVertical) 35f else 16f
        val start = 60f
        val end = width - 60f
        canvas.drawLine(start, y, end, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(45, 255, 255, 255); strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
        })
        val fill = start + (end - start) * progress
        if (fill > start) canvas.drawLine(start, y, fill, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dominantColor; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
        })
    }

    private fun drawDedication(canvas: Canvas, y: Float, text: String) {
        val p = textPaint(23f, Color.WHITE, bold = true, align = Paint.Align.CENTER)
        val full = if (text.startsWith("💌") || text.startsWith("✨") || text.startsWith("💖")) text else "💌 $text"
        val pillW = (p.measureText(full) + 48f).coerceAtMost(width - 80f)
        val rect = RectF((width - pillW) / 2f, y - 22f, (width + pillW) / 2f, y + 22f)
        canvas.drawRoundRect(rect, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 18, 20, 32) })
        canvas.drawRoundRect(rect, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f
        })
        canvas.drawText(truncate(full, p, pillW - 24f), width / 2f, y + 8f, p)
    }

    // ---------- Pixel art ----------

    private fun pixelArtSize(): Float {
        val sw = width / PX
        val sh = height / PX
        return when {
            config.isLandscape -> sh * 0.62f
            config.isVertical -> sw * 0.72f
            else -> minOf(sw * 0.46f, sh * 0.42f)
        }
    }

    /**
     * Pasa la carátula a los colores de la paleta: cada píxel toma el color de la paleta (ordenada de oscuro a
     * claro) que corresponde a su luminosidad, con tramado ordenado Bayer 4×4 y contraste automático (percentiles
     * 2–98 de la imagen). Antes se buscaba el color más parecido y, con paletas casi monocromas como Virtual Boy
     * (negro y rojos), casi toda la carátula caía en negro y no se veía. Devuelve un bitmap nuevo y recicla el de entrada.
     */
    private fun quantizeToPalette(src: Bitmap, palette: IntArray): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        fun luma(c: Int) = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        val ramp = palette.distinct().sortedBy { luma(it) }
        val n = ramp.size
        val lumas = FloatArray(px.size) { luma(px[it]) }
        val sorted = lumas.sortedArray()
        val lo = sorted[(sorted.size * 0.02f).toInt()]
        val hi = sorted[((sorted.size - 1) * 0.98f).toInt()].coerceAtLeast(lo + 1f)
        val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val level = ((lumas[i] - lo) / (hi - lo)).coerceIn(0f, 1f) * (n - 1)
            val dither = (bayer[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f - 0.5f
            px[i] = ramp[(level + dither).roundToInt().coerceIn(0, n - 1)]
        }
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        src.recycle()
        return out
    }

    /**
     * Escena a 1/[PX] de resolución, sin antialias en el texto: al ampliarla sin filtro cada píxel queda como un
     * bloque nítido. Todo usa los colores de la paleta elegida.
     */
    private fun drawPixelScene(c: Canvas, sw: Float, sh: Float, timeMs: Long, progress: Float, frame: Float) {
        val (bg, surface, primary, secondary, ink) = config.pixelColors.let { listOf(it[0], it[1], it[2], it[3], it[4]) }
        c.drawColor(bg)
        val px = Paint().apply { isAntiAlias = false }

        // Estrellas que titilan (posiciones fijas pseudoaleatorias)
        for (i in 0 until 40) {
            val x = ((i * 73 + 17) % sw.toInt()).toFloat()
            val y = ((i * 131 + 29) % sh.toInt()).toFloat()
            if ((i + (frame / 8).toInt()) % 5 == 0) continue
            px.color = if (i % 3 == 0) secondary else surface
            c.drawRect(x, y, x + 1, y + 1, px)
        }
        // Suelo a rayas
        px.color = surface
        var stripeY = sh * 0.86f
        var gap = 2f
        while (stripeY < sh) { c.drawRect(0f, stripeY, sw, stripeY + 1, px); stripeY += gap; gap += 1f }

        val artSize = pixelArtSize()
        val artRect = if (config.isLandscape) {
            RectF(sw * 0.07f, (sh - artSize) / 2f - 6, sw * 0.07f + artSize, (sh - artSize) / 2f - 6 + artSize)
        } else {
            val top = if (config.isVertical) sh * 0.11f else (sh - artSize) * 0.25f
            RectF((sw - artSize) / 2f, top, (sw + artSize) / 2f, top + artSize)
        }
        // Sombra escalonada y marco de 2 px
        px.color = Color.argb(160, 0, 0, 0)
        c.drawRect(artRect.left + 3, artRect.top + 3, artRect.right + 3, artRect.bottom + 3, px)
        px.color = primary
        c.drawRect(artRect.left - 2, artRect.top - 2, artRect.right + 2, artRect.bottom + 2, px)
        // Rebote suave de la carátula al ritmo (1 px)
        val bob = if ((frame / 10).toInt() % 2 == 0) 0f else -1f
        lastPixelArtRect = RectF(artRect.left, artRect.top + bob, artRect.right, artRect.bottom + bob)
        if (pixelArt != null) c.drawBitmap(pixelArt, null, lastPixelArtRect, nearest)
        else { px.color = surface; c.drawRect(artRect, px) }

        val centerX: Float
        val textW: Float
        var y: Float
        if (config.isLandscape) {
            centerX = (artRect.right + 10 + sw - 6) / 2f
            textW = sw - 6 - (artRect.right + 10)
            y = artRect.top + 12
        } else {
            centerX = sw / 2f
            textW = sw - 16
            y = artRect.bottom + 18
        }
        fun pixelText(size: Float, color: Int, bold: Boolean) = Paint().apply {
            isAntiAlias = false; textSize = size; this.color = color
            typeface = if (bold) boldTf else regularTf; textAlign = Paint.Align.CENTER
        }
        val big = config.isVertical
        val title = pixelText(if (big) 15f else 12f, ink, true)
        c.drawText(truncate(cased(config.songTitle), title, textW), centerX, y, title)
        y += if (big) 13 else 11
        val artist = pixelText(if (big) 11f else 9f, secondary, false)
        c.drawText(truncate(config.artistName, artist, textW), centerX, y, artist)
        y += if (big) 26 else 18

        // Letra: en vertical, línea anterior y siguiente atenuadas alrededor de la actual (karaoke)
        val lines = config.lyricsLines
        val idx = lines.indexOfLast { it.timeMs <= timeMs + 50 }
        if (idx >= 0) {
            val size = (if (big) 15f else 12f) * config.textScale
            val lyric = pixelText(size, ink, true)
            val shadow = pixelText(size, primary, true)
            val dim = pixelText(size * 0.8f, surface, false).apply { color = Color.argb(150, Color.red(ink), Color.green(ink), Color.blue(ink)) }
            if (big && idx > 0) {
                wrapText(cased(lines[idx - 1].text), dim, textW).take(1).forEach { t -> c.drawText(t, centerX, y, dim) }
                y += dim.textSize + 8
            }
            wrapText(cased(lines[idx].text), lyric, textW).take(4).forEach { t ->
                c.drawText(t, centerX + 1, y + 1, shadow)
                c.drawText(t, centerX, y, lyric)
                y += lyric.textSize + 3
            }
            translationOf(lines[idx])?.let { trans ->
                y += 2
                val tp = pixelText((if (big) 11f else 9f) * config.textScale, secondary, false)
                wrapText(trans, tp, textW).take(3).forEach { t -> c.drawText(t, centerX, y, tp); y += tp.textSize + 2 }
            }
            if (big && idx + 1 < lines.size) {
                y += 8
                wrapText(cased(lines[idx + 1].text), dim, textW).take(1).forEach { t -> c.drawText(t, centerX, y, dim) }
            }
        }

        // Ecualizador de bloques
        if (config.showWaveform) {
            val bars = if (config.isLandscape) 24 else 14
            val block = 3f
            val barW = 4f
            val total = bars * barW + (bars - 1) * 2
            val left = (sw - total) / 2f
            val base = sh - 14
            for (i in 0 until bars) {
                val maxLevel = if (config.isVertical) 11 else 7
                val level = (((sin(frame * 0.25 + i * 0.9) + 1) * (maxLevel / 2.8)) + ((i * 7 + (frame / 3).toInt()) % 3)).toInt().coerceIn(1, maxLevel)
                for (b in 0 until level) {
                    px.color = if (b >= level - 1) primary else secondary
                    val x = left + i * (barW + 2)
                    val by = base - b * (block + 1)
                    c.drawRect(x, by - block, x + barW, by, px)
                }
            }
        }
        // Barra de progreso por segmentos
        val segments = 20
        val segW = (sw - 20 - (segments - 1)) / segments
        val filled = (progress * segments).toInt()
        for (i in 0 until segments) {
            px.color = if (i < filled) primary else surface
            val x = 10 + i * (segW + 1)
            c.drawRect(x, sh - 7, x + segW, sh - 4, px)
        }
        config.dedicationText?.takeIf { it.isNotBlank() }?.let { text ->
            val p = pixelText(9f, ink, true)
            val w = (p.measureText(text) + 10).coerceAtMost(sw - 8)
            px.color = primary
            c.drawRect((sw - w) / 2f, 6f, (sw + w) / 2f, 20f, px)
            c.drawText(truncate(text, p, w - 6), sw / 2f, 16f, p.apply { color = bg })
        }
    }

    // ---------- Utilidades ----------

    private fun lerpColor(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        var current = ""
        for (raw in text.trim().split(Regex("\\s+"))) {
            var word = raw
            while (paint.measureText(word) > maxWidth && word.length > 1) {
                var cut = word.length - 1
                while (cut > 1 && paint.measureText(word, 0, cut) > maxWidth) cut--
                if (current.isNotEmpty()) { result.add(current); current = "" }
                result.add(word.substring(0, cut))
                word = word.substring(cut)
            }
            val test = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && paint.measureText(test) > maxWidth) { result.add(current); current = word } else current = test
        }
        if (current.isNotEmpty()) result.add(current)
        return result
    }

    private fun drawWrappedCentered(
        canvas: Canvas, text: String, paint: Paint, maxWidth: Float, centerX: Float, firstBaseline: Float,
        maxLines: Int, minSize: Float
    ): Float {
        var lines = wrapText(text, paint, maxWidth)
        while (lines.size > maxLines && paint.textSize > minSize) {
            paint.textSize = (paint.textSize - 1f).coerceAtLeast(minSize)
            lines = wrapText(text, paint, maxWidth)
        }
        if (lines.size > maxLines) lines = lines.take(maxLines - 1) + truncate(lines.drop(maxLines - 1).joinToString(" "), paint, maxWidth)
        var y = firstBaseline
        lines.forEachIndexed { i, line -> if (i > 0) y += paint.textSize * 1.25f; canvas.drawText(line, centerX, y, paint) }
        return y
    }

    private fun truncate(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && paint.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    private fun extractArtworkColors(bitmap: Bitmap?, defPrimary: Int, defAccent: Int): Pair<Int, Int> {
        if (bitmap == null || bitmap.isRecycled) return defPrimary to defAccent
        return runCatching {
            val small = Bitmap.createScaledBitmap(bitmap, 48, 48, false)
            val palette = Palette.from(small).generate()
            if (small !== bitmap) small.recycle()
            palette.getVibrantColor(palette.getDominantColor(defPrimary)) to palette.getLightVibrantColor(palette.getMutedColor(defAccent))
        }.getOrDefault(defPrimary to defAccent)
    }

    companion object {
        /** Tamaño de cada "píxel" de la plantilla Pixel art, en unidades del lienzo lógico. */
        const val PX = 3f
        private const val LINE_TOLERANCE_MS = 300L
    }
}
