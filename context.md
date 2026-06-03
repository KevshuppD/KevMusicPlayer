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
  - **Temas de Colores**: Añadido soporte para 4 temas dinámicos: *Cyberpunk Púrpura* (estilo neón clásico), *Azul Petróleo* (minimalismo sofisticado a juego con el nuevo logo), *Obsidiana Oscuro* (negro puro AMOLED de alto contraste) y *Turquesa* (vibrante turquesa neón y verde menta).
  - **Reordenamiento de Pestañas**: Sistema de reordenamiento de pestañas por arrastre (Drag-and-Drop) en la configuración para priorizar categorías de la biblioteca.
- **Traducción Inteligente Dual de Letras**: Motor de traducción premium y resiliente de doble capa. El motor principal utiliza la API de cliente web de **Google Translate** (completamente libre, sin límites de cuota diaria ni coste). Si este falla o excede tiempos de respuesta, la app conmuta automáticamente al motor secundario **MyMemory API** configurado con canal de desarrollo de 10,000 palabras/día como respaldo. Mantiene el divisor por tuberías (`|`) para preservar la estructura de líneas y soporta recarga/traducción forzada manteniendo presionado el botón.
- **Gestor de Actualizaciones GitHub Releases**: Botón inteligente en ajustes que comprueba la API de GitHub en un hilo secundario y muestra un cuadro de diálogo estético para descargar nuevas versiones estables.
- **Búsqueda Avanzada y Control de Letras (Nuevas Funciones)**:
  - **Reinicio Automático de Posición**: Al cambiar de canción, el scroll de las letras sincronizadas se restablece a la posición de inicio (posición 0), solucionando el problema de permanencia al final.
  - **Indicador de Carga Circular de Letras**: Al seleccionar una canción o pasar a la siguiente, se activa una búsqueda automática de letras en segundo plano en LRCLIB y se muestra un hermoso spinner circular de carga en la pantalla de letras.
  - **Búsqueda Multiópciones (Evita Letras Defectuosas)**: Al buscar letras en línea, la app consulta LRCLIB y devuelve una lista completa de opciones con título, artista, álbum y estado de sincronización (sincronizada vs texto plano) para que el usuario seleccione la mejor opción disponible.
  - **Soporte para Pistas Instrumentales**: Si una canción no tiene letra, el usuario puede marcarla como "Instrumental", guardando `[[Instrumental]]` en la base de datos para mostrar una hermosa pantalla representativa con un botón para re-buscar si lo desea.
- **Organizador de Archivos en Ajustes**: Una función integrada en el panel de Mantenimiento de Biblioteca que renombra dinámicamente los archivos físicos de audio en el almacenamiento local al formato `<Número de pista>. <Artista> - <Título>.<ext>` (si existe el número de pista, de lo contrario `<Artista> - <Título>.<ext>`) utilizando metadatos sanitizados. Es compatible con Scoped Storage en Android 10+ mediante un renombrado dual (directo y a través del proveedor de MediaStore). Incluye control de progreso e indicador final.
- **Exclusión de Carpetas**: Panel interactivo desplegable en Ajustes que lista todas las carpetas con archivos de audio detectados en el dispositivo. Permite marcar/desmarcar carpetas específicas (como WhatsApp Media) mediante checkboxes para omitirlas de manera persistente en la biblioteca, limpiando de inmediato la base de datos local y la UI.
- **Modularización de Ajustes**: Para evitar scrolls excesivos y mejorar la organización, se implementó una navegación por pestañas en Ajustes (`SettingsScreen.kt`) mediante un selector horizontal ("pills") para las categorías *General*, *Sistema*, *Biblioteca* y *Acerca de*. Toda la lógica y visuales de cada grupo se refactorizaron en sub-composables independientes.
- **Sección "Acerca de" Completa**: Muestra el nuevo logo minimalista en azul petróleo, créditos de autor, link directo al perfil de GitHub del desarrollador y etiquetas técnicas de todas las librerías utilizadas.
- **Controles de Pantalla y Rendimiento**:
  - **Tasa de Refresco Ajustable**: Selector interactivo de 60Hz / 120Hz que fuerza dinámicamente los parámetros de la pantalla en tiempo de ejecución.
  - **Modo sin Animaciones**: Switch de rendimiento que desactiva por completo y a nivel global todas las transiciones y efectos de movimiento (incluyendo transiciones de página en la navegación por `NavDisplay` y cambios de pestaña) de forma estable y robusta.
