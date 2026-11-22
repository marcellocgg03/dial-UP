// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// NET-01 (RENDERER CR-01, phase 11): an ssdp:byebye takes a running speaker out of the cast menu only when the datagram comes
// from the address the speaker was set up with. A NOTIFY from another host on the LAN, however well formed, leaves it running.
// The datagrams go through the bridge's real SSDP listener (the responder's loop socket) into the registry, from the loopback
// addresses 127.0.0.2 (another host) and 127.0.0.1 (the fake speaker itself).
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeByebyeTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() = h.close()

    /** One NOTIFY ssdp:byebye for the harness speaker, sent to the bridge's SSDP listener from [sourceIp]. */
    private fun byebyeFrom(bridge: Bridge, sourceIp: String) {
        val text = listOf(
            "NOTIFY * HTTP/1.1",
            "HOST: 239.255.255.250:1900",
            "NT: urn:schemas-upnp-org:device:MediaRenderer:1",
            "NTS: ssdp:byebye",
            "USN: uuid:$HARNESS_RENDERER_UDN::urn:schemas-upnp-org:device:MediaRenderer:1",
            "",
            "",
        ).joinToString("\r\n").toByteArray(Charsets.UTF_8)
        DatagramSocket(InetSocketAddress(InetAddress.getByName(sourceIp), 0)).use { s ->
            s.send(DatagramPacket(text, text.size, InetSocketAddress(h.loopback, bridge.ssdpPorts().getValue("loop"))))
        }
    }

    private fun removedEvents() = h.events.filter { (kind, fields) ->
        kind == "discovery" && fields["event"] == "removed" && fields["udn"] == HARNESS_RENDERER_UDN
    }

    private fun speakerListed(bridge: Bridge) = bridge.state()["renderers"]?.toString()?.contains(HARNESS_RENDERER_UDN) == true

    @Test
    fun `a byebye for the running speaker sent from another LAN host leaves it running`() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig())
        bridge.start()
        h.awaitUntil("one renderer up") { speakerListed(bridge) }

        byebyeFrom(bridge, "127.0.0.2")
        delay(1000)

        assertEquals(emptyList(), removedEvents(), "a forged byebye removed the speaker: ${h.events.takeLast(10)}")
        assertTrue(speakerListed(bridge), "the speaker is still in the registry: ${bridge.state()["renderers"]}")
    }

    @Test
    fun `the speaker's own byebye still removes it`() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig())
        bridge.start()
        h.awaitUntil("one renderer up") { speakerListed(bridge) }

        byebyeFrom(bridge, "127.0.0.1")

        h.awaitUntil("the speaker removed by its own byebye", timeoutMs = 5_000) { removedEvents().isNotEmpty() }
    }
}
