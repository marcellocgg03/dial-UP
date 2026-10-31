package dev.t2u.app

import dev.t2u.dial.DialAccessMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
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
}
