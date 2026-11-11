package com.kevshupp.kevmusic.desktop

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.math.abs

sealed interface TgState {
    data object NotConfigured : TgState
    data object Off : TgState
    data object Loading : TgState
    data object WaitPhone : TgState
    data class WaitCode(val phone: String, val sentVia: String) : TgState
    data class WaitPassword(val hint: String) : TgState
    data object Ready : TgState
    data class Failed(val message: String) : TgState
}

/**
 * Sesión propia de Telegram en el PC (TDLib por `tdl-coroutines-jvm`), para que el modo bajo demanda funcione con el
 * teléfono apagado. Es la misma lógica que `TelegramMusicClient` de la app: se manda al bot el enlace de Deezer y
 * se toma el primer audio de duración ±5 s. La sesión de Telegram no se copia del teléfono (TDLib la ata al
 * dispositivo y daría acceso a la cuenta): se inicia aquí una vez, con el código que llega a Telegram.
 * Vive en `<config>/tdlib/`. Se arranca solo al usarlo o al abrir su tarjeta de Ajustes, y se cierra tras 5 min sin uso.
 */
object TelegramPc {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(Config.dir, "tdlib")
    private var client: TdlClient? = null
    private val mutex = Mutex()
    @Volatile private var lastUse = 0L
    private var botChatId = 0L
    private var botChatFor = ""

    private val _state = MutableStateFlow<TgState>(if (configured) TgState.Off else TgState.NotConfigured)
    val state: StateFlow<TgState> = _state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    val configured: Boolean get() = BuildInfo.TELEGRAM_API_ID != 0 && BuildInfo.TELEGRAM_API_HASH.isNotBlank()

    /** Hubo una sesión iniciada en este PC (no hace falta abrir TDLib para saberlo). */
    val hasSession: Boolean get() = File(dir, "td.binlog").exists()

    /** @usuario del bot: el de Ajustes del PC o, si está vacío, el que informa el teléfono. */
    val bot: String get() = Settings.telegramBot.ifBlank { phoneBot }
    /** Se guarda en disco: sin él, con el teléfono desconectado Telegram no contaba como listo y todo iba a YouTube. */
    private val phoneBotFile = File(Config.dir, "tg_phone_bot")
    @Volatile var phoneBot: String = runCatching { phoneBotFile.readText().trim() }.getOrDefault("")
        set(value) {
            val v = value.trim()
            if (v.isBlank() || v == field) return
            field = v
            runCatching { phoneBotFile.writeText(v) }
        }

    val ready: Boolean get() = configured && hasSession && bot.isNotBlank()

    @Synchronized
    fun start() {
        if (!configured || client != null) return
        _state.value = TgState.Loading
        val c = runCatching { TdlClient.create() }.getOrElse {
            _state.value = TgState.Failed("No se pudo cargar Telegram: ${it.message}")
            return
        }
        client = c
        lastUse = System.currentTimeMillis()
        scope.launch { c.authorizationStateUpdates.collect { onAuth(c, it.authorizationState) } }
        scope.launch {
            c.setLogVerbosityLevel(newVerbosityLevel = 1)
            c.getAuthorizationState()
        }
        scope.launch {
            while (client === c) {
                delay(30_000)
                if (System.currentTimeMillis() - lastUse > 5 * 60_000 && !_busy.value && mutex.tryLock()) {
                    try { if (_state.value == TgState.Ready) close() } finally { mutex.unlock() }
                }
            }
        }
    }

    @Synchronized
    private fun close() {
        val c = client ?: return
        client = null
        botChatId = 0
        scope.launch { c.close() }
        _state.value = TgState.Off
    }

    private suspend fun onAuth(c: TdlClient, s: AuthorizationState) {
        if (client !== c) return
        _state.value = when (s) {
            is AuthorizationStateWaitTdlibParameters -> {
                dir.mkdirs()
                c.setTdlibParameters(
                    useTestDc = false,
                    databaseDirectory = dir.absolutePath,
                    filesDirectory = File(dir, "files").absolutePath,
                    databaseEncryptionKey = ByteArray(0),
                    useFileDatabase = true,
                    useChatInfoDatabase = true,
                    useMessageDatabase = false,
                    useSecretChats = false,
                    apiId = BuildInfo.TELEGRAM_API_ID,
                    apiHash = BuildInfo.TELEGRAM_API_HASH,
                    systemLanguageCode = java.util.Locale.getDefault().language.ifBlank { "es" },
                    deviceModel = "KevMusic Desktop",
                    systemVersion = System.getProperty("os.name") + " " + System.getProperty("os.version"),
                    applicationVersion = BuildInfo.VERSION
                ).failure()?.let { _error.value = it }
                TgState.Loading
            }
            is AuthorizationStateWaitPhoneNumber -> TgState.WaitPhone
            is AuthorizationStateWaitCode -> TgState.WaitCode(
                s.codeInfo.phoneNumber,
                if (s.codeInfo.type is AuthenticationCodeTypeTelegramMessage) "la app de Telegram" else "SMS"
            )
            is AuthorizationStateWaitPassword -> TgState.WaitPassword(s.passwordHint)
            is AuthorizationStateReady -> TgState.Ready
            is AuthorizationStateClosed -> {
                synchronized(this) { if (client === c) client = null }
                TgState.Off
            }
            else -> TgState.Loading
        }
    }

