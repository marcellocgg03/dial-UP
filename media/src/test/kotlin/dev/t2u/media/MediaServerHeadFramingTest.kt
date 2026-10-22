package dev.t2u.media

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan 03-27: a HEAD answer on a pipe token must end at its header block. The head is a chunked one (no length is
 * known), and a chunked terminator ("0" CRLF CRLF) written after the headers of a HEAD answer is a body the client
 * never asked for: on a kept-alive connection it is read as the start of the next response, and the JDK client
 * throws `Invalid status line: "0"` (the BridgeTest flake found in plan 03-22).
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

    private fun send(socket: Socket, request: String) {
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
    }

    @Test
    fun theNextResponseOnAKeptAliveConnectionAfterAPipeHeadStartsWithAStatusLine() {
        val path = server.register(StreamEntry(StreamSource.PipeSource(listOf("-i", "unused")), "adts", "audio/aac", "unit-head"))
        Socket("127.0.0.1", server.resolvedPort()).use { s ->
            s.soTimeout = 10_000
            val input = s.getInputStream().buffered()
            send(s, "HEAD $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            val (headStatus, _) = RawHttp.readHead(input)
            assertEquals(200, headStatus)
            // the second request goes out on the same connection once the HEAD answer's headers were read
            send(s, "GET /stream/${"0".repeat(32)}.aac HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            val next = RawHttp.readLine(input)
            assertTrue(next != null && next.startsWith("HTTP/1.1 404"), "the line after the HEAD headers was: $next")
        }
    }
}
