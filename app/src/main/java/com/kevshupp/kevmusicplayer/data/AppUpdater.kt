package com.kevshupp.kevmusicplayer.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.ui.text.withStyle
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val version: String,
    val downloadUrl: String,
    val changelog: String
)

object AppUpdater {

    /**
     * Convierte las notas de la versión (Markdown de GitHub) en texto con formato: viñetas anidadas
     * por sangría, títulos y **negrita** en negrita, y `código` sin las comillas invertidas.
     */
    /** settings_prefs: buscar actualizaciones al abrir la app y avisar. */
    const val KEY_AUTO_CHECK = "update_auto_check"
    /** settings_prefs: al encontrar una, descargarla sin preguntar (al terminar se abre el instalador). */
    const val KEY_AUTO_DOWNLOAD = "update_auto_download"

    fun formatChangelog(markdown: String): androidx.compose.ui.text.AnnotatedString {
        val bold = androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        return androidx.compose.ui.text.buildAnnotatedString {
            fun appendInline(text: String) {
                // Partes impares entre ** van en negrita
                text.replace("`", "").split("**").forEachIndexed { i, part ->
                    if (i % 2 == 1) withStyle(bold) { append(part) } else append(part)
                }
            }
            val lines = markdown.replace("\r\n", "\n").lines()
            lines.forEachIndexed { index, raw ->
                val indent = raw.length - raw.trimStart().length
                val line = raw.trim()
                when {
                    line.startsWith("#") -> withStyle(bold) { append(line.trimStart('#').trim().replace("**", "").replace("`", "")) }
                    line.startsWith("- ") || line.startsWith("* ") -> {
                        val level = indent / 2
                        append("    ".repeat(level))
                        append(if (level == 0) "• " else "◦ ")
                        appendInline(line.substring(2))
                    }
                    else -> appendInline(line)
                }
                if (index < lines.lastIndex) append("\n")
            }
        }
    }

    fun isNewerVersion(current: String, latest: String): Boolean {
        val cleanCurrent = current.removePrefix("v").split("-")[0].split("+")[0].trim()
        val cleanLatest = latest.removePrefix("v").split("-")[0].split("+")[0].trim()
        if (cleanCurrent == cleanLatest) return false
        
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
        
        val maxParts = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxParts) {
            val curPart = currentParts.getOrNull(i) ?: 0
            val latPart = latestParts.getOrNull(i) ?: 0
            if (latPart > curPart) return true
            if (curPart > latPart) return false
        }
        return false
    }

    private const val REPO = "KevshuppD/KevMusicPlayer"

    /**
     * La release más reciente de la app (tag `vX.Y.Z`), o null si no se pudo comprobar (sin red). Primero la API;
     * si falla (sin sesión permite 60 consultas por hora por IP pública, compartidas con el PC y su app: el
     * 23/10/2026 y el 08/11/2026 se agotó y la app decía que no había versión nueva, y el botón de Acerca de leía el
     * error como "1.0" y decía "al día"), el feed `releases.atom`, que no tiene ese límite: la versión más alta, el
     * APK por su dirección fija (`KevMusicPlayer-vX.Y.Z.apk`, release.yml) y las notas de `changelog/vX.Y.Z.md`.
     */
    suspend fun latestRelease(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching { fromApi() }.getOrNull()
            ?: runCatching { fromFeed() }.onFailure {
                TelemetryLogger.logError(context, "Updater_Check", "No se pudo comprobar la versión", it)
            }.getOrNull()
    }

    private fun get(url: String, accept: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "KevMusicPlayer-Updater")
        accept?.let { conn.setRequestProperty("Accept", it) }
        return try {
            val code = conn.responseCode
            code to (if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else "")
        } finally {
            conn.disconnect()
        }
    }

    private fun fromApi(): UpdateInfo? {
        val (code, response) = get("https://api.github.com/repos/$REPO/releases/latest", "application/vnd.github+json")
        if (code != 200) return null
        val json = org.json.JSONObject(response)
        val tag = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url") ?: return null
        // Notas de la versión (Markdown). Con una regex se cortaban en la primera comilla
        return UpdateInfo(tag, apk, json.optString("body", ""))
    }

    private fun fromFeed(): UpdateInfo? {
        val (code, feed) = get("https://github.com/$REPO/releases.atom")
        if (code != 200) error("releases.atom respondió $code")
        // Solo las de Android (vX.Y.Z); las del PC son desktop-vX.Y.Z
        val tag = Regex("""/releases/tag/(v\d+\.\d+\.\d+)"""").findAll(feed).map { it.groupValues[1] }.distinct()
            .reduceOrNull { a, b -> if (isNewerVersion(a, b)) b else a } ?: return null
        val notes = runCatching { get("https://raw.githubusercontent.com/$REPO/main/changelog/$tag.md").second }.getOrDefault("")
        return UpdateInfo(tag, "https://github.com/$REPO/releases/download/$tag/KevMusicPlayer-$tag.apk", notes)
    }

    fun currentVersion(context: Context): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return packageInfo.versionName ?: "1.0.0"
    }

    /** La release si es más nueva que la instalada; null si no hay o no se pudo comprobar. */
    suspend fun checkUpdate(context: Context): UpdateInfo? {
        val latest = latestRelease(context) ?: return null
        return latest.takeIf { isNewerVersion(currentVersion(context), it.version) }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        targetFile: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                
                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 45000
                conn.connect()
                
                if (conn.responseCode != 200) {
                    return@withContext false
                }
                
                val contentLength = conn.contentLength
                var downloaded = 0
                
                conn.inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead = input.read(buffer)
                        while (bytesRead != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (contentLength > 0) {
                                val progress = downloaded.toFloat() / contentLength
                                withContext(Dispatchers.Main) {
                                    onProgress(progress)
                                }
                            }
                            bytesRead = input.read(buffer)
                        }
                    }
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                TelemetryLogger.logError(context, "Updater_Download", "Failed to download update APK", e)
                false
            }
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val fileUri = FileProvider.getUriForFile(context, authority, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            TelemetryLogger.logError(context, "Updater_Install", "Failed to trigger package installer", e)
            android.widget.Toast.makeText(
                context,
                "Error al iniciar instalación: ${e.localizedMessage}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
