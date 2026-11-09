// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writes [text] to `<dir>/<name>` so a reader never sees a half-written file: the text goes to `<name>.tmp` first and the
 * temp file then replaces the destination in one move. A failure deletes the temp file and is thrown as an [IOException];
 * it is never ignored. Where the file system cannot move atomically the move falls back to a plain replace, unless
 * [atomicOnly] is set: then that case is a failure too and the previous file stays untouched. Pure JVM (no android.*).
 */
fun writeTextAtomic(dir: File, name: String, text: String, atomicOnly: Boolean = false) {
    writeTextAtomic(dir, name, text, atomicOnly) { from, to, options -> Files.move(from, to, *options) }
}

/** The same write with the move step passed in, so a test can refuse the atomic move without a second file system. */
internal fun writeTextAtomic(
    dir: File,
    name: String,
    text: String,
    atomicOnly: Boolean,
    move: (Path, Path, Array<CopyOption>) -> Unit,
) {
    val tmp = File(dir, "$name.tmp")
    val dest = File(dir, name)
    try {
        tmp.writeText(text)
        try {
            move(tmp.toPath(), dest.toPath(), arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
        } catch (e: AtomicMoveNotSupportedException) {
            if (atomicOnly) throw IOException("could not replace $name atomically: ${e.message}", e)
            move(tmp.toPath(), dest.toPath(), arrayOf(StandardCopyOption.REPLACE_EXISTING))
        }
    } catch (e: IOException) {
        tmp.delete()
        throw e
    } catch (e: RuntimeException) {
        tmp.delete()
        throw IOException("could not replace $name: ${e.message}", e)
    }
}
