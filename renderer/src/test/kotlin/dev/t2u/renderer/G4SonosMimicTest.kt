package dev.t2u.renderer

// End-to-end reproduction of defect G4 (an early Pause then Play on the SYMFONISK Lamp) and proof of the plan 02-16
// fix, quick task 261021-c6t. GPL-3.0-only.
//
// The real Renderer talks SOAP to a SonosMimic (FakeRenderer plus a Lamp-like HTTP client, see SonosMimic.kt); the
// token URL it hands the speaker is served by the real MediaServer, which launches the real /usr/bin/ffmpeg on a
// 90 s fragmented M4A held by a local upstream HTTP server (the same setup kind as MediaServerLadderTest). The
// sender side is driven through Player.pause()/resume(). Nothing leaves 127.0.0.1: no LAN, device, adb or speaker.
//
// Timing is scaled down from the device log (poll 2 s -> 1.5 s, start timeout 12 s -> 2.5 s, "Play 5 s later" ->
// 1 s) with the order of events kept. Waits are condition waits with timeouts; the only fixed delays are the paused
// gap and the settle time the assertions on "nothing else happened" need.

import com.sun.net.httpserver.HttpServer
import dev.t2u.lounge.CastReceiver
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.ReceiverFactory
import dev.t2u.lounge.Sender
import dev.t2u.lounge.Video
import dev.t2u.media.FfmpegUrlPolicy
import dev.t2u.media.JvmPipeLauncher
import dev.t2u.media.MediaRequest
import dev.t2u.media.MediaServer
import dev.t2u.media.PipeLauncher
import dev.t2u.media.PipeRangeConfig
import dev.t2u.media.ResolvedInfo
import dev.t2u.media.VideoResolver
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.FakeRenderer
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VIDEO = "AAAAAAAAAAA"

/** Cast at this position: ffmpeg starts at `-ss 30` of the 90 s source, so the speaker's stream holds 60 s. */
private const val CAST_AT = 30.0

/** PLAY-03, PLAY-05: the Lamp's early-Pause behaviour against the real renderer and media server. */
class G4SonosMimicTest {
    @TempDir lateinit var dir: File

    private val ffmpeg = "/usr/bin/ffmpeg"
    private lateinit var upstream: HttpServer
    private lateinit var base: String
    private lateinit var launcher: CountingLauncher
    private lateinit var fake: FakeRenderer
    private lateinit var mimic: SonosMimic
    private lateinit var server: MediaServer
    private lateinit var renderer: Renderer
    private lateinit var scope: CoroutineScope

    private val seen = CopyOnWriteArrayList<MediaRequest>()
    private val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()

    /** Wraps the real launcher so a test can count the ffmpeg processes the media server started. */
    private class CountingLauncher(val inner: JvmPipeLauncher) : PipeLauncher {
        val starts = AtomicInteger()

        override fun start(args: List<String>): Process {
            starts.incrementAndGet()
            return inner.start(args)
        }

        override fun stderrTail(process: Process): String = inner.stderrTail(process)
    }

    private class StubReceiver : CastReceiver {
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

    @AfterEach
    fun tearDown() {
        if (::renderer.isInitialized) runBlocking { runCatching { renderer.destroy() } }
        if (::scope.isInitialized) scope.cancel()
        if (::mimic.isInitialized) mimic.close()
        if (::server.isInitialized) server.stop()
        if (::fake.isInitialized) fake.close()
        if (::upstream.isInitialized) upstream.stop(0)
    }

