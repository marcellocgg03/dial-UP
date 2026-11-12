package dev.dialup.app

import dev.t2u.dial.DialAccessMode
import dev.t2u.upnp.AtomicWriteOptions
import dev.t2u.upnp.writeTextAtomic
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D-01/D-02: settings.json defaults to phone, survives garbage, and is replaced by rename. */
class SettingsTest {
    @TempDir
    lateinit var dir: File

    private val errors = mutableListOf<String>()

    private fun settings(file: File = File(dir, SETTINGS_FILE)) = Settings(file) { where, _ -> errors.add(where) }

    @Test
    fun missingFileGivesPhoneWithoutAnError() {
        assertEquals(DialAccessMode.PHONE, settings().dialAccess.value)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun defaultIsPhone() {
        assertEquals(DialAccessMode.PHONE, DEFAULT_DIAL_ACCESS)
    }

    @Test
    fun malformedOrUnknownContentGivesPhoneAndReportsAnError() {
        for (content in listOf("{not json", "[]", """{"dialAccess":"everyone"}""", """{"dialAccess":{"x":1}}""")) {
            errors.clear()
            val f = File(dir, SETTINGS_FILE).apply { writeText(content) }
            assertEquals(DialAccessMode.PHONE, settings(f).dialAccess.value, content)
            assertEquals(listOf("settings:read"), errors, content)
        }
    }

    @Test
    fun setPersistsAtomicallyAndKeepsUnknownKeys() {
        val f = File(dir, SETTINGS_FILE).apply { writeText("""{"other":7,"dialAccess":"phone"}""") }
        val s = settings(f)
        s.setDialAccess(DialAccessMode.LAN)
        assertEquals(DialAccessMode.LAN, s.dialAccess.value)
        assertFalse(File(dir, "settings.json.tmp").exists(), "temp file was renamed away")
        val text = f.readText()
        assertTrue(text.contains(""""dialAccess":"lan""""), text)
        assertTrue(text.contains(""""other":7"""), text)
        assertEquals(DialAccessMode.LAN, settings(f).dialAccess.value, "a new instance reads the persisted mode")
    }

    @Test
    fun setCreatesTheFileWhenMissing() {
        val f = File(dir, "sub/$SETTINGS_FILE")
        settings(f).setDialAccess(DialAccessMode.LAN)
        assertEquals("""{"dialAccess":"lan"}""", f.readText())
    }

    @Test
    fun updateKeepsUnknownKeysAndWritesAtomically() {
        val f = File(dir, SETTINGS_FILE).apply { writeText("""{"dialAccess":"lan","future":42}""") }
        val s = settings(f)
        s.update { it.copy(gapless = false) }
        assertFalse(File(dir, "settings.json.tmp").exists(), "temp file was renamed away")
        val text = f.readText()
        assertTrue(text.contains(""""dialAccess":"lan""""), text)
        assertTrue(text.contains(""""gapless":false"""), text)
        assertTrue(text.contains(""""future":42"""), text)
        assertEquals(false, s.data.value.gapless)
        assertEquals(DialAccessMode.LAN, s.dialAccess.value)
    }

    /** A writer that goes through the strict atomic write and fails on the writes listed in [failOn] (1-based). */
    private fun flakyWriter(failOn: Set<Int>): (File, String) -> Unit {
        var calls = 0
        return { target, text ->
            calls++
            val options = AtomicWriteOptions.APP.copy(atomicOnly = true)
            if (calls in failOn) {
                writeTextAtomic(target, text, options) { from, to, _ ->
                    throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "refused")
                }
            } else {
                writeTextAtomic(target, text, options)
            }
        }
    }

    @Test
    fun aFailedReplacementKeepsThePreviousFileAndIsReportedOnce() {
        val f = File(dir, SETTINGS_FILE)
        val s = Settings(f, flakyWriter(setOf(2))) { where, _ -> errors.add(where) }
        s.update { it.copy(gapless = false) }
        val afterFirst = f.readBytes()
        s.update { it.copy(cacheMb = 512) }
        assertTrue(afterFirst.contentEquals(f.readBytes()), "the file from the first update is untouched")
        assertEquals(listOf("settings:write"), errors)
        assertFalse(File(dir, "settings.json.tmp").exists())
        assertEquals(512, s.data.value.cacheMb, "the in-memory value still shows the change")
    }

    @Test
    fun theNextUpdateAfterAFailureWritesTheFullCurrentSettings() {
        val f = File(dir, SETTINGS_FILE)
        val s = Settings(f, flakyWriter(setOf(2))) { where, _ -> errors.add(where) }
        s.update { it.copy(gapless = false) }
        s.update { it.copy(cacheMb = 512) }
        s.update { it.copy(outputFormat = "mp4") }
        val text = f.readText()
        assertTrue(text.contains(""""gapless":false"""), text)
        assertTrue(text.contains(""""cacheMb":512"""), text)
        assertTrue(text.contains(""""outputFormat":"mp4""""), text)
        assertEquals(listOf("settings:write"), errors)
        assertFalse(File(dir, "settings.json.tmp").exists())
    }

    @Test
    fun aWriterThatThrowsAnIoExceptionIsReportedNotThrown() {
        val s = Settings(File(dir, SETTINGS_FILE), { _, _ -> throw IOException("disk full") }) { where, _ -> errors.add(where) }
        s.update { it.copy(gapless = false) }
        assertEquals(listOf("settings:write"), errors)
    }

    @Test
    fun aFailedSaveOfLoosenedAccessKeepsPhoneInMemoryAndOnDisk() {
        val f = File(dir, SETTINGS_FILE)
        val failing = Settings(f, { _, _ -> throw IOException("disk full") }) { where, _ -> errors.add(where) }
        failing.setDialAccess(DialAccessMode.LAN)
        assertEquals(DialAccessMode.PHONE, failing.dialAccess.value, "never looser in memory than a restart would be")
        assertEquals(DialAccessMode.PHONE, failing.data.value.dialAccess)
        assertEquals(listOf("settings:write", "settings:dialAccess"), errors)
        assertEquals(DialAccessMode.PHONE, settings(f).dialAccess.value)
    }

    @Test
    fun aFailedSaveOfTightenedAccessStaysTightAndIsReported() {
        val f = File(dir, SETTINGS_FILE).apply { writeText("""{"dialAccess":"lan"}""") }
        val s = Settings(f, flakyWriter(setOf(1))) { where, _ -> errors.add(where) }
        assertEquals(DialAccessMode.LAN, s.dialAccess.value)
        s.setDialAccess(DialAccessMode.PHONE)
        assertEquals(DialAccessMode.PHONE, s.dialAccess.value, "the stricter value is in force at once")
        assertEquals(listOf("settings:write", "settings:dialAccess"), errors, "the stale file is reported")
        assertTrue(f.readText().contains("lan"), "the file still holds the old value")
        // the next successful write brings the file in line
        s.update { it.copy(gapless = false) }
        assertEquals(DialAccessMode.PHONE, settings(f).dialAccess.value)
        assertEquals(2, errors.size)
    }

    @Test
    fun aSuccessfulLoosenedSaveIsPublishedAndPersisted() {
        val f = File(dir, SETTINGS_FILE)
        val s = settings(f)
        s.setDialAccess(DialAccessMode.LAN)
        assertEquals(DialAccessMode.LAN, s.dialAccess.value)
        assertEquals(DialAccessMode.LAN, settings(f).dialAccess.value)
        s.update { it.copy(gapless = false) }
        assertEquals(DialAccessMode.LAN, s.dialAccess.value, "an unrelated update keeps it")
        assertTrue(errors.isEmpty())
    }

    @Test
    fun missingFileGivesTheFreshInstallDefaults() {
        val d = settings().data.value
        assertEquals(AppSettingsData(), d)
        assertTrue(d.bridgeEnabled)
        assertEquals(DialAccessMode.PHONE, d.dialAccess)
        assertFalse(d.clearOnCastEnd)
        assertFalse(d.showAudioFormat)
        assertTrue(d.notifyGuests, "the guest notice is on by default")
        assertEquals(ThemeMode.SYSTEM, d.theme)
        assertEquals(null, d.logEnabled)
        assertEquals(null, d.gapless)
        assertEquals(null, d.cacheMb)
        assertEquals(null, d.outputFormat)
        assertEquals(null, d.ytdlpChannel)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun everySettingRoundTripsThroughAFreshInstance() {
        val f = File(dir, SETTINGS_FILE)
        val all = AppSettingsData(
            bridgeEnabled = false,
            dialAccess = DialAccessMode.LAN,
            clearOnCastEnd = true,
            showAudioFormat = true,
            theme = ThemeMode.DARK,
            updateCheckEnabled = false,
            logEnabled = false,
            gapless = false,
            cacheMb = 512,
            outputFormat = "mp4",
            ytdlpChannel = "nightly",
        )
        settings(f).update { all }
        assertEquals(all, settings(f).data.value)
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun playbackModesRoundTripAndLegacyAutomaticRemainsReadable() {
        val f = File(dir, SETTINGS_FILE)
        for (format in listOf("streaming", "direct", "mp4", "auto")) {
            settings(f).update { it.copy(outputFormat = format) }
            assertEquals(format, settings(f).data.value.outputFormat)
        }
    }

    @Test
    fun aBadValueFallsBackAloneAndReportsOneError() {
        val cases = listOf(
            """{"cacheMb":-1,"gapless":false}""",
            """{"cacheMb":40000,"gapless":false}""",
            """{"outputFormat":"ogg","gapless":false}""",
            """{"outputFormat":"mp3","gapless":false}""",
            """{"ytdlpChannel":"master","gapless":false}""",
            """{"logEnabled":"yes","gapless":false}""",
            """{"bridgeEnabled":"no","gapless":false}""",
            """{"showAudioFormat":"yes","gapless":false}""",
            """{"cacheMb":12.5,"gapless":false}""",
        )
        for (content in cases) {
            errors.clear()
            val f = File(dir, SETTINGS_FILE).apply { writeText(content) }
            val d = settings(f).data.value
            assertEquals(false, d.gapless, content)
            assertEquals(null, d.cacheMb, content)
            assertEquals(null, d.outputFormat, content)
            assertEquals(null, d.ytdlpChannel, content)
            assertEquals(null, d.logEnabled, content)
            assertTrue(d.bridgeEnabled, content)
            assertEquals(listOf("settings:read"), errors, content)
        }
    }

    @Test
    fun namePrefixDefaultsToTheSpeakerEmojiAndKeepsAnEmptyChoice() {
        val f = File(dir, SETTINGS_FILE)
        val s = settings(f)
        assertEquals("🔊", s.data.value.namePrefix)
        assertFalse(f.exists() && f.readText().contains("namePrefix"))
        s.update { it.copy(namePrefix = "") }
        assertEquals("", settings(f).data.value.namePrefix)
        assertTrue(f.readText().contains("namePrefix"))
        s.update { it.copy(namePrefix = "🎵") }
        assertEquals("🎵", settings(f).data.value.namePrefix)
        s.update { it.copy(namePrefix = "🔊") }
        assertFalse(f.readText().contains("namePrefix"))
    }

    @Test
    fun aReloadedPrefixCanBeResetToTheDefaultAndStaysReset() {
        val f = File(dir, SETTINGS_FILE).apply { writeText("""{"namePrefix":"Home","other":7}""") }
        val s = settings(f)
        assertEquals("Home", s.data.value.namePrefix)
        assertFalse("namePrefix" in parseSettings(f.readText()).extras, "the prefix is a known key, not an extra")
        s.update { it.copy(namePrefix = DEFAULT_NAME_PREFIX) }
        val text = f.readText()
        assertFalse(text.contains("namePrefix"), text)
        assertTrue(text.contains(""""other":7"""), text)
        assertEquals(DEFAULT_NAME_PREFIX, settings(f).data.value.namePrefix)
    }

    @Test
    fun audioFormatSwitchIsStoredOnlyWhenEnabled() {
        val f = File(dir, SETTINGS_FILE)
        val s = settings(f)
        s.update { it.copy(showAudioFormat = true) }
        assertTrue(settings(f).data.value.showAudioFormat)
        assertTrue(f.readText().contains(""""showAudioFormat":true"""))
        s.update { it.copy(showAudioFormat = false) }
        assertFalse(settings(f).data.value.showAudioFormat)
        assertFalse(f.readText().contains(""""showAudioFormat""""))
    }

    @Test
    fun problemsNameKeysNeverValues() {
        val p = parseSettings("""{"outputFormat":"SECRETVALUE","cacheMb":99999}""")
        assertEquals(setOf("outputFormat", "cacheMb"), p.problems.toSet())
        assertFalse("SECRETVALUE" in p.problems.toString())
    }

    @Test
    fun theRangeEdgesOfCacheMbAreAccepted() {
        assertEquals(0, parseSettings("""{"cacheMb":0}""").data.cacheMb)
        assertEquals(32768, parseSettings("""{"cacheMb":32768}""").data.cacheMb)
    }

    @Test
    fun settingsJsonNeverCarriesAPrefetchKey() {
        val f = File(dir, SETTINGS_FILE)
        settings(f).update { it.copy(logEnabled = true, gapless = true, cacheMb = 64, outputFormat = "auto", ytdlpChannel = "stable", clearOnCastEnd = true) }
        assertFalse("prefetch" in f.readText(), f.readText())
    }

    @Test
    fun theThemeWireNamesAreSystemLightDark() {
        assertEquals(listOf("system", "light", "dark"), ThemeMode.entries.map { it.wire })
        for (m in ThemeMode.entries) assertEquals(m, ThemeMode.fromWire(m.wire))
        for (bad in listOf("Dark", "sepia", "", null)) assertEquals(null, ThemeMode.fromWire(bad), bad.toString())
        assertEquals(ThemeMode.SYSTEM, DEFAULT_THEME)
    }

    @Test
    fun aChosenThemeIsWrittenByWireNameAndSystemIsLeftOut() {
        val f = File(dir, SETTINGS_FILE)
        val s = settings(f)
        s.update { it.copy(theme = ThemeMode.LIGHT) }
        assertFalse(File(dir, "settings.json.tmp").exists())
        assertTrue(f.readText().contains(""""theme":"light""""), f.readText())
        assertEquals(ThemeMode.LIGHT, settings(f).data.value.theme)
        s.update { it.copy(theme = ThemeMode.SYSTEM) }
        assertFalse("theme" in f.readText(), f.readText())
        assertEquals(ThemeMode.SYSTEM, settings(f).data.value.theme)
    }

    @Test
    fun theThemeKeepsUnknownKeys() {
        val f = File(dir, SETTINGS_FILE).apply { writeText("""{"theme":"dark","future":42}""") }
        val s = settings(f)
        assertEquals(ThemeMode.DARK, s.data.value.theme)
        s.update { it.copy(gapless = false) }
        val text = f.readText()
        assertTrue(text.contains(""""future":42"""), text)
        assertTrue(text.contains(""""gapless":false"""), text)
        assertEquals(1, Regex(""""theme"""").findAll(text).count(), text)
        assertEquals(ThemeMode.DARK, settings(f).data.value.theme)
    }

    @Test
    fun aBadThemeFallsBackToSystemAloneAndReportsOneError() {
        val bad = listOf(""""sepia"""", """"Dark"""", "1", "true", "null", """{"x":1}""")
        for (v in bad) {
            errors.clear()
            val content = """{"theme":$v,"gapless":false}"""
            val f = File(dir, SETTINGS_FILE).apply { writeText(content) }
            val d = settings(f).data.value
            assertEquals(ThemeMode.SYSTEM, d.theme, content)
            assertEquals(false, d.gapless, content)
            assertEquals(listOf("settings:read"), errors, content)
            val p = parseSettings(content)
            assertEquals(listOf("theme"), p.problems, content)
            assertFalse("theme" in p.extras, content)
            assertFalse("sepia" in p.problems.toString() || "Dark" in p.problems.toString(), content)
        }
    }

    @Test
    fun theDarkDecisionFollowsTheMode() {
        assertTrue(useDarkTheme(ThemeMode.SYSTEM, true))
        assertFalse(useDarkTheme(ThemeMode.SYSTEM, false))
        assertFalse(useDarkTheme(ThemeMode.LIGHT, true))
        assertFalse(useDarkTheme(ThemeMode.LIGHT, false))
        assertTrue(useDarkTheme(ThemeMode.DARK, true))
        assertTrue(useDarkTheme(ThemeMode.DARK, false))
    }

    @Test
    fun theThemeIsNotAConfigOverride() {
        assertTrue(settingsOverrideKeys(AppSettingsData(theme = ThemeMode.DARK)).isEmpty())
    }

    @Test
    fun theUpdateCheckIsOnByDefaultAndTrueIsNotWritten() {
        assertTrue(AppSettingsData().updateCheckEnabled)
        val f = File(dir, SETTINGS_FILE)
        settings(f).update { it.copy(clearOnCastEnd = true) }
        assertFalse("updateCheckEnabled" in f.readText(), f.readText())
    }

    @Test
    fun theUpdateCheckOffRoundTripsAndKeepsUnknownKeys() {
        val f = File(dir, SETTINGS_FILE).apply { writeText("""{"future":42}""") }
        val s = settings(f)
        s.update { it.copy(updateCheckEnabled = false) }
        assertTrue(f.readText().contains(""""updateCheckEnabled":false"""), f.readText())
        assertTrue(f.readText().contains(""""future":42"""), f.readText())
        assertFalse(settings(f).data.value.updateCheckEnabled)
        s.update { it.copy(updateCheckEnabled = true) }
        assertFalse("updateCheckEnabled" in f.readText(), f.readText())
        assertTrue(settings(f).data.value.updateCheckEnabled)
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun aBadUpdateCheckValueFallsBackToOnAndReportsOnlyTheKey() {
        for (v in listOf(""""yes"""", "1", "null", """{"x":1}""")) {
            errors.clear()
            val content = """{"updateCheckEnabled":$v,"gapless":false}"""
            val f = File(dir, SETTINGS_FILE).apply { writeText(content) }
            val d = settings(f).data.value
            assertTrue(d.updateCheckEnabled, content)
            assertEquals(false, d.gapless, content)
            assertEquals(listOf("settings:read"), errors, content)
            val p = parseSettings(content)
            assertEquals(listOf("updateCheckEnabled"), p.problems, content)
            assertFalse("updateCheckEnabled" in p.extras, content)
            assertFalse("yes" in p.problems.toString(), content)
        }
    }

    @Test
    fun theUpdateCheckIsNotAConfigOverride() {
        assertTrue(settingsOverrideKeys(AppSettingsData(updateCheckEnabled = false)).isEmpty())
    }

    @Test
    fun theGuestNoticeSwitchRoundTripsAndOnlyOffIsWritten() {
        val f = File(dir, SETTINGS_FILE)
        val s = settings(f)
        s.update { it.copy(notifyGuests = false) }
        assertFalse(settings(f).data.value.notifyGuests)
        assertTrue(f.readText().contains(""""notifyGuests":false"""))
        s.update { it.copy(notifyGuests = true) }
        assertTrue(settings(f).data.value.notifyGuests)
        assertFalse(f.readText().contains("notifyGuests"))
    }
}
