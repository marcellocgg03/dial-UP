package dev.dialup.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
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
}
