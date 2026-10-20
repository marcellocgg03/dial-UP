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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
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
}