- **Fast Scroll Spotify-Style**: Barra de desplazamiento rápido rediseñada con burbuja flotante dinámica que sigue el dedo e indicador de letra actual.
- **Smart Media Scanning & Folder View**: Supports multi-format audio scanning (MP3, FLAC, AAC, OGG, WAV, etc.) with automatic parent directory extraction to enable native "Browse by Folders" grid navigation.
- **Robust Background playback engine**: Powered by Media3, running seamlessly as a persistent foreground service. Enhanced with a partial **CPU WakeLock** to keep the playback engine awake during active screen lock periods, and migrated widget callbacks to a modern **MediaController client connection model** to bypass background service start restrictions. Includes a **Battery Optimization exemption monitoring dashboard** in settings to guide the user to grant "Unrestricted" status.
- **Adaptive Layouts**: Full Phone, Foldable, and Tablet compatibility using Navigation 3 and ListDetailSceneStrategy.
- **Persistent Playlists & Queue Management**: Full CRUD capability for lists, dynamic gradient covers, and a fully interactive playback queue. Persists the playback queue, current track position, and active "Shuffle" (random playback) state across application restarts.
- **Lyrics Display & LRC Synchronization**: Large scrolling lyrics pane with LRCLIB API integration, interactive time-seeking, manual lyric editing, and online lyrics search dialogs.
- **System-Wide "Manage All Files" Deletions**: Implemented robust full storage management settings intents to securely perform physical deletes of local tracks on Android 11+ (API 30+).
- **Library Multi-Select Mode**: Support for batch actions (Queue, Playlist, and Deletion) with highlighted borders, custom checkboxes, and a premium glassmorphic bottom bar.
- **Fixed Overwrite-Capable Backup System**: Updated backup features in settings to export settings, playlists, covers, and lyrics to a single fixed file `kev_music_player_backup.json` inside a user-defined directory using the Android Storage Access Framework (`ActivityResultContracts.OpenDocumentTree`) and persistent Uri permissions.
- **Playlist Enhancements**:
  - **Custom Covers**: Added capability to pick and set custom images as playlist cover arts using `ActivityResultContracts.GetContent`. The picked image is copied locally to `filesDir` for reliability, registered in the viewModel's `playlistCovers` map, and rendered beautifully in both the detail header and grid items with custom vertical gradient overlays.
  - **Shuffle Button**: Dedicated button in the playlist view header to instantly start a shuffled queue of the playlist songs.
  - **Dynamic Sorting**: Custom dropdown selector in the playlist view header to sort songs by Date added, Title (Name), or Artist.
  - **Search & Filter inside Dialog**: A search bar inside the "Add songs to playlist" dialog with real-time filtering for songs, albums, artists, and genres, making playlist creation incredibly smooth.
- **Deep Obsidian Theme Legibility**: Resolved accessibility bugs in the Deep Obsidian theme by applying dynamic theme colors (`onPrimary` instead of hardcoded white/black) to selected chips in the main screen.
- **Tag Editor (Metadata Tags)**: Integrated `jaudiotagger` library to write song tags (Title, Artist, Album, Genre) directly into physical local files (MP3, FLAC, M4A). The app automatically syncs changes to the Room database, memory lists, and active UI in real-time. It features an online search metadata tool querying the LRCLIB API to autofill tags dynamically.
- **Home Screen Glance Widgets**: Designed and implemented premium home screen widgets using `androidx.glance` library. The widget displays the actual album cover image extracted dynamically via `MediaMetadataRetriever`, along with the playing track title, artist, and state (play/pause), allowing controls (play/pause, next, previous) directly from the screen. State updates are instantly pushed reactively via Glance's native `PreferencesGlanceStateDefinition` and `updateAppWidgetState` on every single ExoPlayer event from the background service.

