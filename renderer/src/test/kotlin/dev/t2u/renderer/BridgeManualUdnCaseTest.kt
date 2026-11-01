// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.renderer

// D-9 (Session GUI-D steps 3 and 6): a real Sonos serves its MediaRenderer UDN in upper case (uuid:RINCON_..._MR) while every
// UDN the bridge holds is lower case (udnFromUsn). A saved speaker must still count as answering its HTTP liveness fetch.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val UPPER_UDN = "RINCON_0A1B2C3D4E5F01400_MR"
private const val LOWER_UDN = "rincon_0a1b2c3d4e5f01400_mr"
private const val OTHER_UDN = "rincon_000e58aaaaaa01400_mr"

class BridgeManualUdnCaseTest {
    private val h = BridgeHarness(rendererUdn = UPPER_UDN)

    @AfterEach
    fun tearDown() = h.close()

    /** A bridge whose multicast discovery goes to a port nobody listens on, so only a manual add or a saved entry finds the speaker. */
    private fun blindBridge(
        manual: ManualSpeakers? = null,
        manualProbeIntervalMs: Long? = null,
        manualDescriptionUrl: ((String) -> String)? = null,
        manualSsdpGraceMs: Long? = null,
    ): Bridge =
        h.newBridge(
            searchTarget = InetSocketAddress(h.loopback, h.freePort()),
            manualSsdpPort = h.speakerPort,
            manual = manual,
            manualProbeIntervalMs = manualProbeIntervalMs,
            manualDescriptionUrl = manualDescriptionUrl,
            manualSsdpGraceMs = manualSsdpGraceMs,
        )

    /** The fake speaker's description URL, whatever host the bridge asks for (it only listens on loopback). */
    private val fakeDescription: (String) -> String = { h.fakeRenderer.location }

    /** A deaf speaker added through its description and saved; returns the store (the first bridge is stopped). */
    private suspend fun savedDeafSpeaker(): ManualSpeakers {
        h.speakerSearchDeaf = true
        val store = ManualSpeakers(h.dataDir).load()
        val first = blindBridge(manual = store, manualDescriptionUrl = fakeDescription, manualSsdpGraceMs = 300)
        first.start()
        assertTrue(first.addManual(h.loopback) is ManualAddResult.Added)
        first.stop()
        return store
    }

    @Test
    @DisplayName("a deaf speaker with an upper-case UDN is added by its address under the lower-case UDN")
    fun aDeafSpeakerWithAnUpperCaseUdnIsAddedUnderTheLowerCaseUdn() = h.run<Unit> {
        h.speakerSearchDeaf = true
        val store = ManualSpeakers(h.dataDir).load()
        val bridge = blindBridge(manual = store, manualDescriptionUrl = fakeDescription, manualSsdpGraceMs = 300)
        bridge.start()

        val result = bridge.addManual(h.loopback)

        val added = result as? ManualAddResult.Added ?: throw AssertionError("expected Added, got $result")
        assertEquals(LOWER_UDN, added.udn)
        assertEquals(LOWER_UDN, store.entries.value.single().udn)
        h.awaitUntil("the speaker listed") { bridge.speakers.value.size == 1 }
        assertEquals(LOWER_UDN, bridge.speakers.value.single().udn)
    }

    @Test
    @DisplayName("a saved speaker with an upper-case description UDN is set up at the next start")
    fun aSavedSpeakerWithAnUpperCaseDescriptionUdnIsSetUpAtTheNextStart() = h.run<Unit> {
        savedDeafSpeaker()

        val second = blindBridge(manual = ManualSpeakers(h.dataDir).load(), manualDescriptionUrl = fakeDescription)
        second.start()

        h.awaitUntil("the saved speaker online again", timeoutMs = 3_000) { second.speakers.value.singleOrNull()?.online == true }
        val card = second.speakers.value.single()
        assertEquals(LOWER_UDN, card.udn)
        assertTrue(card.manual)
        assertTrue(h.dialSearchAt(second, h.loopback) != null, "a DIAL cast target")
        assertTrue(
            h.events.any { it.first == "manual-probe" && it.second["result"] == "Answered" && it.second["via"] == "http" },
            "manual-probe Answered via http: ${h.events.takeLast(8)}",
        )
        assertFalse(h.events.any { it.first == "manual-probe" && it.second["result"] == "OtherDevice" }, "no OtherDevice")
    }

    @Test
    @DisplayName("a saved speaker with an upper-case description UDN stays online across probe rounds")
    fun aSavedSpeakerWithAnUpperCaseDescriptionUdnStaysOnlineAcrossProbeRounds() = h.run<Unit> {
        savedDeafSpeaker()
        val second = blindBridge(
            manual = ManualSpeakers(h.dataDir).load(),
            manualProbeIntervalMs = 200,
            manualDescriptionUrl = fakeDescription,
        )
        second.start()
        h.awaitUntil("the saved speaker online", timeoutMs = 3_000) { second.speakers.value.singleOrNull()?.online == true }
        val requestsBefore = h.fakeRenderer.descriptionRequests.get()

        val until = System.nanoTime() + 1_500_000_000L
        while (System.nanoTime() < until) {
            assertTrue(second.speakers.value.singleOrNull()?.online == true, "it left the list or went offline: ${second.speakers.value}")
            delay(50)
        }

        assertFalse(h.events.any { it.first == "manual-offline" }, "no manual-offline event")
        assertFalse(h.events.any { it.first == "manual-probe" && it.second["result"] == "OtherDevice" }, "no OtherDevice")
        val requests = h.fakeRenderer.descriptionRequests.get() - requestsBefore
        assertTrue(requests >= 3, "description requests over the rounds: $requests")
    }

    @Test
    @DisplayName("a saved entry whose UDN differs from the description stays OtherDevice")
    fun aSavedEntryWhoseUdnDiffersFromTheDescriptionStaysOtherDevice() = h.run<Unit> {
        h.speakerSearchDeaf = true
        val store = ManualSpeakers(h.dataDir).load()
        assertTrue(store.add(ManualSpeaker("127.0.0.1", OTHER_UDN, "Other", h.fakeRenderer.location)))
        val bridge = blindBridge(
            manual = store,
            manualProbeIntervalMs = 200,
            manualDescriptionUrl = fakeDescription,
        )
        bridge.start()

        h.awaitUntil("a manual-probe OtherDevice via http", timeoutMs = 2_000) {
            h.events.any { it.first == "manual-probe" && it.second["result"] == "OtherDevice" && it.second["via"] == "http" }
        }
        delay(500)

        assertFalse(h.events.any { it.first == "manual-probe" && it.second["result"] == "Answered" }, "never Answered")
        assertTrue(bridge.speakers.value.none { it.online }, "never online: ${bridge.speakers.value}")
    }
}
