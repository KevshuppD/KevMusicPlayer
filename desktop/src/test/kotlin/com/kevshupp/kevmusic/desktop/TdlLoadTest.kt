package com.kevshupp.kevmusic.desktop

import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** El binario de TDLib del jar recortado (linux/x64) carga y responde. */
class TdlLoadTest {
    @Test
    fun tdlibResponde() = runBlocking {
        val c = TdlClient.create()
        val r = c.getAuthorizationState()
        println("TDLib: $r")
        assertTrue(r is TdlResult.Success)
        c.close()
        Unit
    }
}
