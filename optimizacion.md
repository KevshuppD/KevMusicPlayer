# ⚡ Plan de Optimización de Rendimiento — KevMusicPlayer

> Generado el 2026-09-17. Análisis combinado de revisión manual + análisis profundo automatizado.
> Contiene los problemas detectados con código original vs. nuevo exacto.
> Sirve como referencia para revertir si algo falla.

### 📊 Estado General:
- ✅ **16 Aplicados y Verificados en Build**
- ⏳ **8 Pendientes**
- ⏳ **1 A futuro**

---

## 🔴 Alto Impacto

---

### Fix 1 [✅ APLICADO] — `getGradientForString`: Pre-crear Brushes como lista fija
**Archivo:** `ui/screens/LibraryComponents.kt` — Líneas 81–85

`Brush.linearGradient()` instancia un objeto nuevo en cada recomposición de `SongListItem`.
Con miles de canciones en scroll rápido se crean miles de objetos `Brush` por segundo.

#### Código ORIGINAL
```kotlin
fun getGradientForString(name: String): Brush {
    val index = java.lang.Math.abs(name.hashCode()) % GradientPairs.size
    val colors = GradientPairs[index]
    return Brush.linearGradient(colors)
}
```
#### Código NUEVO
```kotlin
// Pre-created brushes — only 7 objects ever created for the lifetime of the app
private val GradientBrushes: List<Brush> = GradientPairs.map { Brush.linearGradient(it) }

fun getGradientForString(name: String): Brush {
    val index = Math.abs(name.hashCode()) % GradientBrushes.size
    return GradientBrushes[index]
}
```

---

### Fix 2 [✅ APLICADO] — `rememberAlbumArt`: Eliminar doble consulta al cache
**Archivo:** `ui/screens/LibraryComponents.kt` — Líneas 1165–1190

El `remember(uriString, version)` en línea 1171 ya llama `albumArtCache.get(uriString)`.
El `LaunchedEffect` vuelve a llamarlo en línea 1177 innecesariamente.
Con 50 ítems visibles = **100 consultas por recomposición** en vez de 50.

#### Código ORIGINAL
```kotlin
@Composable
fun rememberAlbumArt(uriString: String?): android.graphics.Bitmap? {
    if (uriString == null) return null
    val context = LocalContext.current
    val version = albumArtVersion

    val initialBitmap = remember(uriString, version) {
        albumArtCache.get(uriString)
    }
    var bitmap by remember(uriString, version) { mutableStateOf(initialBitmap) }

    LaunchedEffect(uriString, version) {
        val cached = albumArtCache.get(uriString)
        if (cached != null) {
            bitmap = cached
        } else {
            val loadedBmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loadAlbumArtBitmap(context, uriString)
            }
            if (loadedBmp != null) {
                bitmap = loadedBmp
            }
        }
    }
    return bitmap
}
```
#### Código NUEVO
```kotlin
@Composable
fun rememberAlbumArt(uriString: String?): android.graphics.Bitmap? {
    if (uriString == null) return null
    val context = LocalContext.current
    val version = albumArtVersion

    // Single cache read on composition — no duplicate lookup in LaunchedEffect
    var bitmap by remember(uriString, version) {
        mutableStateOf(albumArtCache.get(uriString))
    }

    LaunchedEffect(uriString, version) {
        // Only hit disk/MediaStore if cache miss — avoids double lookup
        if (bitmap == null) {
            val loadedBmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loadAlbumArtBitmap(context, uriString)
            }
            if (loadedBmp != null) {
                bitmap = loadedBmp
            }
        }
    }
    return bitmap
}
```

---

### Fix 3 [✅ APLICADO] — `rememberDominantColor`: Escalar Bitmap a 50px antes de `Palette.generate()`
**Archivo:** `ui/screens/PlayerScreen.kt` — Líneas 2237–2251

