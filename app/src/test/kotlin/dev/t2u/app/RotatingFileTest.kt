package dev.t2u.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotatingFileTest {
    @TempDir
    lateinit var tmp: File

    private fun payload(ch: Char, n: Int = 60) = ch.toString().repeat(n).toByteArray()

    @Test
    fun `rotation keeps current plus two older files and drops the oldest`() {
        val dir = File(tmp, "logs")
        val rf = RotatingFile(dir, "a.jsonl", rotateBytes = 100, keep = 2)
        rf.append(payload('A'))
        rf.append(payload('B'))
        assertEquals("B".repeat(60), File(dir, "a.jsonl").readText())
        assertEquals("A".repeat(60), File(dir, "a.jsonl.1").readText())
        rf.append(payload('C'))
        assertEquals("C".repeat(60), File(dir, "a.jsonl").readText())
        assertEquals("B".repeat(60), File(dir, "a.jsonl.1").readText())
        assertEquals("A".repeat(60), File(dir, "a.jsonl.2").readText())
        rf.append(payload('D'))
        assertEquals("D".repeat(60), File(dir, "a.jsonl").readText())
        assertEquals("C".repeat(60), File(dir, "a.jsonl.1").readText())
        assertEquals("B".repeat(60), File(dir, "a.jsonl.2").readText())
        assertFalse(File(dir, "a.jsonl.3").exists())
        rf.close()
    }

    @Test
    fun `the directory is created on the first append and append after clear starts a new file`() {
        val dir = File(tmp, "deep/er/logs")
        val rf = RotatingFile(dir, "a.jsonl", rotateBytes = 100, keep = 2)
        assertFalse(dir.exists())
        rf.append(payload('A', 10))
        assertTrue(File(dir, "a.jsonl").exists())
        assertEquals(1, rf.clear())
        assertFalse(File(dir, "a.jsonl").exists())
        rf.append(payload('Z', 5))
        assertEquals("Z".repeat(5), File(dir, "a.jsonl").readText())
        rf.close()
    }

    @Test
    fun `clear deletes only the log files and returns how many`() {
        val dir = File(tmp, "logs")
        val rf = RotatingFile(dir, "a.jsonl", rotateBytes = 100, keep = 2)
        rf.append(payload('A'))
        rf.append(payload('B'))
        rf.append(payload('C'))
        val other = File(dir, "state.json").also { it.writeText("{}") }
        assertEquals(3, rf.clear())
        assertTrue(other.exists())
        assertEquals("{}", other.readText())
        assertFalse(File(dir, "a.jsonl").exists())
        assertFalse(File(dir, "a.jsonl.1").exists())
        assertFalse(File(dir, "a.jsonl.2").exists())
        assertEquals(0, rf.clear())
        rf.close()
    }

    @Test
    fun `files lists existing files oldest first and current names dir slash name`() {
        val dir = File(tmp, "logs")
        val rf = RotatingFile(dir, "a.jsonl", rotateBytes = 100, keep = 2)
        assertEquals(File(dir, "a.jsonl"), rf.current)
        assertEquals(emptyList(), rf.files())
        rf.append(payload('A'))
        rf.append(payload('B'))
        rf.append(payload('C'))
        assertEquals(
            listOf(File(dir, "a.jsonl.2"), File(dir, "a.jsonl.1"), File(dir, "a.jsonl")),
            rf.files(),
        )
        rf.close()
    }

    @Test
    fun `logDirFor prefers the external directory and falls back to internal evidence`() {
        val ext = File(tmp, "ext")
        val int = File(tmp, "int")
        assertEquals(File(ext, "logs") to "external", logDirFor(ext, int))
        assertEquals(File(int, "evidence") to "internal", logDirFor(null, int))
    }
}
