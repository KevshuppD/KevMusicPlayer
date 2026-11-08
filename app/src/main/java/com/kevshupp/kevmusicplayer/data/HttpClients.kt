package com.kevshupp.kevmusicplayer.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP compartido. Cada `OkHttpClient()` abre su propio pool de conexiones e hilos;
 * las variantes con otros timeouts se crean con [OkHttpClient.newBuilder] y reutilizan este pool.
 */
object HttpClients {
    /** Idioma del teléfono para las APIs que traducen sus textos ("es-CL", con "es" como respaldo). */
    fun deviceLanguage(): String {
        val locale = java.util.Locale.getDefault()
        return "${locale.toLanguageTag()},${locale.language};q=0.9"
    }

    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Deezer traduce los nombres de género según el idioma de la petición; sin él elegía uno cualquiera
            // y quedaban géneros como "Aziatische Muziek" o "Vaihtoehtoinen" en las etiquetas
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host.endsWith("deezer.com") && request.header("Accept-Language") == null) {
                    chain.proceed(request.newBuilder().header("Accept-Language", deviceLanguage()).build())
                } else chain.proceed(request)
            }
            .build()
    }
}
