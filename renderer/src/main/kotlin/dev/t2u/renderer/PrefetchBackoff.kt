package dev.t2u.renderer

// Per-video back-off for the prefetch of the next track, GPL-3.0-only.

/**
 * Per-video back-off for the prefetch (renderer.js `#prefetchFailures`): a video that could not be prepared is not tried
 * again by the poll before `retryMs * 2^(failures-1)` (capped at [maxMs]) has passed. Remembers the last [kept] ids; a repeated
 * failure moves its id to the newest end. Times are on the renderer's injected clock.
 */
internal class PrefetchBackoff(private val retryMs: Long, private val maxMs: Long, private val kept: Int) {
    private class Failure(val count: Int, val until: Long)

    private val failures = LinkedHashMap<String, Failure>()

    val size: Int get() = synchronized(failures) { failures.size }

    /** The time before which [id] is not tried again; 0 when it has no failure on record. */
    fun blockedUntil(id: String): Long = synchronized(failures) { failures[id]?.until ?: 0L }

    /** Records a failure at [now] and returns the wait that now applies, in milliseconds. */
    fun failed(id: String, now: Long): Long = synchronized(failures) {
        val count = (failures[id]?.count ?: 0) + 1
        val delay = minOf(retryMs * (1L shl minOf(count - 1, 30)), maxMs)
        failures.remove(id) // re-insert: the oldest entries are dropped first
        failures[id] = Failure(count, now + delay)
        while (failures.size > kept) failures.remove(failures.keys.first())
        delay
    }

    fun succeeded(id: String) {
        synchronized(failures) { failures.remove(id) }
    }
}
