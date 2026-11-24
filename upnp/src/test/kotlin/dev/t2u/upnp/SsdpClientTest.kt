package dev.t2u.upnp

// Port of the ssdp.js parsing test from test/unit.test.js plus the search-socket behaviour of the
// Ssdp client (DISC-01). Everything runs on loopback; the "speaker" is a unicast socket the client
// is pointed at through searchTarget, so no test puts multicast on a real network. GPL-3.0-only.

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.io.IOException
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    @DisplayName("an announced max-age is capped at one day")
    fun maxAgeIsCappedAtOneDay() {
        assertEquals(86_400, maxAge("max-age=2147483647", 1800))
        assertEquals(86_400, maxAge("max-age=99999999999", 1800))
        assertEquals(86_400, maxAge("max-age=99999999999999999999999999", 1800))
        assertEquals(86_400, maxAge("max-age=86401", 1800))
        assertEquals(86_400, maxAge("max-age=86400", 1800))
        assertEquals(1800, maxAge("max-age=1800, no-cache", 0))
        assertEquals(1800, maxAge(null, 1800))
        assertEquals(1800, maxAge("no-cache", 1800))
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
    @DisplayName("searchAt sends one M-SEARCH to the given address")
    fun searchAtSendsOneSearchToTheGivenAddress() {
        // the client's own target is a dead address; only searchAt may reach the speaker (unicast, from a loopback socket)
        FakeSpeaker(loopback, listOf(reply("200 OK"))).use { speaker ->
            val events = Collected()
            val ssdp = Ssdp(listOf(loopback), events, InetSocketAddress(loopback, 9))
            try {
                ssdp.start()
                ssdp.searchAt(speaker.address, RENDERER_ST, 1)
                assertTrue(awaitTrue { speaker.requests.isNotEmpty() })
                Thread.sleep(100)
                assertEquals(1, speaker.requests.size)
                val text = speaker.requests.single()
                assertTrue(text.startsWith("M-SEARCH * HTTP/1.1\r\n"))
                assertTrue(text.contains("ST: $RENDERER_ST\r\n"))
                assertTrue(text.contains("MX: 1\r\n"))
                assertTrue(awaitTrue { events.alive.isNotEmpty() }, "the unicast reply reaches onAlive like any search reply")
            } finally {
                ssdp.stop()
            }
        }
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

    @Test
    @DisplayName("isBound is true only while a search socket is open: before start, after stop and after a failed bind it is false")
    fun isBoundFollowsTheSearchSockets() {
        val events = Collected()
        val ssdp = Ssdp(listOf(loopback), events)
        assertFalse(ssdp.isBound(), "before start")
        ssdp.start()
        try {
            assertTrue(ssdp.isBound(), "after start on a local address")
        } finally {
            ssdp.stop()
        }
        assertFalse(ssdp.isBound(), "after stop")

        // a bind address that is not local: start does not throw, the error goes to onError and the client is deaf
        val failing = Collected()
        val deaf = Ssdp(listOf(InetAddress.getByName("203.0.113.9")), failing)
        deaf.start()
        try {
            assertFalse(deaf.isBound(), "a client whose every bind failed is running but not bound")
            assertTrue(failing.errors.any { it.startsWith("bind:") }, "the bind failure is reported: ${failing.errors}")
        } finally {
            deaf.stop()
        }
    }

    @Test
    @DisplayName("a search on a stopped or never started client is a no-op that does not throw")
    fun searchOnAStoppedClientIsHarmless() {
        FakeSpeaker(loopback, listOf(reply("200 OK"))).use { speaker ->
            val events = Collected()
            val ssdp = Ssdp(listOf(loopback), events, speaker.address)
            ssdp.search(RENDERER_ST)
            ssdp.start()
            ssdp.stop()
            ssdp.search(RENDERER_ST)
            Thread.sleep(200)
            assertTrue(speaker.requests.isEmpty(), "a stopped client sends nothing: ${speaker.requests}")
            assertTrue(events.errors.isEmpty(), "and reports nothing: ${events.errors}")
        }
    }

    /** A search socket whose receive throws [failures] times before it delegates to the real receive. */
    private class FlakySocket(addr: SocketAddress, private val failures: Int) : MulticastSocket(addr) {
        @Volatile var thrown = 0

        override fun receive(p: DatagramPacket) {
            if (thrown < failures) {
                thrown++
                throw IOException("bad packet")
            }
            super.receive(p)
        }
    }

    @Test
    @DisplayName("a receive error is reported once and the loop keeps listening, so a later reply still reaches onAlive")
    fun aReceiveErrorIsReportedOnceAndTheLoopKeepsListening() {
        FakeSpeaker(loopback, listOf(reply("200 OK"))).use { speaker ->
            val events = Collected()
            val ssdp = Ssdp(
                listOf(loopback),
                events,
                speaker.address,
                socketFactory = { FlakySocket(InetSocketAddress(it, 0), failures = 2) },
                receiveBackoffMs = 1,
            )
            try {
                ssdp.start()
                ssdp.search(RENDERER_ST)
                assertTrue(awaitTrue { events.alive.isNotEmpty() }, "the reply after the bad traffic reaches onAlive: ${events.errors}")
                assertEquals(1, events.errors.size, "only the first failure of the run is reported: ${events.errors}")
                assertTrue(events.errors.single().startsWith("receive:"), events.errors.toString())
                assertTrue(ssdp.isBound(), "the client still listens")
            } finally {
                ssdp.stop()
            }
        }
    }

    /** A search socket whose receive always throws; [noClose] makes close() a no-op, so isClosed stays false. */
    private class DeadSocket(addr: SocketAddress, private val noClose: Boolean = false) : MulticastSocket(addr) {
        override fun receive(p: DatagramPacket) = throw IOException("bad packet")

        override fun close() {
            if (!noClose) super.close()
        }

        fun closeForReal() = super.close()
    }

    @Test
    @DisplayName("a socket that fails on every receive gives up after the cap, reports it once and is no longer bound")
    fun aSocketThatAlwaysFailsGivesUpAndIsNoLongerBound() {
        val events = Collected()
        var created: DeadSocket? = null
        val ssdp = Ssdp(
            listOf(loopback),
            events,
            socketFactory = { DeadSocket(InetSocketAddress(it, 0)).also { s -> created = s } },
            receiveBackoffMs = 1,
            maxReceiveFailures = 3,
        )
        try {
            ssdp.start()
            assertTrue(awaitTrue { !ssdp.isBound() }, "the client reports itself unbound after the give-up: ${events.errors}")
            assertEquals(1, events.errors.count { it.startsWith("receive:") }, events.errors.toString())
            assertEquals(1, events.errors.count { it.startsWith("receive-gave-up:") }, events.errors.toString())
            assertEquals(2, events.errors.size, events.errors.toString())
            assertTrue(created!!.isClosed, "the socket is closed")
        } finally {
            ssdp.stop()
        }
    }

    @Test
    @DisplayName("isBound needs a live receive thread: a socket that still looks open does not make a dead client bound")
    fun aDeadThreadIsNotBoundEvenWhenItsSocketLooksOpen() {
        val events = Collected()
        var created: DeadSocket? = null
        val ssdp = Ssdp(
            listOf(loopback),
            events,
            socketFactory = { DeadSocket(InetSocketAddress(it, 0), noClose = true).also { s -> created = s } },
            receiveBackoffMs = 1,
            maxReceiveFailures = 3,
        )
        try {
            ssdp.start()
            assertTrue(awaitTrue { events.errors.any { it.startsWith("receive-gave-up:") } }, events.errors.toString())
            assertFalse(created!!.isClosed, "the socket looks open")
            assertTrue(awaitTrue { !ssdp.isBound() }, "but its thread is gone, so the client is not bound")
        } finally {
            ssdp.stop()
            created?.closeForReal()
        }
    }

    @Test
    @DisplayName("stop during a back-off returns promptly and the stopping client reports nothing more")
    fun stopDuringTheBackOffReturnsPromptly() {
        val events = Collected()
        val ssdp = Ssdp(
            listOf(loopback),
            events,
            socketFactory = { DeadSocket(InetSocketAddress(it, 0)) },
            receiveBackoffMs = 1000,
            maxReceiveFailures = 100,
        )
        ssdp.start()
        try {
            assertTrue(awaitTrue { events.errors.isNotEmpty() }, "the first failure is reported")
        } finally {
            val started = System.currentTimeMillis()
            ssdp.stop()
            val took = System.currentTimeMillis() - started
            assertTrue(took < 2000, "stop returned in $took ms")
        }
        assertEquals(1, events.errors.size, events.errors.toString())
    }
}
