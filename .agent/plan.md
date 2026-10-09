# Project Plan

A well-optimized music player app using Kotlin, Jetpack Compose, and Material Design 3. Features include a music player interface, settings, and necessary components for a full-featured player. It should request necessary permissions on startup (storage/media access and unrestricted background execution).

## Project Brief

# Kev Music Player - Project Brief

## Features
1. **Local Media Library**: Automatically scan and list audio files from device storage with proper media permissions.
2. **Advanced Playback Interface**: Comprehensive player controls including play/pause, skip, shuffle, repeat, and progress seeking with a Material 3 aesthetic.
3. **Adaptive UI Layouts**: A responsive interface that transitions seamlessly between phone, foldable, and tablet form factors using Material 3 Adaptive components.
4. **Optimized Background Execution**: Reliable music playback that continues in the background with a persistent notification and necessary power-saving exemptions.

## High-Level Technical Stack
- **Kotlin**: Core programming language.
- **Jetpack Compose**: For building the modern, declarative user interface.
- **Jetpack Navigation 3**: State-driven navigation for handling app flow.
- **Compose Material Adaptive**: Implementation of adaptive layouts across various device configurations.
- **Media3 (ExoPlayer)**: High-performance media playback engine for Android.
- **Kotlin Coroutines**: For managing asynchronous operations like media scanning and playback state updates.
- **Coil**: Efficient loading and caching of album artwork.

## Implementation Steps
**Total Duration:** 5h 20m 17s

### Task_1_Setup_Infrastructure_and_Permissions: Configure the project with necessary Media3 dependencies, implement a vibrant Material 3 theme (light/dark) with Edge-to-Edge support, and handle runtime permissions for media storage and notifications.
- **Status:** COMPLETED
- **Updates:** Configured Media3 dependencies (exoplayer, session, ui), implemented a vibrant Material 3 theme (Deep Orange/Blue A400), enabled full Edge-to-Edge display, created an adaptive icon, and implemented runtime permission handling for media and notifications. Navigation 3 structure also established.
- **Acceptance Criteria:**
  - Media3 dependencies added to build.gradle.kts and libs.versions.toml
  - Material 3 theme with energetic colors implemented
  - Edge-to-Edge display enabled
  - Permissions for reading media and posting notifications are requested on startup
- **Duration:** 1h 22m 7s

### Task_2_Media3_Background_Service: Implement a Media3 MediaLibraryService and MediaSession to manage audio playback. Set up ExoPlayer and configure the playback notification for background execution.
- **Status:** COMPLETED
- **Updates:** Implemented PlaybackService extending MediaLibraryService, initialized ExoPlayer with audio focus and noise handling, set up MediaSession, and configured background execution with persistent notifications. Updated Manifest with necessary permissions and foreground service type. Added MediaBrowserViewModel for UI-Service connection.
- **Acceptance Criteria:**
  - MediaLibraryService created and declared in Manifest
  - ExoPlayer initialized within the service
  - Music playback continues when app is in background
  - Persistent notification with playback controls is functional
- **Duration:** 1h 4m 48s

### Task_3_Adaptive_Library_and_Player_UI: Build the media library screen by scanning local audio files via MediaStore. Implement an adaptive UI using Material 3 Adaptive and Navigation 3 to show the library and a full-featured player interface.
- **Status:** COMPLETED
- **Updates:** Implemented AudioScanner using MediaStore for local media discovery. Integrated ListDetailSceneStrategy with Navigation 3 for adaptive layouts (phone vs tablet/foldable). Built a full-featured Player UI using Media3 Compose components, including controls, progress seeking, and state sync with PlaybackService.
- **Acceptance Criteria:**
  - Local audio files are scanned and displayed in a list
  - Adaptive layout transitions correctly between phone, foldable, and tablet
  - Player UI includes play/pause, skip, shuffle, repeat, and seek bar
  - Navigation 3 manages the flow between library and player
- **Duration:** 1h 43m 13s

### Task_4_Assets_and_Final_Verification: Create an adaptive app icon, refine the energetic UI styling, and perform a final verification of app stability and background playback. The critic_agent must verify stability, alignment with requirements, and report UI issues.
- **Status:** COMPLETED
- **Updates:** Refined the adaptive app icon with the vibrant primary color. Enhanced the Player UI with a vertical gradient background and high-quality album art placeholders. Verified app stability, background playback, and compliance with the project brief. Confirmed successful builds and architecture consistency.
- **Acceptance Criteria:**
  - Adaptive app icon matches the music player theme
  - App builds and runs without crashes
  - App does not crash
  - All existing tests pass
  - Verified alignment with project brief requirements
- **Duration:** 1h 10m 9s

