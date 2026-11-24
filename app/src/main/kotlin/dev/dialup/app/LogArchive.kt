// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The one file Share log hands over: a zip holding every event-log file, oldest first, under their own names, behind a
 * `SUMMARY.txt` that lists each file with its first and last timestamp. The event log is JSON lines, so it compresses well and
 * the whole history (up to [EvidenceLog.KEEP_ROTATED] rotated files) stays small enough to attach. Pure JVM (no android.*).
 */
object LogArchive {
    const val ZIP_NAME = "dialup-log.zip"
    const val SUMMARY_NAME = "SUMMARY.txt"
    private val TS = Regex("\"ts\":\"([^\"]+)\"")

    /** The log files in [dir], oldest first: name.<keep> .. name.1, then the current file. Only files that exist. */
    fun logFiles(dir: File, name: String = EvidenceLog.FILE_NAME, keep: Int = EvidenceLog.KEEP_ROTATED): List<File> =
        ((keep downTo 1).map { File(dir, "$name.$it") } + File(dir, name)).filter { it.isFile && it.length() > 0 }

    /** Writes [files] and the summary to [out] (replacing it). Returns how many log files went in. */
    fun build(files: List<File>, out: File): Int {
        out.parentFile?.mkdirs()
        out.delete()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(SUMMARY_NAME))
            zip.write(summary(files).toByteArray())
            zip.closeEntry()
            for (f in files) {
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return files.size
    }

    /**
     * The whole of Share log up to the share sheet: runs [flush] (so the file is complete), lists the log files in [dir] and
     * builds the zip at `dir/ZIP_NAME`. Returns the zip, or null when there is no log (an old zip is deleted then, so nothing
     * stale is ever offered). Blocking file work (the flush waits up to 2 s): call it off the main thread.
     */
    fun prepare(dir: File, flush: () -> Unit): File? {
        flush()
        val zip = File(dir, ZIP_NAME)
        val logs = logFiles(dir)
        if (logs.isEmpty()) {
            zip.delete()
            return null
        }
        build(logs, zip)
        return zip
    }

    /** One line per file, oldest first: name, size and the `ts` of its first and last line. */
    fun summary(files: List<File>): String = buildString {
        appendLine("DialUP event log. Files are listed oldest first; the file without a number is the newest.")
        appendLine("Every line has a \"ts\" (local time with UTC offset); files never overlap in time.")
        appendLine()
        for (f in files) {
            appendLine("${f.name}  ${f.length()} bytes  first=${firstTs(f) ?: "-"}  last=${lastTs(f) ?: "-"}")
        }
    }

    private fun tsOf(line: String?): String? = line?.let { TS.find(it)?.groupValues?.get(1) }

    private fun firstTs(f: File): String? = runCatching { f.bufferedReader().use { r -> tsOf(r.readLine()) } }.getOrNull()

    /** The `ts` of the last complete line, read from the end of the file (the last 16 KiB, which holds at least one line of at most 8 KiB). */
    private fun lastTs(f: File): String? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val n = minOf(raf.length(), 16L * 1024).toInt()
            raf.seek(raf.length() - n)
            val buf = ByteArray(n)
            raf.readFully(buf)
            String(buf, Charsets.UTF_8).lines().lastOrNull { it.isNotBlank() }?.let { tsOf(it) }
        }
    }.getOrNull()
}
