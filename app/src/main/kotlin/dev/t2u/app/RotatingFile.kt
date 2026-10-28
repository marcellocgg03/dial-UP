// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app

import java.io.File
import java.io.FileOutputStream

/**
 * Append-only file with size-based rotation: current -> name.1 -> ... -> name.<keep>, the oldest
 * file is deleted on a rotation (decision D-10: 3 files of 8 MB). Pure JVM (no android.*).
 *
 * Not thread-safe: the owner calls it from one thread (EvidenceLog uses its single writer thread).
 */
class RotatingFile(
    val dir: File,
    val name: String,
    private val rotateBytes: Long,
    private val keep: Int = 2,
) {
    val current: File = File(dir, name)
    private var stream: FileOutputStream? = null
    private var size = 0L

    fun append(bytes: ByteArray) {
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

    private fun closeQuietly() {
        try {
            stream?.close()
        } catch (_: Throwable) {
        }
        stream = null
    }
}

/**
 * Where the rotating event log lives: the app external files directory (readable by `adb pull`
 * without run-as, decision D-08) or, when that is unavailable, the internal evidence directory.
 */
fun logDirFor(external: File?, internal: File): Pair<File, String> =
    if (external != null) File(external, "logs") to "external" else File(internal, "evidence") to "internal"
