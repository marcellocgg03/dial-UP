package dev.t2u.renderer

// A cast whose speaker cannot play ends visibly (quick 261028-ago): the sender is told state 4 and then
// loungeScreenDisconnected, the player ends IDLE and the speaker is cleared. GPL-3.0-only.
//
// Every test is a block-bodied function or `= h.run<Unit> { }`: JUnit silently skips a test whose expression body returns a value.

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FAIL_VID = "AAAAAAAAAAA"

/** The whole bridge against a speaker that accepts the URL and never plays it, with the format forced so nothing is left to try. */
class PlaybackFailureBridgeTest {
    private val h = BridgeHarness(neverPlay = true)

    @AfterEach
    fun tearDown() {
        h.close()
    }

    private suspend fun castOne(bridge: Bridge) {
        h.innerTube.scriptNext(FAIL_VID, previousId = null, nextId = null)
        bridge.start()
        h.launch(bridge)
        h.connectSender()
        h.rpc("cl", "setPlaylist", h.playlist(FAIL_VID))
        h.awaitUntil("SetAVTransportURI on the speaker") { h.setAvTransportUris().isNotEmpty() }
    }

    private fun disconnects() = h.count("loungeScreenDisconnected")

    @Test
    @DisplayName("a forced format that never starts tells the sender state 4, then disconnects it, and clears the cast count")
    fun aForcedFormatThatNeverStartsDisconnectsTheSender() = h.run<Unit> {
        h.resolvedUrl = h.startUpstream(ByteArray(2000)) + "/audio.m4a"
        val bridge = h.newBridge(h.testConfig(outputFormat = "direct", startTimeoutMs = 800))
        castOne(bridge)

        h.awaitUntil("loungeScreenDisconnected", 15_000) { disconnects() >= 1 }
        val names = h.messages("cl")
        val four = names.indexOfLast { it.first == "onStateChange" && it.second["state"] == "4" }
        val gone = names.indexOfFirst { it.first == "loungeScreenDisconnected" }
        assertTrue(four in 0 until gone, "state 4 comes before the disconnect: ${names.map { it.first }}")
        h.awaitUntil("cast count 0") { bridge.castCount.value.casting == 0 }
        assertEquals(1, h.setAvTransportUris().count { it.isNotEmpty() }, "one non-empty SetAVTransportURI: ${h.setAvTransportUris()}")
    }

    @Test
    @DisplayName("the receiver stays usable: the same sender casts again and fails visibly again")
    fun theSameSenderCanCastAgainAndFailsVisiblyAgain() = h.run<Unit> {
        h.resolvedUrl = h.startUpstream(ByteArray(2000)) + "/audio.m4a"
        val bridge = h.newBridge(h.testConfig(outputFormat = "direct", startTimeoutMs = 800))
        castOne(bridge)
        h.awaitUntil("first loungeScreenDisconnected", 15_000) { disconnects() >= 1 }
        h.awaitUntil("first player reset") { bridge.castCount.value.casting == 0 }

        h.connectSender()
        h.rpc("cl", "setPlaylist", h.playlist(FAIL_VID))
        h.awaitUntil("a second non-empty SetAVTransportURI", 15_000) { h.setAvTransportUris().count { it.isNotEmpty() } >= 2 }
        h.awaitUntil("second loungeScreenDisconnected", 15_000) { disconnects() >= 2 }
    }
}
