package dev.t2u.upnp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicFilesTest {
    @TempDir
    lateinit var dir: File

    private val app = AtomicWriteOptions.APP
    private val renderer = AtomicWriteOptions.RENDERER

    // A move step that refuses the atomic move, the way a file system without ATOMIC_MOVE does.
    private fun refusingAtomicMove(): (Path, Path, Array<CopyOption>) -> Unit = { from, to, options ->
        if (StandardCopyOption.ATOMIC_MOVE in options) throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "refused")
        Files.move(from, to, *options)
    }

    @Test
    fun appPresetCreatesThenReplacesTheFileAndLeavesNoTempFile() {
        val f = File(dir, "state.json")
        writeTextAtomic(f, "first", app)
        assertEquals("first", f.readText())
        writeTextAtomic(f, "second", app)
        assertEquals("second", f.readText())
        assertFalse(File(dir, "state.json.tmp").exists())
        assertEquals(listOf("state.json"), dir.list()!!.toList())
    }

    @Test
    fun theTempFileHoldsTheWholeTextWhenTheMoveStepRunsAndTheFileIsUtf8() {
        val target = File(dir, "a.json")
        val text = "{\"name\":\"caf\u00e9 \ud83d\udd0a\"}"
        var seenAtMove: String? = null
        writeTextAtomic(target, text, app) { from, to, options ->
            seenAtMove = from.toFile().readText(Charsets.UTF_8)
            Files.move(from, to, *options)
        }
        assertEquals(text, seenAtMove, "the data is complete before the rename")
        assertEquals(text, target.readText(Charsets.UTF_8))
    }

    @Test
    fun appPresetFailedReplacementThrowsAndLeavesNoTempFile() {
        val dest = File(dir, "state.json")
        assertTrue(dest.mkdir())
        File(dest, "child").writeText("keeps the directory non-empty")
        assertFailsWith<IOException> { writeTextAtomic(dest, "x", app) }
        assertFalse(File(dir, "state.json.tmp").exists())
    }

    @Test
    fun appPresetReaderOnlySeesCompleteContents() {
        val f = File(dir, "state.json")
        val a = "A".repeat(64 * 1024)
        val b = "B".repeat(64 * 1024)
        writeTextAtomic(f, a, app)
        repeat(200) { i ->
            val next = if (i % 2 == 0) b else a
            writeTextAtomic(f, next, app)
            val seen = f.readText()
            assertTrue(seen == a || seen == b, "saw a torn file of ${seen.length} chars")
        }
    }

    @Test
    fun appPresetDoesNotCreateAMissingParentDirectory() {
        val missing = File(dir, "nope")
        assertFailsWith<IOException> { writeTextAtomic(File(missing, "state.json"), "x", app) }
        assertFalse(missing.exists())
    }

    @Test
    fun appPresetThrowsAnUncheckedFailureOfTheMoveAsIOExceptionAndLeavesNoTempFile() {
        val f = File(dir, "state.json")
        val e = assertFailsWith<IOException> {
            writeTextAtomic(f, "x", app) { _, _, _ -> throw IllegalStateException("boom") }
        }
        assertTrue(e.cause is IllegalStateException)
        assertFalse(File(dir, "state.json.tmp").exists())
    }

    @Test
    fun rendererPresetCreatesAMissingParentDirectory() {
        val f = File(File(dir, "a/b"), "ports.json")
        writeTextAtomic(f, "{}", renderer)
        assertEquals("{}", f.readText())
        assertFalse(File(f.parentFile, "ports.json.tmp").exists())
    }

    @Test
    fun rendererPresetLeavesTheTempFileWhenTheMoveFailsAndThrowsIOException() {
        val dest = File(dir, "ports.json")
        assertTrue(dest.mkdir())
        File(dest, "child").writeText("keeps the directory non-empty")
        assertFailsWith<IOException> { writeTextAtomic(dest, "x", renderer) }
        assertTrue(File(dir, "ports.json.tmp").exists())
    }

    @Test
    fun rendererPresetLetsAnUncheckedFailureOfTheMoveThroughUnwrapped() {
        val f = File(dir, "ports.json")
        assertFailsWith<IllegalStateException> {
            writeTextAtomic(f, "x", renderer) { _, _, _ -> throw IllegalStateException("boom") }
        }
    }

    @Test
    fun atomicOnlyRefusedMoveThrowsKeepsTheOldFileAndLeavesNoTempFile() {
        val f = File(dir, "settings.json")
        writeTextAtomic(f, "old", app)
        assertFailsWith<IOException> { writeTextAtomic(f, "new", app.copy(atomicOnly = true), refusingAtomicMove()) }
        assertEquals("old", f.readText())
        assertFalse(File(dir, "settings.json.tmp").exists())
    }

    @Test
    fun withoutAtomicOnlyARefusedMoveFallsBackToThePlainReplaceForBothPresets() {
        for (options in listOf(app, renderer)) {
            val f = File(dir, "state-${options.createParentDirs}.json")
            writeTextAtomic(f, "old", options)
            writeTextAtomic(f, "new", options, refusingAtomicMove())
            assertEquals("new", f.readText())
            assertFalse(File(dir, f.name + ".tmp").exists())
        }
    }

    @Test
    fun atomicOnlyAlsoRefusesForTheRendererPreset() {
        val f = File(dir, "ports.json")
        writeTextAtomic(f, "old", renderer)
        assertFailsWith<IOException> { writeTextAtomic(f, "new", renderer.copy(atomicOnly = true), refusingAtomicMove()) }
        assertEquals("old", f.readText())
    }
}
