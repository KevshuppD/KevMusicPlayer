package com.kevshupp.kevmusic.desktop

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/** El binario de TDLib del jar recortado (linux/x64) carga, abre su base de datos y la vuelve a abrir. */
class TdlLoadTest {
    @Test
    fun tdlibAbreSuBase() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("tdl").toFile()
        repeat(2) { round ->
            val c = TdlClient.create()
            c.setLogVerbosityLevel(newVerbosityLevel = 1)
            withTimeout(15_000) { while ((c.getAuthorizationState() as? TdlResult.Success)?.result !is AuthorizationStateWaitTdlibParameters) kotlinx.coroutines.delay(100) }
            val r = c.setTdlibParameters(
                useTestDc = false, databaseDirectory = dir.absolutePath, filesDirectory = dir.resolve("files").absolutePath,
                databaseEncryptionKey = ByteArray(0), useFileDatabase = true, useChatInfoDatabase = true, useMessageDatabase = false,
                useSecretChats = false, apiId = 94575, apiHash = "a3406de8d171bb422bb6ddf3bbd800e2",
                systemLanguageCode = "es", deviceModel = "test", systemVersion = "linux", applicationVersion = "1"
            )
            println("TDLib ronda $round: $r ${(r as? TdlResult.Failure)?.message}")
            withTimeout(15_000) { while ((c.getAuthorizationState() as? TdlResult.Success)?.result !is AuthorizationStateWaitPhoneNumber) kotlinx.coroutines.delay(200) }
            c.close()
            kotlinx.coroutines.delay(1000)
        }
        dir.deleteRecursively()
        assertTrue(true)
    }
}
