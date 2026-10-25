package com.kevshupp.kevmusicplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Traducción de letras por idioma de cada fragmento.
 *
 * Antes se mandaban bloques de 15 líneas a Google con `sl=auto`, que detecta **un** idioma por petición: en una
 * canción con coreano e inglés (BTS) solo se traducía el dominante, y una línea "사랑해 baby, I'm so sick" quedaba
 * a medias. Ahora cada línea se parte en tramos por sistema de escritura (hangul, kana, han, cirílico, latino…),
 * los tramos de cada sistema se traducen juntos con su idioma y la línea se vuelve a armar en orden.
 *
 * Una línea cuya traducción sale igual al original (ya está en el idioma de destino) no se incluye en el
 * resultado: así una canción en español no muestra la misma letra dos veces.
 */
object LyricsTranslator {
    private enum class Script(val sourceLang: String) {
        HANGUL("ko"), KANA("ja"), HAN("auto"), CYRILLIC("auto"), ARABIC("auto"), HEBREW("auto"),
        THAI("th"), DEVANAGARI("hi"), GREEK("el"), LATIN("auto"), OTHER("auto")
    }

    /** Fragmento de una línea en un solo sistema de escritura. */
    private class Unit(val lineTime: Long, val index: Int, val script: Script, val text: String) {
        var translated: String? = null
    }

    private val client by lazy {
        HttpClients.shared.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Traduce las líneas (tiempo → texto) a [target]. Devuelve tiempo → traducción solo para las líneas que
     * cambian; un mapa vacío significa que la letra ya está en ese idioma. Lanza excepción si no hubo red.
     */
    suspend fun translate(lines: List<Pair<Long, String>>, target: String): Map<Long, String> = withContext(Dispatchers.IO) {
        val units = mutableListOf<Unit>()
        val unitsByLine = linkedMapOf<Long, List<Unit>>()
        lines.filter { it.second.isNotBlank() }.forEach { (time, text) ->
            val parts = splitByScript(text).mapIndexed { i, (script, part) -> Unit(time, i, script, part) }
            unitsByLine[time] = parts
            units += parts
        }
        if (units.isEmpty()) return@withContext emptyMap()

        // El japonés escrito solo con kanji se detecta por el resto de la canción: si hay kana, el han es japonés
        val songHasKana = units.any { it.script == Script.KANA }
        var anySuccess = false
        var lastError: Exception? = null
        units.groupBy { if (it.script == Script.HAN && songHasKana) Script.KANA else it.script }.forEach { (script, group) ->
            // Mismo texto repetido (estribillos): se traduce una vez
            val distinct = group.map { it.text }.distinct()
            val translated = try {
                translateBatched(distinct, script.sourceLang, target).also { anySuccess = true }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
                emptyMap()
            }
            group.forEach { it.translated = translated[it.text] }

            // Tramos latinos que volvieron iguales en una canción mayormente en otro idioma: Google detectó el
            // idioma del bloque y estas líneas eran de otro (p. ej. una línea en francés entre inglés)
            if (script == Script.LATIN) {
                val unchanged = group.filter { same(it.text, it.translated) }.map { it.text }.distinct()
                if (unchanged.isNotEmpty() && unchanged.size <= 15 && unchanged.size <= distinct.size * 0.4) {
                    unchanged.forEach { text ->
                        coroutineContext.ensureActive()
                        runCatching { googleOne(text, "auto", target) }.getOrNull()?.let { t ->
                            group.filter { it.text == text }.forEach { it.translated = t }
                        }
                    }
                }
            }
        }
        if (!anySuccess) throw lastError ?: IllegalStateException("No se pudo traducir")

        val result = linkedMapOf<Long, String>()
        unitsByLine.forEach { (time, parts) ->
            val original = parts.joinToString(" ") { it.text }
            val joined = parts.joinToString(" ") { it.translated?.takeIf { t -> t.isNotBlank() } ?: it.text }
            if (!same(original, joined)) result[time] = joined.trim()
        }
        result
    }

    // ---------- Partir por sistema de escritura ----------

    private fun scriptOf(cp: Int): Script? {
        if (!Character.isLetter(cp)) return null // espacios, signos y números siguen al tramo actual
        return when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.HANGUL -> Script.HANGUL
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Script.KANA
            Character.UnicodeScript.HAN -> Script.HAN
            Character.UnicodeScript.CYRILLIC -> Script.CYRILLIC
            Character.UnicodeScript.ARABIC -> Script.ARABIC
            Character.UnicodeScript.HEBREW -> Script.HEBREW
            Character.UnicodeScript.THAI -> Script.THAI
            Character.UnicodeScript.DEVANAGARI -> Script.DEVANAGARI
            Character.UnicodeScript.GREEK -> Script.GREEK
            Character.UnicodeScript.LATIN -> Script.LATIN
            else -> Script.OTHER
        }
    }

