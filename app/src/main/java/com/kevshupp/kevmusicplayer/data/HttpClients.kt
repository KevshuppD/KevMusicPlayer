package com.kevshupp.kevmusicplayer.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP compartido. Cada `OkHttpClient()` abre su propio pool de conexiones e hilos;
 * las variantes con otros timeouts se crean con [OkHttpClient.newBuilder] y reutilizan este pool.
 */
object HttpClients {
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
