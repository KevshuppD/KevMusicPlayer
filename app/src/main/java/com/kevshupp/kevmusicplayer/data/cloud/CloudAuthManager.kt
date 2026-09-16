@file:Suppress("DEPRECATION")

package com.kevshupp.kevmusicplayer.data.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.kevshupp.kevmusicplayer.data.PreferenceConstants
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Manages Cloud User Authentication state (Google Sign-In), token lifecycle, and persistence.
 */
object CloudAuthManager {
    const val WEB_CLIENT_ID = "250307790225-2bf1g55v6md8j8du61le2h1q4nj84srh.apps.googleusercontent.com"

    private const val KEY_USER_UID = "cloud_user_uid"
    private const val KEY_USER_EMAIL = "cloud_user_email"
    private const val KEY_USER_NAME = "cloud_user_name"
    private const val KEY_USER_PHOTO = "cloud_user_photo"
    private const val KEY_CUSTOM_PHOTO = "cloud_user_custom_photo"
    private const val KEY_LAST_SYNC = "cloud_last_sync_timestamp"
    private const val KEY_AUTO_SYNC = "cloud_auto_sync_enabled"

    private val _currentUser = MutableStateFlow<CloudUser?>(null)
    val currentUser: StateFlow<CloudUser?> = _currentUser.asStateFlow()

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    fun getGoogleSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .requestProfile()
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun getGoogleSignInIntent(context: Context): Intent {
        val client = getGoogleSignInClient(context)
        // Sign out client before launching intent to always allow picking accounts
        try {
            client.signOut()
        } catch (e: Exception) {
            // Ignore
        }
        return client.signInIntent
    }

    fun init(context: Context) {
        val prefs = PreferenceConstants.getSettingsPrefs(context)
        val firebaseUser = auth.currentUser

        if (firebaseUser != null) {
            val customPhoto = prefs.getString(KEY_CUSTOM_PHOTO, null)
            val photo = customPhoto ?: firebaseUser.photoUrl?.toString()
            val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
            val autoSync = prefs.getBoolean(KEY_AUTO_SYNC, true)
            val user = CloudUser(
                uid = firebaseUser.uid,
                email = firebaseUser.email ?: "",
                displayName = firebaseUser.displayName,
                photoUrl = photo,
                lastSyncTimestamp = lastSync,
                isAutoSyncEnabled = autoSync
            )
            _currentUser.value = user
        } else {
            val uid = prefs.getString(KEY_USER_UID, null)
            val email = prefs.getString(KEY_USER_EMAIL, null)
            if (!uid.isNullOrBlank() && !email.isNullOrBlank()) {
                val name = prefs.getString(KEY_USER_NAME, null)
                val customPhoto = prefs.getString(KEY_CUSTOM_PHOTO, null)
                val photo = customPhoto ?: prefs.getString(KEY_USER_PHOTO, null)
                val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
                val autoSync = prefs.getBoolean(KEY_AUTO_SYNC, true)

                _currentUser.value = CloudUser(
                    uid = uid,
                    email = email,
                    displayName = name,
                    photoUrl = photo,
                    lastSyncTimestamp = lastSync,
                    isAutoSyncEnabled = autoSync
                )
            }
        }

        auth.addAuthStateListener { fbAuth ->
            val u = fbAuth.currentUser
            if (u == null) {
                _currentUser.value = null
            } else {
                val customPhoto = prefs.getString(KEY_CUSTOM_PHOTO, null)
                val photo = customPhoto ?: u.photoUrl?.toString()
                val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
                val autoSync = prefs.getBoolean(KEY_AUTO_SYNC, true)
                _currentUser.value = CloudUser(
                    uid = u.uid,
                    email = u.email ?: "",
                    displayName = u.displayName,
                    photoUrl = photo,
                    lastSyncTimestamp = lastSync,
                    isAutoSyncEnabled = autoSync
                )
            }
        }
    }

