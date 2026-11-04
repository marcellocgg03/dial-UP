package dev.dialup.app

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EvidenceLogTest {
    @TempDir
    lateinit var tmp: File

    private val opened = mutableListOf<EvidenceLog>()

    @AfterEach
    fun closeAll() {
        opened.forEach { it.close() }
    }

    private fun writers(): Int = Thread.getAllStackTraces().keys.count { it.name == "t2u-evidence" && it.isAlive }

    private fun waitFor(ms: Long = 2000, cond: () -> Boolean): Boolean {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    private fun shared(dir: File): EvidenceLog = EvidenceLog.shared(dir, logcat = {}).also { opened += it }

    @Test
    fun `close ends the writer thread and is idempotent`() {
        val before = writers()
        val log = EvidenceLog(File(tmp, "a"), monoNanos = { 1L }, logcat = {})
        log.event("probe")
        log.flush()
        assertEquals(before + 1, writers())
        log.close()
        assertTrue(waitFor { writers() == before }, "writer thread still alive after close")
        log.close()
        assertEquals(before, writers())
    }

    @Test
    fun `event after close does not throw and writes nothing`() {
        val dir = File(tmp, "b")
        val log = EvidenceLog(dir, monoNanos = { 1L }, logcat = {})
        log.event("one")
        log.flush()
        val size = log.currentFile().length()
        assertTrue(size > 0)
        log.close()
        log.event("two")
        log.flush()
        log.clear()
        assertEquals(size, log.currentFile().length())
    }

    @Test
    fun `shared returns one object per directory with one writer thread`() {
        val before = writers()
        val dir = File(tmp, "c")
        val first = shared(dir)
        first.event("probe")
        first.flush()
        val second = shared(dir)
        assertSame(first, second)
        assertEquals(before + 1, writers())

        val other = shared(File(tmp, "d"))
        assertNotSame(first, other)
        other.event("probe")
        other.flush()
        assertTrue(waitFor { writers() == before + 1 }, "previous shared log was not closed")
    }

    @Test
    fun `shared after close makes a new live log`() {
        val dir = File(tmp, "e")
        val first = shared(dir)
        first.close()
        val second = shared(dir)
        assertNotSame(first, second)
        second.event("alive")
        second.flush()
        assertTrue(second.currentFile().length() > 0)
    }

    @Test
    fun `two shared calls write one file in order`() {
        val dir = File(tmp, "f")
        shared(dir).event("first")
        shared(dir).event("second")
        val log = shared(dir)
        log.flush()
        val lines = log.currentFile().readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"kind\":\"first\""))
        assertTrue(lines[1].contains("\"kind\":\"second\""))
    }

    @Test
    fun `event log directory and file name`() {
        val files = File(tmp, "files")
        assertEquals(File(files, "evidence"), eventLogDir(files))
        assertEquals("dialup-events.jsonl", EvidenceLog.FILE_NAME)
    }
}
