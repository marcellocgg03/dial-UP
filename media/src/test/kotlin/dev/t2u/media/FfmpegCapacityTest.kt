package dev.t2u.media

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the ffmpeg limits must let through and what they must stop. A "speaker" here fetches like a Sonos: GETs that stay open
 * while the stream runs. The fake ffmpeg streams until it is killed, so a client that hangs up is noticed by the next write.
 */
class FfmpegCapacityTest {
    @TempDir lateinit var dir: File

    /** A process that writes a small chunk every 10 ms until [destroyForcibly]. */
    private class StreamingProcess : Process() {
        val killed = AtomicBoolean(false)

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int = if (fill(ByteArray(1), 0, 1) < 0) -1 else 0

            override fun read(b: ByteArray, off: Int, len: Int): Int = fill(b, off, len)

            private fun fill(b: ByteArray, off: Int, len: Int): Int {
                if (killed.get()) return -1
                Thread.sleep(10)
                if (killed.get()) return -1
                val n = minOf(len, 512)
                java.util.Arrays.fill(b, off, off + n, 1)
                return n
            }
        }

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

        override fun exitValue(): Int = 0

        override fun destroy() {
            killed.set(true)
        }

        override fun destroyForcibly(): Process {
            killed.set(true)
            return this
        }
    }

    private val starts = CopyOnWriteArrayList<List<String>>()
    private val launcher = PipeLauncher { args ->
        starts += args
        StreamingProcess()
    }

    private val open = CopyOnWriteArrayList<Socket>()
    private val servers = CopyOnWriteArrayList<MediaServer>()

    private fun newServer(launcher: PipeLauncher = this.launcher, buffer: Boolean = false): MediaServer =
        MediaServer("127.0.0.1", 0, launcher, { }, if (buffer) File(dir, "buffer") else null).also {
            it.start()
            servers += it
        }

    @org.junit.jupiter.api.AfterEach
    fun cleanup() {
        open.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.stop() } }
    }

    private fun token(server: MediaServer, n: Int) =
        server.register(StreamEntry(StreamSource.PipeSource(listOf("-i", "x$n")), "adts", "audio/aac", "u$n"))

    /** Sends a GET and returns the status of the answer; the connection stays open and is closed by [cleanup] or [hangUp]. */
    private fun get(server: MediaServer, path: String, headers: Map<String, String> = emptyMap(), version: String = "HTTP/1.1"): Pair<Socket, Int> {
        val s = Socket("127.0.0.1", server.resolvedPort())
        s.soTimeout = 10_000
        open += s
        s.getOutputStream().write(RawHttp.requestBytes("GET", path, version, headers))
        s.getOutputStream().flush()
        return s to RawHttp.readHead(s.getInputStream()).first
    }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!cond() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue(cond(), what)
    }

    @Test
    fun `three casts each with a playing GET, a pre-load GET and a takeover overlap are all served`() {
        val server = newServer()
        // one cast: the current track, the next track opened early, and the old stream still open while another sender takes over
        val statuses = (1..3).flatMap { cast -> (1..3).map { part -> get(server, token(server, cast * 10 + part)).second } }
        assertEquals(List(9) { 200 }, statuses)
        assertEquals(9, server.ffmpegInUse(), "3 permits per cast at the peak")
        assertEquals(0L, server.ffmpegRejectedCount())
        assertTrue(MediaServer.MAX_CONCURRENT_FFMPEG >= 8 * 3, "the global limit covers 8 casting speakers at 3 permits each")
    }

    @Test
    fun `a flood on one token beyond its cap is refused while another token on the same server is served`() {
        val server = newServer()
        val flooded = token(server, 1)
        val cap = MediaServer.MAX_FFMPEG_PER_TOKEN
        val answers = (1..cap + 3).map { get(server, flooded) }
        assertEquals(List(cap) { 200 } + List(3) { 503 }, answers.map { it.second })
        assertEquals(cap, starts.size, "a refused GET starts no process")
        assertEquals(3L, server.ffmpegRejectedCount())
        assertEquals(3L, server.ffmpegRejectedPerTokenCount(), "the refusals were the token's own cap")
        val refused = RawHttp.request(server.resolvedPort(), "GET", flooded, emptyMap(), "HTTP/1.1")
        assertEquals(503, refused.status)
        assertNotNull(refused.header("Retry-After"))
        assertEquals(4L, server.ffmpegRejectedCount())

        assertEquals(200, get(server, token(server, 2)).second, "a takeover or any other token is not affected")
        assertEquals(4L, server.ffmpegRejectedCount())

        // a HEAD never takes a slot, even on the token that is full
        val head = RawHttp.request(server.resolvedPort(), "HEAD", flooded, emptyMap(), "HTTP/1.1")
        assertEquals(200, head.status)
        assertEquals(cap + 1, server.ffmpegInUse())
    }

    @Test
    fun `a speaker's probe GET, real GET and Range GET on one token are all served with margin`() {
        val server = newServer()
        val path = token(server, 1)
        val probe = get(server, path)
        val real = get(server, path)
        val ranged = get(server, path, mapOf("Range" to "bytes=0-"))
        assertEquals(listOf(200, 200, 200), listOf(probe.second, real.second, ranged.second))
        assertEquals(3, server.ffmpegInUse())
        assertEquals(0L, server.ffmpegRejectedCount())
        assertTrue(MediaServer.MAX_FFMPEG_PER_TOKEN > 3, "a reconnect that overtakes the old connection still fits")
        assertEquals(200, get(server, path).second, "the fourth, a reconnect, is served too")
    }

    @Test
    fun `every slot is given back after the clients hang up and no token stays in the map`() {
        val server = newServer()
        val sockets = (1..6).flatMap { n ->
            val path = token(server, n)
            (1..2).map { get(server, path).first }
        }
        assertEquals(12, server.ffmpegInUse())
        assertEquals(6, server.ffmpegTokensTracked())
        sockets.forEach { it.close() }
        waitUntil("slots come back after the hang-ups") { server.ffmpegInUse() == 0 }
        waitUntil("finished tokens leave the map") { server.ffmpegTokensTracked() == 0 }
        assertEquals(0L, server.ffmpegRejectedCount())
    }

    @Test
    fun `a stream that produces nothing gives its slot back and leaves no entry`() {
        // a process whose output is empty and ends at once: the GET answers 502
        val silent = PipeLauncher {
            object : Process() {
                override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
                override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun waitFor(): Int = 1
                override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
                override fun exitValue(): Int = 1
                override fun destroy() {}
                override fun destroyForcibly(): Process = this
            }
        }
        val server = newServer(launcher = silent)
        repeat(40) { n -> assertEquals(502, get(server, token(server, n)).second) }
        assertEquals(0, server.ffmpegInUse())
        assertEquals(0, server.ffmpegTokensTracked(), "the map does not grow with the tokens that came and went")
        assertEquals(0L, server.ffmpegRejectedCount())
    }

    @Test
    fun `an HTTP 1_0 GET hands its slot back when ffmpeg is done, before the finished file is sent`() {
        val bigFile = PipeLauncher { args ->
            starts += args
            File(args.last()).outputStream().use { o -> repeat(64) { o.write(ByteArray(1 shl 20)) } }
            object : Process() {
                override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
                override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun waitFor(): Int = 0
                override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
                override fun exitValue(): Int = 0
                override fun destroy() {}
                override fun destroyForcibly(): Process = this
            }
        }
        val server = newServer(launcher = bigFile, buffer = true)
        val path = token(server, 1)
        val s = Socket("127.0.0.1", server.resolvedPort())
        open += s
        s.getOutputStream().write(RawHttp.requestBytes("GET", path, "HTTP/1.0", emptyMap()))
        s.getOutputStream().flush()
        // the client reads nothing: 64 MiB cannot leave the server, so the answer is still being sent
        waitUntil("ffmpeg ran") { starts.size == 1 }
        waitUntil("the slot is back while the file is still being sent") { server.ffmpegInUse() == 0 && server.ffmpegTokensTracked() == 0 }
    }
}
