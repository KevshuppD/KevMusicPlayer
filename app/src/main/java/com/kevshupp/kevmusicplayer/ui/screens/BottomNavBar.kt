package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.kevshupp.kevmusicplayer.ui.theme.LocalTransparencyEnabled

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
    val enableTransparency = LocalTransparencyEnabled.current
    val playerState = if (player != null) rememberPlayerState(player) else null
    val hasCurrentSong = playerState?.currentSong != null

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 20.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = if (enableTransparency) 0.25f else 0.10f)
            ),
        color = if (enableTransparency) MaterialTheme.colorScheme.surface.copy(alpha = 0.92f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (enableTransparency) 0.08f else 0.12f)),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Integrated Mini Player (Seamless Docked Header)
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

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMiniPlayerClick?.invoke() }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Album Art Thumbnail
                            val isArtRounded = LocalSongImageRounded.current
                            val artShape = if (isArtRounded) RoundedCornerShape(12.dp) else androidx.compose.ui.graphics.RectangleShape

                            Box(
                                modifier = Modifier
                                    .size(44.dp)
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
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    error = {
                                        Icon(
                                            imageVector = Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Song Title & Artist
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = artist,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Controls (Play/Pause & Next)
                            IconButton(
                                onClick = {
                                    if (playerState.isPlaying) player.pause() else player.play()
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = if (playerState.isPlaying) Icons.Rounded.PauseCircleFilled else Icons.Rounded.PlayCircleFilled,
                                    contentDescription = "Play/Pause",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(34.dp)
                                )
                            }

                            IconButton(
                                onClick = { player.seekToNext() },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipNext,
                                    contentDescription = "Next",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        // Seamless Glowing Progress Bar / Divider
                        val progress = if (playerState.duration > 0) {
                            (playerState.position.toFloat() / playerState.duration.toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
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

            // Bottom Navigation Tabs (Home & Library)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isHome = currentScreen == "home"
                val isLibrary = currentScreen == "library"

                // Home Tab
                Surface(
                    onClick = { onTabSelected("home") },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isHome) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Home,
                            contentDescription = homeText,
                            tint = if (isHome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = homeText,
                            fontSize = 11.5.sp,
                            fontWeight = if (isHome) FontWeight.Bold else FontWeight.Medium,
                            color = if (isHome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                // Library Tab
                Surface(
                    onClick = { onTabSelected("library") },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isLibrary) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LibraryMusic,
                            contentDescription = libraryText,
                            tint = if (isLibrary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = libraryText,
                            fontSize = 11.5.sp,
                            fontWeight = if (isLibrary) FontWeight.Bold else FontWeight.Medium,
                            color = if (isLibrary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}
