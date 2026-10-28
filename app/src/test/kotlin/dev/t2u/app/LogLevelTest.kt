package dev.t2u.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogLevelTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `logLevelOf maps off and debug and defaults everything else to info`() {
        assertEquals(LogLevel.OFF, logLevelOf("off"))
        assertEquals(LogLevel.DEBUG, logLevelOf("debug"))
        assertEquals(LogLevel.INFO, logLevelOf("info"))
        assertEquals(LogLevel.INFO, logLevelOf(null))
        assertEquals(LogLevel.INFO, logLevelOf("verbose"))
    }

    @Test
    fun `packet and chunk kinds are debug`() {
        for (kind in listOf("ssdp-rx", "ssdp-tx", "ssdp-drop", "ssdp-bind", "lounge-raw", "lounge-http")) {
            assertEquals(LogLevel.DEBUG, levelOf(kind, emptyMap()), kind)
        }
    }

    @Test
    fun `lounge noops are debug and other lounge messages are info`() {
        assertEquals(LogLevel.DEBUG, levelOf("lounge", mapOf("dir" to "in", "name" to "noop")))
        assertEquals(LogLevel.INFO, levelOf("lounge", mapOf("dir" to "in", "name" to "setPlaylist")))
        assertEquals(LogLevel.DEBUG, levelOf("lounge-app", mapOf("type" to "notHandled", "name" to "noop")))
        assertEquals(LogLevel.INFO, levelOf("lounge-app", mapOf("type" to "notHandled", "name" to "somethingElse")))
        assertEquals(LogLevel.INFO, levelOf("lounge-app", mapOf("type" to "other", "name" to "noop")))
    }

    @Test
    fun `successful media and dial GETs are debug but errors and dial POSTs are info`() {
        assertEquals(LogLevel.DEBUG, levelOf("http-req", mapOf("server" to "media", "method" to "GET", "status" to 200)))
        assertEquals(LogLevel.DEBUG, levelOf("http-req", mapOf("server" to "media", "method" to "GET", "status" to 206)))
        assertEquals(LogLevel.DEBUG, levelOf("http-req", mapOf("server" to "dial", "method" to "GET", "status" to 200)))
        assertEquals(LogLevel.INFO, levelOf("http-req", mapOf("server" to "media", "method" to "GET", "status" to 404)))
        assertEquals(LogLevel.INFO, levelOf("http-req", mapOf("server" to "media", "method" to "GET", "status" to 500)))
        assertEquals(LogLevel.INFO, levelOf("http-req", mapOf("server" to "dial", "method" to "POST", "status" to 201)))
        assertEquals(LogLevel.INFO, levelOf("http-req", mapOf("server" to "media", "method" to "GET")))
        assertEquals(LogLevel.INFO, levelOf("http-req", mapOf("server" to "media", "method" to "GET", "status" to "oops")))
    }

    @Test
    fun `everything else including unknown kinds is info`() {
        for (kind in listOf(
            "heartbeat", "discovery", "renderer", "net", "retry", "lounge-session", "feature-error",
            "service-start", "brand-new-kind",
        )) {
            assertEquals(LogLevel.INFO, levelOf(kind, emptyMap()), kind)
        }
    }

    @Test
    fun `shouldWrite honours the configured level`() {
        assertFalse(shouldWrite(LogLevel.INFO, "ssdp-tx", emptyMap()))
        assertTrue(shouldWrite(LogLevel.DEBUG, "ssdp-tx", emptyMap()))
        assertTrue(shouldWrite(LogLevel.INFO, "renderer", emptyMap()))
        for (kind in listOf("feature-error", "service-start", "renderer", "ssdp-tx")) {
            assertFalse(shouldWrite(LogLevel.OFF, kind, emptyMap()), kind)
        }
    }

    @Test
    fun `EvidenceLog filters file and logcat by level`() {
        val seen = mutableListOf<String>()
        val log = EvidenceLog(File(tmp, "logs"), monoNanos = { 42L }, logcat = { seen += it })
        fun fileText() = log.currentFile().takeIf { it.exists() }?.readText().orEmpty()

        log.event("ssdp-tx", mapOf("raw" to "x"))
        log.flush()
        assertEquals("", fileText())
        assertTrue(seen.isEmpty())

        log.event("renderer", mapOf("name" to "Arc"))
        log.flush()
        assertEquals(1, fileText().lines().count { it.isNotBlank() })
        assertEquals(1, seen.size)

        log.setLevel(LogLevel.OFF)
        log.event("renderer", mapOf("name" to "Amp"))
        log.flush()
        assertEquals(1, fileText().lines().count { it.isNotBlank() })
        assertEquals(1, seen.size)

        log.setLevel(LogLevel.DEBUG)
        log.event("ssdp-tx", mapOf("raw" to "x"))
        log.flush()
        assertEquals(2, fileText().lines().count { it.isNotBlank() })
        assertEquals(2, seen.size)
        assertEquals(LogLevel.DEBUG, log.level())
    }
}
