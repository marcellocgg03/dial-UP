package dev.t2u.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** GUI-08 / GUI-05: settings saved by the app overlay config.json at every bridge start. */
class ConfigOverlayTest {
    @TempDir
    lateinit var dir: File

    private fun writeConfig(text: String) = File(dir, CONFIG_FILE).writeText(text)

    private fun writeSettings(text: String) = File(dir, SETTINGS_FILE).writeText(text)

    @Test
    fun aGaplessOverrideSavedByTheAppIsInTheEffectiveConfig() {
        assertTrue(loadAppConfig(dir).config.gapless)
        Settings(File(dir, SETTINGS_FILE)).update { it.copy(gapless = false) }
        assertFalse(loadAppConfig(dir).config.gapless)
    }

    @Test
    fun configJsonAppliesWhileTheAppHasNotSetTheKey() {
        writeConfig("""{"gapless": false}""")
        assertFalse(loadAppConfig(dir).config.gapless)
        writeSettings("""{"gapless": true}""")
        assertTrue(loadAppConfig(dir).config.gapless)
    }
}
