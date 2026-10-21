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
//  * A restart-seek while paused loads without Play and Play is sent at resume (Node's #load always sends Play).
//  * A Pause waits, bounded, for a starting speaker to report PLAYING (Node sends it at once).

import dev.t2u.lounge.CastReceiver
import dev.t2u.lounge.PlayerStatus
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
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.FakeRenderer
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"
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
        locationOverride: String? = null,
        private val artist: String = "Band",
        private val album: String = "",
    ) : AutoCloseable {
        val dataDir: File = Files.createTempDirectory("renderer-").toFile().also { it.deleteOnExit() }
        val registered = CopyOnWriteArrayList<VideoEntry>()
        val resolves = CopyOnWriteArrayList<String>()
        val receiver = FakeReceiver()

        /** Every evidence event the renderer reported: kind and fields. */
        val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()

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
                    artist = artist,
                    album = album,
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
            location = locationOverride ?: fake.location,
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
                events = EventSink { kind, fields -> events.add(kind to fields) },
            ),
        )

        /** The renderer events whose message starts with [prefix]. */
        fun notes(prefix: String): List<Map<String, Any?>> =
            events.filter { it.first == "renderer" && (it.second["message"] as? String)?.startsWith(prefix) == true }.map { it.second }

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

    private suspend fun until(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) delay(20)
    }

    private fun FakeRenderer.count(action: String): Int = calls.count { it.action == action }

    @Test
    @DisplayName("renderer errors without UPnP fault body do not crash, and UTF-8 bodies have byte-accurate length")
    fun bareFaultAndByteAccurateLength() = withFake(FakeRenderer(bareFaultOn = setOf("Pause"))) { fake ->
        Harness(fake).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.doPlay(Video(DQW), 0.0))
            assertFalse(r.doPause()) // reported as failure, the process survives
            for (c in fake.calls) assertEquals(c.contentLength, c.bytes.toLong(), "${c.action} Content-Length")
            assertTrue(fake.calls.any { it.body.contains("ő") }, "the accented title went over the wire")
        }
    }

    @Test
    @DisplayName("a renderer removed while a track is loading stays stopped")
    fun removedWhileLoadingStaysStopped() = withFake { fake ->
        Harness(fake, resolveDelayMs = 200).use { h ->
            val r = h.renderer
            r.init()
            val play = h.scope.async { r.doPlay(Video(A), 0.0) }
            delay(50)
            r.destroy() // the device went away (byebye or stale) mid-load
            assertFalse(play.await())
            val calls = fake.calls.size
            delay(300)
            assertEquals(calls, fake.calls.size, "no SOAP calls (polls, Play) after destroy")
            assertEquals(0, fake.count("SetAVTransportURI"))
            assertEquals(0, fake.count("Play"))
        }
    }

    @Test
    @DisplayName("duration comes from the video, not a renderer still reporting the previous track")
    fun durationComesFromTheVideo() = withFake { fake ->
        Harness(fake).use { h ->
            val r = h.renderer
            r.init()
            r.doPlay(Video(A), 0.0)

            // The speaker briefly still reports the previous, longer track (4:58 at 2:40).
            fake.state.trackDuration = "0:04:58"
            fake.state.relTime = "0:02:40"
            fake.state.reportedUri = "http://192.168.0.85:8000/stream/" + "f".repeat(32) + ".aac"
            assertEquals(210.0, r.doGetDuration(), "video length, not the stale 4:58")
            assertEquals(0.0, r.doGetPosition(), "stale position ignored")

            // Once it reports our stream, its position is used again.
            fake.state.reportedUri = null
            fake.state.relTime = "0:00:07"
            assertEquals(7.0, r.doGetPosition())
        }
    }

    @Test
    @DisplayName("a description without AVTransport makes init return false")
    fun noAvTransportSkipsTheDevice() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/desc.xml") { ex ->
            val xml = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>""" +
                "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Lamp</friendlyName>" +
                "<UDN>uuid:lamp</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>" +
                "<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><controlURL>/ctl</controlURL>" +
                "<eventSubURL>/evt</eventSubURL><SCPDURL>/scpd</SCPDURL></service></serviceList></device></root>"
            val bytes = xml.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/xml")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            // Harness wants a FakeRenderer for its location; build the pieces by hand instead.
            val fake = FakeRenderer()
            Harness(fake, locationOverride = "http://127.0.0.1:${server.address.port}/desc.xml").use { h ->
                assertFalse(runBlocking { h.renderer.init() })
                assertEquals(0, h.receiverCreated)
                assertNull(h.renderer.name)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    @DisplayName("the default config keeps the coexistence suffix in the receiver name")
    fun defaultSuffixIsKept() = withFake { fake ->
        Harness(fake, config = { Config(dataDir = it, pollIntervalMs = 50, startTimeoutMs = 200) }).use { h ->
            h.renderer.init()
            assertEquals("🔊 Living Room Speaker (app)", h.receiverOptions?.name)
        }
    }

    @Test
    @DisplayName("resume, stop and volume from the sender become Play, Stop, SetVolume and SetMute")
    fun controlsReachTheSpeaker() = withFake { fake ->
        Harness(fake).use { h ->
            val r = h.renderer
            r.init()
            r.doPlay(Video(A), 0.0)

            val plays = fake.count("Play")
            assertTrue(r.doResume())
            assertEquals(plays + 1, fake.count("Play"))

            assertTrue(r.doPause())
            assertEquals("PAUSED_PLAYBACK", fake.state.transport)

            assertTrue(r.doSetVolume(Volume(40, true)))
            val setVolume = fake.calls.first { it.action == "SetVolume" }
            assertTrue(setVolume.body.contains("<DesiredVolume>40</DesiredVolume>"), setVolume.body)
            assertTrue(fake.calls.first { it.action == "SetMute" }.body.contains("<DesiredMute>1</DesiredMute>"))
            assertEquals(Volume(40, true), r.doGetVolume())

            assertNotNull(r.nowPlaying)
            assertTrue(r.doStop())
            assertEquals("STOPPED", fake.state.transport)
            assertNull(r.nowPlaying)
        }
    }

    @Test
    @DisplayName("speaker pause and play are mirrored to the sender by polling")
    fun pollingMirrorsPauseAndPlay() = withFake { fake ->
        Harness(fake).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.play(Video(A), 0.0))
            assertEquals(PlayerStatus.PLAYING, r.status)

            fake.state.transport = "PAUSED_PLAYBACK"
            until(500) { r.status == PlayerStatus.PAUSED }
            assertEquals(PlayerStatus.PAUSED, r.status)

            fake.state.transport = "PLAYING"
            until(500) { r.status == PlayerStatus.PLAYING }
            assertEquals(PlayerStatus.PLAYING, r.status)
        }
    }

    @Test
    @DisplayName("a track that ends on the speaker advances the queue and ends stopped")
    fun endOfTrackCallsNext() = withFake { fake ->
        Harness(fake).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.play(Video(A), 0.0))
            until { r.status == PlayerStatus.PLAYING && fake.count("GetPositionInfo") > 0 }
            fake.state.transport = "STOPPED"
            until { r.status == PlayerStatus.STOPPED }
            assertEquals(PlayerStatus.STOPPED, r.status)
        }
    }

    @Test
    @DisplayName("a speaker that never starts playing ends stopped with the reason")
    fun neverPlayingStops() = withFake(FakeRenderer(neverPlay = true)) { fake ->
        Harness(fake).use { h ->
            val r = h.renderer
            r.init()
            r.play(Video(A), 0.0)
            until { r.status == PlayerStatus.STOPPED }
            assertEquals(PlayerStatus.STOPPED, r.status)
            assertTrue(r.lastError?.contains("stream") == true, r.lastError)
        }
    }

    @Test
    @DisplayName("seeking an ADTS stream reloads it at the new offset")
    fun seekReloadsAtOffset() = withFake { fake ->
        Harness(fake).use { h ->
            val r = h.renderer
            r.init()
            r.doPlay(Video(A), 0.0)
            assertTrue(r.doSeek(50.0))
            assertEquals(listOf("adts" to 0, "adts" to 50), h.registered.map { it.format to it.startAt })
            assertEquals(2, fake.count("SetAVTransportURI"))
            assertEquals(0, fake.count("Seek"), "no AVTransport Seek on a non-seekable stream")
        }
    }

    @Test
    @DisplayName("casting mid-video starts the stream at that position")
    fun castingMidVideoStartsAtPosition() = withFake { fake ->
        Harness(fake).use { h ->
            val r = h.renderer
            r.init()
            r.doPlay(Video(A), 36.0)
            assertEquals(36, h.registered[0].startAt)
            assertTrue(fake.calls.first { it.action == "SetAVTransportURI" }.body.contains("duration=&quot;0:02:54.000&quot;"))
        }
    }

    @Test
    @DisplayName("the speaker volume is never changed on its own")
    fun volumeIsNeverTouchedUnasked() = withFake { fake ->
        Harness(fake).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.play(Video(A), 0.0))
            delay(300)
            assertTrue(r.doStop())
            assertEquals(0, fake.count("SetVolume"))
            assertEquals(0, fake.count("SetMute"))
        }
    }

    @Test
    @DisplayName("a seek while paused keeps the speaker paused at the new offset")
    fun seekWhilePausedKeepsTheSpeakerPausedAtTheNewOffset() = withFake { fake ->
        Harness(fake).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.play(Video(A), 0.0))
            assertTrue(r.pause())
            val seen = CopyOnWriteArrayList<Pair<PlayerStatus, Double>>()
            r.addStateListener { seen.add(it.current.status to it.current.position) }

            assertTrue(r.seek(50.0))

            assertEquals(2, fake.count("SetAVTransportURI"))
            assertEquals(1, fake.count("Play"), "no Play reached the speaker")
            assertEquals(listOf("adts" to 0, "adts" to 50), h.registered.map { it.format to it.startAt })
            assertTrue(fake.state.transport != "PLAYING", fake.state.transport)
            assertEquals(PlayerStatus.PAUSED, r.status)
            assertEquals(listOf(PlayerStatus.LOADING, PlayerStatus.PAUSED), seen.map { it.first })
            assertEquals(50.0, seen.last().second, "the sender sees the new position, not the speaker's 0:01:05")
            assertEquals(true, h.notes("Sent").last()["playDeferred"])
            delay(300)
            assertEquals(PlayerStatus.PAUSED, r.status)
            assertEquals(1, fake.count("Play"))
        }
    }

    @Test
    @DisplayName("Play after a paused seek starts the new stream once")
    fun playAfterAPausedSeekStartsTheNewStreamOnce() = withFake { fake ->
        Harness(fake).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.play(Video(A), 0.0))
            assertTrue(r.pause())
            assertTrue(r.seek(50.0))

            assertTrue(r.resume())

            assertEquals(2, fake.count("Play"))
            assertEquals(2, fake.count("SetAVTransportURI"))
            assertEquals(2, h.registered.size)
            assertEquals("PLAYING", fake.state.transport)
            assertEquals(PlayerStatus.PLAYING, r.status)
        }
    }

    @Test
    @DisplayName("the start clock restarts at Play after a deferred load")
    fun startClockRestartsAtPlayAfterADeferredLoad() = withFake(FakeRenderer(neverPlay = true)) { fake ->
        Harness(fake, config = { Config(dataDir = it, pollIntervalMs = 50, startTimeoutMs = 300, nameSuffix = "") }).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.doPlay(Video(A), 0.0))
            r.notifyExternalStateChange(PlayerStatus.PAUSED)
            assertTrue(r.seek(50.0))
            delay(700) // longer than the start timeout: the deferred load must not count as a failed start

            assertEquals(PlayerStatus.PAUSED, r.status)
            assertTrue(r.resume())
            delay(100)
            assertEquals(PlayerStatus.PLAYING, r.status)
            assertNull(r.lastError)

            delay(600)
            assertEquals(PlayerStatus.STOPPED, r.status)
            assertTrue(r.lastError?.contains("stream") == true, r.lastError)
        }
    }

    @Test
    @DisplayName("a seek while playing keeps playing")
    fun seekWhilePlayingKeepsPlaying() = withFake { fake ->
        Harness(fake).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.play(Video(A), 0.0))
            assertTrue(r.seek(50.0))
            assertEquals(2, fake.count("SetAVTransportURI"))
            assertEquals(2, fake.count("Play"))
            assertEquals(PlayerStatus.PLAYING, r.status)
            assertEquals(false, h.notes("Sent").last()["playDeferred"])
        }
    }

    @Test
    @DisplayName("a Pause during start-up waits for PLAYING before it is sent")
    fun aPauseDuringStartupWaitsForPlayingBeforeItIsSent() = withFake { fake ->
        Harness(fake, config = { Config(dataDir = it, pollIntervalMs = 50, startTimeoutMs = 2000, nameSuffix = "") }).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.doPlay(Video(A), 0.0))
            fake.state.transport = "TRANSITIONING"
            val pause = h.scope.async { r.doPause() }
            delay(300)
            assertEquals(0, fake.count("Pause"))
            assertTrue(pause.isActive, "the Pause is still waiting")
            fake.state.transport = "PLAYING"
            assertTrue(pause.await())
            assertEquals(1, fake.count("Pause"))
        }
    }

    @Test
    @DisplayName("a Pause is sent anyway when the speaker never starts")
    fun aPauseIsSentAnywayWhenTheSpeakerNeverStarts() = withFake(FakeRenderer(neverPlay = true)) { fake ->
        Harness(fake).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.doPlay(Video(A), 0.0))
            val t0 = System.currentTimeMillis()
            assertTrue(r.doPause())
            val took = System.currentTimeMillis() - t0
            assertTrue(took in 100..2000, "waited $took ms")
            assertEquals(1, fake.count("Pause"))
        }
    }
}