`Palette.from(bitmap).generate()` analiza el Bitmap HD completo (500×500 = 250.000 px).
Escalarlo a 50×50 produce el mismo color dominante pero es **~100x más rápido**.

#### Código ORIGINAL
```kotlin
LaunchedEffect(bitmap) {
    if (bitmap != null) {
        withContext(Dispatchers.IO) {
            try {
                val palette = Palette.from(bitmap).generate()
                val color = palette.getVibrantColor(
                    palette.getDominantColor(defaultColor.toArgb())
                )
                dominantColor = Color(color)
                // Note: We MUST NOT call bitmap.recycle() here, because the bitmap is cached
                // globally in albumArtCache and will be drawn/reused by rememberAlbumArt.
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    } else {
        dominantColor = defaultColor
    }
}
```
#### Código NUEVO
```kotlin
LaunchedEffect(bitmap) {
    if (bitmap != null) {
        withContext(Dispatchers.IO) {
            try {
                // Scale down to 50x50 for Palette analysis — ~100x faster, identical color result.
                // We MUST NOT recycle the original bitmap (it lives in albumArtCache).
                val smallBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, 50, 50, false)
                val palette = Palette.from(smallBitmap).generate()
                smallBitmap.recycle()
                val color = palette.getVibrantColor(
                    palette.getDominantColor(defaultColor.toArgb())
                )
                dominantColor = Color(color)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    } else {
        dominantColor = defaultColor
    }
}
```

---

### Fix 4 [✅ APLICADO] — `savePlaybackState()` itera hasta 1500 items en el Main thread
**Archivo:** `playback/MediaBrowserViewModel.kt` — Líneas ~266–270

`b.getMediaItemAt(i)` se llama en Main thread hasta 1500 veces. Se dispara en **4 eventos** distintos
del Player (transición, playbackState, positionDiscontinuity, shuffleMode). Con 1500 canciones =
**~6000 accesos a MediaBrowser en el Main thread por cambio de canción**.

#### Código ORIGINAL
```kotlin
val mediaIds = ArrayList<String>(b.mediaItemCount)
for (i in 0 until b.mediaItemCount) {
    val item = b.getMediaItemAt(i)
    mediaIds.add(item.mediaId)
}
```
#### Código NUEVO
```kotlin
// Snapshot de la cola desde Main y procesamiento en IO
val mediaIds = withContext(Dispatchers.Main) {
    ArrayList<String>(b.mediaItemCount).also { list ->
        for (i in 0 until b.mediaItemCount) list.add(b.getMediaItemAt(i).mediaId)
    }
}
// ... resto del guardado en IO
```

---

### Fix 5 [✅ APLICADO] — `savePlaybackState()` llamado 4 veces por cada skip/shuffle — añadir debounce
**Archivo:** `playback/MediaBrowserViewModel.kt` — Líneas ~197, 208, 215, 220

`onMediaItemTransition`, `onPlaybackStateChanged`, `onPositionDiscontinuity`, `onShuffleModeEnabledChanged`
todos llaman `savePlaybackState()`. Un simple `seekToNext()` puede disparar los 4 en cadena =
4 × (iteración de 1500 items + write a SharedPreferences).

#### Corrección: añadir debounce de 300ms
```kotlin
private var saveStateJob: Job? = null

fun savePlaybackState() {
    saveStateJob?.cancel()
    saveStateJob = viewModelScope.launch {
        delay(300) // debounce — coalesce multiple rapid calls into one write
        // ... resto del código de guardado existente
    }
}
```

---

### Fix 6 [⏳ PENDIENTE] — `localAudioFiles.clear() + addAll()` dispara recomposición masiva de toda la UI
**Archivo:** `playback/MediaBrowserViewModel.kt` — Líneas ~682–684

`localAudioFiles` es `mutableStateListOf<AudioFile>()`. `clear()` dispara una recomposición,
`addAll()` dispara otra. Con 5000 canciones = **2 recomposiciones masivas** de Library + Home + Player.

