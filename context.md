# Project Context: Kev Music Player

## 1. Project Name
**Kev Music Player**

## 2. Project Description
A highly optimized, production-grade music player application for Android built with **Kotlin** and **Jetpack Compose**. The app follows **Material Design 3 (M3)** guidelines to provide a vibrant, expressive, and premium user experience. It is designed to be highly responsive, adapting its layout seamlessly across phone, foldable, and tablet form factors. It features a customizable display refresh rate pipeline (supporting 60Hz/120Hz toggle) and a performance-optimized global zero-animations mode to guarantee lag-free operation.

## 3. Current Status
**Completed & Production Ready**. All core and advanced features described in the project briefs have been fully implemented, optimized, and verified:
- **0ms Instant Startup Loading**: Integrated a fast SQLite database cache using Room to load and show the music library instantly upon opening the application.
- **Soporte Multi-idioma (I18n)**: Implementado soporte completo para Español e Inglés con cambio dinámico de lenguaje en tiempo real sin necesidad de reiniciar la aplicación. Se han localizado al 100% las pantallas de configuración (incluyendo ordenación de pistas y mantenimiento) y los diálogos de confirmación de borrado.
- **Personalización de Interfaz y Temas Premium**:
  - **Temas de Colores**: Añadido soporte para 3 temas dinámicos: *Cyberpunk Púrpura* (estilo neón clásico), *Azul Petróleo* (minimalismo sofisticado a juego con el nuevo logo) y *Obsidiana Oscuro* (negro puro AMOLED de alto contraste).
  - **Reordenamiento de Pestañas**: Sistema de reordenamiento de pestañas por arrastre (Drag-and-Drop) en la configuración para priorizar categorías de la biblioteca.
- **Traducción Inteligente de Letras**: Integración con MyMemory API para traducir letras en tiempo real al idioma del usuario. Optimizado con un canal de desarrollo (`&de`) para elevar la cuota diaria gratuita a 10,000 palabras, un divisor por tuberías (`|`) para garantizar que la estructura de líneas se preserve sin errores y un sistema de corte ante cuotas excedidas (circuit-breaker). Soporta recarga forzada manteniendo presionado el botón.
- **Gestor de Actualizaciones GitHub Releases**: Botón inteligente en ajustes que comprueba la API de GitHub en un hilo secundario y muestra un cuadro de diálogo estético para descargar nuevas versiones estables.
- **Búsqueda Avanzada y Control de Letras (Nuevas Funciones)**:
  - **Reinicio Automático de Posición**: Al cambiar de canción, el scroll de las letras sincronizadas se restablece a la posición de inicio (posición 0), solucionando el problema de permanencia al final.
  - **Indicador de Carga Circular de Letras**: Al seleccionar una canción o pasar a la siguiente, se activa una búsqueda automática de letras en segundo plano en LRCLIB y se muestra un hermoso spinner circular de carga en la pantalla de letras.
  - **Búsqueda Multiópciones (Evita Letras Defectuosas)**: Al buscar letras en línea, la app consulta LRCLIB y devuelve una lista completa de opciones con título, artista, álbum y estado de sincronización (sincronizada vs texto plano) para que el usuario seleccione la mejor opción disponible.
  - **Soporte para Pistas Instrumentales**: Si una canción no tiene letra, el usuario puede marcarla como "Instrumental", guardando `[[Instrumental]]` en la base de datos para mostrar una hermosa pantalla representativa con un botón para re-buscar si lo desea.
- **Sección "Acerca de" Completa**: Muestra el nuevo logo minimalista en azul petróleo, créditos de autor, link directo al perfil de GitHub del desarrollador y etiquetas técnicas de todas las librerías utilizadas.
- **Controles de Pantalla y Rendimiento**:
  - **Tasa de Refresco Ajustable**: Selector interactivo de 60Hz / 120Hz que fuerza dinámicamente los parámetros de la pantalla en tiempo de ejecución.
  - **Modo sin Animaciones**: Switch de rendimiento que desactiva por completo y a nivel global todas las transiciones y efectos de movimiento de forma estable y robusta.
- **Fast Scroll Spotify-Style**: Barra de desplazamiento rápido rediseñada con burbuja flotante dinámica que sigue el dedo e indicador de letra actual.
- **Smart Media Scanning & Folder View**: Supports multi-format audio scanning (MP3, FLAC, AAC, OGG, WAV, etc.) with automatic parent directory extraction to enable native "Browse by Folders" grid navigation.
- **Robust Background playback engine**: Powered by Media3, running seamlessly as a persistent foreground service. Enhanced with a **Battery Optimization exemption monitoring dashboard** in settings that guides the user to grant "Unrestricted" background usage to bypass aggressive OEM process-killing.
- **Adaptive Layouts**: Full Phone, Foldable, and Tablet compatibility using Navigation 3 and ListDetailSceneStrategy.
- **Persistent Playlists & Queue Management**: Full CRUD capability for lists, dynamic gradient covers, and a fully interactive playback queue.
- **Lyrics Display & LRC Synchronization**: Large scrolling lyrics pane with LRCLIB API integration, interactive time-seeking, manual lyric editing, and online lyrics search dialogs.
- **System-Wide "Manage All Files" Deletions**: Implemented robust full storage management settings intents to securely perform physical deletes of local tracks on Android 11+ (API 30+).
- **Library Multi-Select Mode**: Support for batch actions (Queue, Playlist, and Deletion) with highlighted borders, custom checkboxes, and a premium glassmorphic bottom bar.

