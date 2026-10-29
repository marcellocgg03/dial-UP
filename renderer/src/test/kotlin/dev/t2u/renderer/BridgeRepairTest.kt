package dev.t2u.renderer

// Plan 05-18 (gap closure, Phase 1 carry-over c and d, review WR-03, WR-06, WR-07): housekeeping leaves the sockets alone
// while the system reports no Wi-Fi, rebuilds a responder or SSDP client that reopening cannot heal, and no longer holds the
// network lock through a whole pass. Same loopback rig as the other whole-bridge tests (BridgeHarness): no LAN, no device.
// GPL-3.0-only.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BridgeRepairTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() = h.close()

    private fun after(marker: Int, kind: String) = h.events.drop(marker).filter { it.first == kind }.map { it.second }

    @Test
    fun housekeepingDuringAnOutageLeavesTheListenersAlone() = h.run<Unit> {
        // Session E step 5 in loopback terms: the rebind switch is off (networkLost is never called), the system reports no
        // Wi-Fi, and a housekeeping pass arrives. It must touch nothing; when the network returns on the same address the
        // old listener still answers.
        val up = AtomicBoolean(true)
        val bridge = h.newBridge(currentNetwork = { if (up.get()) h.network(h.loopback) else null })
        bridge.start()
        val launched = h.launch(bridge)
        val port = bridge.ssdpPorts().getValue("loop")

        up.set(false)
        val marker = h.events.size
        assertFalse(bridge.housekeeping("alarm"), "a pass without Wi-Fi does not run")
        val skipped = after(marker, "housekeeping").single()
        assertEquals("no-wifi", skipped["skipped"])
        assertEquals("alarm", skipped["trigger"])
        assertTrue(after(marker, "ssdp-probe").isEmpty(), "no probe without Wi-Fi: ${h.events.drop(marker)}")
        assertTrue(after(marker, "ssdp-bind").isEmpty(), "no listener reopened without Wi-Fi: ${h.events.drop(marker)}")
        assertTrue(after(marker, "reannounce").isEmpty(), "no announce without Wi-Fi: ${h.events.drop(marker)}")

        up.set(true)
        assertEquals(port, bridge.ssdpPorts().getValue("loop"), "the listener is the one that was open before the outage")
        val reply = assertNotNull(h.dialSearchAt(bridge, h.loopback), "the old listener answers once the network is back")
        assertTrue(assertNotNull(h.header(reply, "USN")).contains(launched.uuid))
        val marker2 = h.events.size
        assertTrue(bridge.housekeeping("adb"))
        assertEquals(true, after(marker2, "ssdp-probe").single()["ok"])
    }

    @Test
    fun theAdbTriggerAlsoSkipsWithoutWifi() = h.run<Unit> {
        val up = AtomicBoolean(false)
        val bridge = h.newBridge(currentNetwork = { if (up.get()) h.network(h.loopback) else null })
        bridge.start()
        val marker = h.events.size
        assertFalse(bridge.housekeeping("adb"), "the person asking cannot make a dead network alive")
        val skipped = after(marker, "housekeeping").single()
        assertEquals("no-wifi", skipped["skipped"])
        assertEquals("adb", skipped["trigger"])
        assertTrue(after(marker, "ssdp-probe").isEmpty())
    }

    @Test
    fun noSupplierMeansAlwaysPresent() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.start()
        val marker = h.events.size
        assertTrue(bridge.housekeeping("test"))
        assertEquals(true, after(marker, "ssdp-probe").single()["ok"])
        assertTrue(after(marker, "housekeeping").none { it["skipped"] != null })
    }

    @Test
    fun aSkippedPassDoesNotArmTheDedupe() = h.run<Unit> {
        val up = AtomicBoolean(false)
        val bridge = h.newBridge(currentNetwork = { if (up.get()) h.network(h.loopback) else null })
        bridge.start()
        assertFalse(bridge.housekeeping("alarm"))
        up.set(true)
        val marker = h.events.size
        assertTrue(bridge.housekeeping("alarm"), "the next pass with Wi-Fi back runs")
        assertTrue(after(marker, "housekeeping").none { it["skipped"] == "recent" })
    }
}
