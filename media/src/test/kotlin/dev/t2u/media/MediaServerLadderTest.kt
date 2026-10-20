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
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
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
    private lateinit var launcher: JvmPipeLauncher
    private var server: MediaServer? = null
    private val seen = CopyOnWriteArrayList<MediaRequest>()

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
        upstream.start()
        base = "http://127.0.0.1:${upstream.address.port}"
        launcher = JvmPipeLauncher(ffmpeg)
    }

    @AfterEach
    fun tearDown() {
        server?.stop()
        if (::upstream.isInitialized) upstream.stop(0)
    }

    private fun serve(resolver: VideoResolver): MediaServer =
        MediaServer("127.0.0.1", 0, launcher, { seen.add(it) }, File(dir, "buffer"), resolver).also {
            it.start()
            server = it
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
        assertEquals(200, r.status)
        assertEquals("audio/mpeg", r.header("Content-Type"))
        assertTrue(isMp3Start(r.body), "first bytes ${r.body.take(4)}")
        val saved = File(dir, "out.mp3").also { it.writeBytes(r.body) }
        assertEquals("mp3", probe(saved, "stream=codec_name"))
        assertEquals(2, resolver.resolves.get())
        assertEquals(1, resolver.invalidations.get(), "the second attempt must drop the cached entry first")
    }
}