    /**
     * Builds the whole rig. [upstreamPath] picks the source URL the resolver returns (a path containing "slow" makes
     * the upstream stream in small pieces, so the first fetch is still running when a Pause arrives).
     */
    private fun rig(
        pipeRanges: PipeRangeConfig = PipeRangeConfig(enabled = true),
        offsets: List<Long> = SonosMimic.DEVICE_OFFSETS,
        earlyWindowMs: Long = 3000,
        upstreamPath: String = "s.m4a",
    ) {
        assumeTrue(File(ffmpeg).canExecute(), "needs $ffmpeg")
        val source = sourceM4a()
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { ex ->
            ex.use {
                val slow = ex.requestURI.path.contains("slow")
                val m = Regex("bytes=(\\d+)-(\\d*)").find(ex.requestHeaders.getFirst("Range").orEmpty())
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                if (m != null) {
                    val a = m.groupValues[1].toInt()
                    val b = m.groupValues[2].toIntOrNull() ?: (source.size - 1)
                    ex.responseHeaders.add("Content-Range", "bytes $a-$b/${source.size}")
                    ex.sendResponseHeaders(206, (b - a + 1).toLong())
                    writeBody(ex.responseBody, source, a, b - a + 1, slow)
                } else {
                    ex.sendResponseHeaders(200, source.size.toLong())
                    writeBody(ex.responseBody, source, 0, source.size, slow)
                }
            }
        }
        upstream.executor = Executors.newCachedThreadPool { r -> Thread(r, "upstream").also { it.isDaemon = true } }
        upstream.start()
        base = "http://127.0.0.1:${upstream.address.port}"

        val resolver = object : VideoResolver {
            override suspend fun resolve(videoId: String) = ResolvedInfo(
                url = "$base/$upstreamPath",
                ext = "m4a",
                mime = "audio/mp4",
                headers = emptyMap(),
                title = "Mimic song",
                artist = "Band",
                album = "",
                thumbnail = "https://i/x.jpg",
                duration = 90.0,
                filesize = 0,
                formatId = null,
            )

            override fun invalidate(videoId: String) {}
        }

        launcher = CountingLauncher(JvmPipeLauncher(ffmpeg))
        server = MediaServer("127.0.0.1", 0, launcher, { seen.add(it) }, File(dir, "buffer"), resolver, pipeRanges, ffmpegUrlPolicy = FfmpegUrlPolicy.ANY_HTTP).also { it.start() }

        fake = FakeRenderer(neverPlay = true).also { it.start() } // the mimic decides when the speaker is PLAYING
        mimic = SonosMimic(fake, offsets = offsets, earlyWindowMs = earlyWindowMs)

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receiver = StubReceiver()
        renderer = Renderer(
            udn = "fake-renderer",
            location = fake.location,
            address = "127.0.0.1",
            maxAge = 1800,
            port = 3000,
            deps = RendererDeps(
                config = Config(
                    dataDir = File(dir, "data"),
                    outputFormat = "adts", // pinned to the pipe format these assertions were written for (plan 03-06)
                    mediaPort = server.resolvedPort(),
                    pollIntervalMs = POLL_MS,
                    startTimeoutMs = START_TIMEOUT_MS,
                ),
                resolver = resolver,
                media = server,
                http = lanHttpClient(),
                createReceiver = ReceiverFactory { _, _ -> receiver },
                scope = scope,
                events = EventSink { kind, fields -> events.add(kind to fields) },
                allowLoopbackSpeakers = true,
            ),
        )
        runBlocking { assertTrue(renderer.init()) }
    }

    /** A path containing "slow" streams in 8 KB pieces with a 15 ms pause between them (about 530 KB/s). */
    private fun writeBody(out: java.io.OutputStream, file: ByteArray, off: Int, len: Int, slow: Boolean) {
        try {
            if (!slow) {
                out.write(file, off, len)
                return
            }
            var done = 0
            while (done < len) {
                val n = minOf(8192, len - done)
                out.write(file, off + done, n)
                out.flush()
                done += n
                Thread.sleep(15)
            }
        } catch (_: java.io.IOException) {
            // ffmpeg hung up (killed): nothing to report
        }
    }

    private fun errorNotes(): List<String> =
        events.filter { it.first == "renderer" && it.second["level"] == "error" }.map { it.second["message"] as String }

    private fun getStatuses(): List<Int> = seen.filter { it.method == "GET" }.map { it.status }

    private fun awaitNoFfmpeg() = awaitCondition("no ffmpeg process left", 10_000) { launcher.inner.liveCount() == 0 }

