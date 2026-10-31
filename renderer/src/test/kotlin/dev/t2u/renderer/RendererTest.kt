package dev.t2u.renderer

// Port of the renderer.test.js titles that belong to the Phase 2 slice (init, doPlay, bare faults,
// removal during a load, duration from the video), driven against the upnp FakeRenderer. GPL-3.0-only.
//
// Adaptations from the Node tests (plan 02-14 copies these into PARITY.md "Adaptations"):
//  * The stream format is pinned: these tests set outputFormat "adts" (the app default is auto since plan 03-06), so the token URL ends in .aac and the
//    registered format is "adts" (Node's setup used outputFormat "auto", which resolved to "direct"/.m4a).
//  * The Node-titled init test uses nameSuffix "" to match the Node assertion; another test checks the
//    default D-04 suffix.
//  * Mute assertions rely on the FakeRenderer SetMute/GetMute extension (the Node fake has neither).
//  * Receivers, resolver and media registrar are stubs, as in Node (deps.createReceiver, resolver, mediaServer).
//  * A restart-seek while paused loads without Play and Play is sent at resume (Node's #load always sends Play).
//  * A Pause waits, bounded, for a starting speaker to report PLAYING (Node sends it at once).
//  * The start clock restarts at a Play that follows a Pause when the speaker was never seen PLAYING (Node
//    measures from the load only).

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
        // pinned to the pipe format these assertions were written for (plan 03-06)
        config: (File) -> Config = { Config(dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = 200, nameSuffix = "", clearOnCastEnd = true) },
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

        /** Runs inside every resolve; a test sets it to hold the resolver (plan 03-22). */
        @Volatile var resolveHold: (suspend () -> Unit)? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val resolver = object : VideoResolver {
            override suspend fun resolve(videoId: String): ResolvedInfo {
                resolves.add(videoId)
                if (resolveDelayMs > 0) delay(resolveDelayMs)
                resolveHold?.invoke()
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
        // pinned to the pipe format these assertions were written for (plan 03-06)
        Harness(fake, config = { Config(dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = 200, clearOnCastEnd = true) }).use { h ->
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
        // pinned to the pipe format these assertions were written for (plan 03-06)
        Harness(fake, config = { Config(dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = 300, nameSuffix = "", clearOnCastEnd = true) }).use { h ->
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
    @DisplayName("a Play after a Pause restarts the start clock when the speaker was never seen playing")
    fun aPlayAfterAPauseRestartsTheStartClockWhenTheSpeakerWasNeverSeenPlaying() = withFake(FakeRenderer(neverPlay = true)) { fake ->
        // pinned to the pipe format these assertions were written for (plan 03-06)
        Harness(fake, config = { Config(dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = 300, nameSuffix = "", clearOnCastEnd = true) }).use { h ->
            val r = h.renderer
            r.init()
            assertTrue(r.doPlay(Video(A), 0.0))
            r.notifyExternalStateChange(PlayerStatus.PAUSED)
            delay(700) // longer than the start timeout: a long Pause must not make the next Play look like a failed start

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
        // pinned to the pipe format these assertions were written for (plan 03-06)
        Harness(fake, config = { Config(dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = 2000, nameSuffix = "", clearOnCastEnd = true) }).use { h ->
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

    private val jett = "Joan Jett & the Blackhearts"
    private val alley = "Up Your Alley"

    @Test
    @DisplayName("the DIDL sent to the speaker carries title, artist twice, album, cover, class and duration in Node order")
    fun didlSentToTheSpeakerHasNodeOrder() = withFake { fake ->
        Harness(fake, artist = jett, album = alley).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.play(Video(A), 0.0))
            val didl = fake.state.currentMetaData
            assertEquals(2, Regex(Regex.escape("Joan Jett &amp; the Blackhearts")).findAll(didl).count(), didl)
            assertEquals(1, Regex(Regex.escape(alley)).findAll(didl).count(), didl)
            val order = listOf(
                "<dc:title>", "<dc:creator>", "<upnp:artist>", "<upnp:album>", "<upnp:albumArtURI>", "<upnp:class>", "<res ",
            ).map { didl.indexOf(it) }
            assertTrue(order.all { it >= 0 }, didl)
            assertEquals(order.sorted(), order, didl)
            assertTrue(didl.contains("object.item.audioItem.musicTrack"), didl)
            assertTrue(didl.contains("duration=\"0:03:30.000\""), didl)
            assertTrue(Regex("protocolInfo=\"http-get:\\*:audio/aac:").containsMatchIn(didl), didl)
        }
    }

    @Test
    @DisplayName("an empty artist sends no creator element and logs zero artist characters")
    fun emptyArtistSendsNoCreator() = withFake { fake ->
        Harness(fake, artist = "").use { h ->
            h.renderer.init()
            assertTrue(h.renderer.play(Video(A), 0.0))
            val didl = fake.state.currentMetaData
            assertFalse(didl.contains("<dc:creator>"), didl)
            assertFalse(didl.contains("<upnp:artist>"), didl)
            assertEquals(0, h.notes("Sent").last()["artistChars"])
        }
    }

    @Test
    @DisplayName("each cast logs what was sent as lengths only")
    fun castLogsLengthsOnly() = withFake { fake ->
        Harness(fake, artist = jett, album = alley).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.play(Video(A), 0.0))
            val sent = h.notes("Sent").last()
            assertEquals(27, sent["artistChars"])
            assertEquals(13, sent["albumChars"])
            assertEquals(true, sent["hasArt"])
            assertEquals(fake.state.currentMetaData.length, sent["didlChars"])
            assertEquals(false, sent["playDeferred"])
            delay(150)
            for ((kind, fields) in h.events) {
                for ((key, value) in fields) {
                    assertFalse(value.toString().contains("Joan Jett"), "$kind.$key carries the artist text")
                    assertFalse(value.toString().contains(alley), "$kind.$key carries the album text")
                }
            }
        }
    }

    @Test
    @DisplayName("the speaker's own track metadata is reported once per load")
    fun speakerMetadataReportedOncePerLoad() = withFake { fake ->
        Harness(fake, artist = jett, album = alley).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.play(Video(A), 0.0))
            until(2000) { h.notes("Speaker reports track metadata").isNotEmpty() }
            delay(300)
            val reports = h.notes("Speaker reports track metadata")
            assertEquals(1, reports.size)
            val r = reports.single()
            assertEquals(true, r["reported"])
            assertEquals(27, r["creatorChars"])
            assertEquals(27, r["artistChars"])
            assertEquals(13, r["albumChars"])
            assertEquals(true, r["hasArt"])
            assertEquals("object.item.audioItem.musicTrack", r["upnpClass"])
            assertTrue(h.renderer.seek(50.0))
            until(2000) { h.notes("Speaker reports track metadata").size == 2 }
            assertEquals(2, h.notes("Speaker reports track metadata").size, "one more report for the second load")
        }
    }

    @Test
    @DisplayName("a speaker that drops the artist is reported with zero creator characters")
    fun speakerThatDropsTheArtist() = withFake { fake ->
        fake.state.trackMetaData = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><item id=\"0\"><dc:title>T</dc:title></item></DIDL-Lite>"
        Harness(fake, artist = jett, album = alley).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.play(Video(A), 0.0))
            until(2000) { h.notes("Speaker reports track metadata").isNotEmpty() }
            val r = h.notes("Speaker reports track metadata").single()
            assertEquals(true, r["reported"])
            assertEquals(1, r["titleChars"])
            assertEquals(0, r["creatorChars"])
            assertEquals(0, r["artistChars"])
        }
    }

    @Test
    @DisplayName("a speaker without track metadata is reported as not reported")
    fun speakerWithoutTrackMetadata() = withFake { fake ->
        fake.state.trackMetaData = "NOT_IMPLEMENTED"
        Harness(fake, artist = jett, album = alley).use { h ->
            h.renderer.init()
            assertTrue(h.renderer.play(Video(A), 0.0))
            until(2000) { h.notes("Speaker reports track metadata").isNotEmpty() }
            val r = h.notes("Speaker reports track metadata").single()
            assertEquals(false, r["reported"])
            assertEquals(0, r["creatorChars"])
        }
    }

    // ---- plan 03-21: a clear that lands while a load is under way (CAST-06) ----

    /** Holds the first request for [action] at the fake (recorded, not yet applied or answered) until [release] is completed. */
    private class Hold(private val action: String) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private val used = AtomicBoolean(false)

        suspend fun onCall(called: String) {
            if (called == action && used.compareAndSet(false, true)) {
                entered.complete(Unit)
                release.await()
            }
        }
    }

    private fun emptyUriSentAt(fake: FakeRenderer): Int =
        fake.calls.indexOfLast { it.action == "SetAVTransportURI" && it.body.contains("<CurrentURI></CurrentURI>") }

    @Test
    @DisplayName("a clear after the URI was sent but before the load finished empties the speaker")
    fun aClearAfterTheUriWasSentButBeforeTheLoadFinishedEmptiesTheSpeaker() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                val hold = Hold("Play")
                fake.beforeAction = hold::onCall
                val load = h.scope.async { h.renderer.doPlay(Video(A), 0.0) }
                withTimeout(5000) { hold.entered.await() }
                assertTrue(fake.state.currentUri.contains("/stream/"), "the load is held at its Play: " + fake.state.currentUri)

                h.renderer.clearSpeaker()
                hold.release.complete(Unit)
                val loaded = load.await()

                assertEquals("", fake.state.currentUri, "the song stayed on the speaker")
                val emptiedAt = emptyUriSentAt(fake)
                assertTrue(emptiedAt >= 0, fake.calls.map { it.action }.toString())
                assertTrue(fake.calls.drop(emptiedAt + 1).none { it.action == "Play" }, "a Play followed the clear")
                assertFalse(loaded, "the load that was cleared reports failure")
                assertNull(h.renderer.nowPlaying)
            }
        }
    }

    @Test
    @DisplayName("a clear while the URI is being sent leaves the speaker empty")
    fun aClearWhileTheUriIsBeingSentLeavesTheSpeakerEmpty() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                val hold = Hold("SetAVTransportURI")
                fake.beforeAction = hold::onCall
                val load = h.scope.async { h.renderer.doPlay(Video(A), 0.0) }
                withTimeout(5000) { hold.entered.await() }
                assertEquals("", fake.state.currentUri, "the URI has not landed yet")

                val clear = h.scope.async { h.renderer.clearSpeaker() }
                delay(300)
                hold.release.complete(Unit)
                clear.await()
                val loaded = load.await()

                assertEquals("", fake.state.currentUri, "the URI landed after the clear and the song stayed on the speaker")
                val emptiedAt = emptyUriSentAt(fake)
                assertTrue(emptiedAt >= 0, fake.calls.map { it.action }.toString())
                assertTrue(fake.calls.drop(emptiedAt + 1).none { it.action == "Play" }, "a Play followed the clear")
                assertFalse(loaded, "the load that was cleared reports failure")
                assertNull(h.renderer.nowPlaying)
            }
        }
    }

    @Test
    @DisplayName("a clear does not wait for a load whose URI call never returns past the clear budget")
    fun aClearIsBoundedWhenTheUriCallNeverReturns() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                val hold = Hold("SetAVTransportURI")
                fake.beforeAction = hold::onCall
                val load = h.scope.async { h.renderer.doPlay(Video(A), 0.0) }
                try {
                    withTimeout(5000) { hold.entered.await() }

                    val t0 = System.nanoTime()
                    h.renderer.clearSpeaker()
                    val ms = (System.nanoTime() - t0) / 1_000_000

                    assertTrue(ms < CLEAR_BUDGET_MS + 1000, "the clear took $ms ms")
                    assertEquals(0, fake.count("Play"), "the cleared load must not reach Play")
                } finally {
                    hold.release.complete(Unit)
                    load.await()
                }
            }
        }
    }

    @Test
    @DisplayName("a clear before any load was started sends nothing")
    fun aClearBeforeAnyLoadDoesNothing() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                val before = fake.calls.size

                h.renderer.clearSpeaker()

                assertEquals(before, fake.calls.size, fake.calls.drop(before).map { it.action }.toString())
            }
        }
    }

    @Test
    @DisplayName("a clear while the speaker is on another source leaves it alone")
    fun aClearWhileTheSpeakerIsOnAnotherSourceLeavesItAlone() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                fake.state.currentUri = "http://192.168.1.50/other.mp3"
                val before = fake.calls.size

                h.renderer.clearSpeaker()

                val sent = fake.calls.drop(before).map { it.action }
                assertTrue(sent.none { it == "Stop" || it == "SetAVTransportURI" }, "sent $sent")
                assertEquals("http://192.168.1.50/other.mp3", fake.state.currentUri)
            }
        }
    }

    @Test
    @DisplayName("a clear after a finished load still empties the speaker")
    fun aClearAfterAFinishedLoadStillEmptiesTheSpeaker() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertTrue(fake.state.currentUri.contains("/stream/"), fake.state.currentUri)

                h.renderer.clearSpeaker()

                assertEquals("", fake.state.currentUri)
                assertTrue(emptyUriSentAt(fake) >= 0)
                assertNull(h.renderer.nowPlaying)
            }
        }
    }

    // ---- plan 03-22: a clear while a load is still resolving or preparing (CAST-06) ----

    /** Holds the resolver once [armed]: [entered] says a resolve is waiting, [release] lets it go on. */
    private class ResolveGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        @Volatile var armed = false

        suspend fun onResolve() {
            if (armed) {
                entered.complete(Unit)
                release.await()
            }
        }
    }

    private val streamActions = setOf("Stop", "SetAVTransportURI", "Play")

    @Test
    @DisplayName("a clear during the first track's resolve stops that load")
    fun aClearDuringTheFirstTracksResolveStopsThatLoad() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                val gate = ResolveGate().also { it.armed = true }
                h.resolveHold = gate::onResolve
                val before = fake.calls.size
                val load = h.scope.async { h.renderer.doPlay(Video(A), 0.0) }
                withTimeout(5000) { gate.entered.await() }

                h.renderer.clearSpeaker()
                gate.release.complete(Unit)
                val loaded = load.await()

                assertEquals("", fake.state.currentUri, "the song reached the speaker after the clear")
                val sent = fake.calls.drop(before).map { it.action }.filter { it in streamActions }
                assertTrue(sent.isEmpty(), "sent after the clear: $sent")
                assertFalse(loaded, "the load that was cleared reports failure")
                assertNull(h.renderer.nowPlaying)
            }
        }
    }

    @Test
    @DisplayName("a clear during a later track's resolve stops that load")
    fun aClearDuringALaterTracksResolveStopsThatLoad() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                val gate = ResolveGate()
                h.resolveHold = gate::onResolve
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertTrue(fake.state.currentUri.contains("/stream/"), fake.state.currentUri)
                gate.armed = true
                val load = h.scope.async { h.renderer.doPlay(Video(DQW), 0.0) }
                withTimeout(5000) { gate.entered.await() }
                val before = fake.calls.size

                h.renderer.clearSpeaker()
                gate.release.complete(Unit)
                val loaded = load.await()

                assertEquals("", fake.state.currentUri, "the speaker still holds a track of ours")
                val emptiedAt = emptyUriSentAt(fake)
                assertTrue(emptiedAt >= before, fake.calls.map { it.action }.toString())
                assertTrue(fake.calls.drop(emptiedAt + 1).none { it.action in streamActions }, "the cancelled load sent something after the clear")
                assertFalse(loaded, "the load that was cleared reports failure")
                assertNull(h.renderer.nowPlaying)
            }
        }
    }

    @Test
    @DisplayName("a clear while the resolver never returns stays inside the budget")
    fun aClearWhileTheResolverNeverReturnsStaysInsideTheBudget() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                val gate = ResolveGate().also { it.armed = true }
                h.resolveHold = gate::onResolve
                val before = fake.calls.size
                val load = h.scope.async { h.renderer.doPlay(Video(A), 0.0) }
                try {
                    withTimeout(5000) { gate.entered.await() }

                    val t0 = System.nanoTime()
                    h.renderer.clearSpeaker()
                    val ms = (System.nanoTime() - t0) / 1_000_000

                    assertTrue(ms < CLEAR_BUDGET_MS, "the clear took $ms ms")
                    assertEquals(emptyList(), fake.calls.drop(before).map { it.action }.filter { it in streamActions })
                } finally {
                    load.cancel()
                }
            }
        }
    }

    @Test
    @DisplayName("a clear during a later track's resolve leaves a speaker on another source alone")
    fun aClearDuringALoadLeavesAnotherSourceAlone() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                val gate = ResolveGate()
                h.resolveHold = gate::onResolve
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                fake.state.currentUri = "http://192.168.1.50/other.mp3"
                gate.armed = true
                val load = h.scope.async { h.renderer.doPlay(Video(DQW), 0.0) }
                withTimeout(5000) { gate.entered.await() }
                val before = fake.calls.size

                h.renderer.clearSpeaker()
                gate.release.complete(Unit)
                load.await()

                val sent = fake.calls.drop(before).map { it.action }
                assertTrue(sent.none { it == "Stop" || it == "SetAVTransportURI" }, "sent $sent")
                assertEquals("http://192.168.1.50/other.mp3", fake.state.currentUri)
            }
        }
    }

    // ---- plan 03-24: stop, pause, resume and a native seek never act on a speaker that holds another source (T-03-58, T-03-59)

    private val otherSource = "http://192.168.1.50/other.mp3"
    private val transportCommands = setOf("Stop", "Pause", "Play", "Seek", "SetAVTransportURI")

    private fun FakeRenderer.sentSince(mark: Int): List<String> = calls.drop(mark).map { it.action }.filter { it in transportCommands }

    private fun mp4Config(): (File) -> Config =
        { Config(dataDir = it, outputFormat = "mp4", pollIntervalMs = 50, startTimeoutMs = 2000, nameSuffix = "", clearOnCastEnd = true) }

    @Test
    @DisplayName("a stop while the speaker is on another source sends no Stop, forgets our track and stops polling")
    fun aStopWhileTheSpeakerIsOnAnotherSourceSendsNoStop() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                fake.state.currentUri = otherSource
                val mark = fake.calls.size

                // The service-stop path: the clear leaves the speaker alone, then the player stop runs.
                h.renderer.clearSpeaker()
                assertTrue(h.renderer.doStop())

                assertEquals(emptyList(), fake.sentSince(mark), "a command reached the other source")
                assertEquals(otherSource, fake.state.currentUri)
                assertNull(h.renderer.nowPlaying)
                assertEquals(1, h.notes("stop skipped").size)
                val polled = fake.calls.size
                delay(300)
                val after = fake.calls.drop(polled).filter { it.action == "GetTransportInfo" || it.action == "GetPositionInfo" }
                assertTrue(after.isEmpty(), "polling went on after the stop: ${after.map { it.action }}")
            }
        }
    }

    @Test
    @DisplayName("a pause while the speaker is on another source sends no Pause")
    fun aPauseWhileTheSpeakerIsOnAnotherSourceSendsNoPause() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                fake.state.currentUri = otherSource
                val mark = fake.calls.size

                assertFalse(h.renderer.doPause())

                assertEquals(emptyList(), fake.sentSince(mark))
                assertEquals(1, h.notes("pause skipped").size)
            }
        }
    }

    @Test
    @DisplayName("a resume while the speaker is on another source sends no Play")
    fun aResumeWhileTheSpeakerIsOnAnotherSourceSendsNoPlay() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertTrue(h.renderer.doPause())
                fake.state.currentUri = otherSource
                val mark = fake.calls.size

                assertFalse(h.renderer.doResume())

                assertEquals(emptyList(), fake.sentSince(mark))
                assertEquals(1, h.notes("resume skipped").size)
            }
        }
    }

    @Test
    @DisplayName("a native seek while the speaker is on another source sends no Seek")
    fun aNativeSeekWhileTheSpeakerIsOnAnotherSourceSendsNoSeek() {
        withFake { fake ->
            Harness(fake, config = mp4Config()).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                fake.state.currentUri = otherSource
                val mark = fake.calls.size

                assertFalse(h.renderer.doSeek(50.0))

                assertEquals(emptyList(), fake.sentSince(mark))
                assertEquals(1, h.notes("seek skipped").size)
            }
        }
    }

    @Test
    @DisplayName("a pause, resume or native seek on a speaker that holds nothing sends nothing")
    fun anEmptySpeakerCountsAsNotOursForPauseResumeAndSeek() {
        withFake { fake ->
            Harness(fake, config = mp4Config()).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                fake.state.currentUri = ""
                val mark = fake.calls.size

                assertFalse(h.renderer.doPause())
                assertFalse(h.renderer.doResume())
                assertFalse(h.renderer.doSeek(50.0))

                assertEquals(emptyList(), fake.sentSince(mark))
            }
        }
    }

    @Test
    @DisplayName("stop, pause, resume and native seek on a speaker that holds our stream send the same commands as before")
    fun commandsOnOurOwnStreamAreUnchanged() {
        withFake { fake ->
            Harness(fake, config = mp4Config()).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))

                var mark = fake.calls.size
                assertTrue(h.renderer.doPause())
                assertEquals(listOf("Pause"), fake.sentSince(mark))

                mark = fake.calls.size
                assertTrue(h.renderer.doResume())
                assertEquals(listOf("Play"), fake.sentSince(mark))

                mark = fake.calls.size
                assertTrue(h.renderer.doSeek(50.0))
                assertEquals(listOf("Seek"), fake.sentSince(mark))

                mark = fake.calls.size
                assertTrue(h.renderer.doStop())
                assertEquals(listOf("Stop"), fake.sentSince(mark))
                assertTrue(h.notes("stop skipped").isEmpty())
            }
        }
    }

    @Test
    @DisplayName("a stop on a speaker that holds nothing still sends Stop")
    fun aStopOnAnEmptySpeakerStillSendsStop() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                fake.state.currentUri = ""
                val mark = fake.calls.size

                assertTrue(h.renderer.doStop())

                assertEquals(listOf("Stop"), fake.sentSince(mark))
            }
        }
    }

    @Test
    @DisplayName("a speaker whose URI cannot be read still receives pause, resume, seek and stop")
    fun anUnreadableUriFailsOpen() {
        withFake { fake ->
            Harness(fake, config = mp4Config()).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                fake.state.currentUri = otherSource
                fake.bareFaultOn = setOf("GetMediaInfo")
                val mark = fake.calls.size

                assertTrue(h.renderer.doPause())
                assertTrue(h.renderer.doResume())
                assertTrue(h.renderer.doSeek(50.0))
                assertTrue(h.renderer.doStop())

                assertEquals(listOf("Pause", "Play", "Seek", "Stop"), fake.sentSince(mark))
                assertTrue(h.notes("pause skipped").isEmpty())
            }
        }
    }

    @Test
    @DisplayName("a new load still takes the speaker over from another source")
    fun aNewLoadStillTakesTheSpeakerOver() {
        withFake { fake ->
            Harness(fake).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                fake.state.currentUri = otherSource
                val mark = fake.calls.size

                assertTrue(h.renderer.doPlay(Video(DQW), 0.0))

                val sent = fake.sentSince(mark)
                assertTrue("SetAVTransportURI" in sent && "Play" in sent, "sent $sent")
                assertTrue(fake.state.currentUri.contains("/stream/"), fake.state.currentUri)
            }
        }
    }

    // ---- plan 03-25: a speaker that another source took over is released, quietly (T-03-60, T-03-61, T-03-62)

    private val radio = "x-sonosapi-stream:s12345?sid=254&flags=8224&sn=0"

    /** A renderer playing A through the sender path (status PLAYING); the speaker has been seen PLAYING. */
    private suspend fun playingThroughThePlayer(h: Harness): Renderer {
        h.renderer.init()
        assertTrue(h.renderer.play(Video(A), 0.0))
        until { h.renderer.status == PlayerStatus.PLAYING && h.fake.count("GetPositionInfo") > 0 }
        return h.renderer
    }

    @Test
    @DisplayName("a speaker taken over by another source is released after two polls: stopped, forgotten, one line, nothing sent")
    fun aSpeakerTakenOverByAnotherSourceIsReleasedAfterTwoPolls() {
        withFake { fake ->
            Harness(fake).use { h ->
                val r = playingThroughThePlayer(h)
                val told = CopyOnWriteArrayList<dev.t2u.lounge.PlayerStateEvent>()
                r.addStateListener { told.add(it) }
                val mark = fake.calls.size

                fake.state.currentUri = radio // PLAYING stays: the speaker plays the radio now
                until(3000) { r.status == PlayerStatus.STOPPED && told.any { it.current.status == PlayerStatus.STOPPED } }

                assertEquals(PlayerStatus.STOPPED, r.status, "the count source still counts the speaker")
                assertTrue(told.any { it.current.status == PlayerStatus.STOPPED }, "the sender was not told the player stopped")
                assertNull(r.nowPlaying, "our track is still remembered")
                val lines = h.notes("the speaker was taken over by another source - released")
                assertEquals(1, lines.size, "log lines: $lines")
                assertTrue(h.events.none { it.second.toString().contains("s12345") || it.second.toString().contains("sid=254") }, "the other source's URI was logged")
                assertEquals(emptyList(), fake.sentSince(mark), "a command reached the other source")
                val polled = fake.calls.size
                delay(300)
                val after = fake.calls.drop(polled).filter { it.action == "GetTransportInfo" || it.action == "GetPositionInfo" }
                assertTrue(after.isEmpty(), "polling went on after the release: ${after.map { it.action }}")
            }
        }
    }

    @Test
    @DisplayName("a taken-over speaker does not move the queue on")
    fun aTakenOverSpeakerDoesNotAdvanceTheQueue() {
        withFake { fake ->
            Harness(fake).use { h ->
                val r = h.renderer
                r.init()
                r.queue.updateByMessage("setPlaylist", queuePayload(), dev.t2u.lounge.Client.YT)
                assertTrue(r.play(r.queue.current!!, 0.0))
                until { r.status == PlayerStatus.PLAYING && fake.count("GetPositionInfo") > 0 }

                fake.state.currentUri = radio
                until(3000) { r.status == PlayerStatus.STOPPED }
                delay(300)

                assertEquals(PlayerStatus.STOPPED, r.status)
                assertEquals(1, fake.count("SetAVTransportURI"), "a second track was sent")
                assertEquals(A, r.queue.current?.id)
            }
        }
    }

    private fun queuePayload() = buildJsonObject {
        put("listId", "PL")
        put("videoIds", "$A,$DQW")
        put("videoId", A)
        put("currentIndex", "0")
        put("currentTime", "0")
    }

    @Test
    @DisplayName("after a takeover a clear and a stop send nothing to the speaker")
    fun afterATakeoverAClearAndAStopSendNothing() {
        withFake { fake ->
            Harness(fake).use { h ->
                val r = playingThroughThePlayer(h)
                fake.state.currentUri = radio
                until(3000) { r.status == PlayerStatus.STOPPED }
                assertEquals(PlayerStatus.STOPPED, r.status, "the speaker was not released")
                val mark = fake.calls.size

                r.clearSpeaker()
                r.doStop()

                assertEquals(emptyList(), fake.sentSince(mark))
                assertEquals(radio, fake.state.currentUri)
            }
        }
    }

    @Test
    @DisplayName("a new cast takes a speaker back after a takeover")
    fun aNewCastTakesASpeakerBackAfterATakeover() {
        withFake { fake ->
            Harness(fake).use { h ->
                val r = playingThroughThePlayer(h)
                fake.state.currentUri = radio
                until(3000) { r.status == PlayerStatus.STOPPED }
                assertEquals(PlayerStatus.STOPPED, r.status, "the speaker was not released")
                val mark = fake.calls.size

                assertTrue(r.play(Video(DQW), 0.0))
                delay(300)

                val sent = fake.sentSince(mark)
                assertTrue("SetAVTransportURI" in sent && "Play" in sent, "sent $sent")
                assertEquals(PlayerStatus.PLAYING, r.status)
                assertTrue(fake.state.currentUri.contains("/stream/"), fake.state.currentUri)
                assertEquals(1, h.notes("the speaker was taken over by another source - released").size)
            }
        }
    }

    @Test
    @DisplayName("one odd poll between two reads of our own stream is not a takeover")
    fun oneOddPollIsNotATakeover() {
        withFake { fake ->
            Harness(fake).use { h ->
                val r = playingThroughThePlayer(h)
                val reads = java.util.concurrent.atomic.AtomicInteger()
                // The next read after the first one reports the radio, every other read reports our own stream.
                fake.onAction = { action ->
                    if (action == "GetPositionInfo") fake.state.reportedUri = if (reads.incrementAndGet() == 1) radio else null
                }

                delay(500)

                assertTrue(reads.get() > 3, "reads ${reads.get()}")
                assertEquals(PlayerStatus.PLAYING, r.status)
                assertTrue(h.notes("the speaker was taken over").isEmpty())
            }
        }
    }

    @Test
    @DisplayName("an empty URI, an unreadable URI and a paused speaker on our stream are not a takeover")
    fun anEmptyOrUnreadableUriOrAPausedSpeakerIsNotATakeover() {
        withFake { fake ->
            Harness(fake).use { h ->
                val r = playingThroughThePlayer(h)
                val ours = fake.state.currentUri

                fake.state.currentUri = ""
                delay(400)
                assertEquals(PlayerStatus.PLAYING, r.status, "an empty URI released the speaker")

                fake.state.currentUri = radio
                fake.bareFaultOn = setOf("GetPositionInfo")
                delay(400)
                assertEquals(PlayerStatus.PLAYING, r.status, "an unreadable URI released the speaker")

                fake.bareFaultOn = emptySet()
                fake.state.currentUri = ours
                assertTrue(r.pause())
                delay(400)
                assertEquals(PlayerStatus.PAUSED, r.status, "a paused speaker on our stream released")
                assertTrue(h.notes("the speaker was taken over").isEmpty())
            }
        }
    }

    @Test
    @DisplayName("a speaker briefly reporting the previous track of ours is not a takeover")
    fun aTrackChangeShowingThePreviousTrackOfOursIsNotATakeover() {
        withFake { fake ->
            Harness(fake).use { h ->
                val r = playingThroughThePlayer(h)

                fake.state.reportedUri = "http://127.0.0.1:8000/stream/" + "f".repeat(32) + ".aac"
                delay(500)

                assertEquals(PlayerStatus.PLAYING, r.status)
                assertTrue(h.notes("the speaker was taken over").isEmpty())
            }
        }
    }

    @Test
    @DisplayName("a load in flight does not count a foreign URI, and the new track is not released")
    fun aLoadInFlightDoesNotCountTowardsATakeover() {
        withFake { fake ->
            Harness(fake).use { h ->
                val r = playingThroughThePlayer(h)
                fake.state.currentUri = radio
                h.resolveHold = { delay(400) }

                val play = h.scope.async { r.play(Video(DQW), 0.0) }
                assertTrue(play.await())
                delay(300)

                assertEquals(PlayerStatus.PLAYING, r.status)
                assertTrue(fake.state.currentUri.contains("/stream/"), fake.state.currentUri)
                assertTrue(h.notes("the speaker was taken over").isEmpty())
            }
        }
    }
}
