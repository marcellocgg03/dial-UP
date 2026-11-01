package dev.t2u.renderer

// CAST-06 through the composition root (plan 03-08): Bridge.stop(clearSpeakers = true) leaves the fake speaker with
// nothing loaded, Bridge.stop() leaves the cast track where it was. Same loopback rig as BridgeTest: the fake
// speaker answers the SSDP search, FakeRenderer is the UPnP device, FakeLoungeServer replays the recorded cast.
// GPL-3.0-only.

import dev.t2u.dial.DialAccessMode
import dev.t2u.lounge.FakeInnerTubeServer
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgeClearTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val fakeRenderer = FakeRenderer().also { it.start() }
    private val lounge = FakeLoungeServer()

    // Stands in for www.youtube.com: the first cast awaits InnerTube /next before it plays, so a real call ties the test to
    // the network (a hanging DNS lookup alone outlasts the 10 s waits below).
    private val innerTube = FakeInnerTubeServer()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = HttpClient.newHttpClient()
    private val dataDir = Files.createTempDirectory("bridge-clear-").toFile().also { it.deleteOnExit() }
    private val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
    private val bridges = CopyOnWriteArrayList<Bridge>()

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
                    "USN: uuid:fake-renderer::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                    "LOCATION: ${fakeRenderer.location}\r\nSERVER: Linux UPnP/1.0 Fake/1.0\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
            runCatching { speakerSocket.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
        }
    }.apply { isDaemon = true; start() }

    private val notifySocket = DatagramSocket(InetSocketAddress(loopback, 0)).also { it.soTimeout = 200 }

    private val resolver = object : VideoResolver {
        override suspend fun resolve(videoId: String) = ResolvedInfo(
            url = "https://example/v", ext = "m4a", mime = "audio/mp4", headers = emptyMap(), title = "Stub song $videoId",
            artist = "Band", album = "", thumbnail = "https://i/x.jpg", duration = 210.0, filesize = 0, formatId = null,
        )

        override fun invalidate(videoId: String) {}
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            for (b in bridges) runCatching { withTimeout(10_000) { b.stop() } }
        }
        scope.cancel()
        speakerSocket.close()
        notifySocket.close()
        lounge.close()
        innerTube.close()
        fakeRenderer.close()
    }

    private fun freePort(): Int = freeServerPort(loopback)

    private fun newBridge(): Bridge {
        val bridge = Bridge(
            BridgeNetwork(
                wifiIp = loopback,
                iface = null,
                ssdpListen = listOf(ListenSpec("loop", loopback, 0, null, false)),
                ssdpSearchTarget = InetSocketAddress(loopback, speakerSocket.localPort),
                notifyTarget = InetSocketAddress(loopback, notifySocket.localPort),
            ),
            BridgeDeps(
                config = Config(
                    dataDir = dataDir, outputFormat = "adts", receiverBasePort = freePort(), mediaPort = 0,
                    pollIntervalMs = 100, startTimeoutMs = 5000, clearOnCastEnd = true,
                ),
                resolver = resolver,
                launcher = null,
                http = lanHttpClient(),
                accessMode = { DialAccessMode.PHONE },
                serverHeader = "Linux/6 UPnP/1.1 test/0",
                events = EventSink { kind, fields -> events.add(kind to fields) },
                scope = scope,
                bufferDir = null,
                loungeEndpoints = lounge.endpoints,
                innerTubeEndpoints = innerTube.endpoints,
            ),
        )
        bridges.add(bridge)
        return bridge
    }

    private suspend fun awaitUntil(what: String, cond: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!cond()) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what; events=${events.takeLast(15)}")
        }
    }

    /** An M-SEARCH for the DIAL service type to the responder's listen port; the reply text or null. */
    private fun dialSearch(bridge: Bridge): String? {
        val port = bridge.ssdpPorts().getValue("loop")
        DatagramSocket(InetSocketAddress(loopback, 0)).use { sock ->
            sock.soTimeout = 1500
            val msg = (
                "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n" +
                    "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(msg, msg.size, InetSocketAddress(loopback, port)))
            val packet = DatagramPacket(ByteArray(4096), 4096)
            return try {
                sock.receive(packet)
                String(packet.data, 0, packet.length, Charsets.UTF_8)
            } catch (_: java.net.SocketTimeoutException) {
                null
            }
        }
    }

    /** Starts a bridge, launches the receiver and replays the recorded cast until the fake speaker holds our stream. */
    private suspend fun castUntilLoaded(): Bridge {
        val bridge = newBridge()
        bridge.start()
        awaitUntil("one renderer up") { bridge.state()["renderers"]?.toString()?.contains("fake-renderer") == true }
        var reply: String? = null
        awaitUntil("a DIAL search reply") {
            reply = dialSearch(bridge)
            reply != null
        }
        val location = reply!!.lines().first { it.startsWith("LOCATION:", ignoreCase = true) }.substringAfter(':').trim()
        val description = http.send(HttpRequest.newBuilder(URI(location)).GET().build(), HttpResponse.BodyHandlers.ofString())
        val appUrl = description.headers().firstValue("Application-URL").get().trimEnd('/')
        val launch = http.send(
            HttpRequest.newBuilder(URI("$appUrl/YouTube"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("pairingCode=XYZ&theme=cl"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, launch.statusCode())
        lounge.replayRecorded("cl")
        awaitUntil("the speaker holding our stream") { fakeRenderer.state.currentUri.contains("/stream/") }
        return bridge
    }

    @Test
    @DisplayName("Bridge.stop(clearSpeakers = true) leaves the speaker with an empty transport URI")
    fun stopWithClearEmptiesTheSpeaker() {
        runBlocking<Unit> {
            val bridge = castUntilLoaded()
            bridge.stop(clearSpeakers = true)
            assertEquals("", fakeRenderer.state.currentUri)
            assertTrue(
                fakeRenderer.calls.any { it.action == "SetAVTransportURI" && it.body.contains("<CurrentURI></CurrentURI>") },
                fakeRenderer.calls.map { it.action }.toString(),
            )
        }
    }

    @Test
    @DisplayName("Bridge.stop() without the flag leaves the speaker's track alone")
    fun plainStopDoesNotClear() {
        runBlocking<Unit> {
            val bridge = castUntilLoaded()
            val loaded = fakeRenderer.state.currentUri
            bridge.stop()
            assertEquals(loaded, fakeRenderer.state.currentUri)
            assertTrue(fakeRenderer.calls.none { it.action == "SetAVTransportURI" && it.body.contains("<CurrentURI></CurrentURI>") })
        }
    }
}
