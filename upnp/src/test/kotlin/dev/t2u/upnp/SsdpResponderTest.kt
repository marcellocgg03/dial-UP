package dev.t2u.upnp

import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SVC-09: the responder's reply and announce behaviour follows a policy, and one device can be
 * withdrawn with its own byebye. Everything runs on loopback; NOTIFY goes to a unicast socket
 * through notifyTarget, so no test puts multicast on a real network.
 */
class SsdpResponderTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val dialSt = "urn:dial-multiscreen-org:service:dial:1"
    private val uuidA = "aaaaaaaa-0000-4000-8000-000000000001"
    private val uuidB = "bbbbbbbb-0000-4000-8000-000000000002"

    private inner class Harness(
        replyPolicy: (InetAddress) -> Boolean = { true },
        announcePolicy: () -> Boolean = { true },
    ) : AutoCloseable {
        val notifySock = DatagramSocket(0, loopback).apply { soTimeout = 1000 }
        val drops = CopyOnWriteArrayList<String>()
        val responder = SsdpResponder(
            listOf(ListenSpec("loop", loopback, 0, null, false)),
            loopback,
            null,
            object : SsdpEvents {
                override fun onDrop(src: InetSocketAddress, reason: String) {
                    drops.add(reason)
                }
            },
            replyPolicy = replyPolicy,
            announcePolicy = announcePolicy,
            notifyTarget = InetSocketAddress(loopback, notifySock.localPort),
        )

        fun device(uuid: String, name: String = "dev-$uuid") =
            SsdpDevice(name, uuid, "Linux/test UPnP/1.1 t2u/0.0.1", "http://127.0.0.1:3000/ytcr/ssdp/device-desc.xml?u=$uuid")

        fun start(vararg devices: SsdpDevice): Harness {
            devices.forEach { responder.register(it) }
            responder.start()
            return this
        }

        /** Sends an M-SEARCH for [st] from a fresh loopback socket and returns every reply within [waitMs]. */
        fun search(st: String, waitMs: Int = 1000): List<SsdpMessage> {
            val port = responder.boundPorts().getValue("loop")
            DatagramSocket(0, loopback).use { client ->
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\nUSER-AGENT: t2u-test\r\n\r\n"
                val bytes = msg.toByteArray()
                client.send(DatagramPacket(bytes, bytes.size, loopback, port))
                return receiveAll(client, waitMs)
            }
        }

        fun notifies(waitMs: Int = 1000) = receiveAll(notifySock, waitMs)

        override fun close() {
            responder.stop()
            notifySock.close()
        }
    }

    private fun receiveAll(sock: DatagramSocket, waitMs: Int): List<SsdpMessage> {
        val out = mutableListOf<SsdpMessage>()
        sock.soTimeout = waitMs
        val buf = ByteArray(4096)
        while (true) {
            val p = DatagramPacket(buf, buf.size)
            try {
                sock.receive(p)
            } catch (_: SocketTimeoutException) {
                return out
            }
            out.add(parseMessage(p.data, p.length))
        }
    }

    @Test
    fun replyPolicyFalseDropsTheSearchWithReasonAccess() {
        Harness(replyPolicy = { false }).use { h ->
            h.start(h.device(uuidA))
            val replies = h.search(dialSt, waitMs = 1000)
            assertTrue(replies.isEmpty(), "no reply to a refused source: $replies")
            assertEquals(listOf("access"), h.drops.toList(), "exactly one drop for the one datagram")
        }
    }

    @Test
    fun replyPolicyTrueAnswersAsInPhase1() {
        Harness(replyPolicy = { true }).use { h ->
            val dev = h.device(uuidA)
            h.start(dev)
            val reply = h.search(dialSt).single()
            assertEquals("HTTP/1.1 200 OK", reply.startLine)
            assertEquals(dev.location, reply.headers["LOCATION"])
            assertEquals(dialSt, reply.headers["ST"])
            assertEquals("uuid:$uuidA::$dialSt", reply.headers["USN"])
            assertTrue(h.drops.isEmpty())
        }
    }

    @Test
    fun replyPolicyReceivesTheSourceAddress() {
        val seen = CopyOnWriteArrayList<InetAddress>()
        Harness(replyPolicy = { seen.add(it); true }).use { h ->
            h.start(h.device(uuidA))
            h.search(dialSt)
            assertEquals(listOf(loopback), seen.toList())
        }
    }

    @Test
    fun announcePolicyFalseSendsNothingAndTrueSendsFivePerDevice() {
        var allowed = false
        Harness(announcePolicy = { allowed }).use { h ->
            h.start(h.device(uuidA), h.device(uuidB))
            h.responder.announce()
            assertTrue(h.notifies(500).isEmpty(), "phone mode: no multicast alive")

            allowed = true
            h.responder.announce()
            val sent = h.notifies(1000)
            assertEquals(10, sent.size, "5 types x 2 devices")
            assertTrue(sent.all { it.startLine == "NOTIFY * HTTP/1.1" && it.headers["NTS"] == "ssdp:alive" })
            assertEquals(5, sent.count { it.headers.getValue("USN").startsWith("uuid:$uuidA") })
            assertEquals(5, sent.count { it.headers.getValue("USN").startsWith("uuid:$uuidB") })
        }
    }

    @Test
    fun withdrawSendsFiveByebyesForThatDeviceAndKeepsAnsweringForTheOther() {
        Harness().use { h ->
            h.start(h.device(uuidA), h.device(uuidB))
            h.responder.withdraw(uuidA)
            val sent = h.notifies(1000)
            assertEquals(5, sent.size, sent.toString())
            assertTrue(sent.all { it.headers["NTS"] == "ssdp:byebye" && it.headers.getValue("USN").startsWith("uuid:$uuidA") })

            val replies = h.search("ssdp:all")
            assertEquals(1, replies.size, "only B answers: $replies")
            assertTrue(replies.single().headers.getValue("USN").startsWith("uuid:$uuidB"))
        }
    }

    @Test
    fun withdrawAndStopSendByebyeEvenWhenAnnouncingIsOff() {
        val h = Harness(announcePolicy = { false })
        try {
            h.start(h.device(uuidA), h.device(uuidB))
            h.responder.withdraw(uuidA)
            assertEquals(5, h.notifies(1000).size, "byebye only removes cache entries, so it is always sent")
            h.responder.stop()
            val rest = h.notifies(1000)
            assertEquals(5, rest.size, "stop() says goodbye for the remaining device")
            assertTrue(rest.all { it.headers["NTS"] == "ssdp:byebye" && it.headers.getValue("USN").startsWith("uuid:$uuidB") })
        } finally {
            h.close()
        }
    }

    @Test
    fun withdrawOfAnUnknownDeviceSendsNothing() {
        Harness().use { h ->
            h.start(h.device(uuidA))
            h.responder.withdraw("cccccccc-0000-4000-8000-000000000003")
            assertTrue(h.notifies(300).isEmpty())
        }
    }

    @Test
    fun withoutNotifyTargetALoopbackResponderStillSendsNoMulticast() {
        // the Phase 1 guard: a responder bound to loopback never multicasts, so JVM tests stay off the network
        val responder = SsdpResponder(listOf(ListenSpec("loop", loopback, 0, null, false)), loopback, null, object : SsdpEvents {})
        responder.register(SsdpDevice("x", uuidA, "s", "http://127.0.0.1/"))
        responder.start()
        try {
            responder.announce()
        } finally {
            responder.stop()
        }
    }

    // ------------------------------------------------------------------------------------------ self-probe (05-02)

    private class ProbeEvents : SsdpEvents {
        val received = CopyOnWriteArrayList<String>()
        val replies = CopyOnWriteArrayList<String>()
        val drops = CopyOnWriteArrayList<String>()

        override fun onReceive(specId: String, src: InetSocketAddress, msg: SsdpMessage, raw: String) {
            received.add(msg.headers["ST"].orEmpty())
        }

        override fun onReply(dest: InetSocketAddress, st: String, usn: String, deviceName: String) {
            replies.add(st)
        }

        override fun onDrop(src: InetSocketAddress, reason: String) {
            drops.add(reason)
        }
    }

    private fun probeResponder(events: SsdpEvents = object : SsdpEvents {}) =
        SsdpResponder(listOf(ListenSpec("loop", loopback, 0, null, false)), loopback, null, events)

    @Test
    fun selfProbeIsSeenByTheOwnListenerAndNeverAnswered() {
        val events = ProbeEvents()
        val responder = probeResponder(events)
        responder.register(SsdpDevice("x", uuidA, "s", "http://127.0.0.1/"))
        responder.start()
        try {
            assertTrue(responder.selfProbe(2000), "the listener sees the probe")
            assertTrue(events.received.any { it.startsWith("urn:dev-t2u:probe:") }, "onReceive still fires: ${events.received}")
            val nonce = events.received.first { it.startsWith("urn:dev-t2u:probe:") }.removePrefix("urn:dev-t2u:probe:")
            assertTrue(Regex("[0-9a-f]{16}").matches(nonce), "16 lowercase hex characters: $nonce")
            Thread.sleep(300)
            assertTrue(events.replies.isEmpty(), "a probe is never answered: ${events.replies}")
            assertTrue(events.drops.isEmpty(), "and never logged as a drop: ${events.drops}")
        } finally {
            responder.stop()
        }
    }

    @Test
    fun selfProbeDatagramCarriesNoDeviceNameUuidOrLocation() {
        val sink = DatagramSocket(0, loopback).apply { soTimeout = 1000 }
        val responder = SsdpResponder(
            listOf(ListenSpec("loop", loopback, 0, null, false)),
            loopback,
            null,
            object : SsdpEvents {},
            probeTarget = InetSocketAddress(loopback, sink.localPort),
        )
        responder.register(SsdpDevice("Secret Speaker", uuidA, "s", "http://127.0.0.1:3000/desc.xml"))
        responder.start()
        try {
            // nobody listens at the sink, so the probe times out, but the datagram is what the sink receives
            assertEquals(false, responder.selfProbe(300))
            val got = receiveAll(sink, 500).single()
            assertEquals("M-SEARCH * HTTP/1.1", got.startLine)
            assertTrue(got.headers.getValue("ST").startsWith("urn:dev-t2u:probe:"))
            val text = got.headers.entries.joinToString { "${it.key}=${it.value}" }
            assertTrue("Secret" !in text && uuidA !in text && "3000" !in text, "no identity in the probe: $text")
        } finally {
            responder.stop()
            sink.close()
        }
    }

    @Test
    fun aDeafListenerFailsTheProbeAndRestartListenersRepairsIt() {
        val responder = probeResponder()
        responder.start()
        try {
            val before = responder.boundPorts().getValue("loop")
            assertTrue(responder.selfProbe(2000))
            responder.closeListenersForTest()
            assertEquals(false, responder.selfProbe(300), "a closed listener hears nothing")
            assertEquals(1, responder.restartListeners())
            val after = responder.boundPorts().getValue("loop")
            assertTrue(after > 0, "the reopened listener has a port (was $before, now $after)")
            assertTrue(responder.selfProbe(2000), "the reopened listener hears the probe")
        } finally {
            responder.stop()
        }
    }

    @Test
    fun selfProbeOnAResponderThatIsNotRunningReturnsFalse() {
        val responder = probeResponder()
        assertEquals(false, responder.selfProbe(100), "never started")
        responder.start()
        responder.stop()
        assertEquals(false, responder.selfProbe(100), "stopped")
        assertEquals(0, responder.restartListeners(), "a stopped responder does not reopen anything")
    }

    // ------------------------------------------------------------------------------------------ reply socket repair (05-15)

    private fun realReplySocket(bind: InetAddress, iface: NetworkInterface?): MulticastSocket =
        MulticastSocket(InetSocketAddress(bind, 0)).apply {
            timeToLive = 4
            if (iface != null) networkInterface = iface
        }

    private class ErrorEvents : SsdpEvents {
        val errors = CopyOnWriteArrayList<String>()

        override fun onError(where: String, t: Throwable) {
            errors.add(where)
        }
    }

    private fun responderWith(events: SsdpEvents, factory: (InetAddress, NetworkInterface?) -> MulticastSocket) =
        SsdpResponder(
            listOf(ListenSpec("loop", loopback, 0, null, false)),
            loopback,
            null,
            events,
            openReplySocket = factory,
        )

    @Test
    fun aReplySocketThatFailedAtStartIsCreatedByRestartListeners() {
        val events = ErrorEvents()
        var calls = 0
        val responder = responderWith(events) { b, i ->
            if (calls++ == 0) throw java.io.IOException("no socket for you") else realReplySocket(b, i)
        }
        responder.register(SsdpDevice("x", uuidA, "s", "http://127.0.0.1/"))
        responder.start()
        try {
            assertEquals(listOf("reply-socket"), events.errors.toList(), "start() reports the failure once and keeps running")
            assertEquals(false, responder.selfProbe(300), "no reply socket, no probe")
            assertEquals(1, responder.restartListeners())
            assertTrue(responder.selfProbe(2000), "the recreated reply socket can probe")

            val port = responder.boundPorts().getValue("loop")
            DatagramSocket(0, loopback).use { client ->
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $dialSt\r\n\r\n"
                val bytes = msg.toByteArray()
                client.send(DatagramPacket(bytes, bytes.size, loopback, port))
                val replies = receiveAll(client, 1000)
                assertEquals(1, replies.size, "the device is answered again: $replies")
                assertEquals(dialSt, replies.single().headers["ST"])
            }
            assertEquals(listOf("reply-socket"), events.errors.toList(), "no further error")
        } finally {
            responder.stop()
        }
    }

    @Test
    fun restartListenersReplacesTheReplySocketEvenWhenOneExists() {
        val made = CopyOnWriteArrayList<MulticastSocket>()
        val responder = responderWith(ErrorEvents()) { b, i -> realReplySocket(b, i).also { made.add(it) } }
        responder.start()
        try {
            assertEquals(1, made.size)
            assertEquals(1, responder.restartListeners())
            assertEquals(2, made.size)
            assertTrue(made[0].isClosed, "the old reply socket is closed")
            assertEquals(false, made[1].isClosed, "the new one is open")
            assertTrue(responder.selfProbe(2000))
        } finally {
            responder.stop()
        }
    }

    @Test
    fun restartListenersOnAStoppedResponderOpensNothing() {
        var calls = 0
        val responder = responderWith(ErrorEvents()) { b, i -> calls++; realReplySocket(b, i) }
        responder.start()
        responder.stop()
        assertEquals(1, calls)
        assertEquals(0, responder.restartListeners())
        assertEquals(1, calls, "the factory is not called for a stopped responder")
    }

    @Test
    fun aFactoryThatFailsOnRestartLeavesTheResponderRunningWithoutAReplySocket() {
        val events = ErrorEvents()
        var calls = 0
        val responder = responderWith(events) { b, i ->
            if (calls++ == 0) realReplySocket(b, i) else throw java.io.IOException("still no socket")
        }
        responder.start()
        try {
            assertEquals(emptyList(), events.errors.toList())
            assertEquals(1, responder.restartListeners(), "the listeners are reopened regardless")
            assertEquals(listOf("reply-socket"), events.errors.toList())
            assertEquals(false, responder.selfProbe(300))
        } finally {
            responder.stop()
        }
    }

    // ------------------------------------------------------------------------------------------ probe reasons (05-15)

    private fun closedReplySocket(b: InetAddress, i: NetworkInterface?): MulticastSocket =
        realReplySocket(b, i).also { it.close() }

    /** One responder per situation; each runs [check] with the probe function under test, then is stopped. */
    private fun forEachProbeSituation(check: (situation: String, expected: ProbeResult, run: (SsdpResponder) -> Unit) -> Unit) {
        // the five situations of probeSaysWhyItFailed, as (name, expected, body that returns when the responder is set up)
        check("before start", ProbeResult.NOT_RUNNING) { }
        check("after stop", ProbeResult.NOT_RUNNING) { it.start(); it.stop() }
        check("no reply socket", ProbeResult.NO_REPLY_SOCKET) { it.start() }
        check("send failed", ProbeResult.SEND_FAILED) { it.start() }
        check("deaf listener", ProbeResult.NOT_HEARD) { it.start(); it.closeListenersForTest() }
        check("healthy", ProbeResult.OK) { it.start() }
    }

    private fun situationResponder(situation: String): SsdpResponder = when (situation) {
        "no reply socket" -> responderWith(ErrorEvents()) { _, _ -> throw java.io.IOException("none") }
        "send failed" -> responderWith(ErrorEvents(), ::closedReplySocket)
        else -> responderWith(ErrorEvents(), ::realReplySocket)
    }

    @Test
    fun probeSaysWhyItFailed() {
        forEachProbeSituation { situation, expected, setup ->
            val responder = situationResponder(situation)
            try {
                setup(responder)
                assertEquals(expected, responder.probe(300), situation)
            } finally {
                responder.stop()
            }
        }
    }

    @Test
    fun selfProbeIsProbeEqualsOk() {
        forEachProbeSituation { situation, expected, setup ->
            val responder = situationResponder(situation)
            try {
                setup(responder)
                assertEquals(expected == ProbeResult.OK, responder.selfProbe(300), situation)
            } finally {
                responder.stop()
            }
        }
    }

    @Test
    fun probeResultWireNamesAreFixedStrings() {
        assertEquals(
            listOf("ok", "not-running", "no-reply-socket", "send-failed", "not-heard"),
            listOf(ProbeResult.OK, ProbeResult.NOT_RUNNING, ProbeResult.NO_REPLY_SOCKET, ProbeResult.SEND_FAILED, ProbeResult.NOT_HEARD).map { it.wire },
        )
        assertEquals(5, ProbeResult.values().size, "no entry outside the five codes")
    }
}
