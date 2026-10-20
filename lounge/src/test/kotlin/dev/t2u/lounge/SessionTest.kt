package dev.t2u.lounge

// Tests for the lounge Session (port of yt-cast-receiver 2.1.0 lib/app/Session.js and RPCConnection.js,
// MIT, Copyright (c) Patrick Kan) against FakeLoungeServer, which replays the traffic recorded from the Node receiver.

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionTest {
    private class MemoryStore : DataStore {
        val map = ConcurrentHashMap<String, JsonElement>()

        override suspend fun get(key: String): JsonElement? = map[key]

        override suspend fun set(key: String, value: JsonElement?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private val fake = FakeLoungeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
    private val sink = EventSink { kind, fields -> events.add(kind to fields) }
    private val sessions = CopyOnWriteArrayList<Session>()

    @AfterEach
    fun tearDown() {
        scope.cancel()
        fake.close()
    }

    private fun session(store: DataStore? = MemoryStore(), client: Client = Client.YT): Session {
        val s = Session(
            client = client,
            screenName = "T2U test",
            brand = "T2U",
            model = "Test",
            dataStore = store,
            http = lanHttpClient(),
            endpoints = fake.endpoints,
            sink = sink,
            scope = scope,
            rpcRetryDelayMs = 20,
        )
        sessions.add(s)
        return s
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5000, what: String = "condition", cond: () -> Boolean) {
        withTimeout(timeoutMs) { while (!cond()) delay(15) }
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(20_000) { block() } }

    /** Names of the messages in a recorded fixture, decoded by the production decoder. */
    private fun recordedNames(file: String): List<String> {
        val text = SessionTest::class.java.getResourceAsStream("/fixtures/$file")!!.use { String(it.readBytes(), Charsets.UTF_8) }
        val names = ArrayList<String>()
        lateinit var decoder: ChunkDecoder
        decoder = ChunkDecoder(onPayload = { p -> decoder.parseMessages(p).forEach { names.add(it.name) } })
        decoder.feed(text)
        return names
    }

    @Test
    fun tracerHandshakeThenRecordedSenderMessagesInOrder() = run {
        val store = MemoryStore()
        val s = session(store)
        val received = CopyOnWriteArrayList<IncomingMessage>()
        s.onMessages = { msgs, _ -> received.addAll(msgs) }
        s.begin()

        val paths = fake.requests.map { it.method + " " + it.path.substringAfter("/api/lounge/") }
        assertEquals(
            listOf("GET pairing/generate_screen_id", "POST pairing/get_lounge_token_batch", "POST bc/bind", "GET bc/bind"),
            paths,
        )
        val token = fake.requests[1]
        assertEquals(s.screenId, token.form["screen_ids"])

        val init = fake.requests[2]
        val keys = init.rawQuery.split('&').map { it.substringBefore('=') }
        assertEquals(keys.sorted(), keys, "query keys sorted")
        assertEquals("LOUNGE_SCREEN", init.query["device"])
        assertTrue("capabilities=dsp%2Cmic%2Cdpa%2Cntb" in init.rawQuery, init.rawQuery)
        assertEquals("cl", init.query["theme"])
        assertEquals("8", init.query["VER"])
        assertEquals("1", init.query["CVER"])
        assertEquals("0", init.form["count"])

        val rpc = fake.requests[3]
        assertEquals("rpc", rpc.query["RID"])
        assertTrue(rpc.query["SID"]!!.startsWith("SECRETsid"))
        assertTrue(rpc.query["gsessionid"]!!.startsWith("SECRETgsession"))
        assertTrue(rpc.query.getValue("AID").toInt() >= 3)
        assertEquals("xmlhttp", rpc.query["TYPE"])

        assertTrue(store.map.containsKey("mdxContext.cl"), "mdxContext stored")

        fake.replayRecorded("cl")
        val expected = listOf("loungeStatus") + recordedNames("rpc-cl.txt")
        awaitUntil { received.size >= expected.size }
        assertEquals(expected, received.map { it.name })
        assertTrue(fake.requests.all { it.userAgent == "node" }, "every request carries User-Agent node")
    }

    private fun volume(level: Int, aid: Int? = null) = OutgoingMessage.onVolumeChanged(aid, Volume(level, false))

    @Test
    fun messagesArePostedInQueueOrderWithGrowingOfs() = run {
        val s = session()
        s.begin()
        assertTrue(s.sendMessage(listOf(volume(30))))
        assertTrue(s.sendMessage(listOf(OutgoingMessage.autoplayUpNext(null, null), volume(31))))
        val sent = fake.sent.getValue("cl")
        assertEquals(2, sent.size)
        assertEquals(mapOf("count" to "1", "ofs" to "0", "req0__sc" to "onVolumeChanged", "req0_volume" to "30", "req0_muted" to "false"), sent[0])
        assertEquals("2", sent[1]["count"])
        assertEquals("1", sent[1]["ofs"])
        assertEquals(listOf("count", "ofs", "req0__sc", "req1__sc", "req1_volume", "req1_muted"), sent[1].keys.toList())
        val post = fake.requests.last { it.method == "POST" && it.form["count"] == "2" }
        assertEquals("SECRET", post.query.getValue("SID").take(6))
        assertTrue(post.query.containsKey("AID") && post.query.containsKey("gsessionid"))
    }

    @Test
    fun deferredVolumeMessagesCollapseToTheLastOne() = run {
        val s = session()
        s.begin()
        val results = kotlinx.coroutines.coroutineScope {
            listOf(10, 20, 30).map { level ->
                val r = async { s.sendMessage(listOf(volume(level)), deferKey = "volume") }
                delay(15)
                r
            }.awaitAll()
        }
        assertEquals(listOf(false, false, true), results)
        val sent = fake.sent.getValue("cl")
        assertEquals(1, sent.size)
        assertEquals("30", sent[0]["req0_volume"])
    }

    @Test
    fun aFailedPostIsRetriedOnceAfterRefreshingTheToken() = run {
        val s = session()
        s.begin()
        fake.failNextSends.set(1)
        val tokens = fake.requests.count { it.path.endsWith("get_lounge_token_batch") }
        assertTrue(s.sendMessage(listOf(volume(12))))
        assertEquals(tokens + 1, fake.requests.count { it.path.endsWith("get_lounge_token_batch") })
        val sent = fake.sent.getValue("cl")
        assertEquals(1, sent.size)
        assertEquals("0", sent[0]["ofs"], "a refreshed session counts from zero again")
        assertEquals(SessionStatus.RUNNING, s.status)
    }

    @Test
    fun aSecondFailureEndsTheSessionWithThatError() = run {
        val s = session()
        val terminated = CopyOnWriteArrayList<Throwable>()
        s.onTerminate = { terminated.add(it) }
        s.begin()
        fake.failNextSends.set(2)
        assertEquals(false, s.sendMessage(listOf(volume(12))))
        awaitUntil { s.status == SessionStatus.STOPPED && terminated.isNotEmpty() }
    }

    // ---- lifecycle

    private fun count(suffix: String, method: String? = null) =
        fake.requests.count { it.path.endsWith(suffix) && (method == null || it.method == method) }

    @Test
    fun aSecondSessionOnTheSameStoreReusesScreenAndDeviceId() = run {
        val store = MemoryStore()
        val first = session(store)
        first.begin()
        val screen = first.screenId
        val device = first.deviceId
        first.end()
        fake.requests.clear()

        val second = session(store)
        second.begin()
        assertEquals(0, count("generate_screen_id"))
        assertEquals(screen, second.screenId)
        assertEquals(device, second.deviceId)
        assertEquals(screen, fake.requests.first { it.path.endsWith("get_lounge_token_batch") }.form["screen_ids"])
    }

    @Test
    fun aStoredScreenIdTheTokenServiceRefusesIsReplacedOnce() = run {
        val store = MemoryStore()
        val refused = "STOREDSCREEN00000000001"
        store.set(StoreKeys.mdxContext("cl"), JsonObject(mapOf("deviceId" to JsonPrimitive("dev-x"), "screenId" to JsonPrimitive(refused))))
        fake.refuseTokenFor.add(refused)
        val s = session(store)
        s.begin()
        assertEquals(1, count("generate_screen_id"))
        assertEquals(2, count("get_lounge_token_batch"))
        assertTrue(s.screenId != refused)
        assertEquals(SessionStatus.RUNNING, s.status)
    }

    @Test
    fun aStreamThatEndsIsReopened() = run {
        val s = session()
        s.begin()
        assertEquals(1, fake.requests.count { it.method == "GET" && it.query["RID"] == "rpc" })
        fake.closeRpc("cl")
        awaitUntil { fake.requests.count { it.method == "GET" && it.query["RID"] == "rpc" } >= 2 }
        awaitUntil { fake.rpcOpen("cl") == 1 }
        // messages arriving on the new stream are delivered
        val received = CopyOnWriteArrayList<IncomingMessage>()
        s.onMessages = { m, _ -> received.addAll(m) }
        fake.enqueueRpc("cl", "[[40,[\"pause\"]]]")
        awaitUntil { received.any { it.name == "pause" } }
    }

    @Test
    fun threeRefusedConnectsRefreshTheTokenAndBeginAgain() = run {
        val s = session()
        s.begin()
        fake.refuseNextRpc.set(3)
        fake.closeRpc("cl")
        awaitUntil { count("get_lounge_token_batch") >= 2 && fake.rpcOpen("cl") == 1 }
        assertEquals(2, count("bc/bind", "POST"), "a new bind init")
        assertEquals(5, fake.requests.count { it.method == "GET" && it.query["RID"] == "rpc" })
        assertEquals(SessionStatus.RUNNING, s.status)
    }

    @Test
    fun theTokenIsRefreshedWhenItsIntervalElapses() = run {
        fake.refreshIntervalInMillis = 500
        val s = session()
        s.begin()
        awaitUntil(2000) { count("get_lounge_token_batch") >= 2 }
        awaitUntil(2000) { s.status == SessionStatus.RUNNING && fake.rpcOpen("cl") == 1 }
    }

    @Test
    fun endSaysGoodbyeClosesTheStreamAndIsIdempotent() = run {
        val s = session()
        s.begin()
        s.end()
        assertEquals(SessionStatus.STOPPED, s.status)
        val bye = fake.sent.getValue("cl").single()
        assertEquals("loungeScreenDisconnected", bye["req0__sc"])
        awaitUntil { !s.rpcOpen() }
        val before = fake.requests.size
        s.end()
        assertEquals(before, fake.requests.size)
        assertFalse(s.sendMessage(listOf(volume(5))), "a stopped session sends nothing")
    }

    @Test
    fun registerPairingCodePostsTheRecordedFields() = run {
        val s = session()
        s.begin()
        s.registerPairingCode("ABC123")
        val post = fake.requests.last { it.path.endsWith("register_pairing_code") }
        assertEquals(
            listOf("access_type" to "permanent", "app" to "ytcr", "pairing_code" to "ABC123", "screen_id" to s.screenId!!, "screen_name" to "T2U test", "device_id" to s.deviceId),
            post.form.toList(),
        )
        fake.refusePairing = true
        assertFailsWith<LoungeHttpException> { s.registerPairingCode("ZZZ999") }
    }

    // ---- logging (CAST-08, T-02-30)

    private fun flatten(v: Any?): List<String> = when (v) {
        null -> emptyList()
        is Map<*, *> -> v.entries.flatMap { flatten(it.key) + flatten(it.value) }
        is Iterable<*> -> v.flatMap { flatten(it) }
        else -> listOf(v.toString())
    }

    @Test
    fun everyMessageIsLoggedAndNoSecretEverReachesALog() = run {
        val s = session()
        val received = CopyOnWriteArrayList<IncomingMessage>()
        s.onMessages = { m, _ -> received.addAll(m) }
        s.begin()
        fake.replayRecorded("cl")
        awaitUntil { received.size >= 1 + recordedNames("rpc-cl.txt").size }
        assertTrue(received.any { it.name == "setPlaylist" })
        val sentOk = s.sendMessage(listOf(volume(30), OutgoingMessage.autoplayUpNext(null, "VID12345678")))
        assertTrue(sentOk)
        s.registerPairingCode("ABC123")
        s.end()

        val all = events.toList()
        assertTrue(all.isNotEmpty())
        for ((kind, fields) in all) {
            val text = flatten(fields).joinToString(" ")
            for (secret in fake.secrets) assertFalse(secret in text, "$kind leaked a fake secret")
            assertFalse("ABC123" in text, "$kind leaked the pairing code")
            assertFalse(s.screenId!! in text)
            assertFalse(s.deviceId in text)
        }
        val rx = all.filter { it.first == "lounge" && it.second["dir"] == "rx" }
        // the init response is logged whole, although only c, S and loungeStatus are acted on
        assertEquals(
            listOf("c", "S", "loungeStatus", "getNowPlaying", "getDiscoveryDeviceId") + received.drop(1).map { it.name },
            rx.map { it.second["name"] },
        )
        assertTrue(rx.all { it.second["theme"] == "cl" && it.second["aid"] is Int })
        val tx = all.filter { it.first == "lounge" && it.second["dir"] == "tx" }
        assertEquals(listOf("onVolumeChanged", "autoplayUpNext", "loungeScreenDisconnected"), tx.map { it.second["name"] })
    }

    @Test
    fun rawChunksAreLoggedRedactedInPiecesOfAtMost1800Characters() = run {
        val s = session()
        val received = CopyOnWriteArrayList<IncomingMessage>()
        s.onMessages = { m, _ -> received.addAll(m) }
        s.begin()
        val big = "[[60,[\"setPlaylist\",{\"listId\":\"L\",\"videoIds\":\"" + "a".repeat(4500) + "\"}]]]"
        fake.replayRecorded("cl")
        fake.enqueueRpc("cl", big)
        awaitUntil { received.any { it.aid == 60 } }
        val raw = events.filter { it.first == "lounge-raw" }.map { it.second }
        assertTrue(raw.isNotEmpty())
        for (e in raw) {
            assertEquals("cl", e["theme"])
            assertTrue((e["data"] as String).length <= 1800)
            assertTrue(e["seq"] is Int && e["part"] is Int && e["parts"] is Int)
        }
        val bySeq = raw.groupBy { it["seq"] as Int }.mapValues { (_, parts) ->
            parts.sortedBy { it["part"] as Int }.also { assertEquals(it.size, it[0]["parts"]) }.joinToString("") { it["data"] as String }
        }
        // every RPC payload the fake served shows up, redacted, once its pieces are joined
        for (served in fake.served.getValue("cl")) {
            assertTrue(Redact.text(served) in bySeq.values, "a served chunk is missing from lounge-raw")
        }
        assertTrue(raw.any { (it["parts"] as Int) >= 3 }, "the 4500 character chunk is split")
    }
}
