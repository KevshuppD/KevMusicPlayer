package com.kevshupp.kevmusic.desktop.local

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray

/**
 * Motor de audio del modo local: libmpv (`libmpv.so.2`, viene con Linux Mint y se declara como dependencia del .deb)
 * por JNA, sin ventana de video. Reproduce un archivo a la vez; la cola la lleva [LocalPlayer].
 * Los eventos (fin de pista, posición, pausa) llegan por un hilo propio que llama a [listener].
 */
class Mpv private constructor(private val lib: LibMpv, private val ctx: Pointer) {
    interface Listener {
        fun onPosition(seconds: Double)
        fun onDuration(seconds: Double)
        fun onPause(paused: Boolean)
        /** La pista terminó sola (no por cambiarla). */
        fun onEndOfFile()
        fun onError(message: String)
    }

    @Volatile var listener: Listener? = null
    @Volatile private var closed = false

    private interface LibMpv : Library {
        fun mpv_create(): Pointer?
        fun mpv_initialize(ctx: Pointer): Int
        fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
        fun mpv_set_property_string(ctx: Pointer, name: String, value: String): Int
        fun mpv_command(ctx: Pointer, args: StringArray): Int
        fun mpv_observe_property(ctx: Pointer, userdata: Long, name: String, format: Int): Int
        fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
        fun mpv_terminate_destroy(ctx: Pointer)
        fun mpv_error_string(error: Int): String
    }

    private interface LibC : Library {
        fun setlocale(category: Int, locale: String): String?
    }

    fun load(path: String, startPaused: Boolean = false) {
        set("pause", if (startPaused) "yes" else "no")
        command("loadfile", path, "replace")
    }

    fun stop() = command("stop")
    fun setPaused(paused: Boolean) = set("pause", if (paused) "yes" else "no")
    fun seek(seconds: Double) = command("seek", "%.3f".format(java.util.Locale.ROOT, seconds), "absolute")
    fun setVolume(percent: Int) = set("volume", percent.coerceIn(0, 100).toString())

    private fun set(name: String, value: String) {
        if (closed) return
        val r = lib.mpv_set_property_string(ctx, name, value)
        if (r < 0) listener?.onError("mpv $name: ${lib.mpv_error_string(r)}")
    }

    private fun command(vararg args: String) {
        if (closed) return
        val r = lib.mpv_command(ctx, StringArray(arrayOf(*args, null)))
        if (r < 0) listener?.onError("mpv ${args.first()}: ${lib.mpv_error_string(r)}")
    }

    fun close() {
        if (closed) return
        closed = true
        lib.mpv_terminate_destroy(ctx)
    }

    private fun eventLoop() {
        while (!closed) {
            val ev = lib.mpv_wait_event(ctx, 1.0)
            if (closed) break
            // struct mpv_event { int event_id; int error; uint64_t reply_userdata; void *data; }
            when (ev.getInt(0)) {
                EVENT_SHUTDOWN -> break
                EVENT_END_FILE -> {
                    // struct mpv_event_end_file { int reason; int error; ... }
                    val data = ev.getPointer(16) ?: continue
                    when (data.getInt(0)) {
                        END_EOF -> listener?.onEndOfFile()
                        END_ERROR -> {
                            listener?.onError("No se pudo reproducir: ${lib.mpv_error_string(data.getInt(4))}")
                            listener?.onEndOfFile()
                        }
                    }
                }
                EVENT_PROPERTY_CHANGE -> {
                    // struct mpv_event_property { const char *name; mpv_format format; void *data; }
                    val prop = ev.getPointer(16) ?: continue
                    val format = prop.getInt(Native.POINTER_SIZE.toLong())
                    val value = prop.getPointer(Native.POINTER_SIZE.toLong() * 2) ?: continue
                    when (ev.getLong(8)) {
                        OBS_TIME -> if (format == FORMAT_DOUBLE) listener?.onPosition(value.getDouble(0))
                        OBS_DURATION -> if (format == FORMAT_DOUBLE) listener?.onDuration(value.getDouble(0))
                        OBS_PAUSE -> if (format == FORMAT_FLAG) listener?.onPause(value.getInt(0) != 0)
                    }
                }
            }
        }
    }

    companion object {
        private const val EVENT_SHUTDOWN = 1
        private const val EVENT_END_FILE = 7
        private const val EVENT_PROPERTY_CHANGE = 22
        private const val END_EOF = 0
        private const val END_ERROR = 4
        private const val FORMAT_FLAG = 3
        private const val FORMAT_DOUBLE = 5
        private const val OBS_TIME = 1L
        private const val OBS_DURATION = 2L
        private const val OBS_PAUSE = 3L
        private const val LC_NUMERIC = 1

        /** El motivo si no se pudo crear (sin libmpv), para mostrarlo en la interfaz. */
        @Volatile var unavailableReason: String? = null
            private set

        fun create(): Mpv? = runCatching {
            // libmpv se niega a arrancar si LC_NUMERIC no es "C" (la JVM pone el del sistema: es_CL usa coma decimal)
            if (!com.kevshupp.kevmusic.desktop.Platform.isWindows) {
                runCatching { Native.load("c", LibC::class.java).setlocale(LC_NUMERIC, "C") }
            }
            val lib = loadLib()
            val ctx = lib.mpv_create() ?: error("mpv_create devolvió null")
            listOf(
                "vid" to "no", "video" to "no", "audio-display" to "no", "terminal" to "no", "config" to "no",
                "input-default-bindings" to "no", "idle" to "yes", "gapless-audio" to "weak",
                "audio-client-name" to "KevMusic Desktop", "replaygain" to "track"
            ).forEach { (k, v) -> lib.mpv_set_option_string(ctx, k, v) }
            val init = lib.mpv_initialize(ctx)
            if (init < 0) error("mpv_initialize: ${lib.mpv_error_string(init)}")
            lib.mpv_observe_property(ctx, OBS_TIME, "time-pos", FORMAT_DOUBLE)
            lib.mpv_observe_property(ctx, OBS_DURATION, "duration", FORMAT_DOUBLE)
            lib.mpv_observe_property(ctx, OBS_PAUSE, "pause", FORMAT_FLAG)
            Mpv(lib, ctx).also { mpv ->
                Thread(mpv::eventLoop, "mpv-events").apply { isDaemon = true }.start()
                unavailableReason = null
            }
        }.onFailure {
            unavailableReason = if (it is UnsatisfiedLinkError)
                "Falta libmpv. Instálala con: sudo apt install libmpv2"
            else "No se pudo iniciar el audio: ${it.message}"
        }.getOrNull()

        private fun loadLib(): LibMpv {
            val names = if (com.kevshupp.kevmusic.desktop.Platform.isWindows) listOf("libmpv-2", "mpv-2", "mpv")
            else listOf("mpv", "libmpv.so.2", "libmpv.so.1")
            var last: Throwable? = null
            for (n in names) {
                try { return Native.load(n, LibMpv::class.java) } catch (e: UnsatisfiedLinkError) { last = e }
            }
            throw last ?: UnsatisfiedLinkError("libmpv")
        }
    }
}
