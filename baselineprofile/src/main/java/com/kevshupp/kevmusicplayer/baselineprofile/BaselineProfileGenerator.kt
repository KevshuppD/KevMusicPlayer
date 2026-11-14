package com.kevshupp.kevmusicplayer.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recorre lo que más se usa (arranque, Inicio, Biblioteca con sus pestañas, perfil de artista, reproductor y
 * Ajustes) para que Android compile ese código al instalar y no vaya interpretado los primeros segundos.
 * Necesita la app (debug) ya con permisos y onboarding hechos en el teléfono.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "com.kevshupp.kevmusicplayer.debug",
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        // Inicio
        fling(Direction.DOWN); fling(Direction.UP)

        // Biblioteca y sus pestañas
        if (clickText("Biblioteca", "Library")) {
            device.waitForIdle()
            fling(Direction.DOWN); fling(Direction.UP)
            if (clickText("Artistas", "Artists")) {
                device.waitForIdle()
                fling(Direction.DOWN)
                // Abrir el primer artista visible
                safe { device.findObject(By.scrollable(true))?.children?.getOrNull(1)?.click() }
                device.waitForIdle()
                fling(Direction.DOWN)
                device.pressBack()
                device.waitForIdle()
            }
            if (clickText("Álbumes", "Albums")) {
                device.waitForIdle()
                fling(Direction.DOWN)
            }
        }

        // Reproductor (desde el mini-reproductor, si hay canción)
        device.pressBack()
        device.waitForIdle()
        startActivityAndWait()
    }

    // Compose redibuja las listas a menudo: un nodo encontrado puede quedar viejo a mitad del gesto
    private inline fun safe(block: () -> Unit) {
        repeat(3) {
            try { block(); return } catch (_: androidx.test.uiautomator.StaleObjectException) { }
        }
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.fling(direction: Direction) = safe {
        device.findObject(By.scrollable(true))?.fling(direction)
        device.waitForIdle()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.clickText(vararg texts: String): Boolean {
        for (t in texts) {
            var clicked = false
            safe {
                val obj = device.wait(Until.findObject(By.text(t)), 2_000) ?: device.findObject(By.desc(t))
                if (obj != null) { obj.click(); clicked = true }
            }
            if (clicked) return true
        }
        return false
    }
}
