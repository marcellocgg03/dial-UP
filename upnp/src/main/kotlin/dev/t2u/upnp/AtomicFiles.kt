package dev.t2u.upnp

// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
//
// The one atomic text writer of the app: the app's own files (state.json, settings.json, the yt-dlp and update records)
// and the renderer's stores (ports, hidden and manual speakers, names, per-speaker data) all persist through it.
// Pure JVM (no android.*).

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * How [writeTextAtomic] behaves around the write itself. The two presets reproduce the two writers that existed before
 * they were merged, so no caller changes behaviour: use [RENDERER] or [APP], and `copy(atomicOnly = true)` for a file
 * that must never fall back to a plain replace (settings.json).
 *
 * @property createParentDirs create the missing parent directory before writing.
 * @property deleteTempOnFailure delete `<name>.tmp` when the write or the move fails.
 * @property wrapUncheckedAsIOException throw an unchecked exception from the move as an [IOException]; otherwise it
 * reaches the caller unwrapped.
 * @property atomicOnly where the file system cannot move atomically, fail instead of falling back to a plain replace; the
 * previous file stays untouched.
 */
data class AtomicWriteOptions(
    val createParentDirs: Boolean,
    val deleteTempOnFailure: Boolean,
    val wrapUncheckedAsIOException: Boolean,
    val atomicOnly: Boolean = false,
) {
    companion object {
        /** The renderer stores: create the directory, leave a failed temp file in place, let unchecked exceptions through. */
        val RENDERER = AtomicWriteOptions(createParentDirs = true, deleteTempOnFailure = false, wrapUncheckedAsIOException = false)

        /** The app files: the directory must exist, a failed temp file is deleted, unchecked exceptions become [IOException]. */
        val APP = AtomicWriteOptions(createParentDirs = false, deleteTempOnFailure = true, wrapUncheckedAsIOException = true)
    }
}

/**
 * Writes [text] to [file] so a reader never sees a half-written file: the text goes to `<name>.tmp` beside it first, is
 * forced to disk, and the temp file then replaces [file] in one move (ATOMIC_MOVE with REPLACE_EXISTING, falling back to REPLACE_EXISTING
 * alone where the file system refuses ATOMIC_MOVE, unless [AtomicWriteOptions.atomicOnly]). A failure is thrown, never
 * ignored; [options] says what else happens around it. There is one temp name per file, so callers serialise writes to the
 * same file.
 */
fun writeTextAtomic(file: File, text: String, options: AtomicWriteOptions) {
    writeTextAtomic(file, text, options) { from, to, copyOptions -> Files.move(from, to, *copyOptions) }
}

/** The same write with the move step passed in, so a test can refuse the atomic move without a second file system. */
fun writeTextAtomic(
    file: File,
    text: String,
    options: AtomicWriteOptions,
    move: (Path, Path, Array<CopyOption>) -> Unit,
) {
    val parent = file.absoluteFile.parentFile
    if (options.createParentDirs) parent?.mkdirs()
    val tmp = File(parent, file.name + ".tmp")
    try {
        // forced to disk before the move: after a power loss the renamed file must not be empty or short
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        try {
            move(tmp.toPath(), file.toPath(), arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
        } catch (e: AtomicMoveNotSupportedException) {
            if (options.atomicOnly) throw IOException("could not replace ${file.name} atomically: ${e.message}", e)
            move(tmp.toPath(), file.toPath(), arrayOf(StandardCopyOption.REPLACE_EXISTING))
        }
    } catch (e: IOException) {
        if (options.deleteTempOnFailure) tmp.delete()
        throw e
    } catch (e: RuntimeException) {
        if (options.deleteTempOnFailure) tmp.delete()
        if (options.wrapUncheckedAsIOException) throw IOException("could not replace ${file.name}: ${e.message}", e)
        throw e
    }
}
