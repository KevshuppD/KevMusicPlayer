package com.kevshupp.kevmusicplayer.ui.screens

import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.kevshupp.kevmusicplayer.playback.getPhysicalPath

// ---------------- ALBUM ART CACHE & ASYNC LOADER ----------------
private val defaultCacheBudgetKb = ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceIn(16 * 1024, 48 * 1024)

val albumArtCache = object : LruCache<String, android.graphics.Bitmap>(defaultCacheBudgetKb) {
    override fun sizeOf(key: String, bitmap: android.graphics.Bitmap): Int {
        return bitmap.byteCount / 1024
    }
}

// Small bitmaps for list rows and mini players, so scrolling doesn't fill the main cache with full-size covers
private val albumArtThumbCache = object : LruCache<String, android.graphics.Bitmap>((defaultCacheBudgetKb / 3).coerceIn(8 * 1024, 16 * 1024)) {
    override fun sizeOf(key: String, bitmap: android.graphics.Bitmap): Int {
        return bitmap.byteCount / 1024
    }
}

var albumArtVersion by androidx.compose.runtime.mutableStateOf(0)

// Songs known to have no cover, so the extraction tiers don't re-run every time they scroll into view.
// Lives only in memory: nothing is written to disk for a missing cover.
private val noArtUris: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

@Volatile
private var derivedArtCachesVersion = 0

// Thumbnails and "no cover" entries are derived data: drop them whenever covers are invalidated
private fun syncDerivedArtCaches() {
    val version = albumArtVersion
    if (version != derivedArtCachesVersion) {
        derivedArtCachesVersion = version
        albumArtThumbCache.evictAll()
        noArtUris.clear()
    }
}

fun clearNoArtCache() {
    noArtUris.clear()
}

fun updateAlbumArtCacheSize(maxSizeItems: Int) {
    try {
        val estimatedSizeKb = (maxSizeItems * 300).coerceIn(16 * 1024, 64 * 1024)
        albumArtCache.resize(estimatedSizeKb)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
    val options = android.graphics.BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    options.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    return try {
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (e: OutOfMemoryError) {
        System.gc()
        try {
            options.inSampleSize *= 2
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (t: Throwable) {
            null
        }
    } catch (e: Exception) {
        null
    }
}

private fun decodeSampledBitmapFromFile(path: String, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
    val options = android.graphics.BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    android.graphics.BitmapFactory.decodeFile(path, options)
    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    options.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    return try {
        android.graphics.BitmapFactory.decodeFile(path, options)
    } catch (e: OutOfMemoryError) {
        System.gc()
        try {
            options.inSampleSize *= 2
            android.graphics.BitmapFactory.decodeFile(path, options)
        } catch (t: Throwable) {
            null
        }
    } catch (e: Exception) {
        null
    }
}

fun preloadAlbumArt(context: android.content.Context, uriString: String) {
    if (albumArtCache.get(uriString) != null) return
    loadAlbumArtBitmap(context, uriString)
}

fun preloadAlbumArtThumbnail(context: android.content.Context, uriString: String) {
    loadAlbumArtThumbnail(context, uriString)
}

private var cachedArtResolution: Int = -1
private fun getArtResolution(context: android.content.Context): Int {
    if (cachedArtResolution == -1) {
        cachedArtResolution = try {
            context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE).getInt("art_resolution", 500)
        } catch (e: Exception) {
            500
        }
    }
    return cachedArtResolution
}

private var cachedThumbResolution: Int = -1
private fun getThumbResolution(context: android.content.Context): Int {
    if (cachedThumbResolution == -1) {
        // Enough pixels for a ~64dp list thumbnail on this screen
        cachedThumbResolution = (64 * context.resources.displayMetrics.density).toInt().coerceIn(96, 256)
    }
    return minOf(cachedThumbResolution, getArtResolution(context))
}

fun getDiskCacheFile(context: android.content.Context, uriString: String, res: Int): java.io.File {
    val dir = java.io.File(context.cacheDir, "album_art_thumbnails")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val rawHash = "$uriString-$res".hashCode().toString(16)
    val fileKey = if (rawHash.startsWith('-')) "n${rawHash.substring(1)}" else rawHash
    return java.io.File(dir, "$fileKey.webp")
}

private fun saveBitmapToDiskCache(context: android.content.Context, file: java.io.File, bitmap: android.graphics.Bitmap) {
    val quality = try {
        context.getSharedPreferences("settings_prefs", android.content.Context.MODE_PRIVATE).getInt("disk_cache_quality", 85)
    } catch (e: Exception) {
        85
    }
    try {
        java.io.FileOutputStream(file).use { out ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val format = if (quality >= 100) android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS else android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
                bitmap.compress(format, quality, out)
            } else {
                @Suppress("DEPRECATION")
                bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, quality, out)
            }
        }
    } catch (e: Exception) {
        file.delete()
    }
}

