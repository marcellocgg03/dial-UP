package dev.t2u.lounge

// Tests for the lounge Session (port of yt-cast-receiver 2.1.0 lib/app/Session.js and RPCConnection.js,
// MIT, Copyright (c) Patrick Kan) against FakeLoungeServer, which replays the traffic recorded from the Node receiver.

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
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
}
