package dev.t2u.renderer

// Port of the renderer.test.js format-chain titles plus the Kotlin additions of plan 03-06 (PLAY-04, DISC-04),
// driven against the upnp FakeRenderer. GPL-3.0-only.
//
// Adaptations from the Node tests (plan 03-12 copies these into PARITY.md "Adaptations"):
//  * Node's setup passes outputFormat "auto"; each test here sets it explicitly (the app default is auto too).
//  * The media server is a recording stub with a prepare hook (Node's fake server had no prepare step).

import dev.t2u.lounge.NoOpPlaylistRequestHandler
import dev.t2u.lounge.PlayerStateListener
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.PlaylistRequestHandler
import dev.t2u.lounge.PreviousNextVideos
import dev.t2u.lounge.Playlist
import dev.t2u.lounge.Video
import dev.t2u.upnp.FakeRenderer
import dev.t2u.upnp.parseSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val DQW = "dQw4w9WgXcQ"

class FormatChainTest {
    private fun <T> withFake(fake: FakeRenderer = FakeRenderer(), block: suspend (FakeRenderer) -> T): T {
        fake.start()
        try {
            return runBlocking { block(fake) }
        } finally {
            fake.close()
        }
    }

    private fun auto(dir: File) = Config(dataDir = dir, pollIntervalMs = 50, startTimeoutMs = 200, nameSuffix = "", outputFormat = "auto")