    /**
     * "사랑해 baby, I'm so sick 너무 아파" → [HANGUL "사랑해", LATIN "baby, I'm so sick", HANGUL "너무 아파"].
     * Kana y han juntos en una frase japonesa quedan en el mismo tramo.
     */
    private fun splitByScript(line: String): List<Pair<Script, String>> {
        val parts = mutableListOf<Pair<Script, StringBuilder>>()
        var current: Script? = null
        var pending = StringBuilder() // signos antes de la primera letra
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            var s = scriptOf(cp)
            if (s == Script.HAN && current == Script.KANA) s = Script.KANA
            if (s == Script.KANA && current == Script.HAN) { parts.lastOrNull()?.let { parts[parts.lastIndex] = Script.KANA to it.second }; current = Script.KANA }
            if (s != null && s != current) {
                parts += s to StringBuilder(pending)
                pending = StringBuilder()
                current = s
            }
            if (current == null) pending.appendCodePoint(cp) else parts.last().second.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        if (parts.isEmpty()) return listOf(Script.OTHER to line.trim())
        return parts.map { it.first to it.second.toString().trim() }.filter { it.second.isNotEmpty() }
    }

    /** Igual ignorando mayúsculas, tildes y puntuación. */
    private fun same(a: String, b: String?): Boolean {
        if (b == null) return true
        fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), "")
        return norm(a) == norm(b)
    }

    // ---------- Google (y MyMemory si falla) ----------

    /** En bloques de 15 líneas o 600 caracteres, separadas por saltos de línea; si un bloque no cuadra, línea a línea. */
    private suspend fun translateBatched(texts: List<String>, source: String, target: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val batches = mutableListOf<MutableList<String>>(mutableListOf())
        var length = 0
        texts.forEach { t ->
            if (batches.last().size >= 15 || length + t.length > 600) { batches += mutableListOf<String>(); length = 0 }
            batches.last() += t
            length += t.length + 1
        }
        var failures = 0
        for (batch in batches.filter { it.isNotEmpty() }) {
            coroutineContext.ensureActive()
            val joined = try {
                googleOne(batch.joinToString("\n"), source, target)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failures++
                runCatching { myMemory(batch.joinToString("\n"), source, target) }.getOrNull()
            }
            val split = joined?.split(Regex("\\r?\\n"))
            if (split != null && split.size == batch.size) {
                batch.forEachIndexed { i, t -> out[t] = split[i].trim() }
            } else {
                batch.forEach { t ->
                    coroutineContext.ensureActive()
                    runCatching { googleOne(t, source, target) }.getOrNull()?.let { out[t] = it.trim() }
                }
            }
        }
        if (out.isEmpty() && failures > 0) throw java.io.IOException("Sin respuesta del traductor")
        return out
    }

    private fun googleOne(text: String, source: String, target: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$source&tl=$target&dt=t&q=" +
            URLEncoder.encode(text, "UTF-8")
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Google Translate: HTTP ${response.code}")
            val segments = JSONArray(response.body?.string() ?: "").optJSONArray(0) ?: throw java.io.IOException("Respuesta vacía")
            val sb = StringBuilder()
            for (i in 0 until segments.length()) segments.optJSONArray(i)?.let { sb.append(it.optString(0, "")) }
            return sb.toString()
        }
    }

    private fun myMemory(text: String, source: String, target: String): String {
        val pair = (if (source == "auto") "autodetect" else source) + "|" + target
        val url = "https://api.mymemory.translated.net/get?q=${URLEncoder.encode(text, "UTF-8")}&langpair=$pair&de=kevshupp.musicplayer@gmail.com"
        client.newCall(Request.Builder().url(url).header("User-Agent", "KevMusicPlayer").build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("MyMemory: HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: "")
            if (json.optInt("responseStatus", 200) != 200) throw java.io.IOException("MyMemory: ${json.optString("responseDetails")}")
            return json.getJSONObject("responseData").getString("translatedText")
        }
    }
}