// Reads a cached cover, deleting a leftover 0-byte file on the spot so the real sources get retried
private fun readDiskCachedArt(diskFile: java.io.File, res: Int): android.graphics.Bitmap? {
    if (!diskFile.exists() || !diskFile.isFile) return null
    if (diskFile.length() == 0L) {
        diskFile.delete()
        return null
    }
    return decodeSampledBitmapFromFile(diskFile.absolutePath, res, res)
}

fun clearDiskAlbumArtCache(context: android.content.Context) {
    try {
        val dir = java.io.File(context.cacheDir, "album_art_thumbnails")
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { it.delete() }
        }
    } catch (e: Exception) {}
    albumArtThumbCache.evictAll()
    noArtUris.clear()
}

fun deleteDiskAlbumArtCacheForUri(context: android.content.Context, uriString: String) {
    try {
        val res = getArtResolution(context)
        val diskFile = getDiskCacheFile(context, uriString, res)
        if (diskFile.exists()) {
            diskFile.delete()
        }
        val thumbFile = getDiskCacheFile(context, uriString, getThumbResolution(context))
        if (thumbFile.exists()) {
            thumbFile.delete()
        }
    } catch (e: Exception) {}
    albumArtThumbCache.remove(uriString)
    noArtUris.remove(uriString)
}

fun getDiskAlbumArtCacheSizeBytes(context: android.content.Context): Long {
    return try {
        val dir = java.io.File(context.cacheDir, "album_art_thumbnails")
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.sumOf { it.length() } ?: 0L
        } else {
            0L
        }
    } catch (e: Exception) {
        0L
    }
}

fun loadAlbumArtBitmapSync(context: android.content.Context, uriString: String): android.graphics.Bitmap? {
    val cachedRam = albumArtCache.get(uriString)
    if (cachedRam != null) return cachedRam
    val res = getArtResolution(context)

    val bmp = readDiskCachedArt(getDiskCacheFile(context, uriString, res), res)
    if (bmp != null) {
        albumArtCache.put(uriString, bmp)
    }
    return bmp
}

private class ArtDecodeResult(
    val bitmap: android.graphics.Bitmap?,
    // True when the audio file itself could be reached, i.e. a null bitmap really means "no cover"
    val sourceReadable: Boolean
)

/** Carátula de Deezer de una canción bajo demanda (id negativo, o uri kmpremote://deezer/<id>). */
private fun decodeRemoteArt(context: android.content.Context, uriString: String, res: Int): ArtDecodeResult? {
    val id = if (uriString.startsWith("${com.kevshupp.kevmusicplayer.data.telegram.OnDemandTracks.SCHEME}://")) {
        uriString.substringAfterLast("/").toLongOrNull()?.let { -it }
    } else {
        uriString.substringAfterLast("/").toLongOrNull()
    }
    if (id == null || !com.kevshupp.kevmusicplayer.data.telegram.OnDemandTracks.isRemoteId(id)) return null
    val url = com.kevshupp.kevmusicplayer.data.telegram.OnDemandTracks.coverUrl(context, id, large = res > 300)
        ?: return ArtDecodeResult(null, sourceReadable = true)
    return try {
        val bytes = com.kevshupp.kevmusicplayer.data.HttpClients.shared
            .newCall(okhttp3.Request.Builder().url(url).build()).execute()
            .use { if (it.isSuccessful) it.body?.bytes() else null }
        // Un fallo de red no se recuerda como "sin carátula"
        ArtDecodeResult(bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }, sourceReadable = bytes != null)
    } catch (e: Exception) {
        ArtDecodeResult(null, sourceReadable = false)
    }
}