    // ------------------------------------------------------------------------------------------ (1)

    @Test
    @DisplayName("G4 fixed: an early Pause then Play continues from the first run's bytes, one ffmpeg launch, PLAYING at the right place")
    fun earlyPauseThenPlayContinuesFromTheFirstRun() = runBlocking {
        rig(offsets = listOf(41_357L)) // 41357 bytes at 16 KB/s = 2 s into the stream
        assertTrue(renderer.play(Video(VIDEO), CAST_AT))
        mimic.awaitPlaying()
        mimic.awaitFirstBodyComplete() // the Lamp held the whole track within about a second, as on the device
        val first = mimic.requests[0]
        assertEquals(null, first.range)
        assertEquals(200, first.status)

        assertTrue(renderer.pause()) // early: well inside the mimic's early window
        assertEquals(PlayerStatus.PAUSED, renderer.status)
        assertEquals(listOf(41_357L), mimic.pauseOffsets)
        delay(1000) // "Play 5 s later", scaled; a paused speaker asks for nothing
        assertEquals(1, mimic.requests.size, "nothing requested while paused: ${mimic.requests}")

        assertTrue(renderer.resume())
        mimic.awaitPlaying()
        mimic.awaitRequestDone(2)

        // The Lamp's resume request was answered 206 from the bytes it already got: right range, right total, right bytes.
        val total = first.body.size
        val resume = mimic.requests[1]
        assertEquals("bytes=41357-", resume.range)
        assertEquals(206, resume.status, "${mimic.requests}")
        assertEquals("bytes 41357-${total - 1}/$total", resume.contentRange)
        assertTrue(resume.complete, "$resume")
        assertContentEquals(first.body.copyOfRange(41_357, total), resume.body, "the 206 body is the tail of the first body")

        // No replay from byte 0 and no new ffmpeg: one plain GET, one ranged GET, one launch, the server's own log agrees.
        assertEquals(1, mimic.requests.count { it.range == null })
        assertEquals(1, launcher.starts.get(), "ffmpeg launches")
        awaitCondition("both GETs reported by the media server", 5000) { seen.count { it.method == "GET" } >= 2 }
        assertEquals(listOf(200, 206), getStatuses())
        assertEquals("cached-range", seen.last { it.method == "GET" }.pipeMode)
        assertEquals(1, server.tokenCount(), "one token registered by the single cast")

        // The sender sees PLAYING at the right place: the stream started at 30 s, the speaker is 2 s into it.
        assertEquals(PlayerStatus.PLAYING, renderer.status)
        assertEquals(CAST_AT + 2.0, renderer.doGetPosition())
        delay(POLL_MS + 300) // a poll after the Play sees PLAYING; it is never a failed start
        assertEquals("PLAYING", fake.state.transport)
        assertEquals(PlayerStatus.PLAYING, renderer.status)
        assertNull(renderer.lastError)
        assertEquals(emptyList(), errorNotes())
        assertTrue(mimic.requests.none { it.hungUp }, "the speaker never hung up: ${mimic.requests}")
        assertEquals(1, fake.calls.count { it.action == "Stop" }, "only the load's own Stop reached the speaker")
        assertEquals(1, fake.calls.count { it.action == "SetAVTransportURI" }, "the speaker was never reloaded")
    }

    // ------------------------------------------------------------------------------------------ (2)

