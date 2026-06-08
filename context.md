# Project Context: Kev Music Player

## 1. Project Overview
**Kev Music Player** is a highly optimized, production-grade music player application for Android built using **Kotlin** and **Jetpack Compose**. The app follows **Material Design 3 (M3)** guidelines to provide a vibrant, expressive, and premium user experience.

Designed to be highly responsive, it adapts its layout seamlessly across phone, foldable, and tablet form factors. It features a customizable display refresh rate pipeline (supporting a 60Hz/120Hz toggle) and a performance-optimized global zero-animations mode to guarantee lag-free operation.

---

## 2. Key Features
- **0ms Instant Startup & Local Caching**: Room SQLite Database cache prevents blank screens on startup. Scanning happens in the background, updating Room and local lists reactively.
- **Enhanced Playback Engine (Media3/ExoPlayer)**: Runs as a persistent background foreground service. Employs CPU WakeLock during screen-off states and a Glance-integrated widget binding model to ensure stable background playback. Includes a Battery Optimization monitoring dashboard in settings.
- **Lyrics Display, LRC Sync & Translation**: High-performance lyrics rendering with interactive seeking. Integrates LRCLIB for online multi-option lyrics searching, auto-fills instrumental tags `[[Instrumental]]`, and features dual-layered automatic translation (Google Translate / MyMemory API).
- **Comprehensive Metadata & Cover Editor**:
  - **Single Track Metadata**: Edit Title, Artist, Album, and Genre. Supports searching and downloading iTunes covers.
  - **Full Album Metadata & Cover**: Modify Album Title, Album Artist, and batch-apply a single album cover art to all songs in the album.
- **Home Screen Glance Widgets**: Reactive Glance app widgets with dynamic album cover retrieval and real-time state syncing via DataStore Preferences.
- **Advanced Audio Settings**: Integrated 10-preset equalizer, and circular rotary controls for *Bass Boost* and *Virtualizer*.
- **Exclusion of Folders**: interactive checkbox folder selection list to exclude specific folders (e.g., WhatsApp Media) from library database scan.
- **Tab Reordering & Customization**: Drag-and-drop category customizer in settings.
- **System-Wide Secure Deletion**: Storage Access Framework deletions targeting physical audio file paths.
- **Professional Backup & Restore**: Exports settings, playlists, covers, and lyrics to a single `kev_music_player_backup.json` using SAF document trees.

---

## 3. Technical Stack
- **Language**: Kotlin (100%)
- **UI Framework**: Jetpack Compose (Declarative UI)
- **Audio Engine**: AndroidX Media3 (ExoPlayer, MediaLibrarySession, FFmpeg extension)
- **Database**: Room SQLite (DAO, local entity cache)
- **Image Loading**: Coil (Efficient asynchronous album art retrieval)
- **Metadata Editing Library**: net.jthink:jaudiotagger
- **Lyrics Search & Translation**: LRCLIB API, Google Translate Web API, and MyMemory API
- **State Management**: MVVM Architecture with Kotlin Flow (StateFlow, SharedFlow)

---

## 4. Development & Testing Rules
- **Automatic Build & Test**: Every feature modification, bug fix, or session iteration MUST be compiled, packaged, and verified by running `./gradlew installDebug`. **Always use installDebug to install the app on the connected device for testing.** This ensures the code compiles correctly, detects syntax issues, and verifies deployment stability on physical devices or emulators. Always run this command and check its output.
- **Release Distribution**: Whenever a new GitHub Release is created, the compiled APK (e.g., `app/build/outputs/apk/release/app-release.apk`) MUST be attached as an asset to the release using the GitHub CLI (`gh release upload`).
- **Theme Integrity**: Colors must adapt dynamically. Do not use hardcoded black/white backgrounds or colors that break dark modes (especially Obsidian and Cyberpunk) or light modes (Monochrome clear theme).
- **Error Handling**: Maintain robust try-catch blocks around file I/O operations (such as jaudiotagger actions) to prevent application crashes on corrupted or write-protected files.
