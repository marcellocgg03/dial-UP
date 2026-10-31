// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.renderer

// GUI-04 (plan 07-12, D-06): a speaker that multicast discovery cannot see is added by typing its IPv4 address. The bridge
// sends one unicast M-SEARCH to that address and the reply goes through the same registry guards as any search reply.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeManualTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() = h.close()

    /** A bridge whose multicast discovery goes to a port nobody listens on, so only a manual add can find the speaker. */
    private fun blindBridge(manualAnswerTimeoutMs: Long? = null, manualSsdpPort: Int? = null, hidden: HiddenSpeakers? = null): Bridge =
        h.newBridge(
            searchTarget = InetSocketAddress(h.loopback, h.freePort()),
            manualSsdpPort = manualSsdpPort ?: h.speakerPort,
            manualAnswerTimeoutMs = manualAnswerTimeoutMs,
            hidden = hidden,
        )

    @Test
    @DisplayName("a speaker discovery cannot see is added by its address")
    fun aSpeakerDiscoveryCannotSeeIsAddedByItsAddress() = h.run<Unit> {
        val bridge = blindBridge()
        bridge.start()
        // discovery is blind: a search reaches nothing, so no speaker is listed
        delay(600)
        assertTrue(bridge.speakers.value.isEmpty(), "discovery found nothing")
        assertEquals(0, h.searchesSeen.get(), "no search reached the speaker")

        val result = bridge.addManual(h.loopback)

        val added = result as? ManualAddResult.Added ?: throw AssertionError("expected Added, got $result")
        assertEquals(HARNESS_RENDERER_UDN, added.udn)
        h.awaitUntil("the speaker listed") { bridge.speakers.value.size == 1 }
        val card = bridge.speakers.value.single()
        assertEquals(HARNESS_RENDERER_UDN, card.udn)
        assertEquals(card.name, added.name)
        assertEquals(1, h.searchesSeen.get(), "exactly one unicast search went to the typed address")
    }
}
