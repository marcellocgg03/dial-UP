package dev.t2u.media

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `direct` serving path: the phone relays YouTube's audio URL to the speaker (plan 03-02). */
class MediaServerDirectTest {
    private var server: MediaServer? = null
    private val seen = CopyOnWriteArrayList<MediaRequest>()

    /** Records every request and answers from [respond]. */
    private class ScriptedFetcher(private val respond: (call: Int, req: UpstreamRequest) -> UpstreamResponse) : UpstreamFetcher {
        val requests = CopyOnWriteArrayList<UpstreamRequest>()
        private val calls = AtomicInteger()

        override suspend fun fetch(request: UpstreamRequest): UpstreamResponse {
            requests.add(request)
            return respond(calls.getAndIncrement(), request)
        }
    }

    private class TestResolver(private val urls: (call: Int) -> String = { "https://rr1.googlevideo.com/videoplayback?n=$it&sig=SECRET" }) :
        VideoResolver {
        val resolves = AtomicInteger()
        val invalidations = AtomicInteger()

        override suspend fun resolve(videoId: String): ResolvedInfo {
            val n = resolves.getAndIncrement()
            return ResolvedInfo(
                urls(n), "m4a", "audio/mp4", mapOf("User-Agent" to "yt-dlp-ua", "X-Custom" to "c1"),
                "t", "a", "", "", 60.0, 0, "140",
            )
        }

        override fun invalidate(videoId: String) {
            invalidations.incrementAndGet()
        }
    }

