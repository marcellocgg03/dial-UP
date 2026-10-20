package dev.t2u.renderer

// Tests for Bridge (port of tube2upnp src/index.js, the composition root), GPL-3.0-only.
//
// The whole cast path on loopback, no phone and no network: a fake speaker answers the SSDP search, FakeRenderer is
// the UPnP device, FakeLoungeServer replays the traffic recorded from the Node receiver. Everything listens on
// 127.0.0.1 only; the "multicast" targets are unicast sockets of the test.

import dev.t2u.dial.DialAccessMode
import dev.t2u.lounge.FakeLoungeServer
import dev.t2u.media.ResolvedInfo
import dev.t2u.media.VideoResolver
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.FakeRenderer
import dev.t2u.upnp.ListenSpec
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val RENDERER_UDN = "fake-renderer"
private const val DIAL_ST = "urn:dial-multiscreen-org:service:dial:1"

/** CAST-01, SVC-01, SVC-09, PLAY-03, DISC-01: speaker found, receiver found by the phone, cast played, Stop removes everything. */
class BridgeTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val fakeRenderer = FakeRenderer().also { it.start() }
    private val lounge = FakeLoungeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = HttpClient.newHttpClient()
    private val dataDir: File = Files.createTempDirectory("bridge-").toFile().also { it.deleteOnExit() }
    private val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
    private val resolved = CopyOnWriteArrayList<String>()

    @Volatile private var accessMode = DialAccessMode.PHONE

    /** Answers every M-SEARCH with a MediaRenderer reply whose LOCATION is the FakeRenderer. */
    private val speakerSocket = DatagramSocket(InetSocketAddress(loopback, 0)).also { it.soTimeout = 200 }
    private val speakerThread = Thread {
        val buf = ByteArray(4096)
        while (!speakerSocket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                speakerSocket.receive(packet)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            if (!String(packet.data, 0, packet.length, Charsets.UTF_8).startsWith("M-SEARCH")) continue
            val reply = (
                "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                    "USN: uuid:$RENDERER_UDN::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                    "LOCATION: ${fakeRenderer.location}\r\nSERVER: Linux UPnP/1.0 Fake/1.0\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
            runCatching { speakerSocket.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
        }
    }.apply { isDaemon = true; start() }

    /** Where the responder's NOTIFY datagrams go in the test (a unicast socket instead of the SSDP group). */
    private val notifySocket = DatagramSocket(InetSocketAddress(loopback, 0)).also { it.soTimeout = 200 }
    private val notifies = CopyOnWriteArrayList<String>()
    private val notifyThread = Thread {
        val buf = ByteArray(4096)
        while (!notifySocket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                notifySocket.receive(packet)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            notifies.add(String(packet.data, 0, packet.length, Charsets.UTF_8))
        }
    }.apply { isDaemon = true; start() }

    private val resolver = object : VideoResolver {
        override suspend fun resolve(videoId: String): ResolvedInfo {
            resolved.add(videoId)
            return ResolvedInfo(
                url = "https://example/v",
                ext = "m4a",
                mime = "audio/mp4",
                headers = emptyMap(),
                title = "Stub song $videoId",
                artist = "Band",
                album = "",
                thumbnail = "https://i/x.jpg",
                duration = 210.0,
                filesize = 0,
                formatId = null,
            )
        }

        override fun invalidate(videoId: String) {}
    }

    private val bridges = CopyOnWriteArrayList<Bridge>()

    @AfterEach
    fun tearDown() {
        runBlocking {
            for (b in bridges) runCatching { withTimeout(10_000) { b.stop() } }
        }
        scope.cancel()
        speakerSocket.close()
        notifySocket.close()
        lounge.close()
        fakeRenderer.close()
    }

    private fun freePort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    private fun newBridge(mode: () -> DialAccessMode = { accessMode }, mediaPort: Int = 0): Bridge {
        val config = Config(
            dataDir = dataDir,
            receiverBasePort = freePort(),
            mediaPort = mediaPort,
            pollIntervalMs = 100,
            startTimeoutMs = 5000,
        )
        val bridge = Bridge(
            BridgeNetwork(
                wifiIp = loopback,
                iface = null,
                ssdpListen = listOf(ListenSpec("loop", loopback, 0, null, false)),
                ssdpSearchTarget = InetSocketAddress(loopback, speakerSocket.localPort),
                notifyTarget = InetSocketAddress(loopback, notifySocket.localPort),
            ),
            BridgeDeps(
                config = config,
                resolver = resolver,
                launcher = null,
                http = lanHttpClient(),
                accessMode = mode,
                serverHeader = "Linux/6 UPnP/1.1 test/0",
                events = EventSink { kind, fields -> events.add(kind to fields) },
                scope = scope,
                bufferDir = null,
                loungeEndpoints = lounge.endpoints,
            ),
        )
        bridges.add(bridge)
        return bridge
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out after $timeoutMs ms waiting for $what; events=${events.takeLast(15)}")
        }
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(60_000) { block() } }

    /** An M-SEARCH for the DIAL service type from 127.0.0.1 to the responder's listen port; the reply text or null. */
    private fun dialSearch(bridge: Bridge): String? {
        val port = bridge.ssdpPorts().getValue("loop")
        DatagramSocket(InetSocketAddress(loopback, 0)).use { s ->
            s.soTimeout = 1500
            val msg = (
                "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $DIAL_ST\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
            s.send(DatagramPacket(msg, msg.size, InetSocketAddress(loopback, port)))
            val buf = ByteArray(4096)
            val p = DatagramPacket(buf, buf.size)
            return try {
                s.receive(p)
                String(p.data, 0, p.length, Charsets.UTF_8)
            } catch (_: java.net.SocketTimeoutException) {
                null
            }
        }
    }

    private fun header(reply: String, name: String): String? =
        reply.lines().firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()

    private fun setAvTransportUris(): List<String> = fakeRenderer.calls.filter { it.action == "SetAVTransportURI" }.map {
        Regex("<CurrentURI>([^<]*)</CurrentURI>").find(it.body)?.groupValues?.get(1).orEmpty()
    }

    private fun connectFails(port: Int) {
        assertFailsWith<ConnectException> { Socket().use { it.connect(InetSocketAddress(loopback, port), 1000) } }
    }

    @Test
    @DisplayName("a speaker found by search becomes a receiver, the launch plays the recorded cast on it and Stop removes everything")
    fun tracerSpeakerBecomesReceiverLaunchPlaysAndStopRemovesEverything() = run {
        val bridge = newBridge()
        bridge.start()

        // 1. the speaker answered the search, so one renderer is up and nothing is casting yet
        awaitUntil("one renderer up") { bridge.state()["renderers"]?.toString()?.contains(RENDERER_UDN) == true }
        assertEquals(BridgeCount(0, 1), bridge.castCount.value)

        // 2. the phone finds the receiver by M-SEARCH from its own address
        var reply: String? = null
        awaitUntil("a DIAL search reply") {
            reply = dialSearch(bridge)
            reply != null
        }
        val location = assertNotNull(header(reply!!, "LOCATION"))
        val usn = assertNotNull(header(reply!!, "USN"))
        val receiverUuid = usn.removePrefix("uuid:").substringBefore("::")
        val receiverPort = URI(location).port
        assertEquals(bridge.state()["renderers"].toString().let { Regex("\"port\":(\\d+)").find(it)!!.groupValues[1].toInt() }, receiverPort)

        // 3. the device description names the application URL; the launch pairs the sender through the lounge
        val description = http.send(HttpRequest.newBuilder(URI(location)).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, description.statusCode())
        val appUrl = description.headers().firstValue("Application-URL").get().trimEnd('/')
        val launch = http.send(
            HttpRequest.newBuilder(URI("$appUrl/YouTube"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("pairingCode=XYZ&theme=cl"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, launch.statusCode())
        assertTrue(launch.headers().firstValue("LOCATION").isPresent, "the launch answers a LOCATION")
        assertTrue(lounge.requests.any { it.path.endsWith("register_pairing_code") })

        // 4. the recorded setPlaylist reaches the speaker as a token URL plus Play
        lounge.replayRecorded("cl")
        awaitUntil("SetAVTransportURI and Play on the speaker") {
            setAvTransportUris().isNotEmpty() && fakeRenderer.calls.any { it.action == "Play" }
        }
        val uri = setAvTransportUris().first()
        assertTrue(Regex("http://127\\.0\\.0\\.1:\\d+/stream/[0-9a-f]{32}\\.aac").matches(uri), "CurrentURI was $uri")
        awaitUntil("cast count 1 of 1") { bridge.castCount.value == BridgeCount(1, 1) }

        // 5. the media server answers HEAD for the token and 404 for an unknown token
        val head = http.send(HttpRequest.newBuilder(URI(uri)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
        assertEquals(200, head.statusCode())
        assertEquals("audio/aac", head.headers().firstValue("Content-Type").get())
        val unknown = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${URI(uri).port}/stream/${"0".repeat(32)}.aac")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(404, unknown.statusCode())

        // 6. Stop: byebye for the receiver, goodbye to both lounge sessions, every port closed
        val mediaPort = URI(uri).port
        notifies.clear()
        bridge.stop()
        awaitUntil("byebye datagrams") { notifies.any { it.contains("ssdp:byebye") && it.contains(receiverUuid) } }
        for (theme in listOf("cl", "m")) {
            assertTrue(
                lounge.sent[theme].orEmpty().any { form -> form.values.contains("loungeScreenDisconnected") },
                "loungeScreenDisconnected was sent on $theme",
            )
        }
        connectFails(receiverPort)
        connectFails(mediaPort)
        assertEquals(BridgeCount(0, 0), bridge.castCount.value)
    }
}
