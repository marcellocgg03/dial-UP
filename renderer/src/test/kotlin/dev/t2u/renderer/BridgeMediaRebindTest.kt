package dev.t2u.renderer

// WR-05 (release review, plan 08-05): a media-server rebind that hits a busy address is retried with backoff, and a media
// server that is still down is rebound by the next housekeeping pass. Loopback stands in for Wi-Fi: 127.0.0.1 is the old
// address, 127.0.0.2 the new one, and a ServerSocket held on 127.0.0.2 plays the busy address. GPL-3.0-only.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BridgeMediaRebindTest {
    private val h = BridgeHarness()
    private val other: InetAddress = InetAddress.getByName("127.0.0.2")
    private val unknown = "/stream/${"0".repeat(32)}.aac"

    @AfterEach
    fun tearDown() = h.close()

    private fun mediaPort(bridge: Bridge): Int = bridge.state()["mediaBase"].toString().trim('"').substringAfterLast(':').toInt()

    private fun statusOf(host: String, port: Int): Int =
        h.http.send(HttpRequest.newBuilder(URI("http://$host:$port$unknown")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    private fun refuses(host: String, port: Int) {
        assertFailsWith<ConnectException> { Socket().use { it.connect(InetSocketAddress(host, port), 1000) } }
    }

    private fun blocker(port: Int): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(other, port))
    }

    private fun attempts() = h.events.filter { it.first == "media-rebind" }.map { it.second }

    @Test
    fun aBusyAddressIsRetriedWithBackoffUntilItIsFree() = h.run<Unit> {
        val bridge = h.newBridge(mediaRebindBackoffMs = List(20) { 100L })
        bridge.start()
        val port = mediaPort(bridge)
        val busy = blocker(port)
        val releaser = Thread {
            Thread.sleep(250)
            busy.close()
        }.apply { isDaemon = true; start() }

        bridge.networkLost()
        bridge.rebindNetwork(h.network(other))
        releaser.join()

        assertEquals(404, statusOf("127.0.0.2", port), "the media server answers on the new address")
        refuses("127.0.0.1", port)
        val log = attempts()
        assertTrue(log.any { it["ok"] == false }, "at least one attempt failed: $log")
        assertTrue(log.any { it["ok"] == true }, "and a later one succeeded: $log")
    }

    @Test
    fun aMediaServerThatStayedDownIsRebornByTheNextHousekeepingPass() = h.run<Unit> {
        val bridge = h.newBridge(mediaRebindBackoffMs = listOf(30L, 30L), currentNetwork = { h.network(other) })
        bridge.start()
        val port = mediaPort(bridge)
        val busy = blocker(port)

        bridge.networkLost()
        bridge.rebindNetwork(h.network(other))
        assertTrue(
            h.events.any { it.first == "bridge-error" && (it.second["where"] as? String)?.contains("media") == true },
            "the failed step is logged: ${h.events.takeLast(10)}",
        )
        assertEquals(3, attempts().count { it["ok"] == false }, "the first try and both retries failed")

        busy.close()
        delay(100)
        refuses("127.0.0.2", port)

        assertTrue(bridge.housekeeping("adb"))
        assertEquals(404, statusOf("127.0.0.2", port), "housekeeping rebound the media server")
        val repair = h.events.filter { it.first == "media-repair" }.map { it.second }
        assertTrue(repair.any { it["ok"] == true }, "a media-repair event with ok = true: $repair")
    }

    @Test
    fun aListeningMediaServerIsLeftAlone() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.start()
        assertTrue(bridge.housekeeping("adb"))
        assertTrue(h.events.none { it.first == "media-repair" }, "no repair when the media server listens: ${h.events.takeLast(10)}")
    }

    @Test
    fun aSameAddressNetworkReturnDoesNotRestartTheMediaServer() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.start()
        val port = mediaPort(bridge)

        bridge.networkLost()
        bridge.rebindNetwork(h.network(h.loopback))

        assertEquals(404, statusOf("127.0.0.1", port), "the media server remains available at its existing address")
        assertTrue(attempts().isEmpty(), "same-address recovery does not restart the media listener")
    }
}
