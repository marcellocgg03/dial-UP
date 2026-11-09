// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FailuresTest {
    @TempDir
    lateinit var tmp: File

    private val opened = mutableListOf<EvidenceLog>()

    @AfterEach
    fun closeAll() {
        opened.forEach { it.close() }
    }

    private fun newLog(name: String): EvidenceLog =
        EvidenceLog(File(tmp, name), monoNanos = { 1L }, logcat = {}).also { opened += it }

    private fun lines(log: EvidenceLog): List<String> {
        log.flush()
        val file = log.currentFile()
        return if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
    }

    @Test
    fun `a failure becomes one feature-error line with where, class and message`() {
        val log = newLog("a")
        Failures.report("speaker:hide", IOException("boom"), log)
        val written = lines(log)
        assertEquals(1, written.size)
        val line = written.single()
        assertTrue(line.contains("\"kind\":\"feature-error\""), line)
        assertTrue(line.contains("\"where\":\"speaker:hide\""), line)
        assertTrue(line.contains("\"error\":\"java.io.IOException\""), line)
        assertTrue(line.contains("\"message\":\"boom\""), line)
    }

    @Test
    fun `a long message is cut to 200 characters`() {
        val log = newLog("b")
        Failures.report("speaker:rename", IOException("x".repeat(500)), log)
        val line = lines(log).single()
        assertTrue(line.contains("\"message\":\"" + "x".repeat(200) + "\""), line)
        assertTrue(!line.contains("x".repeat(201)), line)
    }

    @Test
    fun `cancellation is rethrown and nothing is written`() {
        val log = newLog("c")
        val cancel = CancellationException("stop")
        val thrown = assertFailsWith<CancellationException> { Failures.report("speaker:hide", cancel, log) }
        assertSame(cancel, thrown)
        assertTrue(lines(log).isEmpty())
    }

    @Test
    fun `no log and a closed log return normally and write nothing`() {
        Failures.report("speaker:hide", IOException("no log"), null)
        val log = newLog("d")
        log.close()
        Failures.report("speaker:hide", IOException("closed"), log)
        assertTrue(lines(log).isEmpty())
    }

    @Test
    fun `current is the shared instance and null once it is closed`() {
        val log = EvidenceLog.shared(File(tmp, "e"), logcat = {}, monoNanos = { 1L }).also { opened += it }
        assertSame(log, EvidenceLog.current())
        log.close()
        assertNull(EvidenceLog.current())
        assertNotNull(log)
    }
}