    @Test
    @DisplayName("G4 reproduced: the same scenario with range answering off makes the speaker hang up and the renderer report the start failure")
    fun theSameScenarioWithRangeAnsweringOffFails() = runBlocking {
        rig(pipeRanges = PipeRangeConfig(enabled = false), offsets = listOf(7_227L))
        assertTrue(renderer.play(Video(VIDEO), CAST_AT))
        mimic.awaitPlaying()
        mimic.awaitFirstBodyComplete()
        assertTrue(renderer.pause())
        delay(1000)
        assertTrue(renderer.resume())

        mimic.awaitRequestDone(2)
        val resume = mimic.requests[1]
        assertEquals("bytes=7227-", resume.range)
        assertEquals(200, resume.status, "today's old answer: 200 from byte 0 to a Range request")
        assertNull(resume.contentRange)
        assertTrue(resume.hungUp, "the speaker hangs up on it: $resume")
        assertEquals(2, launcher.starts.get(), "the 200 relaunched ffmpeg (a replay from the start)")
        assertEquals(1, mimic.playingReports, "the speaker reported PLAYING for the first fetch only")

        // ... and the renderer ends in the logged failure.
        awaitCondition("the renderer to report the failed start", 15_000) { renderer.lastError != null }
        assertTrue(renderer.lastError!!.contains("never started playing"), renderer.lastError)
        assertTrue(errorNotes().any { it.contains("never started playing") }, "${errorNotes()}")
        assertEquals(PlayerStatus.STOPPED, renderer.status)
        assertEquals(1, mimic.playingReports)
        awaitCondition("both GETs reported by the media server", 5000) { seen.count { it.method == "GET" } >= 2 }
        assertEquals(listOf(200, 200), getStatuses())
        awaitNoFfmpeg() // the hang-up left no process behind
    }

    // ------------------------------------------------------------------------------------------ (3)

    @Test
    @DisplayName("G4 fixed: rapid Pause/Play toggling in the first seconds ends PLAYING with no new ffmpeg launch")
    fun rapidToggleEndsPlaying() = runBlocking {
        rig(earlyWindowMs = 8000) // every toggle is early: each Pause drops the connection and each Play re-requests
        assertTrue(renderer.play(Video(VIDEO), CAST_AT))
        mimic.awaitPlaying()
        mimic.awaitFirstBodyComplete()

        repeat(4) { n ->
            assertTrue(renderer.pause(), "pause #${n + 1}")
            assertTrue(renderer.resume(), "resume #${n + 1}")
        }
        mimic.awaitPlaying()
        mimic.awaitRequestDone(5)

        val offsets = SonosMimic.DEVICE_OFFSETS.take(4)
        assertEquals(offsets, mimic.pauseOffsets)
        assertEquals(listOf(null) + offsets.map { "bytes=$it-" }, mimic.requests.map { it.range })
        val total = mimic.requests[0].body.size
        for ((i, offset) in offsets.withIndex()) {
            val r = mimic.requests[i + 1]
            assertEquals(206, r.status, "${mimic.requests}")
            assertEquals("bytes $offset-${total - 1}/$total", r.contentRange)
        }
        // a Pause cuts a ranged GET short; the last one is consumed to its end and is the tail of the first body
        val last = mimic.requests.last()
        assertTrue(last.complete, "$last")
        assertContentEquals(mimic.requests[0].body.copyOfRange(offsets.last().toInt(), total), last.body)
        assertEquals(1, launcher.starts.get(), "ffmpeg launches")
        assertEquals(1, mimic.requests.count { it.range == null }, "no replay from byte 0")

        assertEquals(PlayerStatus.PLAYING, renderer.status)
        assertEquals("PLAYING", fake.state.transport)
        assertEquals(CAST_AT + 2.0, renderer.doGetPosition(), "the last Pause was at 41357 bytes")
        delay(POLL_MS + 300)
        assertEquals(PlayerStatus.PLAYING, renderer.status)
        assertNull(renderer.lastError)
        assertEquals(emptyList(), errorNotes())
        assertTrue(mimic.requests.none { it.status != 206 && it.range != null })
    }

    // ------------------------------------------------------------------------------------------ (4)

