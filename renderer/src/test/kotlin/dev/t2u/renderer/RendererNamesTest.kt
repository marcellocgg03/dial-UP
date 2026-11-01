// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.renderer

// GUI rename (quick 261101-dcg, PD-4, T-dcg-01, T-dcg-02): the renderer-names store. Trimmed, validated, capped, atomic, and
// tolerant of a missing or damaged names.json.

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RendererNamesTest {
    private fun tempDir(): File = Files.createTempDirectory("names-").toFile().also { it.deleteOnExit() }

    private class Warnings : (String) -> Unit {
        val lines = CopyOnWriteArrayList<String>()
        override fun invoke(line: String) {
            lines.add(line)
        }
    }

    private fun store(dir: File, warnings: Warnings = Warnings()) = RendererNames(dir, warnings).load()

    @Test
    @DisplayName("a name is trimmed, saved in names.json and read back")
    fun aNameIsTrimmedSavedAndReadBack() = runBlocking {
        val dir = tempDir()
        val names = store(dir)

        assertTrue(names.set("uuid:1", "  Kitchen  "))

        assertEquals("Kitchen", names.get("uuid:1"))
        assertEquals(mapOf("uuid:1" to "Kitchen"), names.entries.value)
        assertTrue(File(dir, "names.json").exists())
        assertEquals("Kitchen", store(dir).get("uuid:1"))
    }

    @Test
    @DisplayName("blank, over-long and control-character names are refused")
    fun refusedNames() = runBlocking {
        val dir = tempDir()
        val w = Warnings()
        val names = store(dir, w)
        val bad = listOf("", "   ", "a".repeat(64), "Kitchen\nBath", "Kitchen\tBath")

        for (text in bad) assertFalse(names.set("uuid:1", text), "refused: '$text'")

        assertTrue(names.entries.value.isEmpty())
        assertFalse(File(dir, "names.json").exists())
        assertEquals(bad.size, w.lines.size, w.lines.toString())
        assertEquals("Enter a name", rendererNameProblem(""))
        assertEquals("At most 63 characters", rendererNameProblem("a".repeat(64)))
        assertEquals("No line breaks or tabs", rendererNameProblem("Kitchen\nBath"))
        assertNull(rendererNameProblem("Kitchen"))
    }

    @Test
    @DisplayName("63 characters are accepted and 64 are refused, an emoji counting once")
    fun lengthIsCountedInCodePoints() {
        val speaker = "🔊"
        assertNull(rendererNameProblem("a".repeat(63)))
        assertNull(rendererNameProblem(speaker + "a".repeat(62)))
        assertEquals(64, (speaker + "a".repeat(62)).length)
        assertEquals("At most 63 characters", rendererNameProblem(speaker + "a".repeat(63)))
    }

    @Test
    @DisplayName("remove forgets a name and writes the file")
    fun removeForgetsAName() = runBlocking {
        val dir = tempDir()
        val names = store(dir)
        names.set("uuid:1", "Kitchen")

        assertTrue(names.remove("uuid:1"))

        assertNull(names.get("uuid:1"))
        assertTrue(store(dir).entries.value.isEmpty())
        assertFalse(names.remove("uuid:1"))
    }

    @Test
    @DisplayName("a damaged names.json or a bad entry is ignored with a warning")
    fun damagedFileIsIgnored() {
        val notJson = tempDir()
        File(notJson, "names.json").writeText("{ not json")
        val w1 = Warnings()
        assertTrue(store(notJson, w1).entries.value.isEmpty())
        assertEquals(1, w1.lines.size, w1.lines.toString())

        val array = tempDir()
        File(array, "names.json").writeText("[1, 2]")
        val w2 = Warnings()
        assertTrue(store(array, w2).entries.value.isEmpty())
        assertEquals(1, w2.lines.size, w2.lines.toString())

        val mixed = tempDir()
        File(mixed, "names.json").writeText(
            """
            {
              "good": {"name": "Kitchen"},
              "number": {"name": 5},
              "long": {"name": "${"a".repeat(100)}"}
            }
            """.trimIndent(),
        )
        val w3 = Warnings()
        val names = store(mixed, w3)
        assertEquals(mapOf("good" to "Kitchen"), names.entries.value)
        assertEquals(2, w3.lines.size, w3.lines.toString())
    }

    @Test
    @DisplayName("a user name wins over the described name")
    fun aUserNameWins() {
        assertEquals("D", shownRendererName(null, "D"))
        assertEquals("Kitchen", shownRendererName("Kitchen", "D"))
        assertEquals("D", shownRendererName("   ", "D"))
    }
}
