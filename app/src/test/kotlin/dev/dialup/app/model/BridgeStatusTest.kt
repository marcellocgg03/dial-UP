// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeStatusTest {
    private val bools = listOf(true, false)

    @Test
    fun `the only subtitle is Waiting for Wi-Fi, for switch on, exempt and no Wi-Fi`() {
        assertEquals("Waiting for Wi-Fi", statusLine(enabled = true, exempt = true, wifiPresent = false))
    }

    @Test
    fun `every other combination has no subtitle`() {
        for (e in bools) for (x in bools) for (w in bools) {
            if (e && x && !w) continue
            assertEquals("", statusLine(e, x, w), "enabled=$e exempt=$x wifiPresent=$w")
        }
    }

    @Test
    fun `no combination returns a removed status text`() {
        for (e in bools) for (x in bools) for (w in bools) {
            val text = statusLine(e, x, w)
            for (word in listOf("Running", "phone", "network", "Bridge off", "Starting", "battery")) {
                assertFalse(text.contains(word), "$text contains $word")
            }
        }
    }

    @Test
    fun `waitingForWifi is true only for on, exempt and no Wi-Fi`() {
        for (e in bools) for (x in bools) for (w in bools) {
            assertEquals(e && x && !w, waitingForWifi(e, x, w))
        }
        assertTrue(waitingForWifi(true, true, false))
    }
}
