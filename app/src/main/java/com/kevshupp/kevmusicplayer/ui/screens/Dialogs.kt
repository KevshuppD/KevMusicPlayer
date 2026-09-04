package com.kevshupp.kevmusicplayer.ui.screens

import androidx.compose.runtime.Composable
import com.kevshupp.kevmusicplayer.data.AudioFile
import com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel
import com.kevshupp.kevmusicplayer.ui.screens.dialogs.*

// Re-export dialogs for backwards compatibility with any direct callers in ui.screens
@Composable
fun AddSongsToPlaylistDialog(
    playlistName: String,
    audioFiles: List<AudioFile>,
    viewModel: MediaBrowserViewModel?,
    onDismiss: () -> Unit
) = com.kevshupp.kevmusicplayer.ui.screens.dialogs.AddSongsToPlaylistDialog(playlistName, audioFiles, viewModel, onDismiss)

@Composable
fun TagEditorDialog(
    song: AudioFile,
    viewModel: MediaBrowserViewModel?,
    onDismiss: () -> Unit
) = com.kevshupp.kevmusicplayer.ui.screens.dialogs.TagEditorDialog(song, viewModel, onDismiss)

@Composable
fun AlbumCoverEditorDialog(
    albumName: String,
    viewModel: MediaBrowserViewModel?,
    onDismiss: () -> Unit
) = com.kevshupp.kevmusicplayer.ui.screens.dialogs.AlbumCoverEditorDialog(albumName, viewModel, onDismiss)

@Composable
fun AlbumEditorDialog(
    albumName: String,
    viewModel: MediaBrowserViewModel?,
    onDismiss: (String?) -> Unit
) = com.kevshupp.kevmusicplayer.ui.screens.dialogs.AlbumEditorDialog(albumName, viewModel, onDismiss)

@Composable
fun DuplicateFinderDialog(
    viewModel: MediaBrowserViewModel,
    onDismiss: () -> Unit
) = com.kevshupp.kevmusicplayer.ui.screens.dialogs.DuplicateFinderDialog(viewModel, onDismiss)

@Composable
fun SongIntegrityDialog(
    viewModel: MediaBrowserViewModel,
    onDismiss: () -> Unit
) = com.kevshupp.kevmusicplayer.ui.screens.dialogs.SongIntegrityDialog(viewModel, onDismiss)

@Composable
fun ShortSongsDialog(
    viewModel: MediaBrowserViewModel,
    onDismiss: () -> Unit
) = com.kevshupp.kevmusicplayer.ui.screens.dialogs.ShortSongsDialog(viewModel, onDismiss)

@Composable
fun MissingCoverFinderDialog(
    viewModel: MediaBrowserViewModel,
    onDismiss: () -> Unit
) = com.kevshupp.kevmusicplayer.ui.screens.dialogs.MissingCoverFinderDialog(viewModel, onDismiss)
