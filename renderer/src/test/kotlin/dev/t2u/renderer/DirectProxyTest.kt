package dev.t2u.renderer

import com.sun.net.httpserver.HttpServer
import dev.t2u.media.MediaServer
import dev.t2u.media.MediaRequest
import dev.t2u.media.PipeRangeConfig
import dev.t2u.media.ResolvedInfo
import dev.t2u.media.VideoEntry
import dev.t2u.media.VideoResolver
import dev.t2u.media.UpstreamRequest
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `direct` path end to end: a real [MediaServer] with the OkHttp fetcher in front of a JDK HttpServer that plays
 * the part of googlevideo (plan 03-02). Tests use a permissive host policy for loopback, except the policy test.
 */
class DirectProxyTest {
    private val payload = "0123456789abcdef".repeat(63).toByteArray().copyOf(1000).also { for (i in it.indices) it[i] = (i % 251).toByte() }
    private lateinit var upstream: HttpServer
    private lateinit var base: String
    private var server: MediaServer? = null
    private val upstreamHeaders = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private val seen = CopyOnWriteArrayList<MediaRequest>()

    private class TestResolver(private val urls: (Int) -> String) : VideoResolver {
        val resolves = AtomicInteger()
        val invalidations = AtomicInteger()

        override suspend fun resolve(videoId: String): ResolvedInfo = ResolvedInfo(
            urls(resolves.getAndIncrement()), "m4a", "audio/mp4", mapOf("User-Agent" to "yt-dlp-ua", "X-Custom" to "c1"),
            "t", "a", "", "", 60.0, 0, "140",
        )

        override fun invalidate(videoId: String) {
            invalidations.incrementAndGet()
        }
    }

