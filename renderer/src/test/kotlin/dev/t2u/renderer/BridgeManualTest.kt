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
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Test
    @DisplayName("parseManualIp accepts private dotted quads only")
    fun parseManualIpAcceptsPrivateDottedQuadsOnly() {
        for (ok in listOf(" 192.168.1.40 ", "10.0.0.5", "169.254.3.4", "172.16.0.9")) {
            assertNotNull(parseManualIp(ok), "'$ok' is accepted")
        }
        for (bad in listOf(
            "8.8.8.8", "127.0.0.1", "224.0.0.251", "0.0.0.0", "speaker.local", "192.168.001.40", "0xc0.168.1.40", "3232235816",
            "192.168.1", "", "   ", "::1", "fe80::1",
        )) {
            assertNull(parseManualIp(bad), "'$bad' is refused")
        }
    }

    @Test
    @DisplayName("the bridge refuses a public address without sending anything")
    fun theBridgeRefusesAPublicAddressWithoutSendingAnything() = h.run<Unit> {
        val bridge = blindBridge()
        bridge.start()
        val before = h.searchesSeen.get()
        val refused = listOf(
            InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)),
            InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte())),
            InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)),
            InetAddress.getByName("::1"),
        )
        for (ip in refused) {
            assertEquals(ManualAddResult.Refused, bridge.addManual(ip), "${ip.hostAddress} is refused")
        }
        delay(300)
        assertEquals(before, h.searchesSeen.get(), "the harness speaker's socket saw no search")
        assertFalse(h.events.any { it.first == "bridge-error" }, "a send would have failed or been logged: ${h.events.takeLast(5)}")
        assertTrue(bridge.speakers.value.isEmpty())
    }

    @Test
    @DisplayName("an address that does not answer gives NoAnswer")
    fun anAddressThatDoesNotAnswerGivesNoAnswer() = h.run<Unit> {
        val bridge = blindBridge(manualAnswerTimeoutMs = 500, manualSsdpPort = h.freePort())
        bridge.start()

        assertEquals(ManualAddResult.NoAnswer, bridge.addManual(h.loopback))

        assertTrue(bridge.speakers.value.isEmpty(), "nothing is listed")
        assertEquals(0, h.searchesSeen.get())
    }

    @Test
    @DisplayName("a device that answers but is not a usable speaker gives NotUsable")
    fun aDeviceThatAnswersButIsNotAUsableSpeakerGivesNotUsable() = h.run<Unit> {
        // the registry refuses the fake speaker by its SERVER header, as it does a Quick Share device
        val bridge = h.newBridge(
            config = h.testConfig().copy(excludeServers = listOf("fake")),
            searchTarget = InetSocketAddress(h.loopback, h.freePort()),
            manualSsdpPort = h.speakerPort,
            manualAnswerTimeoutMs = 800,
        )
        bridge.start()

        val result = bridge.addManual(h.loopback)

        assertTrue(result is ManualAddResult.NotUsable, "got $result")
        assertTrue(bridge.speakers.value.isEmpty())
    }

    @Test
    @DisplayName("a hidden speaker added by address stays hidden")
    fun aHiddenSpeakerAddedByAddressStaysHidden() = h.run<Unit> {
        val hidden = HiddenSpeakers(h.dataDir).load()
        assertTrue(hidden.add(HiddenSpeaker(HARNESS_RENDERER_UDN, "Living Room Speaker", "127.0.0.1")))
        val bridge = blindBridge(hidden = hidden)
        bridge.start()

        val result = bridge.addManual(h.loopback)

        assertEquals(ManualAddResult.Hidden(HARNESS_RENDERER_UDN), result)
        delay(300)
        assertTrue(bridge.speakers.value.isEmpty(), "nothing is listed")
    }

    @Test
    @DisplayName("a speaker that is already listed is reported as such")
    fun aSpeakerThatIsAlreadyListedIsReportedAsSuch() = h.run<Unit> {
        val bridge = h.newBridge(manualSsdpPort = h.speakerPort)
        bridge.start()
        h.awaitUntil("the speaker listed by discovery") { bridge.speakers.value.size == 1 }

        val result = bridge.addManual(h.loopback)

        val listed = result as? ManualAddResult.AlreadyListed ?: throw AssertionError("expected AlreadyListed, got $result")
        assertEquals(HARNESS_RENDERER_UDN, listed.udn)
        assertEquals(1, bridge.speakers.value.size)
    }
}
