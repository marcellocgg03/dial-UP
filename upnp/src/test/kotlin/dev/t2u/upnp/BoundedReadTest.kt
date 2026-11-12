package dev.t2u.upnp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** F-01: a LAN device that answers with an endless body cannot make the bridge buffer it. */
class BoundedReadTest {
    /** Serves [headers] and then [chunk] over and over until the client goes away; returns the port. */
    private fun endlessServer(headers: String, chunk: ByteArray, onClosed: () -> Unit): ServerSocket {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            try {
                server.accept().use { s ->
                    s.getInputStream().let { input ->
                        val buf = ByteArray(4096)
                        input.read(buf) // request head (a SOAP body may follow, it does not matter)
                    }
                    val out = s.getOutputStream()
                    out.write(headers.toByteArray())
                    try {
                        while (true) out.write(chunk)
                    } catch (_: IOException) {
                        // the client closed the connection after hitting its cap
                    }
                }
            } catch (_: IOException) {
            } finally {
                onClosed()
            }
        }
        return server
    }

    private val head = "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nConnection: close\r\n\r\n"

    @Test
    fun anEndlessDescriptionFailsWithABoundedIOException() {
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val server = endlessServer(head, ByteArray(64 * 1024) { 'a'.code.toByte() }) { closed.set(true) }
        server.use {
            val e = assertFailsWith<IOException> {
                runBlocking { fetchDescription(lanHttpClient(), "http://127.0.0.1:${server.localPort}/desc.xml", timeoutMs = 10_000) }
            }
            assertTrue("larger than" in e.message.orEmpty(), e.message)
        }
        repeat(50) { if (!closed.get()) Thread.sleep(100) }
        assertTrue(closed.get(), "the connection was dropped once the cap was hit")
    }

    @Test
    fun anEndlessSoapReplyFailsAsANetworkError() {
        val server = endlessServer(head, ByteArray(64 * 1024) { 'a'.code.toByte() }) { }
        server.use {
            val svc = UpnpService("urn:schemas-upnp-org:service:AVTransport:1", "http://127.0.0.1:${server.localPort}/ctl", "", "")
            val e = assertFailsWith<UpnpError> {
                runBlocking { SoapClient(lanHttpClient()).callAction(svc, "Play", timeoutMs = 10_000) }
            }
            assertEquals("ENETWORK", e.code)
        }
    }

    @Test
    fun aDeclaredOversizeLengthIsRefusedWithoutReadingIt() {
        val server = endlessServer(
            "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${MAX_DESCRIPTION_BYTES + 1}\r\n\r\n",
            ByteArray(1024) { 'a'.code.toByte() },
        ) { }
        server.use {
            assertFailsWith<IOException> {
                runBlocking { fetchDescription(lanHttpClient(), "http://127.0.0.1:${server.localPort}/desc.xml", timeoutMs = 10_000) }
            }
        }
    }

    @Test
    fun aDescriptionExactlyAtTheCapIsStillRead() {
        val prefix = """<?xml version="1.0"?><root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>""" +
            "<friendlyName>Cap</friendlyName><UDN>uuid:cap</UDN></device><!--"
        val suffix = "--></root>"
        val body = prefix + "x".repeat(MAX_DESCRIPTION_BYTES.toInt() - prefix.length - suffix.length) + suffix
        assertEquals(MAX_DESCRIPTION_BYTES.toInt(), body.toByteArray().size)
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            try {
                server.accept().use { s ->
                    s.getInputStream().read(ByteArray(4096))
                    s.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n" + body).toByteArray(),
                    )
                }
            } catch (_: IOException) {
            }
        }
        server.use {
            val d = runBlocking { fetchDescription(lanHttpClient(), "http://127.0.0.1:${server.localPort}/desc.xml", timeoutMs = 10_000) }
            assertEquals("Cap", d.friendlyName)
        }
    }
}
