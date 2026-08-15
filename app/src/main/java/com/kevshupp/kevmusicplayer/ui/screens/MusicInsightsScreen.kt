package com.kevshupp.kevmusicplayer.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.data.AudioFile
import kotlinx.coroutines.launch
import java.util.Calendar

enum class InsightPeriod {
    ALL_TIME,
    THIS_YEAR,
    THIS_MONTH,
    LAST_30_DAYS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicInsightsScreen(
    visible: Boolean,
    onDismiss: () -> Unit,
    audioFiles: List<AudioFile>,
    getLocalized: (String, String) -> String,
    onPlaySongs: ((List<AudioFile>, Int) -> Unit)? = null,
    onCreatePlaylist: ((String, List<AudioFile>) -> Unit)? = null
) {
    if (!visible) return

    androidx.activity.compose.BackHandler(enabled = visible) {
        onDismiss()
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val graphicsLayer = rememberGraphicsLayer()
    val posterGraphicsLayer = rememberGraphicsLayer()

    var selectedPeriod by remember { mutableStateOf(InsightPeriod.ALL_TIME) }
    var showPosterDialog by remember { mutableStateOf(false) }

    // Period boundary calculations
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val startOfYear = remember {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val startOfMonth = remember {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val thirtyDaysAgo = remember {
        System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
    }

    // Filtered songs
    val playedSongs = remember(audioFiles, selectedPeriod) {
        val base = audioFiles.filter { it.playCount > 0 }
        when (selectedPeriod) {
            InsightPeriod.ALL_TIME -> base
            InsightPeriod.THIS_YEAR -> base.filter { it.lastPlayed >= startOfYear }
            InsightPeriod.THIS_MONTH -> base.filter { it.lastPlayed >= startOfMonth }
            InsightPeriod.LAST_30_DAYS -> base.filter { it.lastPlayed >= thirtyDaysAgo }
        }
    }

    val totalMinListened = remember(playedSongs) {
        playedSongs.sumOf { it.playCount.toLong() * it.duration } / 1000 / 60
    }

    val totalPlays = remember(playedSongs) {
        playedSongs.sumOf { it.playCount }
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

    val topAlbums = remember(playedSongs) {
        playedSongs.filter { it.album.isNotBlank() && it.album != "<unknown>" }
            .groupBy { it.album to it.artist }
            .map { (key, songs) ->
                Triple(key.first, key.second, songs.sumOf { it.playCount } to songs.first().uriString)
            }
            .sortedByDescending { it.third.first }
            .take(5)
    }

    val topGenres = remember(playedSongs) {
        playedSongs.filter { it.genre.isNotBlank() && it.genre != "<unknown>" }
            .groupBy { it.genre }
            .mapValues { entry -> entry.value.sumOf { it.playCount } }
            .toList()
            .sortedByDescending { it.second }
            .take(5)
    }

    val totalGenrePlays = remember(topGenres) {
        topGenres.sumOf { it.second }.coerceAtLeast(1)
    }

    val favoriteGenre = remember(topGenres) {
        topGenres.firstOrNull()?.first ?: getLocalized("Varios", "Various")
    }

    val dayDistribution = remember(playedSongs) {
        val counts = IntArray(7) // 0: Dom, 1: Lun, 2: Mar, 3: Mie, 4: Jue, 5: Vie, 6: Sab
        val cal = Calendar.getInstance()
        playedSongs.forEach { song ->
            if (song.lastPlayed > 0L) {
                cal.timeInMillis = song.lastPlayed
                val day = cal.get(Calendar.DAY_OF_WEEK) - 1
                if (day in 0..6) counts[day] += song.playCount
            }
        }
        counts
    }

    val dayNames = listOf(
        getLocalized("Dom", "Sun"),
        getLocalized("Lun", "Mon"),
        getLocalized("Mar", "Tue"),
        getLocalized("Mié", "Wed"),
        getLocalized("Jue", "Thu"),
        getLocalized("Vie", "Fri"),
        getLocalized("Sáb", "Sat")
    )
    val fullDayNames = listOf(
        getLocalized("Domingos", "Sundays"),
        getLocalized("Lunes", "Mondays"),
        getLocalized("Martes", "Tuesdays"),
        getLocalized("Miércoles", "Wednesdays"),
        getLocalized("Jueves", "Thursdays"),
        getLocalized("Viernes", "Fridays"),
        getLocalized("Sábados", "Saturdays")
    )
    val peakDayIndex = remember(dayDistribution) {
        dayDistribution.indices.maxByOrNull { dayDistribution[it] } ?: 5
    }
    val totalDayPlays = remember(dayDistribution) {
        dayDistribution.sum().coerceAtLeast(1)
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
    val totalTimePlays = remember(timeDistribution) {
        timeDistribution.sum().coerceAtLeast(1)
    }

    val shareStatsText = remember(totalMinListened, topArtists, topSongs, favoriteGenre, selectedPeriod) {
        val periodStr = when (selectedPeriod) {
            InsightPeriod.ALL_TIME -> getLocalized("Histórico", "All-Time")
            InsightPeriod.THIS_YEAR -> getLocalized("Año $currentYear", "Year $currentYear")
            InsightPeriod.THIS_MONTH -> getLocalized("Este Mes", "This Month")
            InsightPeriod.LAST_30_DAYS -> getLocalized("Últimos 30 días", "Last 30 Days")
        }
        val topArtistStr = topArtists.firstOrNull()?.first ?: "N/A"
        val topSongStr = topSongs.firstOrNull()?.title ?: "N/A"
        """
        🎶 ¡Mi KevWrapped de KevMusicPlayer ($periodStr)! 🎶
        
        ⏱️ Minutos escuchados: $totalMinListened min
        🎧 Reproducciones: $totalPlays
        🎤 Artista Top #1: $topArtistStr
        🎵 Canción Top #1: $topSongStr
        💿 Género Favorito: $favoriteGenre
        🗓️ Día más activo: ${fullDayNames.getOrElse(peakDayIndex) { "Viernes" }}
        
        ¡Disfruta tu música offline sin límites con KevMusicPlayer!
        """.trimIndent()
    }

    val shareBitmap: (androidx.compose.ui.graphics.layer.GraphicsLayer) -> Unit = { layer ->
        coroutineScope.launch {
            try {
                val imageBitmap = layer.toImageBitmap()
                val bitmap = imageBitmap.asAndroidBitmap()
                val cacheFile = java.io.File(context.cacheDir, "kev_wrapped_${System.currentTimeMillis()}.png")
                java.io.FileOutputStream(cacheFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val authority = "${context.packageName}.fileprovider"
                val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, cacheFile)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, shareStatsText)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, getLocalized("Compartir KevWrapped", "Share KevWrapped")))
            } catch (e: Exception) {
                e.printStackTrace()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareStatsText)
                }
                context.startActivity(Intent.createChooser(intent, getLocalized("Compartir Estadísticas", "Share Insights")))
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
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
                        Column {
                            Text(
                                text = getLocalized("KevWrapped", "KevWrapped"),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = getLocalized("Resumen Musical & Estadísticas", "Music Insights & Statistics"),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Poster Story Generator Button
                        IconButton(
                            onClick = { showPosterDialog = true },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = "Wrapped Poster",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }

                        // Share Screenshot Button
                        IconButton(
                            onClick = { shareBitmap(graphicsLayer) },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = "Share",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Filter Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val periods = listOf(
                        InsightPeriod.ALL_TIME to getLocalized("Todo el tiempo", "All Time"),
                        InsightPeriod.THIS_YEAR to getLocalized("Año $currentYear", "Year $currentYear"),
                        InsightPeriod.THIS_MONTH to getLocalized("Este mes", "This Month"),
                        InsightPeriod.LAST_30_DAYS to getLocalized("Últimos 30 días", "Last 30 Days")
                    )

                    periods.forEach { (period, label) ->
                        val isSelected = selectedPeriod == period
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedPeriod = period },
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                labelColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = null,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                val backgroundColor = MaterialTheme.colorScheme.background
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawWithContent {
                                graphicsLayer.record {
                                    this@drawWithContent.drawContent()
                                }
                                drawLayer(graphicsLayer)
                            }
                            .background(backgroundColor)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // 1. Premium Overview Card
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
                                .padding(22.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = getLocalized("TU SINTONÍA MUSICAL", "YOUR MUSIC HARMONY"),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 1.5.sp,
                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.Headphones,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "$totalMinListened",
                                            fontSize = 34.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Text(
                                            text = getLocalized("Minutos Totales", "Total Minutes"),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "$totalPlays",
                                            fontSize = 34.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Text(
                                            text = getLocalized("Reproducciones", "Plays"),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f))
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = getLocalized("Género Favorito", "Top Genre"),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                                        )
                                        Text(
                                            text = favoriteGenre,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = Alignment.End
                                    ) {
                                        Text(
                                            text = getLocalized("Día Más Activo", "Peak Day"),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                                        )
                                        Text(
                                            text = fullDayNames.getOrElse(peakDayIndex) { "Viernes" },
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Action Shortcuts Row (Play Top & Save Playlist)
                        if (topSongs.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        onPlaySongs?.invoke(topSongs, 0)
                                        onDismiss()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = getLocalized("Reproducir Top", "Play Top"),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        val playlistName = when (selectedPeriod) {
                                            InsightPeriod.ALL_TIME -> "Top Favoritas KevMusic"
                                            InsightPeriod.THIS_YEAR -> "Top $currentYear"
                                            InsightPeriod.THIS_MONTH -> "Top Mes"
                                            InsightPeriod.LAST_30_DAYS -> "Top 30 Días"
                                        }
                                        onCreatePlaylist?.invoke(playlistName, topSongs)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = getLocalized("Crear Playlist", "Save Playlist"),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // 3. Top Songs Section
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = getLocalized("TOP 5 CANCIONES", "TOP 5 SONGS"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.sp
                            )

                            if (topSongs.isEmpty()) {
                                EmptyStatsCard(getLocalized("Sin canciones reproducidas en este período.", "No songs played in this period."))
                            } else {
                                Card(
                                    shape = RoundedCornerShape(24.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        topSongs.forEachIndexed { index, song ->
                                            val artBytes = rememberAlbumArt(song.uriString)
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        onPlaySongs?.invoke(topSongs, index)
                                                        onDismiss()
                                                    }
                                                    .padding(vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "#${index + 1}",
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Black,
                                                    color = when (index) {
                                                        0 -> Color(0xFFFFD700) // Gold
                                                        1 -> Color(0xFFC0C0C0) // Silver
                                                        2 -> Color(0xFFCD7F32) // Bronze
                                                        else -> MaterialTheme.colorScheme.primary
                                                    },
                                                    modifier = Modifier.width(36.dp)
                                                )

                                                Card(
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.size(44.dp)
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
                                                                    modifier = Modifier.size(18.dp)
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
                                                        color = MaterialTheme.colorScheme.onSurface,
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
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            if (index < topSongs.lastIndex) {
                                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 4. Top Artists Section
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = getLocalized("TOP 5 ARTISTAS", "TOP 5 ARTISTS"),
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
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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

                                                ArtistImage(
                                                    artist = artist,
                                                    modifier = Modifier
                                                        .size(44.dp)
                                                        .clip(CircleShape)
                                                )

                                                Spacer(modifier = Modifier.width(12.dp))

                                                Text(
                                                    text = artist,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )

                                                Text(
                                                    text = getLocalized("$plays repr.", "$plays plays"),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                            }
                                            if (index < topArtists.lastIndex) {
                                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 5. Top Albums Section
                        if (topAlbums.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = getLocalized("TOP 5 ÁLBUMES", "TOP 5 ALBUMS"),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 1.sp
                                )

                                Card(
                                    shape = RoundedCornerShape(24.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        topAlbums.forEachIndexed { index, (album, artist, playData) ->
                                            val (plays, uri) = playData
                                            val artBytes = rememberAlbumArt(uri)
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
                                                    color = MaterialTheme.colorScheme.tertiary,
                                                    modifier = Modifier.width(36.dp)
                                                )

                                                Card(
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.size(44.dp)
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
                                                                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Rounded.Album,
                                                                    contentDescription = null,
                                                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }
                                                        }
                                                    )
                                                }

                                                Spacer(modifier = Modifier.width(12.dp))

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = album,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = artist,
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }

                                                Text(
                                                    text = getLocalized("$plays repr.", "$plays plays"),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.tertiary
                                                )
                                            }
                                            if (index < topAlbums.lastIndex) {
                                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 6. Top Genres with Percentage Progress Bars
                        if (topGenres.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = getLocalized("GÉNEROS MÁS ESCUCHADOS", "TOP GENRES BREAKDOWN"),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 1.sp
                                )

                                Card(
                                    shape = RoundedCornerShape(24.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        val genreGradients = listOf(
                                            listOf(Color(0xFF00C9FF), Color(0xFF92FE9D)),
                                            listOf(Color(0xFFFF416C), Color(0xFFFF4B2B)),
                                            listOf(Color(0xFF8A2387), Color(0xFFE94057)),
                                            listOf(Color(0xFFF7971E), Color(0xFFFFD200)),
                                            listOf(Color(0xFF654EA3), Color(0xFFEAAFC8))
                                        )

                                        topGenres.forEachIndexed { i, (genre, plays) ->
                                            val percentage = (plays.toFloat() / totalGenrePlays * 100).toInt()
                                            val barFraction = (plays.toFloat() / totalGenrePlays).coerceIn(0.08f, 1f)
                                            val gradient = genreGradients.getOrElse(i) { listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary) }

                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = genre,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = "$percentage% ($plays)",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Black,
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                                    )
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(8.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth(barFraction)
                                                            .fillMaxHeight()
                                                            .clip(CircleShape)
                                                            .background(Brush.horizontalGradient(gradient))
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 7. Weekly Activity Days Distribution
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = getLocalized("DÍAS DE LA SEMANA MÁS ACTIVOS", "ACTIVE DAYS OF THE WEEK"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.sp
                            )

                            Card(
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = getLocalized(
                                                "Día favorito: ${fullDayNames.getOrElse(peakDayIndex) { "Viernes" }}",
                                                "Top Day: ${fullDayNames.getOrElse(peakDayIndex) { "Friday" }}"
                                            ),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Icon(
                                            imageVector = Icons.Rounded.CalendarToday,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // 7 Vertical Bars for Monday to Sunday
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(90.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Bottom
                                    ) {
                                        val orderedDayIndices = listOf(1, 2, 3, 4, 5, 6, 0) // Lun to Dom
                                        orderedDayIndices.forEach { dayIdx ->
                                            val plays = dayDistribution[dayIdx]
                                            val fraction = (plays.toFloat() / totalDayPlays * 2.5f).coerceIn(0.12f, 1f)
                                            val isPeak = dayIdx == peakDayIndex

                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Bottom,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(18.dp)
                                                        .fillMaxHeight(fraction)
                                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                                        .background(
                                                            if (isPeak) {
                                                                Brush.verticalGradient(
                                                                    listOf(
                                                                        MaterialTheme.colorScheme.primary,
                                                                        MaterialTheme.colorScheme.secondary
                                                                    )
                                                                )
                                                            } else {
                                                                Brush.verticalGradient(
                                                                    listOf(
                                                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                                                    )
                                                                )
                                                            }
                                                        )
                                                )
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = dayNames[dayIdx],
                                                    fontSize = 10.sp,
                                                    fontWeight = if (isPeak) FontWeight.Black else FontWeight.Medium,
                                                    color = if (isPeak) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 8. Time of Day Distribution
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = getLocalized("HORARIOS DE ESCUCHA", "TIME OF DAY DISTRIBUTION"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.sp
                            )

                            Card(
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
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
                                        val percentage = (plays.toFloat() / totalTimePlays * 100).toInt()
                                        val barWeight = (plays.toFloat() / totalTimePlays).coerceAtLeast(0.08f)

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
                                                    Text(
                                                        text = label,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                                Text(
                                                    text = "$percentage%",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Black,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(8.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth(barWeight)
                                                        .fillMaxHeight()
                                                        .clip(CircleShape)
                                                        .background(
                                                            Brush.horizontalGradient(
                                                                listOf(
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

                        Text(
                            text = "KevMusicPlayer · Music Insights",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // Modal: Wrapped Story Poster Dialog
    if (showPosterDialog) {
        Dialog(
            onDismissRequest = { showPosterDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Visual Poster Card Container
                    Card(
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .drawWithContent {
                                posterGraphicsLayer.record {
                                    this@drawWithContent.drawContent()
                                }
                                drawLayer(posterGraphicsLayer)
                            },
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0xFF1E0836),
                                            Color(0xFF0F0C20),
                                            Color(0xFF070B19)
                                        )
                                    )
                                )
                                .padding(24.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // App Branding Header
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.GraphicEq,
                                        contentDescription = null,
                                        tint = Color(0xFF00FFCC),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "KEVMUSIC WRAPPED",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 13.sp,
                                        letterSpacing = 2.sp,
                                        color = Color(0xFF00FFCC)
                                    )
                                }

                                Text(
                                    text = when (selectedPeriod) {
                                        InsightPeriod.ALL_TIME -> getLocalized("Mi Resumen Musical", "My Music Wrapped")
                                        InsightPeriod.THIS_YEAR -> getLocalized("Lo Mejor del $currentYear", "Best of $currentYear")
                                        InsightPeriod.THIS_MONTH -> getLocalized("Mi Mes Musical", "My Monthly Hits")
                                        InsightPeriod.LAST_30_DAYS -> getLocalized("Mis Últimos 30 Días", "My Last 30 Days")
                                    },
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )

                                // Top 1 Album Art & Song Spotlight
                                val top1Song = topSongs.firstOrNull()
                                if (top1Song != null) {
                                    val artBytes = rememberAlbumArt(top1Song.uriString)
                                    Box(
                                        modifier = Modifier
                                            .size(130.dp)
                                            .shadow(20.dp, RoundedCornerShape(20.dp), spotColor = Color(0xFF00FFCC))
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(Color.DarkGray)
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
                                                        tint = Color.White,
                                                        modifier = Modifier.size(40.dp)
                                                    )
                                                }
                                            }
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = top1Song.title,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = top1Song.artist,
                                            fontSize = 13.sp,
                                            color = Color.White.copy(alpha = 0.7f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Key Stats Grid
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                                        .padding(vertical = 12.dp, horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "$totalMinListened",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color(0xFF00FFCC)
                                        )
                                        Text(
                                            text = getLocalized("Minutos", "Minutes"),
                                            fontSize = 11.sp,
                                            color = Color.White.copy(alpha = 0.6f)
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "${topArtists.firstOrNull()?.first ?: "N/A"}",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color(0xFFFF66CC),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = getLocalized("Top Artista", "Top Artist"),
                                            fontSize = 11.sp,
                                            color = Color.White.copy(alpha = 0.6f)
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = favoriteGenre,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color(0xFFFFD200),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = getLocalized("Género", "Genre"),
                                            fontSize = 11.sp,
                                            color = Color.White.copy(alpha = 0.6f)
                                        )
                                    }
                                }

                                // Top 3 Songs list
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    topSongs.take(3).forEachIndexed { idx, song ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "#${idx + 1}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Black,
                                                color = Color(0xFF00FFCC),
                                                modifier = Modifier.width(24.dp)
                                            )
                                            Text(
                                                text = "${song.title} · ${song.artist}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color.White.copy(alpha = 0.9f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = "Reproducido en KevMusicPlayer Offline",
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Share & Close Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(0.92f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                shareBitmap(posterGraphicsLayer)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00FFCC),
                                contentColor = Color.Black
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = getLocalized("Compartir Historia", "Share Story"),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        IconButton(
                            onClick = { showPosterDialog = false },
                            modifier = Modifier
                                .size(50.dp)
                                .background(Color.White.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close",
                                tint = Color.White
                            )
                        }
                    }
                }
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
