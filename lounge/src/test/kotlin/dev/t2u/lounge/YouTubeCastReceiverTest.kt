package dev.t2u.lounge

// Tests for YouTubeCastReceiver (port of yt-cast-receiver 2.1.0 lib/YouTubeCastReceiver.js and the DIAL glue,
// MIT, Copyright (c) Patrick Kan): DIAL server, the two lounge sessions and one SSDP advertisement as one cast target.
// The lounge side is FakeLoungeServer, which replays the traffic recorded from the Node receiver.

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.SsdpDevice
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class MemoryDataStore : DataStore {
    val map = ConcurrentHashMap<String, JsonElement>()

    override suspend fun get(key: String): JsonElement? = map[key]

    override suspend fun set(key: String, value: JsonElement?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    fun text(key: String): String? = (map[key] as? JsonPrimitive)?.content
}

internal class RecordingAdvertiser : DialAdvertiser {
    val advertised = CopyOnWriteArrayList<SsdpDevice>()
    val withdrawn = CopyOnWriteArrayList<String>()

    /** What the DIAL port did at the moment of the withdrawal (set by a test that wants to know). */
    @Volatile
    var onWithdraw: () -> Unit = {}

    override fun advertise(device: SsdpDevice) {
        advertised.add(device)
    }

    override fun withdraw(uuid: String) {
        onWithdraw()
        withdrawn.add(uuid)
    }
}

/** The names of the messages in each recorded post of [theme], in order: `req{i}__sc` values per post. */
internal fun FakeLoungeServer.sentNames(theme: String): List<List<String>> =
    (sent[theme] ?: emptyList<Map<String, String>>()).map { form ->
        form.entries.filter { it.key.matches(Regex("req\\d+__sc")) }.map { it.value }
    }

internal fun recordedMessages(file: String): List<IncomingMessage> {
    val text = YouTubeCastReceiverTest::class.java.getResourceAsStream("/fixtures/$file")!!.use { String(it.readBytes(), Charsets.UTF_8) }
    val out = ArrayList<IncomingMessage>()
    lateinit var decoder: ChunkDecoder
    decoder = ChunkDecoder(onPayload = { out.addAll(decoder.parseMessages(it)) })
    decoder.feed(text)
    return out
}

class YouTubeCastReceiverTest {
    private val fake = FakeLoungeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = MemoryDataStore()
    private val advertiser = RecordingAdvertiser()
    private val player = FakePlayer()
    private val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
    private val sink = EventSink { kind, fields -> events.add(kind to fields) }
    private val receivers = CopyOnWriteArrayList<YouTubeCastReceiver>()
    private val http = HttpClient.newHttpClient()

    @AfterEach
    fun tearDown() {
        runBlocking {
            for (r in receivers) {
                try {
                    withTimeout(5000) { r.stop() }
                } catch (_: Exception) {
                    // best effort
                }
            }
        }
        scope.cancel()
        fake.close()
    }

    private fun options(name: String = "🔊 Test (app)", dataStore: DataStore = store) = ReceiverOptions(
        dialPort = 0,
        name = name,
        screenName = name,
        brand = "T2U",
        model = "Test",
        dataStore = dataStore,
    )

    private fun deps(adv: DialAdvertiser = advertiser, accessPolicy: (String) -> Boolean = { true }) = ReceiverDeps(
        bindHost = "127.0.0.1",
        accessPolicy = accessPolicy,
        advertiser = adv,
        serverHeader = "Linux/5.0 UPnP/1.1 T2U/1.0",
        http = lanHttpClient(),
        endpoints = fake.endpoints,
        sink = sink,
        scope = scope,
    )

    private fun receiver(
        name: String = "🔊 Test (app)",
        dataStore: DataStore = store,
        adv: DialAdvertiser = advertiser,
        accessPolicy: (String) -> Boolean = { true },
        p: Player = player,
    ): YouTubeCastReceiver {
        val r = YouTubeCastReceiver(p, options(name, dataStore), deps(adv, accessPolicy))
        receivers.add(r)
        return r
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5000, cond: () -> Boolean) {
        withTimeout(timeoutMs) { while (!cond()) delay(15) }
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(30_000) { block() } }

    private fun dialPort(adv: RecordingAdvertiser = advertiser): Int = URI(adv.advertised.last().location).port

    private fun launchPost(port: Int, body: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port/ytcr/apps/YouTube"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun tracerDialLaunchPairsTheSenderAndTheRecordedSetPlaylistPlaysAndReportsBack() = run {
        val r = receiver()
        r.start()
        assertEquals("running", r.status)

        // one SSDP device, whose LOCATION is this receiver's device description
        assertEquals(1, advertiser.advertised.size)
        val device = advertiser.advertised.single()
        val port = dialPort()
        assertTrue(port > 0)
        assertEquals("http://127.0.0.1:$port/ytcr/ssdp/device-desc.xml", device.location)
        assertEquals(store.text(StoreKeys.DIAL_UUID), device.uuid)
        assertEquals("🔊 Test (app)", device.name)

        // the phone's DIAL launch: pairing code and theme
        val response = launchPost(port, "pairingCode=ABCDEF&theme=cl")
        assertEquals(200, response.statusCode())
        val pid = store.text(StoreKeys.APP_PID)!!
        assertEquals("http://127.0.0.1:$port/ytcr/apps/YouTube/$pid", response.headers().firstValue("LOCATION").get())

        val clScreen = store.map["mdxContext.cl"] as JsonObject
        val registration = fake.requests.single { it.path.endsWith("register_pairing_code") }
        assertEquals("ABCDEF", registration.form["pairing_code"])
        assertEquals((clScreen["screenId"] as JsonPrimitive).content, registration.form["screen_id"])

        // the sender connects and casts: the recorded setPlaylist plays on the player
        val recordedVideo = recordedMessages("rpc-cl.txt").first { it.name == "setPlaylist" }.payload.let {
            ((it as JsonObject)["videoId"] as JsonPrimitive).content
        }
        fake.replayRecorded("cl")
        awaitUntil { "play:$recordedVideo" in player.calls }

        // and the player's state goes back to the sender
        awaitUntil { fake.sentNames("cl").flatten().let { "nowPlaying" in it && "onStateChange" in it } }
        assertTrue(fake.sentNames("cl").flatten().containsAll(listOf("nowPlaying", "onStateChange")))
    }

    private fun getBytes(port: Int, path: String): ByteArray = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray(),
    ).body()

    private fun deviceDescription(port: Int) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(getBytes(port, "/ytcr/ssdp/device-desc.xml").inputStream())

    private fun reachable(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        true
    } catch (_: ConnectException) {
        false
    }

    private fun friendlyName(port: Int): String =
        deviceDescription(port).getElementsByTagName("friendlyName").item(0).textContent

    @Test
    fun renameServesTheNewNameAndAdvertisesItAgain() = run {
        val r = receiver()
        r.start()
        val port = dialPort()
        assertEquals("🔊 Test (app)", friendlyName(port))

        r.rename("Kitchen")

        assertEquals("Kitchen", friendlyName(port))
        assertEquals(2, advertiser.advertised.size)
        val first = advertiser.advertised[0]
        val second = advertiser.advertised[1]
        assertEquals("Kitchen", second.name)
        assertEquals(first.uuid, second.uuid)
        assertEquals(first.location, second.location)
        assertEquals("running", r.status)
    }

    @Test
    fun aRenameBeforeStartIsUsedAtStart() = run {
        val r = receiver()

        r.rename("Kitchen")

        assertTrue(advertiser.advertised.isEmpty(), "a stopped receiver advertises nothing")
        r.start()
        assertEquals("Kitchen", advertiser.advertised.single().name)
        assertEquals("Kitchen", friendlyName(dialPort()))
    }

    private fun pairingRegistrations() = fake.requests.filter { it.path.endsWith("register_pairing_code") }

    @Test
    fun aRestartOnTheSameStoreKeepsUuidPidAndLoungeIdentities() = run {
        val first = receiver()
        first.start()
        val uuid = advertiser.advertised.last().uuid
        val port1 = dialPort()
        val pid = launchPost(port1, "pairingCode=ABCDEF&theme=cl").headers().firstValue("LOCATION").get().substringAfterLast('/')
        val udn1 = deviceDescription(port1).getElementsByTagName("UDN").item(0).textContent
        val screenIdRequests = fake.requests.count { it.path.endsWith("generate_screen_id") }
        assertEquals(2, screenIdRequests, "one new screen for each of cl and m on the first start")
        val identities = listOf("mdxContext.cl", "mdxContext.m").map { store.map[it] }
        first.stop()

        val second = receiver()
        second.start()
        val port2 = dialPort()
        assertEquals(2, advertiser.advertised.size)
        assertEquals(uuid, advertiser.advertised.last().uuid, "same uuid, so the phone sees the same cast target")
        assertEquals(udn1, deviceDescription(port2).getElementsByTagName("UDN").item(0).textContent)
        assertEquals("uuid:$uuid", udn1)
        val pid2 = launchPost(port2, "pairingCode=GHIJKL&theme=cl").headers().firstValue("LOCATION").get().substringAfterLast('/')
        assertEquals(pid, pid2)
        assertEquals(screenIdRequests, fake.requests.count { it.path.endsWith("generate_screen_id") }, "stored screen ids reused")
        assertEquals(identities, listOf("mdxContext.cl", "mdxContext.m").map { store.map[it] })
        assertEquals(2, pairingRegistrations().size)
    }

    @Test
    fun aStoredValueThatIsNotAUuidIsReplaced() = run {
        store.map[StoreKeys.DIAL_UUID] = JsonPrimitive("x\r\nINJECTED: 1")
        receiver().start()
        val uuid = advertiser.advertised.single().uuid
        assertTrue(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(uuid), uuid)
        assertEquals(uuid, store.text(StoreKeys.DIAL_UUID))
    }

    @Test
    fun stopWithdrawsBeforeThePortClosesThenClosesItAndTellsBothSessions() = run {
        val r = receiver()
        r.start()
        val port = dialPort()
        assertTrue(reachable(port))
        var reachableAtWithdraw: Boolean? = null
        advertiser.onWithdraw = { reachableAtWithdraw = reachable(port) }

        r.stop()

        assertEquals("stopped", r.status)
        assertEquals(listOf(advertiser.advertised.single().uuid), advertiser.withdrawn.toList())
        assertEquals(true, reachableAtWithdraw, "byebye goes out while the DIAL port still answers")
        assertFalse(reachable(port), "the DIAL port refuses connections afterwards")
        assertTrue("loungeScreenDisconnected" in fake.sentNames("cl").flatten())
        assertTrue("loungeScreenDisconnected" in fake.sentNames("m").flatten())
    }

    @Test
    fun aLaunchWithoutPairingCodeOrWithAnUnknownThemeAnswers200WithoutLocation() = run {
        receiver().start()
        val port = dialPort()
        for (body in listOf("theme=cl", "pairingCode=ABCDEF&theme=x", "pairingCode=&theme=cl", "")) {
            val response = launchPost(port, body)
            assertEquals(200, response.statusCode(), body)
            assertTrue(response.headers().firstValue("LOCATION").isEmpty, "no LOCATION for '$body'")
        }
        assertTrue(pairingRegistrations().isEmpty())
        assertTrue(events.any { (kind, f) -> kind == "lounge-receiver" && f["type"] == "launchFailed" })
    }

    @Test
    fun aRefusingAccessPolicyAnswers403AndNothingReachesTheLounge() = run {
        receiver(accessPolicy = { false }).start()
        val port = dialPort()
        val response = launchPost(port, "pairingCode=ABCDEF&theme=cl")
        assertEquals(403, response.statusCode())
        assertTrue(response.headers().firstValue("LOCATION").isEmpty)
        assertTrue(pairingRegistrations().isEmpty())
    }

    @Test
    fun aNameWithEmojiAndAccentsReachesTheDeviceDescriptionAndTheBindUnchanged() = run {
        val name = "\uD83D\uDD0A K\u00FCche (app)"
        receiver(name = name).start()
        val doc = deviceDescription(dialPort())
        assertEquals(name, doc.getElementsByTagName("friendlyName").item(0).textContent)
        assertEquals(name, advertiser.advertised.single().name)

        val binds = fake.requests.filter { it.path.endsWith("/bc/bind") }
        assertTrue(binds.isNotEmpty())
        for (bind in binds) {
            assertTrue("name=%F0%9F%94%8A%20K%C3%BCche%20%28app%29" in bind.rawQuery.split('&'), bind.rawQuery)
        }
    }

    @Test
    fun aStartFailureThrowsAndLeavesNothingListeningOrAdvertised() = run {
        fake.refuseAllTokens = true
        val r = receiver()
        assertFailsWith<AppException> { r.start() }
        assertEquals("stopped", r.status)
        assertTrue(advertiser.advertised.isEmpty())
        assertTrue(advertiser.withdrawn.isEmpty())

        // nothing was left half-started: the same receiver starts once the lounge accepts again
        fake.refuseAllTokens = false
        r.start()
        assertEquals("running", r.status)
        assertEquals(1, advertiser.advertised.size)
    }

    @Test
    fun aSecondStartWhileRunningDoesNothing() = run {
        val r = receiver()
        r.start()
        r.start()
        assertEquals(1, advertiser.advertised.size)
    }
}