## 4. Key Features
- **Traducción Automática de Letras**: Los usuarios pueden traducir instantáneamente cualquier letra al idioma de la aplicación mediante un botón dedicado en la pantalla del reproductor.
- **Actualizaciones desde la App**: Búsqueda en background de las últimas entregas estables en GitHub, notificando al usuario mediante dialogs nativos en caso de que exista una versión más reciente.
- **Panel de Control de Rendimiento**: Ajusta la experiencia visual forzando 60Hz/120Hz y desactivando animaciones con un solo toque para maximizar el rendimiento.
- **Reordenamiento Dinámico de Categorías**: Permite al usuario mover y priorizar sus pestañas favoritas simplemente arrastrando la fila en los ajustes.
- **Navegación Fluida con Quick-Scroll**: Barra lateral de desplazamiento con burbuja verde (estilo Spotify) que indica la letra actual para una navegación ultrarrápida en bibliotecas grandes.
- **Local SQLite Caching (Room)**: Eliminates the blank screen problem on startup by caching scanned tracks locally. Background scans sync silently and update the database without startup delays.
- **Browse by Folders**: Extracts directory structures from MediaStore to automatically organize the library into a grid of system folders displaying tracks grouped by parent directories.
- **Playlists System (CRUD)**: Persists user-defined lists using SharedPreferences (`playlists_prefs`). Includes a stunning `PlaylistGridView` with dynamic gradient cards.
- **Dynamic Playback Queue**: Features controls for Play Next, Add to Queue, dynamic index highlights, direct track skipping, individual track removal, and a full queue clear option.
- **Sleek Shuffle Button**: A compact, premium `IconButton` positioned right next to the search query bar in the library header.
- **Physical Song Deletion**: Enhanced deletion module that resolves the data path on storage to physically delete files from the device via `java.io.File.delete()`, alongside `ContentResolver` database purging.
- **Advanced Playback Interface**: Features an expressive deep gradient screen containing track info, seek progress, physical details (bitrate/format), synchronized lyrics, and a quick-access queue drawer.

## 5. Technical Stack
- **API de Traducción**: MyMemory API (via HTTP/JSON)
- **Motor de Actualizaciones**: GitHub Releases API (via OkHttp & JSON parsing on Coroutines)
- **Localización**: Sistema nativo de XML con `res/values-es/` y gestión dinámica de `LocaleManager`.
- **Language**: Kotlin (100%)
- **UI Framework**: Jetpack Compose (Declarative UI)
- **Design System**: Material Design 3 (Dynamic Color, custom theme palettes, deep mesh gradients)
- **Audio Engine**: AndroidX Media3 (ExoPlayer, MediaLibrarySession, FFmpeg)
- **Database**: Room SQLite Database (DAO, entity synchronization, local caching)
- **Architecture**: MVVM with StateFlow, SharedFlow, and Compose SnapshotStateList
- **Image Loading**: Coil (Efficient asynchronous Album Art loading)
- **Async Concurrency**: Kotlin Coroutines & Flow (Safe off-main-thread I/O)
- **Build System**: Gradle Kotlin DSL with KSP (Kotlin Symbol Processing)

## 6. Project Structure Highlights
- `com.kevshupp.kevmusicplayer.MainActivity.kt`: Main entry point. Dynamically applies 60Hz/120Hz refresh rates, intercepts setting modifications to update refresh modes, and handles edge-to-edge content.
- `com.kevshupp.kevmusicplayer.playback.PlaybackService.kt`: Extends `MediaLibraryService` to run a robust background player with persistent system media controllers.
- `com.kevshupp.kevmusicplayer.ui.screens/`:
    - `LibraryScreen.kt`: The main portal featuring reactive tab filters, Search query filters, sub-view detail navigators, FolderGridView, PlaylistGridView, and SongListView incorporating contextual menus, multi-selection state machines, category-based adding to playlists, and search-aligned shuffle buttons.
    - `SettingsScreen.kt`: Full-screen professional settings UI with dynamic theme selections, 60Hz/120Hz performance controls, zero-animation switches, a custom GitHub-connected update checker, and a legal credits panel.
    - `PlayerScreen.kt`: The playback screen featuring album art indicators, track info, progress seek sliders, physical/bitrate info, scrolling lyrics panel, quick-access Queue overlay, and playback mode controls.
- `com.kevshupp.kevmusicplayer.ui.theme/`:
    - `Theme.kt`: Implements the color palette selectors, listening to preferences in real time to swap between Cyberpunk, Petrol Blue, and Deep Obsidian dark schemes instantly.
    - `Color.kt`: Houses the premium visual color palettes.
