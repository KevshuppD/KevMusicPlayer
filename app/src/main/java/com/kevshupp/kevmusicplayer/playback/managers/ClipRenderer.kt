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

/** Fondo animado de la plantilla Pixel art; todos usan los colores de la paleta elegida. */
enum class PixelScene { STARS, FOREST, ROAD, CYBER_CITY, DISCO, CITY, JAPAN }

/** Composición del Pixel art: carátula con letra (escena), letra protagonista estilo karaoke o vinilo girando. */
enum class PixelLayout { SCENE, LYRICS, VINYL }

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
    val pixelScene: PixelScene = PixelScene.STARS,
    val pixelLayout: PixelLayout = PixelLayout.SCENE,
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
            if (config.pixelArtMode == PixelArtMode.ORIGINAL && original != null && !original.isRecycled && !lastPixelArtRect.isEmpty) {
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

        drawPixelBackdrop(c, sw, sh, frame, bg, surface, primary, secondary, ink, px)

        fun pixelText(size: Float, color: Int, bold: Boolean) = Paint().apply {
            isAntiAlias = false; textSize = size; this.color = color
            typeface = if (bold) boldTf else regularTf; textAlign = Paint.Align.CENTER
        }
        when (config.pixelLayout) {
            PixelLayout.SCENE -> drawPixelSceneLayout(c, sw, sh, timeMs, frame, ::pixelText, px)
            PixelLayout.LYRICS -> drawPixelLyricsLayout(c, sw, sh, timeMs, frame, ::pixelText, px)
            PixelLayout.VINYL -> drawPixelVinylLayout(c, sw, sh, timeMs, frame, ::pixelText, px)
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

    /** Escena clásica: carátula grande con marco, título, artista y letra (en vertical, con la anterior y la siguiente). */
    private fun drawPixelSceneLayout(
        c: Canvas, sw: Float, sh: Float, timeMs: Long, frame: Float, pixelText: (Float, Int, Boolean) -> Paint, px: Paint
    ) {
        val (bg, surface, primary, secondary, ink) = config.pixelColors.let { listOf(it[0], it[1], it[2], it[3], it[4]) }
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

    }

    /**
     * Letra protagonista: carátula pequeña con título y artista arriba, y la letra en grande con varias líneas
     * alrededor de la actual, que va resaltada sobre un bloque del color principal (karaoke).
     */
    private fun drawPixelLyricsLayout(
        c: Canvas, sw: Float, sh: Float, timeMs: Long, frame: Float, pixelText: (Float, Int, Boolean) -> Paint, px: Paint
    ) {
        val (bg, surface, primary, secondary, ink) = config.pixelColors.let { listOf(it[0], it[1], it[2], it[3], it[4]) }
        val big = config.isVertical
        // Cabecera: carátula pequeña a la izquierda, título y artista a su derecha
        val art = if (big) 34f else 26f
        val top = if (config.dedicationText.isNullOrBlank()) 10f else 26f
        val artRect = RectF(10f, top, 10f + art, top + art)
        px.color = primary
        c.drawRect(artRect.left - 1, artRect.top - 1, artRect.right + 1, artRect.bottom + 1, px)
        lastPixelArtRect = RectF(artRect)
        if (pixelArt != null) c.drawBitmap(pixelArt, null, artRect, nearest)
        else { px.color = surface; c.drawRect(artRect, px) }
        val textLeft = artRect.right + 6
        val textW = sw - textLeft - 8
        val title = pixelText(if (big) 12f else 10f, ink, true).apply { textAlign = Paint.Align.LEFT }
        val artist = pixelText(if (big) 9f else 8f, secondary, false).apply { textAlign = Paint.Align.LEFT }
        c.drawText(truncate(cased(config.songTitle), title, textW), textLeft, artRect.top + art * 0.45f, title)
        c.drawText(truncate(config.artistName, artist, textW), textLeft, artRect.top + art * 0.45f + artist.textSize + 3, artist)

        val lines = config.lyricsLines
        val idx = lines.indexOfLast { it.timeMs <= timeMs + 50 }
        val areaTop = artRect.bottom + 12
        // Por encima del ecualizador de bloques (hasta 11 bloques de 4 px en vertical) y de la barra de progreso
        val areaBottom = sh - if (config.showWaveform) (if (big) 62f else 46f) else 14f
        val textW2 = sw - 20
        val size = (if (big) 17f else 13f) * config.textScale
        val active = pixelText(size, bg, true)
        val other = pixelText(size * 0.82f, ink, false).apply { alpha = 150 }
        val lineGap = 4f
        if (idx < 0) {
            // Antes de la primera línea: tres puntos que parpadean
            val dots = ".".repeat(1 + (frame / 8).toInt() % 3)
            c.drawText(dots, sw / 2f, (areaTop + areaBottom) / 2f, pixelText(size, primary, true))
            return
        }
        val activeWrapped = wrapText(cased(lines[idx].text), active, textW2 - 8).take(4)
        val activeH = activeWrapped.size * (active.textSize + lineGap) + 6
        val centerY = (areaTop + areaBottom) / 2f
        // Línea actual sobre un bloque del color principal, con sombra escalonada
        val boxTop = centerY - activeH / 2f
        px.color = Color.argb(160, 0, 0, 0)
        c.drawRect(8f + 2, boxTop + 2, sw - 8 + 2, boxTop + activeH + 2, px)
        px.color = primary
        c.drawRect(8f, boxTop, sw - 8, boxTop + activeH, px)
        var y = boxTop + active.textSize + 2
        activeWrapped.forEach { t -> c.drawText(t, sw / 2f, y, active); y += active.textSize + lineGap }
        // Donde termina el bloque actual (con su traducción): de ahí hacia abajo van las siguientes
        var blockBottom = boxTop + activeH
        translationOf(lines[idx])?.let { trans ->
            val tp = pixelText((if (big) 10f else 8f) * config.textScale, secondary, false)
            var ty = boxTop + activeH + tp.textSize + 4
            wrapText(trans, tp, textW2).take(2).forEach { t -> c.drawText(t, sw / 2f, ty, tp); blockBottom = ty; ty += tp.textSize + 2 }
        }
        // Anteriores hacia arriba y siguientes hacia abajo mientras quepan
        var upY = boxTop - 8
        var i = idx - 1
        while (i >= 0 && upY > areaTop + other.textSize) {
            val wrapped = wrapText(cased(lines[i].text), other, textW2).take(2)
            for (t in wrapped.reversed()) {
                if (upY <= areaTop + other.textSize) break
                c.drawText(t, sw / 2f, upY, other); upY -= other.textSize + lineGap
            }
            upY -= 4; i--
        }
        var downY = blockBottom + other.textSize + 8
        i = idx + 1
        while (i < lines.size && downY < areaBottom) {
            for (t in wrapText(cased(lines[i].text), other, textW2).take(2)) {
                if (downY >= areaBottom) break
                c.drawText(t, sw / 2f, downY, other); downY += other.textSize + lineGap
            }
            downY += 4; i++
        }
    }

    /**
     * Vinilo pixel: disco negro con surcos que giran (un reflejo que da vueltas) y la carátula como etiqueta,
     * dibujado sin antialias para que al ampliar los bordes queden en escalones. Debajo, título y letra.
     */
    private fun drawPixelVinylLayout(
        c: Canvas, sw: Float, sh: Float, timeMs: Long, frame: Float, pixelText: (Float, Int, Boolean) -> Paint, px: Paint
    ) {
        val (bg, surface, primary, secondary, ink) = config.pixelColors.let { listOf(it[0], it[1], it[2], it[3], it[4]) }
        val big = config.isVertical
        val r = when {
            config.isLandscape -> sh * 0.36f
            big -> sw * 0.38f
            else -> minOf(sw * 0.26f, sh * 0.25f)
        }
        val cx = if (config.isLandscape) sw * 0.07f + r else sw / 2f
        val cy = if (config.isLandscape) sh / 2f - 4 else if (big) sh * 0.11f + r else (sh - 2 * r) * 0.22f + r
        // Sombra y disco
        px.color = Color.argb(150, 0, 0, 0)
        c.drawCircle(cx + 3, cy + 3, r, px)
        px.color = 0xFF0B0B0F.toInt()
        c.drawCircle(cx, cy, r, px)
        // Surcos: anillos de 1 px en el tono de superficie
        px.style = Paint.Style.STROKE; px.strokeWidth = 1f
        px.color = surface
        var ring = r - 3
        while (ring > r * 0.42f) { c.drawCircle(cx, cy, ring, px); ring -= 3 }
        // Reflejo que gira: dos arcos opuestos del color principal
        val angle = (frame * 4f) % 360f
        px.color = primary; px.strokeWidth = 2f
        val oval = RectF(cx - r + 4, cy - r + 4, cx + r - 4, cy + r - 4)
        c.drawArc(oval, angle, 26f, false, px)
        c.drawArc(oval, angle + 180f, 26f, false, px)
        px.color = secondary; px.strokeWidth = 1f
        val oval2 = RectF(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f)
        c.drawArc(oval2, angle + 90f, 18f, false, px)
        px.style = Paint.Style.FILL
        // Etiqueta: la carátula recortada en círculo, girando con el disco
        val label = r * 0.4f
        val save = c.save()
        val clip = android.graphics.Path().apply { addCircle(cx, cy, label, android.graphics.Path.Direction.CW) }
        c.clipPath(clip)
        c.rotate(angle, cx, cy)
        if (pixelArt != null) c.drawBitmap(pixelArt, null, RectF(cx - label, cy - label, cx + label, cy + label), nearest)
        else { px.color = primary; c.drawRect(cx - label, cy - label, cx + label, cy + label, px) }
        c.restoreToCount(save)
        px.color = bg
        c.drawCircle(cx, cy, 2f, px)
        // "Sin alterar" no aplica: la carátula forma parte del disco que gira
        lastPixelArtRect = RectF()

        val centerX: Float
        val textW: Float
        var y: Float
        if (config.isLandscape) {
            centerX = (cx + r + 10 + sw - 6) / 2f
            textW = sw - 6 - (cx + r + 10)
            y = cy - r * 0.5f
        } else {
            centerX = sw / 2f
            textW = sw - 16
            y = cy + r + 18
        }
        val title = pixelText(if (big) 15f else 12f, ink, true)
        c.drawText(truncate(cased(config.songTitle), title, textW), centerX, y, title)
        y += if (big) 13 else 11
        val artist = pixelText(if (big) 11f else 9f, secondary, false)
        c.drawText(truncate(config.artistName, artist, textW), centerX, y, artist)
        y += if (big) 24 else 16
        val lines = config.lyricsLines
        val idx = lines.indexOfLast { it.timeMs <= timeMs + 50 }
        if (idx >= 0) {
            val size = (if (big) 14f else 11f) * config.textScale
            val lyric = pixelText(size, ink, true)
            val shadow = pixelText(size, primary, true)
            wrapText(cased(lines[idx].text), lyric, textW).take(3).forEach { t ->
                c.drawText(t, centerX + 1, y + 1, shadow)
                c.drawText(t, centerX, y, lyric)
                y += lyric.textSize + 3
            }
            translationOf(lines[idx])?.let { trans ->
                val tp = pixelText((if (big) 10f else 8f) * config.textScale, secondary, false)
                wrapText(trans, tp, textW).take(2).forEach { t -> c.drawText(t, centerX, y, tp); y += tp.textSize + 2 }
            }
        }
    }

    // ---------- Escenas de fondo del Pixel art ----------

    /** Número pseudoaleatorio estable (mismo resultado en cada cuadro) para posiciones y alturas. */
    private fun rnd(i: Int, m: Int): Int = (((i * 73856093) xor (i * 19349663 ushr 3) xor 0x5bd1e995) and 0x7fffffff) % m.coerceAtLeast(1)

    private fun drawPixelBackdrop(
        c: Canvas, sw: Float, sh: Float, t: Float, bg: Int, surface: Int, primary: Int, secondary: Int, ink: Int, px: Paint
    ) {
        fun box(x: Float, y: Float, w: Float, h: Float, color: Int) { px.color = color; c.drawRect(x, y, x + w, y + h, px) }
        fun dim(color: Int, amount: Float) = lerpColor(bg, color, amount)
        fun disc(cx: Float, cy: Float, r: Float, color: Int, topHalfOnly: Boolean = false) {
            val ri = r.toInt()
            for (dy in -ri..(if (topHalfOnly) 0 else ri)) {
                val half = kotlin.math.sqrt((r * r - dy * dy).coerceAtLeast(0f)).toInt().toFloat()
                box(cx - half, cy + dy, half * 2 + 1, 1f, color)
            }
        }
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
            px.color = color; px.strokeWidth = 1f
            c.drawLine(x0.toInt().toFloat(), y0.toInt().toFloat(), x1.toInt().toFloat(), y1.toInt().toFloat(), px)
        }
        fun stars(count: Int, maxY: Float, color: Int) {
            for (i in 0 until count) {
                if ((i + (t / 8).toInt()) % 5 == 0) continue
                box(rnd(i * 3 + 1, sw.toInt()).toFloat(), rnd(i * 7 + 2, maxY.toInt()).toFloat(), 1f, 1f, color)
            }
        }
        val horizon = (sh * if (config.isVertical) 0.80f else 0.74f).toInt().toFloat()

        when (config.pixelScene) {
            PixelScene.STARS -> {
                // Estrellas que titilan y suelo a rayas
                for (i in 0 until 40) {
                    val x = ((i * 73 + 17) % sw.toInt()).toFloat()
                    val y = ((i * 131 + 29) % sh.toInt()).toFloat()
                    if ((i + (t / 8).toInt()) % 5 == 0) continue
                    box(x, y, 1f, 1f, if (i % 3 == 0) secondary else surface)
                }
                var stripeY = sh * 0.86f
                var gap = 2f
                while (stripeY < sh) { box(0f, stripeY, sw, 1f, surface); stripeY += gap; gap += 1f }
            }

            PixelScene.FOREST -> {
                stars(18, horizon * 0.6f, dim(ink, 0.5f))
                // Luna creciente
                disc(sw * 0.82f, sh * 0.07f, 9f, dim(ink, 0.85f))
                disc(sw * 0.82f + 4, sh * 0.07f - 2, 8f, bg)
                // Pinos en dos capas que se desplazan a distinta velocidad
                fun pines(baseY: Float, height: Float, spacing: Float, scroll: Float, color: Int, seed: Int) {
                    val first = kotlin.math.floor(scroll / spacing).toInt()
                    for (j in -1..(sw / spacing).toInt() + 1) {
                        val idx = first + j
                        val x = idx * spacing - scroll
                        val h = height * (0.65f + rnd(idx * 13 + seed, 35) / 100f)
                        box(x - 1, baseY - 4, 3f, 4f, color)
                        var row = 0
                        while (row < h) {
                            // Copa escalonada: se ensancha y cada tercio vuelve a estrecharse un poco
                            val w = 1 + (row * spacing * 0.42f / h).toInt() - (if ((row / 4) % 2 == 1) 1 else 0)
                            box(x - w, baseY - 4 - h + row, w * 2 + 1f, 1f, color)
                            row++
                        }
                    }
                }
                pines(horizon, 42f, 13f, t * 0.15f, dim(surface, 0.55f), 1)
                box(0f, horizon, sw, sh - horizon, dim(surface, 0.4f))
                pines(sh + 2, 72f, 21f, t * 0.45f, surface, 2)
                // Luciérnagas
                for (i in 0 until 16) {
                    if (((t / 6).toInt() + i) % 4 == 0) continue
                    val x = (rnd(i * 5 + 3, sw.toInt()) + sin(t * 0.05 + i) * 6).toFloat()
                    val y = (horizon - 8 - rnd(i * 11 + 4, 70) + kotlin.math.cos(t * 0.07 + i) * 4).toFloat()
                    box(x, y, 1f, 1f, secondary)
                }
            }

            PixelScene.ROAD -> {
                stars(24, horizon * 0.7f, dim(ink, 0.45f))
                // Sol synthwave con franjas que se abren hacia abajo
                val sunR = minOf(sw * 0.22f, horizon * 0.38f)
                val sunY = horizon - 1
                for (dy in -sunR.toInt()..0) {
                    val fromTop = (dy + sunR) / sunR
                    val gapRow = -dy < sunR * 0.55f && (-dy % 7) < (1 + (sunR * 0.55f + dy) / 7).toInt().coerceAtMost(4)
                    if (gapRow) continue
                    val half = kotlin.math.sqrt((sunR * sunR - dy * dy).coerceAtLeast(0f)).toInt().toFloat()
                    box(sw / 2 - half, sunY + dy, half * 2 + 1, 1f, lerpColor(secondary, primary, fromTop))
                }
                // Montañas
                for (x in 0 until sw.toInt()) {
                    val a = 20 - kotlin.math.abs(((x + 7) % 46) - 23)
                    val b = 12 - kotlin.math.abs(((x + 30) % 28) - 14)
                    val h = (maxOf(a * 0.9f, b * 0.8f) + rnd(x / 3, 3)).coerceAtLeast(1f)
                    box(x.toFloat(), horizon - h, 1f, h, dim(surface, 0.9f))
                }
                box(0f, horizon, sw, 1f, primary)
                // Cuadrícula que avanza
                val ground = sh - horizon
                val phase = (t * 0.04f) % 1f
                for (k in 1..12) {
                    val p = (k - phase) / 12f
                    box(0f, horizon + ground * p * p, sw, 1f, dim(primary, 0.55f))
                }
                for (i in -9..9) line(sw / 2 + i * 5, horizon, sw / 2 + i * sw / 5, sh, dim(primary, 0.55f))
                // Carretera: bordes y líneas centrales que vienen hacia ti
                line(sw / 2 - 4, horizon, sw / 2 - sw * 0.34f, sh, ink)
                line(sw / 2 + 4, horizon, sw / 2 + sw * 0.34f, sh, ink)
                val dashPhase = (t * 0.09f) % 2f
                for (k in 0..9) {
                    if ((k + dashPhase.toInt()) % 2 == 1) continue
                    val p0 = ((k + dashPhase % 1f) / 10f).coerceIn(0f, 1f)
                    val p1 = ((k + 0.6f + dashPhase % 1f) / 10f).coerceIn(0f, 1f)
                    val y0 = horizon + ground * p0 * p0
                    val y1 = horizon + ground * p1 * p1
                    val w = 1 + p1 * 3
                    box(sw / 2 - w / 2, y0, w, (y1 - y0).coerceAtLeast(1f), secondary)
                }
            }

            PixelScene.CYBER_CITY -> {
                // Cielo en bandas hacia el color principal
                for (b in 0 until 8) {
                    val y = sh * 0.25f + b * sh * 0.07f
                    box(0f, y, sw, sh * 0.07f + 1, lerpColor(bg, primary, b * 0.035f))
                }
                fun towers(seed: Int, minH: Float, maxH: Float, body: Int, window: Int, neon: Boolean, scroll: Float) {
                    var x = -scroll % 60f - 60f
                    var idx = seed * 100 + kotlin.math.floor(scroll / 60f).toInt() * 7
                    while (x < sw) {
                        val w = 12f + rnd(idx * 3, 16)
                        val h = minH + rnd(idx * 5, (maxH - minH).toInt())
                        val top = sh - h
                        box(x, top, w, h, body)
                        // Ventanas: algunas encendidas, alguna parpadea
                        var wy = top + 3
                        while (wy < sh - 2) {
                            var wx = x + 2
                            while (wx < x + w - 2) {
                                val k = idx * 131 + (wy.toInt() * 7) + wx.toInt()
                                val on = rnd(k, 6) == 0 || (rnd(k, 40) == 1 && (t / 12).toInt() % 2 == 0)
                                if (on) box(wx, wy, 1f, 1f, window)
                                wx += 3
                            }
                            wy += 4
                        }
                        if (neon) {
                            box(x, top, w, 1f, if (idx % 2 == 0) primary else secondary)
                            if (rnd(idx, 3) == 0) {
                                box(x + w / 2, top - 6, 1f, 6f, body)
                                if ((t / 10).toInt() % 2 == 0) box(x + w / 2, top - 7, 1f, 1f, primary)
                            }
                        }
                        x += w + 2 + rnd(idx * 11, 5)
                        idx++
                    }
                }
                towers(1, sh * 0.30f, sh * 0.55f, dim(surface, 0.5f), dim(secondary, 0.5f), false, t * 0.1f)
                towers(2, sh * 0.16f, sh * 0.34f, surface, secondary, true, t * 0.3f)
                // Coches voladores
                for (lane in 0 until 4) {
                    val speed = 1.2f + lane * 0.5f
                    val dir = if (lane % 2 == 0) 1 else -1
                    val raw = (t * speed + lane * 70) % (sw + 30)
                    val x = if (dir == 1) raw - 15 else sw - raw + 15
                    val y = sh * (0.38f + lane * 0.08f)
                    box(x, y, 4f, 1f, ink)
                    box(x - dir * 4f, y, 3f, 1f, dim(primary, 0.6f))
                }
                // Lluvia
                for (i in 0 until 34) {
                    val x = (rnd(i * 17 + 5, sw.toInt()) + t * 1.2f) % sw
                    val y = (rnd(i * 23 + 9, sh.toInt()) + t * 5f) % sh
                    box(x, y, 1f, 3f, dim(secondary, 0.4f))
                }
            }

            PixelScene.DISCO -> {
                // Pared de LEDs con ondas que salen del centro
                val cx = sw / 2
                val cy = sh * 0.32f
                var y = 2f
                while (y < horizon - 2) {
                    var x = 2f
                    while (x < sw) {
                        val d = kotlin.math.hypot(x - cx, (y - cy) * 1.3f)
                        val ring = ((d - t * 1.1f) % 18f + 18f) % 18f
                        val color = when {
                            ring < 2.5f -> lerpColor(primary, secondary, (d / sw).coerceIn(0f, 1f))
                            ring < 4f -> dim(primary, 0.4f)
                            else -> dim(surface, 0.35f)
                        }
                        box(x, y, 2f, 2f, color)
                        x += 4
                    }
                    y += 4
                }
                // Pirámide de luz con una línea que la recorre
                val pyramid = minOf(sw * 0.5f, horizon * 0.75f)
                val apexY = horizon - pyramid
                val leftX = cx - pyramid * 0.84f
                val rightX = cx + pyramid * 0.84f
                line(cx, apexY, leftX, horizon, primary)
                line(cx, apexY, rightX, horizon, primary)
                val scan = ((t * 0.02f) % 1f)
                val sy = horizon - (horizon - apexY) * scan
                val half = (rightX - cx) * (sy - apexY) / (horizon - apexY)
                box(cx - half, sy, half * 2, 1f, secondary)
                // Pista de baile: baldosas que cambian al ritmo
                val colors = intArrayOf(primary, secondary, ink, surface)
                var top = horizon
                var row = 0
                var rowH = 3f
                while (top < sh) {
                    val cols = 8
                    val tileW = sw / cols
                    for (col in 0 until cols) {
                        val k = (row + col * 3 + (t / 8).toInt()) % 7
                        val color = if (k < 4) dim(colors[k], 0.5f) else dim(surface, 0.35f)
                        box(col * tileW + 1, top + 1, tileW - 1, rowH - 1, color)
                    }
                    top += rowH
                    rowH += 2f
                    row++
                }
            }

            PixelScene.CITY -> {
                stars(22, horizon * 0.5f, dim(ink, 0.45f))
                disc(sw * 0.18f, sh * 0.08f, 8f, dim(ink, 0.8f))
                val street = sh - 26f
                fun skyline(seed: Int, minH: Float, maxH: Float, body: Int, window: Int, base: Float) {
                    var x = -4f
                    var idx = seed * 50
                    while (x < sw) {
                        val w = 14f + rnd(idx * 7, 14)
                        val h = minH + rnd(idx * 9, (maxH - minH).toInt())
                        box(x, base - h, w, h, body)
                        var wy = base - h + 3
                        while (wy < base - 3) {
                            var wx = x + 2
                            while (wx < x + w - 3) {
                                val k = idx * 97 + wy.toInt() * 5 + wx.toInt()
                                // Se encienden y apagan muy de vez en cuando
                                if (rnd(k + (t / 90).toInt() * (rnd(k, 30) / 29), 4) == 0) box(wx, wy, 2f, 2f, window)
                                wx += 4
                            }
                            wy += 5
                        }
                        x += w + 1
                        idx++
                    }
                }
                skyline(1, sh * 0.18f, sh * 0.36f, dim(surface, 0.5f), dim(ink, 0.35f), street)
                skyline(2, sh * 0.08f, sh * 0.22f, surface, dim(secondary, 0.85f), street)
                // Calle con farolas y coches
                box(0f, street, sw, 10f, dim(surface, 0.6f))
                val dash = (t * 0.8f) % 12f
                var dx = -dash
                while (dx < sw) { box(dx, street + 5, 5f, 1f, dim(ink, 0.6f)); dx += 12f }
                var lamp = 10f
                while (lamp < sw) {
                    box(lamp, street - 14, 1f, 14f, dim(ink, 0.4f))
                    box(lamp - 1, street - 15, 3f, 1f, primary)
                    lamp += 44f
                }
                for (car in 0 until 3) {
                    val dir = if (car == 1) -1 else 1
                    val raw = (t * (1.4f + car * 0.5f) + car * 90) % (sw + 20)
                    val x = if (dir == 1) raw - 10 else sw - raw + 10
                    val y = street + if (dir == 1) 6 else 1
                    box(x, y, 8f, 3f, if (car == 0) primary else secondary)
                    box(if (dir == 1) x + 8 else x - 1, y + 1, 1f, 1f, ink)
                    box(if (dir == 1) x - 1 else x + 8, y + 1, 1f, 1f, primary)
                }
            }

            PixelScene.JAPAN -> {
                stars(14, horizon * 0.5f, dim(ink, 0.4f))
                // Sol rojo asomando tras la cima del Fuji
                val peakY = horizon - 62
                disc(sw / 2, peakY + 2, 22f, primary)
                // Monte Fuji con nieve (en horizontal no se ensancha con todo el ancho)
                val fujiBase = minOf(sw, sh * 0.56f)
                for (yy in peakY.toInt()..horizon.toInt()) {
                    val f = (yy - peakY) / (horizon - peakY)
                    val half = 5 + f * fujiBase * 0.42f
                    val snowLine = 16 + (if (((sw / 2 - half).toInt() / 3) % 2 == 0) 2 else 0)
                    box(sw / 2 - half, yy.toFloat(), half * 2, 1f, dim(surface, 0.95f))
                    if (yy - peakY < snowLine) {
                        // Borde dentado de la nieve
                        val teeth = if (yy - peakY > snowLine - 4) 0.6f else 1f
                        box(sw / 2 - half * teeth, yy.toFloat(), half * 2 * teeth, 1f, ink)
                    }
                }
                // Torii a la izquierda
                val tx = sw * 0.1f
                val tw = sw * 0.16f
                box(tx, horizon - 34, 2f, 34f, primary)
                box(tx + tw, horizon - 34, 2f, 34f, primary)
                box(tx - 4, horizon - 38, tw + 10, 2f, primary)
                box(tx - 2, horizon - 40, tw + 6, 1f, primary)
                box(tx - 1, horizon - 30, tw + 4, 1f, primary)
                // Agua con reflejos
                box(0f, horizon, sw, sh - horizon, dim(surface, 0.35f))
                var ry = horizon + 2
                var row = 0
                while (ry < sh) {
                    val shift = sin(t * 0.08 + row).toFloat() * 3
                    val len = (26f - row * 1.5f).coerceAtLeast(4f)
                    box(sw / 2 - len / 2 + shift, ry, len, 1f, dim(primary, 0.55f))
                    box(sw * 0.2f + shift * 2, ry, 6f, 1f, dim(ink, 0.25f))
                    box(sw * 0.75f - shift * 2, ry, 8f, 1f, dim(ink, 0.25f))
                    ry += 3; row++
                }
                // Pétalos de sakura que caen
                for (i in 0 until 26) {
                    val x = ((rnd(i * 29 + 1, sw.toInt()) + t * 0.6f + sin(t * 0.08 + i).toFloat() * 5) % sw + sw) % sw
                    val y = (rnd(i * 31 + 7, sh.toInt()) + t * 0.9f) % sh
                    box(x, y, if ((t / 6 + i).toInt() % 2 == 0) 2f else 1f, 1f, secondary)
                }
            }
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
