package dev.t2u.media

// Finding 15 (plan 09-34): startSuspending and rebindHostSuspending bind and move like the blocking start() and
// rebindHost(). Loopback stands in for Wi-Fi: a socket bound to 127.0.0.1 refuses connections on 127.0.0.2. GPL-3.0-only.

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.BindException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MediaServerStartSuspendingTest {
    @TempDir lateinit var dir: File

    private val http: HttpClient = HttpClient.newHttpClient()
    private var server: MediaServer? = null
    private val blockers = mutableListOf<ServerSocket>()

    @AfterEach
    fun tearDown() {
        server?.stop()
        blockers.forEach { runCatching { it.close() } }
    }

    private fun newServer(host: String, port: Int): MediaServer =
        MediaServer(host, port, null, {}, File(dir, "buffer").also { it.mkdirs() }).also { server = it }

    private fun status(host: String, port: Int, path: String): Int =
        http.send(HttpRequest.newBuilder(URI("http://$host:$port$path")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    private val unknown = "/stream/${"0".repeat(32)}.aac"

    @Test
    fun startSuspendingBindsAndAnswers() = runBlocking<Unit> {
        val s = newServer("127.0.0.1", 0)
        s.startSuspending()
        assertTrue(s.resolvedPort() > 0)
        assertTrue(s.isListening())
        assertEquals(404, status("127.0.0.1", s.resolvedPort(), unknown))
    }

    @Test
    fun startSuspendingOnABusyPortThrowsAndLeavesNothingRunning() = runBlocking<Unit> {
        val blocker = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).also { blockers.add(it) }
        val s = newServer("127.0.0.1", blocker.localPort)
        val failure = runCatching { s.startSuspending() }.exceptionOrNull()
        assertTrue(failure is BindException, "startSuspending() must throw BindException, got $failure")
        assertEquals(false, s.isRunning())
        assertEquals(false, s.isListening())
    }

    @Test
    fun rebindHostSuspendingMovesToTheNewHostOnTheSamePortAndKeepsTokens() = runBlocking<Unit> {
        val s = newServer("127.0.0.1", 0)
        s.startSuspending()
        val port = s.resolvedPort()
        val path = s.register(StreamEntry(StreamSource.VideoPipe(VideoEntry("aaaaaaaaaaa", "adts")), "adts", "audio/aac", "unit"))
        val tokens = s.tokenCount()

        s.rebindHostSuspending("127.0.0.2")

        assertEquals(port, s.resolvedPort(), "the port is kept")
        assertTrue(s.isListening())
        assertEquals(501, status("127.0.0.2", port, path), "the token survived the move")
        assertEquals(tokens, s.tokenCount())
        assertFailsWith<ConnectException> { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000) } }
    }

    @Test
    fun rebindHostSuspendingOnABusyAddressThrowsAndLeavesItNotListening() = runBlocking<Unit> {
        val s = newServer("127.0.0.1", 0)
        s.startSuspending()
        val port = s.resolvedPort()
        val blocker = ServerSocket(port, 50, InetAddress.getByName("127.0.0.2")).also { blockers.add(it) }

        val failure = runCatching { s.rebindHostSuspending("127.0.0.2") }.exceptionOrNull()

        assertTrue(failure is BindException, "rebindHostSuspending() must throw BindException, got $failure")
        assertEquals(false, s.isListening())
        assertEquals(true, s.isRunning())
        blocker.close()
    }
}
