// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.media

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UPD-02: the pure "is a yt-dlp update due" rule and the persisted record's tolerant parsing. */
class YtDlpDueTest {
    private val hour = 3_600_000L
    private val minute = 60_000L
    private val now = 1_000_000_000_000L

    @Test
    fun anEmptyRecordIsDue() {
        assertTrue(ytdlpDue(YtDlpRecord(null, null), now))
    }

    @Test
    fun aSuccessLessThanEighteenHoursAgoIsNotDue() {
        assertFalse(ytdlpDue(YtDlpRecord(now - (18 * hour - minute), null), now))
    }

    @Test
    fun aSuccessExactlyEighteenHoursAgoIsDue() {
        assertTrue(ytdlpDue(YtDlpRecord(now - 18 * hour, null), now))
    }

    @Test
    fun aSuccessThirtyHoursAgoIsDue() {
        assertTrue(ytdlpDue(YtDlpRecord(now - 30 * hour, null), now))
    }

    @Test
    fun anAttemptLessThanAnHourAgoHoldsBackEvenAnOldSuccess() {
        assertFalse(ytdlpDue(YtDlpRecord(now - 30 * hour, now - (hour - minute)), now))
    }

    @Test
    fun anAttemptExactlyAnHourAgoIsDue() {
        assertTrue(ytdlpDue(YtDlpRecord(now - 30 * hour, now - hour), now))
    }

    @Test
    fun aFailedAttemptWithNoSuccessStillWaitsAnHour() {
        assertFalse(ytdlpDue(YtDlpRecord(null, now - 10 * minute), now))
        assertTrue(ytdlpDue(YtDlpRecord(null, now - hour), now))
    }

    @Test
    fun aTimestampFromTheFutureCountsAsAbsent() {
        assertTrue(ytdlpDue(YtDlpRecord(now + hour, null), now))
        assertTrue(ytdlpDue(YtDlpRecord(null, now + hour), now))
        assertTrue(ytdlpDue(YtDlpRecord(now + hour, now + hour), now))
    }

    @Test
    fun theRecordSurvivesAJsonRoundTrip() {
        for (r in listOf(YtDlpRecord(), YtDlpRecord(5L, null), YtDlpRecord(null, 7L), YtDlpRecord(5L, 7L))) {
            assertEquals(r, parseYtDlpRecord(ytDlpRecordJson(r)))
        }
    }

    @Test
    fun unreadableTextGivesTheEmptyRecord() {
        for (text in listOf("", "[]", "not json", "{", "null", "\"x\"")) {
            assertEquals(YtDlpRecord(), parseYtDlpRecord(text), "for <$text>")
        }
    }

    @Test
    fun aBadFieldIsDroppedAndTheGoodOneKept() {
        assertEquals(YtDlpRecord(null, 7L), parseYtDlpRecord("""{"lastSuccessWallMs":"yesterday","lastAttemptWallMs":7}"""))
        assertEquals(YtDlpRecord(null, null), parseYtDlpRecord("""{"lastSuccessWallMs":-5,"lastAttemptWallMs":1.5}"""))
        assertEquals(YtDlpRecord(5L, null), parseYtDlpRecord("""{"lastSuccessWallMs":5,"lastAttemptWallMs":{"a":1}}"""))
    }
}
