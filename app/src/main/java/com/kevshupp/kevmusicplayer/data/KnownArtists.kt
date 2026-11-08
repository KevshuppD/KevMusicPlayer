package com.kevshupp.kevmusicplayer.data

import android.content.Context

/**
 * Artistas confirmados como personas distintas (colaboradores puestos en el editor de etiquetas o que Deezer
 * lista en `contributors`). [ArtistSplitter] los cuenta como conocidos, así "Polima WestCoast, Pablo Chill-E"
 * se separa aunque ninguno tenga canciones solos. Se guardan en `settings_prefs/known_artists`.
 */
object KnownArtists {
    private const val KEY = "known_artists"
    @Volatile private var names: Set<String> = emptySet()
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
        names = prefs()?.getStringSet(KEY, emptySet()).orEmpty().toSet()
    }

    fun all(): Set<String> = names

    /** Solo si son dos o más (un artista suelto no aporta nada). */
    fun add(artists: Collection<String>) {
        val clean = artists.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.size < 2) return
        val next = names + clean
        if (next.size == names.size) return
        names = next
        prefs()?.edit()?.putStringSet(KEY, HashSet(next))?.apply()
    }

    private fun prefs() = context?.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
}
