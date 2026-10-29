package dev.t2u.renderer

// Plan 05-18 (gap closure, Phase 1 carry-over c and d, review WR-03, WR-06, WR-07): housekeeping leaves the sockets alone
// while the system reports no Wi-Fi, rebuilds a responder or SSDP client that reopening cannot heal, and no longer holds the
// network lock through a whole pass. Same loopback rig as the other whole-bridge tests (BridgeHarness): no LAN, no device.
// GPL-3.0-only.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.dial.DialAccessMode
import dev.t2u.dial.DialAccessPolicy
import dev.t2u.upnp.SsdpEvents
import dev.t2u.upnp.SsdpResponder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    // ------------------------------------------------------------------------------------------ repair (task 2)

    /** A reply socket whose send fails while [broken] is set: the send side of the responder is dead. */
    private class BreakableSocket(bind: InetAddress, private val broken: AtomicBoolean) : MulticastSocket(InetSocketAddress(bind, 0)) {
        override fun send(p: DatagramPacket) {
            if (broken.get()) throw IOException("injected: send side is dead")
            super.send(p)
        }
    }

    private fun healthySocket(bind: InetAddress, iface: NetworkInterface?): MulticastSocket =
        MulticastSocket(InetSocketAddress(bind, 0)).apply {
            timeToLive = 4
            if (iface != null) networkInterface = iface
        }

    /**
     * A responderFactory whose first responder has a [BreakableSocket] (deaf once [broken] is set) and whose later ones are
     * healthy. [failNext] makes the next call throw a BindException instead. [calls] counts the builds.
     */
    private fun factory(broken: AtomicBoolean, calls: AtomicInteger, failNext: AtomicBoolean = AtomicBoolean(false)) =
        { net: BridgeNetwork, ev: SsdpEvents, policy: DialAccessPolicy ->
            val n = calls.incrementAndGet()
            if (failNext.compareAndSet(true, false)) throw BindException("injected: address in use")
            SsdpResponder(
                net.ssdpListen,
                net.wifiIp,
                net.iface,
                ev,
                replyPolicy = policy::allowsSsdp,
                announcePolicy = policy::announceAllowed,
                notifyTarget = net.notifyTarget,
                openReplySocket = { bind, iface -> if (n == 1) BreakableSocket(bind, broken) else healthySocket(bind, iface) },
            )
        }

    @Test
    fun aDeafResponderIsRebuiltAndAnswersAgain() = h.run<Unit> {
        val broken = AtomicBoolean(false)
        val calls = AtomicInteger(0)
        val bridge = h.newBridge(responderFactory = factory(broken, calls))
        bridge.start()
        val launched = h.launch(bridge)
        broken.set(true)

        val marker = h.events.size
        assertTrue(bridge.housekeeping("adb"))
        val probes = after(marker, "ssdp-probe")
        assertEquals(3, probes.size, "first probe, probe after the reopen, probe after the repair: $probes")
        assertEquals(false, probes[0]["ok"])
        assertEquals("send-failed", probes[0]["reason"])
        assertEquals(false, probes[1]["ok"])
        assertEquals(1, probes[1]["restarted"])
        assertEquals("send-failed", probes[1]["reason"])
        assertEquals(true, probes[2]["ok"])
        val repair = after(marker, "ssdp-repair").single()
        assertEquals("responder", repair["what"])
        assertEquals(true, repair["ok"])
        assertEquals("adb", repair["trigger"])
        val reply = assertNotNull(h.dialSearchAt(bridge, h.loopback), "the rebuilt responder answers")
        assertTrue(assertNotNull(h.header(reply, "USN")).contains(launched.uuid))
        assertEquals(2, calls.get(), "the factory built the first responder and the replacement")
    }

    @Test
    fun aFailedRebindStepIsHealedByTheNextHousekeeping() = h.run<Unit> {
        val other = InetAddress.getByName("127.0.0.2")
        val calls = AtomicInteger(0)
        val failNext = AtomicBoolean(false)
        val bridge = h.newBridge(responderFactory = factory(AtomicBoolean(false), calls, failNext))
        bridge.start()
        val launched = h.launch(bridge)

        failNext.set(true)
        val marker = h.events.size
        bridge.networkLost()
        bridge.rebindNetwork(h.network(other))
        assertTrue(
            after(marker, "bridge-error").any { it["where"] == "rebind:responder-start" },
            "the failed step is logged: ${h.events.drop(marker)}",
        )
        assertTrue(bridge.ssdpPorts().isEmpty(), "no stopped responder is installed: ${bridge.ssdpPorts()}")
        val description = h.http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.2:${launched.port}/ytcr/ssdp/device-desc.xml")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(200, description.statusCode(), "the receiver was still moved")

        val marker2 = h.events.size
        assertTrue(bridge.housekeeping("adb"))
        val repair = after(marker2, "ssdp-repair").single()
        assertEquals("responder", repair["what"])
        assertEquals(true, repair["ok"])
        val reply = assertNotNull(h.dialSearchAt(bridge, other), "the rebuilt responder answers on the new address")
        assertEquals("127.0.0.2", URI(assertNotNull(h.header(reply, "LOCATION"))).host)
        assertTrue(assertNotNull(h.header(reply, "USN")).contains(launched.uuid))
    }

    @Test
    fun aDeadSearchClientIsRebuiltAndSearchesAgain() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.start()
        h.awaitUntil("the start-up search") { h.searchesSeen.get() >= 1 }
        // the state a failed rebind step or a failed bind leaves: an installed client without a search socket
        assertNotNull(bridge.ssdpClientForTest()).stop()
        val before = h.searchesSeen.get()

        val marker = h.events.size
        assertTrue(bridge.housekeeping("adb"))
        val repair = after(marker, "ssdp-repair").single()
        assertEquals("client", repair["what"])
        assertEquals(true, repair["ok"])
        h.awaitUntil("the repair's search") { h.searchesSeen.get() > before }
        assertTrue(assertNotNull(bridge.ssdpClientForTest()).isBound())

        val marker2 = h.events.size
        assertTrue(bridge.housekeeping("adb"))
        assertTrue(after(marker2, "ssdp-repair").isEmpty(), "a healthy client is left alone: ${after(marker2, "ssdp-repair")}")
    }

    @Test
    fun aRepairIsSkippedWhenTheAddressChanged() = h.run<Unit> {
        val other = InetAddress.getByName("127.0.0.2")
        val broken = AtomicBoolean(false)
        val calls = AtomicInteger(0)
        val bridge = h.newBridge(responderFactory = factory(broken, calls), currentNetwork = { h.network(other) })
        bridge.start()
        broken.set(true)

        val marker = h.events.size
        assertTrue(bridge.housekeeping("adb"))
        val repair = after(marker, "ssdp-repair").single()
        assertEquals("address-changed", repair["skipped"])
        assertEquals("responder", repair["what"])
        assertEquals(1, calls.get(), "nothing was rebuilt")
    }

    @Test
    fun phoneOnlyModeStillSendsNoAliveAfterARepair() = h.run<Unit> {
        val broken = AtomicBoolean(false)
        val bridge = h.newBridge(responderFactory = factory(broken, AtomicInteger(0)))
        bridge.start()
        h.launch(bridge)
        broken.set(true)

        val marker = h.events.size
        assertTrue(bridge.housekeeping("adb"))
        assertEquals(true, after(marker, "ssdp-repair").single()["ok"])
        assertTrue(
            after(marker, "ssdp-tx").none { it["dest"] == "notify" },
            "phone mode never puts ssdp:alive on the wire: ${after(marker, "ssdp-tx")}",
        )
    }

    @Test
    fun lanModeAnnouncesTheReceiverAfterARepair() = h.run<Unit> {
        h.accessMode = DialAccessMode.LAN
        val broken = AtomicBoolean(false)
        val bridge = h.newBridge(responderFactory = factory(broken, AtomicInteger(0)))
        bridge.start()
        h.launch(bridge)
        broken.set(true)

        val marker = h.events.size
        assertTrue(bridge.housekeeping("adb"))
        assertEquals(true, after(marker, "ssdp-repair").single()["ok"])
        val alive = after(marker, "ssdp-tx").filter { it["dest"] == "notify" && it["nts"] == "ssdp:alive" }
        assertEquals(5, alive.size, "five DIAL notification types for the one receiver: $alive")
    }

    @Test
    fun aHealthyPassLogsTheSameProbeEventsAsBefore() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.start()
        val marker = h.events.size
        assertTrue(bridge.housekeeping("adb"))
        val probe = after(marker, "ssdp-probe").single()
        assertEquals(setOf("ok", "trigger"), probe.keys)
        assertTrue(after(marker, "ssdp-repair").isEmpty())
    }
}
