package dev.t2u.dial

import dev.t2u.upnp.ListenSpec
import dev.t2u.upnp.SsdpDevice
import dev.t2u.upnp.SsdpEvents
import dev.t2u.upnp.SsdpResponder
import dev.t2u.upnp.parseMessage
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tracer: one M-SEARCH reaches variant A and its LOCATION serves device-desc, app-desc and the launch. */
class DiscoveryLoopbackTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun searchFindsVariantAAndLocationServesTheDialChain() {
        val seen = CopyOnWriteArrayList<DialRequest>()
        val uuid = "11652cc9-8e9f-4431-9bdd-49a5bf04dd57"
        val dial = DialServer(DialConfig("T2U-SPIKE A base", uuid, "127.0.0.1", 0)) { seen.add(it) }
        dial.start()
        val responder = SsdpResponder(
            listOf(ListenSpec("loop", loopback, 0, null, false)),
            loopback,
            null,
            object : SsdpEvents {},
        )
        try {
            val dialPort = dial.resolvedPort()
            assertTrue(dialPort > 0, "DIAL port resolved")
            val location = "http://127.0.0.1:$dialPort/ytcr/ssdp/device-desc.xml"
            responder.register(SsdpDevice("T2U-SPIKE A base", uuid, "Linux/test UPnP/1.1 famium/0.0.1", location))
            responder.start()
            val ssdpPort = assertNotNull(responder.boundPorts()["loop"])

            // M-SEARCH in, unicast reply out
            val reply = DatagramSocket(0, loopback).use { client ->
                client.soTimeout = 2000
                val search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
                    "ST: urn:dial-multiscreen-org:service:dial:1\r\nUSER-AGENT: t2u-test\r\n\r\n"
                val bytes = search.toByteArray()
                client.send(DatagramPacket(bytes, bytes.size, loopback, ssdpPort))
                val buf = ByteArray(2048)
                val packet = DatagramPacket(buf, buf.size)
                client.receive(packet)
                parseMessage(packet.data, packet.length)
            }
            assertEquals("HTTP/1.1 200 OK", reply.startLine)
            assertEquals(location, reply.headers["LOCATION"])
            assertEquals("urn:dial-multiscreen-org:service:dial:1", reply.headers["ST"])
            assertEquals("uuid:$uuid::urn:dial-multiscreen-org:service:dial:1", reply.headers["USN"])

            // LOCATION -> device description with Application-URL
            val http = HttpClient.newHttpClient()
            val desc = http.send(HttpRequest.newBuilder(URI.create(reply.headers.getValue("LOCATION"))).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, desc.statusCode())
            val appUrl = desc.headers().firstValue("Application-URL").orElseThrow()
            assertTrue(appUrl.endsWith("/ytcr/apps"), appUrl)
            assertTrue(desc.body().contains("<friendlyName>T2U-SPIKE A base</friendlyName>"))
            assertEquals(1, desc.headers().allValues("Content-Length").size, "exactly one Content-Length")

            // Application-URL -> app description
            val app = http.send(HttpRequest.newBuilder(URI.create("$appUrl/YouTube")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, app.statusCode())
            assertTrue(app.body().contains("<name>YouTube</name>"), app.body())

            // launch
            val launch = http.send(
                HttpRequest.newBuilder(URI.create("$appUrl/YouTube"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "t2u-test")
                    .POST(HttpRequest.BodyPublishers.ofString("pairingCode=abc&theme=cl"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertTrue(launch.statusCode() == 200 || launch.statusCode() == 201, "launch status ${launch.statusCode()}")
            val loc = launch.headers().firstValue("LOCATION").orElseThrow()
            assertTrue(loc.startsWith("http://127.0.0.1:$dialPort/ytcr/apps/YouTube/"), loc)
            val post = seen.first { it.method == "POST" }
            assertEquals("pairingCode=<redacted len 3>&theme=cl", post.body)
            assertEquals("t2u-test", post.headers["User-Agent"])
            assertTrue(seen.size >= 3, "every request reported")
        } finally {
            responder.stop()
            dial.stop()
        }
    }

    @Test
    fun aDialServerCanRebindItsPortRightAfterServingRequests() {
        val first = DialServer(DialConfig("rebind", "u1", "127.0.0.1", 0)) {}
        first.start()
        val port = first.resolvedPort()
        try {
            val http = HttpClient.newHttpClient()
            repeat(3) {
                http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/ytcr/apps")).GET().build(), HttpResponse.BodyHandlers.discarding())
            }
        } finally {
            first.stop()
        }
        val second = DialServer(DialConfig("rebind", "u1", "127.0.0.1", port)) {}
        second.start() // would throw BindException without reuseAddress when connections linger in TIME_WAIT
        try {
            assertEquals(port, second.resolvedPort())
        } finally {
            second.stop()
        }
    }
}
