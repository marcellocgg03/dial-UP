package dev.t2u.media

/** Result of parsing a Range header against a known size (port of mediaServer.js parseRange). */
sealed interface ByteRange {
    /** Inclusive byte span, already clamped to the resource size. */
    data class Span(val start: Long, val end: Long) : ByteRange

    /** The header was well formed but names nothing inside the resource (the Node 'invalid'). */
    data object Unsatisfiable : ByteRange
}

private val RANGE = Regex("^bytes=(\\d*)-(\\d*)$")

/** Digits that overflow a Long are treated as "far beyond any file", like a JS Number would be. */
private fun number(digits: String): Long = digits.toLongOrNull() ?: Long.MAX_VALUE

/**
 * Port of `parseRange(header, size)` from mediaServer.js, line for line.
 *
 * Returns null when the header is absent, does not match `bytes=<n>-<m>` or has both numbers empty
 * (the caller then answers 200 with the whole body). Suffix form `bytes=-N` is the last N bytes (start
 * never below 0), an open end means size-1, the end is clamped to size-1, and the result is
 * [ByteRange.Unsatisfiable] when start > end or start >= size.
 */
fun parseRange(header: String?, size: Long): ByteRange? {
    val m = RANGE.matchEntire(header?.trim().orEmpty()) ?: return null
    val first = m.groupValues[1]
    val last = m.groupValues[2]
    if (first.isEmpty() && last.isEmpty()) return null
    val start: Long
    val end: Long
    if (first.isEmpty()) {
        start = maxOf(0L, size - number(last))
        end = size - 1
    } else {
        start = number(first)
        end = if (last.isEmpty()) size - 1 else minOf(number(last), size - 1)
    }
    return if (start <= end && start < size) ByteRange.Span(start, end) else ByteRange.Unsatisfiable
}

/** STUB (plan 02-16 Task 2, RED step). */
fun isResumeRange(header: String?): Boolean = false