    @Test
    @DisplayName("picks the first format the renderer advertises (lossless ADTS before MP3)")
    fun picksTheFirstFormatTheRendererAdvertises() = withFake(FakeRenderer(sink = "http-get:*:audio/vnd.dlna.adts:*")) { fake ->
        RendererHarness(fake, config = ::auto).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            assertEquals("adts", h.registered.first().format)
        }
    }

    @Test
    @DisplayName("MP3 only when the renderer advertises nothing better")
    fun usesMp3OnlyWhenNothingBetter() = withFake(FakeRenderer(sink = "http-get:*:audio/mpeg:*")) { fake ->
        RendererHarness(fake, config = ::auto).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            assertEquals("mp3", h.registered.first().format)
            val set = fake.calls.first { it.action == "SetAVTransportURI" }
            assertTrue(set.body.contains("audio/mpeg"), set.body)
        }
    }

    @Test
    @DisplayName("direct comes first when the speaker takes the source container")
    fun directComesFirst() = withFake { fake ->
        RendererHarness(fake, config = ::auto).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            val entry = h.registered.first()
            assertEquals("direct", entry.format)
            assertEquals("m4a", entry.ext)
            assertEquals("audio/mp4", entry.sourceMime)
            val set = fake.calls.first { it.action == "SetAVTransportURI" }
            assertTrue(Regex("/stream/\\d+\\.m4a").containsMatchIn(set.body), set.body)
            assertTrue(set.body.contains("audio/mp4:DLNA.ORG_OP=01;DLNA.ORG_CI=0"), set.body)
        }
    }

    @Test
    @DisplayName("a speaker that lists no sink protocols is tried with direct first")
    fun emptySinkTriesDirectFirst() = withFake(FakeRenderer(sink = "")) { fake ->
        RendererHarness(fake, config = ::auto).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            assertEquals("direct", h.registered.first().format)
        }
    }

    @Test
    @DisplayName("media.prepare runs once per load, before registerVideo")
    fun prepareRunsBeforeRegister() = withFake { fake ->
        RendererHarness(fake, config = ::auto).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            assertEquals(1, h.prepared.size)
            assertEquals(listOf("prepare:direct", "register:direct"), h.order.toList())
        }
    }

    // ------------------------------------------------------------------------------ Task 2: walk and learn

    private fun storedFormat(dir: File): String? = runBlocking {
        val v = RendererDataStore(dir, "fake-renderer").get(LEARNED_FORMAT_KEY)
        (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }

    private fun seedFormat(dir: File, value: String) = runBlocking {
        RendererDataStore(dir, "fake-renderer").set(LEARNED_FORMAT_KEY, JsonPrimitive(value))
    }

    /** A speaker that starts only for MP3 (the direct format never starts). */
    private fun FakeRenderer.playsOnlyMp3(): FakeRenderer {
        onAction = { action ->
            if (action == "Play" && state.currentMetaData.contains("audio/mpeg:")) state.transport = "PLAYING"
        }
        return this
    }

    @Test
    @DisplayName("walks the format chain when the renderer never starts, and remembers the winner")
    fun walksTheChainAndRemembersTheWinner() = withFake(FakeRenderer(neverPlay = true).playsOnlyMp3()) { fake ->
        val dir: File
        RendererHarness(fake, config = ::auto).use { h ->
            dir = h.dataDir
            h.renderer.init()
            assertTrue(h.renderer.play(Video(DQW), 0.0))
            waitUntil { storedFormat(dir) == "mp3" }
            assertEquals(listOf("direct", "mp3"), h.formats())
            assertEquals("mp3", storedFormat(dir))
            assertEquals(PlayerStatus.PLAYING, h.renderer.status)
        }
        RendererHarness(fake, config = ::auto, dataDir = dir).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            assertEquals("mp3", h.formats().first())
        }
    }

    @Test
    @DisplayName("exclude filter skips renderer")
    fun excludeFilterSkipsRenderer() = withFake { fake ->
        RendererHarness(fake, config = { Config(dataDir = it, nameSuffix = "", nameFilter = NameFilter(exclude = listOf("living"))) }).use { h ->
            assertFalse(h.renderer.init())
            assertEquals(0, h.receiverCreated)
        }
        RendererHarness(fake, config = { Config(dataDir = it, nameSuffix = "", nameFilter = NameFilter(include = listOf("kitchen"))) }).use { h ->
            assertFalse(h.renderer.init())
            assertEquals(0, h.receiverCreated)
        }
        RendererHarness(fake, config = { Config(dataDir = it, nameSuffix = "", nameFilter = NameFilter(include = listOf("LIVING"))) }).use { h ->
            assertTrue(h.renderer.init())
            assertEquals(1, h.receiverCreated)
        }
        RendererHarness(fake, config = { Config(dataDir = it, nameSuffix = "") }).use { h ->
            assertTrue(h.renderer.init())
        }
    }

    @Test
    @DisplayName("a forced format never falls back: it stops with a reason and learns nothing")
    fun forcedFormatNeverFallsBack() = withFake(FakeRenderer(neverPlay = true)) { fake ->
        RendererHarness(fake, config = { Config(dataDir = it, pollIntervalMs = 50, startTimeoutMs = 200, nameSuffix = "", outputFormat = "mp3") }).use { h ->
            h.renderer.init()
            h.renderer.play(Video(DQW), 0.0)
            waitUntil { h.renderer.status == PlayerStatus.STOPPED }
            assertEquals(PlayerStatus.STOPPED, h.renderer.status)
            assertTrue(h.renderer.lastError != null)
            delay(500)
            assertEquals(listOf("mp3"), h.formats())
            assertNull(storedFormat(h.dataDir))
        }
    }

    @Test
    @DisplayName("a start failure of a remembered format clears the remembered value")
    fun startFailureClearsTheRememberedFormat() = withFake(FakeRenderer(neverPlay = true)) { fake ->
        val dir = java.nio.file.Files.createTempDirectory("renderer-").toFile().also { it.deleteOnExit() }
        seedFormat(dir, "direct")
        RendererHarness(fake, config = ::auto, dataDir = dir).use { h ->
            h.renderer.init()
            h.renderer.play(Video(DQW), 0.0)
            waitUntil { h.formats().size >= 2 && storedFormat(dir) == null }
            assertEquals(listOf("direct", "mp3"), h.formats())
            assertNull(storedFormat(dir))
        }
    }

    @Test
    @DisplayName("a previously learned mp4 is ignored in auto mode")
    fun learnedMp4IsIgnored() = withFake { fake ->
        val dir = java.nio.file.Files.createTempDirectory("renderer-").toFile().also { it.deleteOnExit() }
        seedFormat(dir, "mp4")
        RendererHarness(fake, config = ::auto, dataDir = dir).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            assertEquals("direct", h.registered.first().format)
        }
    }

    @Test
    @DisplayName("a learned FLAC format is ignored because it is no longer in the chain")
    fun learnedFlacIsIgnored() = withFake { fake ->
        val dir = java.nio.file.Files.createTempDirectory("renderer-").toFile().also { it.deleteOnExit() }
        seedFormat(dir, "flac")
        val chain = mutableListOf(chooseFormat("auto", "flac", null, "audio/mp4"))
        while (true) {
            val next = nextFormat(chain.last(), null, "audio/mp4") ?: break
            chain.add(next)
        }
        assertEquals(listOf("direct", "adts", "mp3"), chain)
        RendererHarness(fake, config = ::auto, dataDir = dir).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            assertEquals("direct", h.registered.first().format)
            assertFalse(h.formats().contains("flac"))
        }
    }

    @Test
    fun webmSourceSkipsAdtsDuringSelectionAndFallback() {
        val adtsOnly = parseSink("http-get:*:audio/vnd.dlna.adts:*")
        assertFalse(formatAccepted(null, "adts", "audio/webm"))
        assertTrue(formatAccepted(null, "adts", "audio/mp4"))
        listOf("audio/WEBM", "audio/opus", "audio/ogg", "audio/vorbis").forEach {
            assertFalse(formatAccepted(null, "adts", it), it)
        }
        assertEquals("direct", chooseFormat("auto", null, null, "audio/webm"))
        assertEquals("mp3", chooseFormat("auto", null, adtsOnly, "audio/webm"))
        assertEquals("mp3", nextFormat("direct", null, "audio/webm"))
        assertNull(nextFormat("mp3", null, "audio/webm"))
        assertEquals("direct", chooseFormat("auto", "mp4", null, "audio/webm"))
        assertEquals("mp4", chooseFormat("mp4", null, null, "audio/webm"))
    }

    @Test
    @DisplayName("a prepare failure in auto mode moves on to the next format within the same load")
    fun prepareFailureInAutoMovesOn() = withFake { fake ->
        RendererHarness(fake, config = ::auto, prepareFailure = { if (it.format == "direct") IllegalStateException("download failed") else null }).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.doPlay(Video(DQW), 0.0))
            assertEquals(listOf("mp3"), h.formats())
            assertEquals(listOf("direct", "mp3"), h.prepared.map { it.format })
            assertEquals(1, fake.calls.count { it.action == "SetAVTransportURI" })
        }
    }

    @Test
    @DisplayName("a prepare failure with a forced format fails the play")
    fun prepareFailureForcedFailsThePlay() = withFake { fake ->
        RendererHarness(
            fake,
            config = { Config(dataDir = it, pollIntervalMs = 50, startTimeoutMs = 200, nameSuffix = "", outputFormat = "direct") },
            prepareFailure = { IllegalStateException("download failed") },
        ).use { h ->
            h.renderer.init()
            assertFalse(h.renderer.doPlay(Video(DQW), 0.0))
            assertEquals(emptyList(), h.formats())
            assertTrue(h.renderer.lastError?.contains("download failed") == true, h.renderer.lastError)
        }
    }

    @Test
    @DisplayName("the start clock begins at Play, not at the start of a slow prepare")
    fun theClockStartsAtPlay() = withFake { fake ->
        RendererHarness(fake, config = ::auto, prepareDelayMs = 500).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.play(Video(DQW), 0.0))
            delay(600)
            assertEquals(listOf("direct"), h.formats())
            assertEquals(PlayerStatus.PLAYING, h.renderer.status)
        }
    }

    @Test
    @DisplayName("a failed reload in the last format reports STOPPED once, with one reload at a time")
    fun failedReloadReportsStoppedOnce() = withFake(FakeRenderer(neverPlay = true)) { fake ->
        RendererHarness(fake, config = ::auto, prepareFailure = { if (it.format == "direct") null else IllegalStateException("build failed") }).use { h ->
            val stopped = AtomicInteger()
            h.renderer.addStateListener(PlayerStateListener { if (it.current.status == PlayerStatus.STOPPED) stopped.incrementAndGet() })
            h.renderer.init()
            h.renderer.play(Video(DQW), 0.0)
            waitUntil { h.renderer.status == PlayerStatus.STOPPED }
            delay(600)
            assertEquals(1, stopped.get())
            assertEquals(listOf("direct"), h.formats())
            assertEquals(listOf("direct", "mp3"), h.prepared.map { it.format })
        }
    }

    @Test
    @DisplayName("a refused upstream host is an ordinary start failure that falls through to the next format")
    fun refusedUpstreamFallsThrough() = withFake(FakeRenderer(neverPlay = true)) { fake ->
        // The direct proxy refuses a URL its policy does not allow (here: any non-googlevideo host) with a 502.
        // The speaker then never reaches PLAYING, which the renderer cannot tell from any other failed start.
        val refusals = java.util.concurrent.atomic.AtomicInteger()
        val ms = dev.t2u.media.MediaServer(
            "127.0.0.1", 0, null, { }, null,
            object : dev.t2u.media.VideoResolver {
                override suspend fun resolve(videoId: String) = dev.t2u.media.ResolvedInfo(
                    "http://127.0.0.1:9/never?sig=SECRET", "m4a", "audio/mp4", emptyMap(), "t", "a", "", "", 60.0, 0, "140",
                )

                override fun invalidate(videoId: String) {}
            },
            dev.t2u.media.PipeRangeConfig(),
            OkHttpUpstreamFetcher(dev.t2u.upnp.lanHttpClient(), UpstreamPolicy.GOOGLEVIDEO),
        )
        ms.start()
        try {
            val port = ms.resolvedPort()
            // The speaker fetches the direct URL; the stub MP3 stream is simply accepted.
            fake.onAction = { action ->
                if (action == "Play" && fake.state.currentMetaData.contains("audio/mpeg:")) {
                    fake.state.transport = "PLAYING"
                } else if (action == "Play") {
                    val c = java.net.URI(fake.state.currentUri).toURL().openConnection() as java.net.HttpURLConnection
                    val status = c.responseCode
                    if (status == 200 || status == 206) fake.state.transport = "PLAYING" else refusals.incrementAndGet()
                    c.disconnect()
                }
            }
            RendererHarness(
                fake,
                config = { Config(dataDir = it, pollIntervalMs = 50, startTimeoutMs = 200, nameSuffix = "", outputFormat = "auto", mediaPort = port) },
                directDelegate = ms,
            ).use { h ->
                h.renderer.init()
                h.renderer.play(Video(DQW), 0.0)
                waitUntil { h.formats().size >= 2 }
                assertEquals("direct", h.formats().first())
                assertEquals("mp3", h.formats()[1], "the refused direct stream falls through to the next accepted format")
                assertTrue(refusals.get() >= 1, "the proxy refused the first stream")
                assertNull(storedFormat(h.dataDir), "a refused format is never learned")
            }
        } finally {
            ms.stop()
        }
    }

    @Test
    @DisplayName("the playlist request handler factory installs a handler on the queue")
    fun handlerFactoryInstallsAHandler() = withFake { fake ->
        val handler = object : PlaylistRequestHandler {
            override suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos? = null
        }
        RendererHarness(fake, playlistRequestHandlerFactory = { handler }).use { h ->
            assertSame(handler, h.renderer.queue.requestHandler)
        }
        RendererHarness(fake).use { h ->
            assertSame(NoOpPlaylistRequestHandler, h.renderer.queue.requestHandler)
        }
    }
}
