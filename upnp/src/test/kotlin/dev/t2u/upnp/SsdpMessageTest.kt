package dev.t2u.upnp

import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SsdpMessageTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val uuid = "11652cc9-8e9f-4431-9bdd-49a5bf04dd57"
    private val dialSt = "urn:dial-multiscreen-org:service:dial:1"

    private fun golden(name: String): String {
        val text = SsdpMessageTest::class.java.getResource("/golden/$name")!!.readText()
        // fixtures are stored with LF so git line-ending settings cannot break them; the wire uses CRLF
        return text.replace("\r\n", "\n").replace("\n", "\r\n")
    }

    private fun search(st: String?, man: String? = "\"ssdp:discover\""): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n")
        if (man != null) append("MAN: ").append(man).append("\r\n")
        append("MX: 2\r\n")
        if (st != null) append("ST: ").append(st).append("\r\n")
        append("USER-AGENT: t2u-test\r\n\r\n")
    }.toByteArray()

    private class Rig(responder: (SsdpEvents) -> SsdpResponder) {
        val replies = CopyOnWriteArrayList<Pair<String, String>>()
        val drops = CopyOnWriteArrayList<String>()
        val notifies = AtomicInteger()
        val received = AtomicInteger()
        val events = object : SsdpEvents {
            override fun onReceive(specId: String, src: InetSocketAddress, msg: SsdpMessage, raw: String) {
                received.incrementAndGet()
            }

            override fun onReply(dest: InetSocketAddress, st: String, usn: String, deviceName: String) {
                replies.add(st to usn)
            }

            override fun onNotify(nts: String, nt: String, deviceName: String) {
                notifies.incrementAndGet()
            }

            override fun onDrop(src: InetSocketAddress, reason: String) {
                drops.add(reason)
            }
        }
        val responder = responder(events)
    }

    private fun waitFor(timeoutMs: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    private fun rig(clock: () -> Long = System::nanoTime): Rig {
        val device = SsdpDevice("T2U-SPIKE A base", uuid, "Linux/test UPnP/1.1 famium/0.0.1", "http://127.0.0.1:3100/ytcr/ssdp/device-desc.xml")
        return Rig { ev ->
            SsdpResponder(listOf(ListenSpec("loop", loopback, 0, null, false)), loopback, null, ev, clockNanos = clock).also {
                it.register(device)
                it.start()
            }
        }
    }

    // ----- wire format -------------------------------------------------------------------------

    @Test
    fun replyMatchesTheNodeCaptureByteForByte() {
        val bytes = buildSearchReply(
            location = "http://192.168.50.239:3003/ytcr/ssdp/device-desc.xml",
            st = dialSt,
            server = "Linux/6.1.0-40-amd64 UPnP/1.1 famium/0.0.1",
            usn = "uuid:$uuid::$dialSt",
            date = "Mon, 19 Oct 2026 20:36:03 GMT",
        )
        assertEquals(golden("ssdp-reply-dial.txt"), String(bytes, Charsets.UTF_8))
    }

    @Test
    fun parseMessageKeepsLettersOnlyUpperCaseKeysInGoldenOrder() {
        val msg = parseMessage(golden("ssdp-reply-dial.txt").toByteArray())
        assertEquals("HTTP/1.1 200 OK", msg.startLine)
        assertEquals(
            listOf("LOCATION", "ST", "CONFIGIDUPNPORG", "BOOTIDUPNPORG", "SERVER", "USN", "HOST", "CACHECONTROL", "EXT", "DATE"),
            msg.headers.keys.toList(),
        )
        assertEquals("", msg.headers["EXT"])
        assertEquals("max-age=1800", msg.headers["CACHECONTROL"])
    }

    @Test
    fun parseMessageSkipsLinesWithoutColonAndAcceptsBareLf() {
        val msg = parseMessage("M-SEARCH * HTTP/1.1\nnot a header\nst:  ssdp:all \n\n".toByteArray())
        assertEquals("M-SEARCH * HTTP/1.1", msg.startLine)
        assertEquals(mapOf("ST" to "ssdp:all"), msg.headers)
    }

    @Test
    fun notifyAliveAndByebyeUsePeerSsdpHeaderOrder() {
        val date = "Mon, 19 Oct 2026 20:36:03 GMT"
        val alive = String(buildNotify("ssdp:alive", "upnp:rootdevice", "uuid:$uuid::upnp:rootdevice", "SRV", "http://x/d.xml", date), Charsets.UTF_8)
        assertEquals(
            "NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\nUSN: uuid:$uuid::upnp:rootdevice\r\nSERVER: SRV\r\nLOCATION: http://x/d.xml\r\n" +
                "NTS: ssdp:alive\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\nEXT: \r\nDATE: $date\r\n\r\n",
            alive,
        )
        val bye = String(buildNotify("ssdp:byebye", "upnp:rootdevice", "uuid:$uuid::upnp:rootdevice", "SRV", "http://x/d.xml", date), Charsets.UTF_8)
        assertTrue(bye.contains("NTS: ssdp:byebye\r\n"))
        assertTrue(bye.startsWith("NOTIFY * HTTP/1.1\r\n"))
    }

    @Test
    fun httpDateIsZeroPaddedGmt() {
        assertEquals("Mon, 05 Oct 2026 01:02:03 GMT", httpDate(Instant.parse("2026-10-05T01:02:03Z")))
    }

    // ----- search matching ---------------------------------------------------------------------

    @Test
    fun eachOfTheFiveServiceTypesIsAnsweredWithStEchoedAndUsnPerType() {
        val types = SsdpConstants.dialServiceTypes(uuid)
        assertEquals(5, types.size)
        assertEquals("uuid:$uuid", types.last())
        val rig = rig()
        try {
            val port = rig.responder.boundPorts().getValue("loop")
            DatagramSocket(0, loopback).use { client ->
                client.soTimeout = 2000
                for (st in types) {
                    val bytes = search(st)
                    client.send(DatagramPacket(bytes, bytes.size, loopback, port))
                    val buf = ByteArray(2048)
                    val packet = DatagramPacket(buf, buf.size)
                    client.receive(packet)
                    val reply = parseMessage(packet.data, packet.length)
                    assertEquals(st, reply.headers["ST"])
                    assertEquals("uuid:$uuid::$st", reply.headers["USN"])
                }
            }
            assertEquals(types, rig.replies.map { it.first })
        } finally {
            rig.responder.stop()
        }
    }

    @Test
    fun unknownStOrMissingManGetsNoReply() {
        val rig = rig()
        try {
            val port = rig.responder.boundPorts().getValue("loop")
            DatagramSocket(0, loopback).use { client ->
                for (bytes in listOf(search("urn:schemas-upnp-org:device:MediaRenderer:1"), search(dialSt, man = null), search(dialSt, man = "\"ssdp:other\""), search(null))) {
                    client.send(DatagramPacket(bytes, bytes.size, loopback, port))
                }
            }
            waitFor { rig.received.get() == 4 }
            Thread.sleep(200)
            assertEquals(4, rig.received.get())
            assertTrue(rig.replies.isEmpty(), "no replies: ${rig.replies}")
        } finally {
            rig.responder.stop()
        }
    }

    // ----- reflection cap (T-01-05) ------------------------------------------------------------

    @Test
    fun moreThan32RepliesPerSecondToOneSourceAreDroppedAndReported() {
        val rig = rig(clock = { 0L }) // frozen clock: every search lands in the same rolling second
        try {
            val port = rig.responder.boundPorts().getValue("loop")
            DatagramSocket(0, loopback).use { client ->
                val bytes = search(dialSt)
                repeat(40) { client.send(DatagramPacket(bytes, bytes.size, loopback, port)) }
                waitFor { rig.replies.size + rig.drops.size == 40 }
            }
            assertEquals(32, rig.replies.size)
            assertEquals(8, rig.drops.size)
            assertTrue(rig.drops.all { it == "rate" })
        } finally {
            rig.responder.stop()
        }
    }

    @Test
    fun capWindowRollsSoRepliesResumeAfterOneSecond() {
        var now = 0L
        val rig = rig(clock = { now })
        try {
            val port = rig.responder.boundPorts().getValue("loop")
            DatagramSocket(0, loopback).use { client ->
                val bytes = search(dialSt)
                repeat(33) { client.send(DatagramPacket(bytes, bytes.size, loopback, port)) }
                waitFor { rig.replies.size + rig.drops.size == 33 }
                assertEquals(32, rig.replies.size)
                now = 1_100_000_000L
                client.send(DatagramPacket(bytes, bytes.size, loopback, port))
                waitFor { rig.replies.size == 33 }
                assertEquals(33, rig.replies.size)
            }
        } finally {
            rig.responder.stop()
        }
    }

    // ----- announce / byebye -------------------------------------------------------------------

    @Test
    fun announceAndByebyeSendNothingWhenTheReplySocketIsLoopback() {
        val rig = rig()
        rig.responder.announce()
        rig.responder.stop()
        assertEquals(0, rig.notifies.get())
    }
}
