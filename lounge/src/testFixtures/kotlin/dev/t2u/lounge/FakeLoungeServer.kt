package dev.t2u.lounge

// Test fake of the YouTube lounge endpoints (CAST-08), serving the traffic recorded from the Node
// receiver (yt-cast-receiver 2.1.0, MIT, Copyright (c) Patrick Kan) in plan 02-06. A Ktor CIO server on
// 127.0.0.1; the Session under test gets [endpoints] instead of https://www.youtube.com.
//
// The fixtures hold placeholders R-<kind>-<n>. This fake swaps every placeholder for a deterministic fake
// secret ("SECRET<kind><n>X" padded to 24 characters) and re-frames each chunk with its new UTF-16 length,
// so tests can prove no secret ever reaches a log ([secrets]).

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One request as the fake saw it. [query] is decoded, [rawQuery] is exactly what was on the wire. */
class LoungeRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val rawQuery: String,
    val form: Map<String, String>,
    val userAgent: String?,
)

class FakeLoungeServer : AutoCloseable {
    /** Every request in arrival order. */
    val requests: MutableList<LoungeRequest> = CopyOnWriteArrayList()

    /** Decoded form fields of every message POST (bind with count >= 1), per theme. */
    val sent: MutableMap<String, MutableList<Map<String, String>>> = ConcurrentHashMap()

    /** Every fake secret this server handed out; tests assert none of them reaches a log. */
    val secrets: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The RPC chunk payloads (without the length line) served per theme, after placeholder substitution. */
    val served: MutableMap<String, MutableList<String>> = ConcurrentHashMap()

    /** Overrides refreshIntervalInMillis in the token batch when set. */
    @Volatile
    var refreshIntervalInMillis: Long? = null

    /** screenIds that get 400 from get_lounge_token_batch. */
    val refuseTokenFor: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** When set, get_lounge_token_batch answers 400 for every screen. */
    @Volatile
    var refuseAllTokens: Boolean = false

    /** The next this many RPC GETs answer 503. */
    val refuseNextRpc = AtomicInteger(0)

    @Volatile
    var refusePairing: Boolean = false

    /** The next this many message POSTs (bind with count >= 1) answer 500 and are not recorded in [sent]. */
    val failNextSends = AtomicInteger(0)

    /** Holds message POST responses while set. */
    @Volatile
    var sendGate: CompletableDeferred<Unit>? = null

    private val screenIdCalls = AtomicInteger(0)
    private val freshCounter = AtomicInteger(0)
    private val rpcStates = ConcurrentHashMap<String, RpcState>()

    private val server: EmbeddedServer<*, *>

    /** Base http://127.0.0.1:<port>. */
    val endpoints: LoungeEndpoints

    init {
        val s = embeddedServer(CIO, serverConfig { module { routes() } }) {
            reuseAddress = true
            connectors.add(
                EngineConnectorBuilder().apply {
                    host = "127.0.0.1"
                    this.port = 0
                },
            )
        }
        s.start(wait = false)
        val port = runBlocking { s.engine.resolvedConnectors().first().port }
        server = s
        endpoints = LoungeEndpoints("http://127.0.0.1:$port")
    }

    override fun close() {
        rpcStates.values.forEach { st -> synchronized(st) { st.current?.close() } }
        server.stop(50, 500)
    }

    /** Queues every chunk of the recorded rpc-<theme>.txt (placeholders substituted, chunks re-framed). */
    fun replayRecorded(theme: String) {
        for (payload in splitChunks(fixture("rpc-$theme.txt"))) push(theme, substitute(payload))
    }

    /** Frames [payloadJson] the way the lounge does (length counts the payload plus its trailing newline) and queues it. */
    fun enqueueRpc(theme: String, payloadJson: String) {
        push(theme, payloadJson + "\n")
    }

    /** Ends the open RPC response for [theme] normally (the client sees the end of the stream). No-op without an open one. */
    fun closeRpc(theme: String) {
        val st = rpcStates.getOrPut(theme) { RpcState() }
        synchronized(st) { st.current?.trySend(RpcItem.Close) }
    }

    /** Number of RPC GETs currently open for [theme]. */
    fun rpcOpen(theme: String): Int = rpcStates[theme]?.let { synchronized(it) { if (it.current != null) 1 else 0 } } ?: 0

    // ---- routes

    private fun Application.routes() {
        routing {
            get("/api/lounge/pairing/generate_screen_id") {
                record(call)
                val n = screenIdCalls.incrementAndGet()
                val text = when (n) {
                    1 -> fixture("generate-screen-id.txt")
                    2 -> fixture("generate-screen-id-2.txt")
                    else -> "R-screen-$n"
                }
                call.respondText(substitute(text).trim(), PLAIN)
            }
            post("/api/lounge/pairing/get_lounge_token_batch") {
                val form = record(call)
                val requested = form["screen_ids"] ?: ""
                if (refuseAllTokens || requested in refuseTokenFor) {
                    call.respondText("{}", ContentType.Application.Json, HttpStatusCode.BadRequest)
                } else {
                    call.respondText(tokenBatch(requested), ContentType.Application.Json)
                }
            }
            post("/api/lounge/pairing/register_pairing_code") {
                record(call)
                if (refusePairing) call.respondText("", PLAIN, HttpStatusCode.BadRequest) else call.respondText("", PLAIN)
            }
            route("/api/lounge/bc/bind") {
                get {
                    val req = record(call)
                    if (req["RID"] != "rpc") {
                        call.respondText("", PLAIN, HttpStatusCode.BadRequest)
                    } else {
                        rpc(call, call.request.queryParametersFirst("theme") ?: "cl")
                    }
                }
                post {
                    val form = record(call)
                    val theme = call.request.queryParametersFirst("theme") ?: "cl"
                    if ((form["count"] ?: "0") == "0") {
                        call.respondText(handshake(theme), PLAIN)
                    } else if (failNextSends.getAndUpdate { if (it > 0) it - 1 else it } > 0) {
                        call.respondText("", PLAIN, HttpStatusCode.InternalServerError)
                    } else {
                        sent.getOrPut(theme) { CopyOnWriteArrayList() }.add(form)
                        sendGate?.await()
                        call.respondText("7\n[1,4,0]", PLAIN)
                    }
                }
            }
        }
    }