    fun submitPhone(phone: String) = call { it.setAuthenticationPhoneNumber(phoneNumber = phone.filter { ch -> ch.isDigit() || ch == '+' }) }
    fun submitCode(code: String) = call { it.checkAuthenticationCode(code = code.trim()) }
    fun submitPassword(password: String) = call { it.checkAuthenticationPassword(password = password) }
    fun logOut() = call { it.logOut() }

    private fun call(block: suspend (TdlClient) -> TdlResult<*>) {
        val c = client ?: return
        if (_busy.value) return
        _error.value = null
        _busy.value = true
        scope.launch {
            try { block(c).failure()?.let { _error.value = friendly(it) } }
            catch (e: Exception) { _error.value = e.message }
            finally { _busy.value = false }
        }
    }

    /** El cliente con sesión (lo abre si estaba cerrado). Con [mutex] tomado. */
    private suspend fun readyClient(): TdlClient {
        lastUse = System.currentTimeMillis()
        start()
        val s = withTimeout(30_000) { _state.first { it != TgState.Loading && it != TgState.Off } }
        if (s != TgState.Ready) throw IllegalStateException("Inicia sesión en Telegram en Ajustes del PC")
        return client ?: throw IllegalStateException("Telegram no está iniciado")
    }

    private suspend fun botChat(c: TdlClient): Long {
        val username = bot.ifBlank { throw IllegalStateException("Configura el bot de Telegram en Ajustes") }
        if (botChatId != 0L && botChatFor == username) return botChatId
        val chat = c.searchPublicChat(username = username).orThrow()
        botChatId = chat.id
        botChatFor = username
        return chat.id
    }

    /** Pide al bot la canción de Deezer [deezerId] y devuelve el archivo descargado por TDLib. */
    suspend fun request(deezerId: Long, durationSec: Int): File = mutex.withLock {
        val c = readyClient()
        val chatId = botChat(c)
        val audio = coroutineScope {
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(90_000) {
                    c.newMessageUpdates
                        .filter { it.message.chatId == chatId && !it.message.isOutgoing }
                        .mapNotNull { audioOf(it.message.content) }
                        .first { durationSec <= 0 || it.second <= 0 || abs(it.second - durationSec) <= 5 }
                }
            }
            c.sendMessage(
                chatId = chatId,
                inputMessageContent = InputMessageText(
                    text = FormattedText(text = "https://www.deezer.com/track/$deezerId", entities = emptyArray()),
                    linkPreviewOptions = LinkPreviewOptions(isDisabled = true, url = "", forceSmallMedia = false, forceLargeMedia = false, showAboveText = false),
                    clearDraft = false
                )
            ).orThrow()
            reply.await()
        }
        val file = c.downloadFile(fileId = audio.first.id, priority = 32, offset = 0L, limit = 0L, synchronous = true).orThrow()
        lastUse = System.currentTimeMillis()
        if (!file.local.isDownloadingCompleted || file.local.path.isBlank()) throw java.io.IOException("Descarga incompleta")
        File(file.local.path)
    }

    private fun audioOf(content: MessageContent): Pair<dev.g000sha256.tdl.dto.File, Int>? = when (content) {
        is MessageAudio -> content.audio.audio to content.audio.duration
        is MessageDocument -> content.document.takeIf { it.mimeType.startsWith("audio/") }?.let { it.document to 0 }
        else -> null
    }

    private fun <T> TdlResult<T>.orThrow(): T = when (this) {
        is TdlResult.Success -> result
        is TdlResult.Failure -> throw java.io.IOException(message)
    }

    private fun TdlResult<*>.failure(): String? = (this as? TdlResult.Failure)?.message

    private fun friendly(m: String) = when {
        m.contains("PHONE_NUMBER_INVALID") -> "Número no válido: incluye el prefijo del país (+56…)"
        m.contains("PHONE_CODE_INVALID") -> "Código incorrecto"
        m.contains("PHONE_CODE_EXPIRED") -> "El código caducó, vuelve a pedirlo"
        m.contains("PASSWORD_HASH_INVALID") -> "Contraseña incorrecta"
        m.contains("FLOOD_WAIT") -> "Demasiados intentos, espera un rato"
        else -> m
    }
}
