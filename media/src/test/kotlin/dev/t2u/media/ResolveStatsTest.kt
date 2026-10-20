package dev.t2u.media

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResolveStatsTest {
    private fun rec(index: Int, ms: Long, ok: Boolean = true) = ResolveRecord(
        id = "id%09d".format(index),
        pass = "default",
        index = index,
        ok = ok,
        wallMs = ms,
        ext = if (ok) "m4a" else null,
        formatId = if (ok) "140" else null,
        urlHost = if (ok) "rr1.googlevideo.com" else null,
        expire = null,
        error = if (ok) null else "ERROR",
        jsRuntimeLine = null,
    )

    @Test
    fun fiveOkRecordsGiveColdWarmMedianAndNearestRankP90() {
        val s = summarize(listOf(9000L, 3000L, 2000L, 4000L, 2500L).mapIndexed { i, ms -> rec(i, ms) })
        assertEquals(5, s.total)
        assertEquals(5, s.ok)
        assertEquals(0, s.fail)
        assertEquals(9000L, s.coldMs)
        assertEquals(2750L, s.warmMedianMs)
        assertEquals(4000L, s.warmP90Ms)
        assertEquals(2000L, s.minMs)
        assertEquals(9000L, s.maxMs)
    }

    @Test
    fun failedRecordsCountAsFailAndAreExcludedFromTiming() {
        val s = summarize(listOf(rec(0, 8000), rec(1, 99999, ok = false), rec(2, 3000), rec(3, 5000)))
        assertEquals(4, s.total)
        assertEquals(3, s.ok)
        assertEquals(1, s.fail)
        assertEquals(8000L, s.coldMs)
        assertEquals(4000L, s.warmMedianMs) // warm = 3000, 5000
        assertEquals(5000L, s.warmP90Ms)
        assertEquals(3000L, s.minMs)
        assertEquals(8000L, s.maxMs)
    }

    @Test
    fun firstRecordFailedMeansNoColdTime() {
        val s = summarize(listOf(rec(0, 100, ok = false), rec(1, 3000), rec(2, 2000), rec(3, 4000)))
        assertNull(s.coldMs)
        assertEquals(1, s.fail)
        assertEquals(3, s.ok)
        assertEquals(3000L, s.warmMedianMs) // odd count: the middle value of 2000, 3000, 4000
        assertEquals(4000L, s.warmP90Ms)
        assertEquals(2000L, s.minMs)
        assertEquals(4000L, s.maxMs)
    }

    @Test
    fun emptyListGivesZerosAndNullTimings() {
        val s = summarize(emptyList())
        assertEquals(0, s.total)
        assertEquals(0, s.ok)
        assertEquals(0, s.fail)
        assertNull(s.coldMs)
        assertNull(s.warmMedianMs)
        assertNull(s.warmP90Ms)
        assertNull(s.minMs)
        assertNull(s.maxMs)
    }

    @Test
    fun recordsAreOrderedByIndexAndOneOkRecordHasNoWarmTimes() {
        val shuffled = summarize(listOf(rec(2, 2000), rec(0, 7000), rec(1, 3000)))
        assertEquals(7000L, shuffled.coldMs)
        assertEquals(2500L, shuffled.warmMedianMs)

        val single = summarize(listOf(rec(0, 6000)))
        assertEquals(6000L, single.coldMs)
        assertNull(single.warmMedianMs)
        assertNull(single.warmP90Ms)
        assertEquals(6000L, single.minMs)
    }

    @Test
    fun evenCountMedianRoundsDown() {
        val s = summarize(listOf(rec(0, 9000), rec(1, 1000), rec(2, 1001)))
        assertEquals(1000L, s.warmMedianMs) // (1000 + 1001) / 2 rounded down
    }
}