private fun decodeAlbumArtFromSources(context: android.content.Context, uriString: String, res: Int): ArtDecodeResult {
    decodeRemoteArt(context, uriString, res)?.let { return it }
    var decodedResult: android.graphics.Bitmap? = null

    // Resolved at most once and shared by every tier that needs it
    val physicalPath: String? by lazy(LazyThreadSafetyMode.NONE) {
        try {
            val songId = uriString.substringAfterLast("/").toLongOrNull()
            getPhysicalPath(context, songId ?: 0L, uriString)
        } catch (e: Exception) {
            null
        }
    }

    // Tier 1: Hardware/Native MediaStore Thumbnail (Android 10+ / API 29+)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        try {
            val parsedUri = Uri.parse(uriString)
            decodedResult = context.contentResolver.loadThumbnail(parsedUri, android.util.Size(res, res), null)
        } catch (e: Exception) {
            // Fallback to extraction methods
        }
    }

    // Tier 2: Try reading directly from physical path with MediaMetadataRetriever
    if (decodedResult == null) {
        try {
            val path = physicalPath
            if (!path.isNullOrBlank()) {
                val file = java.io.File(path)
                if (file.isFile) {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(path)
                        val picture = retriever.embeddedPicture
                        if (picture != null) {
                            decodedResult = decodeSampledBitmap(picture, res, res)
                        }
                    } catch (e: Exception) {
                    } finally {
                        try { retriever.release() } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {}
    }

    // Tier 3: Fallback to ParcelFileDescriptor / Content Uri with MediaMetadataRetriever
    if (decodedResult == null) {
        val retriever = android.media.MediaMetadataRetriever()
        var pfd: android.os.ParcelFileDescriptor? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
            if (pfd != null) {
                retriever.setDataSource(pfd.fileDescriptor)
                val picture = retriever.embeddedPicture
                if (picture != null) {
                    decodedResult = decodeSampledBitmap(picture, res, res)
                }
            }
        } catch (e: Exception) {
            try {
                retriever.setDataSource(context, Uri.parse(uriString))
                val picture = retriever.embeddedPicture
                if (picture != null) {
                    decodedResult = decodeSampledBitmap(picture, res, res)
                }
            } catch (ex: Exception) {}
        } finally {
            try { pfd?.close() } catch (e: Exception) {}
            try { retriever.release() } catch (e: Exception) {}
        }
    }

    // Tier 4: Fallback to the cover saved for this song's album (hidden .covers folder or app internal covers directory)
    if (decodedResult == null) {
        try {
            val path = physicalPath
            val savedCover = com.kevshupp.kevmusicplayer.playback.findSavedCoverFile(context, uriString, path)
            if (savedCover != null) {
                decodedResult = decodeSampledBitmapFromFile(savedCover.absolutePath, res, res)
            }

            // Legacy loose cover file next to the audio file, if present
            if (decodedResult == null && !path.isNullOrBlank()) {
                val parentDir = java.io.File(path).parentFile
                if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
                    val coverNames = listOf(
                        "cover.jpg", "folder.jpg", "album.jpg", "front.jpg", "artwork.jpg",
                        "Cover.jpg", "Folder.jpg", "Album.jpg", "Front.jpg", "Artwork.jpg",
                        "cover.png", "folder.png", "album.png", "front.png", "Cover.png", "Folder.png"
                    )
                    val foundCover = coverNames.map { java.io.File(parentDir, it) }.firstOrNull { it.exists() && it.isFile && it.length() > 0 }
                    if (foundCover != null) {
                        decodedResult = decodeSampledBitmapFromFile(foundCover.absolutePath, res, res)
                    }
                }
            }
        } catch (e: Exception) {}
    }

    // Tier 5: Jaudiotagger tag parsing fallback for physical audio files
    if (decodedResult == null) {
        try {
            val path = physicalPath
            if (!path.isNullOrBlank()) {
                val file = java.io.File(path)
                if (file.exists() && file.isFile) {
                    val audioFile = org.jaudiotagger.audio.AudioFileIO.read(file)
                    val tag = audioFile.tag
                    val artwork = tag?.firstArtwork
                    if (artwork != null && artwork.binaryData != null && artwork.binaryData.isNotEmpty()) {
                        decodedResult = decodeSampledBitmap(artwork.binaryData, res, res)
                    }
                }
            }
        } catch (e: Exception) {}
    }

    if (decodedResult != null) return ArtDecodeResult(decodedResult, true)

    val sourceReadable = try {
        val path = physicalPath
        !path.isNullOrBlank() && java.io.File(path).let { it.isFile && it.canRead() }
    } catch (e: Exception) {
        false
    }
    return ArtDecodeResult(null, sourceReadable)
}