#### Código ORIGINAL
```kotlin
localAudioFiles.clear()
localAudioFiles.addAll(updatedFilesList)
```
#### Corrección: actualizar solo los items que cambiaron
```kotlin
if (updatedFilesList != localAudioFiles) {
    val newIds = updatedFilesList.associateBy { it.id }
    // Remover los que ya no existen
    localAudioFiles.removeAll { it.id !in newIds }
    // Actualizar los modificados y añadir los nuevos
    updatedFilesList.forEachIndexed { index, newFile ->
        val existingIndex = localAudioFiles.indexOfFirst { it.id == newFile.id }
        if (existingIndex == -1) {
            localAudioFiles.add(newFile)
        } else if (localAudioFiles[existingIndex] != newFile) {
            localAudioFiles[existingIndex] = newFile
        }
    }
}
```

---

### Fix 7 [✅ APLICADO] — `localAudioFiles.find {}` O(n) en composición activa — usar Map indexado
**Archivos:** `ui/screens/PlayerScreen.kt` líneas ~203–207, `playback/MediaBrowserViewModel.kt` línea ~466

`localAudioFiles.find { it.id.toString() == mediaId }` con 5000 canciones = hasta 5000 comparaciones
+conversión `.id.toString()` en cada cambio de canción. Se llama desde PlayerScreen y ViewModel.

#### Código ORIGINAL
```kotlin
// En PlayerScreen.kt:
val currentSongFile = remember(playerState.currentSong?.mediaId) {
    derivedStateOf {
        viewModel?.localAudioFiles?.find { it.id.toString() == playerState.currentSong?.mediaId }
    }
}.value
```
#### Código NUEVO
```kotlin
// En PlayerScreen.kt — usar el índice del ViewModel:
val currentSongFile = remember(playerState.currentSong?.mediaId, viewModel?.audioFileIndex) {
    viewModel?.audioFileIndex?.get(playerState.currentSong?.mediaId)
}

// En MediaBrowserViewModel.kt — mantener un índice actualizado:
val audioFileIndex: Map<String, AudioFile>
    get() = localAudioFiles.associateBy { it.id.toString() }
// Nota: para mayor eficiencia, hacer el Map lazy con derivedStateOf
```

---

### Fix 8 [✅ APLICADO] — `Regex("\\s+")` instanciada dentro de `derivedStateOf` en búsqueda
**Archivo:** `ui/screens/LibraryScreen.kt` — Línea 262

La expresión regular se compila como objeto nuevo en cada evaluación del `derivedStateOf`.
Con búsqueda rápida = compilación de Regex por cada tecla presionada.

#### Código ORIGINAL
```kotlin
val terms = queryClean.split(Regex("\\s+"))
```
#### Código NUEVO
```kotlin
// Top-level o companion — compilada una sola vez
private val WHITESPACE_REGEX = Regex("\\s+")

// Dentro del derivedStateOf:
val terms = queryClean.split(WHITESPACE_REGEX)
```

---

### Fix 9 [✅ APLICADO] — `stripAccents()` × 4 campos por canción en búsqueda — precalcular índice
**Archivo:** `ui/screens/LibraryScreen.kt` — Líneas 263–268

`stripAccents()` usa `Normalizer.normalize()` + `Regex.replace()` — costoso.
Con 5000 canciones = **20.000 operaciones de normalización Unicode** por cada tecla presionada.

#### Corrección: precalcular índice de búsqueda en el ViewModel (una sola vez al cargar)
```kotlin
// En MediaBrowserViewModel.kt — recalcular cuando cambia localAudioFiles
val searchIndex: Map<Long, String> by lazy {
    // O mejor, recalcular con derivedStateOf:
    localAudioFiles.associate { song ->
        song.id to "${song.title.stripAccents()} ${song.artist.stripAccents()} " +
                  "${song.album.stripAccents()} ${song.genre.stripAccents()}"
    }
}

// En LibraryScreen.kt — usar el índice precalculado:
audioFiles.filter { song ->
    val text = viewModel?.searchIndex?.get(song.id) ?: ""
    terms.all { term -> text.contains(term, ignoreCase = true) }
}
```

