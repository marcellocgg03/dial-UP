package dev.t2u.renderer

// Whole-bridge gapless tests (plan 04-08; PLAY-08, PLAY-09, PLAY-10): SSDP, DIAL, the lounge, InnerTube, the renderer, the
// real media server and the fake speaker, all on loopback. The fake speaker's `advance()` stands in for "the speaker moved
// on into the queued track by itself". The Node project has no such end-to-end test, so every title here is Kotlin-only.
// GPL-3.0-only.
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.media.JvmPipeLauncher
import dev.t2u.media.SourceDownloader
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"
private const val B = "BBBBBBBBBBB"
private const val C = "CCCCCCCCCCC"

private val TOKEN_URL = Regex("http://127\\.0\\.0\\.1:\\d+/stream/[0-9a-f]{32}\\.aac")

/** PLAY-08, PLAY-09, PLAY-10: gapless changes, the no-gapless fallback and the bounded cache, through the whole bridge. */
class BridgeGaplessTest {
    private var h = BridgeHarness()
    private val tmp: File = Files.createTempDirectory("bridge-gapless-").toFile().also { it.deleteOnExit() }

    @AfterEach
    fun tearDown() {
        h.close()
        tmp.deleteRecursively()
    }

    private fun scriptAbc() {
        h.innerTube.scriptNext(A, previousId = null, nextId = B, index = 0)
        h.innerTube.scriptNext(B, previousId = A, nextId = C, index = 1)
        h.innerTube.scriptNext(C, previousId = B, nextId = null, index = 2)
    }

    private suspend fun castAbc(bridge: Bridge) {
        scriptAbc()
        bridge.start()
        h.launch(bridge)
        h.connectSender()
        h.rpc("cl", "setPlaylist", h.playlist(A, videoIds = listOf(A, B, C), currentIndex = 0))
    }

    private fun rendererState(bridge: Bridge) = bridge.state()["renderers"]!!.jsonArray.first().jsonObject

    private fun nextUris(): List<String> = h.fakeRenderer.calls.filter { it.action == "SetNextAVTransportURI" }.map {
        Regex("<NextURI>([^<]*)</NextURI>").find(it.body)?.groupValues?.get(1).orEmpty()
    }

