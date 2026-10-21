package dev.t2u.renderer

// Whole-bridge playlist tests (plan 03-10; CAST-04, CAST-05): SSDP, DIAL, the lounge, InnerTube, the renderer and the
// fake speaker, all on loopback. The fake InnerTube server stands in for YouTube; the fake speaker's position and
// transport state stand in for "the track finished". GPL-3.0-only.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.InnerTubeConstants
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"
private const val B = "BBBBBBBBBBB"
private const val C = "CCCCCCCCCCC"
private const val D = "DDDDDDDDDDD"
private const val E = "EEEEEEEEEEE"

/** CAST-04, CAST-05: a playlist plays through the whole bridge, and a failing InnerTube degrades to one playing track. */
class BridgePlaylistTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() {
        h.close()
    }

    /** The speaker reports the track as finished: a position within 10 s of its 210 s end, then STOPPED. */
    private suspend fun trackFinishes() {
        h.fakeRenderer.state.relTime = "0:03:29"
        delay(300) // a few polls, so the poll that reads the position has run before the stop
        h.fakeRenderer.state.transport = "STOPPED"
    }

    /** The next track starts from the beginning. */
    private fun nextTrackStartsAtZero() {
        h.fakeRenderer.state.relTime = "0:00:05"
    }

    private suspend fun waitForPlayingReported(playing: Int = 1) {
        h.awaitUntil("the sender is told the speaker is playing") { h.messages().count { it.first == "onStateChange" && it.second["state"] == "1" } >= playing }
        delay(300) // the speaker has been polled as PLAYING
    }

    private fun mediaPortOf(uri: String) = URI(uri).port

    @Test
    @DisplayName("a playlist cast through the whole bridge asks InnerTube about each track and advances when the speaker finishes one")
    fun tracerAPlaylistAdvancesToTheSecondTrack() = h.run<Unit> {
        h.innerTube.scriptNext(A, previousId = null, nextId = B, index = 0)
        h.innerTube.scriptNext(B, previousId = A, nextId = C, index = 1)
        h.innerTube.scriptNext(C, previousId = B, nextId = null, autoplayId = D, index = 2)
        val bridge = h.newBridge()
        bridge.start()
        h.launch(bridge)
        h.connectSender()
        h.rpc("cl", "setPlaylist", h.playlist(A, videoIds = listOf(A, B, C), currentIndex = 0))

        h.awaitUntil("SetAVTransportURI and Play for the first track") { h.setAvTransportUris().isNotEmpty() && h.speakerCount("Play") >= 1 }
        val first = h.setAvTransportUris().first()
        assertTrue(Regex("http://127\\.0\\.0\\.1:\\d+/stream/[0-9a-f]{32}\\.aac").matches(first), "CurrentURI was $first")

        // InnerTube was asked about A as the TVHTML5 client, and the sender is told a next track exists
        h.awaitUntil("a /next request for A") { A in h.askedAbout() }
        val request = h.innerTube.requests.first { (it.body["videoId"] as? JsonPrimitive)?.content == A }
        assertEquals("POST", request.method)
        assertEquals("/youtubei/v1/next", request.path)
        val client = ((request.body["context"] as JsonObject)["client"] as JsonObject)["clientName"] as JsonPrimitive
        assertEquals(InnerTubeConstants.CLIENT_NAME, client.content)
        h.awaitUntil("the sender is told next is available") {
            h.messages().any { it.first == "onHasPreviousNextChanged" && it.second["hasNext"] == "true" }
        }

        // the speaker finishes the first track: the second one is loaded and the sender is told
        waitForPlayingReported()
        val stateChanges = h.count("onStateChange")
        trackFinishes()
        h.awaitUntil("a second SetAVTransportURI") { h.setAvTransportUris().size >= 2 }
        val second = h.setAvTransportUris()[1]
        assertNotEquals(first, second, "the second track has its own token URL")
        assertTrue(Regex("http://127\\.0\\.0\\.1:\\d+/stream/[0-9a-f]{32}\\.aac").matches(second), "CurrentURI was $second")
        h.awaitUntil("the sender sees the new track") {
            h.messages().any { it.first == "nowPlaying" && it.second["videoId"] == B } && h.count("onStateChange") > stateChanges
        }
        h.awaitUntil("a /next request for B") { B in h.askedAbout() }
        assertEquals(listOf(A, B), h.resolved.distinct().take(2))
    }

    @Test
    @DisplayName("Stop after a playlist cast still removes everything and InnerTube is not asked again")
    fun stopAfterAPlaylistRemovesEverything() = h.run<Unit> {
        h.innerTube.scriptNext(A, previousId = null, nextId = B, index = 0)
        h.innerTube.scriptNext(B, previousId = A, nextId = C, index = 1)
        val bridge = h.newBridge()
        bridge.start()
        val receiver = h.launch(bridge)
        h.connectSender()
        h.rpc("cl", "setPlaylist", h.playlist(A, videoIds = listOf(A, B, C), currentIndex = 0))
        h.awaitUntil("the first track playing") { h.setAvTransportUris().isNotEmpty() && h.speakerCount("Play") >= 1 }
        h.awaitUntil("a /next request for A") { A in h.askedAbout() }
        val mediaPort = mediaPortOf(h.setAvTransportUris().first())

        bridge.stop()
        assertEquals(BridgeCount(0, 0), bridge.castCount.value)
        assertEquals("false", bridge.state()["running"].toString())
        for (port in listOf(receiver.port, mediaPort)) {
            assertFailsWith<ConnectException> { Socket().use { it.connect(InetSocketAddress(h.loopback, port), 1000) } }
        }
        val seen = h.innerTube.requests.size
        delay(500)
        assertEquals(seen, h.innerTube.requests.size, "nothing asks InnerTube after Stop")
    }

    @Test
    @DisplayName("a cast that starts at the last item plays C, then the autoplay video D, then D's next E: the mix keeps going")
    fun theAutoplayMixKeepsGoing() = h.run<Unit> {
        h.innerTube.scriptNext(C, previousId = B, nextId = null, autoplayId = D, index = 2)
        h.innerTube.scriptNext(D, previousId = null, nextId = E, index = 0) // a mix: no previous
        h.innerTube.scriptNext(E, previousId = null, nextId = null)
        val bridge = h.newBridge()
        bridge.start()
        h.launch(bridge)
        h.connectSender()
        h.rpc("cl", "setPlaylist", h.playlist(C, videoIds = listOf(A, B, C), currentIndex = 2))

        h.awaitUntil("C loaded on the speaker") { h.setAvTransportUris().isNotEmpty() && h.speakerCount("Play") >= 1 }
        h.awaitUntil("a /next request for C") { C in h.askedAbout() }
        h.awaitUntil("the sender is told a next track exists (the autoplay video)") {
            h.messages().any { it.first == "onHasPreviousNextChanged" && it.second["hasNext"] == "true" }
        }

        // C finishes: the autoplay video D plays, InnerTube is asked about D, the sender sees previous and next after the hop
        waitForPlayingReported()
        trackFinishes()
        h.awaitUntil("D loaded on the speaker") { h.setAvTransportUris().size >= 2 }
        h.awaitUntil("the sender sees D") { h.messages().any { it.first == "nowPlaying" && it.second["videoId"] == D } }
        h.awaitUntil("a /next request for D") { D in h.askedAbout() }
        h.awaitUntil("hasNext true after the hop") { h.messages().last { it.first == "onHasPreviousNextChanged" }.second["hasNext"] == "true" }

        // D finishes: E (D's next in the mix) plays
        nextTrackStartsAtZero()
        delay(400)
        trackFinishes()
        h.awaitUntil("E loaded on the speaker") { h.setAvTransportUris().size >= 3 }
        h.awaitUntil("the sender sees E") { h.messages().any { it.first == "nowPlaying" && it.second["videoId"] == E } }
        h.awaitUntil("a /next request for E") { E in h.askedAbout() }
        assertEquals(3, h.setAvTransportUris().distinct().size, "three different token URLs")
        assertEquals(listOf(C, D, E), h.resolved.distinct())
    }

    @Test
    @DisplayName("an InnerTube that answers 500 still lets the cast track play: no next, a track end stops, and the log holds no request or token text")
    fun aFailingInnerTubeStillPlaysTheCastTrack() = h.run<Unit> {
        h.innerTube.status = 500
        val bridge = h.newBridge()
        bridge.start()
        h.launch(bridge)
        h.connectSender()
        h.rpc("cl", "setPlaylist", h.playlist(A, videoIds = listOf(A, B, C), currentIndex = 0))

        h.awaitUntil("A loaded and playing") { h.setAvTransportUris().isNotEmpty() && h.speakerCount("Play") >= 1 }
        waitForPlayingReported()
        h.awaitUntil("InnerTube was asked") { A in h.askedAbout() }
        assertTrue(h.messages().none { it.first == "onHasPreviousNextChanged" && it.second["hasNext"] == "true" }, "no next track was announced")

        trackFinishes()
        h.awaitUntil("the renderer is stopped") { bridge.state()["renderers"].toString().contains("\"status\":\"STOPPED\"") }
        delay(300)
        assertEquals(1, h.setAvTransportUris().size, "no second track was loaded")
        assertEquals("true", bridge.state()["running"].toString())

        val notes = h.events.filter { it.first == "lounge" && (it.second["type"] as? String)?.startsWith("innertube") == true }
        assertTrue(notes.any { it.second["type"] == "innertubeHttpError" && it.second["status"] == 500 }, "the failure is noted: $notes")
        val text = h.events.joinToString("\n") { "${it.first} ${it.second}" }
        for (forbidden in listOf("ctt", "visitorData", "enableMdxAutoplay", "clientVersion", "SERVERVISITOR1")) {
            assertTrue(!text.contains(forbidden), "the event log must not hold $forbidden")
        }
    }

    // The only slow test of this file: it waits for the handler's real 10 s call timeout (about 11 s).
    @Test
    @DisplayName("an InnerTube slower than the call timeout does not hold the first play for more than the timeout plus one second")
    fun aSlowInnerTubeDoesNotHoldTheFirstPlay() = h.run<Unit> {
        h.innerTube.delayMs = InnerTubeConstants.CALL_TIMEOUT_MS + 5000
        val bridge = h.newBridge()
        bridge.start()
        h.launch(bridge)
        h.connectSender()

        val t0 = System.nanoTime()
        h.rpc("cl", "setPlaylist", h.playlist(A, videoIds = listOf(A, B, C), currentIndex = 0))
        h.awaitUntil("the first SetAVTransportURI", timeoutMs = InnerTubeConstants.CALL_TIMEOUT_MS + 5000) { h.setAvTransportUris().isNotEmpty() }
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(waitedMs <= InnerTubeConstants.CALL_TIMEOUT_MS + 1000, "the first play waited $waitedMs ms")
        assertTrue(A in h.askedAbout(), "InnerTube was asked (and timed out)")
    }
}
