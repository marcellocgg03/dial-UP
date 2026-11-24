package dev.dialup.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogArchiveTest {
    @TempDir
    lateinit var tmp: File

    private fun line(ts: String) = """{"ts":"$ts","mono":1,"kind":"x"}""" + "\n"

    @Test
    fun `the zip holds the summary then every log file oldest first under its own name`() {
        val dir = File(tmp, "evidence").also { it.mkdirs() }
        File(dir, "dialup-events.jsonl.2").writeText(line("2026-11-17T08:00:00.000-05:00") + line("2026-11-17T09:00:00.000-05:00"))
        File(dir, "dialup-events.jsonl.1").writeText(line("2026-11-18T08:00:00.000-05:00") + line("2026-11-18T23:59:59.999-05:00"))
        File(dir, "dialup-events.jsonl").writeText(line("2026-11-19T00:00:00.000-05:00") + line("2026-11-19T10:42:46.584-05:00"))
        File(dir, "state.json").writeText("never shared")

        val logs = LogArchive.logFiles(dir)
        assertEquals(listOf("dialup-events.jsonl.2", "dialup-events.jsonl.1", "dialup-events.jsonl"), logs.map { it.name })

        val zipFile = File(dir, LogArchive.ZIP_NAME)
        assertEquals(3, LogArchive.build(logs, zipFile))
        ZipFile(zipFile).use { z ->
            assertEquals(
                listOf("SUMMARY.txt", "dialup-events.jsonl.2", "dialup-events.jsonl.1", "dialup-events.jsonl"),
                z.entries().toList().map { it.name },
            )
            assertEquals(logs[2].readText(), z.getInputStream(z.getEntry("dialup-events.jsonl")).readBytes().decodeToString())
            val summary = z.getInputStream(z.getEntry("SUMMARY.txt")).readBytes().decodeToString()
            assertTrue("dialup-events.jsonl.2" in summary && "first=2026-11-17T08:00:00.000-05:00  last=2026-11-17T09:00:00.000-05:00" in summary, summary)
            assertTrue("first=2026-11-19T00:00:00.000-05:00  last=2026-11-19T10:42:46.584-05:00" in summary, summary)
            assertTrue("state.json" !in summary)
        }
    }

    @Test
    fun `an empty or missing log gives no files and building again replaces the old zip`() {
        val dir = File(tmp, "e2").also { it.mkdirs() }
        File(dir, "dialup-events.jsonl").writeText("")
        assertEquals(emptyList(), LogArchive.logFiles(dir))
        File(dir, "dialup-events.jsonl").writeText(line("2026-11-19T10:00:00.000-05:00"))
        val z = File(dir, LogArchive.ZIP_NAME)
        LogArchive.build(LogArchive.logFiles(dir), z)
        LogArchive.build(LogArchive.logFiles(dir), z)
        ZipFile(z).use { assertEquals(2, it.size()) }
    }

    @Test
    fun `prepare deletes an old zip and gives null when there is no log`() {
        val dir = File(tmp, "e3").also { it.mkdirs() }
        val zip = File(dir, LogArchive.ZIP_NAME).apply { writeText("old") }
        var flushed = false
        assertEquals(null, LogArchive.prepare(dir) { flushed = true })
        assertFalse(zip.exists())
        assertTrue(flushed)
    }

    @Test
    fun `prepare flushes first and builds the zip from every log file`() {
        val dir = File(tmp, "e4").also { it.mkdirs() }
        File(dir, "dialup-events.jsonl.1").writeText(line("2026-11-18T08:00:00.000-05:00"))
        val current = File(dir, "dialup-events.jsonl").apply { writeText(line("2026-11-19T00:00:00.000-05:00")) }
        val flushedLine = line("2026-11-19T10:00:00.000-05:00")
        val out = LogArchive.prepare(dir) { current.appendText(flushedLine) }
        assertEquals(File(dir, LogArchive.ZIP_NAME), out)
        ZipFile(out!!).use { z ->
            assertEquals(listOf("SUMMARY.txt", "dialup-events.jsonl.1", "dialup-events.jsonl"), z.entries().toList().map { it.name })
            val body = z.getInputStream(z.getEntry("dialup-events.jsonl")).readBytes().decodeToString()
            assertTrue(flushedLine in body, "the flush ran before the files were read")
        }
    }

    @Test
    fun `concurrentBuildsLeaveOneCompleteZip`() {
        val dir = File(tmp, "e5").also { it.mkdirs() }
        File(dir, "dialup-events.jsonl.1").writeText(line("2026-11-18T08:00:00.000-05:00").repeat(2000))
        File(dir, "dialup-events.jsonl").writeText(line("2026-11-19T00:00:00.000-05:00").repeat(2000))
        val expected = listOf("SUMMARY.txt", "dialup-events.jsonl.1", "dialup-events.jsonl")
        val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = (1..2).map {
            Thread {
                start.await()
                repeat(20) {
                    try {
                        val zip = LogArchive.prepare(dir) {}
                        val names = ZipFile(zip!!).use { z -> z.entries().toList().map { e -> e.name } }
                        if (names != expected) failures.add("entries $names")
                    } catch (e: Exception) {
                        failures.add(e::class.simpleName ?: "?")
                    }
                }
            }.also { it.start() }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(emptyList(), failures.toList())
        val left = dir.list()!!.sorted()
        assertEquals(listOf("dialup-events.jsonl", "dialup-events.jsonl.1", LogArchive.ZIP_NAME), left)
    }

    @Test
    fun `aFailedBuildKeepsThePreviousZip`() {
        val dir = File(tmp, "e6").also { it.mkdirs() }
        val log = File(dir, "dialup-events.jsonl").apply { writeText(line("2026-11-19T10:00:00.000-05:00")) }
        val zip = File(dir, LogArchive.ZIP_NAME)
        LogArchive.build(listOf(log), zip)
        val before = zip.readBytes()
        val missing = File(dir, "gone.jsonl")
        var threw = false
        try {
            LogArchive.build(listOf(log, missing), zip)
        } catch (_: java.io.IOException) {
            threw = true
        }
        assertTrue(threw, "an unreadable input fails the build")
        assertTrue(before.contentEquals(zip.readBytes()), "the previous zip is intact")
        assertEquals(listOf("dialup-events.jsonl", LogArchive.ZIP_NAME), dir.list()!!.sorted())
    }
}
