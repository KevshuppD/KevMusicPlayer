package com.kevshupp.kevmusic.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

data class DesktopRelease(val version: String, val notes: String, val debUrl: String, val debName: String, val size: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val release: DesktopRelease) : UpdateState
    data class Downloading(val release: DesktopRelease, val progress: Float) : UpdateState
    data class Installing(val release: DesktopRelease) : UpdateState
    data class Installed(val release: DesktopRelease) : UpdateState
    data class Failed(val release: DesktopRelease, val message: String) : UpdateState
}

/**
 * Actualizaciones del PC desde GitHub Releases. Las versiones de escritorio usan tags `desktop-vX.Y.Z`
 * (workflow desktop-release.yml) y se publican sin marcar como "latest", para que la app Android, que
 * consulta /releases/latest, no las tome por suyas. Instala el .deb con pkexec (pide la contraseña).
 */
object Updater {
    private const val REPO = "KevshuppD/KevMusicPlayer"
    private const val TAG_PREFIX = "desktop-v"
    const val INSTALL_DIR = "/opt/kevmusic-desktop"

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** Instalada con el .deb (y no ejecutada desde el código con probar.sh). */
    val isInstalledBuild: Boolean = ProcessHandle.current().info().command().orElse("").startsWith(INSTALL_DIR)

    suspend fun check() {
        val release = withContext(Dispatchers.IO) { runCatching { fetchLatest() }.getOrNull() } ?: return
        if (isNewer(release.version, BuildInfo.VERSION) && _state.value is UpdateState.Idle) {
            _state.value = UpdateState.Available(release)
        }
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    private fun fetchLatest(): DesktopRelease? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = http.newCall(req).execute().use { if (it.isSuccessful) it.body!!.string() else return null }
        val arr = JSONArray(body)
        val releases = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") && it.optString("tag_name").startsWith(TAG_PREFIX) }
            .mapNotNull { r ->
                val assets = r.optJSONArray("assets") ?: return@mapNotNull null
                val deb = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith("_amd64.deb") } ?: return@mapNotNull null
                DesktopRelease(
                    version = r.getString("tag_name").removePrefix(TAG_PREFIX),
                    notes = r.optString("body"),
                    debUrl = deb.getString("browser_download_url"),
                    debName = deb.getString("name"),
                    size = deb.optLong("size")
                )
            }
        return releases.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
    }

    /** Descarga el .deb y lo instala con `pkexec apt-get install` (ventana de contraseña del sistema). */
    suspend fun downloadAndInstall(release: DesktopRelease) {
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(System.getProperty("user.home"), ".cache/kevmusic-desktop/updates").apply { mkdirs() }
                dir.listFiles()?.forEach { if (it.name != release.debName) it.delete() }
                val target = File(dir, release.debName)
                if (target.length() != release.size || release.size == 0L) {
                    val tmp = File(dir, release.debName + ".part")
                    http.newCall(Request.Builder().url(release.debUrl).build()).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val total = resp.body!!.contentLength().takeIf { it > 0 } ?: release.size
                        resp.body!!.byteStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) _state.value = UpdateState.Downloading(release, done.toFloat() / total)
                                }
                            }
                        }
                    }
                    if (release.size > 0 && tmp.length() != release.size) { tmp.delete(); error("descarga incompleta") }
                    if (!tmp.renameTo(target)) error("no se pudo guardar")
                }
                target
            }
        }.getOrElse {
            _state.value = UpdateState.Failed(release, "No se pudo descargar: ${it.message}")
            return
        }

        _state.value = UpdateState.Installing(release)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val proc = ProcessBuilder("pkexec", "apt-get", "install", "-y", "--allow-downgrades", file.absolutePath)
                    .redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().readText()
                proc.waitFor() to output
            }
        }
        val (code, output) = result.getOrElse {
            _state.value = UpdateState.Failed(release, "No se pudo ejecutar pkexec: ${it.message}")
            return
        }
        _state.value = when (code) {
            0 -> UpdateState.Installed(release)
            126, 127 -> UpdateState.Failed(release, "Instalación cancelada")
            else -> UpdateState.Failed(release, "apt-get terminó con error $code: ${output.lines().lastOrNull { it.isNotBlank() } ?: ""}")
        }
    }

    /** Abre la versión instalada y cierra esta. */
    fun relaunch(exit: () -> Unit) {
        runCatching { ProcessBuilder("$INSTALL_DIR/bin/kevmusic-desktop").start() }
        exit()
    }

    fun isNewer(candidate: String, current: String) = compareVersions(candidate, current) > 0

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
