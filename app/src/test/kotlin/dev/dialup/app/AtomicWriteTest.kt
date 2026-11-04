// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicWriteTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun createsThenReplacesTheFileAndLeavesNoTempFile() {
        writeTextAtomic(dir, "state.json", "first")
        assertEquals("first", File(dir, "state.json").readText())
        writeTextAtomic(dir, "state.json", "second")
        assertEquals("second", File(dir, "state.json").readText())
        assertFalse(File(dir, "state.json.tmp").exists())
        assertEquals(listOf("state.json"), dir.list()!!.toList())
    }

    @Test
    fun aFailedReplacementThrowsAndLeavesNoTempFile() {
        val dest = File(dir, "state.json")
        assertTrue(dest.mkdir())
        File(dest, "child").writeText("keeps the directory non-empty")
        assertFailsWith<IOException> { writeTextAtomic(dir, "state.json", "x") }
        assertFalse(File(dir, "state.json.tmp").exists())
    }

    @Test
    fun aReaderOnlySeesCompleteContents() {
        val a = "A".repeat(64 * 1024)
        val b = "B".repeat(64 * 1024)
        writeTextAtomic(dir, "state.json", a)
        repeat(200) { i ->
            val next = if (i % 2 == 0) b else a
            writeTextAtomic(dir, "state.json", next)
            val seen = File(dir, "state.json").readText()
            assertTrue(seen == a || seen == b, "saw a torn file of ${seen.length} chars")
        }
    }
}
