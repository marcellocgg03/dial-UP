package dev.dialup.app

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineEvidenceTest {
    @TempDir
    lateinit var filesDir: File

    private val opened = mutableListOf<EvidenceLog>()

    @AfterEach
    fun closeAll() {
        opened.forEach { it.close() }
    }

    private fun offline(): EvidenceLog =
        offlineEvidence(filesDir) { dir -> EvidenceLog.shared(dir, logcat = {}, monoNanos = { 1L }) }.also { opened += it }

    @Test
    fun loggingOffInSettingsWritesNothing() {
        File(filesDir, SETTINGS_FILE).writeText("""{"logEnabled":false}""")
        val log = offline()
        assertEquals(LogLevel.OFF, log.level())
        log.event("ytdlp-job", mapOf("action" to "offline"))
        log.flush()
        val file = log.currentFile()
        assertFalse(file.exists() && file.readText().contains("ytdlp-job"), "logging is off, nothing may be written")
    }

    @Test
    fun noSettingsKeepsTheDefaultLevelAndWrites() {
        val log = offline()
        assertTrue(log.level() != LogLevel.OFF, "default level is ${log.level()}")
        log.event("ytdlp-job", mapOf("action" to "offline"))
        log.flush()
        assertTrue(log.currentFile().readText().contains("ytdlp-job"))
    }

    @Test
    fun configJsonOffAlsoCounts() {
        File(filesDir, CONFIG_FILE).writeText("""{"logLevel":"off"}""")
        val log = offline()
        assertEquals(LogLevel.OFF, log.level())
        log.event("ytdlp-job", mapOf("action" to "offline"))
        log.flush()
        val file = log.currentFile()
        assertFalse(file.exists() && file.readText().contains("ytdlp-job"))
    }
}
