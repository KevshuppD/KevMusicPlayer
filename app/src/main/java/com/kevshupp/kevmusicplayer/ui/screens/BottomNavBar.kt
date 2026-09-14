package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.SubcomposeAsyncImage
import com.kevshupp.kevmusicplayer.ui.theme.LocalDisableAnimations
import com.kevshupp.kevmusicplayer.ui.theme.LocalSongImageRounded
import com.kevshupp.kevmusicplayer.ui.theme.LocalNavBarTransparencyEnabled

@Composable
fun BottomNavBar(
    currentScreen: String, // "home" or "library"
    onTabSelected: (String) -> Unit,
    player: Player? = null,
    onMiniPlayerClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val systemLang = remember { context.resources.configuration.locales[0].language }
    val homeText = if (systemLang == "es") "Inicio" else "Home"
    val libraryText = if (systemLang == "es") "Biblioteca" else "Library"

    val disableAnimations = LocalDisableAnimations.current
    val enableTransparency = LocalNavBarTransparencyEnabled.current
    val playerState = if (player != null) rememberPlayerState(player) else null
    val hasCurrentSong = playerState?.currentSong != null

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (enableTransparency) 20.dp else 14.dp,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = if (enableTransparency) 0.22f else 0.08f)
            ),
        color = if (enableTransparency) MaterialTheme.colorScheme.surface.copy(alpha = 0.92f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = if (enableTransparency) 0.08f else 0.12f)
        ),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Modern Floating Mini Player Dock
            if (player != null && playerState != null) {
                AnimatedVisibility(
                    visible = hasCurrentSong,
                    enter = if (disableAnimations) EnterTransition.None else (expandVertically(tween(250)) + fadeIn(tween(250))),
                    exit = if (disableAnimations) ExitTransition.None else (shrinkVertically(tween(250)) + fadeOut(tween(200)))
                ) {
                    val metadata = playerState.currentSong?.mediaMetadata
                    val title = metadata?.title?.toString() ?: "Unknown Song"
                    val artist = metadata?.artist?.toString() ?: "Unknown Artist"
                    val currentSongId = playerState.currentSong?.mediaId
                    val currentSongUriString = remember(currentSongId) {
                        currentSongId?.let { "content://media/external/audio/media/$it" }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
                    ) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enableTransparency) 0.35f else 0.55f)
                            ),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = if (enableTransparency) 0.06f else 0.10f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { onMiniPlayerClick?.invoke() }
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(60.dp)
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Album Art Thumbnail with refined depth
                                    val isArtRounded = LocalSongImageRounded.current
                                    val artShape = if (isArtRounded) RoundedCornerShape(12.dp) else androidx.compose.ui.graphics.RectangleShape

                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(artShape)
                                            .background(getGradientForString(title)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val artBytes = rememberAlbumArt(currentSongUriString)
                                        SubcomposeAsyncImage(
                                            model = artBytes,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                            loading = {
                                                Icon(
                                                    imageVector = Icons.Rounded.MusicNote,
                                                    contentDescription = null,
                                                    tint = Color.White.copy(alpha = 0.8f),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            error = {
                                                Icon(
                                                    imageVector = Icons.Rounded.MusicNote,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // Song Title & Artist Hierarchy
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = title,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = artist,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    // Interactive Play/Pause Button with circular glowing background
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                            .clickable {
                                                if (playerState.isPlaying) player.pause() else player.play()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            contentDescription = "Play/Pause",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    // Skip Next Button
                                    IconButton(
                                        onClick = { player.seekToNext() },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.SkipNext,
                                            contentDescription = "Next",
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                // Integrated Seamless Glowing Progress Track
                                val progress = if (playerState.duration > 0) {
                                    (playerState.position.toFloat() / playerState.duration.toFloat()).coerceIn(0f, 1f)
                                } else 0f

                                val animatedProgress by animateFloatAsState(
                                    targetValue = progress,
                                    animationSpec = tween(if (disableAnimations) 0 else 150),
                                    label = "mini_player_progress"
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(animatedProgress)
                                            .fillMaxHeight()
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

            // Expressive Pill Navigation Tabs (Home & Library)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavPillTab(
                    title = homeText,
                    icon = Icons.Rounded.Home,
                    isSelected = currentScreen == "home",
                    onClick = { onTabSelected("home") }
                )

                NavPillTab(
                    title = libraryText,
                    icon = Icons.Rounded.LibraryMusic,
                    isSelected = currentScreen == "library",
                    onClick = { onTabSelected("library") }
                )
            }
        }
    }
}

@Composable
private fun RowScope.NavPillTab(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = tween(220),
        label = "tab_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        animationSpec = tween(220),
        label = "tab_content_color"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.Transparent,
        animationSpec = tween(220),
        label = "tab_border"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = bgColor,
        border = if (isSelected) BorderStroke(1.dp, borderColor) else null,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
                letterSpacing = 0.2.sp
            )
        }
    }
}
