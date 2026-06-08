package com.kevshupp.kevmusicplayer.ui.screens

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.data.AudioFile
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicInsightsScreen(
    visible: Boolean,
    onDismiss: () -> Unit,
    audioFiles: List<AudioFile>,
    getLocalized: (String, String) -> String
) {
    if (!visible) return

    val context = LocalContext.current

    // Calculations
    val playedSongs = remember(audioFiles) {
        audioFiles.filter { it.playCount > 0 }
    }

    val totalMinListened = remember(playedSongs) {
        playedSongs.sumOf { it.playCount.toLong() * it.duration } / 1000 / 60
    }

    val topSongs = remember(playedSongs) {
        playedSongs.sortedByDescending { it.playCount }.take(5)
    }

    val topArtists = remember(playedSongs) {
        playedSongs.groupBy { it.artist }
            .mapValues { entry -> entry.value.sumOf { it.playCount } }
            .toList()
            .sortedByDescending { it.second }
            .take(5)
    }

    val timeDistribution = remember(playedSongs) {
        val counts = IntArray(4) // 0: Madrugada, 1: Mañana, 2: Tarde, 3: Noche
        val cal = Calendar.getInstance()
        playedSongs.forEach { song ->
            if (song.lastPlayed > 0L) {
                cal.timeInMillis = song.lastPlayed
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                when (hour) {
                    in 0..5 -> counts[0] += song.playCount
                    in 6..11 -> counts[1] += song.playCount
                    in 12..17 -> counts[2] += song.playCount
                    else -> counts[3] += song.playCount
                }
            }
        }
        counts
    }

    val totalPlays = remember(timeDistribution) {
        timeDistribution.sum().coerceAtLeast(1)
    }

    val favoriteGenre = remember(playedSongs) {
        playedSongs.groupBy { it.genre }
            .mapValues { entry -> entry.value.sumOf { it.playCount } }
            .maxByOrNull { it.value }?.key ?: getLocalized("Ninguno", "None")
    }

    val shareStatsText = remember(totalMinListened, topArtists, topSongs, favoriteGenre) {
        val topArtistStr = topArtists.firstOrNull()?.first ?: "N/A"
        val topSongStr = topSongs.firstOrNull()?.title ?: "N/A"
        """
        🎶 ¡Mi Resumen Musical de KevMusicPlayer! 🎶
        
        ⏱️ Minutos escuchados: $totalMinListened min
        🎤 Artista Top: $topArtistStr
        🎵 Canción Top: $topSongStr
        💿 Género Favorito: $favoriteGenre
        
        ¡Descubre tu música favorita offline con KevMusicPlayer!
        """.trimIndent()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = getLocalized("Estadísticas", "Insights"),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)
                    )
                }

                IconButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareStatsText)
                        }
                        context.startActivity(Intent.createChooser(intent, getLocalized("Compartir Estadísticas", "Share Insights")))
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Banner / Card Premium Overview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(16.dp, RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        )
                        .padding(24.dp)
                ) {
                    Column {
                        Text(
                            text = getLocalized("TU RESUMEN MUSICAL", "YOUR MUSIC INSIGHTS"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = getLocalized("Sintonía Personal", "Personal Harmony"),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "$totalMinListened",
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Text(
                                    text = getLocalized("Minutos Totales", "Total Minutes"),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = favoriteGenre,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = getLocalized("Género Favorito", "Top Genre"),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                // Top Songs Section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = getLocalized("TOP 5 CANCIONES", "TOP 5 SONGS"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )

                    if (topSongs.isEmpty()) {
                        EmptyStatsCard(getLocalized("Escucha canciones para generar estadísticas.", "Listen to songs to generate insights."))
                    } else {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                topSongs.forEachIndexed { index, song ->
                                    val artBytes = rememberAlbumArt(song.uriString)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "#${index + 1}",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.width(36.dp)
                                        )

                                        Card(
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            SubcomposeAsyncImage(
                                                model = artBytes,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                                error = {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.MusicNote,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = song.title,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = song.artist,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        Text(
                                            text = getLocalized("${song.playCount} repr.", "${song.playCount} plays"),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (index < topSongs.lastIndex) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                    }
                                }
                            }
                        }
                    }
                }

                // Top Artists Section
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = getLocalized("TOP ARTISTAS", "TOP ARTISTS"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )

                    if (topArtists.isEmpty()) {
                        EmptyStatsCard(getLocalized("No hay suficientes datos de artistas.", "Not enough artist data."))
                    } else {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                topArtists.forEachIndexed { index, (artist, plays) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "#${index + 1}",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.width(36.dp)
                                        )

                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Person,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Text(
                                            text = artist,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Text(
                                            text = getLocalized("$plays repr.", "$plays plays"),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (index < topArtists.lastIndex) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                    }
                                }
                            }
                        }
                    }
                }

                // Time Distribution Section (Custom Bar Chart)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = getLocalized("DISTRIBUCIÓN HORARIA DE ESCUCHA", "LISTENING TIME DISTRIBUTION"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )

                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            val labels = listOf(
                                getLocalized("Madrugada (12am - 6am)", "Midnight (12am - 6am)"),
                                getLocalized("Mañana (6am - 12pm)", "Morning (6am - 12pm)"),
                                getLocalized("Tarde (12pm - 6pm)", "Afternoon (12pm - 6pm)"),
                                getLocalized("Noche (6pm - 12am)", "Night (6pm - 12am)")
                            )
                            val icons = listOf(
                                Icons.Rounded.ModeNight,
                                Icons.Rounded.WbSunny,
                                Icons.Rounded.LightMode,
                                Icons.Rounded.NightsStay
                            )

                            labels.forEachIndexed { i, label ->
                                val plays = timeDistribution[i]
                                val percentage = (plays.toFloat() / totalPlays * 100).toInt()
                                val barWeight = (plays.toFloat() / totalPlays).coerceAtLeast(0.08f)

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = icons[i],
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Text(text = "$percentage%", fontSize = 13.sp, fontWeight = FontWeight.Black)
                                    }

                                    // Bar representation
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(barWeight)
                                                .fillMaxHeight()
                                                .clip(CircleShape)
                                                .background(
                                                    Brush.horizontalGradient(
                                                        colors = listOf(
                                                            MaterialTheme.colorScheme.primary,
                                                            MaterialTheme.colorScheme.secondary
                                                        )
                                                    )
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun EmptyStatsCard(text: String) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
