package dev.t2u.media

// SVC-04 (plan 05-07): MediaServer.rebindHost restarts only the HTTP engine on a new host and the same port, so the
// stream URLs already handed to a speaker keep working after a network move. Loopback stands in for Wi-Fi: a socket bound
// to 127.0.0.1 refuses connections on 127.0.0.2. GPL-3.0-only.

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaServerRebindTest {
    @TempDir lateinit var dir: File

    private val http: HttpClient = HttpClient.newHttpClient()
    private var server: MediaServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop()
    }

    private fun started(): MediaServer =
        MediaServer("127.0.0.1", 0, null, {}, File(dir, "buffer").also { it.mkdirs() }).also {
            it.start()
            server = it
        }

    private fun status(host: String, port: Int, path: String): Int =
        http.send(HttpRequest.newBuilder(URI("http://$host:$port$path")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    private fun refuses(host: String, port: Int) {
        assertFailsWith<ConnectException> { Socket().use { it.connect(InetSocketAddress(host, port), 1000) } }
    }

    private val unknown = "/stream/${"0".repeat(32)}.aac"

    @Test
    fun aTokenRegisteredBeforeTheMoveAnswersAfterIt() {
        val s = started()
        val port = s.resolvedPort()
        val path = s.register(StreamEntry(StreamSource.VideoPipe(VideoEntry("aaaaaaaaaaa", "adts")), "adts", "audio/aac", "unit"))
        assertEquals(501, status("127.0.0.1", port, path), "an adts token without a launcher answers 501")
        assertEquals(404, status("127.0.0.1", port, unknown))
        val tokens = s.tokenCount()
        val stats = s.rangeCacheStats()

        s.rebindHost("127.0.0.2")

        assertEquals(port, s.resolvedPort(), "the port is kept")
        assertEquals(501, status("127.0.0.2", port, path), "the token survived the move")
        assertEquals(404, status("127.0.0.2", port, unknown))
        refuses("127.0.0.1", port)
        assertEquals(tokens, s.tokenCount())
        assertEquals(stats, s.rangeCacheStats())
        assertEquals(true, s.isRunning())
    }

    @Test
    fun aSecondMoveBackWorks() {
        val s = started()
        val port = s.resolvedPort()
        val path = s.register(StreamEntry(StreamSource.VideoPipe(VideoEntry("aaaaaaaaaaa", "adts")), "adts", "audio/aac", "unit"))
        s.rebindHost("127.0.0.2")
        s.rebindHost("127.0.0.1")
        assertEquals(port, s.resolvedPort())
        assertEquals(501, status("127.0.0.1", port, path))
        refuses("127.0.0.2", port)
    }

    @Test
    fun aServerThatWasNeverStartedCannotMove() {
        val s = MediaServer("127.0.0.1", 0, null, {})
        assertFailsWith<IllegalStateException> { s.rebindHost("127.0.0.2") }
    }
}
