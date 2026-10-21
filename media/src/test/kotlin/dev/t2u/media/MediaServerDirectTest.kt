package dev.t2u.media

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
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
        assertEquals("proxy", seen.last().pipeMode)
        assertEquals(1000L, seen.last().bytes)
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
}
