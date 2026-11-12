package dev.t2u.renderer

// A cast whose speaker cannot play ends visibly (quick 261028-ago): the sender is told state 4 and then
// loungeScreenDisconnected, the player ends IDLE and the speaker is cleared. GPL-3.0-only.
//
// Every test is a block-bodied function or `= h.run<Unit> { }`: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.Client
import dev.t2u.lounge.Playlist
import dev.t2u.lounge.PlaylistRequestHandler
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.PreviousNextVideos
import dev.t2u.lounge.Video
import dev.t2u.lounge.VideoContext
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

private const val DQW = "dQw4w9WgXcQ"

/** The renderer on its own: every ending of the start verdict reports one failure, and nothing else does. */
class PlaybackFailureTest {
    private fun <T> withFake(fake: FakeRenderer, block: suspend (FakeRenderer) -> T): T {
        fake.start()
        try {
            return runBlocking { block(fake) }
        } finally {
            fake.close()
        }
    }

    private fun cfg(format: String = "auto"): (File) -> Config = {
        Config(dataDir = it, pollIntervalMs = 50, startTimeoutMs = 200, outputFormat = format, prefetch = false)
    }

    /** The state changes to STOPPED and the failure reports of [h]'s renderer, in the order they happened. */
    private class Log(h: RendererHarness) {
        val entries = CopyOnWriteArrayList<String>()

        init {
            h.renderer.addStateListener { if (it.current.status == PlayerStatus.STOPPED) entries += "state:STOPPED" }
            h.renderer.addFailureListener { entries += "failure:$it" }
        }

        val failures: List<String> get() = entries.filter { it.startsWith("failure:") }
    }

    /** A speaker that starts only for MP3 (the direct format never starts). */
    private fun FakeRenderer.playsOnlyMp3(): FakeRenderer {
        onAction = { action ->
            if (action == "Play" && state.currentMetaData.contains("audio/mpeg:")) state.transport = "PLAYING"
        }
        return this
    }

    @Test
    @DisplayName("a speaker that plays no format ends in one failure after the last format of the chain")
    fun aSpeakerThatPlaysNoFormatEndsInOneFailureAfterTheLastFormat() {
        withFake(FakeRenderer(playMimes = setOf("audio/wav"))) { fake ->
            RendererHarness(fake, config = cfg()).use { h ->
                val log = Log(h)
                h.renderer.init()
                h.renderer.play(Video(DQW), 0.0)
                waitUntil(10_000) { log.failures.isNotEmpty() }
                delay(600)
                assertEquals(listOf("direct", "mp3"), h.formats())
                assertEquals(listOf("failure:the speaker never started playing"), log.failures)
                assertTrue(log.entries.indexOf("state:STOPPED") < log.entries.indexOf("failure:the speaker never started playing"), "${log.entries}")
                assertEquals(PlayerStatus.STOPPED, h.renderer.status)
                assertNotNull(h.renderer.lastError)
            }
        }
    }

    @Test
    @DisplayName("a chain used up by a failed reload is reported once")
    fun anExhaustedChainAfterAFailedStartIsReportedOnce() {
        withFake(FakeRenderer(neverPlay = true)) { fake ->
            RendererHarness(
                fake,
                config = cfg(),
                prepareFailure = { if (it.format == "direct") null else IllegalStateException("build failed") },
            ).use { h ->
                val log = Log(h)
                h.renderer.init()
                h.renderer.play(Video(DQW), 0.0)
                waitUntil(10_000) { log.failures.isNotEmpty() }
                delay(600)
                assertEquals(listOf("direct", "mp3"), h.prepared.map { it.format })
                assertEquals(listOf("failure:no format could be loaded on the speaker"), log.failures)
                assertEquals("state:STOPPED", log.entries.first())
                assertEquals(1, log.entries.count { it == "state:STOPPED" })
            }
        }
    }

