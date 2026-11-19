package dev.t2u.media

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A video pipe reads through the server's own feed, so a signed URL that is about to expire is replaced behind ffmpeg's back:
 * against real ffmpeg, a local "YouTube" that serves a 60 s fragmented M4A slowly and whose URLs expire a few seconds after
 * they are handed out. The answer must still be the whole track, in one piece, from several upstream requests.
 */
class MediaServerFeedTest {
    @TempDir lateinit var dir: File

    private val ffmpeg = ToolPaths.ffmpeg
    private val ffprobe = ToolPaths.ffprobe
    private lateinit var upstream: HttpServer
    private lateinit var base: String
    private var server: MediaServer? = null
    private val seen = CopyOnWriteArrayList<MediaRequest>()

    /** Each upstream request: its Range header and the `expire` it was made with. */
    private val upstreamRequests = CopyOnWriteArrayList<Pair<String, String>>()

    /** Hands out a URL that expires [ttlSeconds] from now each time it is asked, and counts the asks. */
    private class ExpiringResolver(private val base: String, private val ttlSeconds: Long) : VideoResolver {
        val resolves = AtomicInteger()
        val invalidations = AtomicInteger()

        override suspend fun resolve(videoId: String): ResolvedInfo {
            resolves.incrementAndGet()
            val expire = System.currentTimeMillis() / 1000 + ttlSeconds
            return ResolvedInfo("$base/s.m4a?expire=$expire", "m4a", "audio/mp4", emptyMap(), "t", "a", "", "", 60.0, 0, "140")
        }

        override fun invalidate(videoId: String) {
            invalidations.incrementAndGet()
        }
    }

    /** A real HTTP fetch, standing in for the OkHttp fetcher of the app. */
    private class HttpFetcher : UpstreamFetcher {
        override suspend fun fetch(request: UpstreamRequest): UpstreamResponse {
            val c = URI(request.url).toURL().openConnection() as HttpURLConnection
            c.requestMethod = request.method
            request.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val status = c.responseCode
            val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
            val body = if (request.method == "HEAD" || status >= 400) null else c.inputStream
            return UpstreamResponse(status, headers, body) { runCatching { c.disconnect() } }
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
                val range = ex.requestHeaders.getFirst("Range").orEmpty()
                upstreamRequests += range to ex.requestURI.query.orEmpty()
                val m = Regex("bytes=(\\d+)-(\\d*)").find(range)
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                val a = m?.groupValues?.get(1)?.toInt() ?: 0
                val b = m?.groupValues?.get(2)?.toIntOrNull() ?: (file.size - 1)
                if (m != null) {
                    ex.responseHeaders.add("Content-Range", "bytes $a-$b/${file.size}")
                    ex.sendResponseHeaders(206, (b - a + 1).toLong())
                } else {
                    ex.sendResponseHeaders(200, file.size.toLong())
                }
                try {
                    // about 80 KB/s: the track takes several seconds, longer than a URL lives in this test
                    var done = 0
                    while (done < b - a + 1) {
                        val n = minOf(4096, b - a + 1 - done)
                        ex.responseBody.write(file, a + done, n)
                        ex.responseBody.flush()
                        done += n
                        Thread.sleep(50)
                    }
                } catch (_: IOException) {
                    // the feed or ffmpeg hung up
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
        if (::upstream.isInitialized) upstream.stop(0)
    }

    private fun serve(resolver: VideoResolver): MediaServer = MediaServer(
        "127.0.0.1", 0, JvmPipeLauncher(ffmpeg), { seen.add(it) }, File(dir, "buffer"), resolver,
        PipeRangeConfig(enabled = false), upstream = HttpFetcher(), ffmpegUrlPolicy = FfmpegUrlPolicy.ANY_HTTP,
        feedRefreshMarginMs = 5_000, feedCutMarginMs = 3_000,
    ).also {
        it.start()
        server = it
    }

    /**
     * The playing time of [bytes], found by decoding them. ffprobe's `format=duration` is only an estimate for ADTS (size over
     * bitrate) and moves with the ffmpeg version's AAC encoder (64 s for this 60 s track with ffmpeg 7, 59.6 s with 4.4).
     */
    private fun durationOf(bytes: ByteArray, name: String): Double {
        val f = File(dir, name).also { it.writeBytes(bytes) }
        val p = ProcessBuilder(ffmpeg, "-v", "error", "-nostats", "-progress", "pipe:1", "-i", f.absolutePath, "-f", "null", "-")
            .redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        val micros = Regex("out_time_us=(\\d+)").findAll(text).last().groupValues[1].toLong()
        return micros / 1_000_000.0
    }

    @Test
    @DisplayName("a URL that expires mid-track is replaced behind ffmpeg: the answer is the whole track")
    fun aUrlThatExpiresMidTrackIsReplacedBehindFfmpeg() {
        val resolver = ExpiringResolver(base, ttlSeconds = 8)
        val ms = serve(resolver)
        val path = ms.registerVideo(VideoEntry("fffffffffff", "adts"))
        val r = RawHttp.request(ms.resolvedPort(), "GET", path)
        assertEquals(200, r.status, "reported: $seen")
        val duration = durationOf(r.body, "out.aac")
        // the join is exact: the same bytes ffmpeg writes for the same file read in one piece
        val whole = ProcessBuilder(ffmpeg, "-v", "error", "-i", File(dir, "src.m4a").absolutePath, "-vn", "-c:a", "copy", "-f", "adts", "-")
            .start().inputStream.readBytes()
        assertTrue(r.body.contentEquals(whole), "answer ${r.body.size} bytes, uncut ${whole.size} bytes")
        assertTrue(duration in 58.5..60.5, "the whole track arrived in one piece, got $duration s")
        val resumes = upstreamRequests.count { (range, _) -> Regex("bytes=[1-9]\\d*-").containsMatchIn(range) }
        assertTrue(upstreamRequests.size >= 3 && resumes >= 1, "ffmpeg should have come back with a Range: $upstreamRequests")
        assertTrue(resolver.resolves.get() >= 2, "a fresh URL was looked up: ${resolver.resolves}")
    }

    @Test
    @DisplayName("a feed token the server did not make is answered 404 and asks YouTube for nothing")
    fun anUnknownFeedTokenIsRefused() {
        val resolver = ExpiringResolver(base, ttlSeconds = 8)
        val ms = serve(resolver)
        val r = RawHttp.request(ms.resolvedPort(), "GET", "/feed/" + "0".repeat(32))
        assertEquals(404, r.status)
        assertEquals(0, resolver.resolves.get())
        assertTrue(upstreamRequests.isEmpty())
    }
}
