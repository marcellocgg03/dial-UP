package dev.t2u.media

// Kotlin-only, no Node counterpart (Node's `#pipe` ignores Range), GPL-3.0-only.
//
// A bounded per-token store of finished ffmpeg pipe runs, so a ranged GET on a live video pipe token (what the
// SYMFONISK Lamp sends after an early Pause: `Range: bytes=N-`, plan 02-16 G4) can be answered with a correct
// 206 from the bytes the speaker already received, without a new ffmpeg run. Every method here is best effort:
// a cache problem returns null/false and must never throw into the live stream.

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The one switch for answering `Range` on live video pipe tokens from the bytes of the first run (plan 02-16).
 * A FAIL of the 02-16 Lamp retest sets this to false (range answering off, today's behaviour).
 */
const val PIPE_RANGE_ANSWERING_DEFAULT = true

/** Limits of the range cache. [enabled] false means no tee, no cached answer, no cold fill. */
data class PipeRangeConfig(
    val enabled: Boolean = PIPE_RANGE_ANSWERING_DEFAULT,
    val maxBytesPerToken: Long = 32L * 1024 * 1024,
    val maxBytesTotal: Long = 64L * 1024 * 1024,
    val coldFillTimeoutMs: Long = 8000,
    val maxConcurrentParts: Int = 2,
) {
    init {
        require(maxBytesPerToken > 0) { "maxBytesPerToken must be positive" }
        require(maxBytesTotal > 0) { "maxBytesTotal must be positive" }
        require(maxBytesPerToken <= maxBytesTotal) { "maxBytesPerToken must not exceed maxBytesTotal" }
        require(coldFillTimeoutMs > 0) { "coldFillTimeoutMs must be positive" }
        require(maxConcurrentParts > 0) { "maxConcurrentParts must be positive" }
    }
}

/** A complete stream on disk: [size] is the byte total the first run produced. */
class CachedStream(val file: File, val size: Long)

data class PipeRangeStats(val entries: Int, val bytes: Long)

/**
 * Finished runs under [dir], keyed by token. Token strings are the 32 lowercase hex characters the media server
 * already validated, so they are safe as file names. The directory is created on first use.
 */
class PipeRangeCache(private val dir: File, private val config: PipeRangeConfig) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, CachedStream>()

    /** The complete stream for [token], or null. */
    fun lookup(token: String): CachedStream? = synchronized(lock) {
        val cached = entries[token] ?: return null
        if (!cached.file.exists()) {
            entries.remove(token)
            return null
        }
        cached
    }

    /** A new empty part file in [dir], or null when the directory cannot be created or written. */
    fun newPartFile(): File? = try {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) null else File.createTempFile("run-", ".part", dir)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /**
     * Moves the finished [part] to [token]'s final name and returns it. A token that is already cached keeps its
     * first stream (the part is deleted); an empty part is deleted and gives null.
     */
    fun promote(token: String, part: File): CachedStream? = synchronized(lock) {
        entries[token]?.let { existing ->
            part.delete()
            return existing
        }
        val size = part.length()
        if (size <= 0L) {
            part.delete()
            return null
        }
        val target = File(dir, "$token.bin")
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            part.delete()
            return null
        }
        CachedStream(target, size).also { entries[token] = it }
    }

    /** A tee that copies a live run for [token] into a part file, or null when the cache cannot take it. */
    fun openTee(token: String): TeeFill? {
        if (!config.enabled) return null
        val part = newPartFile() ?: return null
        return try {
            TeeFill(this, token, part, FileOutputStream(part), config.maxBytesPerToken)
        } catch (_: IOException) {
            part.delete()
            null
        }
    }

    // STUBS (plan 02-16 Task 2, RED step): names only, behaviour arrives with the next commit.
    fun drop(token: String) {}

    fun isOversize(token: String): Boolean = false

    fun markOversize(token: String) {}

    fun discard(part: File) {}

    /** Deletes every cached file and forgets them. */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        }
    }

    fun stats(): PipeRangeStats = synchronized(lock) { PipeRangeStats(entries.size, entries.values.sumOf { it.size }) }
}

/**
 * Copies a live run into a part file. [write] returns false (and aborts itself) as soon as the bytes written
 * would pass the per-token cap, or on any I/O error; the caller then simply stops teeing.
 */
class TeeFill internal constructor(
    private val cache: PipeRangeCache,
    private val token: String,
    private val part: File,
    private val stream: FileOutputStream,
    private val maxBytes: Long,
) {
    private var written = 0L
    private var closed = false

    @Synchronized
    fun write(buf: ByteArray, off: Int, len: Int): Boolean {
        if (closed) return false
        if (written + len > maxBytes) {
            abort()
            return false
        }
        return try {
            stream.write(buf, off, len)
            written += len
            true
        } catch (_: IOException) {
            abort()
            false
        }
    }

    /** Registers the finished run under its token; null when it was empty or could not be stored. */
    @Synchronized
    fun complete(): CachedStream? {
        if (closed) return null
        closed = true
        return try {
            stream.close()
            cache.promote(token, part)
        } catch (_: IOException) {
            part.delete()
            null
        }
    }

    /** Idempotent: closes and deletes the part file. */
    @Synchronized
    fun abort() {
        if (closed) return
        closed = true
        runCatching { stream.close() }
        part.delete()
    }
}
