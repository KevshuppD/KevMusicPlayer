package com.kevshupp.kevmusicplayer

import org.junit.Test
import org.junit.Assert.*
import com.kevshupp.kevmusicplayer.data.AppUpdater

class ExampleUnitTest {
    @Test
    fun testVersionCheck() {
        val updater = AppUpdater
        
        // Basic versions
        assertFalse(updater.isNewerVersion("1.2.10", "1.2.10"))
        assertTrue(updater.isNewerVersion("1.2.10", "1.2.11"))
        assertFalse(updater.isNewerVersion("1.2.11", "1.2.10"))
        
        // With 'v' prefix
        assertTrue(updater.isNewerVersion("1.2.10", "v1.2.11"))
        assertFalse(updater.isNewerVersion("v1.2.11", "1.2.10"))
        assertFalse(updater.isNewerVersion("v1.2.10", "v1.2.10"))
        
        // With debug/beta suffixes
        assertFalse(updater.isNewerVersion("1.2.10-debug", "v1.2.10"))
        assertTrue(updater.isNewerVersion("1.2.10-debug", "v1.2.11"))
        assertFalse(updater.isNewerVersion("1.2.10-beta.1", "1.2.10"))
        assertTrue(updater.isNewerVersion("1.2.10-beta.1", "1.2.11-RC2"))
    }
}