package com.kevshupp.kevmusicplayer.data.remote

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Mientras la música suena en un PC (KevMusic Desktop, "Sonar en este PC"), el reproductor del teléfono es su
 * control remoto, como con Chromecast: no suena, reproducir/pausa, cambiar de canción y la barra se envían al PC
 * (`pcCmd`), y sigue la canción que suena allí. El estado llega en [RemoteControlServer.pcPlayback].
 */
object PcRemote {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var localPlayer: Player? = null
    /** Cambio de canción hecho por nosotros para seguir al PC (no se reenvía). */
    private var followingMediaId: String? = null

    private val state get() = RemoteControlServer.pcPlayback.value
    val isActive: Boolean get() = state != null

    fun isPlaying(): Boolean? = state?.playing

    fun remotePosition(): Long? = state?.let { s ->
        if (s.playing) s.pos + (SystemClock.elapsedRealtime() - s.at) else s.pos
    }

    private fun send(cmd: JSONObject) {
        val pc = state?.pc ?: return
        RemoteControlServer.pcCommands.tryEmit(pc to cmd)
    }

    /** Botón de reproducir: el teléfono está en pausa, así que la orden va al PC. */
    fun interceptToggle(): Boolean {
        if (!isActive) return false
        send(JSONObject().put("cmd", "toggle"))
        return true
    }

    fun bindPlayer(player: Player) {
        localPlayer = player
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady || !isActive) return
                player.pause()
                // Reproducir desde la notificación, los auriculares o la biblioteca: suena en el PC
                if (state?.playing != true) send(JSONObject().put("cmd", "toggle"))
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!isActive || mediaItem == null) return
                if (mediaItem.mediaId == followingMediaId) { followingMediaId = null; return }
                // Solo lo que elige el usuario (siguiente, anterior, tocar otra): restaurar o reemplazar la cola al
                // abrir la app también cambia de canción y hacía que el PC empezara de nuevo la que ya sonaba
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) return
                val md0 = mediaItem.mediaMetadata
                if (norm(md0.title?.toString().orEmpty()) == norm(state?.title.orEmpty())) return
                val md = mediaItem.mediaMetadata
                send(
                    JSONObject().put("cmd", "play").put("id", mediaItem.mediaId.toLongOrNull() ?: 0L)
                        .put("title", md.title?.toString().orEmpty()).put("artist", md.artist?.toString().orEmpty())
                )
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (!isActive || reason != Player.DISCONTINUITY_REASON_SEEK || old.mediaItemIndex != new.mediaItemIndex) return
                send(JSONObject().put("cmd", "seek").put("position", new.positionMs))
            }
        })
        // Sigue la canción del PC (sin sonar) para que el reproductor muestre la misma
        scope.launch {
            var last: Pair<String, String>? = null
            RemoteControlServer.pcPlayback.collect { s ->
                if (s == null) { last = null; return@collect }
                val key = s.title to s.artist
                if (key == last) return@collect
                last = key
                if (player.isPlaying) player.pause()
                follow(player, s.title, s.artist)
            }
        }
    }

    private fun norm(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun follow(player: Player, title: String, artist: String) {
        val cur = player.currentMediaItem?.mediaMetadata
        if (cur != null && norm(cur.title?.toString().orEmpty()) == norm(title)) return
        val index = (0 until player.mediaItemCount).firstOrNull { i ->
            val md = player.getMediaItemAt(i).mediaMetadata
            norm(md.title?.toString().orEmpty()) == norm(title) &&
                norm(md.artist?.toString().orEmpty()).let { a -> a.isEmpty() || norm(artist).startsWith(a.take(6)) || a.startsWith(norm(artist).take(6)) }
        } ?: return
        followingMediaId = player.getMediaItemAt(index).mediaId
        player.seekTo(index, 0L)
    }
}
