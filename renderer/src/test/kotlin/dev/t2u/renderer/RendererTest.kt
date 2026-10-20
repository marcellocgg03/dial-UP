package dev.t2u.renderer

// Port of the renderer.test.js titles that belong to the Phase 2 slice (init, doPlay, bare faults,
// removal during a load, duration from the video), driven against the upnp FakeRenderer. GPL-3.0-only.
//
// Adaptations from the Node tests (plan 02-14 copies these into PARITY.md "Adaptations"):
//  * The stream format is fixed: Config.outputFormat is "adts", so the token URL ends in .aac and the
//    registered format is "adts" (Node's setup used outputFormat "auto", which resolved to "direct"/.m4a).
//  * The Node-titled init test uses nameSuffix "" to match the Node assertion; another test checks the
//    default D-04 suffix.
//  * Mute assertions rely on the FakeRenderer SetMute/GetMute extension (the Node fake has neither).
//  * Receivers, resolver and media registrar are stubs, as in Node (deps.createReceiver, resolver, mediaServer).

import dev.t2u.lounge.CastReceiver
import dev.t2u.lounge.ReceiverFactory
import dev.t2u.lounge.ReceiverOptions
import dev.t2u.lounge.Sender
import dev.t2u.lounge.Video
import dev.t2u.lounge.Volume
import dev.t2u.media.MediaRegistrar
import dev.t2u.media.ResolvedInfo
import dev.t2u.media.VideoEntry
import dev.t2u.media.VideoResolver
import dev.t2u.media.extensionOf
import dev.t2u.upnp.FakeRenderer
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DQW = "dQw4w9WgXcQ"

/** PLAY-05, DISC-02, DISC-03, CAST-07: one speaker as one cast target. */
class RendererTest {
    private class FakeReceiver : CastReceiver {
        @Volatile override var status: String = "stopped"
        @Volatile var autoplayOnConnect: Boolean? = null

        override suspend fun start() {
            status = "running"
        }

        override suspend fun stop() {
            status = "stopped"
        }

        override fun connectedSenders(): List<Sender> = emptyList()

        override fun enableAutoplayOnConnect(value: Boolean) {
            autoplayOnConnect = value
        }
    }

    /** One renderer wired to stubs; [close] destroys it and the fake. */
    private class Harness(
        val fake: FakeRenderer,
        config: (File) -> Config = { Config(dataDir = it, pollIntervalMs = 50, startTimeoutMs = 200, nameSuffix = "") },
        resolveDelayMs: Long = 0,
    ) : AutoCloseable {
        val dataDir: File = Files.createTempDirectory("renderer-").toFile().also { it.deleteOnExit() }
        val registered = CopyOnWriteArrayList<VideoEntry>()
        val resolves = CopyOnWriteArrayList<String>()
        val receiver = FakeReceiver()

        @Volatile var receiverOptions: ReceiverOptions? = null

        @Volatile var receiverCreated = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val resolver = object : VideoResolver {
            override suspend fun resolve(videoId: String): ResolvedInfo {
                resolves.add(videoId)
                if (resolveDelayMs > 0) delay(resolveDelayMs)
                return ResolvedInfo(
                    url = "https://example/v",
                    ext = "m4a",
                    mime = "audio/mp4",
                    headers = emptyMap(),
                    title = "Song & Dance – Pitypang ő ű $videoId",
                    artist = "Band",
                    album = "",
                    thumbnail = "https://i/x.jpg",
                    duration = 210.0,
                    filesize = 0,
                    formatId = null,
                )
            }

            override fun invalidate(videoId: String) {}
        }

        private val media = object : MediaRegistrar {
            override fun registerVideo(entry: VideoEntry): String {
                entry.onRequest?.invoke()
                registered.add(entry)
                return "/stream/" + "0".repeat(31) + registered.size + "." + extensionOf(entry.format)
            }
        }

        val renderer = Renderer(
            udn = "fake-renderer",
            location = fake.location,
            address = "127.0.0.1",
            maxAge = 1800,
            port = 3000,
            deps = RendererDeps(
                config = config(dataDir),
                resolver = resolver,
                media = media,
                http = lanHttpClient(),
                createReceiver = ReceiverFactory { _, options ->
                    receiverOptions = options
                    receiverCreated++
                    receiver
                },
                scope = scope,
            ),
        )

        override fun close() {
            runBlocking { renderer.destroy() }
            scope.cancel()
        }
    }

    private fun <T> withFake(fake: FakeRenderer = FakeRenderer(), block: suspend (FakeRenderer) -> T): T {
        fake.start()
        try {
            return runBlocking { block(fake) }
        } finally {
            fake.close()
        }
    }

    @Test
    @DisplayName("init handles embedded renderer and names the receiver")
    fun initHandlesEmbeddedRenderer() = withFake { fake ->
        Harness(fake).use { h ->
            assertTrue(h.renderer.init())
            assertEquals("🔊 Living Room Speaker", h.receiverOptions?.name)
            assertEquals(3000, h.receiverOptions?.dialPort)
            assertEquals("running", h.receiver.status)
            assertEquals(true, h.receiver.autoplayOnConnect)
        }
    }

    @Test
    @DisplayName("doPlay loads short URL with DIDL metadata and plays")
    fun doPlayLoadsShortUrlWithDidlAndPlays() = withFake { fake ->
        Harness(fake).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.doPlay(Video(DQW), 0.0))
            val set = fake.calls.first { it.action == "SetAVTransportURI" }
            assertTrue(Regex("http://127\\.0\\.0\\.1:8000/stream/0{31}1\\.aac").containsMatchIn(set.body), set.body)
            assertTrue(set.body.contains("Song &amp;amp; Dance"), set.body)
            assertTrue(fake.calls.any { it.action == "Play" })
            assertEquals("adts", h.registered[0].format)
            assertEquals(65.0, r.doGetPosition())
            assertEquals(210.0, r.doGetDuration())
            assertEquals(Volume(30, false), r.doGetVolume())
        }
    }
}