---

### Fix 10 [✅ APLICADO] — `getSharedPreferences().getInt()` en cada llamada a `loadAlbumArtBitmap`
**Archivo:** `ui/screens/LibraryComponents.kt` — Líneas ~1034–1036

Cada carga de portada hace una lectura sincrónica de SharedPreferences para obtener `art_resolution`.
Con 150 carátulas precargadas = 150+ lecturas de disco innecesarias. Este valor no cambia durante la sesión.

#### Código ORIGINAL
```kotlin
fun loadAlbumArtBitmap(context: Context, uriString: String): android.graphics.Bitmap? {
    val cachedRam = albumArtCache.get(uriString)
    if (cachedRam != null) return cachedRam

    val res = try {
        context.getSharedPreferences("settings_prefs", ...).getInt("art_resolution", 500)
    } catch (e: Exception) { 500 }
    ...
}
```
#### Código NUEVO
```kotlin
// Caché a nivel de archivo — se lee una sola vez
private var cachedArtResolution: Int = -1
private fun getArtResolution(context: Context): Int {
    if (cachedArtResolution == -1) {
        cachedArtResolution = context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
            .getInt("art_resolution", 500)
    }
    return cachedArtResolution
}

fun loadAlbumArtBitmap(context: Context, uriString: String): android.graphics.Bitmap? {
    val cachedRam = albumArtCache.get(uriString)
    if (cachedRam != null) return cachedRam
    val res = getArtResolution(context)
    ...
}
```

---

### Fix 11 [⏳ PENDIENTE] — `SongListItem` lambdas inestables → recompone todos los ítems visibles
**Archivo:** `ui/screens/LibraryScreen.kt` (múltiples llamadas a `SongListView`)

Las lambdas `onSongClick`, `onSongLongClick`, `onPlayDirectly` se recrean en cada recomposición
del padre (ocurre con cada tick de posición del reproductor). Compose las considera inestables
y recompone **todos los SongListItem visibles** aunque ninguna canción haya cambiado.

#### Código ORIGINAL
```kotlin
SongListView(
    songs = filteredFiles,
    onSongClick = { song -> viewModel?.showSongOptions(song) },
    onSongLongClick = { song -> /* ... */ },
    onPlayDirectly = { song -> viewModel?.playSongFromList(song, filteredFiles) },
    ...
)
```
#### Código NUEVO
```kotlin
val onSongClick = remember(viewModel) { { song: AudioFile -> viewModel?.showSongOptions(song) } }
val onSongLongClick = remember(viewModel) { { song: AudioFile -> /* ... */ } }
val onPlayDirectly = remember(viewModel, filteredFiles) {
    { song: AudioFile -> viewModel?.playSongFromList(song, filteredFiles) }
}

SongListView(
    songs = filteredFiles,
    onSongClick = onSongClick,
    onSongLongClick = onSongLongClick,
    onPlayDirectly = onPlayDirectly,
    ...
)
```

---

### Fix 12 [⏳ PENDIENTE] — `subViewSongs` calculado dos veces para el mismo subView
**Archivo:** `ui/screens/LibraryScreen.kt` — Líneas 303–334 y 892–915

Las mismas operaciones `.filter { it.album == sv.albumName }.sortedWith(...)` se calculan dos veces:
una para `scrollTargetIndex` y otra para renderizar la lista de detalle.