    /** The server adds a request-log entry after it has sent the response, so a slow machine can still be missing it. */
    private fun lastSeen(): MediaRequest {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (seen.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        return seen.last()
    }

    @AfterEach
    fun tearDown() {
        server?.stop()
    }

    private fun serve(resolver: VideoResolver?, fetcher: UpstreamFetcher?): MediaServer =
        MediaServer("127.0.0.1", 0, null, { seen.add(it) }, null, resolver, PipeRangeConfig(), fetcher).also {
            it.start()
            server = it
        }

    private fun body(bytes: ByteArray, status: Int = 200, extra: Map<String, String> = emptyMap(), onClose: () -> Unit = {}) =
        UpstreamResponse(
            status,
            mapOf("content-length" to bytes.size.toString()) + extra,
            ByteArrayInputStream(bytes),
            onClose,
        )

    private fun payload(n: Int) = ByteArray(n) { (it % 251).toByte() }

    private fun register(ms: MediaServer, id: String = "dQw4w9WgXcQ") =
        ms.registerVideo(VideoEntry(id, "direct", 0, ext = "m4a", sourceMime = "audio/mp4"))

    @Test
    @DisplayName("direct: a GET on a registered token is relayed from the upstream with the DLNA headers")
    fun aGetIsRelayedWithDlnaHeaders() = runBlocking {
        val data = payload(1000)
        val fetcher = ScriptedFetcher { _, _ -> body(data) }
        val resolver = TestResolver()
        val ms = serve(resolver, fetcher)
        val path = register(ms)
        assertTrue(path.matches(Regex("/stream/[0-9a-f]{32}\\.m4a")), path)

        val r = RawHttp.request(ms.resolvedPort(), "GET", path)
        assertEquals(200, r.status, "reported: $seen")
        assertEquals("audio/mp4", r.header("Content-Type"))
        assertEquals("1000", r.header("Content-Length"))
        assertEquals(1, r.headerCount("Content-Length"))
        assertTrue(data.contentEquals(r.body))
        assertEquals("bytes", r.header("Accept-Ranges"))
        assertEquals("Streaming", r.header("transferMode.dlna.org"))
        val features = r.header("contentFeatures.dlna.org").orEmpty()
        assertTrue("DLNA.ORG_OP=01" in features, features)
        assertTrue("DLNA.ORG_CI=0" in features, features)
        assertEquals("proxy", lastSeen().pipeMode)
        assertEquals(1000L, lastSeen().bytes)
    }

    @Test
    @DisplayName("direct: the fetch request carries the resolved URL and method GET")
    fun theFetchUsesTheResolvedUrl() = runBlocking {
        val fetcher = ScriptedFetcher { _, _ -> body(payload(10)) }
        val resolver = TestResolver()
        val ms = serve(resolver, fetcher)
        RawHttp.request(ms.resolvedPort(), "GET", register(ms))
        val req = fetcher.requests.single()
        assertEquals("https://rr1.googlevideo.com/videoplayback?n=0&sig=SECRET", req.url)
        assertEquals("GET", req.method)
    }

    private fun upstreamRange(call: Int, req: UpstreamRequest, data: ByteArray): UpstreamResponse {
        val m = Regex("bytes=(\\d+)-(\\d+)").find(req.headers["Range"].orEmpty())
        if (m == null) return body(data)
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        return body(data.copyOfRange(a, b + 1), 206, mapOf("content-range" to "bytes $a-$b/${data.size}"))
    }

    @Test
    @DisplayName("direct: a simple byte range is forwarded and the 206 comes back with its Content-Range")
    fun aRangeIsForwarded() = runBlocking {
        val data = payload(1000)
        val fetcher = ScriptedFetcher { n, req -> upstreamRange(n, req, data) }
        val ms = serve(TestResolver(), fetcher)
        val r = RawHttp.request(ms.resolvedPort(), "GET", register(ms), mapOf("Range" to "bytes=100-199"))
        assertEquals(206, r.status, "reported: $seen")
        assertEquals("bytes 100-199/1000", r.header("Content-Range"))
        assertEquals("100", r.header("Content-Length"))
        assertTrue(data.copyOfRange(100, 200).contentEquals(r.body))
        assertEquals("bytes=100-199", fetcher.requests.single().headers["Range"])
        assertEquals("bytes=100-199", lastSeen().range)
    }

    @Test
    @DisplayName("direct: open-ended and suffix ranges are forwarded as they are")
    fun openEndedRangesAreForwarded() = runBlocking {
        val fetcher = ScriptedFetcher { _, _ -> body(payload(5)) }
        val ms = serve(TestResolver(), fetcher)
        val path = register(ms)
        for (h in listOf("bytes=500-", "bytes=-200")) RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to h))
        assertEquals(listOf("bytes=500-", "bytes=-200"), fetcher.requests.map { it.headers["Range"] })
    }

    @Test
    @DisplayName("direct: a multi-range, a malformed or an over-long Range is not forwarded")
    fun unsafeRangesAreDropped() = runBlocking {
        val fetcher = ScriptedFetcher { _, _ -> body(payload(5)) }
        val ms = serve(TestResolver(), fetcher)
        val path = register(ms)
        val bad = listOf("bytes=0-9,20-29", "bytes=abc", "bytes=" + "1".repeat(200), "bytes=-", "items=0-9")
        for (h in bad) RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to h))
        assertEquals(bad.size, fetcher.requests.size)
        for (req in fetcher.requests) assertTrue("Range" !in req.headers, "Range leaked: ${req.headers}")
    }

    @Test
    @DisplayName("direct: the upstream sees only the info headers, Accept-Encoding identity and a sane Range")
    fun theUpstreamSeesAnExactHeaderSet() = runBlocking {
        val fetcher = ScriptedFetcher { _, _ -> body(payload(5)) }
        val ms = serve(TestResolver(), fetcher)
        RawHttp.request(
            ms.resolvedPort(), "GET", register(ms),
            mapOf(
                "Cookie" to "SID=1", "Authorization" to "Bearer x", "Referer" to "http://evil/", "User-Agent" to "Sonos/1",
                "X-Forwarded-For" to "1.2.3.4", "Range" to "bytes=1-2",
            ),
        )
        val h = fetcher.requests.single().headers
        assertEquals(setOf("User-Agent", "X-Custom", "Accept-Encoding", "Range"), h.keys)
        assertEquals("yt-dlp-ua", h["User-Agent"])
        assertEquals("c1", h["X-Custom"])
        assertEquals("identity", h["Accept-Encoding"])
    }

    @Test
    @DisplayName("direct: a 403 re-resolves once and the fresh URL is fetched")
    fun a403ReResolvesOnce() = runBlocking {
        val data = payload(100)
        val fetcher = ScriptedFetcher { n, _ -> if (n == 0) body(ByteArray(0), 403) else body(data) }
        val resolver = TestResolver { n -> "https://rr1.googlevideo.com/videoplayback?gen=$n" }
        val ms = serve(resolver, fetcher)
        val r = RawHttp.request(ms.resolvedPort(), "GET", register(ms))
        assertEquals(200, r.status, "reported: $seen")
        assertTrue(data.contentEquals(r.body))
        assertEquals(1, resolver.invalidations.get())
        assertEquals(2, resolver.resolves.get())
        assertEquals(listOf("https://rr1.googlevideo.com/videoplayback?gen=0", "https://rr1.googlevideo.com/videoplayback?gen=1"), fetcher.requests.map { it.url })
    }

    @Test
    @DisplayName("direct: a 410 re-resolves once too")
    fun a410ReResolvesOnce() = runBlocking {
        val fetcher = ScriptedFetcher { n, _ -> if (n == 0) body(ByteArray(0), 410) else body(payload(50)) }
        val resolver = TestResolver()
        val ms = serve(resolver, fetcher)
        val r = RawHttp.request(ms.resolvedPort(), "GET", register(ms))
        assertEquals(200, r.status)
        assertEquals(1, resolver.invalidations.get())
        assertEquals(2, fetcher.requests.size)
    }

    @Test
    @DisplayName("direct: two 403 answers in a row pass the 403 through after one retry")
    fun aSecond403IsPassedThrough() = runBlocking {
        val fetcher = ScriptedFetcher { _, _ -> body(ByteArray(0), 403) }
        val resolver = TestResolver()
        val ms = serve(resolver, fetcher)
        val r = RawHttp.request(ms.resolvedPort(), "GET", register(ms))
        assertEquals(403, r.status)
        assertEquals(2, fetcher.requests.size, "no third fetch")
        assertEquals(1, resolver.invalidations.get())
    }

    @Test
    @DisplayName("direct: upstream 416 and 404 are passed through without a body")
    fun otherStatusesArePassedThrough() = runBlocking {
        val answers = listOf(
            body("nope".toByteArray(), 404),
            body(ByteArray(0), 416, mapOf("content-range" to "bytes */1000")),
        )
        val fetcher = ScriptedFetcher { n, _ -> answers[n] }
        val ms = serve(TestResolver(), fetcher)
        val path = register(ms)
        val notFound = RawHttp.request(ms.resolvedPort(), "GET", path)
        assertEquals(404, notFound.status)
        assertEquals(0, notFound.body.size)
        val unsatisfiable = RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to "bytes=5000-6000"))
        assertEquals(416, unsatisfiable.status)
        assertEquals("bytes */1000", unsatisfiable.header("Content-Range"))
        assertEquals(0, unsatisfiable.body.size)
    }

    @Test
    @DisplayName("direct: HEAD sends a HEAD fetch and answers with the upstream length and no body")
    fun headIsRelayedWithoutABody() = runBlocking {
        val fetcher = ScriptedFetcher { _, _ -> UpstreamResponse(200, mapOf("content-length" to "1000", "etag" to "\"e1\"", "last-modified" to "Tue, 01 Jan 2030 00:00:00 GMT"), null) }
        val ms = serve(TestResolver(), fetcher)
        val r = RawHttp.request(ms.resolvedPort(), "HEAD", register(ms))
        assertEquals(200, r.status)
        assertEquals("HEAD", fetcher.requests.single().method)
        assertEquals("1000", r.header("Content-Length"))
        assertEquals("\"e1\"", r.header("ETag"))
        assertEquals("Tue, 01 Jan 2030 00:00:00 GMT", r.header("Last-Modified"))
        assertEquals("audio/mp4", r.header("Content-Type"))
        assertEquals(0, r.body.size)
    }

    private class SlowStream : InputStream() {
        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            Thread.sleep(5)
            val n = minOf(len, 4096)
            java.util.Arrays.fill(b, off, off + n, 7)
            return n
        }
    }

    @Test
    @DisplayName("direct: the upstream response is closed when the speaker hangs up")
    fun theUpstreamIsClosedOnHangUp() = runBlocking {
        val closes = AtomicInteger()
        val fetcher = ScriptedFetcher { _, _ ->
            UpstreamResponse(200, mapOf("content-length" to "100000000"), SlowStream()) { closes.incrementAndGet() }
        }
        val ms = serve(TestResolver(), fetcher)
        val path = register(ms)
        Socket("127.0.0.1", ms.resolvedPort()).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().apply {
                write(RawHttp.requestBytes("GET", path, "HTTP/1.1", emptyMap()))
                flush()
            }
            val input = s.getInputStream().buffered()
            RawHttp.readHead(input)
            assertEquals(2000, input.readNBytes(2000).size)
        }
        val deadline = System.currentTimeMillis() + 2000
        while (closes.get() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals(1, closes.get(), "the upstream must be released after a hang-up")
    }

    @Test
    @DisplayName("direct: a resolver failure answers 502 and the note carries no signed URL")
    fun aResolverFailureIs502() = runBlocking {
        val resolver = object : VideoResolver {
            override suspend fun resolve(videoId: String): ResolvedInfo =
                throw ResolveException("yt-dlp failed for $videoId: https://rr1.googlevideo.com/videoplayback?sig=SECRET&expire=1")

            override fun invalidate(videoId: String) {}
        }
        val fetcher = ScriptedFetcher { _, _ -> error("must not fetch") }
        val ms = serve(resolver, fetcher)
        val r = RawHttp.request(ms.resolvedPort(), "GET", register(ms))
        assertEquals(502, r.status)
        val note = lastSeen().note.orEmpty()
        assertTrue("SECRET" !in note && "sig=" !in note && "?" !in note, note)
        assertEquals(0, fetcher.requests.size)
    }

    @Test
    @DisplayName("direct: a fetch failure answers 502 and the note carries no signed URL")
    fun aFetchFailureIs502() = runBlocking {
        val fetcher = object : UpstreamFetcher {
            override suspend fun fetch(request: UpstreamRequest): UpstreamResponse =
                throw IOException("failed to connect to https://rr1.googlevideo.com/videoplayback?sig=SECRET&expire=1")
        }
        val ms = serve(TestResolver(), fetcher)
        val r = RawHttp.request(ms.resolvedPort(), "GET", register(ms))
        assertEquals(502, r.status)
        val note = lastSeen().note.orEmpty()
        assertTrue("SECRET" !in note && "sig=" !in note && "?" !in note, note)
    }

    @Test
    @DisplayName("direct: an unknown token answers 404 and POST on a direct token answers 404")
    fun unknownTokensAndMethodsAre404() = runBlocking {
        val fetcher = ScriptedFetcher { _, _ -> body(payload(5)) }
        val ms = serve(TestResolver(), fetcher)
        val path = register(ms)
        assertEquals(404, RawHttp.request(ms.resolvedPort(), "GET", "/stream/${"0".repeat(32)}.m4a").status)
        assertEquals(404, RawHttp.request(ms.resolvedPort(), "POST", path).status)
        assertEquals(0, fetcher.requests.size)
    }

    @Test
    @DisplayName("direct: registerVideo needs the source extension and MIME and still refuses unknown formats")
    fun registerValidatesTheEntry() {
        val ms = MediaServer("127.0.0.1", 0, null, {})
        val id = "dQw4w9WgXcQ"
        assertTrue(ms.registerVideo(VideoEntry(id, "direct", 0, "webm", "audio/webm")).endsWith(".webm"))
        for (bad in listOf(VideoEntry(id, "direct"), VideoEntry(id, "direct", 0, "m4a", ""), VideoEntry(id, "direct", 0, "../x", "audio/mp4"), VideoEntry(id, "direct", 0, "m4a", "audio/mp4\r\nX: y"), VideoEntry(id, "bogus", 0, "m4a", "audio/mp4"))) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { ms.registerVideo(bad) }
        }
    }

    @Test
    @DisplayName("direct: without a resolver or fetcher the token answers 501")
    fun withoutAFetcherItIs501() = runBlocking {
        val ms = serve(TestResolver(), null)
        assertEquals(501, RawHttp.request(ms.resolvedPort(), "GET", register(ms)).status)
    }
}
