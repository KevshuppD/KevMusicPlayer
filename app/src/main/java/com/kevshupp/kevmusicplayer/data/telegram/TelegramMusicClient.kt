package com.kevshupp.kevmusicplayer.data.telegram

import android.content.Context
import android.os.Build
import com.kevshupp.kevmusicplayer.BuildConfig
import com.kevshupp.kevmusicplayer.data.TelemetryLogger
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

sealed interface TelegramAuthState {
    data object NotConfigured : TelegramAuthState
    data object Loading : TelegramAuthState
    data object WaitPhone : TelegramAuthState
    /** [sentVia] = por dónde llegó el código; [nextVia] = por dónde se puede reenviar (null si Telegram no ofrece otro). */
    data class WaitCode(val phone: String, val sentVia: String, val nextVia: String?, val timeoutSec: Int) : TelegramAuthState
    data class WaitPassword(val hint: String) : TelegramAuthState
    data object Ready : TelegramAuthState
    data class Unsupported(val what: String) : TelegramAuthState
}

/** Audio recibido del bot y descargado por TDLib a su caché privada. */
class TelegramAudio(val fileId: Int, val localPath: String, val title: String, val performer: String, val fileName: String)

/**
 * Sesión de usuario de Telegram (TDLib) para pedir canciones a un bot: se le envía el enlace de Deezer
 * y el bot responde con el archivo de audio. La sesión vive en `filesDir/tdlib/` y nunca sale del teléfono.
 */
