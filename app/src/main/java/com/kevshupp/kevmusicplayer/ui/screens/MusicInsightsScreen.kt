package com.kevshupp.kevmusicplayer.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
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

    val enableCardTransparency = com.kevshupp.kevmusicplayer.ui.theme.LocalCardTransparencyEnabled.current
    val insightsCardContainerColor = if (enableCardTransparency) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)

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
                                        containerColor = insightsCardContainerColor,
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
                                        containerColor = insightsCardContainerColor,
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
                                        containerColor = insightsCardContainerColor,
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
                                        containerColor = insightsCardContainerColor,
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
                                    containerColor = insightsCardContainerColor,
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
                                    containerColor = insightsCardContainerColor,
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

    // Modal: Spotify-Wrapped Fullscreen Interactive Story Experience
    if (showPosterDialog) {
        Dialog(
            onDismissRequest = { showPosterDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true
            )
        ) {
            val totalSlides = 7
            var currentSlide by remember { mutableIntStateOf(0) }
            var isPaused by remember { mutableStateOf(false) }
            val slideProgress = remember { androidx.compose.animation.core.Animatable(0f) }

            // Story auto-advance timer
            LaunchedEffect(currentSlide, isPaused) {
                if (!isPaused) {
                    slideProgress.snapTo(0f)
                    slideProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 5500,
                            easing = androidx.compose.animation.core.LinearEasing
                        )
                    )
                    if (currentSlide < totalSlides - 1) {
                        currentSlide++
                    }
                }
            }

            // Determine listening personality archetype
            val listeningPersonality = remember(playedSongs, topArtists, topGenres, totalMinListened, peakDayIndex) {
                when {
                    topArtists.isNotEmpty() && (topArtists.first().second.toFloat() / totalPlays.coerceAtLeast(1)) > 0.45f -> {
                        Pair(
                            getLocalized("El Fan Devoto", "The Devoted Fan"),
                            getLocalized("Cuando encuentras un artista que te apasiona, lo escuchas en bucle sin descanso.", "When you love an artist, you keep them on repeat without hesitation.")
                        )
                    }
                    timeDistribution[0] > (totalPlays * 0.3f) -> {
                        Pair(
                            getLocalized("El Melómano Búho", "The Night Owl"),
                            getLocalized("Tus mejores sesiones musicales ocurren de madrugada bajo las estrellas.", "Your best music sessions happen in the dead of night under the stars.")
                        )
                    }
                    topGenres.size >= 4 -> {
                        Pair(
                            getLocalized("El Explorador Sónico", "The Sonic Explorer"),
                            getLocalized("Te mueves entre múltiples géneros y ritmos sin encerrarte en un solo estilo.", "You glide between genres and rhythms without staying in one lane.")
                        )
                    }
                    playedSongs.size > 50 -> {
                        Pair(
                            getLocalized("El Archivista Acústico", "The Acoustic Collector"),
                            getLocalized("Tu biblioteca es amplia, variada y disfrutas redescubrir cada joya musical.", "Your library is vast, diverse, and you cherish rediscovering every music gem.")
                        )
                    }
                    else -> {
                        Pair(
                            getLocalized("El Connoisseur Musical", "The Music Connoisseur"),
                            getLocalized("Escuchas con intención, priorizando calidad y tus temas favoritos.", "You listen with intent, prioritizing quality and your favorite hits.")
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(currentSlide) {
                        detectTapGestures(
                            onPress = {
                                isPaused = true
                                val released = tryAwaitRelease()
                                isPaused = false
                            },
                            onTap = { offset ->
                                val screenWidth = size.width
                                if (offset.x < screenWidth * 0.35f) {
                                    if (currentSlide > 0) currentSlide--
                                } else {
                                    if (currentSlide < totalSlides - 1) currentSlide++
                                }
                            }
                        )
                    }
            ) {
                // Background dynamic gradient based on current slide
                val backgroundBrush = remember(currentSlide) {
                    when (currentSlide) {
                        0 -> Brush.verticalGradient(listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)))
                        1 -> Brush.verticalGradient(listOf(Color(0xFF2E0854), Color(0xFF180B28), Color(0xFF003838)))
                        2 -> Brush.verticalGradient(listOf(Color(0xFF3A1C71), Color(0xFFD76D77), Color(0xFFFFAF7B)))
                        3 -> Brush.verticalGradient(listOf(Color(0xFF11998E), Color(0xFF38EF7D), Color(0xFF051923)))
                        4 -> Brush.verticalGradient(listOf(Color(0xFF8A2387), Color(0xFFE94057), Color(0xFFF27121)))
                        5 -> Brush.verticalGradient(listOf(Color(0xFF654EA3), Color(0xFFEAAFC8), Color(0xFF1B1B2F)))
                        else -> Brush.verticalGradient(listOf(Color(0xFF1E0836), Color(0xFF0F0C20), Color(0xFF070B19)))
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundBrush)
                )

                // Slide Content Container
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Progress Bars Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (i in 0 until totalSlides) {
                            val progress = when {
                                i < currentSlide -> 1f
                                i == currentSlide -> slideProgress.value
                                else -> 0f
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.25f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(progress)
                                        .background(Color.White)
                                )
                            }
                        }
                    }

                    // Top Bar: KevMusic Wrapped Branding + Close Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = Color(0xFF00FFCC),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "KEVMUSIC WRAPPED",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp,
                                color = Color(0xFF00FFCC)
                            )
                        }

                        IconButton(
                            onClick = { showPosterDialog = false },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(0.2f))

                    // Current Slide Body
                    Box(
                        modifier = Modifier
                            .weight(3f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = currentSlide,
                            transitionSpec = {
                                (fadeIn(androidx.compose.animation.core.tween(300)) + slideInHorizontally { width -> width / 3 })
                                    .togetherWith(fadeOut(androidx.compose.animation.core.tween(200)))
                            },
                            label = "WrappedStorySlide"
                        ) { slide ->
                            when (slide) {
                                // Slide 0: Total Minutes & Total Plays
                                0 -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = getLocalized("ESTE AÑO PASASTE", "YOU SPENT"),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 2.sp,
                                            color = Color.White.copy(alpha = 0.7f)
                                        )
                                        Text(
                                            text = "$totalMinListened",
                                            fontSize = 64.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color(0xFF00FFCC),
                                            lineHeight = 70.sp
                                        )
                                        Text(
                                            text = getLocalized("minutos escuchando tu música favorita", "minutes listening to your favorite music"),
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 24.dp)
                                        )

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(Color.White.copy(alpha = 0.12f))
                                                .padding(horizontal = 20.dp, vertical = 12.dp)
                                        ) {
                                            Text(
                                                text = getLocalized("Eso equivale a ${(totalMinListened / 60.0).let { "%.1f".format(it) }} horas de ritmos sin pausas", "That equals ${(totalMinListened / 60.0).let { "%.1f".format(it) }} hours of non-stop beats"),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color.White.copy(alpha = 0.9f),
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }

                                // Slide 1: Sound Aura & Top Genres
                                1 -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(18.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = getLocalized("TU AURA SONORA", "YOUR AUDIO AURA"),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 2.sp,
                                            color = Color(0xFFFF66CC)
                                        )

                                        Box(
                                            modifier = Modifier
                                                .size(140.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    Brush.radialGradient(
                                                        listOf(
                                                            Color(0xFFFF007F),
                                                            Color(0xFF7928CA),
                                                            Color(0xFF00DFD8)
                                                        )
                                                    )
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.GraphicEq,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(54.dp)
                                            )
                                        }

                                        Text(
                                            text = getLocalized("Tus Géneros Principales", "Your Top Genres"),
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White
                                        )

                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 20.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            topGenres.take(4).forEachIndexed { idx, (genre, plays) ->
                                                val pct = (plays.toFloat() / totalGenrePlays) * 100
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "${idx + 1}. $genre",
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White
                                                    )
                                                    Text(
                                                        text = "${pct.toInt()}%",
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Black,
                                                        color = Color(0xFF00FFCC)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Slide 2: Listening Habits & Clock
                                2 -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = getLocalized("TUS HÁBITOS MUSICALES", "YOUR LISTENING HABITS"),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 2.sp,
                                            color = Color(0xFFFFD200)
                                        )

                                        Text(
                                            text = getLocalized("Tu día con más ritmo fue:", "Your most active day was:"),
                                            fontSize = 16.sp,
                                            color = Color.White.copy(alpha = 0.8f)
                                        )

                                        Text(
                                            text = fullDayNames.getOrElse(peakDayIndex) { "Viernes" },
                                            fontSize = 36.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color(0xFFFFD200)
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        val (timeTitle, timeEmoji) = when {
                                            timeDistribution[0] >= timeDistribution.maxOrNull()!! -> Pair(getLocalized("Búho Nocturno (Madrugada)", "Night Owl (Late Night)"), "🌙")
                                            timeDistribution[1] >= timeDistribution.maxOrNull()!! -> Pair(getLocalized("Madrugador Musical (Mañana)", "Early Bird (Morning)"), "🌅")
                                            timeDistribution[2] >= timeDistribution.maxOrNull()!! -> Pair(getLocalized("Energía de Tarde", "Afternoon Energy"), "☀️")
                                            else -> Pair(getLocalized("Noches Melódicas", "Evening Melodies"), "🌆")
                                        }

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.85f)
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(Color.White.copy(alpha = 0.15f))
                                                .padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(text = timeEmoji, fontSize = 32.sp)
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = timeTitle,
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    }
                                }

                                // Slide 3: Top Songs Countdown
                                3 -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = getLocalized("TU CANCIÓN #1", "YOUR TOP SONG"),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 2.sp,
                                            color = Color(0xFF00FFCC)
                                        )

                                        val top1 = topSongs.firstOrNull()
                                        if (top1 != null) {
                                            val artBytes = rememberAlbumArt(top1.uriString)
                                            Box(
                                                modifier = Modifier
                                                    .size(150.dp)
                                                    .shadow(24.dp, RoundedCornerShape(24.dp), spotColor = Color(0xFF00FFCC))
                                                    .clip(RoundedCornerShape(24.dp))
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
                                                                modifier = Modifier.size(50.dp)
                                                            )
                                                        }
                                                    }
                                                )
                                            }

                                            Text(
                                                text = top1.title,
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color.White,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${top1.artist} · ${top1.playCount} ${getLocalized("reproducciones", "plays")}",
                                                fontSize = 14.sp,
                                                color = Color.White.copy(alpha = 0.75f),
                                                textAlign = TextAlign.Center
                                            )
                                        }

                                        // Rest of top songs
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            topSongs.drop(1).take(3).forEachIndexed { idx, song ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .background(Color.White.copy(alpha = 0.08f))
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "#${idx + 2}",
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 13.sp,
                                                        color = Color(0xFF00FFCC),
                                                        modifier = Modifier.width(26.dp)
                                                    )
                                                    Text(
                                                        text = "${song.title} — ${song.artist}",
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = Color.White,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Slide 4: Top Artists Spotlight
                                4 -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = getLocalized("TU ARTISTA #1", "YOUR #1 ARTIST"),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 2.sp,
                                            color = Color(0xFFFF66CC)
                                        )

                                        val topArtistName = topArtists.firstOrNull()?.first ?: "N/A"
                                        val topArtistPlays = topArtists.firstOrNull()?.second ?: 0

                                        ArtistImage(
                                            artist = topArtistName,
                                            modifier = Modifier
                                                .size(150.dp)
                                                .shadow(24.dp, CircleShape, spotColor = Color(0xFFFF66CC))
                                                .clip(CircleShape)
                                        )

                                        Text(
                                            text = topArtistName,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color.White,
                                            textAlign = TextAlign.Center
                                        )

                                        Text(
                                            text = getLocalized("$topArtistPlays reproducciones acumuladas", "$topArtistPlays total plays"),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White.copy(alpha = 0.8f)
                                        )

                                        // Top Artists Ranking list
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            topArtists.drop(1).take(3).forEachIndexed { idx, (artistName, plays) ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .background(Color.White.copy(alpha = 0.08f))
                                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "#${idx + 2}  $artistName",
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White
                                                    )
                                                    Text(
                                                        text = "$plays plays",
                                                        fontSize = 12.sp,
                                                        color = Color(0xFFFF66CC),
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Slide 5: Musical Archetype / Personality
                                5 -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = getLocalized("TU PERSONALIDAD MUSICAL", "YOUR MUSICAL PERSONALITY"),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 2.sp,
                                            color = Color(0xFFFFD200)
                                        )

                                        Box(
                                            modifier = Modifier
                                                .size(110.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Psychology,
                                                contentDescription = null,
                                                tint = Color(0xFFFFD200),
                                                modifier = Modifier.size(54.dp)
                                            )
                                        }

                                        Text(
                                            text = listeningPersonality.first,
                                            fontSize = 26.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color.White,
                                            textAlign = TextAlign.Center
                                        )

                                        Text(
                                            text = listeningPersonality.second,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            color = Color.White.copy(alpha = 0.85f),
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 24.dp)
                                        )
                                    }
                                }

                                // Slide 6: Final Shareable Summary Poster Card
                                else -> {
                                    Card(
                                        shape = RoundedCornerShape(24.dp),
                                        modifier = Modifier
                                            .fillMaxWidth(0.95f)
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
                                                .padding(20.dp)
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.GraphicEq,
                                                        contentDescription = null,
                                                        tint = Color(0xFF00FFCC),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = "KEVMUSIC WRAPPED",
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 12.sp,
                                                        letterSpacing = 2.sp,
                                                        color = Color(0xFF00FFCC)
                                                    )
                                                }

                                                Text(
                                                    text = getLocalized("Mi Resumen Musical", "My Music Wrapped"),
                                                    fontSize = 20.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.White
                                                )

                                                // Top 1 Song
                                                val top1Song = topSongs.firstOrNull()
                                                if (top1Song != null) {
                                                    val artBytes = rememberAlbumArt(top1Song.uriString)
                                                    Box(
                                                        modifier = Modifier
                                                            .size(100.dp)
                                                            .clip(RoundedCornerShape(16.dp))
                                                            .background(Color.DarkGray)
                                                    ) {
                                                        SubcomposeAsyncImage(
                                                            model = artBytes,
                                                            contentDescription = null,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    }
                                                    Text(
                                                        text = top1Song.title,
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        maxLines = 1
                                                    )
                                                    Text(
                                                        text = top1Song.artist,
                                                        fontSize = 12.sp,
                                                        color = Color.White.copy(alpha = 0.7f),
                                                        maxLines = 1
                                                    )
                                                }

                                                // Key Stats Row
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                                                        .padding(vertical = 8.dp, horizontal = 12.dp),
                                                    horizontalArrangement = Arrangement.SpaceAround
                                                ) {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text(text = "$totalMinListened", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color(0xFF00FFCC))
                                                        Text(text = getLocalized("Minutos", "Minutes"), fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
                                                    }
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text(text = topArtists.firstOrNull()?.first ?: "N/A", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color(0xFFFF66CC), maxLines = 1)
                                                        Text(text = getLocalized("Top Artista", "Top Artist"), fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
                                                    }
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text(text = favoriteGenre, fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color(0xFFFFD200), maxLines = 1)
                                                        Text(text = getLocalized("Género", "Genre"), fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
                                                    }
                                                }

                                                // Top 3 Tracks
                                                Column(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    topSongs.take(3).forEachIndexed { idx, song ->
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text(text = "#${idx + 1}", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color(0xFF00FFCC), modifier = Modifier.width(20.dp))
                                                            Text(text = "${song.title} · ${song.artist}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        }
                                                    }
                                                }

                                                Text(
                                                    text = "🎧 ${listeningPersonality.first} • KevMusicPlayer Offline",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = Color.White.copy(alpha = 0.5f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(0.2f))

                    // Bottom Navigation / Share Actions
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentSlide == totalSlides - 1) {
                            Button(
                                onClick = { shareBitmap(posterGraphicsLayer) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                shape = RoundedCornerShape(18.dp),
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
                                    text = getLocalized("Compartir en Redes", "Share on Socials"),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 14.sp
                                )
                            }

                            IconButton(
                                onClick = { currentSlide = 0 },
                                modifier = Modifier
                                    .size(52.dp)
                                    .background(Color.White.copy(alpha = 0.15f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Replay,
                                    contentDescription = "Replay",
                                    tint = Color.White
                                )
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    if (currentSlide < totalSlides - 1) currentSlide++
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    text = getLocalized("Toca para continuar ➔", "Tap to continue ➔"),
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStatsCard(text: String) {
    val enableCardTransparency = com.kevshupp.kevmusicplayer.ui.theme.LocalCardTransparencyEnabled.current
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enableCardTransparency) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
        ),
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
