package dev.t2u.media

// CR-01 b (plan 07-19): a port another listener still holds is a BindException thrown to the caller of start() or
// rebindHost(), never an uncaught exception in Ktor's accept job (which ends the process). GPL-3.0-only.

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaServerBindTest {
    @TempDir lateinit var dir: File

    private val http: HttpClient = HttpClient.newHttpClient()
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previous: Thread.UncaughtExceptionHandler? = null
    private var server: MediaServer? = null
    private val blockers = mutableListOf<ServerSocket>()

    @BeforeEach
    fun installRecorder() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
    }

    @AfterEach
    fun tearDown() {
        server?.stop()
        blockers.forEach { runCatching { it.close() } }
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    private fun hold(host: String, port: Int = 0): ServerSocket =
        ServerSocket(port, 50, InetAddress.getByName(host)).also { blockers.add(it) }

    private fun newServer(host: String, port: Int): MediaServer =
        MediaServer(host, port, null, {}, File(dir, "buffer").also { it.mkdirs() }).also { server = it }

    private fun status(host: String, port: Int, path: String): Int =
        http.send(HttpRequest.newBuilder(URI("http://$host:$port$path")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    private fun token(s: MediaServer): String =
        s.register(StreamEntry(StreamSource.VideoPipe(VideoEntry("aaaaaaaaaaa", "adts")), "adts", "audio/aac", "unit"))

    @Test
    fun startOnABusyPortThrowsAndNothingIsUncaught() {
        val blocker = hold("127.0.0.1")
        val s = newServer("127.0.0.1", blocker.localPort)
        val failure = runCatching { s.start() }.exceptionOrNull()
        assertTrue(failure is BindException, "start() must throw BindException, got $failure")
        Thread.sleep(1000)
        assertTrue(uncaught.isEmpty(), "nothing uncaught, got $uncaught")
        assertEquals(false, s.isRunning())
    }

    @Test
    fun aFailedStartCanBeRepeated() {
        val blocker = hold("127.0.0.1")
        val port = blocker.localPort
        val s = newServer("127.0.0.1", port)
        assertTrue(runCatching { s.start() }.exceptionOrNull() is BindException)
        blocker.close()
        s.start()
        assertEquals(port, s.resolvedPort())
        val path = token(s)
        assertEquals(501, status("127.0.0.1", port, path), "an adts token without a launcher answers 501")
        assertTrue(uncaught.isEmpty(), "nothing uncaught, got $uncaught")
    }

    @Test
    fun aRebindOntoABusyAddressThrowsAndCanBeRepeated() {
        val s = newServer("127.0.0.1", 0)
        s.start()
        val port = s.resolvedPort()
        val path = token(s)
        val blocker = hold("127.0.0.2", port)

        val failure = runCatching { s.rebindHost("127.0.0.2") }.exceptionOrNull()
        assertTrue(failure is BindException, "rebindHost() must throw BindException, got $failure")
        Thread.sleep(1000)
        assertTrue(uncaught.isEmpty(), "nothing uncaught, got $uncaught")

        blocker.close()
        s.rebindHost("127.0.0.2")
        assertEquals(port, s.resolvedPort())
        assertEquals(501, status("127.0.0.2", port, path), "the token registered before the failed rebind is served")
        assertTrue(uncaught.isEmpty(), "nothing uncaught after the retry, got $uncaught")
    }
}
