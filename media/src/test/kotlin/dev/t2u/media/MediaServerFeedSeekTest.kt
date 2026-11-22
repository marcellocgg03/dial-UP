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
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A resume part way into a track must not read everything before it. YouTube sends an open range of a file over 10 MiB at
 * about 30 KB/s, and its audio is a fragmented M4A. ffmpeg's `-ss` goes to the fragment that holds the start, and when the
 * distance is under its buffer plus the input socket's receive buffer it reads the bytes in between through the open
 * response instead of asking for a new Range. Android gives a socket a large receive buffer, so a resume at 40 s read 650 KB
 * for 21 s and the 20 s first-byte watchdog killed it (device log, 2026-11-21). Here, against real ffmpeg, a local "YouTube"
 * serves a track of the same layout slowly, and a resume at the second fragment must ask for it by Range and start at once.
 */
class MediaServerFeedSeekTest {
    @TempDir lateinit var dir: File

    private val ffmpeg = ToolPaths.ffmpeg
    private lateinit var upstream: HttpServer
    private lateinit var base: String
    private lateinit var file: ByteArray
    private var server: MediaServer? = null
    private val seen = CopyOnWriteArrayList<MediaRequest>()

    /** The first byte of each upstream request, in the order they came. */
    private val asked = CopyOnWriteArrayList<Int>()

    /** Each upstream answer that ended: the first byte it was asked for and how many bytes it sent before the reader left. */
    private val answers = CopyOnWriteArrayList<Pair<Int, Int>>()

    private class FixedResolver(private val url: String) : VideoResolver {
        override suspend fun resolve(videoId: String): ResolvedInfo =
            ResolvedInfo(url, "m4a", "audio/mp4", emptyMap(), "t", "a", "", "", 35.0, 0, "140")

        override fun invalidate(videoId: String) = Unit
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
        assumeTrue(File(ffmpeg).canExecute(), "needs $ffmpeg")
        // The layout of YouTube's itag 140: ftyp, moov, sidx, then one moof and mdat per fragment, no mfra at the end. Noise
        // keeps the AAC near its 128 kb/s. The fragments are 7 s (YouTube's are 10 s), so the second one starts about 114 KB in:
        // inside the read-through window of a default Linux socket (32 KB + 128 KB), as on the phone, and well past the
        // window of the feed's own receive buffer.
        val src = File(dir, "src.m4a")
        val p = ProcessBuilder(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "anoisesrc=d=35:c=pink:a=0.3:seed=1",
            "-ac", "2", "-ar", "44100", "-c:a", "aac", "-b:a", "128k", "-f", "mp4",
            "-movflags", "empty_moov+default_base_moof+global_sidx+skip_trailer", "-frag_duration", "7000000", src.absolutePath,
        ).redirectErrorStream(true).start()
        val log = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor(), log)
        file = src.readBytes()
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/") { ex ->
            ex.use {
                val m = Regex("bytes=(\\d+)-(\\d*)").find(ex.requestHeaders.getFirst("Range").orEmpty())
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                val a = m?.groupValues?.get(1)?.toInt() ?: 0
                val b = m?.groupValues?.get(2)?.toIntOrNull() ?: (file.size - 1)
                asked += a
                if (m != null) {
                    ex.responseHeaders.add("Content-Range", "bytes $a-$b/${file.size}")
                    ex.sendResponseHeaders(206, (b - a + 1).toLong())
                } else {
                    ex.sendResponseHeaders(200, file.size.toLong())
                }
                var done = 0
                try {
                    // about 12 KB/s: slow like YouTube's open range, so reading the first fragment through takes about 9 s
                    while (done < b - a + 1) {
                        val n = minOf(4096, b - a + 1 - done)
                        ex.responseBody.write(file, a + done, n)
                        ex.responseBody.flush()
                        done += n
                        Thread.sleep(333)
                    }
                } catch (_: IOException) {
                    // the feed or ffmpeg hung up
                } finally {
                    answers += a to done
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

    /** The offsets of the top-level `moof` boxes of [bytes], in file order. */
    private fun moofOffsets(bytes: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i + 8 <= bytes.size) {
            val size = ByteBuffer.wrap(bytes, i, 4).int
            if (String(bytes, i + 4, 4, Charsets.ISO_8859_1) == "moof") out += i
            if (size < 8) break
            i += size
        }
        return out
    }

    @Test
    @DisplayName("a resume at the second fragment asks YouTube for it by Range instead of reading the first one through")
    fun aResumeAsksForItsFragmentByRange() {
        val moofs = moofOffsets(file)
        assertEquals(5, moofs.size, "five 7 s fragments: $moofs")
        val target = moofs[1]
        assertTrue(target in 100_000..140_000, "the second fragment is about 114 KB in: $target")
        val ms = MediaServer(
            "127.0.0.1", 0, JvmPipeLauncher(ffmpeg), { seen.add(it) }, File(dir, "buffer"), FixedResolver("$base/s.m4a"),
            PipeRangeConfig(enabled = false), upstream = HttpFetcher(), ffmpegUrlPolicy = FfmpegUrlPolicy.ANY_HTTP,
        ).also {
            it.start()
            server = it
        }
        val path = ms.registerVideo(VideoEntry("sssssssssss", "adts", startAt = 7))
        val started = System.nanoTime()
        val status = Socket("127.0.0.1", ms.resolvedPort()).use { s ->
            s.soTimeout = 30_000
            s.getOutputStream().apply {
                write(RawHttp.requestBytes("GET", path, "HTTP/1.1", emptyMap()))
                flush()
            }
            // the media server sends its head only with ffmpeg's first audio byte
            RawHttp.readHead(s.getInputStream().buffered()).first
        }
        val headMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(200, status, "reported: $seen")
        // The answer from byte 0 ends when ffmpeg leaves it (for its Range, or with the closed client). A later answer may
        // never end here: HttpURLConnection drains a short remainder in the background to keep the connection.
        val deadline = System.currentTimeMillis() + 15_000
        while (answers.none { it.first == 0 } && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val fromStart = answers.filter { it.first == 0 }.sumOf { it.second }
        assertTrue(
            asked.any { it in (target - 32_768)..target },
            "ffmpeg should ask for the fragment at $target by Range; upstream asked for $asked, answers (from, sent) $answers",
        )
        assertTrue(fromStart < target / 2, "the first fragment was read through: $fromStart bytes from 0; answers $answers")
        assertTrue(headMs < 4_000, "the resume should start at once, took $headMs ms; upstream asked for $asked")
    }
}
