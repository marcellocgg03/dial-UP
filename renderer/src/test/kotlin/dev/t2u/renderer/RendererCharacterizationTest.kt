package dev.t2u.renderer

// Characterization tests (plan 09-15, D-05) for the Renderer.kt paths no earlier test reached, found with a JaCoCo run
// on the code before the Renderer split. Each test pins what the code does today; none encodes a fix. GPL-3.0-only.
//
// Every test is a block-bodied function: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.Client
import dev.t2u.lounge.CastReceiver
import dev.t2u.lounge.Playlist
import dev.t2u.lounge.PlaylistRequestHandler
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.PreviousNextVideos
import dev.t2u.lounge.ReceiverFactory
import dev.t2u.lounge.ReceiverOptions
import dev.t2u.lounge.Sender
import dev.t2u.lounge.Video
import dev.t2u.lounge.Volume
import dev.t2u.lounge.VideoContext
import dev.t2u.media.MediaRegistrar
import dev.t2u.media.ResolvedInfo
import dev.t2u.media.VideoEntry
import dev.t2u.media.VideoResolver
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.FakeRenderer
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"
private const val B = "BBBBBBBBBBB"

internal class RendererCharacterizationTest {
    private fun <T> withFake(fake: FakeRenderer = FakeRenderer(), block: suspend CoroutineScope.(FakeRenderer) -> T): T {
        fake.start()
        try {
            return runBlocking { block(fake) }
        } finally {
            fake.close()
        }
    }

    private fun pinned(format: String = "adts"): (File) -> Config =
        { Config(dataDir = it, outputFormat = format, pollIntervalMs = 50, startTimeoutMs = 2000, prefetch = false) }

    private fun FakeRenderer.count(action: String): Int = calls.count { it.action == action }

    // ---------------------------------------------------------------------------------------------------- init

    /** A receiver whose start() destroys the renderer being initialised; [onStop] says what each stop() call does. */
    private class DestroyingReceiver(
        private val owner: () -> Renderer,
        private val onStop: suspend (call: Int) -> Unit,
    ) : CastReceiver {
        @Volatile override var status: String = "stopped"
        val stopCalls = java.util.concurrent.atomic.AtomicInteger()

        override suspend fun start() {
            status = "running"
            owner().destroy()
        }

        override suspend fun stop() {
            onStop(stopCalls.incrementAndGet())
        }

        override fun connectedSenders(): List<Sender> = emptyList()

        override fun enableAutoplayOnConnect(value: Boolean) {}
    }

    private fun bareDeps(config: Config, scope: CoroutineScope, receiver: ReceiverFactory, events: EventSink): RendererDeps {
        val resolver = object : VideoResolver {
            override suspend fun resolve(videoId: String): ResolvedInfo = error("not used")

            override fun invalidate(videoId: String) {}
        }
        val media = object : MediaRegistrar {
            override suspend fun prepare(entry: VideoEntry) {}

            override fun registerVideo(entry: VideoEntry): String = "/stream/x"
        }
        return RendererDeps(config, resolver, media, lanHttpClient(), receiver, scope, events)
    }

