package dev.t2u.renderer

// GUI-08 (plan 07-07): the "clear the speaker when a cast ends" setting. Off (the default): the last sender leaving resets
// the player and stops polling but sends no empty transport URI, so the speaker keeps the track loaded. On: today's CAST-06
// clear. The setting is read through a supplier at clear time, so a flip applies to the running bridge (D-09). The stop
// paths (D-03): switching the bridge off stops the speaker, and empties it only with the setting on.
// GPL-3.0-only.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val VID = "AAAAAAAAAAA"

class ClearOnCastEndTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() = h.close()

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

    /** The sender leaves (an explicit disconnect), and the player resets: the speaker gets its Stop. */
    private suspend fun senderLeaves() {
        val stopsBefore = h.speakerCount("Stop")
        h.rpc("cl", "loungeStatus", h.loungeStatus(emptyList(), "dev-1", ui = "true"))
        h.awaitUntil("the speaker told to stop after the sender left") { h.speakerCount("Stop") > stopsBefore }
    }

    private fun emptiedUris() = h.setAvTransportUris().count { it.isEmpty() }

    @Test
    @DisplayName("with the setting off the last sender leaving leaves the track loaded")
    fun offLeavesTheTrackLoaded() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = false))
        castUntilLoaded(bridge)
        val loaded = h.fakeRenderer.state.currentUri

        senderLeaves()
        delay(600) // room for a clear that would follow the Stop

        assertEquals(0, emptiedUris(), "no empty SetAVTransportURI: ${h.setAvTransportUris()}")
        assertEquals(loaded, h.fakeRenderer.state.currentUri, "the speaker keeps the track")
    }

    @Test
    @DisplayName("with the setting on the last sender leaving clears the speaker")
    fun onClearsTheSpeaker() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = true))
        castUntilLoaded(bridge)

        senderLeaves()
        h.awaitUntil("the speaker emptied") { h.fakeRenderer.state.currentUri.isEmpty() }

        assertEquals(1, emptiedUris(), "exactly one empty SetAVTransportURI: ${h.setAvTransportUris()}")
        assertTrue(h.speakerCount("Stop") >= 1)
    }

    @Test
    @DisplayName("the setting applies to the running bridge without a restart")
    fun flipAppliesLive() = h.run<Unit> {
        val setting = AtomicBoolean(false)
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = false), clearOnCastEnd = { setting.get() })
        castUntilLoaded(bridge)

        setting.set(true) // flipped after the bridge started, before the disconnect
        senderLeaves()
        h.awaitUntil("the speaker emptied") { h.fakeRenderer.state.currentUri.isEmpty() }

        assertEquals(1, emptiedUris(), "exactly one empty SetAVTransportURI: ${h.setAvTransportUris()}")
    }

    // ------------------------------------------------------------------------------------------ bridge stop paths (D-03)

    @Test
    @DisplayName("Bridge.stop with stopSpeakers and no clear stops a playing speaker and leaves its URI")
    fun stopSpeakersWithoutClear() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = false))
        castUntilLoaded(bridge)
        val loaded = h.fakeRenderer.state.currentUri
        val stopsBefore = h.speakerCount("Stop")

        bridge.stop(clearSpeakers = false, stopSpeakers = true)

        assertTrue(h.speakerCount("Stop") > stopsBefore, "the speaker received a Stop: ${h.fakeRenderer.calls.map { it.action }}")
        assertEquals(0, emptiedUris(), "no empty SetAVTransportURI: ${h.setAvTransportUris()}")
        assertEquals(loaded, h.fakeRenderer.state.currentUri)
    }

    @Test
    @DisplayName("Bridge.stop with clearSpeakers empties the speaker as before")
    fun stopWithClearEmptiesTheSpeaker() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = true))
        castUntilLoaded(bridge)
        val stopsBefore = h.speakerCount("Stop")

        bridge.stop(clearSpeakers = true, stopSpeakers = true)

        assertTrue(h.speakerCount("Stop") > stopsBefore, "the speaker received a Stop")
        assertEquals(1, emptiedUris(), "exactly one empty SetAVTransportURI: ${h.setAvTransportUris()}")
        assertEquals("", h.fakeRenderer.state.currentUri)
    }

    @Test
    @DisplayName("Bridge.stop without either flag sends nothing to the speaker")
    fun stopWithoutFlagsSendsNothing() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = true))
        castUntilLoaded(bridge)
        val loaded = h.fakeRenderer.state.currentUri
        val stopsBefore = h.speakerCount("Stop")

        bridge.stop() // the reload-config restart path

        assertEquals(stopsBefore, h.speakerCount("Stop"), "no Stop: ${h.fakeRenderer.calls.map { it.action }}")
        assertEquals(0, emptiedUris())
        assertEquals(loaded, h.fakeRenderer.state.currentUri)
    }
}
