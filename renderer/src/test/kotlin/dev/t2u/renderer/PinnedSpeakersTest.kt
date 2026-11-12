// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// The pinned-speakers store: tolerant of a missing or damaged file, capped, written atomically, order kept.

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinnedSpeakersTest {
    @TempDir lateinit var tempRoot: File

    // under the per-test @TempDir, so the JUnit extension removes it (deleteOnExit does not remove a directory that has files in it)
    private fun tempDir(): File = Files.createTempDirectory(tempRoot.toPath(), "pinned-").toFile()

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

    @Test
    @DisplayName("a failed write is warned about once and keeps the pin in memory; the next write that works saves it")
    fun failedWrite() = runBlocking {
        val dir = tempDir()
        // a non-empty directory where pinned.json should be: the rename onto it fails with an IOException
        val blocker = File(dir, "pinned.json").also { it.mkdirs() }
        File(blocker, "keep").writeText("x")
        val s = PinnedSpeakers(dir) { warnings.add(it) }
        assertTrue(s.add(pin("a")))
        assertEquals(listOf("a"), s.entries.value.map { it.udn })
        assertEquals(1, warnings.count { it.startsWith("Could not save") }, warnings.toString())

        File(blocker, "keep").delete()
        blocker.delete()
        assertTrue(s.add(pin("b")))
        assertEquals(listOf("a", "b"), store(dir).entries.value.map { it.udn }, "the write that works saves everything pinned so far")
    }

    @Test
    @DisplayName("many pins added at once are all kept in memory and in the file")
    fun concurrentAdds() = runBlocking {
        val dir = tempDir()
        val s = store(dir)
        val udns = (0 until 40).map { "u$it" }
        coroutineScope {
            udns.map { u -> async(Dispatchers.Default) { assertTrue(s.add(pin(u))) } }.awaitAll()
        }
        assertEquals(udns.toSet(), s.entries.value.map { it.udn }.toSet())
        assertEquals(udns.size, s.entries.value.size)
        assertEquals(udns.toSet(), store(dir).entries.value.map { it.udn }.toSet(), "the file holds every pin, not the last writer's view of some")
        assertTrue(warnings.isEmpty(), warnings.toString())
    }
}