fun loadAlbumArtBitmap(context: android.content.Context, uriString: String): android.graphics.Bitmap? {
    val cachedRam = albumArtCache.get(uriString)
    if (cachedRam != null) return cachedRam
    syncDerivedArtCaches()

    val res = getArtResolution(context)

    val diskFile = getDiskCacheFile(context, uriString, res)
    val diskBmp = readDiskCachedArt(diskFile, res)
    if (diskBmp != null) {
        albumArtCache.put(uriString, diskBmp)
        return diskBmp
    }

    if (noArtUris.contains(uriString)) return null

    val decoded = decodeAlbumArtFromSources(context, uriString, res)
    val decodedResult = decoded.bitmap
    if (decodedResult != null) {
        albumArtCache.put(uriString, decodedResult)
        saveBitmapToDiskCache(context, diskFile, decodedResult)
        return decodedResult
    } else {
        if (decoded.sourceReadable) noArtUris.add(uriString)
        return null
    }
}

private fun scaleDownToThumb(source: android.graphics.Bitmap, thumbRes: Int): android.graphics.Bitmap {
    val shortestSide = minOf(source.width, source.height)
    if (shortestSide <= thumbRes) return source
    val scale = thumbRes.toFloat() / shortestSide
    val width = (source.width * scale).toInt().coerceAtLeast(1)
    val height = (source.height * scale).toInt().coerceAtLeast(1)
    return android.graphics.Bitmap.createScaledBitmap(source, width, height, true)
}

fun peekAlbumArtThumbnail(uriString: String): android.graphics.Bitmap? {
    syncDerivedArtCaches()
    return albumArtThumbCache.get(uriString)
}

/**
 * Loads a small version of the cover for list rows and mini players. Reuses the full-size cover when it
 * is already cached, and only falls back to the extraction tiers when nothing is cached yet.
 */
