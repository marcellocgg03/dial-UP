package dev.t2u.renderer

// GUI-14, plan 07.1-05: Bridge.searchNow(trigger) sends one MediaRenderer search through the running bridge's SSDP client
// on demand, and sends nothing while the network is down or the bridge is not running. Same loopback rig as the other
// whole-bridge tests (BridgeHarness). GPL-3.0-only.

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeSearchNowTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() {
        h.close()
    }

    private fun searchEvents(trigger: String) = h.events.filter { it.first == "search" && it.second["trigger"] == trigger }.map { it.second }

    @Test
    fun aStartedBridgeSendsOneMoreSearchAndEmitsTheEvent() = h.run {
        val bridge = h.newBridge()
        bridge.start()
        h.awaitUntil("the start-up search") { h.searchesSeen.get() >= 1 }
        val before = h.searchesSeen.get()

        assertTrue(bridge.searchNow("ui"))

        h.awaitUntil("the on-demand search") { h.searchesSeen.get() > before }
        val event = searchEvents("ui").single()
        assertEquals(true, event["sent"])
    }

    @Test
    fun aBridgeWhoseNetworkIsDownSendsNothing() = h.run {
        val bridge = h.newBridge()
        bridge.start()
        h.awaitUntil("the start-up search") { h.searchesSeen.get() >= 1 }
        bridge.networkLost()
        val before = h.searchesSeen.get()

        assertFalse(bridge.searchNow("ui"))

        assertEquals(before, h.searchesSeen.get(), "no M-SEARCH while the network is down")
        assertEquals("network-down", searchEvents("ui").single()["skipped"])
    }

    @Test
    fun aBridgeThatWasNeverStartedSendsNothing() = h.run {
        val bridge = h.newBridge()
        val before = h.searchesSeen.get()

        assertFalse(bridge.searchNow("ui"))

        assertEquals(before, h.searchesSeen.get())
        assertTrue(searchEvents("ui").isEmpty())
    }

    @Test
    fun aStoppedBridgeSendsNothing() = h.run {
        val bridge = h.newBridge()
        bridge.start()
        bridge.stop()
        val before = h.searchesSeen.get()

        assertFalse(bridge.searchNow("ui"))

        assertEquals(before, h.searchesSeen.get())
    }
}
