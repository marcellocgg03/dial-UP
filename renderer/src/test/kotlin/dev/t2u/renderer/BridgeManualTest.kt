// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
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
        manualDescriptionUrl: ((String) -> String)? = null,
        manualSsdpGraceMs: Long? = null,
        names: RendererNames? = null,
    ): Bridge =
        h.newBridge(
            searchTarget = InetSocketAddress(h.loopback, h.freePort()),
            manualSsdpPort = manualSsdpPort ?: h.speakerPort,
            manualAnswerTimeoutMs = manualAnswerTimeoutMs,
            hidden = hidden,
            manual = manual,
            manualProbeIntervalMs = manualProbeIntervalMs,
            manualDescriptionUrl = manualDescriptionUrl,
            manualSsdpGraceMs = manualSsdpGraceMs,
            names = names,
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
    private suspend fun upManualBridge(hidden: HiddenSpeakers? = null): Pair<Bridge, ManualSpeakers> {
        val store = ManualSpeakers(h.dataDir).load()
        val bridge = blindBridge(manual = store, manualProbeIntervalMs = 200, hidden = hidden)
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

    @Test
    @DisplayName("Remove forgets a manual speaker")
    fun removeForgetsAManualSpeaker() = h.run<Unit> {
        val (bridge, store) = upManualBridge()

        assertTrue(bridge.removeManual(HARNESS_RENDERER_UDN))

        assertTrue(store.entries.value.isEmpty())
        assertTrue(ManualSpeakers(h.dataDir).load().entries.value.isEmpty(), "gone from manual.json")
        h.awaitUntil("the speaker gone from the list") { bridge.speakers.value.isEmpty() }
        assertNull(h.dialSearchAt(bridge, h.loopback), "no longer advertised")
        assertFalse(bridge.removeManual(HARNESS_RENDERER_UDN), "a second remove finds nothing")
        val seen = h.searchesSeen.get()
        delay(700)
        assertEquals(seen, h.searchesSeen.get(), "no more unicast searches to the removed address")
    }

    @Test
    @DisplayName("Remove of an offline manual speaker")
    fun removeOfAnOfflineManualSpeaker() = h.run<Unit> {
        val (bridge, store) = upManualBridge()
        h.speakerSilent = true
        h.awaitUntil("the speaker offline", timeoutMs = 2_000) { bridge.speakers.value.singleOrNull()?.online == false }

        assertTrue(bridge.removeManual(HARNESS_RENDERER_UDN))

        assertTrue(store.entries.value.isEmpty())
        h.awaitUntil("no greyed card remains") { bridge.speakers.value.isEmpty() }
    }

    @Test
    @DisplayName("Delete of a hidden manual speaker leaves it unhidden, unnamed and gone from manual.json")
    fun deleteOfAHiddenManualSpeakerLeavesNoResidue() = h.run<Unit> {
        val hidden = HiddenSpeakers(h.dataDir).load()
        val names = RendererNames(h.dataDir).load()
        val store = ManualSpeakers(h.dataDir).load()
        val bridge = blindBridge(manual = store, manualProbeIntervalMs = 200, hidden = hidden, names = names)
        bridge.start()
        assertTrue(bridge.addManual(h.loopback) is ManualAddResult.Added)
        h.awaitUntil("the speaker listed") { bridge.speakers.value.size == 1 }
        assertTrue(bridge.rename(HARNESS_RENDERER_UDN, "Kitchen"))
        assertTrue(bridge.hide(HARNESS_RENDERER_UDN))
        h.awaitUntil("the list empty") { bridge.speakers.value.isEmpty() }

        assertTrue(bridge.removeManual(HARNESS_RENDERER_UDN))

        assertFalse(hidden.contains(HARNESS_RENDERER_UDN), "unhidden")
        assertTrue(hidden.entries.value.isEmpty())
        assertNull(names.get(HARNESS_RENDERER_UDN), "its user name is dropped")
        assertTrue(store.entries.value.isEmpty())
        assertTrue(ManualSpeakers(h.dataDir).load().entries.value.isEmpty(), "gone from manual.json")
        assertTrue(HiddenSpeakers(h.dataDir).load().entries.value.isEmpty(), "gone from hidden.json")
    }

    // ------------------------------------------------------------------------------------------ 261101-dcg: Hide replaces Remove

    @Test
    @DisplayName("Hide on a manual speaker keeps it off the list while it answers")
    fun hideOnAManualSpeakerKeepsItOffTheListWhileItAnswers() = h.run<Unit> {
        val hidden = HiddenSpeakers(h.dataDir).load()
        val (bridge, store) = upManualBridge(hidden)

        assertTrue(bridge.hide(HARNESS_RENDERER_UDN))

        h.awaitUntil("the list empty") { bridge.speakers.value.isEmpty() }
        val before = h.searchesSeen.get()
        h.awaitUntil("three probe rounds", timeoutMs = 3_000) { h.searchesSeen.get() - before >= 3 }
        assertTrue(bridge.speakers.value.isEmpty(), "no greyed card comes back")
        assertNull(h.dialSearchAt(bridge, h.loopback), "no DIAL reply for a hidden receiver")
        assertEquals(1, store.entries.value.size, "still saved")
        assertEquals(1, ManualSpeakers(h.dataDir).load().entries.value.size, "still in manual.json")
        assertTrue(hidden.contains(HARNESS_RENDERER_UDN))
    }

    @Test
    @DisplayName("Hide on an offline manual speaker drops its greyed card and keeps it saved")
    fun hideOnAnOfflineManualSpeakerDropsItsGreyedCardAndKeepsItSaved() = h.run<Unit> {
        val hidden = HiddenSpeakers(h.dataDir).load()
        val (bridge, store) = upManualBridge(hidden)
        h.speakerSilent = true
        h.awaitUntil("the speaker offline", timeoutMs = 2_000) { bridge.speakers.value.singleOrNull()?.online == false }

        assertTrue(bridge.hide(HARNESS_RENDERER_UDN))

        h.awaitUntil("the list empty") { bridge.speakers.value.isEmpty() }
        val entry = hidden.entries.value.single()
        assertEquals(HARNESS_RENDERER_UDN, entry.udn)
        assertEquals(h.loopback.hostAddress, entry.address)
        assertEquals(1, store.entries.value.size, "still saved")
        assertTrue(h.events.any { it.first == "hidden" }, "hidden is logged")
    }

    @Test
    @DisplayName("Restore brings a hidden manual speaker back")
    fun restoreBringsAHiddenManualSpeakerBack() = h.run<Unit> {
        val hidden = HiddenSpeakers(h.dataDir).load()
        val (bridge, _) = upManualBridge(hidden)
        assertTrue(bridge.hide(HARNESS_RENDERER_UDN))
        h.awaitUntil("the list empty") { bridge.speakers.value.isEmpty() }

        assertTrue(bridge.unhide(HARNESS_RENDERER_UDN))

        h.awaitUntil("the speaker listed again", timeoutMs = 3_000) { bridge.speakers.value.singleOrNull()?.online == true }
        assertTrue(bridge.speakers.value.single().manual)
    }

    // ------------------------------------------------------------------------------------------ plan 07-17: HTTP description fallback

    /** The fake speaker's description URL, whatever host the bridge asks for (it only listens on loopback). */
    private val fakeDescription: (String) -> String = { h.fakeRenderer.location }

    @Test
    @DisplayName("a speaker deaf to unicast search is added through its description")
    fun aSpeakerDeafToUnicastSearchIsAddedThroughItsDescription() = h.run<Unit> {
        h.speakerSearchDeaf = true
        val store = ManualSpeakers(h.dataDir).load()
        val bridge = blindBridge(manual = store, manualDescriptionUrl = fakeDescription, manualSsdpGraceMs = 300)
        bridge.start()
        delay(300)
        assertTrue(bridge.speakers.value.isEmpty())

        val result = bridge.addManual(h.loopback)

        val added = result as? ManualAddResult.Added ?: throw AssertionError("expected Added, got $result")
        assertEquals(HARNESS_RENDERER_UDN, added.udn)
        assertTrue(h.searchesSeen.get() >= 1, "the unicast search was tried first")
        h.awaitUntil("the speaker listed") { bridge.speakers.value.size == 1 }
        assertTrue(h.dialSearchAt(bridge, h.loopback) != null, "a DIAL cast target")
        val saved = store.entries.value.single()
        assertEquals(HARNESS_RENDERER_UDN, saved.udn)
        assertEquals(h.fakeRenderer.location, saved.location)
        assertTrue(h.events.any { it.first == "manual-add" && it.second["via"] == "http" }, "manual-add via http: ${h.events.takeLast(5)}")
    }

    @Test
    @DisplayName("the fallback refuses a description URL on another host")
    fun theFallbackRefusesADescriptionUrlOnAnotherHost() = h.run<Unit> {
        h.speakerSearchDeaf = true
        // localhost reaches the fake renderer, so a request that was sent would make this an Added
        val bridge = blindBridge(
            manualAnswerTimeoutMs = 1_200,
            manualDescriptionUrl = { _ -> h.fakeRenderer.location.replace("127.0.0.1", "localhost") },
            manualSsdpGraceMs = 300,
        )
        bridge.start()

        assertEquals(ManualAddResult.NoAnswer, bridge.addManual(h.loopback))

        assertTrue(bridge.speakers.value.isEmpty())
    }

    @Test
    @DisplayName("an already listed speaker is reported at once even when deaf to unicast")
    fun anAlreadyListedSpeakerIsReportedAtOnceEvenWhenDeafToUnicast() = h.run<Unit> {
        val store = ManualSpeakers(h.dataDir).load()
        val bridge = h.newBridge(manualSsdpPort = h.speakerPort, manual = store)
        bridge.start()
        h.awaitUntil("the speaker listed by discovery") { bridge.speakers.value.size == 1 }
        h.speakerSearchDeaf = true
        val seen = h.searchesSeen.get()

        val started = System.nanoTime()
        val result = bridge.addManual(h.loopback)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(result is ManualAddResult.AlreadyListed, "got $result")
        assertTrue(tookMs < 500, "answered in $tookMs ms")
        assertEquals(seen, h.searchesSeen.get(), "no unicast search went out")
        assertEquals(HARNESS_RENDERER_UDN, store.entries.value.single().udn)
    }

    @Test
    @DisplayName("a hidden speaker added through its description stays hidden")
    fun aHiddenSpeakerAddedThroughItsDescriptionStaysHidden() = h.run<Unit> {
        h.speakerSearchDeaf = true
        val hidden = HiddenSpeakers(h.dataDir).load()
        assertTrue(hidden.add(HiddenSpeaker(HARNESS_RENDERER_UDN, "Living Room Speaker", "127.0.0.1")))
        val bridge = blindBridge(hidden = hidden, manualDescriptionUrl = fakeDescription, manualSsdpGraceMs = 300)
        bridge.start()

        val result = bridge.addManual(h.loopback)

        assertEquals(ManualAddResult.Hidden(HARNESS_RENDERER_UDN), result)
        delay(300)
        assertTrue(bridge.speakers.value.isEmpty(), "nothing is listed")
        assertNull(h.dialSearchAt(bridge, h.loopback), "no receiver")
    }

    @Test
    @DisplayName("a description that is not a renderer gives NotUsable")
    fun aDescriptionThatIsNotARendererGivesNotUsable() = h.run<Unit> {
        h.speakerSearchDeaf = true
        // an scpd document is XML, names no device with AVTransport and carries no usable UDN
        val bridge = blindBridge(
            manualAnswerTimeoutMs = 1_500,
            manualDescriptionUrl = { _ -> h.fakeRenderer.location.replace("/desc.xml", "/scpd/AVTransport.xml") },
            manualSsdpGraceMs = 300,
        )
        bridge.start()

        val result = bridge.addManual(h.loopback)

        assertTrue(result is ManualAddResult.NotUsable, "got $result")
        assertTrue(bridge.speakers.value.isEmpty())
    }

    // ------------------------------------------------------------------------------------------ plan 07-18: saved speakers judged by description

    /** A deaf speaker added through its description and saved; returns the running first bridge's store (the bridge is stopped). */
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
    @DisplayName("a saved speaker deaf to unicast search is set up at the next start")
    fun aSavedSpeakerDeafToUnicastSearchIsSetUpAtTheNextStart() = h.run<Unit> {
        savedDeafSpeaker()

        val second = blindBridge(manual = ManualSpeakers(h.dataDir).load(), manualDescriptionUrl = fakeDescription)
        second.start()

        // a saved speaker is listed greyed from the start, so "set up" means online
        h.awaitUntil("the saved deaf speaker online again", timeoutMs = 3_000) { second.speakers.value.singleOrNull()?.online == true }
        assertEquals(HARNESS_RENDERER_UDN, second.speakers.value.single().udn)
        assertTrue(second.speakers.value.single().manual)
        assertTrue(h.dialSearchAt(second, h.loopback) != null, "a DIAL cast target")
    }

    @Test
    @DisplayName("a saved deaf speaker stays online across probe rounds")
    fun aSavedDeafSpeakerStaysOnlineAcrossProbeRounds() = h.run<Unit> {
        savedDeafSpeaker()
        val second = blindBridge(
            manual = ManualSpeakers(h.dataDir).load(),
            manualProbeIntervalMs = 200,
            manualDescriptionUrl = fakeDescription,
        )
        second.start()
        h.awaitUntil("the saved deaf speaker online", timeoutMs = 3_000) { second.speakers.value.singleOrNull()?.online == true }
        val requestsBefore = h.fakeRenderer.descriptionRequests.get()

        val until = System.nanoTime() + 1_500_000_000L
        while (System.nanoTime() < until) {
            assertTrue(second.speakers.value.singleOrNull()?.online == true, "it left the list or went offline: ${second.speakers.value}")
            delay(50)
        }

        assertFalse(h.events.any { it.first == "manual-offline" }, "no manual-offline event")
        val requests = h.fakeRenderer.descriptionRequests.get() - requestsBefore
        assertTrue(requests >= 3, "description requests over the rounds: $requests")
    }

    @Test
    @DisplayName("an unplugged saved speaker goes offline and comes back")
    fun anUnpluggedSavedSpeakerGoesOfflineAndComesBack() = h.run<Unit> {
        savedDeafSpeaker()
        val bridge = blindBridge(
            manual = ManualSpeakers(h.dataDir).load(),
            manualProbeIntervalMs = 200,
            manualDescriptionUrl = fakeDescription,
        )
        bridge.start()
        h.awaitUntil("the saved deaf speaker online", timeoutMs = 3_000) { bridge.speakers.value.singleOrNull()?.online == true }
        assertTrue(h.dialSearchAt(bridge, h.loopback) != null, "a cast target while it answers")

        h.speakerSilent = true

        h.awaitUntil("the speaker offline", timeoutMs = 2_000) { bridge.speakers.value.singleOrNull()?.online == false }
        val card = bridge.speakers.value.single()
        assertTrue(card.manual)
        assertNull(h.dialSearchAt(bridge, h.loopback), "an offline speaker is not advertised")

        h.speakerSilent = false

        h.awaitUntil("the speaker online again", timeoutMs = 2_000) { bridge.speakers.value.singleOrNull()?.online == true }
        h.awaitUntil("a DIAL search reply again", timeoutMs = 2_000) { h.dialSearchAt(bridge, h.loopback) != null }
    }

    // ------------------------------------------------------------------------------------------ quick 261102-dmv: D-11, stale saved address

    @Test
    @DisplayName("a saved entry with a stale address does not drop the live renderer of the same UDN and adopts the live address")
    fun aStaleSavedAddressDoesNotDropTheLiveRendererOfTheSameUdn() = h.run<Unit> {
        // the speaker moved: manual.json still holds an address that never answers; discovery finds it at 127.0.0.1
        val store = ManualSpeakers(h.dataDir).load()
        val stale = ManualSpeaker("127.0.0.2", HARNESS_RENDERER_UDN, "Old name", "http://127.0.0.2:${h.speakerPort}/xml/device.xml")
        assertTrue(store.add(stale))
        val bridge = h.newBridge(manual = store, manualProbeIntervalMs = 200)
        bridge.start()
        h.awaitUntil("the speaker found by discovery", timeoutMs = 3_000) { bridge.speakers.value.singleOrNull()?.online == true }
        assertTrue(h.dialSearchAt(bridge, h.loopback) != null, "a cast target while it is discovered")

        // far more than the two missed rounds the old code needed to drop it (200 ms rounds)
        val until = System.nanoTime() + 1_500_000_000L
        while (System.nanoTime() < until) {
            assertTrue(bridge.speakers.value.singleOrNull()?.online == true, "it went offline or left: ${bridge.speakers.value}")
            delay(50)
        }

        assertFalse(h.events.any { it.first == "manual-offline" }, "no manual-offline event for the live renderer")
        assertTrue(h.dialSearchAt(bridge, h.loopback) != null, "still a cast target")
        val saved = store.entries.value.single()
        assertEquals(HARNESS_RENDERER_UDN, saved.udn)
        assertEquals("127.0.0.1", saved.ip, "manual.json follows the speaker to its discovered address")
        assertEquals("Old name", saved.name)
        assertTrue(saved.location.startsWith("http://127.0.0.1:"), "location follows too: ${saved.location}")
        assertTrue(h.events.any { it.first == "manual-moved" }, "the move is logged")
        val onDisk = java.io.File(h.dataDir, "manual.json").readText()
        assertTrue("127.0.0.1" in onDisk && "127.0.0.2" !in onDisk, "the file holds the new address: $onDisk")
    }

    @Test
    @DisplayName("saved addresses are not evicted from the reply table")
    fun savedAddressesAreNotEvictedFromTheReplyTable() = h.run<Unit> {
        savedDeafSpeaker()
        val bridge = blindBridge(manual = ManualSpeakers(h.dataDir).load(), manualDescriptionUrl = fakeDescription)
        bridge.start()
        h.awaitUntil("the saved deaf speaker online", timeoutMs = 3_000) { bridge.speakers.value.singleOrNull()?.online == true }
        assertNotNull(bridge.replySourceAt("127.0.0.1"), "the saved address replied")

        for (i in 1..70) bridge.noteReply("10.9.9.$i", "other-$i")

        assertNotNull(bridge.replySourceAt("127.0.0.1"), "the saved address is still in the table")
    }
}
