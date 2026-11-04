package dev.t2u.lounge

// Test fake of the InnerTube /youtubei/v1/next endpoint (CAST-04, CAST-05). A Ktor CIO server on 127.0.0.1 that serves
// the hand-trimmed responses under resources/innertube/ (shapes recorded from real responses) and records every
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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

    /**
     * Scripts the answer for [videoId] in the shapes of the next-middle and next-last-item fixtures,
     * so the handler's real parser paths run. [index] is the index of [videoId] in its list: the previous
     * video gets index - 1 and the next one index + 1. With a [nextId] the answer is the middle shape (the next video
     * is also the autoplay video unless [autoplayId] names another). With only an [autoplayId] it is the last-item
     * shape: the next renderer is the maybeHistory endpoint (no playlist, no index) and the autoplay renderer the
     * autonav endpoint. All ids must be valid 11-character video ids. Keep every shape here, in this one function.
     */
    fun scriptNext(videoId: String, previousId: String?, nextId: String?, autoplayId: String? = null, playlistId: String = "PLTEST", index: Int = 0) {
        val params = "EAEYAcABAdoBBAgBKgDyBQIKAA%3D%3D"
        fun watch(id: String, withList: Boolean, at: Int) = buildJsonObject {
            put("videoId", id)
            if (withList) {
                put("playlistId", playlistId)
                put("index", at)
            }
            put("params", params)
        }
        fun renderer(kind: String, id: String, withList: Boolean, at: Int) = buildJsonObject {
            putJsonObject(kind) { putJsonObject("endpoint") { put("watchEndpoint", watch(id, withList, at)) } }
        }
        val set = buildJsonObject {
            put("mode", "NORMAL")
            if (previousId != null) put("previousVideoRenderer", renderer("autoplayEndpointRenderer", previousId, true, (index - 1).coerceAtLeast(0)))
            if (nextId != null) {
                put("nextVideoRenderer", renderer("autoplayEndpointRenderer", nextId, true, index + 1))
                put(
                    "autoplayVideoRenderer",
                    if (autoplayId != null) renderer("autonavEndpointRenderer", autoplayId, false, 0) else renderer("autoplayEndpointRenderer", nextId, true, index + 1),
                )
            } else if (autoplayId != null) {
                put("nextVideoRenderer", renderer("maybeHistoryEndpointRenderer", autoplayId, false, 0))
                put("autoplayVideoRenderer", renderer("autonavEndpointRenderer", autoplayId, false, 0))
            }
        }
        val root = buildJsonObject {
            putJsonObject("responseContext") { put("visitorData", "SERVERVISITOR1") }
            putJsonObject("contents") {
                putJsonObject("singleColumnWatchNextResults") {
                    putJsonObject("autoplay") {
                        putJsonObject("autoplay") { put("sets", kotlinx.serialization.json.JsonArray(listOf(set))) }
                    }
                }
            }
        }
        perVideo[videoId] = root.toString()
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
