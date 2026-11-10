package dev.t2u.media

// Characterization tests for the MediaServer paths the split will move (plan 09-21, D-05). Each test pins what the
// code does today, found as a gap in .planning/phases/09-code-quality-cleanup/09-COVERAGE.md. They use scripted
// processes, resolvers and upstreams, so none of them needs ffmpeg. Every test is a block-bodied function: JUnit
// silently skips a test whose expression body returns a value.

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaServerCharacterizationTest {
    @TempDir lateinit var dir: File

    private var server: MediaServer? = null
    private val seen = CopyOnWriteArrayList<MediaRequest>()
    private val id = "dQw4w9WgXcQ"

    @AfterEach
    fun tearDown() {
        server?.stop()
    }

    // ----- fakes -------------------------------------------------------------------------------------------------

    /** A process that never ran: [stdout] is what ffmpeg would print, [waitForResult] answers every timed wait. */
    private class FakeProcess(
        private val stdout: InputStream = ByteArrayInputStream(ByteArray(0)),
        private val exit: Int = 0,
        private val waitForResult: () -> Boolean = { true },
        private val onDestroy: () -> Unit = {},
    ) : Process() {
        @Volatile var destroyed = false

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = exit

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = waitForResult()

        override fun exitValue(): Int = exit

        override fun destroy() {
            destroyForcibly()
        }

        override fun destroyForcibly(): Process {
            destroyed = true
            onDestroy()
            return this
        }

        override fun isAlive(): Boolean = !destroyed
    }

    private class ScriptedLauncher(private val stderr: String = "", private val script: (List<String>) -> Process) : PipeLauncher {
        val starts = CopyOnWriteArrayList<List<String>>()

        override fun start(args: List<String>): Process {
            starts.add(args)
            return script(args)
        }

        override fun stderrTail(process: Process): String = stderr
    }

    /** Unbounded output at a gentle pace; ends once the process was destroyed. */
    private class EndlessStream(private val stopped: () -> Boolean) : InputStream() {
        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (stopped()) return -1
            Thread.sleep(5)
            val n = minOf(len, 4096)
            java.util.Arrays.fill(b, off, off + n, 7)
            return n
        }
    }

    private fun info(n: Int = 0) = ResolvedInfo(
        "https://rr1.googlevideo.com/videoplayback?id=$id&n=$n&sig=SECRET", "m4a", "audio/mp4", emptyMap(),
        "t", "a", "", "", 60.0, 0, "140",
    )

    private class ScriptedResolver(private val onResolve: suspend (call: Int) -> ResolvedInfo) : VideoResolver {
        val resolves = AtomicInteger()
        val invalidations = AtomicInteger()

        override suspend fun resolve(videoId: String): ResolvedInfo = onResolve(resolves.getAndIncrement())

        override fun invalidate(videoId: String) {
            invalidations.incrementAndGet()
        }
    }

    private class ScriptedFetcher(private val respond: (call: Int, req: UpstreamRequest) -> UpstreamResponse) : UpstreamFetcher {
        val requests = CopyOnWriteArrayList<UpstreamRequest>()
        private val calls = AtomicInteger()

        override suspend fun fetch(request: UpstreamRequest): UpstreamResponse {
            requests.add(request)
            return respond(calls.getAndIncrement(), request)
        }
    }

    private fun payload(n: Int) = ByteArray(n) { (it % 251).toByte() }

    /** A 200 or [status] answer with the content length of [bytes]. */
    private fun body(bytes: ByteArray, status: Int = 200, extra: Map<String, String> = emptyMap()) =
        UpstreamResponse(status, mapOf("content-length" to bytes.size.toString()) + extra, ByteArrayInputStream(bytes))

    /** What googlevideo answers to a bounded Range: 206 with the true total. */
    private fun ranged(req: UpstreamRequest, data: ByteArray): UpstreamResponse {
        val m = Regex("bytes=(\\d+)-(\\d+)").find(req.headers["Range"].orEmpty()) ?: return body(data)
        val a = m.groupValues[1].toInt()
        val b = minOf(m.groupValues[2].toInt(), data.size - 1)
        return body(data.copyOfRange(a, b + 1), 206, mapOf("content-range" to "bytes $a-$b/${data.size}"))
    }

    private fun serveDirect(resolver: VideoResolver?, fetcher: UpstreamFetcher?): MediaServer =
        MediaServer("127.0.0.1", 0, null, { seen.add(it) }, null, resolver, PipeRangeConfig(), fetcher).also {
            it.start()
            server = it
        }

    private fun serveVideoPipe(launcher: PipeLauncher?, resolver: VideoResolver?, config: PipeRangeConfig = PipeRangeConfig()): MediaServer =
        MediaServer("127.0.0.1", 0, launcher, { seen.add(it) }, File(dir, "buffer"), resolver, config).also {
            it.start()
            server = it
        }

    private fun serveFiles(prepareTimeoutMs: Long = 120_000): MediaServer =
        MediaServer("127.0.0.1", 0, null, { seen.add(it) }, prepareTimeoutMs = prepareTimeoutMs).also {
            it.start()
            server = it
        }

    private fun registerDirect(ms: MediaServer) = ms.registerVideo(VideoEntry(id, "direct", 0, ext = "m4a", sourceMime = "audio/mp4"))

    private fun registerAdts(ms: MediaServer) = ms.registerVideo(VideoEntry(id, "adts"))

    private fun awaitReport(predicate: (MediaRequest) -> Boolean = { true }): MediaRequest {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            seen.firstOrNull(predicate)?.let { return it }
            Thread.sleep(25)
        }
        throw AssertionError("no matching request reported, saw $seen")
    }

    /** The status of the answer, or null when the connection ended without one. */
    private fun statusOrNull(ms: MediaServer, path: String, headers: Map<String, String> = emptyMap(), version: String = "HTTP/1.1"): Int? =
        Socket("127.0.0.1", ms.resolvedPort()).use { s ->
            s.soTimeout = 30_000
            s.getOutputStream().apply {
                write(RawHttp.requestBytes("GET", path, version, headers))
                flush()
            }
            try {
                RawHttp.readLine(s.getInputStream())?.split(' ')?.get(1)?.toInt()
            } catch (_: IOException) {
                null
            }
        }

    // ----- respondFile -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("file: a vanished file whose rebuild returns a file that is not there answers 502 'rebuild failed'")
    fun aRebuildThatReturnsAMissingFileIs502() {
        val ms = serveFiles()
        val gone = File(dir, "gone.m4a")
        val path = ms.register(StreamEntry(StreamSource.FileSource(gone) { File(dir, "still-gone.m4a") }, "mp4", "audio/mp4", "unit"))
        val r = RawHttp.request(ms.resolvedPort(), "GET", path)
        assertEquals(502, r.status)
        val rep = awaitReport()
        assertEquals(502, rep.status)
        assertEquals("rebuild failed", rep.note)
        assertEquals("file", rep.pipeMode)
    }

    @Test
    @DisplayName("file: a rebuild that throws answers 502 'rebuild failed' with no exception text")
    fun aRebuildThatThrowsIs502() {
        val ms = serveFiles()
        val path = ms.register(
            StreamEntry(StreamSource.FileSource(File(dir, "gone.m4a")) { throw IllegalStateException("boom https://x/?sig=SECRET") }, "mp4", "audio/mp4", "unit"),
        )
        assertEquals(502, RawHttp.request(ms.resolvedPort(), "GET", path).status)
        assertEquals("rebuild failed", awaitReport().note)
    }

    @Test
    @DisplayName("file: a rebuild that outlasts prepareTimeoutMs answers 502 'rebuild failed'")
    fun aRebuildThatTimesOutIs502() {
        val ms = serveFiles(prepareTimeoutMs = 200)
        val path = ms.register(
            StreamEntry(StreamSource.FileSource(File(dir, "gone.m4a")) { kotlinx.coroutines.delay(10_000); File(dir, "late.m4a") }, "mp4", "audio/mp4", "unit"),
        )
        assertEquals(502, RawHttp.request(ms.resolvedPort(), "GET", path).status)
        assertEquals("rebuild failed", awaitReport().note)
    }

    @Test
    @DisplayName("file: a rebuild that is cancelled ends the request with no answer and nothing is reported as served")
    fun aCancelledRebuildEndsTheRequestWithoutAnAnswer() {
        val ms = serveFiles()
        val path = ms.register(
            StreamEntry(StreamSource.FileSource(File(dir, "gone.m4a")) { throw CancellationException("cancelled") }, "mp4", "audio/mp4", "unit"),
        )
        val status = statusOrNull(ms, path)
        val rep = awaitReport()
        assertEquals(0, rep.status)
        assertEquals("file", rep.pipeMode)
        // the connection either ended without an answer or carries a server error; never a success
        assertTrue(status == null || status >= 500, "status $status")
    }

    @Test
    @DisplayName("file: an empty file answers 200 with Content-Length 0, and any Range on it is 416")
    fun anEmptyFileHasLengthZero() {
        val ms = serveFiles()
        val empty = File(dir, "empty.m4a").also { it.writeBytes(ByteArray(0)) }
        val path = ms.register(StreamEntry(StreamSource.FileSource(empty), "mp4", "audio/mp4", "unit"))
        val plain = RawHttp.request(ms.resolvedPort(), "GET", path)
        assertEquals(200, plain.status)
        assertEquals("0", plain.header("Content-Length"))
        assertEquals(0, plain.body.size)
        val ranged = RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to "bytes=0-9"))
        assertEquals(416, ranged.status)
        assertEquals("bytes */0", ranged.header("Content-Range"))
    }

    // ----- serveDirect, windows and relay --------------------------------------------------------------------------

    @Test
    @DisplayName("direct: without a resolver the token answers 501 'no resolver for video tokens'")
    fun directWithoutAResolverIs501() {
        val ms = serveDirect(null, ScriptedFetcher { _, _ -> error("must not fetch") })
        assertEquals(501, RawHttp.request(ms.resolvedPort(), "GET", registerDirect(ms)).status)
        assertEquals("no resolver for video tokens", awaitReport().note)
    }

    @Test
    @DisplayName("direct: an upstream body that fails mid-transfer ends the connection and notes 'transfer ended'")
    fun aFailingUpstreamBodyEndsTheTransfer() {
        val chunk = payload(4096)
        val failing = object : InputStream() {
            private var sent = false

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent) throw IOException("upstream reset")
                sent = true
                chunk.copyInto(b, off)
                return chunk.size
            }
        }
        val fetcher = ScriptedFetcher { _, _ -> UpstreamResponse(200, mapOf("content-length" to "1000000"), failing) }
        val ms = serveDirect(ScriptedResolver { info(it) }, fetcher)
        val path = registerDirect(ms)
        Socket("127.0.0.1", ms.resolvedPort()).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().apply {
                write(RawHttp.requestBytes("GET", path, "HTTP/1.1", emptyMap()))
                flush()
            }
            val input = s.getInputStream().buffered()
            val (status, _) = RawHttp.readHead(input)
            assertEquals(200, status)
            try {
                input.readBytes()
            } catch (_: IOException) {
                // a reset instead of a clean end is just as much "the transfer ended"
            }
        }
        val rep = awaitReport()
        assertEquals(200, rep.status)
        assertEquals("transfer ended: upstream reset", rep.note)
    }

    @Test
    @DisplayName("direct: a resolver that is cancelled ends the request with no answer")
    fun aCancelledResolveEndsTheDirectRequest() {
        val ms = serveDirect(ScriptedResolver { throw CancellationException("cancelled") }, ScriptedFetcher { _, _ -> error("must not fetch") })
        val status = statusOrNull(ms, registerDirect(ms))
        assertEquals(0, awaitReport().status)
        assertTrue(status == null || status >= 500, "status $status")
    }

    @Test
    @DisplayName("direct: a resolver cancelled while the second scan fetches its window ends that request with no answer")
    fun aCancelledResolveInsideTheWindowFetchEndsTheRequest() {
        val data = payload(200_000)
        val resolver = ScriptedResolver { n -> if (n == 0) info(n) else throw CancellationException("cancelled") }
        val ms = serveDirect(resolver, ScriptedFetcher { _, req -> ranged(req, data) })
        val path = registerDirect(ms)
        assertEquals(206, RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to "bytes=0-6143")).status)
        val status = statusOrNull(ms, path, mapOf("Range" to "bytes=6144-12287"))
        assertTrue(status == null || status >= 500, "status $status")
        assertEquals(2, resolver.resolves.get())
    }

    @Test
    @DisplayName("direct: a window whose body is shorter than its Content-Range turns read-ahead off and the plain path answers")
    fun aShortWindowBodyTurnsReadAheadOff() {
        val data = payload(200_000)
        val fetcher = ScriptedFetcher { n, req ->
            if (n == 1) {
                // the window call: names 10000 bytes from 6144, sends 5000
                body(data.copyOfRange(6144, 6144 + 5000), 206, mapOf("content-range" to "bytes 6144-16143/${data.size}"))
            } else {
                ranged(req, data)
            }
        }
        val ms = serveDirect(ScriptedResolver { info(it) }, fetcher)
        val path = registerDirect(ms)
        for (start in listOf(0, 6144)) {
            val end = start + 6143
            val r = RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to "bytes=$start-$end"))
            assertEquals(206, r.status)
            assertTrue(data.copyOfRange(start, end + 1).contentEquals(r.body))
        }
        assertEquals(3, fetcher.requests.size, "scan 1 plain, the window, then scan 2 plain")
        assertEquals("bytes=6144-${6144 + 512 * 1024 - 1}", fetcher.requests[1].headers["Range"])
        val rep = awaitReport { it.range == "bytes=6144-12287" }
        assertEquals("read-ahead off for this stream: upstream sent 5000 of 10000 bytes", rep.note)
    }

    @Test
    @DisplayName("direct: an upstream error on the window fetch is passed on by the plain path and read-ahead stays on")
    fun anErrorOnTheWindowFetchIsPassedOnByThePlainPath() {
        val data = payload(200_000)
        val fetcher = ScriptedFetcher { n, req -> if (n == 0) ranged(req, data) else body(ByteArray(0), 500) }
        val ms = serveDirect(ScriptedResolver { info(it) }, fetcher)
        val path = registerDirect(ms)
        assertEquals(206, RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to "bytes=0-6143")).status)
        assertEquals(500, RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to "bytes=6144-12287")).status)
        // the window call (n = 1) and the plain call (n = 2) both failed; the next scan tries a window again
        assertEquals(3, fetcher.requests.size)
        assertEquals(500, RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to "bytes=12288-18431")).status)
        assertEquals(5, fetcher.requests.size)
    }

    @Test
    @DisplayName("direct: an upstream 304 is passed on with no body and no length, and Accept-Ranges comes from upstream")
    fun anUpstream304IsPassedOnWithoutALength() {
        val fetcher = ScriptedFetcher { _, _ -> UpstreamResponse(304, mapOf("accept-ranges" to "none"), null) }
        val ms = serveDirect(ScriptedResolver { info(it) }, fetcher)
        val r = RawHttp.request(ms.resolvedPort(), "GET", registerDirect(ms))
        assertEquals(304, r.status)
        assertEquals("none", r.header("Accept-Ranges"))
        assertEquals(0, r.body.size)
    }

    @Test
    @DisplayName("direct: an upstream 200 without Content-Length is relayed chunked")
    fun anUpstreamWithoutALengthIsRelayedChunked() {
        val data = payload(300)
        val fetcher = ScriptedFetcher { _, _ -> UpstreamResponse(200, emptyMap(), ByteArrayInputStream(data)) }
        val ms = serveDirect(ScriptedResolver { info(it) }, fetcher)
        val r = RawHttp.request(ms.resolvedPort(), "GET", registerDirect(ms))
        assertEquals(200, r.status)
        assertNull(r.header("Content-Length"))
        assertTrue(r.header("Transfer-Encoding").orEmpty().contains("chunked", true), r.headerLines.toString())
        assertTrue(data.contentEquals(r.body))
        assertEquals("bytes", r.header("Accept-Ranges"))
    }

    @Test
    @DisplayName("direct: stopping the server in the middle of a relay closes the upstream response")
    fun stoppingTheServerMidRelayClosesTheUpstream() {
        val closes = AtomicInteger()
        val stopped = CountDownLatch(1)
        val slow = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(5)
                val n = minOf(len, 4096)
                java.util.Arrays.fill(b, off, off + n, 7)
                return n
            }
        }
        val fetcher = ScriptedFetcher { _, _ ->
            UpstreamResponse(200, mapOf("content-length" to "100000000"), slow) { closes.incrementAndGet(); stopped.countDown() }
        }
        val ms = serveDirect(ScriptedResolver { info(it) }, fetcher)
        val path = registerDirect(ms)
        Socket("127.0.0.1", ms.resolvedPort()).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().apply {
                write(RawHttp.requestBytes("GET", path, "HTTP/1.1", emptyMap()))
                flush()
            }
            val input = s.getInputStream().buffered()
            RawHttp.readHead(input)
            assertEquals(2000, input.readNBytes(2000).size)
            ms.stop()
            assertTrue(stopped.await(5, TimeUnit.SECONDS), "the upstream must be released when the server stops")
        }
        assertEquals(1, closes.get())
    }

    // ----- servePipe -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("pipe: when every attempt's input fails, the answer is 502 and the note lists each attempt, without the URL")
    fun everyAttemptFailingToResolveIs502WithEachFailureNoted() {
        val resolver = ScriptedResolver { throw ResolveException("yt-dlp failed: https://rr1.googlevideo.com/videoplayback?sig=SECRET") }
        val launcher = ScriptedLauncher { error("must not start a process") }
        val ms = serveVideoPipe(launcher, resolver)
        val r = RawHttp.request(ms.resolvedPort(), "GET", registerAdts(ms))
        assertEquals(502, r.status)
        val rep = awaitReport()
        assertEquals(502, rep.status)
        assertEquals("chunked", rep.pipeMode)
        val note = rep.note.orEmpty()
        assertTrue(note.startsWith("YouTube URL: yt-dlp failed: "), note)
        assertTrue(" | a fresh extraction: yt-dlp failed: " in note, note)
        assertTrue("SECRET" !in note && "sig=" !in note, note)
        assertEquals(2, resolver.resolves.get())
        assertEquals(1, resolver.invalidations.get(), "the second attempt drops the cached resolve first")
        assertEquals(0, launcher.starts.size)
    }

    @Test
    @DisplayName("pipe: a resolver that is cancelled ends the request with no answer and starts no process")
    fun aCancelledResolveEndsThePipeRequest() {
        val launcher = ScriptedLauncher { error("must not start a process") }
        val ms = serveVideoPipe(launcher, ScriptedResolver { throw CancellationException("cancelled") })
        val status = statusOrNull(ms, registerAdts(ms))
        assertEquals(0, awaitReport().status)
        assertTrue(status == null || status >= 500, "status $status")
        assertEquals(0, launcher.starts.size)
    }

    @Test
    @DisplayName("pipe: an ffmpeg that prints nothing for 20 s is killed by the watchdog and the answer is 502 'no first byte'")
    fun theFirstByteWatchdogKillsASilentFfmpeg() {
        val release = CountDownLatch(1)
        val silent = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                release.await(60, TimeUnit.SECONDS)
                return -1
            }
        }
        val proc = FakeProcess(stdout = silent, exit = 137, onDestroy = { release.countDown() })
        val launcher = ScriptedLauncher { proc }
        val ms = serveVideoPipe(launcher, null)
        val path = ms.register(StreamEntry(StreamSource.PipeSource(listOf("-i", "x", "-vn")), "adts", "audio/aac", "unit"))
        val r = RawHttp.request(ms.resolvedPort(), "GET", path)
        assertEquals(502, r.status)
        assertEquals("no first byte after 20 s", awaitReport().note)
        assertTrue(proc.destroyed)
    }

    @Test
    @DisplayName("pipe: stopping the server in the middle of a stream kills ffmpeg")
    fun stoppingTheServerMidStreamKillsFfmpeg() {
        val proc = FakeProcess(stdout = EndlessStream { false }, waitForResult = { false })
        val launcher = ScriptedLauncher { proc }
        val ms = serveVideoPipe(launcher, null)
        val path = ms.register(StreamEntry(StreamSource.PipeSource(listOf("-i", "x", "-vn")), "adts", "audio/aac", "unit"))
        Socket("127.0.0.1", ms.resolvedPort()).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().apply {
                write(RawHttp.requestBytes("GET", path, "HTTP/1.1", emptyMap()))
                flush()
            }
            val input = s.getInputStream().buffered()
            RawHttp.readHead(input)
            assertEquals(2000, input.readNBytes(2000).size)
            ms.stop()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!proc.destroyed && System.nanoTime() < deadline) Thread.sleep(25)
            assertTrue(proc.destroyed, "ffmpeg must be killed when the server stops")
        }
    }

    // ----- coldFill ------------------------------------------------------------------------------------------------

    private val tightCaps = PipeRangeConfig(maxBytesPerToken = 1000, maxBytesTotal = 2000, coldFillTimeoutMs = 5000)

    /** A launcher whose cold fill ("-y" first, part file last) writes [fillBytes] and whose live run prints [live]. */
    private fun fillLauncher(fillBytes: Int, fillExit: Int = 0, fillDone: Boolean = true, live: ByteArray = payload(3000)) =
        ScriptedLauncher { args ->
            if (args.first() == "-y") {
                File(args.last()).writeBytes(ByteArray(fillBytes) { 1 })
                FakeProcess(exit = fillExit, waitForResult = { if (!fillDone) Thread.sleep(10); fillDone })
            } else {
                FakeProcess(stdout = ByteArrayInputStream(live))
            }
        }

    @Test
    @DisplayName("cold fill: a resume Range with every input failing falls through to the live path, which fails the same way")
    fun aColdFillWhoseInputsFailFallsBackToTheLivePath() {
        val resolver = ScriptedResolver { throw ResolveException("no url") }
        val launcher = ScriptedLauncher { error("must not start a process") }
        val ms = serveVideoPipe(launcher, resolver)
        val r = RawHttp.request(ms.resolvedPort(), "GET", registerAdts(ms), mapOf("Range" to "bytes=100-"))
        assertEquals(502, r.status)
        assertEquals("YouTube URL: no url | a fresh extraction: no url", awaitReport().note)
        assertEquals(4, resolver.resolves.get(), "two attempts in the fill, two in the live path")
        assertEquals(0, launcher.starts.size)
    }

    @Test
    @DisplayName("cold fill: a resolver cancelled inside the fill ends the request with no answer")
    fun aCancelledResolveInsideAColdFillEndsTheRequest() {
        val launcher = ScriptedLauncher { error("must not start a process") }
        val ms = serveVideoPipe(launcher, ScriptedResolver { throw CancellationException("cancelled") })
        val status = statusOrNull(ms, registerAdts(ms), mapOf("Range" to "bytes=100-"))
        assertEquals(0, awaitReport().status)
        assertTrue(status == null || status >= 500, "status $status")
    }

    @Test
    @DisplayName("cold fill: a fill that grows past the per-token cap is dropped, the token is marked too big and the live 200 answers")
    fun aColdFillPastTheCapWhileRunningFallsBackToTheLivePath() {
        val live = payload(3000)
        val launcher = fillLauncher(fillBytes = 5000, fillDone = false, live = live)
        val ms = serveVideoPipe(launcher, ScriptedResolver { info(it) }, tightCaps)
        val path = registerAdts(ms)
        val r = RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to "bytes=100-"))
        assertEquals(200, r.status)
        assertTrue(live.contentEquals(r.body))
        assertEquals("range fill passed the size cap", awaitReport().note)
        // the token is remembered as too big: the next resume Range starts no fill
        val fills = launcher.starts.count { it.first() == "-y" }
        assertEquals(1, fills)
        assertEquals(200, RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to "bytes=100-")).status)
        assertEquals(fills, launcher.starts.count { it.first() == "-y" })
    }

    @Test
    @DisplayName("cold fill: a fill that ends over the cap is dropped, the token is marked too big and the live 200 answers")
    fun aColdFillPastTheCapAfterItEndedFallsBackToTheLivePath() {
        val live = payload(3000)
        val launcher = fillLauncher(fillBytes = 5000, live = live)
        val ms = serveVideoPipe(launcher, ScriptedResolver { info(it) }, tightCaps)
        val path = registerAdts(ms)
        val r = RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to "bytes=100-"))
        assertEquals(200, r.status)
        assertTrue(live.contentEquals(r.body))
        assertNull(awaitReport().note)
        assertEquals(1, launcher.starts.count { it.first() == "-y" })
        assertEquals(200, RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to "bytes=100-")).status)
        assertEquals(1, launcher.starts.count { it.first() == "-y" })
    }

    @Test
    @DisplayName("cold fill: a fill that exits non-zero tries the next attempt, then the live path answers")
    fun aColdFillThatExitsNonZeroFallsBackToTheLivePath() {
        val live = payload(3000)
        val launcher = fillLauncher(fillBytes = 10, fillExit = 1, live = live)
        val ms = serveVideoPipe(launcher, ScriptedResolver { info(it) })
        val r = RawHttp.request(ms.resolvedPort(), "GET", registerAdts(ms), mapOf("Range" to "bytes=100-"))
        assertEquals(200, r.status)
        assertTrue(live.contentEquals(r.body))
        assertEquals(2, launcher.starts.count { it.first() == "-y" }, "both attempts of the ladder filled once")
    }

    @Test
    @DisplayName("cold fill: a good fill is answered 206 with the true total from the filled file")
    fun aGoodColdFillIsAnswered206() {
        val launcher = fillLauncher(fillBytes = 600)
        val ms = serveVideoPipe(launcher, ScriptedResolver { info(it) })
        val r = RawHttp.request(ms.resolvedPort(), "GET", registerAdts(ms), mapOf("Range" to "bytes=100-"))
        assertEquals(206, r.status)
        assertEquals("bytes 100-599/600", r.header("Content-Range"))
        assertEquals(500, r.body.size)
        val rep = awaitReport()
        assertEquals("range-fill", rep.pipeMode)
        assertEquals("range bytes=100- of 600 bytes from a bounded fill", rep.note)
    }

    // ----- serveBuffered -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("buffered: an HTTP/1.0 client whose every input fails gets 502, pipeMode buffered, and each failure is noted")
    fun bufferedWithFailingInputsIs502() {
        val resolver = ScriptedResolver { throw ResolveException("no url") }
        val launcher = ScriptedLauncher { error("must not start a process") }
        val ms = serveVideoPipe(launcher, resolver)
        val r = RawHttp.request(ms.resolvedPort(), "GET", registerAdts(ms), version = "HTTP/1.0")
        assertEquals(502, r.status)
        val rep = awaitReport()
        assertEquals("buffered", rep.pipeMode)
        assertEquals("YouTube URL: no url | a fresh extraction: no url", rep.note)
        assertEquals(emptyList(), File(dir, "buffer").list().orEmpty().filter { it.startsWith("pipe-") }, "the temp file is deleted")
    }

    @Test
    @DisplayName("buffered: a resolver cancelled while building the input ends the HTTP/1.0 request with no answer")
    fun bufferedWithACancelledResolveEndsTheRequest() {
        val launcher = ScriptedLauncher { error("must not start a process") }
        val ms = serveVideoPipe(launcher, ScriptedResolver { throw CancellationException("cancelled") })
        val status = statusOrNull(ms, registerAdts(ms), version = "HTTP/1.0")
        assertEquals(0, awaitReport().status)
        assertTrue(status == null || status >= 500, "status $status")
        assertEquals(emptyList(), File(dir, "buffer").list().orEmpty().filter { it.startsWith("pipe-") })
    }

    @Test
    @DisplayName("buffered: an ffmpeg that does not finish in time is killed and the answer is 502 'did not finish within 180 s'")
    fun bufferedWithAnFfmpegThatDoesNotFinishIs502() {
        val proc = FakeProcess(waitForResult = { false })
        val launcher = ScriptedLauncher { proc }
        val ms = serveVideoPipe(launcher, null)
        val path = ms.register(StreamEntry(StreamSource.PipeSource(listOf("-i", "x", "-vn")), "adts", "audio/aac", "unit"))
        assertEquals(502, RawHttp.request(ms.resolvedPort(), "GET", path, version = "HTTP/1.0").status)
        val rep = awaitReport()
        assertEquals("buffered", rep.pipeMode)
        assertEquals("ffmpeg did not finish within 180 s", rep.note)
        assertTrue(proc.destroyed)
    }

    @Test
    @DisplayName("buffered: an ffmpeg that exits non-zero gives 502 with its exit code and stderr tail")
    fun bufferedWithAFailingFfmpegIs502() {
        val proc = FakeProcess(exit = 1)
        val launcher = ScriptedLauncher(stderr = "Invalid data found") { proc }
        val ms = serveVideoPipe(launcher, null)
        val path = ms.register(StreamEntry(StreamSource.PipeSource(listOf("-i", "x", "-vn")), "adts", "audio/aac", "unit"))
        assertEquals(502, RawHttp.request(ms.resolvedPort(), "GET", path, version = "HTTP/1.0").status)
        assertEquals("ffmpeg exit 1: Invalid data found", awaitReport().note)
    }

    @Test
    @DisplayName("buffered: an ffmpeg that exits 0 but wrote nothing is also 502")
    fun bufferedWithAnEmptyOutputIs502() {
        val launcher = ScriptedLauncher(stderr = "") { FakeProcess(exit = 0) }
        val ms = serveVideoPipe(launcher, null)
        val path = ms.register(StreamEntry(StreamSource.PipeSource(listOf("-i", "x", "-vn")), "adts", "audio/aac", "unit"))
        assertEquals(502, RawHttp.request(ms.resolvedPort(), "GET", path, version = "HTTP/1.0").status)
        assertEquals("ffmpeg exit 0:", awaitReport().note)
    }

    @Test
    @DisplayName("pipe: a pipe token on a server with no launcher answers 501 'no ffmpeg launcher'")
    fun aPipeWithoutALauncherIs501() {
        val ms = serveVideoPipe(null, null)
        val path = ms.register(StreamEntry(StreamSource.PipeSource(listOf("-i", "x")), "adts", "audio/aac", "unit"))
        assertEquals(501, RawHttp.request(ms.resolvedPort(), "GET", path).status)
        assertEquals("no ffmpeg launcher", awaitReport().note)
    }

    @Test
    @DisplayName("pipe: a pipe token whose format is not a pipe format answers 501 'is not a pipe format'")
    fun aPipeTokenWithAFileFormatIs501() {
        val ms = serveVideoPipe(ScriptedLauncher { error("no") }, null)
        val path = ms.register(StreamEntry(StreamSource.PipeSource(listOf("-i", "x")), "mp4", "audio/mp4", "unit"))
        assertEquals(501, RawHttp.request(ms.resolvedPort(), "GET", path).status)
        assertEquals("format mp4 is not a pipe format", awaitReport().note)
    }

    @Test
    @DisplayName("pipe: a video token on a server with a launcher but no resolver answers 501 'no resolver for video tokens'")
    fun aVideoPipeWithoutAResolverIs501() {
        val ms = serveVideoPipe(ScriptedLauncher { error("no") }, null)
        assertEquals(501, RawHttp.request(ms.resolvedPort(), "GET", registerAdts(ms)).status)
        assertEquals("no resolver for video tokens", awaitReport().note)
    }
}
