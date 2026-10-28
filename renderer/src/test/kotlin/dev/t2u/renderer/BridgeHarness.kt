package dev.t2u.renderer

// Reusable whole-bridge rig for the Phase 3 end-to-end tests (plan 03-10), GPL-3.0-only.
//
// BridgeTest keeps its own copy of this fixture (it is the Phase 2 tracer and stays unedited); this one is for tests that
// need more than one scripted collaborator: the fake speaker (SSDP reply + UPnP device), the fake lounge (the sender),
// the fake InnerTube server, a stub resolver and, for the media tests, a local upstream server and a real ffmpeg.
// Everything listens on 127.0.0.1 only; the "multicast" targets are unicast sockets of the test. No LAN, no device.

import com.sun.net.httpserver.HttpServer
import dev.t2u.dial.DialAccessMode
import dev.t2u.lounge.FakeInnerTubeServer
import dev.t2u.lounge.FakeLoungeServer
import dev.t2u.media.PipeLauncher
import dev.t2u.media.ResolvedInfo
import dev.t2u.media.SourceDownloader
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal const val HARNESS_RENDERER_UDN = "fake-renderer"
private const val DIAL_SEARCH_TARGET = "urn:dial-multiscreen-org:service:dial:1"

/** Where the DIAL launch put the receiver: its port (on the Wi-Fi address) and its UUID in the SSDP replies. */
internal class LaunchedReceiver(val port: Int, val uuid: String)

/** One request the local upstream server saw. [headers] has lower-cased names. */
internal class UpstreamSeen(val method: String, val path: String, val headers: Map<String, String>)

