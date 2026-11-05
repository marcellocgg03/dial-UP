// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefreshPhaseTest {
    @Test
    fun `a search in progress is the spinner`() {
        assertEquals(EmptyPhase.SPINNER, emptyPhase(searching = true, idleSettled = false))
        assertEquals(EmptyPhase.SPINNER, emptyPhase(searching = true, idleSettled = true))
    }

    @Test
    fun `idle but not yet settled stays the spinner so the gap between two searches does not flicker`() {
        assertEquals(EmptyPhase.SPINNER, emptyPhase(searching = false, idleSettled = false))
    }

    @Test
    fun `idle and settled offers Refresh`() {
        assertEquals(EmptyPhase.IDLE_WITH_REFRESH, emptyPhase(searching = false, idleSettled = true))
    }

    @Test
    fun `the grace outlasts the gap between the start-up searches`() {
        // searches at 0 s and 5 s, each holding a 3 s window: the idle gap is 2 s
        assertTrue(SEARCH_IDLE_GRACE_MS > 5_000L - 3_000L)
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
