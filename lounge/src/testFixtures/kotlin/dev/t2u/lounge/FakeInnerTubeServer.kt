package dev.t2u.lounge

// Test fake of the InnerTube /youtubei/v1/next endpoint (CAST-04, CAST-05). A Ktor CIO server on 127.0.0.1 that serves
// the hand-trimmed responses under resources/innertube/ (shapes recorded in 03-RESEARCH.md Pattern 1) and records every
// request. The handler under test gets [endpoints] instead of https://www.youtube.com.

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** One request as the fake saw it. [headers] has lower-cased names; [body] is the parsed JSON object (empty when not JSON). */
class InnerTubeRequest(
    val method: String,
    val path: String,
    val rawQuery: String,
    val headers: Map<String, String>,
    val body: JsonObject,
)

class FakeInnerTubeServer : AutoCloseable {
    /** Every POST /youtubei/v1/next in arrival order. */
    val requests: MutableList<InnerTubeRequest> = CopyOnWriteArrayList()

    /** Overrides the HTTP status of every answer when not 200. */
    @Volatile
    var status: Int = 200

    /** Delays every answer, to test cancellation and the call timeout. */
    @Volatile
    var delayMs: Long = 0

    /** When set, this text is the body of every answer (malformed JSON, oversized bodies). */
    @Volatile
    var rawResponse: String? = null

    /** The fixture served when no per-video answer matches. */
    @Volatile
    var defaultFixture: String = "next-middle"

    private val perVideo = ConcurrentHashMap<String, String>()
    private val server: EmbeddedServer<*, *>

    /** Base http://127.0.0.1:<port>. */
    val endpoints: InnerTubeEndpoints

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
        endpoints = InnerTubeEndpoints("http://127.0.0.1:$port")
    }

    /** Answers a request whose body has this videoId with the fixture resource `innertube/<fixture>.json`. */
    fun on(videoId: String, fixture: String) {
        perVideo[videoId] = readFixture(fixture)
    }

    /** Answers a request whose body has this videoId with literal JSON [json]. */
    fun onJson(videoId: String, json: String) {
        perVideo[videoId] = json
    }

    override fun close() {
        server.stop(50, 500)
    }

    private fun Application.routes() {
        routing {
            post("/youtubei/v1/next") {
                val text = call.receiveText()
                val body = try {
                    Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }
                requests.add(
                    InnerTubeRequest(
                        method = call.request.local.method.value,
                        path = call.request.local.uri.substringBefore('?'),
                        rawQuery = call.request.local.uri.substringAfter('?', ""),
                        headers = call.request.headers.entries().associate { (k, v) -> k.lowercase() to v.joinToString(",") },
                        body = body,
                    ),
                )
                if (delayMs > 0) delay(delayMs)
                val code = HttpStatusCode.fromValue(status)
                val videoId = (body["videoId"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val out = rawResponse ?: perVideo[videoId] ?: readFixture(defaultFixture)
                call.respondText(out, ContentType.Application.Json, code)
            }
        }
    }

    private fun readFixture(name: String): String {
        val stream = FakeInnerTubeServer::class.java.getResourceAsStream("/innertube/$name.json")
            ?: error("missing fixture innertube/$name.json")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