    suspend fun handleGoogleSignInResult(context: Context, intent: Intent?): Result<CloudUser> = withContext(Dispatchers.IO) {
        try {
            if (intent == null) throw Exception("Intent de Google nulo o cancelado")
            val task = GoogleSignIn.getSignedInAccountFromIntent(intent)
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken ?: throw Exception("Google no devolvió ningún ID Token")

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(credential).await()
            val firebaseUser = authResult.user ?: throw Exception("Error al autenticar usuario en Firebase")

            val prefs = PreferenceConstants.getSettingsPrefs(context)
            val customPhoto = prefs.getString(KEY_CUSTOM_PHOTO, null)
            val photo = customPhoto ?: firebaseUser.photoUrl?.toString() ?: account.photoUrl?.toString()
            val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
            val autoSync = prefs.getBoolean(KEY_AUTO_SYNC, true)

            val user = CloudUser(
                uid = firebaseUser.uid,
                email = firebaseUser.email ?: account.email ?: "",
                displayName = firebaseUser.displayName ?: account.displayName,
                photoUrl = photo,
                lastSyncTimestamp = lastSync,
                isAutoSyncEnabled = autoSync
            )

            saveUser(context, user)
            Result.success(user)
        } catch (e: Exception) {
            val userFriendlyMsg = if (e is ApiException) {
                when (e.statusCode) {
                    10 -> "Error 10 (DEVELOPER_ERROR): El paquete '${context.packageName}' no está registrado con su huella SHA-1 en la consola de Firebase para Google Sign-In."
                    7 -> "Error 7: Sin conexión a Internet."
                    12500 -> "Error 12500: Fallo de configuración en Google Play Services."
                    12501 -> "Inicio de sesión cancelado."
                    12502 -> "Operación en progreso."
                    else -> "Error de Google (${e.statusCode}): ${e.localizedMessage ?: "Fallo de autenticación"}"
                }
            } else {
                e.localizedMessage ?: "Error al autenticar con Google"
            }
            TelemetryLogger.logError(context, "CloudAuth", userFriendlyMsg, e)
            Result.failure(Exception(userFriendlyMsg, e))
        }
    }

    fun updateCustomPhoto(context: Context, photoUri: String?) {
        val prefs = PreferenceConstants.getSettingsPrefs(context)
        if (photoUri != null) {
            prefs.edit().putString(KEY_CUSTOM_PHOTO, photoUri).apply()
        } else {
            prefs.edit().remove(KEY_CUSTOM_PHOTO).apply()
        }
        val current = _currentUser.value
        if (current != null) {
            val effectivePhoto = photoUri ?: auth.currentUser?.photoUrl?.toString() ?: prefs.getString(KEY_USER_PHOTO, null)
            _currentUser.value = current.copy(photoUrl = effectivePhoto)
        }
    }

    fun saveUser(context: Context, user: CloudUser) {
        val prefs = PreferenceConstants.getSettingsPrefs(context)
        prefs.edit()
            .putString(KEY_USER_UID, user.uid)
            .putString(KEY_USER_EMAIL, user.email)
            .putString(KEY_USER_NAME, user.displayName)
            .putString(KEY_USER_PHOTO, user.photoUrl)
            .putLong(KEY_LAST_SYNC, user.lastSyncTimestamp)
            .putBoolean(KEY_AUTO_SYNC, user.isAutoSyncEnabled)
            .apply()

        _currentUser.value = user
        TelemetryLogger.logInfo(context, "CloudAuth", "Usuario guardado: ${user.email} (${user.uid})")
    }

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        val current = _currentUser.value ?: return
        val updated = current.copy(isAutoSyncEnabled = enabled)
        val prefs = PreferenceConstants.getSettingsPrefs(context)
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
        _currentUser.value = updated
    }

    fun updateLastSyncTimestamp(context: Context, timestamp: Long) {
        val current = _currentUser.value ?: return
        val updated = current.copy(lastSyncTimestamp = timestamp)
        val prefs = PreferenceConstants.getSettingsPrefs(context)
        prefs.edit().putLong(KEY_LAST_SYNC, timestamp).apply()
        _currentUser.value = updated
    }

    suspend fun signOut(context: Context) = withContext(Dispatchers.IO) {
        try {
            auth.signOut()
            getGoogleSignInClient(context).signOut()
        } catch (e: Exception) {
            TelemetryLogger.logError(context, "CloudAuth", "Error al cerrar sesión", e)
        }

        val prefs = PreferenceConstants.getSettingsPrefs(context)
        prefs.edit()
            .remove(KEY_USER_UID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_PHOTO)
            .remove(KEY_CUSTOM_PHOTO)
            .remove(KEY_LAST_SYNC)
            .remove(KEY_AUTO_SYNC)
            .apply()

        _currentUser.value = null
        TelemetryLogger.logInfo(context, "CloudAuth", "Sesión cerrada correctamente")
    }
}
