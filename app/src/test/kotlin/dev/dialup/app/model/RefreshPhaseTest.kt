// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefreshPhaseTest {
    @Test
    fun `just under eight seconds is the spinner alone`() {
        assertEquals(EmptyPhase.SPINNER, emptyPhase(7_999))
    }

    @Test
    fun `eight seconds adds the Refresh button`() {
        assertEquals(EmptyPhase.SPINNER_WITH_REFRESH, emptyPhase(8_000))
        assertEquals(EmptyPhase.SPINNER_WITH_REFRESH, emptyPhase(60_000))
    }

    @Test
    fun `a negative time is the spinner alone`() {
        assertEquals(EmptyPhase.SPINNER, emptyPhase(-5))
    }

    @Test
    fun `the threshold is eight seconds`() {
        assertEquals(8_000L, REFRESH_AFTER_MS)
    }

    @Test
    fun `the throttle grants the first call and then waits out the gap`() {
        var now = 0L
        val throttle = SearchThrottle({ now }, FOREGROUND_SEARCH_GAP_MS)
        assertTrue(throttle.tryAcquire())
        now = 9_999
        assertFalse(throttle.tryAcquire())
        now = 10_000
        assertTrue(throttle.tryAcquire())
    }

    @Test
    fun `a clock that went back grants again`() {
        var now = 50_000L
        val throttle = SearchThrottle({ now }, FOREGROUND_SEARCH_GAP_MS)
        assertTrue(throttle.tryAcquire())
        now = 1_000
        assertTrue(throttle.tryAcquire())
    }
}
