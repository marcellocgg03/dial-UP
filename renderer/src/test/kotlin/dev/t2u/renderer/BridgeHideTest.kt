// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.renderer

// GUI-03 (plan 07-11, D-08): Hide on a speaker's card drops its cast target at once and keeps it out. The receiver stops
// (ssdp:byebye, DIAL port closed), the hidden set persists in hidden.json, a speaker that is mid-cast is stopped and its
// lounge session ended, and the speaker is emptied only when clear-when-cast-ends is on (RESEARCH A7).
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.upnp.RENDERER_ST
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VID = "AAAAAAAAAAA"

class BridgeHideTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() = h.close()

    private suspend fun awaitSpeakerUp(bridge: Bridge) {
        h.awaitUntil("one speaker listed") { bridge.speakers.value.size == 1 && bridge.castCount.value.total == 1 }
    }

    /** Starts [bridge], connects the sender and casts until the fake speaker holds one of our streams. */
    private suspend fun castUntilLoaded(bridge: Bridge) {
        bridge.start()
        h.launch(bridge)
        h.innerTube.scriptNext(VID, previousId = null, nextId = null)
        h.connectSender()
        h.rpc("cl", "setPlaylist", h.playlist(VID))
        h.awaitUntil("the speaker holding our stream") { h.fakeRenderer.state.currentUri.contains("/stream/") }
        h.awaitUntil("the speaker told to play") { h.speakerCount("Play") >= 1 }
    }

    private fun emptiedUris() = h.setAvTransportUris().count { it.isEmpty() }

    @Test
    @DisplayName("hiding a speaker removes its cast target and keeps it out")
    fun hidingRemovesTheCastTargetAndKeepsItOut() = h.run<Unit> {
        val hidden = HiddenSpeakers(h.dataDir).load()
        val bridge = h.newBridge(hidden = hidden)
        bridge.start()
        awaitSpeakerUp(bridge)
        h.awaitUntil("a DIAL search reply before the hide") { h.dialSearchAt(bridge, h.loopback) != null }

        assertTrue(bridge.hide(HARNESS_RENDERER_UDN))

        h.awaitUntil("the speaker gone from the list") { bridge.speakers.value.isEmpty() && bridge.castCount.value.total == 0 }
        assertNull(h.dialSearchAt(bridge, h.loopback), "a DIAL search gets no reply for the hidden receiver")
        val file = java.io.File(h.dataDir, "hidden.json")
        assertTrue(file.exists(), "hidden.json written")
        val text = file.readText()
        assertTrue(text.contains(HARNESS_RENDERER_UDN), text)
        assertTrue(text.contains("127.0.0.1"), "the address is kept: $text")
        assertTrue(hidden.contains(HARNESS_RENDERER_UDN))

        // the speaker answers another search; the bridge must still refuse it
        val searches = h.searchesSeen.get()
        bridge.ssdpClientForTest()?.search(RENDERER_ST, 3)
        h.awaitUntil("the speaker saw another search") { h.searchesSeen.get() > searches }
        delay(600)
        assertTrue(bridge.speakers.value.isEmpty(), "still hidden after the speaker answered: ${bridge.speakers.value}")
        assertEquals(0, bridge.castCount.value.total)
        assertNull(h.dialSearchAt(bridge, h.loopback))
    }

    @Test
    @DisplayName("hiding a speaker that is not there changes nothing")
    fun hidingAnUnknownSpeakerChangesNothing() = h.run<Unit> {
        val hidden = HiddenSpeakers(h.dataDir).load()
        val bridge = h.newBridge(hidden = hidden)
        bridge.start()
        awaitSpeakerUp(bridge)

        assertFalse(bridge.hide("not-a-speaker"))

        assertFalse(java.io.File(h.dataDir, "hidden.json").exists())
        assertEquals(1, bridge.speakers.value.size)
    }

    @Test
    @DisplayName("hiding a playing speaker stops it and ends the session")
    fun hidingAPlayingSpeakerStopsItAndEndsTheSession() = h.run<Unit> {
        val hidden = HiddenSpeakers(h.dataDir).load()
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = false), hidden = hidden)
        castUntilLoaded(bridge)
        val stopsBefore = h.speakerCount("Stop")
        val loaded = h.fakeRenderer.state.currentUri

        assertTrue(bridge.hide(HARNESS_RENDERER_UDN))

        assertTrue(h.speakerCount("Stop") > stopsBefore, "the speaker received a Stop: ${h.fakeRenderer.calls.map { it.action }}")
        h.awaitUntil("the sender told the screen disconnected") { h.count("loungeScreenDisconnected") >= 1 }
        assertEquals(0, emptiedUris(), "no empty SetAVTransportURI with the setting off: ${h.setAvTransportUris()}")
        assertEquals(loaded, h.fakeRenderer.state.currentUri, "the speaker keeps its track")
        assertTrue(bridge.speakers.value.isEmpty())
    }
    @Test
    @DisplayName("restore brings the speaker back without waiting for the periodic search")
    fun restoreBringsTheSpeakerBack() = h.run<Unit> {
        val hidden = HiddenSpeakers(h.dataDir).load()
        val bridge = h.newBridge(hidden = hidden)
        bridge.start()
        awaitSpeakerUp(bridge)
        assertTrue(bridge.hide(HARNESS_RENDERER_UDN))
        h.awaitUntil("the speaker gone") { bridge.speakers.value.isEmpty() }

        assertTrue(bridge.unhide(HARNESS_RENDERER_UDN))
        assertFalse(bridge.unhide(HARNESS_RENDERER_UDN), "a second restore finds nothing")

        assertFalse(hidden.contains(HARNESS_RENDERER_UDN))
        assertFalse(java.io.File(h.dataDir, "hidden.json").readText().contains(HARNESS_RENDERER_UDN))
        h.awaitUntil("the speaker back", 5_000) { bridge.speakers.value.size == 1 }
        h.awaitUntil("its DIAL target back", 5_000) { h.dialSearchAt(bridge, h.loopback) != null }
    }

    @Test
    @DisplayName("the hidden set survives a bridge restart")
    fun theHiddenSetSurvivesARestart() = h.run<Unit> {
        val a = h.newBridge(hidden = HiddenSpeakers(h.dataDir).load())
        a.start()
        awaitSpeakerUp(a)
        assertTrue(a.hide(HARNESS_RENDERER_UDN))
        a.stop()

        val reloaded = HiddenSpeakers(h.dataDir).load()
        assertTrue(reloaded.contains(HARNESS_RENDERER_UDN))
        val b = h.newBridge(h.testConfig(), hidden = reloaded)
        b.start()
        val searches = h.searchesSeen.get()
        h.awaitUntil("the speaker saw bridge B search") { h.searchesSeen.get() > searches }
        delay(800)
        assertTrue(b.speakers.value.isEmpty(), "bridge B never lists the hidden speaker: ${b.speakers.value}")
        assertEquals(0, b.castCount.value.total)
    }

    @Test
    @DisplayName("unhiding without a store, or a speaker that was never hidden, does nothing")
    fun unhideWithoutAStoreDoesNothing() = h.run<Unit> {
        val withoutStore = h.newBridge()
        withoutStore.start()
        assertFalse(withoutStore.unhide(HARNESS_RENDERER_UDN))
        assertFalse(withoutStore.hide(HARNESS_RENDERER_UDN))
    }
}
