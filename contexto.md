# KevMusicPlayer - Contexto General del Proyecto

Este documento sirve como la **Fuente Única de Verdad (Single Source of Truth)** para el proyecto Android **KevMusicPlayer**, un reproductor de música nativo de alto rendimiento con interfaz moderna en Jetpack Compose, sincronización de biblioteca y respaldo en la nube vía Google Firebase, personalización visual avanzada y soporte de efectos de sonido DSP a 120 FPS.

---

## 1. Propósito y Visión General
**KevMusicPlayer** es un reproductor de audio moderno, ligero y ultrarrápido desarrollado en **Kotlin y Jetpack Compose** para Android 13+ (API 33 a 37+). Está diseñado para ofrecer una experiencia estética, fluida y altamente configurable sin depender de servicios de streaming externos.

### Pilares Funcionales:
1. **Biblioteca Local Inteligente:** Escaneo nativo con `MediaStore` y extracción de metadatos/etiquetas (ID3v1, ID3v2, FLAC, Vorbis, MP4) vía `jaudiotagger` y `mp3agic`.
2. **Sistema de Carga de Carátulas Multi-Nivel:** Arquitectura en 5 niveles de respaldo (RAM LruCache, Disco WebP, `loadThumbnail` acelerado por hardware, `MediaMetadataRetriever` protegido e imágenes de carpeta) con cero bloqueos y protección contra envenenamiento de caché.
3. **Sincronización y Respaldo en la Nube (Google Cloud / Firebase):** Inicio de sesión con Google (`GoogleSignInClient` + `FirebaseAuth`) y almacenamiento comprimido GZIP en Firestore de listas de reproducción, letras de canciones, estadísticas y configuraciones del usuario.
4. **Reproducción y Audio DSP:** Basado en `Jetpack Media3 / ExoPlayer` (`MediaSessionService`) con soporte para reproducción en segundo plano, controles de pantalla de bloqueo, integración de notificación multimedia nativa, ecualizador gráfico de 5 bandas, refuerzo de graves (*Bass Boost*), sonido envolvente virtualizado (*Virtualizer*), normalización de volumen (*ReplayGain*) y transición continua (*Crossfade*).
5. **Letras Sincronizadas y Traducidas:** Visualización y edición en tiempo real de letras sincronizadas (.lrc) o estáticas, con integración de búsqueda automática en línea y persistencia local/nube.
6. **Mantenimiento y Diagnóstico:** Herramientas integradas para detectar canciones duplicadas por hash/metadatos, verificar integridad de archivos corruptos, filtrar audios cortos (audios de WhatsApp/tonos) y descargar carátulas faltantes desde Deezer/iTunes.
7. **Personalización Estética:** Temas visuales (*Cyberpunk Rosa, Cyberpunk Púrpura, Azul Petróleo, Turquesa, Obsidiana AMOLED y Monocromo*), soporte de transparencia Glassmorphism en barra de navegación y tarjetas, fondo ambiental dinámico basado en los colores de la carátula, visualizador animado y selector de tasa de refresco a **120 Hz**.

---

## 2. Stack Tecnológico y Arquitectura

- **Lenguaje:** 100% Kotlin.
- **UI Framework:** Jetpack Compose con Material 3 (`androidx.compose.material3`), Compose Animation, Compose Foundation, Compose Runtime.
- **Reproducción Multimedia:** `androidx.media3` (`media3-exoplayer`, `media3-session`, `media3-ui`, `media3-common`).
- **Base de Datos Local:** Android Room (`androidx.room:room-runtime`, `androidx.room:room-ktx`, KSP).
- **Backend y Nube:**
  - **Firebase Auth:** Autenticación con Google (`GoogleAuthProvider` + `GoogleSignInOptions`).
  - **Cloud Firestore:** Almacenamiento NoSQL de respaldos en la nube bajo `users/<userId>/backups/latest`.
  - **Firebase Storage / Google Services:** Integrados vía `google-services.json`.
- **Carga y Caché de Imágenes:** Coil Compose (`io.coil-kt:coil-compose`) + `LruCache` en memoria y almacenamiento WebP en disco.
- **Manipulación de Etiquetas:** `jaudiotagger` (`org.jaudiotagger:jaudiotagger:3.0.1`) y `mp3agic` (`com.mpatric:mp3agic:0.9.2`).
- **Consumo de APIs REST:** `OkHttp 4.12.0` y `kotlinx.serialization` (Deezer API, iTunes Search API, LrcLib API).