internal class BridgeHarness(
    sink: String = "http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*",
    neverPlay: Boolean = false,
    bareFaultOn: Set<String> = emptySet(),
    noGapless: Boolean = false,
) : AutoCloseable {
    val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    val fakeRenderer = FakeRenderer(sink, neverPlay, bareFaultOn, noGapless).also { it.start() }
    val lounge = FakeLoungeServer()
    val innerTube = FakeInnerTubeServer()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http: HttpClient = HttpClient.newHttpClient()
    val dataDir: File = Files.createTempDirectory("bridge-harness-").toFile().also { it.deleteOnExit() }
    val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()

    /** Ids the stub resolver was asked about, in order. */
    val resolved = CopyOnWriteArrayList<String>()

    /** What the stub resolver answers; a test may change them before a cast. */
    @Volatile var resolvedUrl: String = "https://example/v"

    @Volatile var resolvedExt: String = "m4a"

    @Volatile var resolvedMime: String = "audio/mp4"

    @Volatile var accessMode = DialAccessMode.PHONE

    val bridges = CopyOnWriteArrayList<Bridge>()
    private val aids = AtomicInteger(100)

    /** How many M-SEARCH datagrams the fake speaker's socket has received (plan 05-02: housekeeping searches). */
    val searchesSeen = AtomicInteger(0)

    /** Answers every M-SEARCH with a MediaRenderer reply whose LOCATION is the fake speaker. */
    private val speakerSocket = DatagramSocket(InetSocketAddress(loopback, 0)).also { it.soTimeout = 200 }

    @Suppress("unused")
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
            searchesSeen.incrementAndGet()
            val reply = (
                "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                    "USN: uuid:$HARNESS_RENDERER_UDN::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                    "LOCATION: ${fakeRenderer.location}\r\nSERVER: Linux UPnP/1.0 Fake/1.0\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
            runCatching { speakerSocket.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
        }
    }.apply { isDaemon = true; start() }

    /** Where the responder's NOTIFY datagrams go (a unicast socket instead of the SSDP group); drained and ignored. */
    private val notifySocket = DatagramSocket(InetSocketAddress(loopback, 0)).also { it.soTimeout = 200 }

    @Suppress("unused")
    private val notifyThread = Thread {
        val buf = ByteArray(4096)
        while (!notifySocket.isClosed) {
            try {
                notifySocket.receive(DatagramPacket(buf, buf.size))
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
        }
    }.apply { isDaemon = true; start() }

    val resolver = object : VideoResolver {
        override suspend fun resolve(videoId: String): ResolvedInfo {
            resolved.add(videoId)
            return ResolvedInfo(
                url = resolvedUrl,
                ext = resolvedExt,
                mime = resolvedMime,
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

    // ------------------------------------------------------------------------------------------ bridge

    fun freePort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    /** Test defaults: fast polling, a short start timeout, a free receiver port, any free media port. */
    fun testConfig(
        outputFormat: String = "adts",
        mp3Bitrate: String = "192k",
        flacBits: Int = 16,
        ffmpegLogLevel: String = "error",
        ytdlpVerbose: Boolean = false,
        startTimeoutMs: Long = 5000,
    ): Config = Config(
        dataDir = dataDir,
        outputFormat = outputFormat,
        receiverBasePort = freePort(),
        mediaPort = 0,
        pollIntervalMs = 100,
        startTimeoutMs = startTimeoutMs,
        mp3Bitrate = mp3Bitrate,
        flacBits = flacBits,
        ffmpegLogLevel = ffmpegLogLevel,
        ytdlpVerbose = ytdlpVerbose,
        // Node's test setup() omits PREFETCH; the Config default (true) would prepare the next track behind these
        // tests. Plan 04-08 turns it on where it wants it with testConfig().copy(prefetch = true).
        prefetch = false,
    )

    /**
     * The BridgeNetwork newBridge builds, on [ip] (plan 05-07: a move to 127.0.0.2). The responder listens on [ip] only; the
     * harness's speaker and notify sockets stay the search and notify targets, so a bridge moved to another loopback
     * address still finds the speaker.
     */
    fun network(ip: InetAddress): BridgeNetwork = BridgeNetwork(
        wifiIp = ip,
        iface = null,
        ssdpListen = listOf(ListenSpec("loop", ip, 0, null, false)),
        ssdpSearchTarget = InetSocketAddress(loopback, speakerSocket.localPort),
        notifyTarget = InetSocketAddress(loopback, notifySocket.localPort),
    )

    fun newBridge(
        config: Config = testConfig(),
        launcher: PipeLauncher? = null,
        bufferDir: File? = null,
        sourceDownloader: SourceDownloader? = null,
        upstreamPolicy: UpstreamPolicy? = null,
        clock: (() -> Long)? = null,
        housekeepingIntervalMs: Long? = null,
    ): Bridge {
        val network = network(loopback)
        val sink = EventSink { kind, fields -> events.add(kind to fields) }
        val deps = BridgeDeps(
            config, resolver, launcher, lanHttpClient(), { accessMode }, "Linux/6 UPnP/1.1 test/0", sink, scope, bufferDir,
            loungeEndpoints = lounge.endpoints,
            innerTubeEndpoints = innerTube.endpoints,
            sourceDownloader = sourceDownloader,
            // null: leave BridgeDeps' own default (googlevideo over https only) in place
            upstreamPolicy = upstreamPolicy ?: UpstreamPolicy.GOOGLEVIDEO,
            // null: leave BridgeDeps' own defaults (wall clock, 5 minutes) in place
            clock = clock ?: System::currentTimeMillis,
            housekeepingIntervalMs = housekeepingIntervalMs ?: HOUSEKEEPING_INTERVAL_MS,
        )
        val bridge = Bridge(network, deps)
        bridges.add(bridge)
        return bridge
    }

    /** M-SEARCH for the DIAL service from 127.0.0.1 to the responder; the reply text or null. */
    private fun dialSearch(bridge: Bridge): String? = dialSearchAt(bridge, loopback)

    /** The same M-SEARCH, sent to the responder's listen socket on [ip] (it listens on one address only). */
    fun dialSearchAt(bridge: Bridge, ip: InetAddress): String? {
        val port = bridge.ssdpPorts().getValue("loop")
        DatagramSocket(InetSocketAddress(loopback, 0)).use { s ->
            s.soTimeout = 1500
            val msg = (
                "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $DIAL_SEARCH_TARGET\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
            s.send(DatagramPacket(msg, msg.size, InetSocketAddress(ip, port)))
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

    fun header(reply: String, name: String): String? =
        reply.lines().firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()

    /** Finds the receiver by M-SEARCH, reads its description and posts the DIAL launch (BridgeTest steps 2 and 3). */
    suspend fun launch(bridge: Bridge): LaunchedReceiver {
        awaitUntil("one renderer up") { bridge.state()["renderers"]?.toString()?.contains(HARNESS_RENDERER_UDN) == true }
        var reply: String? = null
        awaitUntil("a DIAL search reply") {
            reply = dialSearch(bridge)
            reply != null
        }
        val location = checkNotNull(header(reply!!, "LOCATION"))
        val usn = checkNotNull(header(reply!!, "USN"))
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
        assertTrue(lounge.requests.any { it.path.endsWith("register_pairing_code") })
        return LaunchedReceiver(URI(location).port, usn.removePrefix("uuid:").substringBefore("::"))
    }

    // ------------------------------------------------------------------------------------------ lounge (the sender)

    fun message(name: String, payload: JsonObject?): JsonArray {
        val args = ArrayList<JsonElement>()
        args.add(JsonPrimitive(name))
        if (payload != null) args.add(payload)
        return JsonArray(listOf(JsonPrimitive(aids.incrementAndGet()), JsonArray(args)))
    }

    fun rpc(theme: String, name: String, payload: JsonObject? = null) {
        lounge.enqueueRpc(theme, JsonArray(listOf(message(name, payload))).toString())
    }

    fun device(id: String, app: String = "android-phone-21.16.256", theme: String = "cl") = buildJsonObject {
        put("app", app)
        put("capabilities", "que,vsp,pas,dsdtr,mvp,atp,mus")
        put("clientName", "android")
        put("type", "REMOTE_CONTROL")
        put("name", "TEST PHONE")
        put("theme", theme)
        put("id", id)
    }

    fun loungeStatus(devices: List<JsonObject>, deviceId: String, ui: String? = "true") = buildJsonObject {
        put("devices", JsonArray(devices).toString())
        put(
            "connectionEventDetails",
            buildJsonObject {
                if (ui != null) put("ui", ui)
                put("deviceId", deviceId)
            }.toString(),
        )
    }

    /** A setPlaylist payload. [videoIds] defaults to the one video; [currentIndex] is the position of [videoId] in it. */
    fun playlist(videoId: String, listId: String = "PLTEST", videoIds: List<String> = listOf(videoId), currentIndex: Int = 0) = buildJsonObject {
        put("videoId", videoId)
        put("currentIndex", currentIndex.toString())
        put("listId", listId)
        put("currentTime", "0")
        put("videoIds", videoIds.joinToString(","))
    }

    /** Every message the app posted for [theme], in order, as (name, payload fields). */
    fun messages(theme: String = "cl"): List<Pair<String, Map<String, String>>> {
        val out = ArrayList<Pair<String, Map<String, String>>>()
        for (form in lounge.sent[theme] ?: emptyList<Map<String, String>>()) {
            val indexes = form.keys.mapNotNull { Regex("req(\\d+)__sc").matchEntire(it)?.groupValues?.get(1) }.sortedBy { it.toInt() }
            for (i in indexes) {
                val prefix = "req${i}_"
                val payload = form.filterKeys { it.startsWith(prefix) && it != "${prefix}_sc" }.mapKeys { it.key.removePrefix(prefix) }
                out.add(form.getValue("${prefix}_sc") to payload)
            }
        }
        return out
    }

    fun count(name: String, theme: String = "cl") = messages(theme).count { it.first == name }

    /** The sender connects over the lounge (the same message the YouTube app posts) and waits for the first state report. */
    suspend fun connectSender(theme: String = "cl", id: String = "dev-1") {
        val before = count("onStateChange", theme)
        rpc(theme, "loungeStatus", loungeStatus(listOf(device(id, theme = theme)), id))
        awaitUntil("sender $id connected") { count("onStateChange", theme) > before }
    }

    // ------------------------------------------------------------------------------------------ speaker and waiting

    /** The CurrentURI of every SetAVTransportURI the fake speaker received, in order. */
    fun setAvTransportUris(): List<String> = fakeRenderer.calls.filter { it.action == "SetAVTransportURI" }.map {
        Regex("<CurrentURI>([^<]*)</CurrentURI>").find(it.body)?.groupValues?.get(1).orEmpty()
    }

    fun speakerCount(action: String): Int = fakeRenderer.calls.count { it.action == action }

    /** The video ids InnerTube was asked about, in order. */
    fun askedAbout(): List<String> = innerTube.requests.mapNotNull { (it.body["videoId"] as? JsonPrimitive)?.content }

    suspend fun awaitUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out after $timeoutMs ms waiting for $what; events=${events.takeLast(15)}")
        }
    }

    fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(90_000) { block() } }

    // ------------------------------------------------------------------------------------------ upstream and ffmpeg

    private val upstreamServers = CopyOnWriteArrayList<HttpServer>()

    /** Every request the local upstream servers saw. */
    val upstreamSeen = CopyOnWriteArrayList<UpstreamSeen>()

    /**
     * A JDK HttpServer on loopback that serves [body] at any path with Range support (206 with Content-Range for
     * `bytes=a-b`, 200 otherwise) and records each request in [upstreamSeen]. Returns its base URL.
     */
    fun startUpstream(body: ByteArray, contentType: String = "audio/mp4"): String {
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.createContext("/") { ex ->
            val headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            upstreamSeen.add(UpstreamSeen(ex.requestMethod, ex.requestURI.path, headers))
            ex.responseHeaders.add("Content-Type", contentType)
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            val range = Regex("^bytes=(\\d+)-(\\d*)$").find(headers["range"].orEmpty())
            val (status, slice) = if (range != null) {
                val from = range.groupValues[1].toInt().coerceAtMost(body.size - 1)
                val to = range.groupValues[2].toIntOrNull()?.coerceAtMost(body.size - 1) ?: (body.size - 1)
                ex.responseHeaders.add("Content-Range", "bytes $from-$to/${body.size}")
                206 to body.copyOfRange(from, to + 1)
            } else {
                200 to body
            }
            if (ex.requestMethod == "HEAD") {
                ex.responseHeaders.add("Content-Length", slice.size.toString())
                ex.sendResponseHeaders(status, -1)
            } else {
                ex.sendResponseHeaders(status, slice.size.toLong())
                ex.responseBody.use { it.write(slice) }
            }
            ex.close()
        }
        server.start()
        upstreamServers.add(server)
        return "http://127.0.0.1:${server.address.port}"
    }

    /** The first ffmpeg binary found, or null. */
    fun findFfmpeg(): String? = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/bin/ffmpeg").firstOrNull { File(it).canExecute() }

    /** Makes a [seconds]-second sine source (AAC in m4a) at [target] with the real ffmpeg; false when ffmpeg is absent or failed. */
    fun makeSineSource(target: File, seconds: Int = 3): Boolean {
        val ffmpeg = findFfmpeg() ?: return false
        target.parentFile?.mkdirs()
        val proc = ProcessBuilder(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=$seconds",
            "-c:a", "aac", "-f", "mp4", target.path,
        ).redirectErrorStream(true).start()
        proc.inputStream.readBytes()
        return proc.waitFor() == 0 && target.length() > 0
    }

    override fun close() {
        runBlocking {
            for (b in bridges) runCatching { withTimeout(10_000) { b.stop() } }
        }
        scope.cancel()
        upstreamServers.forEach { runCatching { it.stop(0) } }
        speakerSocket.close()
        notifySocket.close()
        lounge.close()
        innerTube.close()
        fakeRenderer.close()
    }
}
