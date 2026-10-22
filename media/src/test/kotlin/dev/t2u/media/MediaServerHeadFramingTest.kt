package dev.t2u.media

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan 03-27: the HEAD answer for a pipe token has no length, so it is chunked, and Ktor CIO writes the chunked
 * terminator ("0" CRLF CRLF) after its headers although a HEAD answer has no body. On a kept-alive connection a client
 * reads those bytes as the start of its next response: the JDK client throws `Invalid status line: "0"` (the BridgeTest
 * flake found in plan 03-22, once in about 30 runs). CIO cannot send this HEAD answer without them (NoContent adds
 * Content-Length: 0, Transfer-Encoding is an engine-only header), so the answer ends its connection: a client that honours
 * `Connection: close` never reads the terminator as a response.
 */
class MediaServerHeadFramingTest {
    private lateinit var server: MediaServer

    @BeforeEach
    fun setUp() {
        server = MediaServer("127.0.0.1", 0, null, {})
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    private fun pipeToken(): String =
        server.register(StreamEntry(StreamSource.PipeSource(listOf("-i", "unused")), "adts", "audio/aac", "unit-head"))

    @Test
    fun aPipeHeadAnswerTellsTheClientToCloseTheConnectionAfterIt() {
        val path = pipeToken()
        Socket("127.0.0.1", server.resolvedPort()).use { s ->
            s.soTimeout = 10_000
            val input = s.getInputStream().buffered()
            s.getOutputStream().apply {
                write("HEAD $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val (status, headers) = RawHttp.readHead(input)
            assertEquals(200, status)
            assertTrue(headers.any { it.first.equals("Connection", true) && it.second.equals("close", true) }, "headers were $headers")
        }
    }
}
