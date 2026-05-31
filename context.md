# Project Context: Kev Music Player

## 1. Project Name
**Kev Music Player**

## 2. Project Description
A highly optimized, production-grade music player application for Android built with **Kotlin** and **Jetpack Compose**. The app follows **Material Design 3 (M3)** guidelines to provide a vibrant, expressive, and premium user experience. It is designed to be highly responsive, adapting its layout seamlessly across phone, foldable, and tablet form factors, and enforces a high-refresh-rate **120Hz display pipeline** to guarantee zero UI lag and buttery-smooth scrolling on modern hardware.

## 3. Current Status
**Completed & Production Ready**. All core and advanced features described in the project briefs have been fully implemented, optimized, and verified:
- **0ms Instant Startup Loading**: Integrated a fast SQLite database cache using Room to load and show the music library instantly upon opening the application.
- **Smart Media Scanning & Folder View**: Supports multi-format audio scanning (MP3, FLAC, AAC, OGG, WAV, etc.) with automatic parent directory extraction to enable native "Browse by Folders" grid navigation.
- **Robust Background playback engine**: Powered by Media3, running seamlessly as a persistent foreground service.
- **Adaptive Layouts**: Full Phone, Foldable, and Tablet compatibility using Navigation 3 and ListDetailSceneStrategy.
- **120Hz Rendering Engine**: Programmatically forces the window's preferred display mode to the highest refresh rate supported by the device, providing buttery-smooth 120fps scrolling.
- **Persistent Playlists & Queue Management**: Full CRUD capability for lists, dynamic gradient covers, and a fully interactive playback queue.
- **Lyrics Display & LRC Synchronization**: Large scrolling lyrics pane with LRCLIB API integration, interactive time-seeking, manual lyric editing, and online lyrics search dialogs.
- **System-Wide "Manage All Files" Deletions**: Implemented robust full storage management settings intents to securely perform physical deletes of local tracks on Android 11+ (API 30+).
- **Library Multi-Select Mode**: Support for batch actions (Queue, Playlist, and Deletion) with highlighted borders, custom checkboxes, and a premium glassmorphic bottom bar.
- **Comfortable Playlist Song Injector**: Detailed Playlist view has a direct category adder (Songs, Albums, Artists, Genres) to quickly insert single tracks or full collections with live tick feedback.

## 4. Key Features
- **Local SQLite Caching (Room)**: Eliminates the blank screen problem on startup by caching scanned tracks locally. Background scans sync silently and update the database and reactive Compose lists without wiping the cache on transient startup content provider delays.
- **Browse by Folders (Directory Organizer)**: Extracts the absolute filesystem path (DATA column) from MediaStore to automatically organize the library into a grid of system folders, displaying tracks grouped by parent directories.
- **Playlists System (CRUD)**: Persists user-defined lists using SharedPreferences (`playlists_prefs`). Includes a stunning `PlaylistGridView` with dynamic gradient cards, a playlist detail view, and dialogues for custom list creation or addition of songs from any contextual menu.
- **Dynamic Playback Queue (Interactive Overlay)**: Features full queue controls including:
    - **Play Next**: Immediately inserts the song after the active track.
    - **Add to Queue**: Appends the song to the end of the playback session.
    - **Queue sheet**: An elegant `ModalBottomSheet` displaying all active tracks, highlighting the playing item with a dynamic speaker indicator, enabling direct track skipping, individual track removal, and a full queue clear option.
    - **Queue Restore**: Entire active media sequence and index are saved in `playback_prefs` and fully restored when the application is closed/destroyed and restarted, maintaining complete continuity.
- **Sleek Shuffle Button**: A compact, premium `IconButton` positioned right next to the search query bar in the library header, replacing redundant, space-consuming shuffle play cards.
- **Physical Song Deletion**: Enhanced deletion module that resolves the data path on storage to physically delete files from the device via `java.io.File.delete()`, alongside `ContentResolver` database purging. Habilitates native Settings page intents to request the `MANAGE_EXTERNAL_STORAGE` permission automatically on newer versions of Android.
- **Advanced Playback Interface**: Features an expressive deep gradient screen containing:
    - Play/Pause, Skip Next/Previous controls.
    - Shuffle and Repeat mode toggles.
    - Seeking via a custom reactive `ProgressSlider`.
    - Real-time track progress, duration, file extension, and bitrate details.
