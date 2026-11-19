package dev.dialup.app

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
    fun `a file whose last write is older than the age limit is deleted, a newer one stays`() {
        val dir = File(tmp, "aged").also { it.mkdirs() }
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        val old = File(dir, "a.jsonl.2").also { it.writeText("old"); it.setLastModified(now - 8 * day) }
        val recent = File(dir, "a.jsonl.1").also { it.writeText("recent"); it.setLastModified(now - 6 * day) }
        val stale = File(dir, "a.jsonl").also { it.writeText("stale"); it.setLastModified(now - 9 * day) }
        val rf = RotatingFile(dir, "a.jsonl", rotateBytes = 1000, keep = 2, maxAgeMs = 7 * day, clock = { now })

        rf.append("fresh\n".toByteArray())

        assertFalse(old.exists(), "8 days old: deleted")
        assertTrue(recent.exists(), "6 days old: kept")
        assertEquals("fresh\n", File(dir, "a.jsonl").readText(), "a stale current file is replaced, its lines are not appended to")
        assertEquals(listOf("a.jsonl.1", "a.jsonl"), rf.files().map { it.name })
        rf.close()
    }

    @Test
    fun `without an age limit nothing is deleted by age`() {
        val dir = File(tmp, "unaged").also { it.mkdirs() }
        val f = File(dir, "a.jsonl.1").also { it.writeText("x"); it.setLastModified(1L) }
        val rf = RotatingFile(dir, "a.jsonl", rotateBytes = 1000, keep = 2)
        rf.append("y".toByteArray())
        assertTrue(f.exists())
        rf.close()
    }
}
