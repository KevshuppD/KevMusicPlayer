package com.kevshupp.kevmusicplayer.data

import java.text.Normalizer

/**
 * Separa las colaboraciones del campo artista para agrupar la biblioteca por artista real: "Bad Bunny & Jhay
 * Cortez" y "Bad Bunny feat. Drake" cuentan para Bad Bunny (y para el otro), en lugar de ser artistas aparte.
 *
 * - `feat.`, `ft.`, `featuring`, `with` siempre separan (también entre paréntesis: "X (feat. Y)").
 * - `,` `&` `/` `;` ` x ` ` y ` ` and ` solo separan si alguna de las partes ya aparece sola como artista en la
 *   biblioteca: así "Simon & Garfunkel", "Earth, Wind & Fire" o "Los Bunkers y ..." sin canciones sueltas de sus
 *   miembros siguen enteros.
 *
 * El nombre que se muestra es el de la canción en solitario si existe (su forma de escribirlo), si no la primera
 * que aparece.
 */
class ArtistSplitter(artistFields: Collection<String>) {
    private val soloKeys: Set<String> = artistFields.mapTo(HashSet()) { key(it) }
    private val displayByKey = HashMap<String, String>()

    init {
        // Forma de escribir: la del artista en solitario tiene prioridad
        artistFields.forEach { a -> if (a.isNotBlank()) displayByKey.putIfAbsent(key(a), a.trim()) }
    }

    /** Artistas de una canción, en orden (el principal primero). Nunca vacía. */
    fun split(artist: String): List<String> {
        if (artist.isBlank()) return listOf(artist)
        val parts = mutableListOf<String>()
        // 1. Colaboraciones explícitas
        strong.split(artist.replace(Regex("[()\\[\\]]"), " ")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { chunk ->
            // 2. Separadores ambiguos, solo si alguna parte es un artista conocido
            val weakParts = weak.split(chunk).map { it.trim() }.filter { it.isNotEmpty() }
            if (weakParts.size > 1 && weakParts.any { key(it) in soloKeys }) {
                parts += weakParts
            } else {
                parts += chunk
            }
        }
        return parts.map { display(it) }.distinctBy { key(it) }.ifEmpty { listOf(artist.trim()) }
    }

    /** El artista principal (para abrir su perfil desde el reproductor). */
    fun primary(artist: String): String = split(artist).first()

    /** Igual que [split] pero comparando sin tildes ni mayúsculas. */
    fun matches(artistField: String, name: String): Boolean {
        val k = key(name)
        return split(artistField).any { key(it) == k }
    }

    private fun display(name: String) = displayByKey[key(name)] ?: name

    companion object {
        private val strong = Regex("\\s+(?:feat\\.?|ft\\.?|featuring|with)\\s+", RegexOption.IGNORE_CASE)
        private val weak = Regex("\\s*(?:,|&|/|;|\\s+x\\s+|\\s+y\\s+|\\s+and\\s+)\\s*", RegexOption.IGNORE_CASE)

        fun key(s: String): String = Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ")
    }
}
