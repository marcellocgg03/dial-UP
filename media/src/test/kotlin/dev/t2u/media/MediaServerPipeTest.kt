package dev.t2u.media

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real /usr/bin/ffmpeg (WSL 4.4.2): the pipe path is proven here before it runs on the phone. */
class MediaServerPipeTest {
    @TempDir lateinit var dir: File

    private val ffmpeg = "/usr/bin/ffmpeg"
    private lateinit var tone: File
    private lateinit var bufferDir: File
    private lateinit var launcher: CountingLauncher
    private lateinit var server: MediaServer
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

    @BeforeEach
    fun setUp() {
        assumeTrue(File(ffmpeg).canExecute(), "needs $ffmpeg")
        tone = File(dir, "tone.m4a")
        val p = ProcessBuilder(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
            "-c:a", "aac", "-f", "ipod", tone.absolutePath,
        ).redirectErrorStream(true).start()
        val log = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor(), log)
        bufferDir = File(dir, "buffer").also { it.mkdirs() }
        launcher = CountingLauncher(JvmPipeLauncher(ffmpeg))
        server = MediaServer("127.0.0.1", 0, launcher, { seen.add(it) }, bufferDir)
        server.start()
    }

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    private val toneInput get() = listOf("-hide_banner", "-loglevel", "error", "-i", tone.absolutePath, "-vn")

    private fun register(format: String, mime: String, input: List<String> = toneInput): String =
        server.register(StreamEntry(StreamSource.PipeSource(input), format, mime, "unit-$format"))

    private fun req(method: String, path: String, headers: Map<String, String> = emptyMap(), version: String = "HTTP/1.1") =
        RawHttp.request(server.resolvedPort(), method, path, headers, version)

    private fun isAdtsSync(b: ByteArray) = b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xF6) == 0xF0

    private fun isMp3Start(b: ByteArray): Boolean {
        if (b.size >= 3 && String(b, 0, 3, Charsets.ISO_8859_1) == "ID3") return true
        return b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0
    }

    private fun awaitReport(predicate: (MediaRequest) -> Boolean): MediaRequest {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            seen.firstOrNull(predicate)?.let { return it }
            Thread.sleep(25)
        }
        throw AssertionError("no matching request reported, saw $seen")
    }

    @Test
    fun adtsStreamsChunkedWithAnAdtsFrameFirst() {
        val path = register("adts", "audio/aac")
        val r = req("GET", path)
        assertEquals(200, r.status)
        assertEquals("audio/aac", r.header("Content-Type"))
        assertTrue(r.header("Transfer-Encoding")!!.contains("chunked", true), r.headerLines.toString())
        assertNull(r.header("Content-Length"))
        assertEquals("Streaming", r.header("transferMode.dlna.org"))
        assertEquals(dlnaFeatures("adts"), r.header("contentFeatures.dlna.org"))
        assertEquals("no-store", r.header("Cache-Control"))
        assertTrue(r.body.size > 1000, "body ${r.body.size}")
        assertTrue(isAdtsSync(r.body), "first bytes ${r.body.take(4)}")
        val rep = awaitReport { it.uri == path }
        assertEquals("chunked", rep.pipeMode)
        assertEquals(200, rep.status)
    }

    @Test
    fun mp3StreamsChunkedAndStartsWithAFrameOrId3() {
        val path = register("mp3", "audio/mpeg")
        val r = req("GET", path)
        assertEquals(200, r.status)
        assertEquals("audio/mpeg", r.header("Content-Type"))
        assertTrue(r.header("Transfer-Encoding")!!.contains("chunked", true), r.headerLines.toString())
        assertNull(r.header("Content-Length"))
        assertTrue(isMp3Start(r.body), "first bytes ${r.body.take(4)}")
    }

    @Test
    fun headOnAPipeTokenHasHeadersNoBodyAndStartsNoProcess() {
        val path = register("adts", "audio/aac")
        val r = req("HEAD", path)
        assertEquals(200, r.status)
        assertEquals("audio/aac", r.header("Content-Type"))
        assertEquals("Streaming", r.header("transferMode.dlna.org"))
        assertEquals(dlnaFeatures("adts"), r.header("contentFeatures.dlna.org"))
        assertEquals(0, r.body.size)
        assertEquals(0, launcher.starts.get())
        assertEquals(0, launcher.inner.liveCount())
    }

    @Test
    fun aRangeHeaderOnAPipeIsIgnored() {
        val path = register("adts", "audio/aac")
        val r = req("GET", path, headers = mapOf("Range" to "bytes=100-"))
        assertEquals(200, r.status)
        assertNull(r.header("Content-Range"))
        assertTrue(isAdtsSync(r.body))
    }

    @Test
    fun ffmpegIsGoneWithinThreeSecondsOfAClientHangUp() {
        val endless = listOf("-hide_banner", "-loglevel", "error", "-re", "-f", "lavfi", "-i", "sine=frequency=440:duration=600", "-vn")
        val path = register("adts", "audio/aac", endless)
        Socket("127.0.0.1", server.resolvedPort()).use { s ->
            s.soTimeout = 20_000
            s.getOutputStream().apply {
                write(RawHttp.requestBytes("GET", path, "HTTP/1.1", emptyMap()))
                flush()
            }
            val input = s.getInputStream()
            RawHttp.readHead(input)
            val buf = ByteArray(1024)
            var got = 0
            while (got < buf.size) {
                val n = input.read(buf, got, buf.size - got)
                check(n >= 0) { "stream ended early" }
                got += n
            }
            assertEquals(1, launcher.inner.liveCount(), "ffmpeg should be running while the client reads")
        } // socket closed here
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (launcher.inner.liveCount() > 0 && System.nanoTime() < deadline) Thread.sleep(50)
        assertEquals(0, launcher.inner.liveCount(), "ffmpeg survived the hang-up")
    }

    @Test
    fun aPipeWhoseFfmpegDiesBeforeAnyByteIs502WithTheStderrTailReported() {
        val path = register("adts", "audio/aac", listOf("-hide_banner", "-loglevel", "error", "-i", File(dir, "missing.m4a").absolutePath, "-vn"))
        val r = req("GET", path)
        assertEquals(502, r.status)
        assertNull(r.header("Content-Type")?.takeIf { it.startsWith("audio/") })
        val rep = awaitReport { it.uri == path }
        assertEquals(502, rep.status)
        assertTrue(rep.note.orEmpty().contains("missing.m4a"), "note: ${rep.note}")
        assertEquals(0, launcher.inner.liveCount())
    }

    @Test
    fun anHttp10ClientGetsABufferedBodyWithContentLength() {
        val path = register("mp3", "audio/mpeg")
        val r = req("GET", path, version = "HTTP/1.0")
        assertEquals(200, r.status)
        val length = r.header("Content-Length")?.toInt() ?: throw AssertionError("no Content-Length: ${r.headerLines}")
        assertNull(r.header("Transfer-Encoding"))
        assertEquals(length, r.body.size)
        assertTrue(isMp3Start(r.body), "first bytes ${r.body.take(4)}")
        val rep = awaitReport { it.uri == path }
        assertEquals("buffered", rep.pipeMode)
        assertEquals("HTTP/1.0", rep.httpVersion)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (bufferDir.list().orEmpty().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
        assertEquals(emptyList(), bufferDir.list().orEmpty().toList(), "buffer file must be deleted")
    }

    @Test
    fun anHttp10ClientCanUseRangeOnTheBufferedFile() {
        val path = register("mp3", "audio/mpeg")
        val r = req("GET", path, headers = mapOf("Range" to "bytes=0-99"), version = "HTTP/1.0")
        assertEquals(206, r.status)
        assertEquals(100, r.body.size)
        assertTrue(r.header("Content-Range")!!.startsWith("bytes 0-99/"))
    }
}
