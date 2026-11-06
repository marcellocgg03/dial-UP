// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NetworkLossPolicyTest {
    @Test
    fun `present continues whatever the count and time`() {
        for (casting in listOf(0, 1, 5)) {
            for (down in listOf(0L, 4_999L, 5_000L, 900_000L)) {
                assertEquals(LossAction.CONTINUE, lossAction(true, casting, down))
            }
        }
    }

    @Test
    fun `absent with or without a cast waits for the blip window`() {
        assertEquals(LossAction.WAIT, lossAction(false, 0, 0))
        assertEquals(LossAction.WAIT, lossAction(false, 0, 4_999))
        assertEquals(LossAction.WAIT, lossAction(false, 1, 0))
        assertEquals(LossAction.WAIT, lossAction(false, 1, 4_999))
        assertEquals(LossAction.STOP_IDLE, lossAction(false, 0, 5_000))
        assertEquals(LossAction.STOP_EXPIRED, lossAction(false, 1, 5_000))
        assertEquals(LossAction.STOP_EXPIRED, lossAction(false, 2, 400_000))
    }

    @Test
    fun `a negative down time counts as zero`() {
        assertEquals(LossAction.WAIT, lossAction(false, 1, -5))
    }

    @Test
    fun `wire names`() {
        assertEquals(listOf("continue", "wait", "idle", "expired"), LossAction.entries.map { it.wire })
    }
}