    @Test
    @DisplayName("a forced format that never starts is reported")
    fun aForcedFormatThatNeverStartsIsReported() {
        withFake(FakeRenderer(neverPlay = true)) { fake ->
            RendererHarness(fake, config = cfg("mp3")).use { h ->
                val log = Log(h)
                h.renderer.init()
                h.renderer.play(Video(DQW), 0.0)
                waitUntil(10_000) { log.failures.isNotEmpty() }
                delay(400)
                assertEquals(listOf("failure:the speaker never started playing"), log.failures)
                assertEquals(listOf("mp3"), h.formats())
            }
        }
    }

    @Test
    @DisplayName("a fallback that plays is not reported")
    fun aFallbackThatPlaysIsNotReported() {
        withFake(FakeRenderer(neverPlay = true).playsOnlyMp3()) { fake ->
            RendererHarness(fake, config = cfg()).use { h ->
                val log = Log(h)
                h.renderer.init()
                h.renderer.play(Video(DQW), 0.0)
                waitUntil { h.renderer.status == PlayerStatus.PLAYING && h.formats() == listOf("direct", "mp3") }
                delay(600)
                assertEquals(PlayerStatus.PLAYING, h.renderer.status)
                assertEquals(listOf("direct", "mp3"), h.formats())
                assertTrue(log.failures.isEmpty(), "${log.entries}")
            }
        }
    }

    @Test
    @DisplayName("a Stop while the fallback is loading is not reported")
    fun aStopWhileTheFallbackIsLoadingIsNotReported() {
        withFake(FakeRenderer(neverPlay = true)) { fake ->
            RendererHarness(fake, config = cfg(), prepareDelayFor = { if (it.format == "mp3") 1500L else 0L }).use { h ->
                val log = Log(h)
                h.renderer.init()
                h.renderer.play(Video(DQW), 0.0)
                waitUntil(10_000) { h.notes("Renderer did not start").isNotEmpty() }
                assertTrue(h.notes("Renderer did not start").isNotEmpty(), "the fallback began")
                h.renderer.stop()
                delay(2000)
                assertTrue(log.failures.isEmpty(), "${log.entries}")
                assertFalse(h.renderer.status == PlayerStatus.PLAYING)
            }
        }
    }

    /** Hands out C after B after A (the minimal queue of UnplayableTrackTest). */
    private class Chain : PlaylistRequestHandler {
        private val order = listOf("AAAAAAAAAAA", "BBBBBBBBBBB", "CCCCCCCCCCC")

        override suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos? {
            val i = order.indexOf(target.id)
            val next = order.getOrNull(i + 1)?.let { Video(it, Client.YT, VideoContext(playlistId = "PL", index = i + 1)) }
            return PreviousNextVideos(null, next)
        }
    }

    @Test
    @DisplayName("a track that cannot be prepared is still retried and skipped, not a disconnect")
    fun aTrackThatCannotBePreparedIsStillRetriedAndSkippedNotDisconnected() {
        withFake(FakeRenderer()) { fake ->
            val config: (File) -> Config = {
                Config(
                    dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = 5000,
                    queueSettleTimeoutMs = 5000, prefetch = false, playRetryDelaysMs = listOf(0L),
                )
            }
            RendererHarness(fake, config = config, playlistRequestHandlerFactory = { Chain() }).use { h ->
                h.resolveFailure = { id -> if (id == "BBBBBBBBBBB") IllegalStateException("HTTP Error 403: Forbidden") else null }
                val log = Log(h)
                val r = h.renderer
                r.init()
                r.queue.updateByMessage(
                    "setPlaylist",
                    buildJsonObject {
                        put("listId", "PL")
                        put("videoIds", "AAAAAAAAAAA,BBBBBBBBBBB,CCCCCCCCCCC")
                        put("videoId", "AAAAAAAAAAA")
                        put("currentIndex", "0")
                        put("currentTime", "0")
                    },
                    Client.YT,
                )
                assertTrue(r.play(r.queue.current!!, 0.0))
                delay(200)
                fake.state.relTime = "0:03:28"
                delay(200)
                fake.state.transport = "STOPPED"
                waitUntil { r.queue.current?.id == "CCCCCCCCCCC" && r.status == PlayerStatus.PLAYING }
                assertEquals("CCCCCCCCCCC", r.queue.current?.id)
                assertTrue(log.failures.isEmpty(), "${log.entries}")
            }
        }
    }
}
