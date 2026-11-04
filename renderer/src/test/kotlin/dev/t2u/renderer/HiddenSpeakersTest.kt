// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// GUI-03 (plan 07-11, D-08): the hidden-speakers store. Tolerant of a missing or damaged file, capped, written atomically.

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiddenSpeakersTest {
    private fun tempDir(): File = Files.createTempDirectory("hidden-").toFile().also { it.deleteOnExit() }

    private class Warnings : (String) -> Unit {
        val lines = CopyOnWriteArrayList<String>()
        override fun invoke(line: String) {
            lines.add(line)
        }
    }

    private fun store(dir: File, warnings: Warnings = Warnings()) = HiddenSpeakers(dir, warnings).load()

    private fun speaker(udn: String) = HiddenSpeaker(udn, "Name $udn", "192.168.1.7")

    @Test
    @DisplayName("a missing hidden.json loads empty without a warning")
    fun missingFileIsEmptyAndSilent() {
        val w = Warnings()
        val s = store(tempDir(), w)
        assertTrue(s.entries.value.isEmpty())
        assertFalse(s.contains("a"))
        assertTrue(w.lines.isEmpty(), w.lines.toString())
    }

    @Test
    @DisplayName("malformed JSON loads empty with one warning")
    fun malformedJsonIsEmptyWithOneWarning() {
        val dir = tempDir()
        File(dir, "hidden.json").writeText("{ not json")
        val w = Warnings()
        val s = store(dir, w)
        assertTrue(s.entries.value.isEmpty())
        assertEquals(1, w.lines.size, w.lines.toString())
    }

    @Test
    @DisplayName("a file that is not a JSON object loads empty with one warning")
    fun wrongShapeIsEmptyWithOneWarning() {
        val dir = tempDir()
        File(dir, "hidden.json").writeText("[1, 2]")
        val w = Warnings()
        assertTrue(store(dir, w).entries.value.isEmpty())
        assertEquals(1, w.lines.size, w.lines.toString())
    }

    @Test
    @DisplayName("an entry without a string name or address is dropped with a warning and the others load")
    fun badEntriesAreDropped() {
        val dir = tempDir()
        File(dir, "hidden.json").writeText(
            """
            {
              "good": {"name": "Kitchen", "address": "192.168.1.20"},
              "noName": {"address": "192.168.1.21"},
              "noAddress": {"name": "Lamp"},
              "numeric": {"name": 5, "address": "192.168.1.22"},
              "notAnObject": "x",
              "alsoGood": {"name": "Deck", "address": "192.168.1.23"}
            }
            """.trimIndent(),
        )
        val w = Warnings()
        val s = store(dir, w)
        assertEquals(listOf("good", "alsoGood"), s.entries.value.map { it.udn })
        assertEquals(HiddenSpeaker("good", "Kitchen", "192.168.1.20"), s.entries.value.first())
        assertEquals(4, w.lines.size, w.lines.toString())
        assertTrue(s.contains("good") && !s.contains("noName"))
    }

    @Test
    @DisplayName("add then a fresh load on the same directory returns the entry")
    fun addSurvivesALoad() = runBlocking {
        val dir = tempDir()
        val first = store(dir)
        assertTrue(first.add(speaker("a")))
        assertTrue(first.contains("a"))
        assertEquals(listOf(speaker("a")), first.entries.value)

        val second = store(dir)
        assertEquals(listOf(speaker("a")), second.entries.value)
        assertTrue(second.contains("a"))
    }

    @Test
    @DisplayName("remove persists and says whether the speaker was hidden")
    fun removePersists() = runBlocking {
        val dir = tempDir()
        val first = store(dir)
        first.add(speaker("a"))
        first.add(speaker("b"))

        assertTrue(first.remove("a"))
        assertFalse(first.remove("a"))
        assertFalse(first.remove("never-hidden"))

        assertEquals(listOf("b"), store(dir).entries.value.map { it.udn })
    }

    @Test
    @DisplayName("hiding the same speaker twice keeps one entry with the newest name")
    fun addTwiceKeepsOneEntry() = runBlocking {
        val s = store(tempDir())
        s.add(HiddenSpeaker("a", "Old", "192.168.1.1"))
        s.add(HiddenSpeaker("a", "New", "192.168.1.2"))
        assertEquals(listOf(HiddenSpeaker("a", "New", "192.168.1.2")), s.entries.value)
    }

    @Test
    @DisplayName("more than MAX_HIDDEN adds keep the first 128 and warn")
    fun capKeepsTheFirst128() = runBlocking {
        val dir = tempDir()
        val w = Warnings()
        val s = store(dir, w)
        for (i in 0 until MAX_HIDDEN + 5) s.add(speaker("s$i"))
        assertEquals(MAX_HIDDEN, s.entries.value.size)
        assertEquals("s0", s.entries.value.first().udn)
        assertFalse(s.contains("s$MAX_HIDDEN"))
        assertFalse(s.add(speaker("one-more")))
        assertTrue(w.lines.isNotEmpty())
        assertEquals(MAX_HIDDEN, store(dir).entries.value.size)
        // a speaker that is already hidden can still be refreshed at the cap
        assertTrue(s.add(HiddenSpeaker("s0", "Renamed", "192.168.1.9")))
    }

    @Test
    @DisplayName("no hidden.json.tmp is left behind")
    fun noTempFileLeft() = runBlocking {
        val dir = tempDir()
        val s = store(dir)
        s.add(speaker("a"))
        s.remove("a")
        s.add(speaker("b"))
        assertTrue(File(dir, "hidden.json").exists())
        assertFalse(File(dir, "hidden.json.tmp").exists())
    }

    @Test
    @DisplayName("a write that fails is a warning, and the speaker is still hidden in memory")
    fun failedWriteWarns() = runBlocking {
        val dir = tempDir()
        // hidden.json is a directory, so the move over it fails
        File(dir, "hidden.json").mkdirs()
        File(dir, "hidden.json/keep").writeText("x")
        val w = Warnings()
        val s = HiddenSpeakers(dir, w)
        s.add(speaker("a"))
        assertTrue(s.contains("a"))
        assertTrue(w.lines.any { it.contains("Could not save") }, w.lines.toString())
    }
}
