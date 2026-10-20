package dev.t2u.dial

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.httpVersion
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import java.util.UUID

data class DialConfig(
    val friendlyName: String,
    val uuid: String,
    val bindHost: String,
    val port: Int,
    val manufacturer: String = "Tube2U[PnP]",
    val modelName: String = "UPnP Renderer",
    val prefix: String = "/ytcr",
    val appState: String = "running",
)

/** One handled HTTP call, reported after the response was produced (404s included). */
data class DialRequest(
    val method: String,
    val uri: String,
    val httpVersion: String,
    val remoteHost: String,
    val remotePort: Int,
    val status: Int,
    val headers: Map<String, String>,
    val body: String?,
)

/**
 * DIAL receiver HTTP side, a port of the peer-dial routes under [DialConfig.prefix]. One Ktor CIO
 * server per instance. The host in Application-URL and LOCATION always comes from the request Host
 * header (peer-dial uses req.hostname). Content-Length is never set by hand (RESEARCH Pitfall 4).
 */
class DialServer(private val config: DialConfig, private val onRequest: (DialRequest) -> Unit) {
    private var server: EmbeddedServer<*, *>? = null

    @Volatile private var boundPort: Int = config.port

    /** Pid handed out in the run link and in the POST LOCATION header; fixed for this server's lifetime. */
    private val pid: String = UUID.randomUUID().toString()

    fun start() {
        check(server == null) { "already started" }
        val s = embeddedServer(CIO, port = config.port, host = config.bindHost) { configure(this) }
        s.start(wait = false)
        boundPort = runBlocking { s.engine.resolvedConnectors().first().port }
        server = s
    }

    fun stop() {
        server?.stop(200, 1000)
        server = null
    }

    fun resolvedPort(): Int = boundPort

    /** Installs the routes into [app]; used by [start] and by testApplication tests. */
    fun configure(app: Application) {
        val prefix = config.prefix
        val state = config.appState

        app.intercept(ApplicationCallPipeline.Monitoring) {
            try {
                proceed()
            } finally {
                report(call)
            }
        }

        app.routing {
            route(prefix) {
                get("/ssdp/device-desc.xml") {
                    val base = baseUrl(call)
                    val xml = DialXml.deviceDesc(base, config.friendlyName, config.manufacturer, config.modelName, config.uuid)
                    call.response.header("Application-URL", "$base/apps")
                    call.respondText(xml, XML_UTF8)
                }
                get("/ssdp/notfound") { sendStatus(call, HttpStatusCode.NotFound) }

                route("/apps") {
                    get { call.respond(HttpStatusCode.NoContent) }
                    get("/{appName}") {
                        val name = call.parameters["appName"]
                        if (name != APP) {
                            sendStatus(call, HttpStatusCode.NotFound)
                            return@get
                        }
                        val xml = DialXml.appDesc(name, state, allowStop = false, runHref = if (state == "running") pid else null)
                        call.respondText(xml, XML_UTF8)
                    }
                    post("/{appName}") {
                        val name = call.parameters["appName"]
                        if (name != APP) {
                            sendStatus(call, HttpStatusCode.NotFound)
                            return@post
                        }
                        val body = readBodyCapped(call)
                        if (body == null) {
                            sendStatus(call, HttpStatusCode.PayloadTooLarge)
                            return@post
                        }
                        call.attributes.put(BODY_KEY, body)
                        call.response.header("LOCATION", "${baseUrl(call)}/apps/$APP/$pid")
                        sendStatus(call, if (state == "stopped") HttpStatusCode.Created else HttpStatusCode.OK)
                    }
                    post("/{appName}/dial_data") {
                        val name = call.parameters["appName"]
                        if (name != APP) {
                            sendStatus(call, HttpStatusCode.NotFound)
                            return@post
                        }
                        val body = readBodyCapped(call)
                        if (body == null) {
                            sendStatus(call, HttpStatusCode.PayloadTooLarge)
                            return@post
                        }
                        call.attributes.put(BODY_KEY, body)
                        sendStatus(call, HttpStatusCode.NotImplemented)
                    }
                    delete("/{appName}/{pid}") {
                        val name = call.parameters["appName"]
                        // allowStop is false, so a known app answers 405 (peer-dial)
                        sendStatus(call, if (name == APP) HttpStatusCode.MethodNotAllowed else HttpStatusCode.NotFound)
                    }
                }
            }
        }
    }

    private fun baseUrl(call: ApplicationCall): String = "http://${call.request.host()}:$boundPort${config.prefix}"

    /** peer-dial's rsp.sendStatus: the status description as a text/plain body. */
    private suspend fun sendStatus(call: ApplicationCall, status: HttpStatusCode) {
        call.respondText(status.description, ContentType.Text.Plain, status)
    }

    /**
     * Reads the POST body with a [MAX_BODY] cap (peer-dial maxContentLength). Returns null when the
     * declared Content-Length or the bytes actually arriving exceed the cap, without reading further.
     */
    private suspend fun readBodyCapped(call: ApplicationCall): String? {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > MAX_BODY) return null
        val bytes = call.receiveChannel().readBuffer((MAX_BODY + 1).toLong()).readByteArray()
        if (bytes.size > MAX_BODY) return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun report(call: ApplicationCall) {
        try {
            val req = call.request
            val headers = LinkedHashMap<String, String>()
            for (name in LOGGED_HEADERS) req.headers[name]?.let { headers[name] = it }
            val origin = req.local
            onRequest(
                DialRequest(
                    method = req.httpMethod.value,
                    uri = req.uri,
                    httpVersion = req.httpVersion,
                    remoteHost = origin.remoteAddress,
                    remotePort = origin.remotePort,
                    status = call.response.status()?.value ?: 0,
                    headers = headers,
                    body = call.attributes.getOrNull(BODY_KEY),
                ),
            )
        } catch (_: Throwable) {
            // reporting must never break request handling
        }
    }

    companion object {
        const val MAX_BODY = 4096
        private const val APP = "YouTube"
        private val XML_UTF8 = ContentType.parse("application/xml; charset=utf-8")
        private val BODY_KEY = AttributeKey<String>("dial-body")
        private val LOGGED_HEADERS = listOf(
            HttpHeaders.UserAgent,
            HttpHeaders.Host,
            HttpHeaders.Origin,
            HttpHeaders.ContentType,
            HttpHeaders.ContentLength,
        )
    }
}
