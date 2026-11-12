package dev.t2u.media

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of the remux.test.js titles that belong to the ADTS/MP3 pipe path, against real /usr/bin/ffmpeg and
 * ffprobe: the upstream is a local HTTP server that serves a 60 s fragmented M4A (what YouTube serves) and
 * answers paths containing "forbidden" with 403.
 */
class MediaServerLadderTest {
    @TempDir lateinit var dir: File

    private val ffmpeg = "/usr/bin/ffmpeg"
    private val ffprobe = "/usr/bin/ffprobe"
    private lateinit var upstream: HttpServer
    private lateinit var base: String
    private lateinit var launcher: CountingLauncher
    private var server: MediaServer? = null
    private val seen = CopyOnWriteArrayList<MediaRequest>()

    /** Wraps the real launcher so a test can see how many processes the server started. */
    private class CountingLauncher(val inner: JvmPipeLauncher) : PipeLauncher {
        val starts = AtomicInteger()

        override fun start(args: List<String>): Process {
            starts.incrementAndGet()
            return inner.start(args)
        }

        override fun stderrTail(process: Process): String = inner.stderrTail(process)
    }

    /** Plays the part of [CachingResolver]: the first URL is refused by the upstream, later ones are not. */
    private class TestResolver(private val urls: (call: Int) -> String, private val delayMs: Long = 0) : VideoResolver {
        val resolves = AtomicInteger()
        val invalidations = AtomicInteger()

        override suspend fun resolve(videoId: String): ResolvedInfo {
            val n = resolves.getAndIncrement()
            if (delayMs > 0) delay(delayMs)
            return ResolvedInfo(urls(n), "m4a", "audio/mp4", emptyMap(), "t", "a", "", "", 60.0, 0, "140")
        }

        override fun invalidate(videoId: String) {
            invalidations.incrementAndGet()
        }
    }

    @BeforeEach
    fun setUp() {
        assumeTrue(File(ffmpeg).canExecute() && File(ffprobe).canExecute(), "needs $ffmpeg and $ffprobe")
        val src = File(dir, "src.m4a")
        val p = ProcessBuilder(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=60",
            "-c:a", "aac", "-b:a", "128k", "-f", "mp4", "-movflags", "frag_keyframe+empty_moov", src.absolutePath,
        ).redirectErrorStream(true).start()
        val log = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor(), log)
        val file = src.readBytes()

        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { ex ->
            ex.use {
                if (ex.requestURI.path.contains("forbidden")) {
                    ex.sendResponseHeaders(403, -1)
                    return@use
                }
                val slow = ex.requestURI.path.contains("slow")
                val m = Regex("bytes=(\\d+)-(\\d*)").find(ex.requestHeaders.getFirst("Range").orEmpty())
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                if (m != null) {
                    val a = m.groupValues[1].toInt()
                    val b = m.groupValues[2].toIntOrNull() ?: (file.size - 1)
                    ex.responseHeaders.add("Content-Range", "bytes $a-$b/${file.size}")
                    ex.sendResponseHeaders(206, (b - a + 1).toLong())
                    writeBody(ex.responseBody, file, a, b - a + 1, slow)
                } else {
                    ex.sendResponseHeaders(200, file.size.toLong())
                    writeBody(ex.responseBody, file, 0, file.size, slow)
                }
            }
        }
        // ffmpeg keeps one connection open while it seeks on another: the default single-threaded executor would stall it
        upstream.executor = Executors.newCachedThreadPool { r -> Thread(r, "upstream").also { it.isDaemon = true } }
        upstream.start()
        base = "http://127.0.0.1:${upstream.address.port}"
        launcher = CountingLauncher(JvmPipeLauncher(ffmpeg))
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

    @AfterEach
    fun tearDown() {
        server?.stop()
        if (::upstream.isInitialized) upstream.stop(0)
    }

    private fun serve(
        resolver: VideoResolver?,
        host: String = "127.0.0.1",
        pipeRanges: PipeRangeConfig = PipeRangeConfig(enabled = true),
    ): MediaServer =
        MediaServer(host, 0, launcher, { seen.add(it) }, File(dir, "buffer"), resolver, pipeRanges, ffmpegUrlPolicy = FfmpegUrlPolicy.ANY_HTTP).also {
            it.start()
            server = it
        }

    private fun okUrls(): (Int) -> String = { "$base/s.m4a" }

    private fun forbiddenUrls(): (Int) -> String = { "$base/forbidden.m4a" }

    private fun slowUrls(): (Int) -> String = { "$base/slow.m4a" }

    private val cacheDir get() = File(dir, "buffer/range-cache")

    private fun cacheFiles(): List<String> = cacheDir.listFiles()?.map { it.name }?.sorted().orEmpty()