    @BeforeEach
    fun setUp() {
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { ex ->
            ex.use {
                val path = ex.requestURI.path
                upstreamHeaders.add(path to ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() })
                when {
                    path == "/expired" -> ex.sendResponseHeaders(403, -1)
                    path == "/redir" || path == "/chain1" || path == "/toblocked" -> {
                        val to = when (path) {
                            "/redir" -> "/ok"
                            "/chain1" -> "/chain2"
                            else -> "/blocked"
                        }
                        ex.responseHeaders.add("Location", "$base$to")
                        ex.sendResponseHeaders(302, -1)
                    }
                    path == "/chain2" -> {
                        ex.responseHeaders.add("Location", "$base/ok")
                        ex.sendResponseHeaders(302, -1)
                    }
                    else -> {
                        val m = Regex("bytes=(\\d+)-(\\d+)").find(ex.requestHeaders.getFirst("Range").orEmpty())
                        ex.responseHeaders.add("Accept-Ranges", "bytes")
                        if (m != null) {
                            val a = m.groupValues[1].toInt()
                            val b = m.groupValues[2].toInt()
                            ex.responseHeaders.add("Content-Range", "bytes $a-$b/${payload.size}")
                            ex.sendResponseHeaders(206, (b - a + 1).toLong())
                            ex.responseBody.write(payload, a, b - a + 1)
                        } else {
                            ex.sendResponseHeaders(200, payload.size.toLong())
                            ex.responseBody.write(payload)
                        }
                    }
                }
            }
        }
        upstream.executor = Executors.newCachedThreadPool { r -> Thread(r, "upstream").also { it.isDaemon = true } }
        upstream.start()
        base = "http://127.0.0.1:${upstream.address.port}"
    }

    @AfterEach
    fun tearDown() {
        server?.stop()
        upstream.stop(0)
    }

    private fun serve(resolver: VideoResolver, policy: UpstreamPolicy = UpstreamPolicy { true }): MediaServer =
        MediaServer(
            "127.0.0.1", 0, null, { seen.add(it) }, null, resolver, PipeRangeConfig(),
            OkHttpUpstreamFetcher(lanHttpClient(), policy),
        ).also {
            it.start()
            server = it
        }

    private fun register(ms: MediaServer) = ms.registerVideo(VideoEntry("dQw4w9WgXcQ", "direct", 0, ext = "m4a", sourceMime = "audio/mp4"))

    private class Got(val status: Int, val body: ByteArray, val headers: Map<String, List<String>>) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    }

    private fun get(ms: MediaServer, path: String, range: String? = null, ua: String? = null): Got {
        val c = URI("http://127.0.0.1:${ms.resolvedPort()}$path").toURL().openConnection() as HttpURLConnection
        range?.let { c.setRequestProperty("Range", it) }
        ua?.let { c.setRequestProperty("User-Agent", it) }
        val status = c.responseCode
        val body = (if (status < 400) c.inputStream else c.errorStream)?.readBytes() ?: ByteArray(0)
        return Got(status, body, c.headerFields.filterKeys { it != null })
    }

    @Test
    @DisplayName("proxies with Range, DLNA headers, and re-resolves on 403")
    fun proxiesWithRangeDlnaHeadersAndReResolvesOn403() {
        val resolver = TestResolver { n -> if (n == 0) "$base/expired" else "$base/ok" }
        val ms = serve(resolver)
        val r = get(ms, register(ms), range = "bytes=10-19", ua = "Sonos/1.0")
        assertEquals(206, r.status, "reported: $seen")
        assertEquals("bytes 10-19/1000", r.header("Content-Range"))
        assertTrue(payload.copyOfRange(10, 20).contentEquals(r.body))
        assertEquals("audio/mp4", r.header("Content-Type"))
        assertEquals("Streaming", r.header("transferMode.dlna.org"))
        val features = r.header("contentFeatures.dlna.org").orEmpty()
        assertTrue("DLNA.ORG_OP=01" in features && "DLNA.ORG_CI=0" in features, features)
        assertEquals(2, resolver.resolves.get(), "the expired URL triggers one re-resolve")
        assertEquals(1, resolver.invalidations.get())
        assertEquals(listOf("/expired", "/ok"), upstreamHeaders.map { it.first })

        val bad = get(ms, "/stream/${"0".repeat(32)}.m4a")
        assertEquals(404, bad.status, "unknown tokens must not proxy anything")
        assertEquals(2, upstreamHeaders.size)
    }

    @Test
    @DisplayName("direct: the upstream sees Accept-Encoding identity and the info header, not the speaker's User-Agent")
    fun theUpstreamSeesTheInfoHeadersOnly() {
        val ms = serve(TestResolver { "$base/ok" })
        get(ms, register(ms), ua = "Sonos/1.0")
        val h = upstreamHeaders.single().second
        assertEquals("identity", h["accept-encoding"])
        assertEquals("c1", h["x-custom"])
        assertEquals("yt-dlp-ua", h["user-agent"])
        assertFalse(h.values.any { "Sonos" in it })
    }

    @Test
    @DisplayName("direct: one redirect hop is followed, a chain of two is refused with 502")
    fun oneRedirectHopIsFollowed() {
        val one = serve(TestResolver { "$base/redir" })
        val ok = get(one, register(one))
        assertEquals(200, ok.status, "reported: $seen")
        assertTrue(payload.contentEquals(ok.body))
        assertEquals(listOf("/redir", "/ok"), upstreamHeaders.map { it.first })
        one.stop()

        upstreamHeaders.clear()
        val two = serve(TestResolver { "$base/chain1" })
        val refused = get(two, register(two))
        assertEquals(502, refused.status)
        assertEquals(listOf("/chain1", "/chain2"), upstreamHeaders.map { it.first }, "the second hop is not fetched")
    }

    @Test
    @DisplayName("direct: a redirect whose target the policy refuses is not followed")
    fun aRefusedRedirectTargetIsNotFollowed() {
        val ms = serve(TestResolver { "$base/toblocked" }, UpstreamPolicy { it.encodedPath != "/blocked" })
        val r = get(ms, register(ms))
        assertEquals(502, r.status)
        assertEquals(listOf("/toblocked"), upstreamHeaders.map { it.first })
    }

    @Test
    @DisplayName("direct: the default policy admits https googlevideo.com hosts only")
    fun theDefaultPolicyAdmitsGooglevideoOnly() {
        val p = UpstreamPolicy.GOOGLEVIDEO
        assertFalse(p.allows("http://127.0.0.1/x".toHttpUrl()))
        assertFalse(p.allows("https://example.com/x".toHttpUrl()))
        assertFalse(p.allows("https://evil.googlevideo.com.example.org/x".toHttpUrl()))
        assertFalse(p.allows("https://notgooglevideo.com/x".toHttpUrl()))
        assertFalse(p.allows("http://rr3---sn-abc.googlevideo.com/videoplayback".toHttpUrl()))
        assertFalse(p.allows("https://user:pw@rr3---sn-abc.googlevideo.com/videoplayback".toHttpUrl()))
        assertFalse(p.allows("https://rr3---sn-abc.googlevideo.com:8443/videoplayback".toHttpUrl()))
        assertTrue(p.allows("https://rr3---sn-abc.googlevideo.com/videoplayback".toHttpUrl()))
        assertTrue(p.allows("https://googlevideo.com/x".toHttpUrl()))
    }

    @Test
    @DisplayName("direct: with the default policy a loopback URL is refused and the note names no signed URL")
    fun theDefaultPolicyRefusesLoopbackThroughTheServer() {
        val ms = serve(TestResolver { "$base/ok?sig=SECRET" }, UpstreamPolicy.GOOGLEVIDEO)
        val r = get(ms, register(ms))
        assertEquals(502, r.status)
        assertTrue(upstreamHeaders.isEmpty())
        val note = seen.last().note.orEmpty()
        assertTrue("SECRET" !in note && "sig=" !in note, note)
    }

    @Test
    @DisplayName("direct: the client is derived from lanHttpClient, so cleartext to a public address is refused even if the policy allows it")
    fun theLanGuardIsInherited() {
        val fetcher = OkHttpUpstreamFetcher(lanHttpClient(), UpstreamPolicy { true })
        val e = assertFailsWith<IOException> {
            runBlocking { fetcher.fetch(UpstreamRequest("http://93.184.216.34/x?sig=SECRET", "GET", emptyMap())) }
        }
        assertTrue("SECRET" !in e.message.orEmpty(), e.message)
    }
}
