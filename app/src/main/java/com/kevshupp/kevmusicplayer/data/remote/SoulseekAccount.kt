package com.kevshupp.kevmusicplayer.data.remote

import android.content.Context
import android.util.Base64
import com.kevshupp.kevmusicplayer.data.PreferenceConstants
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cuenta de Soulseek de KevMusic Desktop, guardada en el teléfono (settings_prefs) para que viaje en la
 * copia local y en la de la nube (sección "settings") y un PC nuevo la reciba al conectarse.
 *
 * La contraseña se guarda cifrada con AES-GCM y una clave fija de la app: no protege frente a quien tenga
 * el código, solo evita que quede legible en las copias exportadas o en Firestore. La clave no puede ser
 * del Keystore porque la copia debe poder restaurarse en otro teléfono.
 */
object SoulseekAccount {
    const val KEY_USER = "soulseek_username"
    const val KEY_PASS = "soulseek_password_enc"

    private val key by lazy {
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("kevmusicplayer/soulseek/v1".toByteArray()), "AES")
    }

    fun get(context: Context): Pair<String, String>? {
        val prefs = PreferenceConstants.getSettingsPrefs(context)
        val user = prefs.getString(KEY_USER, null)?.takeIf { it.isNotBlank() } ?: return null
        val pass = prefs.getString(KEY_PASS, null)?.let { decrypt(it) }?.takeIf { it.isNotEmpty() } ?: return null
        return user to pass
    }

    fun set(context: Context, user: String, pass: String) {
        if (get(context) == (user to pass)) return
        PreferenceConstants.getSettingsPrefs(context).edit()
            .putString(KEY_USER, user)
            .putString(KEY_PASS, encrypt(pass))
            .apply()
    }

    fun clear(context: Context) {
        PreferenceConstants.getSettingsPrefs(context).edit().remove(KEY_USER).remove(KEY_PASS).apply()
    }

    private fun encrypt(text: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(data: String): String? = runCatching {
        val bytes = Base64.decode(data, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()
}
