package dev.t2u.media

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** F-07: at most [LIMIT] ffmpeg processes at once (the server's default); the next GET is answered 503 with Retry-After and starts nothing. */
class FfmpegLimitTest {
    @TempDir lateinit var dir: File

    private val release = CountDownLatch(1)

    /** A process whose stdout stays silent until the test releases it, so the call holds its permit. */
    private class SilentProcess(private val release: CountDownLatch) : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                release.await(20, TimeUnit.SECONDS)
                return -1
            }
        }

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

        override fun exitValue(): Int = 0

        override fun destroy() {}

        override fun destroyForcibly(): Process {
            release.countDown()
            return this
        }
    }

    private val starts = CopyOnWriteArrayList<List<String>>()

    private val launcher = PipeLauncher { args ->
        starts += args
        SilentProcess(release)
    }

    private fun get(server: MediaServer, path: String, headers: Map<String, String> = emptyMap()) =
        RawHttp.request(server.resolvedPort(), "GET", path, headers, "HTTP/1.1")

    @Test
    fun thePipeGetBeyondTheLimitIsRefusedWithRetryAfterAndStartsNoProcess() {
        val server = MediaServer("127.0.0.1", 0, launcher, { }, File(dir, "buffer"))
        server.start()
        val holders = ArrayList<Thread>()
        try {
            val paths = (1..LIMIT + 1).map { server.register(StreamEntry(StreamSource.PipeSource(listOf("-i", "x$it")), "adts", "audio/aac", "u$it")) }
            for (p in paths.take(LIMIT)) holders += Thread { runCatching { get(server, p) } }.also { it.start() }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (server.ffmpegInUse() < LIMIT && System.nanoTime() < until) Thread.sleep(10)
            assertEquals(LIMIT, server.ffmpegInUse(), "the live count is exposed")
            assertEquals(LIMIT, starts.size)

            val refused = get(server, paths.last())
            assertEquals(503, refused.status)
            assertNotNull(refused.header("Retry-After"))
            assertEquals(LIMIT, starts.size, "a refused GET starts no process")
            assertEquals(1L, server.ffmpegRejectedCount())

            // a HEAD never needs a permit
            val head = RawHttp.request(server.resolvedPort(), "HEAD", paths.last(), emptyMap(), "HTTP/1.1")
            assertEquals(200, head.status)
        } finally {
            release.countDown()
            holders.forEach { it.join(5000) }
            server.stop()
        }
    }

    @Test
    fun aFinishedGetGivesItsPermitBack() {
        val server = MediaServer("127.0.0.1", 0, launcher, { }, File(dir, "buffer"), maxConcurrentFfmpeg = 1)
        server.start()
        try {
            release.countDown() // processes end at once: the stream is empty, the GET answers 502 and ends
            val path = server.register(StreamEntry(StreamSource.PipeSource(listOf("-i", "x")), "adts", "audio/aac", "u"))
            repeat(3) { assertEquals(502, get(server, path).status) }
            // the server hands the permit back after it has written the answer, so the client can see the answer first
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (server.ffmpegInUse() != 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(0, server.ffmpegInUse())
            assertEquals(0L, server.ffmpegRejectedCount())
        } finally {
            server.stop()
        }
    }

    private companion object {
        const val LIMIT = MediaServer.MAX_CONCURRENT_FFMPEG
    }
}
