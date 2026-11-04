// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [text] to `<dir>/<name>` so a reader never sees a half-written file: the text goes to `<name>.tmp` first and the
 * temp file then replaces the destination in one move. A failure deletes the temp file and is thrown as an [IOException];
 * it is never ignored. Pure JVM (no android.*).
 */
fun writeTextAtomic(dir: File, name: String, text: String) {
    val tmp = File(dir, "$name.tmp")
    val dest = File(dir, name)
    try {
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: IOException) {
        tmp.delete()
        throw e
    } catch (e: RuntimeException) {
        tmp.delete()
        throw IOException("could not replace $name: ${e.message}", e)
    }
}
