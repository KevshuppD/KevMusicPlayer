package com.kevshupp.kevmusicplayer.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Iconos en pixel art para los temas Pixel Retro (Ajustes › Apariencia, "Iconos pixel art"). Cada uno se dibuja
 * desde una cuadrícula ('#' = píxel) como cuadrados exactos, así que se ven nítidos a cualquier tamaño y toman el
 * `tint` del `Icon` como los de Material. [px] devuelve el pixelado si existe y el tema lo pide.
 */
object PixelIcons {
    private fun grid(name: String, vararg rows: String): ImageVector {
        val w = rows.maxOf { it.length }; val h = rows.size
        // Lienzo cuadrado con 1 píxel de margen, centrado
        val side = maxOf(w, h) + 2
        val ox = (side - w) / 2f; val oy = (side - h) / 2f
        return ImageVector.Builder("Pixel.$name", 24.dp, 24.dp, side.toFloat(), side.toFloat()).apply {
            path(fill = SolidColor(Color.Black)) {
                rows.forEachIndexed { y, row ->
                    row.forEachIndexed { x, c ->
                        if (c == '#') { moveTo(ox + x, oy + y); horizontalLineToRelative(1f); verticalLineToRelative(1f); horizontalLineToRelative(-1f); close() }
                    }
                }
            }
        }.build()
    }

    val Heart = grid("Heart",
        ".##.##.",
        "#######",
        "#######",
        "#######",
        ".#####.",
        "..###..",
        "...#...")
    val HeartOutline = grid("HeartOutline",
        ".##.##.",
        "#..#..#",
        "#.....#",
        "#.....#",
        ".#...#.",
        "..#.#..",
        "...#...")
    val Shuffle = grid("Shuffle",
        "##....#..",
        "..#..####",
        "...#..#..",
        "....#....",
        "...#..#..",
        "..#..####",
        "##....#..")
    val Repeat = grid("Repeat",
        "......#..",
        ".#######.",
        "#.....#..",
        "#.......#",
        "..#.....#",
        ".#######.",
        "..#......")
    val RepeatOne = grid("RepeatOne",
        "......#..",
        ".#######.",
        "#.....#..",
        "#...#...#",
        "..#.#...#",
        ".#######.",
        "..#......")
    val Play = grid("Play",
        "#....",
        "##...",
        "###..",
        "####.",
        "#####",
        "####.",
        "###..",
        "##...",
        "#....")
    val Pause = grid("Pause",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##")
    val Next = grid("Next",
        "#...##",
        "##..##",
        "###.##",
        "######",
        "###.##",
        "##..##",
        "#...##")
    val Previous = grid("Previous",
        "##...#",
        "##..##",
        "##.###",
        "######",
        "##.###",
        "##..##",
        "##...#")
    val History = grid("History",
        "..###..",
        ".#...#.",
        "#..#..#",
        "#..##.#",
        "#.....#",
        ".#...#.",
        "..###..")
    val Star = grid("Star",
        "...#...",
        "..###..",
        "#######",
        ".#####.",
        "..###..",
        ".##.##.",
        "#.....#")
    val Home = grid("Home",
        "...#...",
        "..###..",
        ".#####.",
        "#######",
        ".##.##.",
        ".##.##.",
        ".#####.")
    val Note = grid("Note",
        "..#####",
        "..#...#",
        "..#...#",
        "..#...#",
        "###.###",
        "###.###")
    val Library = grid("Library",
        "#.#.#####",
        "#.#.#...#",
        "#.#.#...#",
        "#.#.#...#",
        "#.#.#.###",
        "#.#.#.###")
    val Sparkle = grid("Sparkle",
        "...#...",
        "...#...",
        "..###..",
        "###.###",
        "..###..",
        "...#...",
        "...#...")

    private val byName: Map<String, ImageVector> by lazy {
        mapOf(
            Icons.Rounded.Favorite to Heart,
            Icons.Rounded.FavoriteBorder to HeartOutline,
            Icons.Rounded.Shuffle to Shuffle,
            Icons.Rounded.Repeat to Repeat,
            Icons.Rounded.RepeatOne to RepeatOne,
            Icons.Rounded.PlayArrow to Play,
            Icons.Rounded.Pause to Pause,
            Icons.Rounded.SkipNext to Next,
            Icons.Rounded.SkipPrevious to Previous,
            Icons.Rounded.History to History,
            Icons.Rounded.NewReleases to Star,
            Icons.Rounded.Home to Home,
            Icons.Rounded.MusicNote to Note,
            Icons.Rounded.LibraryMusic to Library,
            Icons.Rounded.AutoAwesome to Sparkle
        ).mapKeys { it.key.name }
    }

    fun of(icon: ImageVector): ImageVector? = byName[icon.name]
}

/** El icono en pixel art si el tema Pixel Retro lo pide ([LocalPixelIcons]) y existe; si no, el mismo. */
@Composable
fun px(icon: ImageVector): ImageVector =
    if (LocalPixelIcons.current) PixelIcons.of(icon) ?: icon else icon
