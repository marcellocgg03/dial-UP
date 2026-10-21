package dev.t2u.media

// Kotlin-only, no Node counterpart (Node's `#pipe` ignores Range), GPL-3.0-only.
//
// STUB (plan 02-16 Task 1, RED step): the API with the planned names and no behaviour, so the RED tests
// fail on behaviour ("200 instead of 206") and not on a compile error. The next commit fills it in.

import java.io.File

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
) {
    init {
        require(maxBytesPerToken > 0) { "maxBytesPerToken must be positive" }
        require(maxBytesTotal > 0) { "maxBytesTotal must be positive" }
        require(maxBytesPerToken <= maxBytesTotal) { "maxBytesPerToken must not exceed maxBytesTotal" }
        require(coldFillTimeoutMs > 0) { "coldFillTimeoutMs must be positive" }
    }
}

/** A complete stream on disk: [size] is the byte total the first run produced. */
class CachedStream(val file: File, val size: Long)

data class PipeRangeStats(val entries: Int, val bytes: Long)

/** Bounded per-token store of finished pipe runs (stub). */
class PipeRangeCache(private val dir: File, private val config: PipeRangeConfig) {
    fun lookup(token: String): CachedStream? = null

    fun newPartFile(): File? = null

    fun promote(token: String, part: File): CachedStream? = null

    fun openTee(token: String): TeeFill? = null

    fun clear() {}

    fun stats(): PipeRangeStats = PipeRangeStats(0, 0)
}

/** Copies a live run into a part file (stub). */
class TeeFill internal constructor() {
    fun write(buf: ByteArray, off: Int, len: Int): Boolean = false

    fun complete(): CachedStream? = null

    fun abort() {}
}
