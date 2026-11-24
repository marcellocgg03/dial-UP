package dev.t2u.dial

// Finding 15 (plan 09-34): startSuspending binds like the blocking start() and reads the bound port without runBlocking.
// GPL-3.0-only.

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DialServerStartSuspendingTest {
    @Test
    fun port0BindsAnEphemeralPortAndAnswers() = runBlocking<Unit> {
        val server = DialServer(DialConfig("susp", "u-susp", "127.0.0.1", 0)) {}
        server.startSuspending()
        try {
            val port = server.resolvedPort()
            assertTrue(port > 0, "the real port is reported, got $port")
            val r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/ytcr/ssdp/device-desc.xml")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, r.statusCode())
            assertTrue(r.body().contains("<friendlyName>susp</friendlyName>"), r.body())
        } finally {
            server.stop()
        }
    }

    @Test
    fun aBusyPortThrowsBindExceptionAndCanBeRetried() = runBlocking<Unit> {
        val blocker = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = blocker.localPort
        val server = DialServer(DialConfig("busy", "u-busy-susp", "127.0.0.1", port)) {}
        try {
            val failure = runCatching { server.startSuspending() }.exceptionOrNull()
            assertTrue(failure is BindException, "startSuspending() must throw BindException, got $failure")
            blocker.close()
            server.startSuspending()
            assertEquals(port, server.resolvedPort())
        } finally {
            server.stop()
            blocker.close()
        }
    }

    private fun describe(port: Int): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/ytcr/ssdp/device-desc.xml")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun aStopThatThrowsStillLeavesTheServerStartable() = runBlocking<Unit> {
        val server = DialServer(DialConfig("stopthrow", "u-stopthrow", "127.0.0.1", 0)) {}
        server.startSuspending()
        var calls = 0
        server.engineStop = { engine ->
            calls++
            engine.stop(0, 0)
            if (calls == 1) throw IllegalStateException("ktor stop failed")
        }
        try {
            val failure = runCatching { server.stop() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException, "the stop failure still reaches the caller, got $failure")
            server.startSuspending()
            assertEquals(200, describe(server.resolvedPort()).statusCode())
        } finally {
            server.stop()
        }
    }

    @Test
    fun aFailureAfterTheStartIsVisible() = runBlocking<Unit> {
        val server = DialServer(DialConfig("failure", "u-failure", "127.0.0.1", 0)) {}
        server.startSuspending()
        val scope = CoroutineScope(SupervisorJob() + server.failureHandler)
        try {
            assertNull(server.failure(), "no failure before one happens")
            val died = IOException("accept died")
            scope.launch { throw died }
            val deadline = System.nanoTime() + 2_000_000_000L
            while (server.failure() == null && System.nanoTime() < deadline) Thread.sleep(10)
            assertSame(died, server.failure(), "the failure the engine reported is kept")
        } finally {
            scope.cancel()
            server.stop()
        }
    }

    @Test
    fun aNewStartClearsTheOldFailure() = runBlocking<Unit> {
        val server = DialServer(DialConfig("clear", "u-clear", "127.0.0.1", 0)) {}
        server.startSuspending()
        val scope = CoroutineScope(SupervisorJob() + server.failureHandler)
        try {
            scope.launch { throw IOException("accept died") }
            val deadline = System.nanoTime() + 2_000_000_000L
            while (server.failure() == null && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(server.failure() != null, "the failure was recorded")
            server.stop()
            server.startSuspending()
            assertNull(server.failure(), "a fresh start forgets the old failure")
        } finally {
            scope.cancel()
            server.stop()
        }
    }
}
