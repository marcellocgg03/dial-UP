// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// GUI-02 (plan 07-08): the buttons on a speaker's card. Each card action goes through Renderer.perform, which calls the same
// Player methods the phone's commands use, so it reaches the fake speaker over SOAP and shows in Renderer.ui. Whole bridge on
// loopback, as SpeakerUiTest.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.PlayerStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"
private const val B = "BBBBBBBBBBB"

class SpeakerControlTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() {
        h.close()
    }

    /** Starts [bridge], connects the sender and casts until the speaker plays [first] of [ids]. */
    private suspend fun castUntilPlaying(bridge: Bridge, first: String, ids: List<String> = listOf(first)): Renderer {
        bridge.start()
        h.launch(bridge)
        if (ids.size == 2) {
            h.innerTube.scriptNext(ids[0], previousId = null, nextId = ids[1], index = 0)
            h.innerTube.scriptNext(ids[1], previousId = ids[0], nextId = null, index = 1)
        } else {
            h.innerTube.scriptNext(first, previousId = null, nextId = null)
        }
        h.connectSender()
        h.rpc("cl", "setPlaylist", h.playlist(first, videoIds = ids, currentIndex = ids.indexOf(first)))
        h.awaitUntil("the speaker playing $first") {
            val s = bridge.speakers.value.singleOrNull()
            s != null && s.status == PlayerStatus.PLAYING && s.track?.videoId == first
        }
        return assertNotNull(bridge.renderer(HARNESS_RENDERER_UDN))
    }

    @Test
    @DisplayName("pause from the card pauses the speaker")
    fun pauseFromTheCard() = h.run<Unit> {
        val bridge = h.newBridge()
        val renderer = castUntilPlaying(bridge, A)
        val pausesBefore = h.speakerCount("Pause")

        assertTrue(renderer.perform(SpeakerAction.PAUSE))

        assertTrue(h.speakerCount("Pause") > pausesBefore, "the speaker received a Pause: ${h.fakeRenderer.calls.map { it.action }}")
        h.awaitUntil("the card shows Paused") { renderer.ui.value.status == PlayerStatus.PAUSED }
    }

    @Test
    @DisplayName("play from the card resumes a paused speaker")
    fun playFromTheCard() = h.run<Unit> {
        val bridge = h.newBridge()
        val renderer = castUntilPlaying(bridge, A)
        assertTrue(renderer.perform(SpeakerAction.PAUSE))
        h.awaitUntil("the card shows Paused") { renderer.ui.value.status == PlayerStatus.PAUSED }
        val playsBefore = h.speakerCount("Play")

        assertTrue(renderer.perform(SpeakerAction.PLAY))

        assertTrue(h.speakerCount("Play") > playsBefore, "the speaker received a Play: ${h.fakeRenderer.calls.map { it.action }}")
        h.awaitUntil("the card shows Playing") { renderer.ui.value.status == PlayerStatus.PLAYING }
    }

    @Test
    @DisplayName("next and previous from the card move through the queue")
    fun nextAndPreviousFromTheCard() = h.run<Unit> {
        val bridge = h.newBridge()
        val renderer = castUntilPlaying(bridge, A, listOf(A, B))
        h.awaitUntil("the card knows there is a next track") { renderer.ui.value.hasNext }
        val urisBefore = h.setAvTransportUris().size

        assertTrue(renderer.perform(SpeakerAction.NEXT))

        h.awaitUntil("the speaker gets the second video's URI") { h.setAvTransportUris().size > urisBefore }
        h.awaitUntil("the card shows B") { renderer.ui.value.track?.videoId == B }
        h.awaitUntil("the sender is told about B") { h.messages().any { it.first == "nowPlaying" && it.second["videoId"] == B } }
        h.awaitUntil("the card knows there is a previous track") { renderer.ui.value.hasPrevious }
        val urisAfterNext = h.setAvTransportUris().size

        assertTrue(renderer.perform(SpeakerAction.PREVIOUS))

        h.awaitUntil("the speaker gets another URI") { h.setAvTransportUris().size > urisAfterNext }
        h.awaitUntil("the card is back on A") { renderer.ui.value.track?.videoId == A }
    }

    @Test
    @DisplayName("stop from the card stops the speaker")
    fun stopFromTheCard() = h.run<Unit> {
        val bridge = h.newBridge()
        val renderer = castUntilPlaying(bridge, A)
        val stopsBefore = h.speakerCount("Stop")

        assertTrue(renderer.perform(SpeakerAction.STOP))

        assertTrue(h.speakerCount("Stop") > stopsBefore, "the speaker received a Stop: ${h.fakeRenderer.calls.map { it.action }}")
        h.awaitUntil("the card shows Stopped") { renderer.ui.value.status == PlayerStatus.STOPPED }
    }

    @Test
    @DisplayName("clear from the card empties the speaker even with clear-when-cast-ends off")
    fun clearFromTheCardIgnoresTheSetting() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig(clearOnCastEnd = false))
        val renderer = castUntilPlaying(bridge, A)
        val stopsBefore = h.speakerCount("Stop")

        assertTrue(renderer.perform(SpeakerAction.CLEAR))

        assertTrue(h.speakerCount("Stop") > stopsBefore, "the speaker received a Stop")
        assertEquals(1, h.setAvTransportUris().count { it.isEmpty() }, "an empty SetAVTransportURI: ${h.setAvTransportUris()}")
        assertEquals("", h.fakeRenderer.state.currentUri)
    }
}