#### Corrección: extraer como `derivedStateOf` compartido
```kotlin
val subViewSongs by remember(filteredFiles, currentSubView, viewModel?.playlists, viewModel?.smartPlaylists) {
    derivedStateOf {
        when (val sv = currentSubView) {
            is SubView.AlbumDetail -> filteredFiles.filter { it.album == sv.albumName }
                .sortedWith(compareBy<AudioFile> { if (it.track > 0) it.track else Int.MAX_VALUE }
                    .thenBy { it.title.lowercase() })
            is SubView.ArtistDetail -> filteredFiles.filter { it.artist == sv.artistName }
            is SubView.GenreDetail -> filteredFiles.filter { it.genre == sv.genreName }
            is SubView.FolderDetail -> filteredFiles.filter { it.folderName == sv.folderName }
            is SubView.PlaylistDetail -> viewModel?.smartPlaylists?.get(sv.playlistName)
                ?: viewModel?.playlists?.get(sv.playlistName) ?: emptyList()
            is SubView.HistoryDetail -> filteredFiles.filter { it.lastPlayed > 0L }
                .sortedByDescending { it.lastPlayed }
            else -> emptyList()
        }
    }
}
// Luego usar subViewSongs tanto en scrollTargetIndex como en el render
```

---

## 🟡 Impacto Medio

---

### Fix 13 [✅ APLICADO] — `pulseScale` / `InfiniteTransition` activo aunque `glowEnabled == false`
**Archivo:** `ui/screens/PlayerScreen.kt` — Líneas 424–436

`rememberInfiniteTransition` se crea y anima aunque el glow esté desactivado,
generando frames de animación invisibles en background (consumo de CPU/GPU/batería innecesario).

#### Código ORIGINAL
```kotlin
val pulseScale = if (disableAnimations) 1f else {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    scale
}
```
#### Código NUEVO
```kotlin
// Only run the infinite animation when the glow background is actually visible
val pulseScale = if (glowEnabled && !disableAnimations) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    scale
} else 1f
```

---

### Fix 14 [✅ APLICADO] — `items(filteredSongs)` sin `key {}` en diálogo de selección de playlist
**Archivo:** `ui/screens/LibraryComponents.kt` — Línea 1510

Sin `key`, al tipear en el buscador del diálogo de creación de playlist,
Compose recompone **todos los ítems** en vez de solo los que cambiaron.

#### Código ORIGINAL
```kotlin
items(filteredSongs) { song ->
```
#### Código NUEVO
```kotlin
items(filteredSongs, key = { it.id }) { song ->
```

---

### Fix 15 [⏳ PENDIENTE] — `selectedSongs.toSet()` crea objeto nuevo en cada recomposición
**Archivo:** `ui/screens/LibraryScreen.kt` — Líneas ~784 y ~1169

`selectedSongs.toSet()` se llama en cada recomposición del caller de `SongListView`,
creando un `Set<AudioFile>` nuevo por frame aunque la selección no haya cambiado.

#### Código ORIGINAL
```kotlin
SongListView(
    selectedSongs = selectedSongs.toSet(),
    ...
)
```
#### Código NUEVO
```kotlin
val selectedSongsSet by remember { derivedStateOf { selectedSongs.toSet() } }
SongListView(
    selectedSongs = selectedSongsSet,
    ...
)
```

---

### Fix 16 [✅ APLICADO] — `glowEnabled`/`glowIntensity` se releen en cada cambio de canción
**Archivo:** `ui/screens/PlayerScreen.kt` — Líneas 391–392

`settingsPrefs.getBoolean/getString` se invoca con `playerState.currentSong?.mediaId` como key.
Esto releer las preferencias de disco **en cada cambio de canción**, aunque el usuario no haya
tocado los ajustes. Estas preferencias no cambian por canción.