    @Test
    @DisplayName("a late Pause (the whole body consumed, the speaker settled) then Play makes no new request and no ffmpeg launch")
    fun aLatePauseMakesNoRequest() = runBlocking {
        rig(earlyWindowMs = 800)
        assertTrue(renderer.play(Video(VIDEO), CAST_AT))
        mimic.awaitPlaying()
        mimic.awaitFirstBodyComplete()
        awaitCondition("the speaker to settle past its early window", 10_000) { mimic.ageMs() > 1200 }
        assertEquals(1, launcher.starts.get())

        assertTrue(renderer.pause())
        assertEquals(PlayerStatus.PAUSED, renderer.status)
        delay(700) // a late Pause drops nothing and asks for nothing
        assertTrue(renderer.resume())
        mimic.awaitPlaying()
        delay(POLL_MS + 300)

        assertEquals(1, mimic.requests.size, "no new request: ${mimic.requests}")
        assertEquals(1, launcher.starts.get(), "no new ffmpeg launch")
        assertEquals(listOf(200), getStatuses())
        assertEquals(PlayerStatus.PLAYING, renderer.status)
        assertEquals("PLAYING", fake.state.transport)
        assertNull(renderer.lastError)
        assertEquals(emptyList(), errorNotes())
        assertFalse(mimic.requests[0].hungUp)
        assertEquals(1, mimic.pauseOffsets.size)
    }

    // ------------------------------------------------------------------------------------------ extra

    @Test
    @DisplayName("G4 fixed on a cold token: a Pause that cuts the first fetch short, then Play, gets a bounded fill and a 206")
    fun aCutFirstFetchIsAnsweredFromABoundedFill() = runBlocking {
        rig(offsets = listOf(7_227L), upstreamPath = "slow.m4a")
        assertTrue(renderer.play(Video(VIDEO), CAST_AT))
        mimic.awaitPlaying() // first body byte
        assertTrue(renderer.pause()) // the slow upstream is still running: the first body is cut off
        mimic.awaitRequestDone(1)
        val first = mimic.requests[0]
        assertTrue(first.hungUp && !first.complete, "the first fetch was cut short: $first")
        assertTrue(first.bytes > 0)
        val offset = mimic.pauseOffsets.single()
        assertTrue(offset in 1..7_227, "offset $offset")
        awaitNoFfmpeg() // the hang-up killed the first run
        delay(500)

        assertTrue(renderer.resume())
        mimic.awaitPlaying()
        mimic.awaitRequestDone(2)
        val resume = mimic.requests[1]
        assertEquals("bytes=$offset-", resume.range)
        assertEquals(206, resume.status, "${mimic.requests}")
        assertTrue(resume.complete, "$resume")
        val total = Regex("/(\\d+)$").find(resume.contentRange.orEmpty())?.groupValues?.get(1)?.toInt()
        assertNotNull(total, resume.contentRange)
        assertEquals("bytes $offset-${total - 1}/$total", resume.contentRange)
        assertTrue(total > 100_000, "a whole 60 s stream, not the cut piece: $total")
        assertContentEquals(first.body.copyOfRange(offset.toInt(), first.body.size), resume.body.copyOf(first.body.size - offset.toInt()), "the fill's bytes continue the first run's bytes")
        assertEquals(2, launcher.starts.get(), "one live run (cut) plus one bounded fill")
        assertEquals(1, mimic.requests.count { it.range == null }, "no replay from byte 0")
        assertEquals(PlayerStatus.PLAYING, renderer.status)
        delay(POLL_MS + 300)
        assertNull(renderer.lastError)
        assertEquals(emptyList(), errorNotes())
    }

    companion object {
        private const val POLL_MS = 1500L
        private const val START_TIMEOUT_MS = 2500L

        /** A 90 s sine as fragmented M4A (what YouTube serves), made once with the real ffmpeg. */
        private val sourceFile: File by lazy {
            val f = File.createTempFile("g4-source-", ".m4a").also { it.deleteOnExit() }
            val p = ProcessBuilder(
                "/usr/bin/ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=90",
                "-c:a", "aac", "-b:a", "128k", "-f", "mp4", "-movflags", "frag_keyframe+empty_moov", f.absolutePath,
            ).redirectErrorStream(true).start()
            val log = p.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(0, p.waitFor(), log)
            f
        }

        private fun sourceM4a(): ByteArray = sourceFile.readBytes()
    }
}
