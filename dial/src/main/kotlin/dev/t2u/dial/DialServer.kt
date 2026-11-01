// DIAL receiver HTTP routes ported from peer-dial (@patrickkfkan/peer-dial, a fork of the original).
// Copyright (c) 2015 Louay Bassbouss, Fraunhofer FOKUS, All rights reserved.
// peer-dial is licensed under the GNU Lesser General Public License, version 3.0 or later;
// fork by Patrick Kan; ported to Kotlin for Tube2U[PnP] and conveyed under GPL-3.0-only.
package dev.t2u.dial

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class DialConfig(
    val friendlyName: String,
    val uuid: String,
    val bindHost: String,
    val port: Int,
    val manufacturer: String = "Tube2U[PnP]",
    val modelName: String = "UPnP Renderer",
    val prefix: String = "/ytcr",
    val appState: String = "running",
    /** Persisted pid for the run link and the launch LOCATION; null means a random pid per server. */
    val pid: String? = null,
    /** Decides per request, from the remote address string, whether a call is served (SVC-09). */
    val accessPolicy: (String) -> Boolean = { true },
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
class DialServer(
    private val config: DialConfig,
    private val onRequest: (DialRequest) -> Unit,
    /**
     * Called for POST /apps/YouTube with the app name and the capped body (pairing code included).
     * The returned pid becomes the LOCATION; null or empty sends no LOCATION; an exception answers 503.
     * Null (the Phase 1 behaviour) answers with the server's own pid.
     */
    private val onLaunch: (suspend (appName: String, body: String) -> String?)?,
) {
    /** Without a launch callback; keeps `DialServer(config) { request -> ... }` working. */
    constructor(config: DialConfig, onRequest: (DialRequest) -> Unit) : this(config, onRequest, null)

    private var server: EmbeddedServer<*, *>? = null

    /** The friendlyName the device description carries; starts as [DialConfig.friendlyName] and changes with [rename]. */
    @Volatile private var friendlyName: String = config.friendlyName

    /**
     * GUI rename (261101-dcg): the next device-desc.xml answer carries [name] as its friendlyName, XML-escaped by [DialXml].
     * The caller validates the text (length, no control characters).
     */
    fun rename(name: String) {
        friendlyName = name
    }

    @Volatile private var boundPort: Int = config.port

    /** Pid handed out in the run link and in the POST LOCATION header; fixed for this server's lifetime. */
    private val pid: String = config.pid ?: UUID.randomUUID().toString()

    /**
     * Binds and starts the engine. A port another listener still holds (the old bridge during a restart)
     * throws [BindException] to the caller and leaves this instance startable again; Ktor's accept job
     * must never throw it on a thread whose uncaught handler ends the process (CR-01 b).
     */
    fun start() {
        check(server == null) { "already started" }
        // Pre-bind check: Ktor CIO binds inside its accept job, where a failure is uncaught. reuseAddress
        // keeps TIME_WAIT from failing it, as for the real bind below.
        if (config.port != 0) requireBindable(config.bindHost, config.port)
        // Safety net for the race between the check and the bind: the failure is recorded, not thrown on a thread.
        val asyncFailure = AtomicReference<Throwable?>(null)
        val handler = CoroutineExceptionHandler { _, e -> asyncFailure.compareAndSet(null, e) }
        // reuseAddress: a restart (variant change, Wi-Fi rebind) must not hit EADDRINUSE from TIME_WAIT
        val s = embeddedServer(CIO, serverConfig {
            parentCoroutineContext = SupervisorJob() + handler
            module { configure(this) }
        }) {
            reuseAddress = true
            connectors.add(
                EngineConnectorBuilder().apply {
                    host = config.bindHost
                    port = config.port
                },
            )
        }
        try {
            s.start(wait = false)
            boundPort = runBlocking { s.engine.resolvedConnectors().first().port }
        } catch (e: Throwable) {
            runCatching { s.stop(0, 0) }
            val cause = asyncFailure.get() ?: e
            throw cause as? BindException
                ?: BindException("DIAL server cannot bind ${config.bindHost}:${config.port}: ${cause.message}").also { it.initCause(cause) }
        }
        server = s
    }

    private fun requireBindable(host: String, port: Int) {
        ServerSocket().use { probe ->
            probe.reuseAddress = true
            try {
                probe.bind(InetSocketAddress(host, port))
            } catch (e: BindException) {
                throw BindException("DIAL port $host:$port is busy: ${e.message}").also { it.initCause(e) }
            }
        }
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

        // Plugins runs before routing: a refused call never reads its body and never reaches onLaunch.
        app.intercept(ApplicationCallPipeline.Plugins) {
            if (!config.accessPolicy(call.request.local.remoteAddress)) {
                sendStatus(call, HttpStatusCode.Forbidden)
                finish()
            }
        }

        app.routing {
            route(prefix) {
                get("/ssdp/device-desc.xml") {
                    val base = baseUrl(call)
                    val xml = DialXml.deviceDesc(base, friendlyName, config.manufacturer, config.modelName, config.uuid)
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
                        // the pairing code must never reach the request report
                        redactLaunchBody(body)?.let { call.attributes.put(BODY_KEY, it) }
                        val launch = onLaunch
                        val locationPid: String? = if (launch == null) {
                            pid
                        } else {
                            try {
                                launch(name, body)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                sendStatus(call, HttpStatusCode.ServiceUnavailable)
                                return@post
                            }
                        }
                        if (!locationPid.isNullOrEmpty()) call.response.header("LOCATION", "${baseUrl(call)}/apps/$APP/$locationPid")
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
                        redactLaunchBody(body)?.let { call.attributes.put(BODY_KEY, it) }
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