---

## 3. Estructura del Proyecto y Archivos Clave

El código fuente se encuentra estructurado modularmente en `app/src/main/java/com/kevshupp/kevmusicplayer/`:

```
com/kevshupp/kevmusicplayer/
├── MainActivity.kt                      # Contenedor principal, flujo de Onboarding y navegación Navigation3
├── KevMusicPlayerApplication.kt         # Inicialización de Firebase, TelemetryLogger y ciclo de vida global
│
├── data/                               # Capa de Datos y Modelos
│   ├── AudioFile.kt                     # Entidad Room de canción local
│   ├── AudioDao.kt                      # DAO de Room para operaciones CRUD de biblioteca
│   ├── AppDatabase.kt                   # Base de datos SQLite Room
│   ├── AudioScanner.kt                  # Escaneo inteligente de MediaStore
│   ├── CoverArtRepository.kt            # Búsqueda y descarga de carátulas (Deezer e iTunes)
│   ├── LyricsRepository.kt              # Repositorio de letras sincronizadas y traducciones
│   ├── TelemetryLogger.kt               # Registro seguro de errores y telemetría local
│   └── cloud/                           # Módulo de Autenticación y Nube
│       ├── CloudUser.kt                 # Modelo de usuario de Google/Firebase
│       ├── CloudAuthManager.kt          # Gestión de autenticación con Google Play Services
│       └── CloudBackupManager.kt        # Compresión GZIP y subida/restauración en Firestore
│
├── playback/                            # Capa de Reproducción y Servicio de Audio
│   ├── PlaybackService.kt               # MediaLibraryService en segundo plano (Media3)
│   ├── MediaBrowserViewModel.kt         # ViewModel central del reproductor y biblioteca
│   ├── AudioIOHelpers.kt                # Helpers de manipulación de archivos y ContentResolver
│   └── managers/                        # Gestores especializados
│       ├── AudioEffectsManager.kt       # Ecualizador, BassBoost, Virtualizer y Loudness
│       ├── CrossfadeManager.kt          # Fundido suave y transiciones de volumen
│       ├── SleepTimerManager.kt         # Temporizador de apagado automático
│       ├── SmartPlaylistEngine.kt       # Motor de listas dinámicas basadas en reglas
│       ├── StorageToolsManager.kt       # Diagnóstico de duplicados, audios cortos e integridad
│       └── TagEditorManager.kt          # Edición física de etiquetas ID3 y metadatos
│
└── ui/                                  # Capa de Presentación (Jetpack Compose)
    ├── screens/
    │   ├── HomeScreen.kt                # Pantalla principal con saludo dinámico, avatar y favoritos
    │   ├── LibraryScreen.kt             # Explorador de biblioteca (Pistas, Artistas, Álbumes, Carpetas)
    │   ├── PlayerScreen.kt              # Reproductor expandido, carrusel de vinilo, fondo y letras
    │   ├── SettingsScreen.kt            # Panel de ajustes centralizado (Hub + Subpáginas nativas)
    │   ├── MusicInsightsScreen.kt       # Estadísticas de reproducción y hábitos musicales
    │   ├── UniversalSearchOverlay.kt    # Buscador universal de canciones, artistas y carpetas
    │   ├── BottomNavBar.kt              # Barra de navegación flotante y Mini-Reproductor Glassmorphism
    │   ├── LibraryComponents.kt         # Componentes reutilizables, rememberAlbumArt y LruCache
    │   ├── dialogs/                     # Diálogos modales
    │   │   ├── UserProfileDialog.kt     # Perfil de usuario, cambio de foto y estado de la nube
    │   │   ├── TagEditorDialog.kt       # Editor avanzado de etiquetas de canciones
    │   │   ├── AlbumEditorDialog.kt     # Editor de álbumes y carátulas
    │   │   ├── DuplicateFinderDialog.kt # Detección y limpieza de canciones duplicadas
    │   │   ├── SongIntegrityDialog.kt   # Verificación de archivos corruptos
    │   │   └── ShortSongsDialog.kt      # Filtrado de audios de corta duración
    │   └── settings/                    # Secciones modulares de configuración
    │       ├── GeneralSettingsSection.kt
    │       ├── LibrarySettingsSection.kt
    │       ├── AudioSettingsSection.kt
    │       ├── PerformanceSettingsSection.kt
    │       ├── SystemSettingsSection.kt
    │       ├── AboutSettingsSection.kt
    │       ├── CloudSyncCard.kt
    │       └── SettingsCommon.kt
    └── theme/
        ├── Color.kt                     # Paletas de color (Cyberpunk, Petrol, Turquoise, etc.)
        ├── Theme.kt                     # Configuración de MaterialTheme y Glassmorphism
        └── Type.kt                      # Tipografías del sistema
```

