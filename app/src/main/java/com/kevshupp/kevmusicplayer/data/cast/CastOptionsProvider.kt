package com.kevshupp.kevmusicplayer.data.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/** Receptor estándar de Google: no requiere registrar la app ni mostrar nada propio en la TV. */
class CastOptionsProvider : OptionsProvider {
    companion object {
        /** Id del receptor propio (docs/cast/index.html) en la consola de Google Cast; vacío usa el de Google, sin letras. */
        const val CUSTOM_RECEIVER_ID = ""
        val receiverId: String get() = CUSTOM_RECEIVER_ID.ifBlank { CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID }
    }

    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(receiverId)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
