// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// Manual pairing: the bridge hands out the TV codes of a running renderer only. Every test is `= h.run<Unit> { ... }`.

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BridgePairingCodesTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() = h.close()

    @Test
    @DisplayName("no codes before the bridge starts, for an unknown renderer, or after it stops")
    fun noCodesWithoutARunningRenderer() = h.run<Unit> {
        val bridge = h.newBridge()
        assertEquals(emptyList(), bridge.pairingCodes(HARNESS_RENDERER_UDN))
        bridge.start()
        h.awaitUntil("one speaker listed") { bridge.speakers.value.size == 1 }
        assertEquals(emptyList(), bridge.pairingCodes("no-such-udn"))
        bridge.stop()
        assertEquals(emptyList(), bridge.pairingCodes(HARNESS_RENDERER_UDN))
    }
}