#### Código ORIGINAL
```kotlin
val glowEnabled = remember(settingsPrefs, playerState.currentSong?.mediaId) {
    settingsPrefs.getBoolean("ambient_glow_enabled", true)
}
val glowIntensity = remember(settingsPrefs, playerState.currentSong?.mediaId) {
    settingsPrefs.getString("ambient_glow_intensity", "normal") ?: "normal"
}
```
#### Código NUEVO
```kotlin
// These are user preferences — no need to re-read on every song change
val glowEnabled = remember(settingsPrefs) { settingsPrefs.getBoolean("ambient_glow_enabled", true) }
val glowIntensity = remember(settingsPrefs) { settingsPrefs.getString("ambient_glow_intensity", "normal") ?: "normal" }
```

---

### Fix 17 [⏳ PENDIENTE] — `rememberPlayerState` actualiza 7 variables de estado independientes por evento
**Archivo:** `ui/screens/LibraryComponents.kt` — Líneas ~806–815

Cada evento del Player actualiza 7 `mutableStateOf` separados = potencialmente 7 recomposiciones
en cascada de `PlayerScreen` por un solo evento de Media3.

```kotlin
// ACTUAL — 7 setState separados:
isPlaying = player.isPlaying
currentSong = player.currentMediaItem
position = player.currentPosition
duration = player.duration.coerceAtLeast(0L)
shuffleModeEnabled = player.shuffleModeEnabled
mediaItemCount = player.mediaItemCount
currentMediaItemIndex = player.currentMediaItemIndex
```

#### Corrección: agrupar en un solo estado con `data class`
```kotlin
// Ya existe PlayerStateInfo como data class — usarla como estado unificado:
var playerStateInfo by mutableStateOf(PlayerStateInfo(...))

// En onEvents — una sola actualización:
playerStateInfo = playerStateInfo.copy(
    isPlaying = player.isPlaying,
    currentSong = player.currentMediaItem,
    // ...
)
```

---

### Fix 18 [⏳ PENDIENTE] — `preloadUpcomingArtwork()` lee SharedPreferences en cada invocación
**Archivo:** `playback/MediaBrowserViewModel.kt` — Líneas ~400–401

Esta función se llama desde `onMediaItemTransition`, `onPositionDiscontinuity`, `onShuffleModeEnabledChanged`.
Lee `getSharedPreferences("settings_prefs").getInt("preload_art_count", 5)` síncronamente 3+ veces por canción.

#### Corrección: cachear el valor en variable del ViewModel
```kotlin
// En MediaBrowserViewModel — leer una sola vez y actualizar si el usuario cambia ajustes:
private var cachedPreloadCount: Int = 5

// Al inicializar:
cachedPreloadCount = prefs.getInt("preload_art_count", 5)

// En preloadUpcomingArtwork():
val preloadCount = cachedPreloadCount  // sin leer disco
```

---

### Fix 19 [⏳ PENDIENTE] — `loadPlaylists()` llamado innecesariamente después de actualizar lyrics
**Archivo:** `playback/MediaBrowserViewModel.kt` — Líneas ~852, 872, 894

`updateSongLyrics`, `deleteSongTranslatedLyrics`, `updateSongTranslatedLyrics` cada uno llama
`loadPlaylists()`. Las letras no afectan la estructura de playlists normales.

#### Corrección: quitar `loadPlaylists()` de las funciones de update de lyrics (o recargar solo smart playlists con filtro de letra).

---

### Fix 20 [⏳ PENDIENTE] — Simulador FFT corre a 20fps aunque el reproductor esté pausado
**Archivo:** `ui/screens/PlayerScreen.kt` — Líneas ~346–361

```kotlin
while (true) {
    fftData = FloatArray(20) { index -> ... }
    kotlinx.coroutines.delay(50)  // 20 fps de recomposición
}
```
El loop tiene una guarda `if (!isVisualizerEnabled || !playerState.isPlaying) return`.
Verificar que la condición `playerState.isPlaying` se evalúe correctamente y que el loop
se cancele completamente cuando la música está pausada.

---

