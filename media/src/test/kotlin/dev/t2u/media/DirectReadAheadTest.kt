package dev.t2u.media

// The direct proxy under the Lamp's post-seek scan (plan 03-20, PLAY-06), GPL-3.0-only.
//
// Device session D (Lamp, format direct, 2026-10-22 06:00-06:08): after every seek the Lamp asked the media server for
// 70 to 110 ranges of 6144 bytes, about 68 ms apart: a probe at byte 0, three at 64 KiB and up, then groups of six
// back-to-back ranges strided by 163840 bytes (evidence/session-d/direct-seek-media-requests-runs.txt). Each was its own
// upstream request. The tests replay that pattern against a counting fake upstream and check two things: the number of
// upstream calls falls to about one per read-ahead window, and every byte the speaker receives is exactly what upstream
// would have returned for its Range.
//
// No Node counterpart: the Node proxy opens one upstream request per speaker request (PARITY.md adaptation 28). Every
// test is a block-bodied function: JUnit silently skips a test whose expression body returns a value.

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectReadAheadTest {
    private var server: MediaServer? = null
    private val seen = CopyOnWriteArrayList<MediaRequest>()

    @AfterEach
    fun tearDown() {
        server?.stop()
    }

    /** One upstream call as the fake saw it. */
    private data class Call(val videoId: String, val gen: Int, val range: String?)

    /**
     * Serves [content] (video id and generation to bytes) like googlevideo: a Range gets 206 with the true total in
     * Content-Range (the end clamped to the file), a start past the end gets 416, no Range gets 200. [respond] may
     * override an answer for a call (a 403, an upstream that ignores Range).
     */
    private class CountingUpstream(
        private val content: (videoId: String, gen: Int) -> ByteArray,
        private val respond: (call: Call, number: Int) -> UpstreamResponse? = { _, _ -> null },
        private val delayMs: Long = 0,
    ) : UpstreamFetcher {
        val calls = CopyOnWriteArrayList<Call>()
        private val count = AtomicInteger()

        override suspend fun fetch(request: UpstreamRequest): UpstreamResponse {
            val id = Regex("id=([A-Za-z0-9_-]+)").find(request.url)!!.groupValues[1]
            val gen = Regex("gen=(\\d+)").find(request.url)!!.groupValues[1].toInt()
            val call = Call(id, gen, request.headers["Range"])
            calls.add(call)
            if (delayMs > 0) Thread.sleep(delayMs)
            respond(call, count.getAndIncrement())?.let { return it }
            val data = content(id, gen)
            val m = Regex("bytes=(\\d+)-(\\d*)").find(call.range.orEmpty()) ?: return reply(200, data, emptyMap())
            val a = m.groupValues[1].toLong()
            if (a >= data.size) return reply(416, ByteArray(0), mapOf("content-range" to "bytes */${data.size}"))
            val b = minOf(m.groupValues[2].toLongOrNull() ?: Long.MAX_VALUE, data.size - 1L)
            return reply(206, data.copyOfRange(a.toInt(), b.toInt() + 1), mapOf("content-range" to "bytes $a-$b/${data.size}"))
        }

        private fun reply(status: Int, bytes: ByteArray, extra: Map<String, String>) =
            UpstreamResponse(status, mapOf("content-length" to bytes.size.toString()) + extra, ByteArrayInputStream(bytes))
    }

    /** Like a resolver with a cache: one URL per video until it is invalidated, which moves to the next generation. */
    private class CachingTestResolver : VideoResolver {
        val gen = AtomicInteger()
        val invalidations = AtomicInteger()

        override suspend fun resolve(videoId: String): ResolvedInfo = ResolvedInfo(
            "https://rr1.googlevideo.com/videoplayback?id=$videoId&gen=${gen.get()}&sig=SECRET", "m4a", "audio/mp4",
            mapOf("User-Agent" to "yt-dlp-ua"), "t", "a", "", "", 60.0, 0, "140",
        )

        override fun invalidate(videoId: String) {
            invalidations.incrementAndGet()
            gen.incrementAndGet()
        }
    }

    private fun serve(resolver: VideoResolver, fetcher: UpstreamFetcher): MediaServer =
        MediaServer("127.0.0.1", 0, null, { seen.add(it) }, null, resolver, PipeRangeConfig(), fetcher).also {
            it.start()
            server = it
        }

    private fun register(ms: MediaServer, id: String = "AAAAAAAAAAA") =
        ms.registerVideo(VideoEntry(id, "direct", 0, ext = "m4a", sourceMime = "audio/mp4"))

    private fun payload(n: Int, salt: Int = 0) = ByteArray(n) { ((it * 7 + it / 251 + salt) % 253).toByte() }

    /** One ranged GET like the Lamp's; checks the answer against [data] byte for byte. */
    private fun scan(ms: MediaServer, path: String, data: ByteArray, start: Int, length: Int = 6144) {
        val end = minOf(start + length, data.size) - 1
        val r = RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to "bytes=$start-$end"))
        assertEquals(206, r.status, "range $start-$end, reported: ${seen.lastOrNull()}")
        assertEquals("bytes $start-$end/${data.size}", r.header("Content-Range"), "range $start-$end")
        assertEquals((end - start + 1).toString(), r.header("Content-Length"), "range $start-$end")
        assertTrue(data.copyOfRange(start, end + 1).contentEquals(r.body), "the bytes of range $start-$end differ from upstream's")
    }

    /**
     * The server adds a request-log entry after it has sent the response, from the connection's own thread, so a slow
     * machine can still be missing the last entries, or have them in another order than the requests. Waits (5 s at most)
     * until [ready] holds for a snapshot of the log, then returns the snapshot for the test to assert on.
     */
    private fun awaitSeen(ready: (List<MediaRequest>) -> Boolean): List<MediaRequest> {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!ready(seen.toList()) && System.nanoTime() < deadline) Thread.sleep(5)
        return seen.toList()
    }

    /** The request starts of the device log: a probe at 0, three at 64 KiB and up, then groups of six strided by 163840. */
    private fun deviceLogStarts(total: Int): List<Int> {
        val starts = ArrayList<Int>()
        starts += 0
        for (i in 0 until 3) starts += 65536 + i * 6144
        var group = 0
        while (starts.size < total) {
            val base = 147456 + group * 163840
            for (i in 0 until 6) if (starts.size < total) starts += base + i * 6144
            group++
        }
        return starts
    }

    @Test
    @DisplayName("100 ranges of 6144 bytes as the Lamp asks them after a seek cause far fewer than 100 upstream calls, with exact bytes")
    fun sixKilobyteSequentialRangesCallUpstreamOnce() {
        runBlocking {
            val data = payload(3_000_000)
            val up = CountingUpstream({ _, _ -> data })
            val ms = serve(CachingTestResolver(), up)
            val path = register(ms)

            val starts = deviceLogStarts(100)
            for (s in starts) scan(ms, path, data, s)

            assertEquals(100, seen.count { it.range != null })
            assertTrue(up.calls.size <= 12, "100 speaker requests caused ${up.calls.size} upstream calls (device log: one each)")
        }
    }

    @Test
    @DisplayName("back-to-back 6144-byte ranges over a whole file cost one upstream call per window")
    fun contiguousRangesCostOneUpstreamCallPerWindow() {
        runBlocking {
            val data = payload(1_000_000, salt = 3)
            val up = CountingUpstream({ _, _ -> data })
            val ms = serve(CachingTestResolver(), up)
            val path = register(ms)

            var start = 0
            while (start < data.size) {
                scan(ms, path, data, start)
                start += 6144
            }

            assertTrue(up.calls.size <= 4, "${seen.size} speaker requests caused ${up.calls.size} upstream calls: ${up.calls.map { it.range }}")
        }
    }

    @Test
    @DisplayName("a range outside the held window fetches a new window that starts at the range, and the next ranges are served from it")
    fun aRangeOutsideTheWindowFetchesANewWindow() {
        runBlocking {
            val data = payload(3_000_000, salt = 5)
            val up = CountingUpstream({ _, _ -> data })
            val ms = serve(CachingTestResolver(), up)
            val path = register(ms)
            for (s in listOf(0, 6144, 12288, 18432)) scan(ms, path, data, s)

            val before = up.calls.size
            scan(ms, path, data, 2_000_000)
            assertEquals(before + 1, up.calls.size, "one call for the range outside the window")
            assertTrue(up.calls.last().range.orEmpty().startsWith("bytes=2000000-"), "the new window starts at the requested offset: ${up.calls.last()}")

            scan(ms, path, data, 2_000_000 + 6144)
            scan(ms, path, data, 2_000_000 + 12288)
            assertEquals(before + 1, up.calls.size, "the ranges that follow need no upstream call")
        }
    }

    @Test
    @DisplayName("a window held for one token never answers another token")
    fun aWindowNeverAnswersAnotherToken() {
        runBlocking {
            val a = payload(1_000_000, salt = 1)
            val b = payload(1_000_000, salt = 2)
            val up = CountingUpstream({ id, _ -> if (id == "AAAAAAAAAAA") a else b })
            val ms = serve(CachingTestResolver(), up)
            val pathA = register(ms, "AAAAAAAAAAA")
            val pathB = register(ms, "BBBBBBBBBBB")

            for (s in listOf(0, 6144, 12288, 18432)) scan(ms, pathA, a, s)
            for (s in listOf(0, 6144, 12288, 18432, 24576)) scan(ms, pathB, b, s)
            scan(ms, pathA, a, 30720)
        }
    }

    @Test
    @DisplayName("a 403 on the upstream re-resolves once and no byte of the old URL's window is served afterwards")
    fun aForbiddenUpstreamStillReResolves() {
        runBlocking {
            val v1 = payload(2_000_000, salt = 7)
            val v2 = payload(2_000_000, salt = 9)
            val armed = AtomicInteger()
            val up = CountingUpstream(
                content = { _, gen -> if (gen == 0) v1 else v2 },
                respond = { call, _ ->
                    if (call.gen == 0 && armed.get() == 1) UpstreamResponse(403, mapOf("content-length" to "0"), ByteArrayInputStream(ByteArray(0))) else null
                },
            )
            val resolver = CachingTestResolver()
            val ms = serve(resolver, up)
            val path = register(ms)
            for (s in listOf(0, 6144, 12288, 18432)) scan(ms, path, v1, s)

            armed.set(1) // the signed URL is revoked: every further request on generation 0 is refused
            scan(ms, path, v2, 1_500_000) // outside any window: upstream says 403, the proxy re-resolves and fetches generation 1
            assertEquals(1, resolver.invalidations.get(), "one re-resolve")
            assertEquals(1, up.calls.last().gen, "the answer came from the fresh URL")

            scan(ms, path, v2, 24576) // inside where the old window was: the old bytes must be gone
            scan(ms, path, v2, 30720)
            assertEquals(1, resolver.invalidations.get(), "no second re-resolve")
        }
    }

    private fun windowSized(call: Call): Boolean {
        val m = Regex("bytes=(\\d+)-(\\d+)").find(call.range.orEmpty()) ?: return false
        return m.groupValues[2].toLong() - m.groupValues[1].toLong() + 1 > 100_000
    }

    @Test
    @DisplayName("the bytes held for read-ahead never pass the total budget, even with many tokens warming at once")
    fun theMemoryBudgetIsNeverExceeded() {
        runBlocking {
            val data = payload(1_500_000, salt = 11)
            val up = CountingUpstream({ _, _ -> data }, delayMs = 15)
            val ms = serve(CachingTestResolver(), up)
            val paths = (0 until 8).map { register(ms, "VIDEO%06d".format(it).padEnd(11, 'x')) }
            var max = 0L
            val workers = paths.map { path ->
                thread {
                    for (s in listOf(0, 6144, 12288, 700_000, 706_144)) scan(ms, path, data, s)
                }
            }
            while (workers.any { it.isAlive }) {
                max = maxOf(max, ms.readAheadHeldBytes())
                Thread.sleep(2)
            }
            workers.forEach { it.join() }
            max = maxOf(max, ms.readAheadHeldBytes())
            assertTrue(max <= READ_AHEAD_TOTAL_BYTES, "held up to $max bytes, budget $READ_AHEAD_TOTAL_BYTES")
            assertTrue(READ_AHEAD_TOTAL_BYTES >= READ_AHEAD_WINDOW_BYTES, "the budget holds at least one window")
        }
    }

    @Test
    @DisplayName("release drops the token's window and the next range goes upstream again")
    fun theWindowIsDroppedWhenTheTokenIsReleased() {
        runBlocking {
            val data = payload(1_000_000, salt = 13)
            val up = CountingUpstream({ _, _ -> data })
            val ms = serve(CachingTestResolver(), up)
            val path = register(ms)
            for (s in listOf(0, 6144, 12288)) scan(ms, path, data, s)
            assertEquals(READ_AHEAD_WINDOW_BYTES.toLong(), ms.readAheadHeldBytes(), "one window is held")
            val calls = up.calls.size

            ms.release("/stream/${"0".repeat(32)}.m4a") // an unknown token: nothing happens
            ms.release("/nothing")
            assertEquals(READ_AHEAD_WINDOW_BYTES.toLong(), ms.readAheadHeldBytes())

            ms.release(path)
            assertEquals(0L, ms.readAheadHeldBytes(), "released")
            scan(ms, path, data, 18432) // the token still works
            assertEquals(calls + 1, up.calls.size, "and its next range fetches a new window")
        }
    }

    @Test
    @DisplayName("the request log names each upstream call with its timing and never the URL or the signature")
    fun theUpstreamTimingIsLoggedWithoutTheUrl() {
        runBlocking {
            val data = payload(1_000_000, salt = 17)
            val up = CountingUpstream({ _, _ -> data })
            val ms = serve(CachingTestResolver(), up)
            val path = register(ms)
            for (s in listOf(0, 6144, 12288)) scan(ms, path, data, s)
            // Each entry is added after its response is sent, by the connection's own thread: on a slow machine the third
            // can be missing and two of them can land in the other order. Wait for the three lines, then match by shape.
            val lines = awaitSeen { log ->
                val l = log.map { it.upstream.orEmpty() }
                l.size >= 3 && l.any { it.startsWith("proxy ") } && l.any { it.startsWith("window 206 ") } && "window hit" in l
            }.map { it.upstream.orEmpty() }
            assertEquals(3, lines.size, "one line per speaker request: $lines")
            val probe = lines.single { it.startsWith("proxy ") }
            val window = lines.single { it.startsWith("window 206 ") }
            assertTrue(probe.startsWith("proxy 206 first byte ") && " ms total " in probe, "the probe is a plain upstream call: $probe")
            assertTrue(window.startsWith("window 206 first byte ") && " ms total " in window && window.endsWith(" $READ_AHEAD_WINDOW_BYTES B"), "the window fetch: $window")
            assertEquals(1, lines.count { it == "window hit" }, "the third range is served from the window: $lines")
            for (l in lines) {
                assertTrue("googlevideo" !in l && "SECRET" !in l && "gen=" !in l && "http" !in l && "?" !in l, "no URL in: $l")
            }
        }
    }

    @Test
    @DisplayName("an upstream that ignores the window Range turns read-ahead off for the token; every range is still exact")
    fun anUpstreamThatIgnoresTheWindowRangeFallsBack() {
        runBlocking {
            val data = payload(1_000_000, salt = 19)
            val up = CountingUpstream(
                content = { _, _ -> data },
                respond = { call, _ ->
                    if (windowSized(call)) UpstreamResponse(200, mapOf("content-length" to data.size.toString()), ByteArrayInputStream(data)) else null
                },
            )
            val ms = serve(CachingTestResolver(), up)
            val path = register(ms)
            val starts = listOf(0, 6144, 12288, 18432, 24576, 30720)
            for (s in starts) scan(ms, path, data, s)

            assertEquals(starts.size + 1, up.calls.size, "one refused window, then one call per range as before: ${up.calls.map { it.range }}")
            val log = awaitSeen { l -> l.size >= starts.size && l.any { it.note.orEmpty().startsWith("read-ahead off") } }
            assertTrue(log.any { it.note.orEmpty().startsWith("read-ahead off") }, "the log says so: ${log.map { it.note }}")
            assertEquals(0L, ms.readAheadHeldBytes())
        }
    }

    @Test
    @DisplayName("a failing window fetch is not an error for the speaker: the plain path answers it")
    fun aFailingWindowFetchFallsBackToThePlainPath() {
        runBlocking {
            val data = payload(1_000_000, salt = 23)
            val up = CountingUpstream(
                content = { _, _ -> data },
                respond = { call, _ -> if (windowSized(call)) throw IOException("window fetch failed") else null },
            )
            val ms = serve(CachingTestResolver(), up)
            val path = register(ms)
            val starts = listOf(0, 6144, 12288, 18432)
            for (s in starts) scan(ms, path, data, s)
            val log = awaitSeen { l -> l.size >= starts.size }
            assertEquals(starts.size, log.size, "one log entry per speaker request")
            assertTrue(log.all { it.status == 206 }, "every speaker request was answered: ${log.map { it.status }}")
        }
    }

    @Test
    @DisplayName("concurrent requests for the same range cost one window fetch")
    fun concurrentRequestsForOneRangeFetchOneWindow() {
        runBlocking {
            val data = payload(1_000_000, salt = 29)
            val up = CountingUpstream({ _, _ -> data }, delayMs = 30)
            val ms = serve(CachingTestResolver(), up)
            val path = register(ms)
            scan(ms, path, data, 0)
            val workers = (0 until 8).map { thread { scan(ms, path, data, 6144) } }
            workers.forEach { it.join() }
            assertEquals(2, up.calls.size, "the probe and one window: ${up.calls.map { it.range }}")
        }
    }

    @Test
    @DisplayName("a range past the end of the file is passed on as upstream's 416, not answered from a window")
    fun aRangePastTheEndIsPassedOn() {
        runBlocking {
            val data = payload(100_000, salt = 31)
            val up = CountingUpstream({ _, _ -> data })
            val ms = serve(CachingTestResolver(), up)
            val path = register(ms)
            for (s in listOf(0, 6144, 12288)) scan(ms, path, data, s)
            val r = RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to "bytes=200000-206143"))
            assertEquals(416, r.status)
            assertEquals("bytes */100000", r.header("Content-Range"))
            assertEquals(0, r.body.size)
        }
    }

    @Test
    @DisplayName("a range that runs past the end of the file is cut to the file, as upstream does")
    fun aRangeRunningPastTheEndIsCutToTheFile() {
        runBlocking {
            val data = payload(100_000, salt = 37)
            val up = CountingUpstream({ _, _ -> data })
            val ms = serve(CachingTestResolver(), up)
            val path = register(ms)
            for (s in listOf(0, 6144, 12288)) scan(ms, path, data, s)
            val r = RawHttp.request(ms.resolvedPort(), "GET", path, mapOf("Range" to "bytes=96000-102143"))
            assertEquals(206, r.status)
            assertEquals("bytes 96000-99999/100000", r.header("Content-Range"))
            assertTrue(data.copyOfRange(96000, 100000).contentEquals(r.body))
        }
    }
}
