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
                val m = Regex("bytes=(\\d+)-(\\d*)").find(ex.requestHeaders.getFirst("Range").orEmpty())
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                if (m != null) {
                    val a = m.groupValues[1].toInt()
                    val b = m.groupValues[2].toIntOrNull() ?: (file.size - 1)
                    ex.responseHeaders.add("Content-Range", "bytes $a-$b/${file.size}")
                    ex.sendResponseHeaders(206, (b - a + 1).toLong())
                    ex.responseBody.write(file, a, b - a + 1)
                } else {
                    ex.sendResponseHeaders(200, file.size.toLong())
                    ex.responseBody.write(file)
                }
            }
        }
        // ffmpeg keeps one connection open while it seeks on another: the default single-threaded executor would stall it
        upstream.executor = Executors.newCachedThreadPool { r -> Thread(r, "upstream").also { it.isDaemon = true } }
        upstream.start()
        base = "http://127.0.0.1:${upstream.address.port}"
        launcher = CountingLauncher(JvmPipeLauncher(ffmpeg))
    }

    @AfterEach
    fun tearDown() {
        server?.stop()
        if (::upstream.isInitialized) upstream.stop(0)
    }

    private fun serve(resolver: VideoResolver?, host: String = "127.0.0.1"): MediaServer =
        MediaServer(host, 0, launcher, { seen.add(it) }, File(dir, "buffer"), resolver).also {
            it.start()
            server = it
        }

    private fun okUrls(): (Int) -> String = { "$base/s.m4a" }

    private fun forbiddenUrls(): (Int) -> String = { "$base/forbidden.m4a" }

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
            "https://h.example/v?x=1", "m4a", "audio/mp4",
            linkedMapOf("User-Agent" to "ua", "Accept" to "*/*"), "t", "a", "", "", 1.0, 0, null,
        )
        assertEquals(
            listOf(
                "-hide_banner", "-loglevel", "error",
                "-headers", "User-Agent: ua\r\nAccept: */*\r\n",
                "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
                "-ss", "30", "-i", "https://h.example/v?x=1", "-vn",
            ),
            urlInputArgs(info, 30),
        )
        val bare = urlInputArgs(info.copy(headers = emptyMap()), 0)
        assertEquals(
            listOf(
                "-hide_banner", "-loglevel", "error",
                "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
                "-i", "https://h.example/v?x=1", "-vn",
            ),
            bare,
        )
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