    private fun awaitCacheFiles(expected: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && cacheFiles().size != expected) Thread.sleep(25)
        assertEquals(expected, cacheFiles().size, "cache files: ${cacheFiles()}")
    }

    private fun get(ms: MediaServer, path: String, headers: Map<String, String> = emptyMap()) =
        RawHttp.request(ms.resolvedPort(), "GET", path, headers)

    private fun awaitNoProcess(seconds: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (launcher.inner.liveCount() > 0 && System.nanoTime() < deadline) Thread.sleep(50)
    }

    private fun probe(file: File, entries: String): String {
        val p = ProcessBuilder(ffprobe, "-v", "error", "-show_entries", entries, "-of", "csv=p=0", file.absolutePath)
            .redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        p.waitFor()
        return text
    }

    private fun isMp3Start(b: ByteArray): Boolean {
        if (b.size >= 3 && String(b, 0, 3, Charsets.ISO_8859_1) == "ID3") return true
        return b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0
    }

    @Test
    @DisplayName("adts/mp3: a refused YouTube URL is retried with a fresh extraction")
    fun aRefusedYouTubeUrlIsRetriedWithAFreshExtraction() {
        val resolver = TestResolver({ n -> "$base/${if (n == 0) "forbidden" else "s"}.m4a" })
        val ms = serve(resolver)
        val path = ms.registerVideo(VideoEntry("iiiiiiiiiii", "mp3"))
        val r = RawHttp.request(ms.resolvedPort(), "GET", path)
        assertEquals(200, r.status, "reported: $seen")
        assertEquals("audio/mpeg", r.header("Content-Type"))
        assertTrue(isMp3Start(r.body), "first bytes ${r.body.take(4)}")
        val saved = File(dir, "out.mp3").also { it.writeBytes(r.body) }
        assertEquals("mp3", probe(saved, "stream=codec_name"))
        assertEquals(2, resolver.resolves.get())
        assertEquals(1, resolver.invalidations.get(), "the second attempt must drop the cached entry first")
    }

    @Test
    @DisplayName("mp3 transcode")
    fun mp3Transcode() {
        val ms = serve(TestResolver(okUrls()))
        val r = get(ms, ms.registerVideo(VideoEntry("ddddddddddd", "mp3")))
        assertEquals(200, r.status)
        assertEquals("audio/mpeg", r.header("Content-Type"))
        val saved = File(dir, "out.mp3").also { it.writeBytes(r.body) }
        assertEquals("mp3", probe(saved, "stream=codec_name"))
    }

    @Test
    @DisplayName("adts: lossless AAC stream, starts at offset")
    fun adtsLosslessAacStreamStartsAtOffset() {
        val ms = serve(TestResolver(okUrls()))
        val r = get(ms, ms.registerVideo(VideoEntry("ccccccccccc", "adts", startAt = 30)))
        assertEquals(200, r.status)
        assertEquals("audio/aac", r.header("Content-Type"))
        assertTrue(r.header("contentFeatures.dlna.org")!!.contains("AAC_ADTS"), r.headerLines.toString())
        val saved = File(dir, "out.aac").also { it.writeBytes(r.body) }
        assertEquals("aac", probe(saved, "stream=codec_name"))
        assertEquals("aac", probe(saved, "format=format_name"))
        // ADTS has no index; ffprobe estimates the duration from the bitrate, so allow slack (remux.test.js)
        val duration = probe(saved, "format=duration").toDouble()
        assertTrue(kotlin.math.abs(duration - 30) < 3, "duration $duration")
    }

    @Test
    @DisplayName("adts/mp3: a stream that cannot be read at all is a 502, not an empty 200")
    fun aStreamThatCannotBeReadAtAllIsA502NotAnEmpty200() {
        // adapted: the yt-dlp download rung is Phase 3, so the two failed URL attempts end the ladder
        val resolver = TestResolver(forbiddenUrls())
        val ms = serve(resolver)
        val path = ms.registerVideo(VideoEntry("kkkkkkkkkkk", "mp3"))
        val r = get(ms, path)
        assertEquals(502, r.status)
        assertNull(r.header("Content-Type")?.takeIf { it.startsWith("audio/") })
        assertEquals(2, resolver.resolves.get())
        assertEquals(0, launcher.inner.liveCount())
        val rep = awaitReport { it.uri == path }
        assertEquals(502, rep.status)
        val note = rep.note.orEmpty()
        assertTrue(note.contains("YouTube URL") && note.contains("a fresh extraction"), "note: $note")
        assertTrue(note.contains("403"), "note: $note")
        assertTrue(!note.contains("forbidden.m4a"), "the URL path must not reach the note: $note")
    }

    @Test
    @DisplayName("a 502 on a video stream runs the entry's onFailure once; a healthy stream does not")
    fun aFailedStreamRunsOnFailure() {
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val ms = serve(TestResolver(forbiddenUrls()))
        val path = ms.registerVideo(VideoEntry("qqqqqqqqqqq", "mp3", onFailure = { failures.incrementAndGet() }))
        assertEquals(502, get(ms, path).status)
        awaitReport { it.uri == path }
        assertEquals(1, failures.get())
    }

    @Test
    @DisplayName("a renderer that hangs up while the stream is being prepared leaves nothing running")
    fun aRendererThatHangsUpWhileTheStreamIsBeingPreparedLeavesNothingRunning() {
        val resolver = TestResolver(okUrls(), delayMs = 400) // slow yt-dlp lookup
        val ms = serve(resolver)
        val path = ms.registerVideo(VideoEntry("lllllllllll", "mp3"))
        Socket("127.0.0.1", ms.resolvedPort()).use { s ->
            s.getOutputStream().apply {
                write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            Thread.sleep(100)
        } // hung up after 100 ms, before the 400 ms lookup is done
        // Ktor CIO 3.6.0 gives the handler no signal for a peer that closes before the first response byte (the
        // call's coroutine stays active), so ffmpeg may start once after the lookup; its first write to the dead
        // socket fails and kills it. What must hold is Node's assertion: nothing is left running.
        awaitNoProcess(2)
        Thread.sleep(500)
        assertEquals(0, launcher.inner.liveCount(), "no ffmpeg left behind")
        assertTrue(launcher.starts.get() <= 1, "at most the one start that follows the lookup, was ${launcher.starts.get()}")
    }

    @Test
    @DisplayName("range parsing")
    fun rangeParsing() {
        assertEquals(ByteRange.Span(0, 9), parseRange("bytes=0-", 10))
        assertEquals(ByteRange.Span(7, 9), parseRange("bytes=-3", 10))
        assertEquals(ByteRange.Span(5, 9), parseRange("bytes=5-100", 10))
        assertEquals(ByteRange.Unsatisfiable, parseRange("bytes=20-", 10))
        assertNull(parseRange("items=1-2", 10))
    }

    @Test
    fun aMissingOrUnknownTokenAndAWrongMethodAre404AndStartNothing() {
        val resolver = TestResolver(okUrls())
        val ms = serve(resolver)
        val path = ms.registerVideo(VideoEntry("mmmmmmmmmmm", "adts"))
        assertEquals(404, get(ms, "/stream/").status)
        assertEquals(404, get(ms, "/stream/${"0".repeat(32)}.aac").status)
        assertEquals(404, get(ms, "/stream/${"0".repeat(32)}").status)
        assertEquals(404, RawHttp.request(ms.resolvedPort(), "POST", path).status)
        assertEquals(404, RawHttp.request(ms.resolvedPort(), "DELETE", path).status)
        assertEquals(0, launcher.starts.get())
        assertEquals(0, resolver.resolves.get())
    }

    @Test
    fun theOldestTokenIsEvictedAfter256Registrations() {
        val ms = serve(TestResolver(okUrls()))
        val paths = (0..256).map { ms.registerVideo(VideoEntry("vid%08d".format(it), "adts")) }
        assertEquals(256, ms.tokenCount())
        assertEquals(404, RawHttp.request(ms.resolvedPort(), "HEAD", paths.first()).status)
        assertEquals(200, RawHttp.request(ms.resolvedPort(), "HEAD", paths[1]).status)
        assertEquals(200, RawHttp.request(ms.resolvedPort(), "HEAD", paths.last()).status)
    }

    @Test
    fun twoParallelGetsOnOneVideoTokenEachRunTheirOwnFfmpegAndLeaveNothingRunning() {
        val resolver = TestResolver(okUrls())
        val ms = serve(resolver)
        val path = ms.registerVideo(VideoEntry("nnnnnnnnnnn", "mp3"))
        val results = arrayOfNulls<RawResponse>(2)
        val threads = (0..1).map { i -> Thread { results[i] = get(ms, path) }.also { it.start() } }
        threads.forEach { it.join(60_000) }
        for (r in results) {
            assertNotNull(r)
            assertEquals(200, r.status)
            assertTrue(r.body.size > 1000, "body ${r.body.size}")
        }
        assertEquals(2, launcher.starts.get(), "each GET runs its own ffmpeg")
        awaitNoProcess(3)
        assertEquals(0, launcher.inner.liveCount(), "ffmpeg survived both clients")
    }

    @Test
    fun headOnAVideoTokenHasTheDlnaHeadersAndNeitherResolvesNorStartsFfmpeg() {
        val resolver = TestResolver(okUrls())
        val ms = serve(resolver)
        val path = ms.registerVideo(VideoEntry("ooooooooooo", "adts"))
        val r = RawHttp.request(ms.resolvedPort(), "HEAD", path)
        assertEquals(200, r.status)
        assertEquals("audio/aac", r.header("Content-Type"))
        assertEquals("Streaming", r.header("transferMode.dlna.org"))
        assertEquals(dlnaFeatures("adts"), r.header("contentFeatures.dlna.org"))
        assertEquals(0, r.body.size)
        assertNull(r.header("Content-Length"), r.headerLines.toString())
        assertEquals(0, resolver.resolves.get())
        assertEquals(0, launcher.starts.get())
    }

    @Test
    fun getCallsTheEntryOnRequestOncePerGetAndHeadNever() {
        val ms = serve(TestResolver(okUrls()))
        val calls = AtomicInteger()
        val path = ms.registerVideo(VideoEntry("ppppppppppp", "mp3", onRequest = { calls.incrementAndGet() }))
        RawHttp.request(ms.resolvedPort(), "HEAD", path)
        assertEquals(0, calls.get())
        assertEquals(200, get(ms, path).status)
        assertEquals(1, calls.get())
        assertEquals(200, get(ms, path).status)
        assertEquals(2, calls.get())
    }

    @Test
    fun aServerBoundToOneAddressRefusesTheHostsOtherAddresses() {
        val other = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
        org.junit.jupiter.api.Assumptions.assumeTrue(other != null, "the host has no non-loopback IPv4 address")
        val ms = serve(TestResolver(okUrls()), host = "127.0.0.1")
        // sanity: the bound address answers
        Socket("127.0.0.1", ms.resolvedPort()).use { }
        assertFailsWith<ConnectException> {
            Socket().use { it.connect(InetSocketAddress(other!!, ms.resolvedPort()), 3000) }
        }
    }

    @Test
    fun registerVideoRefusesABadIdAndAFormatThatIsNotAPipe() {
        val ms = serve(TestResolver(okUrls()))
        assertFailsWith<IllegalArgumentException> { ms.registerVideo(VideoEntry("abc; rm -rf /", "adts")) }
        assertFailsWith<IllegalArgumentException> { ms.registerVideo(VideoEntry("abcdefghij", "adts")) }
        assertFailsWith<IllegalArgumentException> { ms.registerVideo(VideoEntry("abcdefghijk", "mp4")) }
        assertFailsWith<IllegalArgumentException> { ms.registerVideo(VideoEntry("abcdefghijk", "direct")) }
        assertFailsWith<IllegalArgumentException> { ms.registerVideo(VideoEntry("abcdefghijk", "adts", startAt = -1)) }
        assertEquals(0, ms.tokenCount())
    }

    @Test
    fun aVideoTokenWithoutAResolverIs501AndStartsNothing() {
        val ms = serve(null)
        val r = get(ms, ms.registerVideo(VideoEntry("qqqqqqqqqqq", "adts")))
        assertEquals(501, r.status)
        assertEquals(0, launcher.starts.get())
    }

    @Test
    fun urlInputArgsMatchTheNodeArgumentList() {
        val info = ResolvedInfo(
            "https://rr1---sn-x.googlevideo.com/v?x=1", "m4a", "audio/mp4",
            linkedMapOf("User-Agent" to "ua", "Accept" to "*/*"), "t", "a", "", "", 1.0, 0, null,
        )
        assertEquals(
            listOf(
                "-hide_banner", "-loglevel", "error", "-protocol_whitelist", "https,tls,tcp,crypto",
                "-headers", "User-Agent: ua\r\nAccept: */*\r\n",
                "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
                "-ss", "30", "-i", "https://rr1---sn-x.googlevideo.com/v?x=1", "-vn",
            ),
            urlInputArgs(info, 30),
        )
        val bare = urlInputArgs(info.copy(headers = emptyMap()), 0)
        assertEquals(
            listOf(
                "-hide_banner", "-loglevel", "error", "-protocol_whitelist", "https,tls,tcp,crypto",
                "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
                "-i", "https://rr1---sn-x.googlevideo.com/v?x=1", "-vn",
            ),
            bare,
        )
    }

    // ------------------------------------------------------------------------ plan 02-16 (G4): Range on live pipes

    /** One plain GET that runs to its clean end; returns the path, the body and leaves the cache warm. */
    private fun warmUp(ms: MediaServer, id: String = "rrrrrrrrrrr", format: String = "adts", startAt: Int = 10): Pair<String, ByteArray> {
        val path = ms.registerVideo(VideoEntry(id, format, startAt = startAt))
        val first = get(ms, path)
        assertEquals(200, first.status)
        assertTrue(first.body.size > 100_000, "body ${first.body.size}")
        awaitReport { it.uri == path && it.status == 200 && it.pipeMode == "chunked" }
        return path to first.body
    }

    @Test
    @DisplayName("a Range request after a complete live GET is answered 206 from the bytes the speaker already received")
    fun aRangeRequestAfterACompleteLiveGetIsAnswered206FromTheBytesTheSpeakerAlreadyReceived() {
        val resolver = TestResolver(okUrls())
        val ms = serve(resolver)
        val path = ms.registerVideo(VideoEntry("rrrrrrrrrrr", "adts", startAt = 10))
        val first = get(ms, path)
        assertEquals(200, first.status)
        assertEquals("chunked", first.header("Transfer-Encoding")?.lowercase())
        assertNull(first.header("Content-Length"), first.headerLines.toString())
        assertNull(first.header("Content-Range"))
        assertNull(first.header("Accept-Ranges"))
        val total = first.body.size
        assertTrue(total > 100_000, "body $total")
        awaitReport { it.uri == path && it.status == 200 }

        val r = get(ms, path, mapOf("Range" to "bytes=7227-"))
        assertEquals(206, r.status, "reported: $seen")
        assertEquals("bytes 7227-${total - 1}/$total", r.header("Content-Range"))
        assertEquals((total - 7227).toString(), r.header("Content-Length"))
        assertTrue(first.body.copyOfRange(7227, total).contentEquals(r.body), "the tail must equal what the first GET sent")
        assertEquals("bytes", r.header("Accept-Ranges"))
        assertEquals("audio/aac", r.header("Content-Type"))
        assertEquals(dlnaFeatures("adts"), r.header("contentFeatures.dlna.org"))
        assertEquals("Streaming", r.header("transferMode.dlna.org"))
        assertEquals("no-store", r.header("Cache-Control"))
        assertEquals(1, launcher.starts.get(), "a cached answer starts no ffmpeg")
        assertEquals(1, resolver.resolves.get(), "a cached answer resolves nothing")
        val rep = awaitReport { it.uri == path && it.status == 206 }
        assertEquals("cached-range", rep.pipeMode)
        assertEquals((total - 7227).toLong(), rep.bytes)
    }

    @Test
    @DisplayName("a plain GET after the cache holds the bytes is still a live chunked stream")
    fun aPlainGetAfterTheCacheHoldsTheBytesIsStillALiveChunkedStream() {
        val ms = serve(TestResolver(okUrls()))
        val (path, _) = warmUp(ms)
        val again = get(ms, path)
        assertEquals(200, again.status)
        assertEquals("chunked", again.header("Transfer-Encoding")?.lowercase())
        assertNull(again.header("Content-Length"), again.headerLines.toString())
        assertNull(again.header("Accept-Ranges"))
        assertEquals(2, launcher.starts.get())
    }

    @Test
    @DisplayName("a bounded range and a suffix range are answered from the cache")
    fun aBoundedRangeAndASuffixRangeAreAnsweredFromTheCache() {
        val ms = serve(TestResolver(okUrls()))
        val (path, body) = warmUp(ms)
        val total = body.size
        val mid = get(ms, path, mapOf("Range" to "bytes=1000-1999"))
        assertEquals(206, mid.status)
        assertEquals("bytes 1000-1999/$total", mid.header("Content-Range"))
        assertEquals("1000", mid.header("Content-Length"))
        assertTrue(body.copyOfRange(1000, 2000).contentEquals(mid.body))
        val tail = get(ms, path, mapOf("Range" to "bytes=-500"))
        assertEquals(206, tail.status)
        assertEquals("bytes ${total - 500}-${total - 1}/$total", tail.header("Content-Range"))
        assertTrue(body.copyOfRange(total - 500, total).contentEquals(tail.body))
        assertEquals(1, launcher.starts.get())
    }

    @Test
    @DisplayName("two live runs of the same video token produce byte-identical ADTS")
    fun twoLiveRunsOfTheSameVideoTokenProduceByteIdenticalAdts() {
        // Characterization: the cold fill of the range cache relies on the run being deterministic. This measures the
        // ffmpeg on this machine only; the phone's ffmpeg 7.1.1 is not measured (retest 02-16 compares the totals).
        val ms = serve(TestResolver(okUrls()))
        val path = ms.registerVideo(VideoEntry("sssssssssss", "adts", startAt = 30))
        val a = get(ms, path)
        val b = get(ms, path)
        assertEquals(200, a.status)
        assertTrue(a.body.size > 100_000)
        assertTrue(a.body.contentEquals(b.body), "run 1 ${a.body.size} bytes, run 2 ${b.body.size} bytes")
    }


    // ------------------------------------------------------------------------ plan 02-16 Task 2: bounds and cleanup

    @Test
    @DisplayName("a resume Range on a token with no complete run is answered by one bounded ffmpeg run and then 206")
    fun aResumeRangeOnATokenWithNoCompleteRunIsAnsweredByOneBoundedFfmpegRunAndThen206() {
        val resolver = TestResolver(okUrls())
        val ms = serve(resolver)
        val path = ms.registerVideo(VideoEntry("ttttttttttt", "adts", startAt = 10))
        val r = get(ms, path, mapOf("Range" to "bytes=5000-"))
        assertEquals(206, r.status, "reported: $seen")
        val total = 5000 + r.body.size
        assertEquals("bytes 5000-${total - 1}/$total", r.header("Content-Range"))
        assertEquals(r.body.size.toString(), r.header("Content-Length"))
        assertEquals("bytes", r.header("Accept-Ranges"))
        assertEquals(dlnaFeatures("adts"), r.header("contentFeatures.dlna.org"))
        assertEquals(1, launcher.starts.get(), "the fill is the only start for the Range request")
        assertEquals("range-fill", awaitReport { it.uri == path && it.status == 206 }.pipeMode)
        assertEquals(listOf(true), cacheFiles().map { it.endsWith(".bin") }, "one finished run and no part file: ${cacheFiles()}")

        // GUARD: only the cold fill depends on the ffmpeg build writing a FILE with the same bytes as the pipe it
        // would have written. Measured here with ffmpeg 4.4.2 (identical for adts); the phone's 7.1.1 is not measured,
        // so the device retest compares the cold-fill total with a plain GET (see the 02-16 SUMMARY).
        val live = get(ms, path)
        assertEquals(200, live.status)
        assertEquals(2, launcher.starts.get(), "a plain GET is a live run of its own")
        assertEquals(total, live.body.size, "the filled total equals the live total")
        assertTrue(live.body.copyOfRange(5000, total).contentEquals(r.body), "the filled tail equals the live bytes")

        val again = get(ms, path, mapOf("Range" to "bytes=5000-"))
        assertEquals(206, again.status)
        assertTrue(again.body.contentEquals(r.body))
        assertEquals(2, launcher.starts.get(), "a second Range request starts nothing")
    }

    @Test
    @DisplayName("a Range starting at 0 on a token with no complete run stays a live chunked stream")
    fun aRangeStartingAt0OnATokenWithNoCompleteRunStaysALiveChunkedStream() {
        val ms = serve(TestResolver(okUrls()))
        val path = ms.registerVideo(VideoEntry("uuuuuuuuuuu", "adts", startAt = 10))
        val r = get(ms, path, mapOf("Range" to "bytes=0-"))
        assertEquals(200, r.status)
        assertEquals("chunked", r.header("Transfer-Encoding")?.lowercase())
        assertNull(r.header("Content-Range"))
        assertNull(r.header("Accept-Ranges"))
        assertEquals(1, launcher.starts.get())
    }

    @Test
    @DisplayName("a Range past the end of a cached stream is 416 and starts nothing")
    fun aRangePastTheEndOfACachedStreamIs416AndStartsNothing() {
        val resolver = TestResolver(okUrls())
        val ms = serve(resolver)
        val (path, body) = warmUp(ms)
        for (header in listOf("bytes=${body.size}-", "bytes=99999999999999999999999-", "bytes=${body.size + 5}-${body.size + 9}")) {
            val r = get(ms, path, mapOf("Range" to header))
            assertEquals(416, r.status, header)
            assertEquals("bytes */${body.size}", r.header("Content-Range"), header)
        }
        assertEquals(1, launcher.starts.get())
        assertEquals(1, resolver.resolves.get())
    }

    @Test
    @DisplayName("a malformed or multi-range header is ignored and gets the live 200")
    fun aMalformedOrMultiRangeHeaderIsIgnoredAndGetsTheLive200() {
        val ms = serve(TestResolver(okUrls()))
        val (path, body) = warmUp(ms)
        var starts = 1
        for (header in listOf("bytes=abc", "items=1-2", "bytes=", "bytes=0-1,5-9")) {
            val r = get(ms, path, mapOf("Range" to header))
            assertEquals(200, r.status, header)
            assertEquals("chunked", r.header("Transfer-Encoding")?.lowercase(), header)
            assertNull(r.header("Content-Range"), header)
            assertEquals(body.size, r.body.size, header)
            assertEquals(++starts, launcher.starts.get(), "$header is a plain live run")
        }
        // and on a token with no complete run it must not trigger a fill either
        val cold = ms.registerVideo(VideoEntry("vvvvvvvvvvv", "adts", startAt = 10))
        val before = launcher.starts.get()
        val r = get(ms, cold, mapOf("Range" to "bytes=abc"))
        assertEquals(200, r.status)
        assertEquals(before + 1, launcher.starts.get(), "one live run, no fill")
    }

    @Test
    @DisplayName("a track above the per-token cap streams live in full, leaves nothing on disk and a later Range gets the live 200")
    fun aTrackAboveThePerTokenCapStreamsLiveInFullLeavesNothingOnDiskAndALaterRangeGetsTheLive200() {
        val ms = serve(TestResolver(okUrls()), pipeRanges = PipeRangeConfig(enabled = true, maxBytesPerToken = 100_000, maxBytesTotal = 1_000_000))
        val path = ms.registerVideo(VideoEntry("wwwwwwwwwww", "adts", startAt = 30))
        val first = get(ms, path)
        assertEquals(200, first.status)
        assertTrue(first.body.size > 100_000, "the track passes the cap: ${first.body.size}")
        assertEquals(emptyList(), cacheFiles())
        assertEquals(PipeRangeStats(0, 0), ms.rangeCacheStats())
        val r = get(ms, path, mapOf("Range" to "bytes=5000-"))
        assertEquals(200, r.status, "today's answer")
        assertEquals("chunked", r.header("Transfer-Encoding")?.lowercase())
        assertEquals(first.body.size, r.body.size)
        assertEquals(2, launcher.starts.get(), "the Range request ran live and attempted no fill")
        assertEquals(emptyList(), cacheFiles())
    }

    @Test
    @DisplayName("a client hang-up in the middle of a live run leaves no ffmpeg and no part file and caches nothing")
    fun aClientHangUpInTheMiddleOfALiveRunLeavesNoFfmpegAndNoPartFileAndCachesNothing() {
        val ms = serve(TestResolver(slowUrls()))
        val path = ms.registerVideo(VideoEntry("xxxxxxxxxxx", "adts"))
        Socket("127.0.0.1", ms.resolvedPort()).use { s ->
            s.soTimeout = 20_000
            s.getOutputStream().apply {
                write(RawHttp.requestBytes("GET", path, "HTTP/1.1", emptyMap()))
                flush()
            }
            val input = s.getInputStream()
            RawHttp.readHead(input)
            val buf = ByteArray(4096)
            var got = 0
            while (got < buf.size) {
                val n = input.read(buf, got, buf.size - got)
                check(n >= 0) { "stream ended early" }
                got += n
            }
            assertEquals(1, cacheFiles().size, "the part file exists while the run is live")
        } // hung up here, long before the 1.8 s throttled run would have ended
        awaitNoProcess(5)
        assertEquals(0, launcher.inner.liveCount(), "ffmpeg survived the hang-up")
        awaitCacheFiles(0)
        assertEquals(PipeRangeStats(0, 0), ms.rangeCacheStats())
    }

    @Test
    @DisplayName("a cold fill that passes the time budget answers the live 200")
    fun aColdFillThatPassesTheTimeBudgetAnswersTheLive200() {
        val ms = serve(TestResolver(slowUrls()), pipeRanges = PipeRangeConfig(enabled = true, coldFillTimeoutMs = 300))
        val path = ms.registerVideo(VideoEntry("yyyyyyyyyyy", "adts"))
        val r = get(ms, path, mapOf("Range" to "bytes=5000-"))
        assertEquals(200, r.status, "reported: $seen")
        assertEquals("chunked", r.header("Transfer-Encoding")?.lowercase())
        assertNull(r.header("Content-Range"))
        assertEquals(2, launcher.starts.get(), "the killed fill and the live run")
        awaitNoProcess(3)
        assertEquals(0, launcher.inner.liveCount())
        assertTrue(cacheFiles().none { it.endsWith(".part") }, "no part file left: ${cacheFiles()}")
    }

    @Test
    @DisplayName("evicting a token deletes its cache file")
    fun evictingATokenDeletesItsCacheFile() {
        val ms = serve(TestResolver(okUrls()))
        warmUp(ms)
        assertEquals(1, cacheFiles().size)
        repeat(MediaServer.MAX_TOKENS) { ms.registerVideo(VideoEntry("vid%08d".format(it), "adts")) }
        assertEquals(emptyList(), cacheFiles())
        assertEquals(PipeRangeStats(0, 0), ms.rangeCacheStats())
    }

    @Test
    @DisplayName("stopping the server leaves the cache directory empty")
    fun stoppingTheServerLeavesTheCacheDirectoryEmpty() {
        val ms = serve(TestResolver(okUrls()))
        warmUp(ms)
        assertEquals(1, ms.rangeCacheStats().entries)
        ms.stop()
        assertEquals(emptyList(), cacheFiles())
    }

    @Test
    @DisplayName("starting the server removes files an earlier process left in the cache directory")
    fun startingTheServerRemovesFilesAnEarlierProcessLeftInTheCacheDirectory() {
        cacheDir.mkdirs()
        File(cacheDir, "run-9.part").writeBytes(ByteArray(10))
        File(cacheDir, "${"0".repeat(32)}.bin").writeBytes(ByteArray(10))
        serve(TestResolver(okUrls()))
        assertEquals(emptyList(), cacheFiles())
    }

    @Test
    @DisplayName("a cache directory that cannot be created does not break the live GET")
    fun aCacheDirectoryThatCannotBeCreatedDoesNotBreakTheLiveGet() {
        File(dir, "buffer").mkdirs()
        File(dir, "buffer/range-cache").writeText("a regular file where the directory should be")
        val ms = serve(TestResolver(okUrls()))
        val path = ms.registerVideo(VideoEntry("zzzzzzzzzzz", "adts", startAt = 10))
        val first = get(ms, path)
        assertEquals(200, first.status)
        assertTrue(first.body.size > 100_000)
        val r = get(ms, path, mapOf("Range" to "bytes=5000-"))
        assertEquals(200, r.status, "no cache, today's answer")
        assertEquals(first.body.size, r.body.size)
    }

    @Test
    @DisplayName("with range answering disabled or without a buffer directory a Range request gets today's live 200")
    fun withRangeAnsweringDisabledOrWithoutABufferDirectoryARangeRequestGetsTodaysLive200() {
        val off = serve(TestResolver(okUrls()), pipeRanges = PipeRangeConfig(enabled = false))
        val (offPath, body) = warmUp(off)
        val a = get(off, offPath, mapOf("Range" to "bytes=5000-"))
        assertEquals(200, a.status)
        assertEquals(body.size, a.body.size)
        assertEquals(emptyList(), cacheFiles())
        assertEquals(PipeRangeStats(0, 0), off.rangeCacheStats())
        off.stop()

        val bare = MediaServer("127.0.0.1", 0, launcher, { seen.add(it) }, null, TestResolver(okUrls()), PipeRangeConfig(enabled = true), ffmpegUrlPolicy = FfmpegUrlPolicy.ANY_HTTP)
        bare.start()
        server = bare
        val bPath = bare.registerVideo(VideoEntry("bbbbbbbbbbb", "adts", startAt = 10))
        assertEquals(200, get(bare, bPath).status)
        val b = get(bare, bPath, mapOf("Range" to "bytes=5000-"))
        assertEquals(200, b.status)
        assertEquals("chunked", b.header("Transfer-Encoding")?.lowercase())
    }

    @Test
    @DisplayName("HEAD with a Range header on a cached token is still a plain 200 without a length")
    fun headWithARangeHeaderOnACachedTokenIsStillAPlain200WithoutALength() {
        val ms = serve(TestResolver(okUrls()))
        val (path, _) = warmUp(ms)
        val r = RawHttp.request(ms.resolvedPort(), "HEAD", path, mapOf("Range" to "bytes=100-"))
        assertEquals(200, r.status)
        assertNull(r.header("Content-Length"), r.headerLines.toString())
        assertNull(r.header("Content-Range"))
        assertEquals(dlnaFeatures("adts"), r.header("contentFeatures.dlna.org"))
        assertEquals(1, launcher.starts.get())
    }

    @Test
    @DisplayName("an mp3 token is answered from the cache after a complete live GET and two live runs are byte-identical")
    fun anMp3TokenIsAnsweredFromTheCacheAfterACompleteLiveGetAndTwoLiveRunsAreByteIdentical() {
        val ms = serve(TestResolver(okUrls()))
        val (path, body) = warmUp(ms, id = "mmmmmmmmmm3", format = "mp3", startAt = 30)
        val r = get(ms, path, mapOf("Range" to "bytes=4000-"))
        assertEquals(206, r.status, "reported: $seen")
        assertEquals("bytes 4000-${body.size - 1}/${body.size}", r.header("Content-Range"))
        assertEquals("audio/mpeg", r.header("Content-Type"))
        assertTrue(body.copyOfRange(4000, body.size).contentEquals(r.body))
        assertEquals(1, launcher.starts.get())
        val live = get(ms, path)
        assertEquals(200, live.status)
        assertTrue(live.body.contentEquals(body), "two live mp3 runs: ${body.size} and ${live.body.size} bytes")
    }

    @Test
    @DisplayName("an mp3 token with no complete run is not filled because ffmpeg writes a file differently from a pipe")
    fun anMp3TokenWithNoCompleteRunIsNotFilledBecauseFfmpegWritesAFileDifferentlyFromAPipe() {
        // Measured with ffmpeg 4.4.2: the mp3 muxer's Xing/ID3 header differs between a seekable file and a pipe
        // (722,380 against 721,754 bytes for the same 30 s), so an offset the speaker holds from a pipe run would
        // not index a file run. The warm cache serves the very bytes that were sent, so only the cold fill is skipped.
        val ms = serve(TestResolver(okUrls()))
        val path = ms.registerVideo(VideoEntry("nnnnnnnnnn3", "mp3", startAt = 30))
        val r = get(ms, path, mapOf("Range" to "bytes=4000-"))
        assertEquals(200, r.status)
        assertEquals("chunked", r.header("Transfer-Encoding")?.lowercase())
        assertEquals(1, launcher.starts.get(), "one live run, no fill")
    }

    @Test
    @DisplayName("two parallel plain GETs on one token leave exactly one cache file and no part file")
    fun twoParallelPlainGetsOnOneTokenLeaveExactlyOneCacheFileAndNoPartFile() {
        val ms = serve(TestResolver(okUrls()))
        val path = ms.registerVideo(VideoEntry("ppppppppppq", "adts", startAt = 10))
        val results = arrayOfNulls<RawResponse>(2)
        val threads = (0..1).map { i -> Thread { results[i] = get(ms, path) }.also { it.start() } }
        threads.forEach { it.join(60_000) }
        results.forEach { assertEquals(200, it?.status) }
        awaitNoProcess(3)
        awaitCacheFiles(1)
        assertTrue(cacheFiles().single().endsWith(".bin"), cacheFiles().toString())
        assertEquals(1, ms.rangeCacheStats().entries)
    }

    @Test
    @DisplayName("a Range request that arrives before the first GET has ended is filled once and leaves nothing running")
    fun aRangeRequestThatArrivesBeforeTheFirstGetHasEndedIsFilledOnceAndLeavesNothingRunning() {
        // Expected race, not an error: no complete run exists yet, so the Range request gets its own bounded fill and
        // the device retest may legitimately show two launches for such a Pause. Both must end cleanly.
        val ms = serve(TestResolver(slowUrls()))
        val path = ms.registerVideo(VideoEntry("qqqqqqqqqq1", "adts"))
        var first: RawResponse? = null
        val t = Thread { first = get(ms, path) }.also { it.start() }
        Thread.sleep(400) // the throttled first run (about 1.8 s) is under way
        val r = get(ms, path, mapOf("Range" to "bytes=5000-"))
        t.join(60_000)
        assertEquals(206, r.status, "reported: $seen")
        val body = assertNotNull(first).body
        assertEquals(200, first.status)
        assertEquals("bytes 5000-${body.size - 1}/${body.size}", r.header("Content-Range"), "the true total, equal to what the first GET sent")
        assertTrue(body.copyOfRange(5000, body.size).contentEquals(r.body))
        assertEquals(2, launcher.starts.get(), "the live run and the fill")
        awaitNoProcess(3)
        assertEquals(0, launcher.inner.liveCount())
        awaitCacheFiles(1)
        assertTrue(cacheFiles().single().endsWith(".bin"), cacheFiles().toString())
    }

    @Test
    @DisplayName("a cached file that disappeared before the Range request falls through to the live path")
    fun aCachedFileThatDisappearedBeforeTheRangeRequestFallsThroughToTheLivePath() {
        val ms = serve(TestResolver(okUrls()))
        val (path, body) = warmUp(ms)
        cacheDir.listFiles()!!.forEach { assertTrue(it.delete()) } // as if evicted between the lookup and the answer
        val live = get(ms, path, mapOf("Range" to "bytes=0-"))
        assertEquals(200, live.status, "nothing to serve: the live path answers")
        assertEquals(body.size, live.body.size)
        assertEquals(2, launcher.starts.get())
        cacheDir.listFiles()!!.forEach { assertTrue(it.delete()) }
        val refill = get(ms, path, mapOf("Range" to "bytes=5000-"))
        assertEquals(206, refill.status, "a resume Range gets a fresh bounded fill")
        assertTrue(body.copyOfRange(5000, body.size).contentEquals(refill.body))
    }

    @Test
    @DisplayName("while the concurrent part-file limit is reached a Range request gets the live 200 and no fill")
    fun whileTheConcurrentPartFileLimitIsReachedARangeRequestGetsTheLive200AndNoFill() {
        val ms = serve(TestResolver(slowUrls()), pipeRanges = PipeRangeConfig(enabled = true, maxConcurrentParts = 1))
        val busy = ms.registerVideo(VideoEntry("rrrrrrrrrr1", "adts"))
        val other = ms.registerVideo(VideoEntry("rrrrrrrrrr2", "adts"))
        val t = Thread { get(ms, busy) }.also { it.start() } // its tee holds the only part-file slot
        Thread.sleep(400)
        val r = get(ms, other, mapOf("Range" to "bytes=5000-"))
        assertEquals(200, r.status, "no slot for a fill: today's live answer")
        assertEquals("chunked", r.header("Transfer-Encoding")?.lowercase())
        assertEquals(2, launcher.starts.get(), "two live runs, no fill")
        t.join(60_000)
        awaitNoProcess(3)
        assertTrue(cacheFiles().none { it.endsWith(".part") }, cacheFiles().toString())
    }

    private fun awaitReport(predicate: (MediaRequest) -> Boolean): MediaRequest {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            seen.firstOrNull(predicate)?.let { return it }
            Thread.sleep(25)
        }
        throw AssertionError("no matching request reported, saw $seen")
    }
}
