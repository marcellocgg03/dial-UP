// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import java.io.File
import java.io.FileOutputStream

/**
 * Append-only file with size-based rotation: current -> name.1 -> ... -> name.<keep>, the oldest
 * file is deleted on a rotation. With [maxAgeMs] above 0 a file whose last write is older than that is deleted as a whole
 * (checked when the log opens, at each rotation and at most once an hour while it is written). Pure JVM (no android.*).
 *
 * Not thread-safe: the owner calls it from one thread (EvidenceLog uses its single writer thread).
 */
class RotatingFile(
    val dir: File,
    val name: String,
    private val rotateBytes: Long,
    private val keep: Int = 2,
    private val maxAgeMs: Long = 0L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val current: File = File(dir, name)
    private var stream: FileOutputStream? = null
    private var size = 0L
    private var lastPurge = Long.MIN_VALUE

    /**
     * Deletes every file whose last write is more than [maxAgeMs] ago, the current one included (a file keeps its write time
     * when it is renamed, so a rotated file's age is the time of its newest line). Returns how many were deleted.
     */
    fun purgeOld(): Int {
        if (maxAgeMs <= 0) return 0
        val now = clock()
        lastPurge = now
        var deleted = 0
        for (f in allFiles()) {
            if (!f.exists() || now - f.lastModified() <= maxAgeMs) continue
            if (f == current) {
                closeQuietly()
                size = 0
            }
            if (f.delete()) deleted++
        }
        return deleted
    }

    fun append(bytes: ByteArray) {
        if (maxAgeMs > 0 && (stream == null || clock() - lastPurge >= PURGE_EVERY_MS)) purgeOld()
        var out = stream
        if (out == null) {
            dir.mkdirs()
            out = FileOutputStream(current, true)
            stream = out
            size = current.length()
        }
        if (size + bytes.size > rotateBytes) {
            out.close()
            stream = null
            rotate()
            purgeOld()
            dir.mkdirs()
            out = FileOutputStream(current, true)
            stream = out
            size = 0
        }
        try {
            out.write(bytes)
            out.flush()
            size += bytes.size
        } catch (t: Throwable) {
            // drop the stream so the next append reopens the file, then let the owner log it
            closeQuietly()
            throw t
        }
    }

    /** Deletes the current file and name.1..name.<keep> (fixed names only); returns how many existed. */
    fun clear(): Int {
        closeQuietly()
        size = 0
        var deleted = 0
        for (f in allFiles()) {
            if (f.exists() && f.delete()) deleted++
        }
        return deleted
    }

    fun close() {
        closeQuietly()
    }

    /** Existing files, oldest first (name.<keep> .. name.1, current). */
    fun files(): List<File> = allFiles().filter { it.exists() }

    private fun allFiles(): List<File> =
        (keep downTo 1).map { File(dir, "$name.$it") } + current

    private fun rotate() {
        File(dir, "$name.$keep").delete()
        for (i in keep - 1 downTo 1) {
            val from = File(dir, "$name.$i")
            if (from.exists()) from.renameTo(File(dir, "$name.${i + 1}"))
        }
        current.renameTo(File(dir, "$name.1"))
    }

    private companion object {
        const val PURGE_EVERY_MS = 60L * 60 * 1000
    }

    private fun closeQuietly() {
        try {
            stream?.close()
        } catch (_: Throwable) {
        }
        stream = null
    }
}

/**
 * Where the rotating event log lives: the app-private files directory. Nothing else on the device can read it; the shell
 * reads it with run-as on debug builds and users hand it over with Share log.
 */
fun eventLogDir(filesDir: File): File = File(filesDir, "evidence")
