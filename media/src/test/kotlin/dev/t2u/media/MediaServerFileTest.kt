package dev.t2u.media

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaServerFileTest {
    @TempDir lateinit var dir: File

    private lateinit var server: MediaServer
    private lateinit var data: ByteArray
    private lateinit var path: String
    private val seen = CopyOnWriteArrayList<MediaRequest>()

    @BeforeEach
    fun setUp() {
        data = ByteArray(1000) { (it * 7 + 3).toByte() }
        val file = File(dir, "tone.m4a").also { it.writeBytes(data) }
        server = MediaServer("127.0.0.1", 0, null, { seen.add(it) })
        server.start()
        path = server.register(StreamEntry(StreamSource.FileSource(file), "mp4", "audio/mp4", "unit-file"))
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    private fun req(method: String, p: String = path, headers: Map<String, String> = emptyMap(), version: String = "HTTP/1.1") =
        RawHttp.request(server.resolvedPort(), method, p, headers, version)

    // ----- parseRange (size 1000) --------------------------------------------------------------

    private fun span(h: String?) = parseRange(h, 1000)

    @Test
    fun parseRangeExplicitSpan() = assertEquals(ByteRange.Span(0, 99), span("bytes=0-99"))

    @Test
    fun parseRangeSuffixIsTheLastBytes() = assertEquals(ByteRange.Span(900, 999), span("bytes=-100"))

    @Test
    fun parseRangeOpenEndRunsToTheEnd() = assertEquals(ByteRange.Span(500, 999), span("bytes=500-"))

    @Test
    fun parseRangeEndIsClamped() = assertEquals(ByteRange.Span(0, 999), span("bytes=0-5000"))

    @Test
    fun parseRangeSuffixLargerThanTheFileStartsAtZero() = assertEquals(ByteRange.Span(0, 999), span("bytes=-2000"))

    @Test
    fun parseRangeStartBeyondTheSizeIsUnsatisfiable() = assertEquals(ByteRange.Unsatisfiable, span("bytes=999999999-"))

    @Test
    fun parseRangeStartAfterEndIsUnsatisfiable() = assertEquals(ByteRange.Unsatisfiable, span("bytes=5-2"))

    @Test
    fun parseRangeDigitsBeyondLongAreUnsatisfiable() = assertEquals(ByteRange.Unsatisfiable, span("bytes=99999999999999999999-"))

    @Test
    fun parseRangeIgnoresWhatItCannotRead() {
        assertNull(span("bytes=-"))
        assertNull(span("items=0-1"))
        assertNull(span(null))
        assertEquals(ByteRange.Span(0, 99), span("  bytes=0-99  "))
    }

    // ----- Dlna ----------------------------------------------------------------------------------

    @Test
    fun dlnaFeaturesMatchFormatsJs() {
        assertEquals(
            "DLNA.ORG_PN=AAC_ISO_320;DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000",
            dlnaFeatures("mp4"),
        )
        assertEquals(
            "DLNA.ORG_PN=AAC_ADTS_320;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000",
            dlnaFeatures("adts"),
        )
        assertEquals("DLNA.ORG_PN=MP3;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000", dlnaFeatures("mp3"))
        assertEquals("DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000", dlnaFeatures("direct"))
    }

    // ----- registry ------------------------------------------------------------------------------

    @Test
    fun registeredPathHas32LowercaseHexAndTheFormatExtension() {
        assertTrue(Regex("^/stream/[0-9a-f]{32}\\.m4a$").matches(path), path)
        val f = File(dir, "x")
        val e = { fmt: String -> server.register(StreamEntry(StreamSource.FileSource(f), fmt, "x/y", "l")) }
        assertTrue(e("adts").endsWith(".aac"))
        assertTrue(e("mp3").endsWith(".mp3"))
    }

    @Test
    fun theOldestTokenIsEvictedBeyond256() {
        val f = File(dir, "x").also { it.writeBytes(ByteArray(1)) }
        repeat(MediaServer.MAX_TOKENS) { server.register(StreamEntry(StreamSource.FileSource(f), "mp4", "audio/mp4", "n$it")) }
        assertEquals(MediaServer.MAX_TOKENS, server.tokenCount())
        assertEquals(404, req("GET").status, "the first token (from setUp) must be evicted")
    }

    // ----- HEAD / GET ----------------------------------------------------------------------------

    @Test
    fun headAnswersLengthRangesAndDlnaHeadersWithoutBody() {
        val r = req("HEAD")
        assertEquals(200, r.status)
        assertEquals("1000", r.header("Content-Length"))
        assertEquals("bytes", r.header("Accept-Ranges"))
        assertEquals("Streaming", r.header("transferMode.dlna.org"))
        assertEquals(dlnaFeatures("mp4"), r.header("contentFeatures.dlna.org"))
        assertEquals("audio/mp4", r.header("Content-Type"))
        assertEquals("no-store", r.header("Cache-Control"))
        assertEquals(0, r.body.size)
        assertEquals(1, r.headerCount("Content-Length"))
    }

    @Test
    fun getReturnsTheWholeFile() {
        val r = req("GET")
        assertEquals(200, r.status)
        assertContentEquals(data, r.body)
        assertEquals(1, r.headerCount("Content-Length"))
        assertEquals("1000", r.header("Content-Length"))
        assertNull(r.header("Content-Range"))
    }

    @Test
    fun rangeFromZeroAnswers206WithContentRange() {
        val r = req("GET", headers = mapOf("Range" to "bytes=0-99"))
        assertEquals(206, r.status)
        assertEquals("bytes 0-99/1000", r.header("Content-Range"))
        assertEquals("100", r.header("Content-Length"))
        assertContentEquals(data.copyOfRange(0, 100), r.body)
        assertEquals(1, r.headerCount("Content-Length"))
    }

    @Test
    fun suffixRangeReturnsTheLastBytes() {
        val r = req("GET", headers = mapOf("Range" to "bytes=-100"))
        assertEquals(206, r.status)
        assertEquals("bytes 900-999/1000", r.header("Content-Range"))
        assertContentEquals(data.copyOfRange(900, 1000), r.body)
    }

    @Test
    fun openEndedRangeReturnsTheTail() {
        val r = req("GET", headers = mapOf("Range" to "bytes=500-"))
        assertEquals(206, r.status)
        assertContentEquals(data.copyOfRange(500, 1000), r.body)
    }

    @Test
    fun headWithRangeAnswers206HeadersOnly() {
        val r = req("HEAD", headers = mapOf("Range" to "bytes=0-99"))
        assertEquals(206, r.status)
        assertEquals("100", r.header("Content-Length"))
        assertEquals("bytes 0-99/1000", r.header("Content-Range"))
        assertEquals(0, r.body.size)
    }

    @Test
    fun unsatisfiableRangeAnswers416WithSizeInContentRange() {
        val r = req("GET", headers = mapOf("Range" to "bytes=999999999-"))
        assertEquals(416, r.status)
        assertEquals("bytes */1000", r.header("Content-Range"))
        assertEquals(0, r.body.size)
        assertTrue(r.headerCount("Content-Length") <= 1)
    }

    @Test
    fun anUnreadableRangeHeaderMeansTheWholeFile() {
        val r = req("GET", headers = mapOf("Range" to "items=0-1"))
        assertEquals(200, r.status)
        assertEquals(1000, r.body.size)
    }

    // ----- 404 everywhere else (threat T-01-14) -------------------------------------------------

    @Test
    fun anUnregisteredTokenIs404() {
        assertEquals(404, req("GET", "/stream/0123456789abcdef0123456789abcdef.m4a").status)
        assertEquals(404, req("HEAD", "/stream/0123456789abcdef0123456789abcdef.m4a").status)
    }

    @Test
    fun aMalformedNameIs404() {
        assertEquals(404, req("GET", "/stream/notatoken.m4a").status)
        assertEquals(404, req("GET", "/stream/${path.substringAfterLast('/').uppercase()}").status)
        assertEquals(404, req("GET", "$path?x=1").status)
    }

    @Test
    fun otherPathsAre404() {
        assertEquals(404, req("GET", "/").status)
        assertEquals(404, req("GET", "/etc/passwd").status)
        assertEquals(404, req("GET", "/stream/../../etc/passwd").status)
    }

    @Test
    fun otherMethodsOnARegisteredTokenAre404() {
        assertEquals(404, req("POST").status)
        assertEquals(404, req("DELETE").status)
        assertEquals(404, req("PUT").status)
    }

    // ----- reporting -----------------------------------------------------------------------------

    @Test
    fun everyCallIsReportedWithVersionRangeAndUserAgent() {
        req("GET", headers = mapOf("Range" to "bytes=0-9", "User-Agent" to "Sonos/test"))
        req("GET", "/nope")
        val deadline = System.currentTimeMillis() + 3000
        while (seen.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val ok = seen.first { it.status == 206 }
        assertEquals("HTTP/1.1", ok.httpVersion)
        assertEquals("bytes=0-9", ok.range)
        assertEquals("Sonos/test", ok.userAgent)
        assertEquals(10L, ok.bytes)
        assertEquals("file", ok.pipeMode)
        assertEquals("unit-file", ok.label)
        val miss = seen.first { it.status == 404 }
        assertEquals("/nope", miss.uri)
        assertNull(miss.pipeMode)
    }
}
