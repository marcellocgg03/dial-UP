package dev.t2u.dial

// Finding 15 (plan 09-34): startSuspending binds like the blocking start() and reads the bound port without runBlocking.
// GPL-3.0-only.

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
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
}