    /** Runs [body] with a renderer whose receiver destroys it during start(); the receiver is passed on. */
    private fun withDestroyingReceiver(onStop: suspend (Int) -> Unit, body: suspend CoroutineScope.(Renderer, DestroyingReceiver) -> Unit) {
        withFake { fake ->
            val dir = Files.createTempDirectory("renderer-").toFile().also { it.deleteOnExit() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            lateinit var renderer: Renderer
            val receiver = DestroyingReceiver({ renderer }, onStop)
            val deps = bareDeps(
                Config(dataDir = dir, pollIntervalMs = 50, prefetch = false),
                scope,
                ReceiverFactory { _, _: ReceiverOptions -> receiver },
                EventSink { _, _ -> },
            )
            renderer = Renderer("fake-renderer", fake.location, "127.0.0.1", 1800, 3000, deps)
            try {
                body(renderer, receiver)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    @DisplayName("an init that finds the renderer destroyed while its receiver starts stops that receiver and reports false")
    fun initDestroyedWhileTheReceiverStarts() {
        withDestroyingReceiver({ delay(20) }) { renderer, receiver ->
            assertFalse(renderer.init())
            // destroy() stopped it once, init() asked a second time.
            assertEquals(2, receiver.stopCalls.get())
        }
    }

    @Test
    @DisplayName("an init that finds the renderer destroyed ignores a receiver that fails to stop")
    fun initDestroyedWithAReceiverThatCannotStop() {
        withDestroyingReceiver({ error("the receiver cannot stop") }) { renderer, receiver ->
            assertFalse(renderer.init())
            assertEquals(2, receiver.stopCalls.get())
        }
    }

    @Test
    @DisplayName("an init cancelled while it stops a receiver after a destroy rethrows the cancellation")
    fun initCancelledWhileStoppingTheReceiver() {
        withDestroyingReceiver({ call -> if (call > 1) awaitCancellation() }) { renderer, receiver ->
            val job = launch { renderer.init() }
            waitUntil { receiver.stopCalls.get() >= 2 }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
    }

    @Test
    @DisplayName("an init whose GetProtocolInfo fails still succeeds: the sink list is optional")
    fun initSurvivesAFailingProtocolInfo() {
        withFake(FakeRenderer(bareFaultOn = setOf("GetProtocolInfo"))) { fake ->
            RendererHarness(fake, config = pinned()).use { h ->
                assertTrue(h.renderer.init())
                assertEquals(1, fake.count("GetProtocolInfo"))
                assertEquals(1, h.notes("GetProtocolInfo failed (ignored)").size)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------- SOAP helpers

    /** A UPnP description with an AVTransport service only, served by the JDK's own HTTP server. */
    private fun avTransportOnlyDescription(): com.sun.net.httpserver.HttpServer {
        val xml = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>""" +
            "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Bare Speaker</friendlyName>" +
            "<manufacturer>Acme</manufacturer><modelName>Bare</modelName><UDN>uuid:bare</UDN><serviceList>" +
            "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>" +
            "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/scpd/AVTransport.xml</SCPDURL>" +
            "<controlURL>/ctl/AVTransport</controlURL><eventSubURL>/evt/AVTransport</eventSubURL></service>" +
            "</serviceList></device></root>"
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/desc.xml") { ex ->
            val body = xml.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/xml")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        return server
    }

    @Test
    @DisplayName("a command to a speaker whose description lacks the service answers false and warns, it does not throw")
    fun commandWithoutTheServiceFails() {
        val server = avTransportOnlyDescription()
        val dir = Files.createTempDirectory("renderer-").toFile().also { it.deleteOnExit() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        try {
            val deps = bareDeps(
                Config(dataDir = dir, pollIntervalMs = 50, prefetch = false),
                scope,
                ReceiverFactory { _, _: ReceiverOptions -> NoopReceiver() },
                EventSink { kind, fields -> if (kind == "renderer") events.add(fields) },
            )
            val renderer = Renderer("bare", "http://127.0.0.1:${server.address.port}/desc.xml", "127.0.0.1", 1800, 3000, deps)
            runBlocking {
                assertTrue(renderer.init(), "the missing ConnectionManager only skips the sink list")
                assertFalse(renderer.doSetVolume(Volume(40, false)))
                renderer.destroy()
            }
            val messages = events.mapNotNull { it["message"] as? String }
            assertTrue(messages.any { it.startsWith("SetVolume failed: RenderingControl service not provided") }, messages.toString())
            assertTrue(messages.any { it.startsWith("GetProtocolInfo failed (ignored): ConnectionManager service not provided") }, messages.toString())
        } finally {
            scope.cancel()
            server.stop(0)
        }
    }

    private class NoopReceiver : CastReceiver {
        @Volatile override var status: String = "stopped"

        override suspend fun start() {
            status = "running"
        }

        override suspend fun stop() {
            status = "stopped"
        }

        override fun connectedSenders(): List<Sender> = emptyList()

        override fun enableAutoplayOnConnect(value: Boolean) {}
    }

    @Test
    @DisplayName("a Pause cancelled while its SOAP call is in flight rethrows the cancellation and does not warn about a failure")
    fun cancelledCommandIsNotAFailure() {
        withFake { fake ->
            RendererHarness(fake, config = pinned()).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                fake.beforeAction = { action -> if (action == "Pause") delay(10_000) }
                val job = launch { h.renderer.doPause() }
                waitUntil { fake.count("Pause") > 0 }
                job.cancelAndJoin()

                assertTrue(job.isCancelled)
                assertTrue(h.notes("Pause failed").isEmpty())
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------- polling

    @Test
    @DisplayName("a poll that throws is noted at debug level and polling goes on")
    fun aFailingPollIsNotedAndPollingContinues() {
        withFake { fake ->
            val throwing = AtomicBoolean(false)
            RendererHarness(fake, config = pinned(), clock = { if (throwing.get()) error("clock broke") else System.currentTimeMillis() }).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                waitUntil { fake.count("GetPositionInfo") > 0 }

                throwing.set(true)
                waitUntil { h.notes("Poll failed").isNotEmpty() }
                throwing.set(false)
                assertTrue(h.notes("Poll failed").isNotEmpty())

                val before = fake.count("GetTransportInfo")
                waitUntil { fake.count("GetTransportInfo") > before + 2 }
                assertTrue(fake.count("GetTransportInfo") > before + 2, "the poll loop survived")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------- network return

    @Test
    @DisplayName("a load still under way 20 s after the address came back is warned about and the track is not re-set")
    fun resyncGivesUpOnALoadStillInFlight() {
        withFake { fake ->
            val clockMs = AtomicLong(System.currentTimeMillis())
            val stuck = AtomicBoolean(false)
            val gate = CompletableDeferred<Unit>()
            RendererHarness(fake, config = pinned(), clock = { clockMs.get() }).use { h ->
                val r = h.renderer
                r.init()
                assertTrue(r.play(Video(A), 0.0))
                fake.beforeAction = { action -> if (action == "SetAVTransportURI" && stuck.get()) gate.await() }
                stuck.set(true)
                val load = launch { r.doPlay(Video(B), 0.0) }
                waitUntil { fake.count("SetAVTransportURI") == 2 }

                r.onNetworkChanged("127.0.0.1")
                delay(300) // the re-sync has taken its deadline from the clock
                clockMs.addAndGet(NETWORK_RESET_WAIT_MS + 5_000)
                waitUntil { h.notes("A load was still under way").isNotEmpty() }
                assertEquals(1, h.notes("A load was still under way").size)
                assertEquals(2, fake.count("SetAVTransportURI"), "the track was not re-set")

                gate.complete(Unit)
                load.join()
            }
        }
    }

    @Test
    @DisplayName("a speaker that cannot be read after the network returned is read three times, then the track is re-set at the last polled position")
    fun resyncRetriesAnUnreadableSpeakerThenResets() {
        withFake { fake ->
            RendererHarness(fake, config = pinned()).use { h ->
                val r = h.renderer
                r.init()
                assertTrue(r.play(Video(A), 0.0))
                waitUntil { fake.count("GetPositionInfo") > 0 }
                r.onNetworkLost()
                val readsBefore = fake.count("GetTransportInfo")
                fake.bareFaultOn = setOf("GetTransportInfo")

                r.onNetworkChanged("127.0.0.1")
                waitUntil { fake.count("SetAVTransportURI") == 2 }

                assertEquals(2, fake.count("SetAVTransportURI"), "re-set after the third unreadable attempt")
                assertEquals(RESYNC_READ_ATTEMPTS, fake.count("GetTransportInfo") - readsBefore)
                fake.bareFaultOn = emptySet()
            }
        }
    }

    @Test
    @DisplayName("a speaker kept after the network returned that then stops mid-track is re-set once at the last polled position")
    fun keptSpeakerThatStopsMidTrackIsReset() {
        withFake { fake ->
            RendererHarness(fake, config = pinned()).use { h ->
                val r = h.renderer
                r.init()
                assertTrue(r.play(Video(A), 0.0))
                waitUntil { r.status == PlayerStatus.PLAYING && fake.state.transport == "PLAYING" }
                delay(200) // the poll has seen PLAYING
                assertEquals(1, fake.count("SetAVTransportURI"))

                r.onNetworkChanged("127.0.0.1") // same address: the speaker still plays our stream, so it is kept
                waitUntil { h.notes("Kept").isNotEmpty() }
                assertEquals(1, h.notes("Kept").size)
                assertEquals(1, fake.count("SetAVTransportURI"), "a kept speaker is not re-set")

                fake.state.transport = "STOPPED" // its buffer ran dry: 1:05 of 3:30 is mid-track
                waitUntil { fake.count("SetAVTransportURI") == 2 }

                assertEquals(1, h.notes("The speaker stopped after the network returned").size)
                assertEquals(2, fake.count("SetAVTransportURI"))
                assertTrue(h.notes("Track stopped on the speaker").isEmpty(), "the stop was not mirrored to the phone")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------- prefetch

    private class NextHandler : PlaylistRequestHandler {
        override suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos? =
            PreviousNextVideos(null, if (target.id == A) Video(B, Client.YT, VideoContext(playlistId = "PL", index = 1)) else null)
    }

    private fun setPayload(): JsonObject = buildJsonObject {
        put("listId", "PL")
        put("videoIds", "$A,$B")
        put("videoId", A)
        put("currentIndex", "0")
        put("currentTime", "0")
    }

    @Test
    @DisplayName("a next track prepared while a newer load began is released and never handed to the speaker")
    fun prefetchSupersededWhilePreparingIsReleased() {
        withFake { fake ->
            RendererHarness(
                fake,
                config = { Config(dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = 5000, prefetch = true, gapless = true) },
                playlistRequestHandlerFactory = { NextHandler() },
                prepareDelayFor = { entry -> if (entry.videoId == B) 700L else 0L },
            ).use { h ->
                val r = h.renderer
                r.init()
                r.queue.updateByMessage("setPlaylist", setPayload(), Client.YT)
                assertNotNull(r.queue.current)
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { h.prepared.any { it.videoId == B } }

                // A newer load of the same track while B is still being prepared.
                assertTrue(r.doPlay(Video(A), 0.0))
                val releasedByTheReload = h.released.size
                waitUntil { h.released.size > releasedByTheReload }
                assertTrue(h.released.size > releasedByTheReload, "the stale B stream was released once its prepare finished")
                assertEquals(0, fake.count("SetNextAVTransportURI"), "the stale B was not handed to the speaker")
            }
        }
    }

    @Test
    @DisplayName("a renderer destroyed while SetNextAVTransportURI is in flight rethrows the cancellation and notes no failure")
    fun destroyWhileHandingOverTheNextTrack() {
        withFake { fake ->
            RendererHarness(
                fake,
                config = { Config(dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = 5000, prefetch = true, gapless = true) },
                playlistRequestHandlerFactory = { NextHandler() },
            ).use { h ->
                val r = h.renderer
                r.init()
                r.queue.updateByMessage("setPlaylist", setPayload(), Client.YT)
                fake.beforeAction = { action -> if (action == "SetNextAVTransportURI") delay(10_000) }
                assertTrue(r.doPlay(Video(A), 0.0))
                waitUntil { fake.count("SetNextAVTransportURI") > 0 }
                assertTrue(fake.count("SetNextAVTransportURI") > 0)

                r.destroy()
                delay(200)
                assertTrue(h.notes("SetNextAVTransportURI failed").isEmpty())
            }
        }
    }

    @Test
    @DisplayName("in streaming mode a speaker that refuses the adts URL gets the same track as mp3 and no failure is reported")
    fun streamingModeFallsBackFromAdtsToMp3() {
        withFake(FakeRenderer(bareFaultOn = setOf("SetAVTransportURI"))) { fake ->
            RendererHarness(fake, config = pinned("streaming")).use { h ->
                h.renderer.init()
                // The first SetAVTransportURI is answered with a bare 500; from the second on the fault is gone.
                fake.beforeAction = { action ->
                    if (action == "SetAVTransportURI" && fake.count("SetAVTransportURI") >= 2) fake.bareFaultOn = emptySet()
                }

                assertTrue(h.renderer.play(Video(A), 0.0))

                assertEquals(listOf("adts", "mp3"), h.formats())
                assertEquals(2, fake.count("SetAVTransportURI"))
                assertEquals(1, h.notes("Renderer rejected adts").size)
                assertTrue(h.notes("Play $A failed").isEmpty())
                assertTrue(fake.state.currentUri.endsWith(".mp3"), fake.state.currentUri)
            }
        }
    }
}
