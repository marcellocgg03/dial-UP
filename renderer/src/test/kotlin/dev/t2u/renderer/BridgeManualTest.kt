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
    private fun blindBridge(
        manualAnswerTimeoutMs: Long? = null,
        manualSsdpPort: Int? = null,
        hidden: HiddenSpeakers? = null,
        manual: ManualSpeakers? = null,
        manualProbeIntervalMs: Long? = null,
    ): Bridge =
        h.newBridge(
            searchTarget = InetSocketAddress(h.loopback, h.freePort()),
            manualSsdpPort = manualSsdpPort ?: h.speakerPort,
            manualAnswerTimeoutMs = manualAnswerTimeoutMs,
            hidden = hidden,
            manual = manual,
            manualProbeIntervalMs = manualProbeIntervalMs,
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

    @Test
    @DisplayName("an added speaker is saved and probed at the next start")
    fun anAddedSpeakerIsSavedAndProbedAtTheNextStart() = h.run<Unit> {
        val first = blindBridge(manual = ManualSpeakers(h.dataDir).load())
        first.start()
        assertTrue(first.addManual(h.loopback) is ManualAddResult.Added)

        val saved = ManualSpeakers(h.dataDir).load().entries.value.single()
        assertEquals("127.0.0.1", saved.ip)
        assertEquals(HARNESS_RENDERER_UDN, saved.udn)
        assertEquals(first.speakers.value.single().name, saved.name)
        assertTrue(saved.location.startsWith("http://127.0.0.1:"), "location ${saved.location}")
        first.stop()

        // a second bridge on the same data directory, with a freshly loaded store and blind discovery, lists it without an add
        val second = blindBridge(manual = ManualSpeakers(h.dataDir).load())
        second.start()
        h.awaitUntil("the saved speaker listed again") { second.speakers.value.size == 1 }
        assertEquals(HARNESS_RENDERER_UDN, second.speakers.value.single().udn)
    }

    @Test
    @DisplayName("a failed add saves nothing")
    fun aFailedAddSavesNothing() = h.run<Unit> {
        val manualFile = java.io.File(h.dataDir, "manual.json")
        val store = ManualSpeakers(h.dataDir).load()
        val silent = blindBridge(manualAnswerTimeoutMs = 400, manualSsdpPort = h.freePort(), manual = store)
        silent.start()

        assertEquals(ManualAddResult.NoAnswer, silent.addManual(h.loopback))
        assertEquals(ManualAddResult.Refused, silent.addManual(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))))

        assertTrue(store.entries.value.isEmpty())
        assertFalse(manualFile.exists(), "no file was written")
    }
    // ------------------------------------------------------------------------------------------ plan 07-14: offline, back, remove

    /** A bridge with a saved manual speaker that is up, probing every 200 ms. */
    private suspend fun upManualBridge(): Pair<Bridge, ManualSpeakers> {
        val store = ManualSpeakers(h.dataDir).load()
        val bridge = blindBridge(manual = store, manualProbeIntervalMs = 200)
        bridge.start()
        assertTrue(bridge.addManual(h.loopback) is ManualAddResult.Added)
        h.awaitUntil("the speaker listed") { bridge.speakers.value.size == 1 }
        return bridge to store
    }

    @Test
    @DisplayName("a silent manual speaker goes offline and is no longer a cast target")
    fun aSilentManualSpeakerGoesOfflineAndIsNoLongerACastTarget() = h.run<Unit> {
        val (bridge, _) = upManualBridge()
        assertTrue(bridge.speakers.value.single().manual)
        assertTrue(h.dialSearchAt(bridge, h.loopback) != null, "a cast target while it answers")

        h.speakerSilent = true

        h.awaitUntil("the speaker offline", timeoutMs = 2_000) { bridge.speakers.value.singleOrNull()?.online == false }
        val card = bridge.speakers.value.single()
        assertTrue(card.manual)
        assertEquals(dev.t2u.lounge.PlayerStatus.IDLE, card.status)
        assertEquals(HARNESS_RENDERER_UDN, card.udn)
        assertNull(h.dialSearchAt(bridge, h.loopback), "an offline speaker is not advertised")
        assertEquals(0, bridge.castCount.value.total)
        assertTrue(h.events.any { it.first == "manual-offline" }, "manual-offline is logged")
    }

    @Test
    @DisplayName("an offline manual speaker comes back when it answers")
    fun anOfflineManualSpeakerComesBackWhenItAnswers() = h.run<Unit> {
        val (bridge, _) = upManualBridge()
        h.speakerSilent = true
        h.awaitUntil("the speaker offline", timeoutMs = 2_000) { bridge.speakers.value.singleOrNull()?.online == false }

        h.speakerSilent = false

        h.awaitUntil("the speaker online again", timeoutMs = 2_000) { bridge.speakers.value.singleOrNull()?.online == true }
        assertTrue(bridge.speakers.value.single().manual)
        h.awaitUntil("a DIAL search reply again", timeoutMs = 2_000) { h.dialSearchAt(bridge, h.loopback) != null }
        // the card is back as soon as the reply sets the speaker up; the probe round that counts the reply logs the transition
        h.awaitUntil("manual-online logged", timeoutMs = 2_000) { h.events.any { it.first == "manual-online" } }
    }

    @Test
    @DisplayName("an answering manual speaker is probed every interval")
    fun anAnsweringManualSpeakerIsProbedEveryInterval() = h.run<Unit> {
        val (bridge, _) = upManualBridge()
        val before = h.searchesSeen.get()

        delay(1_000)

        assertTrue(h.searchesSeen.get() - before >= 3, "unicast searches seen: ${h.searchesSeen.get() - before}")
        assertTrue(bridge.speakers.value.single().online, "it never went offline")
    }
}
