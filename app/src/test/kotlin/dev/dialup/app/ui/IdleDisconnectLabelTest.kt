// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import dev.dialup.app.IDLE_DISCONNECT_MINUTES
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class IdleDisconnectLabelTest {
    @Test
    fun theOfferedTimesReadAsOffThirtyMinutesOneHourTwoHours() {
        assertEquals(listOf("Off", "30 min", "1 hour", "2 hours"), IDLE_DISCONNECT_MINUTES.map(::idleDisconnectLabel))
    }
}
