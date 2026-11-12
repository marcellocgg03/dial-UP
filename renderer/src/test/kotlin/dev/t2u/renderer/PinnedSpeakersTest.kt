// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// The pinned-speakers store: tolerant of a missing or damaged file, capped, written atomically, order kept.

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinnedSpeakersTest {
    private fun tempDir(): File = Files.createTempDirectory("pinned-").toFile().also { it.deleteOnExit() }

    private val warnings = CopyOnWriteArrayList<String>()

    private fun store(dir: File) = PinnedSpeakers(dir) { warnings.add(it) }.load()

    private fun pin(udn: String) = PinnedSpeaker(udn, "Name $udn", "192.168.1.7")

    @Test
    @DisplayName("a missing pinned.json loads empty without a warning")
    fun missingFileIsEmptyAndSilent() {
        assertTrue(store(tempDir()).entries.value.isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    @DisplayName("pins persist in the order they were made and a repeat keeps its place")
    fun persistsInOrder() = runBlocking {
        val dir = tempDir()
        val s = store(dir)
        assertTrue(s.add(pin("b")))
        assertTrue(s.add(pin("a")))
        assertTrue(s.add(PinnedSpeaker("b", "Renamed", "192.168.1.9")))
        val again = store(dir).entries.value
        assertEquals(listOf("b", "a"), again.map { it.udn })
        assertEquals("Renamed", again.first().name)
    }

    @Test
    @DisplayName("unpinning removes the entry from the file; unpinning an unpinned speaker answers false")
    fun removes() = runBlocking {
        val dir = tempDir()
        val s = store(dir)
        s.add(pin("a"))
        assertTrue(s.remove("a"))
        assertFalse(s.remove("a"))
        assertTrue(store(dir).entries.value.isEmpty())
    }

    @Test
    @DisplayName("a damaged file loads empty with one warning; an entry without a name or address is dropped")
    fun damagedFile() {
        val dir = tempDir()
        File(dir, "pinned.json").writeText("{nope")
        assertTrue(store(dir).entries.value.isEmpty())
        assertEquals(1, warnings.size)

        warnings.clear()
        File(dir, "pinned.json").writeText("""{"a": {"name": "A"}, "b": {"name": "B", "address": "10.0.0.2"}}""")
        assertEquals(listOf("b"), store(dir).entries.value.map { it.udn })
        assertEquals(1, warnings.size)
    }

    @Test
    @DisplayName("no more than MAX_PINNED speakers are pinned")
    fun capped() = runBlocking {
        val s = store(tempDir())
        repeat(MAX_PINNED) { assertTrue(s.add(pin("u$it"))) }
        assertFalse(s.add(pin("extra")))
        assertTrue(s.add(pin("u0")), "an already pinned speaker can still be refreshed")
    }
}
