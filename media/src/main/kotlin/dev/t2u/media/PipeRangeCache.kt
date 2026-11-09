package dev.t2u.media

// Kotlin-only, no Node counterpart (Node's `#pipe` ignores Range), GPL-3.0-only.
//
// A bounded per-token store of finished ffmpeg pipe runs, so a ranged GET on a live video pipe token (what the
// SYMFONISK Lamp sends after an early Pause: `Range: bytes=N-`) can be answered with a correct
// 206 from the bytes the speaker already received, without a new ffmpeg run. Every method here is best effort:
// a cache problem returns null/false and must never throw into the live stream.
//
// Bounds (a long or repeated run must not fill the phone): [PipeRangeConfig.maxBytesPerToken] per run (a longer
// run is never kept and its token is remembered as too big), [PipeRangeConfig.maxBytesTotal] over all finished
// runs (least recently used removed first), and at most [PipeRangeConfig.maxConcurrentParts] part files being
// written at a time (a live tee or a cold fill each hold one), so the disk in use never exceeds total + parts x per-token.

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The one switch for answering `Range` on live video pipe tokens from the bytes of the first run.
 * A failed Lamp retest sets this to false (range answering off, today's behaviour).
 */
const val PIPE_RANGE_ANSWERING_DEFAULT = true

/**
 * Limits of the range cache. [enabled] false means no tee, no cached answer, no cold fill. [coldFillTimeoutMs] is
 * the time budget of one cold fill, [maxConcurrentParts] the number of part files written at the same time.
 */
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
 * already validated, so they are safe as file names. The directory is created on first use; `.part` and `.bin`
 * files an earlier process left behind are removed when the cache is constructed.
 */
class PipeRangeCache(private val dir: File, private val config: PipeRangeConfig) {
    private val lock = Any()

    /** Finished runs, least recently used first. */
    private val entries = LinkedHashMap<String, CachedStream>()

    /** Part files being written; each holds one of the [PipeRangeConfig.maxConcurrentParts] slots. */
    private val activeParts = HashSet<File>()

    /** Tokens whose run passed the per-token cap, oldest first, bounded like the token table. */
    private val oversize = LinkedHashSet<String>()

    init {
        removeLeftovers()
    }

    /** The complete stream for [token], or null. A hit makes it the most recently used. */
    fun lookup(token: String): CachedStream? = synchronized(lock) {
        val cached = entries.remove(token) ?: return null
        if (!cached.file.exists()) return null
        entries[token] = cached
        cached
    }

    /**
     * A new empty part file in [dir], or null when the directory cannot be created or written or when
     * [PipeRangeConfig.maxConcurrentParts] part files are already open. Finish it with [promote] or [discard].
     */
    fun newPartFile(): File? = synchronized(lock) {
        if (!config.enabled || activeParts.size >= config.maxConcurrentParts) return null
        try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return null
            File.createTempFile("run-", ".part", dir).also { activeParts.add(it) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** Deletes an unfinished [part] and frees its slot. */
    fun discard(part: File) {
        synchronized(lock) {
            activeParts.remove(part)
            part.delete()
        }
    }

    /**
     * Moves the finished [part] to [token]'s final name and returns it, then deletes least recently used runs until
     * the total is within the cap (the new run is never the victim). A token that is already cached keeps its first
     * stream (the part is deleted); an empty part, or one above the per-token cap, is deleted and gives null.
     */
    fun promote(token: String, part: File): CachedStream? = synchronized(lock) {
        activeParts.remove(part)
        val existing = entries.remove(token)
        if (existing != null && existing.file.exists()) {
            entries[token] = existing
            part.delete()
            return existing
        }
        val size = part.length()
        if (size <= 0L) {
            part.delete()
            return null
        }
        if (size > config.maxBytesPerToken) {
            markOversizeLocked(token)
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
        val stream = CachedStream(target, size)
        entries[token] = stream
        var total = entries.values.sumOf { it.size }
        val iter = entries.entries.iterator()
        while (total > config.maxBytesTotal && iter.hasNext()) {
            val victim = iter.next()
            if (victim.key == token) continue
            total -= victim.value.size
            victim.value.file.delete()
            iter.remove()
        }
        stream
    }

    /**
     * A tee that copies a live run for [token] into a part file, or null when the cache cannot take it: disabled,
     * the token is known to be too long, it is already cached, no part-file slot is free or the file cannot be made.
     */
    fun openTee(token: String): TeeFill? {
        if (!config.enabled || isOversize(token)) return null
        if (lookup(token) != null) return null
        val part = newPartFile() ?: return null
        return try {
            TeeFill(this, token, part, FileOutputStream(part), config.maxBytesPerToken)
        } catch (_: IOException) {
            discard(part)
            null
        }
    }

    /** Deletes [token]'s file and forgets it, including the too-big mark (the token left the media server's table). */
    fun drop(token: String) {
        synchronized(lock) {
            entries.remove(token)?.file?.delete()
            oversize.remove(token)
        }
    }

    fun isOversize(token: String): Boolean = synchronized(lock) { token in oversize }

    /** Remembers that [token]'s run is longer than the per-token cap, so it is neither teed nor filled again. */
    fun markOversize(token: String) {
        synchronized(lock) { markOversizeLocked(token) }
    }

    private fun markOversizeLocked(token: String) {
        oversize.remove(token)
        oversize.add(token)
        while (oversize.size > MAX_OVERSIZE) oversize.remove(oversize.first())
    }

    /** Deletes every cached and part file and forgets every run and mark. Open part slots free themselves when their owner ends. */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            oversize.clear()
            removeLeftovers()
        }
    }

    fun stats(): PipeRangeStats = synchronized(lock) { PipeRangeStats(entries.size, entries.values.sumOf { it.size }) }

    private fun removeLeftovers() {
        dir.listFiles { f -> f.isFile && (f.name.endsWith(".part") || f.name.endsWith(".bin")) }
            ?.forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val MAX_OVERSIZE = 256
    }
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
            cache.markOversize(token)
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
            cache.discard(part)
            null
        }
    }

    /** Idempotent: closes and deletes the part file. */
    @Synchronized
    fun abort() {
        if (closed) return
        closed = true
        runCatching { stream.close() }
        cache.discard(part)
    }
}