---

## 4. Decisiones de Arquitectura y Reglas Críticas

### A. Autenticación con Google y Soporte Multi-Variante
- **Mecanismo:** Se utiliza `GoogleSignInOptions` + `GoogleSignIn.getClient` vinculado con `FirebaseAuth.signInWithCredential`.
- **Razón:** `CredentialManager` falla en compilaciones sideloaded y Debug arrojando `No credentials available`. `GoogleSignInClient` es 100% estable y retrocompatible.
- **Variantes de compilación:**
  - **Release:** `com.kevshupp.kevmusicplayer` (distribución final).
  - **Debug:** `com.kevshupp.kevmusicplayer.debug` (icono con tuerca ⚙️ para permitir co-instalación simultánea en el mismo teléfono).
- **Huella digital SHA-1 común (`shared.keystore`):**
  ```text
  89:65:60:13:60:21:43:9E:12:A0:49:6E:73:F1:04:24:EE:2B:CC:AC
  ```
- **Web Client ID de OAuth:**
  ```text
  250307790225-2bf1g55v6md8j8du61le2h1q4nj84srh.apps.googleusercontent.com
  ```
- **FileProvider:** El `authorities` en el `AndroidManifest.xml` debe usar siempre `${applicationId}.fileprovider` para evitar conflictos de instalación `INSTALL_FAILED_CONFLICTING_PROVIDER`.

### B. Sistema de Carga y Caché de Carátulas (Album Art)
- **Cero archivos vacíos (0 bytes):** Bajo ninguna circunstancia se debe crear un archivo de 0 bytes en caché ante un error temporal.
- **Auto-recuperación:** Si la caché encuentra un archivo de 0 bytes residual, lo borra en el acto (`diskFile.delete()`) y reintenta la lectura real.
- **Jerarquía de Carga:**
  1. `LruCache` en memoria RAM.
  2. Archivos WebP en disco (`cacheDir/album_art_thumbnails/`).
  3. `contentResolver.loadThumbnail()` (Nativo en Android 10+).
  4. Extracción de `MediaMetadataRetriever` sobre ruta física / descriptores.
  5. Imágenes de carpeta (`cover.jpg`, `folder.jpg`, etc.) y análisis ID3 de Jaudiotagger.

### C. Procesamiento DSP y Efectos de Audio
- Se mantienen y suprimen advertencias (`@file:Suppress("DEPRECATION")`) en las clases `android.media.audiofx.Equalizer`, `BassBoost` y `Virtualizer`, ya que son las únicas APIs de Android que permiten control manual continuo (0-1000) en el procesador de señal digital de cualquier salida de audio.

### D. Flujo de Configuración (Settings Hub)
- El menú de ajustes se organiza como un **Hub principal** con búsqueda en tiempo real, tarjeta de sincronización en la nube y accesos directos a **subpáginas nativas**, sin barras de pestañas horizontales saturadas ni botones flotantes innecesarios.

---

## 5. Automatización de Compilación y Publicación (CI/CD)

### Script de Publicación Local
Ubicado en `/home/kevin/Escritorio/publicar_release.sh` (con alias `release` en `~/.bashrc`):
1. Solicita el mensaje del cambio y el tipo de versión (*patch*, *minor* o *major*).
2. Incrementa automáticamente el `versionCode` (+1) y `versionName` en `app/build.gradle.kts`.
3. Realiza el commit, genera el tag de Git y ejecuta `git push origin main --tags`.

### GitHub Actions Workflow (`.github/workflows/release.yml`)
- Se dispara automáticamente al recibir un nuevo tag `v*`.
- Decodifica el secreto `GOOGLE_SERVICES_JSON_BASE64` para generar `app/google-services.json` de forma segura.
- Compila con Gradle (`./gradlew assembleRelease`).
- Publica el APK firmado y optimizado en **GitHub Releases** adjuntando el changelog automático.