fun loadAlbumArtThumbnail(context: android.content.Context, uriString: String): android.graphics.Bitmap? {
    syncDerivedArtCaches()
    val cachedThumb = albumArtThumbCache.get(uriString)
    if (cachedThumb != null) return cachedThumb

    val thumbRes = getThumbResolution(context)
    val thumbFile = getDiskCacheFile(context, uriString, thumbRes)
    val diskThumb = readDiskCachedArt(thumbFile, thumbRes)
    if (diskThumb != null) {
        albumArtThumbCache.put(uriString, diskThumb)
        return diskThumb
    }

    var source: android.graphics.Bitmap? = albumArtCache.get(uriString)?.takeIf { !it.isRecycled }
    if (source == null) {
        val fullRes = getArtResolution(context)
        if (fullRes != thumbRes) {
            source = readDiskCachedArt(getDiskCacheFile(context, uriString, fullRes), thumbRes)
        }
    }
    if (source == null) {
        if (noArtUris.contains(uriString)) return null
        val decoded = decodeAlbumArtFromSources(context, uriString, thumbRes)
        source = decoded.bitmap
        if (source == null) {
            if (decoded.sourceReadable) noArtUris.add(uriString)
            return null
        }
    }

    var thumb = try {
        scaleDownToThumb(source, thumbRes)
    } catch (t: Throwable) {
        source
    }
    if (thumb.config != android.graphics.Bitmap.Config.RGB_565) {
        // Covers are opaque, so the 16-bit config halves the memory of each thumbnail
        thumb = try {
            thumb.copy(android.graphics.Bitmap.Config.RGB_565, false) ?: thumb
        } catch (t: Throwable) {
            thumb
        }
    }
    albumArtThumbCache.put(uriString, thumb)
    saveBitmapToDiskCache(context, thumbFile, thumb)
    return thumb
}

/**
 * @param thumbnail pass true for small surfaces (list rows, mini players) to load a list-sized bitmap
 * instead of the full-resolution cover.
 */
@Composable
fun rememberAlbumArt(uriString: String?, thumbnail: Boolean = false): android.graphics.Bitmap? {
    if (uriString == null) return null
    val context = LocalContext.current
    val version = albumArtVersion

    var bitmap by remember(uriString, version, thumbnail) {
        mutableStateOf(if (thumbnail) peekAlbumArtThumbnail(uriString) else albumArtCache.get(uriString))
    }

    LaunchedEffect(uriString, version, thumbnail) {
        if (bitmap == null) {
            val loadedBmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (thumbnail) loadAlbumArtThumbnail(context, uriString) else loadAlbumArtBitmap(context, uriString)
            }
            if (loadedBmp != null) {
                bitmap = loadedBmp
            }
        }
    }
    // Carátulas en pixel art (opción de Apariencia): se derivan de la original, que sigue en la caché intacta
    val pixelCovers = com.kevshupp.kevmusicplayer.ui.theme.LocalPixelArtCovers.current
    val palette = com.kevshupp.kevmusicplayer.ui.theme.LocalPixelPalette.current
    val original = bitmap
    return remember(original, pixelCovers, palette, thumbnail) {
        if (pixelCovers != null && original != null) {
            PixelArtCovers.of(original, thumbnail, pixelCovers.level, if (pixelCovers.palette) palette else null)
        } else original
    }
}


/**
 * Carátula ya cargada por [rememberAlbumArt]. Se dibuja directamente: antes se pasaba el Bitmap a
 * SubcomposeAsyncImage y Coil lo procesaba en segundo plano aunque ya estuviera en memoria, de modo que al
 * cambiar de pantalla el mini-reproductor y las filas mostraban unos cuadros el icono de "cargando".
 */
@Composable
fun AlbumArtImage(
    bitmap: android.graphics.Bitmap?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit = {}
) {
    val image = remember(bitmap) { bitmap?.takeIf { !it.isRecycled }?.asImageBitmap() }
    if (image != null) {
        androidx.compose.foundation.Image(image, contentDescription, modifier, contentScale = contentScale)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { placeholder() }
    }
}

/**
 * Deja en caché la miniatura (mini-reproductor) y la carátula completa (reproductor) de una canción, con el mismo
 * uri que usan ellos. Modo aleatorio la llama antes de empezar: la canción elegida al azar casi nunca está en caché
 * y el mini-reproductor y el reproductor se abrían un instante sin carátula. Espera como máximo [timeoutMs].
 */
suspend fun warmAlbumArt(context: android.content.Context, songId: Long, timeoutMs: Long = 400) {
    val uri = "content://media/external/audio/media/$songId"
    kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (peekAlbumArtThumbnail(uri) == null) loadAlbumArtThumbnail(context, uri)
            if (albumArtCache.get(uri) == null) loadAlbumArtBitmap(context, uri)
        }
    }
}
