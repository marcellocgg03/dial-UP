package dev.t2u.media

// Kotlin original, GPL-3.0-only (plan 03-20). The Node proxy opens one upstream request per speaker request and keeps
// nothing (PARITY.md adaptation 28).

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger

/**
 * Size of one read-ahead window of the direct proxy: the bytes fetched from upstream, starting at the offset the speaker
 * asked for, to answer the many small ranges a Sonos sends after a seek (device session D: 70 to 110 ranges of 6144 bytes
 * about 68 ms apart, strided by 163840 bytes). 512 KiB is a few seconds of 128 kbit/s audio and covers about three of the
 * Lamp's strides, so the scan costs one upstream call per three or four groups of six ranges.
 */
internal const val READ_AHEAD_WINDOW_BYTES = 512 * 1024

/**
 * Upper bound on the bytes the direct proxy holds for read-ahead across all tokens, windows being fetched included. A window
 * is reserved against it before its fetch starts and the oldest window of another token is evicted to make room; when it
 * still does not fit the request goes upstream on its own, as before. 2 MiB is four windows.
 */
internal const val READ_AHEAD_TOTAL_BYTES = 4L * READ_AHEAD_WINDOW_BYTES

/** A bounded range of at most this many bytes counts as one read of a scan; a larger or open-ended one is a stream and is relayed as before. */
internal const val READ_AHEAD_SCAN_MAX_BYTES = 64 * 1024

/**
 * Read-ahead starts with the second scan-sized ranged GET of a token. The first one (the Lamp's probe at byte 0) is relayed
 * exactly as the speaker asked, so a token that is only ever asked once behaves as it always did.
 */
internal const val READ_AHEAD_AFTER_SCANS = 1

/** One held window: bytes `[start, start + bytes.size)` of a file that is [total] bytes long. */
internal class ReadAheadWindow(
    val start: Long,
    val bytes: ByteArray,
    val total: Long,
    val lastModified: String?,
    val etag: String?,
) {
    val end: Long get() = start + bytes.size - 1
}

/**
 * Per-token state of the direct proxy's read-ahead. [mutex] makes the window fetches of one token run one at a time;
 * [epoch] is bumped when the token's windows are dropped, so a fetch that was running meanwhile does not install itself.
 */
internal class DirectScan {
    val mutex = Mutex()
    val scans = AtomicInteger()
    val epoch = AtomicInteger()

    /** Set when upstream answered a window request in a way the proxy does not rely on; the token then goes back to one upstream request per speaker request. */
    @Volatile var disabled = false
}

/**
 * The windows held for read-ahead: at most one per token, at most [totalBytes] together with the windows being fetched
 * (threat T-03-51). Least recently used first when something must go.
 */
internal class ReadAheadStore(private val totalBytes: Long) {
    private val lock = Any()
    private val windows = LinkedHashMap<String, ReadAheadWindow>()
    private var reserved = 0L

    fun get(token: String): ReadAheadWindow? = synchronized(lock) {
        val w = windows.remove(token) ?: return null
        windows[token] = w // most recently used goes last
        w
    }

    /** Makes room for a window of [bytes] for [token], dropping the token's own old window and then the oldest others; false if it cannot fit. */
    fun reserve(token: String, bytes: Long): Boolean = synchronized(lock) {
        windows.remove(token)
        while (held() + bytes > totalBytes && windows.isNotEmpty()) {
            val oldest = windows.keys.first()
            windows.remove(oldest)
        }
        if (held() + bytes > totalBytes) return false
        reserved += bytes
        true
    }

    /** The fetch that reserved [reservedBytes] ended with [window] (or null when it produced nothing). */
    fun finish(token: String, reservedBytes: Long, window: ReadAheadWindow?) = synchronized(lock) {
        reserved -= reservedBytes
        if (window != null) windows[token] = window
    }

    fun drop(token: String) {
        synchronized(lock) { windows.remove(token) }
    }

    fun heldBytes(): Long = synchronized(lock) { held() }

    fun windowCount(): Int = synchronized(lock) { windows.size }

    private fun held(): Long = windows.values.sumOf { it.bytes.size.toLong() } + reserved
}

/**
 * One upstream call of the direct proxy as it goes into the request log: what kind, the status, how long until the first
 * byte and until the end. Never the URL or a header (threat T-03-52).
 */
internal class UpstreamTiming(val kind: String, val status: Int = 0, val ttfbMs: Long = -1) {
    @Volatile var totalMs: Long = -1

    @Volatile var bytes: Long = -1

    override fun toString(): String = buildString {
        append(kind)
        if (status != 0) append(' ').append(status)
        if (ttfbMs >= 0) append(" first byte ").append(ttfbMs).append(" ms")
        if (totalMs >= 0) append(" total ").append(totalMs).append(" ms")
        if (bytes >= 0) append(' ').append(bytes).append(" B")
    }
}
