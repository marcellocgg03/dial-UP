package dev.t2u.renderer

// SVC-04 (plan 05-07): Bridge.rebindNetwork moves a running bridge to a new address without rebuilding it. Loopback
// stands in for Wi-Fi: 127.0.0.1 is the old address, 127.0.0.2 the new one (both bindable in WSL, RESEARCH A5); a socket
// bound to one of them refuses connections on the other. GPL-3.0-only.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeRebindTest {
    private val h = BridgeHarness()
    private val other: InetAddress = InetAddress.getByName("127.0.0.2")

    @AfterEach
    fun tearDown() = h.close()

    private fun refuses(host: InetAddress, port: Int) {
        assertFailsWith<ConnectException> { Socket().use { it.connect(InetSocketAddress(host, port), 1000) } }
    }

    private fun statusOf(url: String): Int =
        h.http.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    private fun rendererPort(bridge: Bridge): Int =
        Regex("\"port\":(\\d+)").find(bridge.state()["renderers"].toString())!!.groupValues[1].toInt()

    private fun registerCalls() = h.lounge.requests.count { it.path.endsWith("register_pairing_code") }

    private fun disconnected() = listOf("cl", "m").any { theme ->
        h.lounge.sent[theme].orEmpty().any { form -> form.values.contains("loungeScreenDisconnected") }
    }

    @Test
    @DisplayName("after a move the receiver answers DIAL and SSDP on the new address only and the connected sender's lounge session is untouched")
    fun moveKeepsTheReceiverAndTheSenderSession() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.start()
        val launched = h.launch(bridge)
        h.connectSender()
        val registers = registerCalls()
        assertEquals("127.0.0.1", bridge.state()["wifiIp"].toString().trim('"'))

        bridge.networkLost()
        bridge.rebindNetwork(h.network(other))

        // the same renderer and port, on the new address
        assertEquals("127.0.0.2", bridge.state()["wifiIp"].toString().trim('"'))
        assertEquals(launched.port, rendererPort(bridge))
        assertTrue(bridge.state()["renderers"].toString().contains(HARNESS_RENDERER_UDN))

        // DIAL: the description answers on the new address, the old address refuses
        assertEquals(200, statusOf("http://127.0.0.2:${launched.port}/ytcr/ssdp/device-desc.xml"))
        refuses(h.loopback, launched.port)

        // SSDP: the new responder answers an M-SEARCH for the DIAL ST with a LOCATION on the new address, same port and uuid
        var reply: String? = null
        h.awaitUntil("a DIAL search reply on the new address") {
            reply = h.dialSearchAt(bridge, other)
            reply != null
        }
        val location = URI(assertNotNull(h.header(reply!!, "LOCATION")))
        assertEquals("127.0.0.2", location.host)
        assertEquals(launched.port, location.port)
        assertTrue(assertNotNull(h.header(reply!!, "USN")).contains(launched.uuid))
        // and nothing answers on the old address
        assertNull(h.dialSearchAt(bridge, h.loopback), "the old address answers nothing")

        // the lounge session survived: no goodbye, no new pairing, and a second sender is served by the same session
        assertFalse(disconnected(), "no loungeScreenDisconnected after a move")
        assertEquals(registers, registerCalls(), "no new pairing")
        h.connectSender(id = "dev-2")
        assertFalse(disconnected())
        assertEquals(registers, registerCalls())

        // the log
        val rebound = h.events.filter { it.first == "bridge" && it.second["event"] == "rebound" }.map { it.second }.single()
        assertEquals("127.0.0.1", rebound["oldIp"])
        assertEquals("127.0.0.2", rebound["newIp"])
        assertEquals(1, rebound["receivers"])
        val receiverEvent = h.events.filter { it.first == "lounge-receiver" && it.second["type"] == "rebound" }.map { it.second }.single()
        assertEquals(launched.port, receiverEvent["port"])
    }

    @Test
    @DisplayName("while the network is marked down housekeeping skips with network-down, and after the rebind it runs again")
    fun networkLostSkipsHousekeepingUntilTheRebind() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.start()
        h.launch(bridge)

        bridge.networkLost()
        val marker = h.events.size
        assertFalse(bridge.housekeeping("adb"))
        val skipped = h.events.drop(marker).last { it.first == "housekeeping" }.second
        assertEquals("network-down", skipped["skipped"])
        assertEquals("adb", skipped["trigger"])
        assertTrue(h.events.any { it.first == "bridge" && it.second["event"] == "network-lost" })

        bridge.rebindNetwork(h.network(other))
        assertTrue(bridge.housekeeping("adb"), "housekeeping runs once the network is back")
    }

    @Test
    @DisplayName("a move onto the same address leaves DIAL and SSDP answering there")
    fun moveOntoTheSameAddress() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.start()
        val launched = h.launch(bridge)
        h.connectSender()

        bridge.networkLost()
        bridge.rebindNetwork(h.network(h.loopback))

        assertEquals(200, statusOf("http://127.0.0.1:${launched.port}/ytcr/ssdp/device-desc.xml"))
        var reply: String? = null
        h.awaitUntil("a DIAL search reply after the same-address move") {
            reply = h.dialSearchAt(bridge, h.loopback)
            reply != null
        }
        assertTrue(assertNotNull(h.header(reply!!, "USN")).contains(launched.uuid))
        assertFalse(disconnected())
    }

    @Test
    @DisplayName("after a move the media base is the new address on the same media port and the old media address refuses")
    fun mediaBaseFollowsTheMove() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.start()
        h.launch(bridge)
        val before = Regex("mediaBase\":\"http://127\\.0\\.0\\.1:(\\d+)").find(bridge.state().toString())!!.groupValues[1].toInt()

        bridge.rebindNetwork(h.network(other))

        assertEquals("http://127.0.0.2:$before", bridge.state()["mediaBase"].toString().trim('"'))
        // an unknown token answers 404 on the new address, and the old address refuses
        assertEquals(404, statusOf("http://127.0.0.2:$before/stream/${"0".repeat(32)}.aac"))
        refuses(h.loopback, before)
    }

    @Test
    @DisplayName("a bridge that was never started or is already stopped ignores a move")
    fun stoppedBridgeIgnoresAMove() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.rebindNetwork(h.network(other))
        bridge.start()
        h.launch(bridge)
        bridge.stop()
        val marker = h.events.size
        bridge.rebindNetwork(h.network(other))
        bridge.networkLost()
        assertTrue(h.events.drop(marker).none { it.first == "bridge" })
    }
}