## 4. Key Features
- **Navegación Modular de Ajustes**: Categorización interactiva de la pantalla de configuración en sub-secciones (General, Sistema, Biblioteca, Acerca de) mediante un selector horizontal de pestañas para mejorar la usabilidad.
- **Traducción Automática de Letras**: Los usuarios pueden traducir instantáneamente cualquier letra al idioma de la aplicación mediante un botón dedicado en la pantalla del reproductor.
- **Actualizaciones desde la App**: Búsqueda en background de las últimas entregas estables en GitHub, notificando al usuario mediante dialogs nativos en caso de que exista una versión más reciente.
- **Panel de Control de Rendimiento**: Ajusta la experiencia visual forzando 60Hz/120Hz y desactivando animaciones con un solo toque para maximizar el rendimiento.
- **Reordenamiento Dinámico de Categorías**: Permite al usuario mover y priorizar sus pestañas favoritas simplemente arrastrando la fila en los ajustes.
- **Navegación Fluida con Quick-Scroll**: Barra lateral de desplazamiento con burbuja verde (estilo Spotify) que indica la letra actual para una navegación ultrarrápida en bibliotecas grandes.
- **Local SQLite Caching (Room)**: Eliminates the blank screen problem on startup by caching scanned tracks locally. Background scans sync silently and update the database without startup delays.
- **Browse by Folders**: Extracts directory structures from MediaStore to automatically organize the library into a grid of system folders displaying tracks grouped by parent directories.
- **Playlists System (CRUD)**: Persists user-defined lists using SharedPreferences (`playlists_prefs`). Includes a stunning `PlaylistGridView` with dynamic gradient cards, supporting custom local cover image overlays.
- **Dynamic Playback Queue**: Features controls for Play Next, Add to Queue, dynamic index highlights, direct track skipping, individual track removal, and a full queue clear option.
- **Sleek Shuffle Button**: A compact, premium `IconButton` positioned right next to the search query bar in the library header, as well as a dedicated shuffle button in the playlist detail view.
- **Backup & Restore System**: Fixed folder backup system that targets a user-chosen directory with persistent read/write storage access, saving a single self-contained JSON backup file that is automatically overwritten on subsequent exports.
- **Physical Song Deletion**: Enhanced deletion module that resolves the data path on storage to physically delete files from the device via `java.io.File.delete()`, alongside `ContentResolver` database purging.
- **Advanced Playback Interface**: Features an expressive deep gradient screen containing track info, seek progress, physical details (bitrate/format), synchronized lyrics, and a quick-access queue drawer.
- **Interactive Metadata Tag Editor**: Easily edit metadata tags for individual songs from context menus or long-press, featuring a premium dialog with text input fields and an integrated online metadata search helper.
- **Premium Glance Widgets**: A highly responsive home screen widget with custom dark theme integration, dynamic album cover extraction, and real-time native state syncing using DataStore Preferences.

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
- **Widget Toolkit**: AndroidX Glance (`androidx.glance:glance-appwidget` and `androidx.glance:glance-material3`)
- **Tag Editing Library**: net.jthink:jaudiotagger (Local audio metadata reading/writing)

## 6. Project Structure Highlights
- `com.kevshupp.kevmusicplayer.MainActivity.kt`: Main entry point. Dynamically applies 60Hz/120Hz refresh rates, intercepts setting modifications to update refresh modes, and handles edge-to-edge content.
- `com.kevshupp.kevmusicplayer.playback.PlaybackService.kt`: Extends `MediaLibraryService` to run a robust background player with persistent system media controllers.
- `com.kevshupp.kevmusicplayer.widget/`:
    - `MusicWidget.kt`: Implements the Glance AppWidget custom UI, reactive state binding and service controller intents.
    - `MusicWidgetReceiver.kt`: Handles Android widget lifecycle updates and system broadcasts.
- `com.kevshupp.kevmusicplayer.ui.screens/`:
    - `LibraryScreen.kt`: The main portal featuring reactive tab filters, Search query filters, sub-view detail navigators, FolderGridView, PlaylistGridView, and SongListView incorporating contextual menus, multi-selection state machines, category-based adding to playlists, and search-aligned shuffle buttons.
    - `SettingsScreen.kt`: Full-screen professional settings UI with dynamic theme selections, 60Hz/120Hz performance controls, zero-animation switches, a custom GitHub-connected update checker, and a legal credits panel.
    - `PlayerScreen.kt`: The playback screen featuring album art indicators, track info, progress seek sliders, physical/bitrate info, scrolling lyrics panel, quick-access Queue overlay, and playback mode controls.
- `com.kevshupp.kevmusicplayer.ui.theme/`:
    - `Theme.kt`: Implements the color palette selectors, listening to preferences in real time to swap between Cyberpunk, Petrol Blue, and Deep Obsidian dark schemes instantly.
    - `Color.kt`: Houses the premium visual color palettes.