    private suspend fun rpc(call: ApplicationCall, theme: String) {
        val refused = refuseNextRpc.getAndUpdate { if (it > 0) it - 1 else it } > 0
        if (refused) {
            call.respondText("", PLAIN, HttpStatusCode.ServiceUnavailable)
            return
        }
        val st = rpcStates.getOrPut(theme) { RpcState() }
        val mine = Channel<RpcItem>(Channel.UNLIMITED)
        synchronized(st) {
            // a newer connection supersedes the older one, so queued chunks are never taken by a dead socket
            st.current?.close()
            st.current = mine
            while (st.pending.isNotEmpty()) mine.trySend(st.pending.removeFirst())
        }
        try {
            call.respondBytesWriter(PLAIN) {
                // a first newline makes the response headers leave at once; the decoder skips blank lines
                writeFully("\n".toByteArray())
                flush()
                for (item in mine) {
                    when (item) {
                        is RpcItem.Chunk -> {
                            writeFully(item.bytes)
                            flush()
                        }
                        RpcItem.Close -> return@respondBytesWriter
                    }
                }
            }
        } finally {
            synchronized(st) { if (st.current === mine) st.current = null }
        }
    }

    // ---- fixtures and placeholders

    private fun push(theme: String, payload: String) {
        served.getOrPut(theme) { CopyOnWriteArrayList() }.add(payload)
        val item = RpcItem.Chunk("${payload.length}\n$payload".toByteArray(Charsets.UTF_8))
        val st = rpcStates.getOrPut(theme) { RpcState() }
        synchronized(st) { st.current?.trySend(item) ?: st.pending.addLast(item) }
    }

    private fun handshake(theme: String): String {
        val name = if (resourceExists("/fixtures/handshake-$theme.txt")) "handshake-$theme.txt" else "handshake.txt"
        return splitChunks(fixture(name)).joinToString("") { p ->
            val s = substitute(p)
            "${s.length}\n$s"
        }
    }

    private fun tokenBatch(requested: String): String {
        val tpl = Json.parseToJsonElement(fixture("token-batch.json")).jsonObject["screens"]!!.jsonArray[0].jsonObject
        val fresh = "SECRETtokenF${freshCounter.incrementAndGet()}X".padEnd(24, '0')
        secrets.add(fresh)
        val screen = JsonObject(
            tpl.mapValues { (k, v) ->
                when (k) {
                    "screenId" -> JsonPrimitive(requested)
                    "loungeToken" -> JsonPrimitive(fresh)
                    "refreshIntervalInMillis" -> refreshIntervalInMillis?.let { JsonPrimitive(it) } ?: v
                    else -> v
                }
            },
        )
        return JsonObject(mapOf("screens" to JsonArray(listOf(screen)))).toString()
    }

    private fun substitute(text: String): String = PLACEHOLDER.replace(text) { m ->
        val secret = "SECRET${m.groupValues[1]}${m.groupValues[2]}X".padEnd(24, '0')
        secrets.add(secret)
        secret
    }

    private fun fixture(name: String): String {
        val stream = FakeLoungeServer::class.java.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    private fun resourceExists(path: String): Boolean = FakeLoungeServer::class.java.getResource(path) != null

    /** An independent `<len>\n<payload>` splitter: the payload is exactly len UTF-16 code units. */
    private fun splitChunks(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val nl = text.indexOf('\n', i)
            if (nl < 0) break
            val len = text.substring(i, nl).trim().toIntOrNull()
            if (len == null) {
                i = nl + 1
                continue
            }
            out.add(text.substring(nl + 1, nl + 1 + len))
            i = nl + 1 + len
        }
        return out
    }

    private suspend fun record(call: ApplicationCall): Map<String, String> {
        val form = if (call.request.httpMethod.value == "POST") parseForm(call.receiveText()) else emptyMap()
        val query = LinkedHashMap<String, String>()
        call.request.queryParameters.entries().forEach { (k, v) -> query[k] = v.firstOrNull() ?: "" }
        requests.add(
            LoungeRequest(
                method = call.request.httpMethod.value,
                path = call.request.local.uri.substringBefore('?'),
                query = query,
                rawQuery = call.request.local.uri.substringAfter('?', ""),
                form = form,
                userAgent = call.request.headers["User-Agent"],
            ),
        )
        return if (call.request.httpMethod.value == "POST") form else query
    }

    private fun parseForm(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (body.isEmpty()) return out
        for (pair in body.split('&')) {
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return out
    }

    private fun io.ktor.server.request.ApplicationRequest.queryParametersFirst(name: String): String? = queryParameters[name]

    private sealed interface RpcItem {
        class Chunk(val bytes: ByteArray) : RpcItem

        data object Close : RpcItem
    }

    private class RpcState {
        var current: Channel<RpcItem>? = null
        val pending = ArrayDeque<RpcItem>()
    }

    private companion object {
        val PLAIN: ContentType = ContentType.parse("text/plain; charset=utf-8")
        val PLACEHOLDER = Regex("R-([a-z]+)-(\\d+)")
    }
}
