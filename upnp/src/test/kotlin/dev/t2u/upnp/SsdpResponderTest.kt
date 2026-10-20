package dev.t2u.upnp

import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
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
}
