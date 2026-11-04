// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// The Speakers list's data path (plan 07-03, GUI-01): a speaker the bridge found and cast to reaches Bridge.speakers as an
// immutable SpeakerUi that follows the player. Whole bridge on loopback, as BridgePlaylistTest.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.PlayerStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"
private const val B = "BBBBBBBBBBB"

class SpeakerUiTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() {
        h.close()
    }

    @Test
    @DisplayName("a discovered speaker appears in bridge.speakers with its cast name and address")
    fun aDiscoveredSpeakerAppears() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.start()
        h.awaitUntil("one speaker in the list") { bridge.speakers.value.size == 1 }
        val speaker = bridge.speakers.value.single()
        assertEquals(HARNESS_RENDERER_UDN, speaker.udn)
        assertTrue(speaker.name.isNotEmpty(), "the name is the cast name")
        assertEquals(bridge.renderer(HARNESS_RENDERER_UDN)?.name, speaker.name)
        assertEquals("127.0.0.1", speaker.address)
        assertTrue(speaker.online)
        assertEquals(PlayerStatus.IDLE, speaker.status)
        assertNull(speaker.track)
    }

    @Test
    @DisplayName("a cast track shows in the speaker's snapshot and leaves it on stop")
    fun aCastTrackShowsAndLeavesOnStop() = h.run<Unit> {
        h.innerTube.scriptNext(A, previousId = null, nextId = B, index = 0)
        h.innerTube.scriptNext(B, previousId = A, nextId = null, index = 1)
        val bridge = h.newBridge()
        bridge.start()
        h.launch(bridge)
        h.connectSender()
        h.rpc("cl", "setPlaylist", h.playlist(A, videoIds = listOf(A, B), currentIndex = 0))

        h.awaitUntil("the entry plays the first track with a next one") {
            val s = bridge.speakers.value.singleOrNull()
            s != null && s.status == PlayerStatus.PLAYING && s.track?.videoId == A && s.hasNext
        }
        val playing = bridge.speakers.value.single()
        assertEquals("Stub song $A", playing.track?.title)
        assertEquals("Band", playing.track?.artist)
        assertTrue(playing.queueHasCurrent)
        h.awaitUntil("the position carries the resolved duration") { bridge.speakers.value.single().position?.durationSec == 210.0 }
        assertNotNull(bridge.renderer(HARNESS_RENDERER_UDN))

        h.rpc("cl", "stopVideo")
        h.awaitUntil("the entry left the track") {
            val s = bridge.speakers.value.single()
            (s.status == PlayerStatus.STOPPED || s.status == PlayerStatus.IDLE) && s.track == null
        }
    }

    @Test
    @DisplayName("stop empties the speakers flow")
    fun stopEmptiesTheFlow() = h.run<Unit> {
        val bridge = h.newBridge()
        bridge.start()
        h.awaitUntil("one speaker in the list") { bridge.speakers.value.size == 1 }
        bridge.stop()
        h.awaitUntil("the list is empty") { bridge.speakers.value.isEmpty() }
    }
}
