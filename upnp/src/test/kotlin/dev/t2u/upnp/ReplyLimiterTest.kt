package dev.t2u.upnp

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyLimiterTest {
    @Test
    fun aSourceGetsTheCapPerWindowAndAgainWhenItPasses() {
        var now = 0L
        val l = ReplyLimiter(3, 1_000L, 100) { now }
        repeat(3) { assertTrue(l.allow("a")) }
        assertFalse(l.allow("a"))
        assertTrue(l.allow("b"), "another source has its own count")
        now = 1_000L
        assertTrue(l.allow("a"), "the window has passed")
    }

    @Test
    fun aFloodOfNewSourcesEvictsTheOldestInsteadOfClearingEverything() {
        var now = 0L
        val l = ReplyLimiter(1, 1_000_000L, 8) { now }
        // a limited source that is recent
        for (i in 0 until 7) { now += 10; l.allow("old-$i") }
        now += 10
        assertTrue(l.allow("busy"))
        assertFalse(l.allow("busy"))
        // 5 new sources push past the limit of 8 keys; only the oldest quarter (2 keys) goes at a time
        repeat(5) { i -> now += 10; assertTrue(l.allow("new-$i")) }
        assertTrue(l.size() <= 8, "bounded: ${l.size()}")
        assertFalse(l.allow("busy"), "the recently limited source is still limited after the eviction")
        assertTrue(l.allow("old-0"), "the oldest was evicted, so it starts fresh")
    }
}