### Fix 21 [✅ APLICADO] — `new File(dataPath).parentFile` crea 10.000 objetos por scan
**Archivo:** `data/AudioScanner.kt` — Líneas ~76–80

Con 5000 canciones, crea 10.000 objetos `File` Java. Parsear el path con String directamente es más eficiente.

#### Código ORIGINAL
```kotlin
val file = java.io.File(dataPath)
val parentFile = file.parentFile
val folderPath = parentFile?.absolutePath ?: "Internal Storage"
val folderName = parentFile?.name ?: "Root"
```
#### Código NUEVO
```kotlin
val folderPath = dataPath.substringBeforeLast('/', "Internal Storage")
val folderName = folderPath.substringAfterLast('/', "Root")
```

---

### Fix 22 [✅ APLICADO] — MD5 via `MessageDigest.getInstance("MD5")` en cada acceso al cache de disco
**Archivo:** `ui/screens/LibraryComponents.kt` — Líneas ~944–948

`MessageDigest.getInstance("MD5")` es costoso y se llama en cada `getDiskCacheFile()`.
Esta función se invoca en `loadAlbumArtBitmap`, `preloadAlbumArt`, `saveBitmapToDiskCache`.

#### Código ORIGINAL
```kotlin
val md5Key = try {
    val bytes = java.security.MessageDigest.getInstance("MD5")
        .digest("$uriString-$res".toByteArray())
    bytes.joinToString("") { "%02x".format(it) }
} catch (e: Exception) { uriString.hashCode().toString() }
```
#### Código NUEVO
```kotlin
// Use hashCode — much faster, sufficient uniqueness for a local disk cache key
val md5Key = "$uriString-$res".hashCode().toString(16).let {
    if (it.startsWith('-')) "n$it" else it  // avoid negative sign in filename
}
```

---

## 🟢 Bajo Impacto / Mantenimiento

---

### Fix 23 [✅ APLICADO] — `BoxWithConstraints` innecesario en `SongListView`
**Archivo:** `ui/screens/LibraryComponents.kt` — Línea 276

`BoxWithConstraints` fuerza una medición adicional. Las constraints no se usan dentro del bloque.

#### Código ORIGINAL
```kotlin
BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
```
#### Código NUEVO
```kotlin
Box(modifier = Modifier.fillMaxSize()) {
```

---

### Fix 24 [✅ APLICADO] — `-dontoptimize` en ProGuard desactiva R8 en release
**Archivo:** `app/proguard-rules.pro` — Línea 16

La regla acelera la compilación pero produce APKs con bytecode no optimizado → ejecución más lenta.

#### Código ORIGINAL
```
-dontoptimize
```
#### Código NUEVO
```
# Removed: -dontoptimize
# Stack traces already preserved by -keepattributes SourceFile,LineNumberTable below
```

---

### Fix 25 [⏳ A FUTURO] — Sistema dual de caché de imágenes (`albumArtCache` + Coil) = RAM duplicada
**Archivos:** `LibraryComponents.kt`, `BottomNavBar.kt`, `HomeScreen.kt`, `PlayerScreen.kt`

La app mantiene dos cachés en paralelo para las mismas imágenes.
Migración a Coil unificada a largo plazo (esfuerzo alto, bajo riesgo).

---

## 📋 Resumen Completo y Estado de Implementación