- **Scrolling & LRC Synchronized Lyrics**: Supports LRCLIB lookup, manual editing/creation, and interactive scrolling playback. Clicking any lyric line immediately seeks the player to that timestamp.
- **120Hz High-Refresh Support**: MainActivity forces the window's preferred display mode to the highest refresh rate supported by the device, providing buttery-smooth 120fps scrolling.
- **Clean Architecture (MVVM)**: Built with independent data layers, SQLite caching, hoisted ViewModels, and reactive StateFlow/SharedFlow state management.

## 5. Technical Stack
- **Language**: Kotlin (100%)
- **UI Framework**: Jetpack Compose (Declarative UI)
- **Design System**: Material Design 3 (Dynamic Color, deep mesh gradients, card indicators)
- **Audio Engine**: AndroidX Media3 (ExoPlayer, MediaLibrarySession, FFmpeg)
- **Database**: Room SQLite Database (DAO, entity synchronization, local caching)
- **Architecture**: MVVM (Model-View-ViewModel) with StateFlow, SharedFlow, and Compose SnapshotStateList
- **Image Loading**: Coil (Efficient asynchronous Album Art loading)
- **Async Concurrency**: Kotlin Coroutines & Flow (Safe off-main-thread I/O)
- **Background Tasks**: Foreground Services, MediaSessionService, and WorkManager
- **Build System**: Gradle Kotlin DSL with KSP (Kotlin Symbol Processing)

## 6. Project Structure Highlights
- `com.kevshupp.kevmusicplayer.MainActivity.kt`: Main entry point. Enforces 120Hz preferred display mode, checks runtime permissions (handling both standard storage permissions and Android 11+ native Settings intent for Manage All Files Access), manages navigation backstack transitions, and hosts adaptive layout pane bindings.
- `com.kevshupp.kevmusicplayer.playback.PlaybackService.kt`: Extends `MediaLibraryService` to run a robust background player with persistent system media controllers.
- `com.kevshupp.kevmusicplayer.data/`:
    - `AudioFile.kt`: Core domain model including directory path nodes.
    - `AudioFileEntity.kt`: Room Entity representation of cached songs.
    - `AudioDao.kt`: Data Access Object interface for transactional database CRUD operations.
    - `AppDatabase.kt`: Single-instance Room SQLite Database class.
    - `AudioScanner.kt`: Scans device storage using MediaStore, extracting absolute paths, metadata, and cache structures.
- `com.kevshupp.kevmusicplayer.playback.MediaBrowserViewModel.kt`: Bridge ViewModel holding hoisted lists and settings states, initializing connections, loading caches on startup, running background media scans, managing persistent playlists, managing the active ExoPlayer queue, and executing physical file deletions.
- `com.kevshupp.kevmusicplayer.ui.screens/`:
    - `LibraryScreen.kt`: The main portal featuring reactive tab filters (Songs, Albums, Artists, Genres, Folders, Playlists), Search query filters, sub-view detail navigators, FolderGridView, PlaylistGridView, and SongListView incorporating contextual menus, multi-selection state machines, category-based adding to playlists, and search-aligned shuffle buttons.
    - `SettingsScreen.kt`: Full-screen professional settings UI with deep mesh dark gradients, category switches, sorting selections, active 120Hz indicator badges, and glowing re-scan buttons.
    - `PlayerScreen.kt`: The playback screen featuring album art indicators, track info, progress seek sliders, physical/bitrate info, scrolling lyrics panel, quick-access Queue overlay, and playback mode controls.
- `com.kevshupp.kevmusicplayer.ui.theme/`: Color palettes, Typography models, and dynamic Material 3 design systems.
