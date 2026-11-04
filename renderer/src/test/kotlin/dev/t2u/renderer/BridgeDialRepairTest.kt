package dev.t2u.renderer

// Second review pass (plan 08-07), a sibling of WR-05: a move whose DIAL bind fails leaves a running receiver with no DIAL
// listener, and the next housekeeping pass binds it again on the bridge's own address. Loopback stands in for Wi-Fi:
// 127.0.0.1 is the old address, 127.0.0.2 the new one, and a ServerSocket held on 127.0.0.2 plays the busy address.
// GPL-3.0-only.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeDialRepairTest {
    private val h = BridgeHarness()
    private val other: InetAddress = InetAddress.getByName("127.0.0.2")

    @AfterEach
    fun tearDown() = h.close()

    private fun statusOf(url: String): Int =
        h.http.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    private fun repairs() = h.events.filter { it.first == "dial-repair" }.map { it.second }

    @Test
    fun aDialServerThatAFailedMoveLeftMissingIsBoundAgainByHousekeeping() = h.run<Unit> {
        val bridge = h.newBridge(currentNetwork = { h.network(other) })
        bridge.start()
        val launched = h.launch(bridge)
        val busy = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(other, launched.port))
        }

        bridge.networkLost()
        bridge.rebindNetwork(h.network(other))
        assertTrue(
            h.events.any { it.first == "lounge-receiver" && it.second["type"] == "rebindFailed" },
            "the DIAL move failed: ${h.events.takeLast(10)}",
        )
        busy.close()

        assertTrue(bridge.housekeeping("adb"))
        assertEquals(200, statusOf("http://127.0.0.2:${launched.port}/ytcr/ssdp/device-desc.xml"), "housekeeping bound the DIAL server again")
        assertTrue(repairs().any { it["ok"] == true }, "a dial-repair event with ok = true: ${repairs()}")
    }

    @Test
    fun aListeningDialServerIsLeftAlone() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.start()
        h.launch(bridge)
        assertTrue(bridge.housekeeping("adb"))
        assertTrue(repairs().isEmpty(), "no repair when the DIAL server listens: ${h.events.takeLast(10)}")
    }
}
