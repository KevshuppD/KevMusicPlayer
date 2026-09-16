package com.kevshupp.kevmusicplayer.data.cloud

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
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
    private const val KEY_LAST_SYNC = "cloud_last_sync_timestamp"
    private const val KEY_AUTO_SYNC = "cloud_auto_sync_enabled"

    private val _currentUser = MutableStateFlow<CloudUser?>(null)
    val currentUser: StateFlow<CloudUser?> = _currentUser.asStateFlow()

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    fun init(context: Context) {
        val prefs = PreferenceConstants.getSettingsPrefs(context)
        val firebaseUser = auth.currentUser

        if (firebaseUser != null) {
            val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
            val autoSync = prefs.getBoolean(KEY_AUTO_SYNC, true)
            val user = CloudUser(
                uid = firebaseUser.uid,
                email = firebaseUser.email ?: "",
                displayName = firebaseUser.displayName,
                photoUrl = firebaseUser.photoUrl?.toString(),
                lastSyncTimestamp = lastSync,
                isAutoSyncEnabled = autoSync
            )
            _currentUser.value = user
        } else {
            val uid = prefs.getString(KEY_USER_UID, null)
            val email = prefs.getString(KEY_USER_EMAIL, null)
            if (!uid.isNullOrBlank() && !email.isNullOrBlank()) {
                val name = prefs.getString(KEY_USER_NAME, null)
                val photo = prefs.getString(KEY_USER_PHOTO, null)
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
                val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
                val autoSync = prefs.getBoolean(KEY_AUTO_SYNC, true)
                _currentUser.value = CloudUser(
                    uid = u.uid,
                    email = u.email ?: "",
                    displayName = u.displayName,
                    photoUrl = u.photoUrl?.toString(),
                    lastSyncTimestamp = lastSync,
                    isAutoSyncEnabled = autoSync
                )
            }
        }
    }

    suspend fun signInWithGoogle(activity: Activity): Result<CloudUser> = withContext(Dispatchers.IO) {
        try {
            val credentialManager = CredentialManager.create(activity)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(
                request = request,
                context = activity
            )

            val credential = result.credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken

                val authCredential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = auth.signInWithCredential(authCredential).await()
                val firebaseUser = authResult.user ?: throw Exception("Firebase user is null after sign in")

                val prefs = PreferenceConstants.getSettingsPrefs(activity)
                val lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
                val autoSync = prefs.getBoolean(KEY_AUTO_SYNC, true)

                val user = CloudUser(
                    uid = firebaseUser.uid,
                    email = firebaseUser.email ?: googleIdTokenCredential.id,
                    displayName = firebaseUser.displayName ?: googleIdTokenCredential.displayName,
                    photoUrl = firebaseUser.photoUrl?.toString() ?: googleIdTokenCredential.profilePictureUri?.toString(),
                    lastSyncTimestamp = lastSync,
                    isAutoSyncEnabled = autoSync
                )

                saveUser(activity, user)
                Result.success(user)
            } else {
                Result.failure(Exception("Tipo de credencial no reconocido"))
            }
        } catch (e: Exception) {
            TelemetryLogger.logError(activity, "CloudAuth", "Error al iniciar sesión con Google", e)
            Result.failure(e)
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
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            TelemetryLogger.logError(context, "CloudAuth", "Error al cerrar sesión", e)
        }

        val prefs = PreferenceConstants.getSettingsPrefs(context)
        prefs.edit()
            .remove(KEY_USER_UID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_PHOTO)
            .remove(KEY_LAST_SYNC)
            .remove(KEY_AUTO_SYNC)
            .apply()

        _currentUser.value = null
        TelemetryLogger.logInfo(context, "CloudAuth", "Sesión cerrada correctamente")
    }
}