    @Test
    @DisplayName("a playlist through the whole bridge changes tracks gaplessly")
    fun aPlaylistThroughTheWholeBridgeChangesTracksGaplessly() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig().copy(prefetch = true))
        castAbc(bridge)

        h.awaitUntil("SetAVTransportURI and Play for the first track") { h.setAvTransportUris().isNotEmpty() && h.speakerCount("Play") >= 1 }
        val first = h.setAvTransportUris().first()
        assertTrue(TOKEN_URL.matches(first), "CurrentURI was $first")
        h.awaitUntil("the second track queued on the speaker") { nextUris().isNotEmpty() }
        val queuedB = nextUris().first()
        assertTrue(TOKEN_URL.matches(queuedB), "NextURI was $queuedB")
        assertNotEquals(first, queuedB, "the queued track has its own token URL")
        h.awaitUntil("the speaker reported PLAYING") { h.messages().any { it.first == "onStateChange" && it.second["state"] == "1" } }
        delay(300) // the speaker has been polled as PLAYING
        assertEquals("true", rendererState(bridge)["gapless"]!!.jsonPrimitive.content)

        val stops = h.speakerCount("Stop")
        h.fakeRenderer.advance()
        h.awaitUntil("the sender is told the second track") { h.messages().any { it.first == "nowPlaying" && it.second["videoId"] == B } }
        assertEquals(1, h.setAvTransportUris().size, "the speaker moved on by itself: no second SetAVTransportURI")
        assertEquals(stops, h.speakerCount("Stop"), "and it was not stopped")
        h.awaitUntil("the third track queued") { nextUris().size >= 2 }
        assertNotEquals(queuedB, nextUris()[1], "the third track has its own token URL")
        assertEquals(1, h.setAvTransportUris().size)
        assertEquals(stops, h.speakerCount("Stop"))
    }

    @Test
    @DisplayName("a same-address network return keeps the current stream alive and gapless advances only when the speaker advances")
    fun sameAddressNetworkReturnKeepsTheCurrentStreamAlive() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig().copy(prefetch = true))
        castAbc(bridge)

        h.awaitUntil("the first track is loaded and the second is queued") {
            h.setAvTransportUris().isNotEmpty() && h.fakeRenderer.state.nextUri.isNotEmpty()
        }
        h.awaitUntil("the renderer is playing") { rendererState(bridge)["status"]?.jsonPrimitive?.content == "PLAYING" }
        val currentUri = h.fakeRenderer.state.currentUri
        val nextUri = h.fakeRenderer.state.nextUri
        val loads = h.setAvTransportUris().size
        val plays = h.speakerCount("Play")
        val stops = h.speakerCount("Stop")

        bridge.networkLost()
        bridge.rebindNetwork(h.network(h.loopback))

        assertEquals(currentUri, h.fakeRenderer.state.currentUri, "the current stream remains loaded")
        assertEquals(nextUri, h.fakeRenderer.state.nextUri, "the queued track remains queued")
        assertEquals(loads, h.setAvTransportUris().size, "the current stream was not reloaded")
        assertEquals(plays, h.speakerCount("Play"), "the current stream was not restarted")
        assertEquals(stops, h.speakerCount("Stop"), "the current stream was not stopped")
        assertTrue(h.events.none { it.first == "media-rebind" }, "same-address recovery leaves the media server running")
        assertTrue(h.events.none {
            it.first == "renderer" && it.second["message"] == "Renderer continued into the next track"
        }, "rebind alone does not advance the queue")

        h.rpc("cl", "loungeStatus", h.loungeStatus(emptyList(), "dev-1", ui = null))
        h.awaitUntil("the interrupted sender disconnects without resetting playback") {
            h.events.any { it.first == "lounge-app" && it.second["type"] == "noSendersLeft" && it.second["networkRecovery"] == true }
        }
        val nowPlayingReplies = h.count("nowPlaying")
        h.rpc("cl", "getNowPlaying")
        h.awaitUntil("the disconnect has been handled") { h.count("nowPlaying") > nowPlayingReplies }
        assertEquals(stops, h.speakerCount("Stop"), "an implicit network disconnect must not stop playback")
        assertEquals(plays, h.speakerCount("Play"), "it must not restart playback")
        assertEquals(currentUri, h.fakeRenderer.state.currentUri)
        assertEquals(nextUri, h.fakeRenderer.state.nextUri)
        assertEquals(1, bridge.visibleCastCount.value.casting)
        h.connectSender()

        h.fakeRenderer.advance()
        h.awaitUntil("normal gapless handoff advances to the second track") {
            h.messages().any { it.first == "nowPlaying" && it.second["videoId"] == B }
        }
        assertEquals(loads, h.setAvTransportUris().size, "normal gapless playback still avoids a reload")
    }

    @Test
    @DisplayName("a queued track that starts while Wi-Fi is down is adopted on rebind without reloading the previous track")
    fun aQueuedTrackThatStartsDuringNetworkLossIsAdoptedOnRebind() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig().copy(prefetch = true))
        castAbc(bridge)

        h.awaitUntil("the first track is loaded and the second is queued") {
            h.setAvTransportUris().isNotEmpty() && h.fakeRenderer.state.nextUri.isNotEmpty()
        }
        h.awaitUntil("the player is reported as casting") { bridge.visibleCastCount.value.casting == 1 }
        val loads = h.setAvTransportUris().size
        val stops = h.speakerCount("Stop")
        val plays = h.speakerCount("Play")

        bridge.networkLost()
        assertEquals(1, bridge.visibleCastCount.value.casting, "a short outage retains the cast state")
        h.fakeRenderer.advance()

        bridge.rebindNetwork(h.network(java.net.InetAddress.getByName("127.0.0.2")))
        h.awaitUntil("the sender follows the queued track") {
            h.messages().any { it.first == "nowPlaying" && it.second["videoId"] == B }
        }

        assertEquals(loads, h.setAvTransportUris().size, "the current track was not reloaded")
        assertEquals(stops, h.speakerCount("Stop"), "the hand-off did not stop the speaker")
        assertEquals(plays, h.speakerCount("Play"), "the already-playing queued track was not played again")
        assertTrue(h.events.any {
            it.first == "renderer" &&
                (it.second["message"] as? String) == "Renderer continued into the next track" &&
                it.second["via"] == "rebind"
        })
    }

    @Test
    @DisplayName("a speaker without SetNextAVTransportURI advances at the end of the track with the prepared track")
    fun aSpeakerWithoutSetNextAdvancesWithThePreparedTrack() {
        h.close()
        h = BridgeHarness(noGapless = true)
        h.run<Unit> {
            val bridge = h.newBridge(h.testConfig().copy(prefetch = true))
            castAbc(bridge)

            h.awaitUntil("the first track playing") { h.setAvTransportUris().isNotEmpty() && h.speakerCount("Play") >= 1 }
            h.awaitUntil("the speaker refused the queued track") { rendererState(bridge)["gapless"]!!.jsonPrimitive.content == "false" }
            h.awaitUntil("the second track prepared") { B in h.resolved }
            h.awaitUntil("the sender is told the speaker is playing") { h.messages().any { it.first == "onStateChange" && it.second["state"] == "1" } }
            delay(300)

            // the speaker finishes the first track: a position within 10 s of its 210 s end, then STOPPED
            h.fakeRenderer.state.relTime = "0:03:29"
            delay(300)
            h.fakeRenderer.state.transport = "STOPPED"
            h.awaitUntil("a second SetAVTransportURI") { h.setAvTransportUris().size >= 2 }
            assertTrue(TOKEN_URL.matches(h.setAvTransportUris()[1]), "CurrentURI was ${h.setAvTransportUris()[1]}")
            h.awaitUntil("the sender sees the second track") { h.messages().any { it.first == "nowPlaying" && it.second["videoId"] == B } }
            assertEquals(1, h.resolved.count { it == B }, "the prepared track was used: one resolve of the second video")
            assertEquals("false", rendererState(bridge)["gapless"]!!.jsonPrimitive.content)
        }
    }

    @Test
    @DisplayName("the file cache stays within cacheMb through a gapless playlist")
    fun theFileCacheStaysWithinCacheMbThroughAGaplessPlaylist() = h.run<Unit> {
        assumeTrue(h.findFfmpeg() != null, "ffmpeg is not installed")
        val source = File(tmp, "source/sine.m4a")
        assumeTrue(h.makeSineSource(source), "could not make the sine source with ffmpeg")
        val buffer = File(tmp, "buffer").also { it.mkdirs() }
        val downloader = SourceDownloader { _, _, target ->
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
        val bridge = h.newBridge(
            h.testConfig(outputFormat = "mp4").copy(prefetch = true, cacheMb = 0),
            launcher = JvmPipeLauncher(h.findFfmpeg()!!),
            bufferDir = buffer,
            sourceDownloader = downloader,
        )
        castAbc(bridge)

        fun built() = File(buffer, "files").listFiles().orEmpty().filter { it.name.endsWith(".m4a") }.map { it.name }.toSet()

        h.awaitUntil("the second track queued on the speaker") { nextUris().isNotEmpty() }
        h.awaitUntil("the speaker reported PLAYING") { h.messages().any { it.first == "onStateChange" && it.second["state"] == "1" } }
        delay(300)
        val whileFirstPlays = built()
        assertEquals(2, whileFirstPlays.size, "the first and the prepared second track are built: $whileFirstPlays")

        h.fakeRenderer.advance()
        h.awaitUntil("the third track queued after the advance") { nextUris().size >= 2 }
        h.awaitUntil("the first track's files are gone") { built().size == 2 && built().intersect(whileFirstPlays).size == 1 }
        val now = built()
        assertEquals(1, (now - whileFirstPlays).size, "the third track's built file is on disk: $now")
        assertEquals(0L, bridge.state()["cache"]!!.jsonObject["limitBytes"]!!.jsonPrimitive.long)
    }
}
