package dev.t2u.upnp

// Port of the ssdp.js parsing test from test/unit.test.js plus the search-socket behaviour of the
// Ssdp client (DISC-01). Everything runs on loopback; the "speaker" is a unicast socket the client
// is pointed at through searchTarget, so no test puts multicast on a real network. GPL-3.0-only.

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SsdpClientTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

    @Test
    @DisplayName("SSDP parsing handles CRLF, case and missing headers")
    fun parsingHandlesCrlfCaseAndMissingHeaders() {
        val bytes = (
            "NOTIFY * HTTP/1.1\r\nHost: 239.255.255.250:1900\r\ncache-control: max-age=200\r\n" +
                "Location: http://192.168.1.15:51161/x.xml\r\nNTS: ssdp:alive\r\n" +
                "USN: uuid:93B2ABAC-cb6a::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val msg = parseMessage(bytes)
        assertEquals("NOTIFY * HTTP/1.1", msg.startLine)
        assertEquals("http://192.168.1.15:51161/x.xml", msg.headers["LOCATION"])
        assertEquals(200, maxAge(msg.headers["CACHECONTROL"], 1))
        assertEquals(1800, maxAge(null, 1800))
        assertEquals("93b2abac-cb6a", udnFromUsn(msg.headers["USN"]))
        assertNull(udnFromUsn(null))
    }

    @Test
    @DisplayName("maxAge and udnFromUsn tolerate spacing, case and junk")
    fun maxAgeAndUdnTolerateJunk() {
        assertEquals(1800, maxAge("max-age=1800, no-cache", 1))
        assertEquals(60, maxAge("no-cache, MAX-AGE = 60", 1))
        assertEquals(7, maxAge("no-cache", 7))
        assertEquals("abcd", udnFromUsn("UUID:ABCD::urn:x"))
        assertEquals("abcd", udnFromUsn("uuid:abcd"))
        assertNull(udnFromUsn("urn:no-uuid-here"))
    }

    @Test
    @DisplayName("buildSearch writes the ssdp.js M-SEARCH text with CRLF line ends")
    fun buildSearchText() {
        val text = String(buildSearch(RENDERER_ST), Charsets.UTF_8)
        assertEquals(
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",
            text,
        )
        assertTrue(String(buildSearch("ssdp:all", 5), Charsets.UTF_8).contains("MX: 5\r\nST: ssdp:all\r\n"))
    }

    private class Collected : SsdpClientEvents {
        val alive = CopyOnWriteArrayList<Pair<Map<String, String>, InetSocketAddress>>()
        val byebye = CopyOnWriteArrayList<Map<String, String>>()
        val errors = CopyOnWriteArrayList<String>()
        var throwOnAlive = 0

        override fun onAlive(headers: Map<String, String>, src: InetSocketAddress) {
            if (throwOnAlive > 0) {
                throwOnAlive--
                throw IllegalStateException("boom")
            }
            alive.add(headers to src)
        }

        override fun onByebye(headers: Map<String, String>, src: InetSocketAddress) {
            byebye.add(headers)
        }

        override fun onError(where: String, t: Throwable) {
            errors.add(where)
        }
    }

    /** A unicast stand-in for the SSDP group: answers each datagram it receives with [replies]. */
    private class FakeSpeaker(loopback: InetAddress, private val replies: List<String>) : AutoCloseable {
        val socket = DatagramSocket(0, loopback).apply { soTimeout = 200 }
        val requests = CopyOnWriteArrayList<String>()

        @Volatile private var running = true
        private val thread = Thread {
            val buf = ByteArray(2048)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(p)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                requests.add(String(p.data, 0, p.length, Charsets.UTF_8))
                for (r in replies) {
                    val b = r.toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(b, b.size, p.address, p.port))
                }
            }
        }.apply { isDaemon = true; start() }

        val address: InetSocketAddress get() = InetSocketAddress(socket.localAddress, socket.localPort)

        override fun close() {
            running = false
            socket.close()
            thread.join(2000)
        }
    }

    private fun reply(status: String, usn: String = "uuid:Fake::urn:schemas-upnp-org:device:MediaRenderer:1") =
        "HTTP/1.1 $status\r\nST: $RENDERER_ST\r\nUSN: $usn\r\nLOCATION: http://127.0.0.1:1400/x.xml\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n\r\n"

    private fun awaitTrue(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    @Test
    @DisplayName("a 200 reply becomes onAlive and a 404 reply is ignored")
    fun status200OnlyBecomesAlive() {
        FakeSpeaker(loopback, listOf(reply("404 Not Found"), reply("200 OK"))).use { speaker ->
            val events = Collected()
            val ssdp = Ssdp(listOf(loopback), events, speaker.address)
            try {
                ssdp.start()
                ssdp.search(RENDERER_ST)
                assertTrue(awaitTrue { events.alive.isNotEmpty() })
                Thread.sleep(100)
                assertEquals(1, events.alive.size)
                assertEquals("http://127.0.0.1:1400/x.xml", events.alive[0].first["LOCATION"])
                assertEquals(loopback, events.alive[0].second.address)
                assertTrue(speaker.requests.single().startsWith("M-SEARCH * HTTP/1.1\r\n"))
            } finally {
                ssdp.stop()
            }
        }
    }

    @Test
    @DisplayName("a handler that throws does not stop later replies")
    fun throwingHandlerKeepsReceiveLoopAlive() {
        FakeSpeaker(loopback, listOf(reply("200 OK"))).use { speaker ->
            val events = Collected().apply { throwOnAlive = 1 }
            val ssdp = Ssdp(listOf(loopback), events, speaker.address)
            try {
                ssdp.start()
                ssdp.search(RENDERER_ST)
                assertTrue(awaitTrue { events.errors.isNotEmpty() })
                ssdp.search(RENDERER_ST)
                assertTrue(awaitTrue { events.alive.isNotEmpty() })
            } finally {
                ssdp.stop()
            }
        }
    }

    @Test
    @DisplayName("NOTIFY alive and byebye are dispatched from onNotify and anything else is ignored")
    fun notifyDispatch() {
        val events = Collected()
        val ssdp = Ssdp(emptyList(), events)
        val src = InetSocketAddress(loopback, 1900)
        fun notify(nts: String?, start: String = "NOTIFY * HTTP/1.1") = parseMessage(
            (start + "\r\n" + (if (nts != null) "NTS: $nts\r\n" else "") + "USN: uuid:x::y\r\n\r\n").toByteArray(),
        )
        ssdp.onNotify(src, notify("ssdp:alive"))
        ssdp.onNotify(src, notify("ssdp:byebye"))
        ssdp.onNotify(src, notify("ssdp:update"))
        ssdp.onNotify(src, notify(null))
        ssdp.onNotify(src, notify("ssdp:alive", start = "M-SEARCH * HTTP/1.1"))
        assertEquals(1, events.alive.size)
        assertEquals(1, events.byebye.size)
        assertTrue(events.errors.isEmpty())
    }

    @Test
    @DisplayName("an onNotify handler that throws is reported to onError, not thrown")
    fun notifyHandlerExceptionsGoToOnError() {
        val events = Collected().apply { throwOnAlive = 1 }
        val ssdp = Ssdp(emptyList(), events)
        ssdp.onNotify(
            InetSocketAddress(loopback, 1900),
            parseMessage("NOTIFY * HTTP/1.1\r\nNTS: ssdp:alive\r\n\r\n".toByteArray()),
        )
        assertEquals(listOf("notify"), events.errors.toList())
    }
}