object TelegramMusicClient {
    const val PREFS = "settings_prefs"
    const val KEY_BOT = "telegram_bot_username"
    const val DEFAULT_BOT = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: TdlClient? = null
    private lateinit var appContext: Context

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Loading)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Hay una petición de inicio de sesión o de verificación del bot en curso. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** @usuario del bot guardado; vacío hasta que se configura y se verifica. */
    private val _bot = MutableStateFlow("")
    val bot: StateFlow<String> = _bot.asStateFlow()

    /** Una petición al bot a la vez (descargas, reparaciones y bajo demanda): cada respuesta es de su enlace. */
    private val botMutex = kotlinx.coroutines.sync.Mutex()

    /** Sesión iniciada, bot configurado y Telegram activado en Ajustes: se pueden pedir canciones. */
    val isReady: Boolean get() = _enabled.value && _authState.value == TelegramAuthState.Ready && _bot.value.isNotBlank()

    const val KEY_ENABLED = "telegram_enabled"

    /**
     * Interruptor general de Ajustes. Apagado no arranca TDLib (la sesión se conserva en disco), oculta el
     * botón de descarga de Inicio y los de reparación, y desactiva el modo bajo demanda.
     */
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun loadEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true).also { _enabled.value = it }

    fun setEnabled(context: Context, on: Boolean) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on)
        if (!on) edit.putBoolean(OnDemandTracks.KEY_ENABLED, false)
        edit.apply()
        _enabled.value = on
        if (on) start(context)
    }

    /** Silencia una vez por sesión el chat del bot: cada canción enviada generaba una notificación. */
    private var mutedChatId = 0L

    private var botChatId: Long = 0L
    private var botChatUsername: String = ""

    val isConfigured: Boolean get() = BuildConfig.TELEGRAM_API_ID != 0 && BuildConfig.TELEGRAM_API_HASH.isNotBlank()

    fun botUsername(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BOT, DEFAULT_BOT).orEmpty()
            .trim().removePrefix("@").removePrefix("https://t.me/")

    /**
     * Comprueba que el @usuario existe y es un bot antes de guardarlo; así la pantalla pasa al buscador
     * en cuanto se confirma, o muestra el error sin cambiar de paso.
     */
    fun setBotUsername(username: String, onSaved: () -> Unit = {}) {
        val c = client ?: return
        val clean = normalizeUsername(username)
        if (clean.isBlank()) return
        _lastError.value = null
        _busy.value = true
        scope.launch {
            try {
                val chat = c.searchPublicChat(username = clean).getOrThrow()
                val userId = (chat.type as? ChatTypePrivate)?.userId
                val isBot = userId != null && (c.getUser(userId = userId) as? TdlResult.Success)?.result?.type is UserTypeBot
                if (!isBot) {
                    _lastError.value = "@$clean no es un bot"
                    return@launch
                }
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_BOT, clean).apply()
                botChatId = chat.id
                botChatUsername = clean
                muteChat(c, chat)
                _bot.value = clean
                kotlinx.coroutines.withContext(Dispatchers.Main) { onSaved() }
            } catch (e: Exception) {
                _lastError.value = "No se encontró @$clean"
            } finally {
                _busy.value = false
            }
        }
    }

    private fun normalizeUsername(text: String) = text.trim()
        .removePrefix("https://").removePrefix("http://").removePrefix("t.me/").removePrefix("@")
        .substringBefore('?').substringBefore('/').trim()

    /** Arranca TDLib la primera vez que se abre el descargador. */
    @Synchronized
    fun start(context: Context) {
        if (!loadEnabled(context)) return
        if (client != null) return
        appContext = context.applicationContext
        _bot.value = botUsername(appContext)
        if (!isConfigured) {
            _authState.value = TelegramAuthState.NotConfigured
            return
        }
        val c = TdlClient.create()
        client = c
        scope.launch {
            c.authorizationStateUpdates.collect { update -> onAuthState(c, update.authorizationState) }
        }
        scope.launch { c.getAuthorizationState() }
    }

    private suspend fun onAuthState(c: TdlClient, state: AuthorizationState) {
        if (state !is AuthorizationStateWaitTdlibParameters) _lastError.value = null
        _authState.value = when (state) {
            is AuthorizationStateWaitTdlibParameters -> {
                val dir = java.io.File(appContext.filesDir, "tdlib")
                c.setTdlibParameters(
                    useTestDc = false,
                    databaseDirectory = dir.absolutePath,
                    filesDirectory = java.io.File(dir, "files").absolutePath,
                    databaseEncryptionKey = ByteArray(0),
                    useFileDatabase = true,
                    useChatInfoDatabase = true,
                    useMessageDatabase = false,
                    useSecretChats = false,
                    apiId = BuildConfig.TELEGRAM_API_ID,
                    apiHash = BuildConfig.TELEGRAM_API_HASH,
                    systemLanguageCode = java.util.Locale.getDefault().language.ifBlank { "es" },
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    systemVersion = "Android ${Build.VERSION.RELEASE}",
                    applicationVersion = BuildConfig.VERSION_NAME
                ).failureMessage()?.let { _lastError.value = it }
                TelegramAuthState.Loading
            }
            is AuthorizationStateWaitPhoneNumber -> TelegramAuthState.WaitPhone
            is AuthorizationStateWaitCode -> TelegramAuthState.WaitCode(
                state.codeInfo.phoneNumber,
                codeTypeLabel(state.codeInfo.type) ?: "la app de Telegram",
                state.codeInfo.nextType?.let { codeTypeLabel(it) },
                state.codeInfo.timeout
            )
            is AuthorizationStateWaitPassword -> TelegramAuthState.WaitPassword(state.passwordHint)
            is AuthorizationStateReady -> TelegramAuthState.Ready
            is AuthorizationStateClosed -> {
                // Tras cerrar sesión TDLib no se puede reutilizar: se crea un cliente nuevo
                synchronized(this) { client = null }
                botChatId = 0L
                start(appContext)
                TelegramAuthState.Loading
            }
            is AuthorizationStateLoggingOut, is AuthorizationStateClosing -> TelegramAuthState.Loading
            else -> TelegramAuthState.Unsupported(state::class.simpleName.orEmpty())
        }
    }

    fun submitPhone(phone: String) = authCall { it.setAuthenticationPhoneNumber(phoneNumber = phone.filter { ch -> ch.isDigit() || ch == '+' }) }
    fun submitCode(code: String) = authCall { it.checkAuthenticationCode(code = code.trim()) }
    /** Pide el código por el siguiente método que ofrece Telegram (SMS, llamada…). */
    fun resendCode() = authCall { it.resendAuthenticationCode(reason = dev.g000sha256.tdl.dto.ResendCodeReasonUserRequest()) }

    private fun codeTypeLabel(type: dev.g000sha256.tdl.dto.AuthenticationCodeType): String? = when (type) {
        is dev.g000sha256.tdl.dto.AuthenticationCodeTypeTelegramMessage -> "la app de Telegram"
        is dev.g000sha256.tdl.dto.AuthenticationCodeTypeSms,
        is dev.g000sha256.tdl.dto.AuthenticationCodeTypeSmsWord,
        is dev.g000sha256.tdl.dto.AuthenticationCodeTypeSmsPhrase,
        is dev.g000sha256.tdl.dto.AuthenticationCodeTypeFirebaseAndroid -> "SMS"
        is dev.g000sha256.tdl.dto.AuthenticationCodeTypeCall -> "llamada"
        is dev.g000sha256.tdl.dto.AuthenticationCodeTypeFlashCall,
        is dev.g000sha256.tdl.dto.AuthenticationCodeTypeMissedCall -> "llamada perdida"
        is dev.g000sha256.tdl.dto.AuthenticationCodeTypeFragment -> "Fragment"
        else -> null
    }

    fun submitPassword(password: String) = authCall { it.checkAuthenticationPassword(password = password) }
    fun logOut() = authCall { it.logOut() }

    private fun authCall(block: suspend (TdlClient) -> TdlResult<*>) {
        val c = client ?: return
        if (_busy.value) return
        _lastError.value = null
        _busy.value = true
        scope.launch {
            try {
                block(c).failureMessage()?.let { _lastError.value = friendlyError(it) }
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun resolveBotChat(c: TdlClient): Long {
        val username = botUsername(appContext)
        if (username.isBlank()) throw IllegalStateException("Configura el @usuario del bot")
        if (botChatId != 0L && botChatUsername == username) return botChatId
        val chat = c.searchPublicChat(username = username).getOrThrow()
        botChatId = chat.id
        botChatUsername = username
        muteChat(c, chat)
        return chat.id
    }

    private suspend fun muteChat(c: TdlClient, chat: Chat) {
        if (mutedChatId == chat.id) return
        val current = chat.notificationSettings
        if (!current.useDefaultMuteFor && current.muteFor > 0) {
            mutedChatId = chat.id
            return
        }
        val muted = ChatNotificationSettings(
            useDefaultMuteFor = false,
            muteFor = Int.MAX_VALUE,
            useDefaultSound = current.useDefaultSound,
            soundId = current.soundId,
            useDefaultShowPreview = current.useDefaultShowPreview,
            showPreview = current.showPreview,
            useDefaultMuteStories = current.useDefaultMuteStories,
            muteStories = current.muteStories,
            useDefaultStorySound = current.useDefaultStorySound,
            storySoundId = current.storySoundId,
            useDefaultShowStoryPoster = current.useDefaultShowStoryPoster,
            showStoryPoster = current.showStoryPoster,
            useDefaultDisablePinnedMessageNotifications = current.useDefaultDisablePinnedMessageNotifications,
            disablePinnedMessageNotifications = current.disablePinnedMessageNotifications,
            useDefaultDisableMentionNotifications = current.useDefaultDisableMentionNotifications,
            disableMentionNotifications = current.disableMentionNotifications
        )
        if (c.setChatNotificationSettings(chatId = chat.id, notificationSettings = muted) is TdlResult.Success) {
            mutedChatId = chat.id
            TelemetryLogger.logInfo(appContext, "Telegram", "Chat del bot silenciado")
        }
    }

    /**
     * Envía el enlace de Deezer al bot y espera su respuesta de audio con una duración parecida
     * (±5 s, el bot a veces manda antes mensajes de texto). Después descarga el archivo.
     */
    suspend fun requestTrack(track: DeezerTrack, onProgress: (Float) -> Unit): TelegramAudio =
        botMutex.withLock { requestTrackLocked(track, onProgress) }

    private suspend fun requestTrackLocked(track: DeezerTrack, onProgress: (Float) -> Unit): TelegramAudio {
        val c = client ?: throw IllegalStateException("Telegram no está iniciado")
        if (_authState.value != TelegramAuthState.Ready) throw IllegalStateException("Inicia sesión en Telegram")
        val chatId = resolveBotChat(c)
        val received = coroutineScope {
            // Se escucha antes de enviar para no perder una respuesta inmediata
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(REPLY_TIMEOUT_MS) {
                    c.newMessageUpdates
                        .filter { it.message.chatId == chatId && !it.message.isOutgoing }
                        .mapNotNull { audioOf(it.message.content) }
                        .first { track.durationSec <= 0 || it.duration <= 0 || abs(it.duration - track.durationSec) <= 5 }
                }
            }
            c.sendMessage(
                chatId = chatId,
                inputMessageContent = InputMessageText(
                    text = FormattedText(text = track.link, entities = emptyArray()),
                    linkPreviewOptions = LinkPreviewOptions(
                        isDisabled = true, url = "", forceSmallMedia = false, forceLargeMedia = false, showAboveText = false
                    ),
                    clearDraft = false
                )
            ).getOrThrow()
            reply.await()
        }
        return download(c, received, onProgress)
    }

    /**
     * Plan B para canciones que no están en Deezer: se escribe el texto al bot, que responde con una lista
     * de botones "N. Artista - Título"; [choose] elige uno (o null si ninguno sirve), la app lo pulsa y
     * espera el audio.
     */
    suspend fun requestByText(query: String, choose: (List<String>) -> Int?, onProgress: (Float) -> Unit): TelegramAudio =
        botMutex.withLock { requestByTextLocked(query, choose, onProgress) }

    private suspend fun requestByTextLocked(query: String, choose: (List<String>) -> Int?, onProgress: (Float) -> Unit): TelegramAudio {
        val c = client ?: throw IllegalStateException("Telegram no está iniciado")
        if (_authState.value != TelegramAuthState.Ready) throw IllegalStateException("Inicia sesión en Telegram")
        val chatId = resolveBotChat(c)
        val results = coroutineScope {
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(REPLY_TIMEOUT_MS) {
                    c.newMessageUpdates
                        .filter { it.message.chatId == chatId && !it.message.isOutgoing }
                        .mapNotNull { u -> resultButtons(u.message.replyMarkup)?.let { u.message.id to it } }
                        .first()
                }
            }
            sendText(c, chatId, query)
            reply.await()
        }
        val (messageId, buttons) = results
        val index = choose(buttons.map { it.text }) ?: throw NoSuchElementException("El bot no tiene esa canción")
        val data = (buttons[index].type as InlineKeyboardButtonTypeCallback).data
        val received = coroutineScope {
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(REPLY_TIMEOUT_MS) {
                    c.newMessageUpdates
                        .filter { it.message.chatId == chatId && !it.message.isOutgoing }
                        .mapNotNull { audioOf(it.message.content) }
                        .first()
                }
            }
            // La respuesta del callback puede tardar o fallar aunque el bot envíe el audio: se ignora
            launch { c.getCallbackQueryAnswer(chatId = chatId, messageId = messageId, payload = CallbackQueryPayloadData(data = data)) }
            reply.await()
        }
        return download(c, received, onProgress)
    }

    /** Botones de resultado ("1. Artista - Título") de un teclado del bot; null si no los tiene. */
    private fun resultButtons(markup: ReplyMarkup?): List<InlineKeyboardButton>? {
        val keyboard = markup as? ReplyMarkupInlineKeyboard ?: return null
        val buttons = keyboard.rows.flatMap { it.toList() }
            .filter { it.type is InlineKeyboardButtonTypeCallback && RESULT_BUTTON.containsMatchIn(it.text) }
        return buttons.takeIf { it.isNotEmpty() }
    }

    private suspend fun sendText(c: TdlClient, chatId: Long, text: String) {
        c.sendMessage(
            chatId = chatId,
            inputMessageContent = InputMessageText(
                text = FormattedText(text = text, entities = emptyArray()),
                linkPreviewOptions = LinkPreviewOptions(
                    isDisabled = true, url = "", forceSmallMedia = false, forceLargeMedia = false, showAboveText = false
                ),
                clearDraft = false
            )
        ).getOrThrow()
    }

    private suspend fun download(c: TdlClient, received: ReceivedAudio, onProgress: (Float) -> Unit): TelegramAudio {
        onProgress(0f)
        val progressJob = scope.launch {
            c.fileUpdates.filter { it.file.id == received.file.id }.collect { u ->
                val total = u.file.size.takeIf { it > 0 } ?: u.file.expectedSize
                if (total > 0) onProgress((u.file.local.downloadedSize.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        val file = try {
            c.downloadFile(fileId = received.file.id, priority = 32, offset = 0L, limit = 0L, synchronous = true).getOrThrow()
        } finally {
            progressJob.cancel()
        }
        if (!file.local.isDownloadingCompleted || file.local.path.isBlank()) throw java.io.IOException("Descarga incompleta")
        return TelegramAudio(file.id, file.local.path, received.title, received.performer, received.fileName)
    }


    /** Borra la copia de la caché de TDLib una vez guardada en la carpeta de música. */
    fun deleteCachedFile(fileId: Int) {
        val c = client ?: return
        scope.launch { c.deleteFile(fileId = fileId) }
    }

    private class ReceivedAudio(val file: File, val duration: Int, val title: String, val performer: String, val fileName: String)

    private fun audioOf(content: MessageContent): ReceivedAudio? = when (content) {
        is MessageAudio -> content.audio.let { ReceivedAudio(it.audio, it.duration, it.title, it.performer, it.fileName) }
        is MessageDocument -> content.document.takeIf { it.mimeType.startsWith("audio/") }
            ?.let { ReceivedAudio(it.document, 0, "", "", it.fileName) }
        else -> null
    }

    private fun <T> TdlResult<T>.getOrThrow(): T = when (this) {
        is TdlResult.Success -> result
        is TdlResult.Failure -> {
            TelemetryLogger.logError("Telegram", "TDLib $code: $message")
            throw java.io.IOException(message)
        }
    }

    private fun friendlyError(message: String) = when {
        message.contains("PHONE_NUMBER_INVALID") -> "Número no válido: incluye el prefijo del país (+56…)"
        message.contains("PHONE_CODE_INVALID") -> "Código incorrecto"
        message.contains("PHONE_CODE_EXPIRED") -> "El código caducó, vuelve a pedirlo"
        message.contains("PASSWORD_HASH_INVALID") -> "Contraseña incorrecta"
        message.contains("FLOOD_WAIT") || message.contains("Too Many Requests") -> "Demasiados intentos, espera un rato"
        else -> message
    }

    private fun TdlResult<*>.failureMessage(): String? = (this as? TdlResult.Failure)?.message

    private const val REPLY_TIMEOUT_MS = 90_000L
    private val RESULT_BUTTON = Regex("^\\s*\\d+\\.\\s+\\S")
}