| Fix | Impacto | Archivo | Esfuerzo | Estado |
|---|---|---|---|---|
| 1 — GradientBrushes precreadas | 🔴 Alto | `LibraryComponents.kt:81` | 🟢 2 líneas | ✅ **Aplicado** |
| 2 — rememberAlbumArt sin doble lookup | 🔴 Alto | `LibraryComponents.kt:1165` | 🟢 5 líneas | ✅ **Aplicado** |
| 3 — Palette en bitmap 50px | 🔴 Alto | `PlayerScreen.kt:2237` | 🟢 3 líneas | ✅ **Aplicado** |
| 4 — savePlaybackState iteración en Main | 🔴 Alto | `MediaBrowserViewModel.kt:266` | 🟡 Medio | ✅ **Aplicado** |
| 5 — savePlaybackState debounce 300ms | 🔴 Alto | `MediaBrowserViewModel.kt:197` | 🟢 10 líneas | ✅ **Aplicado** |
| 6 — clear()+addAll() → diff selectivo | 🔴 Alto | `MediaBrowserViewModel.kt:682` | 🟡 Medio | ⏳ Pendiente |
| 7 — find{} O(n) → Map indexado | 🔴 Alto | `PlayerScreen.kt:203` + ViewModel | 🟡 Medio | ✅ **Aplicado** |
| 8 — Regex compilada 1 sola vez | 🔴 Alto | `LibraryScreen.kt:262` | 🟢 1 línea | ✅ **Aplicado** |
| 9 — searchIndex precalculado | 🔴 Alto | `LibraryScreen.kt:263` + ViewModel | 🟡 Medio | ✅ **Aplicado** |
| 10 — getSharedPrefs en loadAlbumArt | 🔴 Alto | `LibraryComponents.kt:1034` | 🟢 10 líneas | ✅ **Aplicado** |
| 11 — Lambdas estables en SongListView | 🔴 Alto | `LibraryScreen.kt` múltiple | 🟡 Medio | ⏳ Pendiente |
| 12 — subViewSongs derivedState compartido | 🔴 Alto | `LibraryScreen.kt:303+892` | 🟡 Medio | ⏳ Pendiente |
| 13 — pulseScale condicional a glowEnabled | 🟡 Medio | `PlayerScreen.kt:424` | 🟢 1 línea | ✅ **Aplicado** |
| 14 — key en items(filteredSongs) | 🟡 Medio | `LibraryComponents.kt:1510` | 🟢 1 palabra | ✅ **Aplicado** |
| 15 — selectedSongs.toSet() derivedState | 🟡 Medio | `LibraryScreen.kt:784,1169` | 🟢 3 líneas | ⏳ Pendiente |
| 16 — glowEnabled sin mediaId como key | 🟡 Medio | `PlayerScreen.kt:391` | 🟢 1 línea | ✅ **Aplicado** |
| 17 — 7 setState → 1 state unificado | 🟡 Medio | `LibraryComponents.kt:806` | 🟡 Medio | ⏳ Pendiente |
| 18 — preloadCount cacheado en ViewModel | 🟡 Medio | `MediaBrowserViewModel.kt:400` | 🟢 5 líneas | ⏳ Pendiente |
| 19 — loadPlaylists() tras update lyrics | 🟡 Medio | `MediaBrowserViewModel.kt:852` | 🟢 Eliminar llamada | ⏳ Pendiente |
| 20 — FFT loop 20fps con música pausada | 🟡 Medio | `PlayerScreen.kt:346` | 🟢 Verificar condición | ⏳ Pendiente |
| 21 — File() × 2 por ítem en AudioScanner | 🟡 Medio | `AudioScanner.kt:76` | 🟢 2 líneas | ✅ **Aplicado** |
| 22 — MD5 → hashCode en disk cache key | 🟡 Medio | `LibraryComponents.kt:944` | 🟢 3 líneas | ✅ **Aplicado** |
| 23 — BoxWithConstraints → Box | 🟢 Bajo | `LibraryComponents.kt:276` | 🟢 1 palabra | ✅ **Aplicado** |
| 24 — quitar -dontoptimize | 🟢 Bajo | `proguard-rules.pro:16` | 🟢 1 línea | ✅ **Aplicado** |
| 25 — unificar caché imágenes → Coil | 🟢 Bajo* | Múltiples archivos | 🔴 Alto esfuerzo | ⏳ A futuro |

> *Bajo en urgencia porque ambos cachés funcionan, pero alto en ahorro de RAM.
