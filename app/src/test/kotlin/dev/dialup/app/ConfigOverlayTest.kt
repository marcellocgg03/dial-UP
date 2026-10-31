package dev.dialup.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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

    private fun overlay(base: dev.t2u.renderer.Config, s: AppSettingsData) = applySettings(base, s)

    private val base get() = dev.t2u.renderer.Config(dataDir = File(dir, "cast"))

    @Test
    fun theLogToggleMapsToALogLevel() {
        assertEquals("off", overlay(base, AppSettingsData(logEnabled = false)).logLevel)
        assertEquals("debug", overlay(base.copy(logLevel = "debug"), AppSettingsData(logEnabled = true)).logLevel)
        assertEquals("info", overlay(base.copy(logLevel = "off"), AppSettingsData(logEnabled = true)).logLevel)
        assertEquals("debug", overlay(base.copy(logLevel = "debug"), AppSettingsData()).logLevel)
        assertEquals("off", overlay(base.copy(logLevel = "off"), AppSettingsData()).logLevel)
    }

    @Test
    fun everyOverrideReachesTheConfig() {
        val c = overlay(
            base,
            AppSettingsData(cacheMb = 512, outputFormat = "mp3", ytdlpChannel = "nightly", clearOnCastEnd = true, gapless = false),
        )
        assertEquals(512, c.cacheMb)
        assertEquals("mp3", c.outputFormat)
        assertEquals("nightly", c.ytdlpChannel)
        assertTrue(c.clearOnCastEnd)
        assertFalse(c.gapless)
        c.validate()
    }

    @Test
    fun noOverridesLeaveConfigJsonAlone() {
        writeConfig("""{"outputFormat":"flac","cacheMb":64,"ytdlpChannel":"nightly","logLevel":"debug"}""")
        val c = loadAppConfig(dir).config
        assertEquals("flac", c.outputFormat)
        assertEquals(64, c.cacheMb)
        assertEquals("nightly", c.ytdlpChannel)
        assertEquals("debug", c.logLevel)
        assertFalse(c.clearOnCastEnd)
    }

    @Test
    fun clearOnCastEndCannotBeSwitchedOnFromConfigJson() {
        writeConfig("""{"clearOnCastEnd":true}""")
        assertFalse(loadAppConfig(dir).config.clearOnCastEnd)
        writeSettings("""{"clearOnCastEnd":true}""")
        assertTrue(loadAppConfig(dir).config.clearOnCastEnd)
    }

    @Test
    fun aBadSettingsFileGivesTheConfigJsonValues() {
        writeConfig("""{"cacheMb":64}""")
        writeSettings("{not json")
        assertEquals(64, loadAppConfig(dir).config.cacheMb)
        writeSettings("""{"cacheMb":-5,"gapless":false}""")
        val c = loadAppConfig(dir).config
        assertEquals(64, c.cacheMb)
        assertFalse(c.gapless)
    }

    @Test
    fun theSummaryListsTheOverriddenKeyNamesAndClearOnCastEnd() {
        writeSettings("""{"gapless":false,"cacheMb":512,"outputFormat":"mp3","clearOnCastEnd":true}""")
        val r = loadAppConfig(dir)
        val s = configSummary(r)
        assertEquals(
            listOf("gapless", "cacheMb", "outputFormat"),
            s["gui"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(s["clearOnCastEnd"]!!.jsonPrimitive.boolean)
        assertFalse("512" in s["gui"].toString())
        assertEquals(listOf("gapless", "cacheMb", "outputFormat"), configEvidenceFields(r)["gui"])
    }

    @Test
    fun theSummaryHasAnEmptyGuiListWithoutSettings() {
        val s = configSummary(loadAppConfig(dir))
        assertTrue(s["gui"]!!.jsonArray.isEmpty())
        assertFalse(s["clearOnCastEnd"]!!.jsonPrimitive.boolean)
    }
}
