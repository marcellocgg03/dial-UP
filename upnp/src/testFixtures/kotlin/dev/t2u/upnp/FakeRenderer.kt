package dev.t2u.upnp

// Port of tube2upnp test/fakeRenderer.js (GPL-3.0-only), as a Ktor CIO server.
//
// A fake UPnP MediaRenderer that exposes itself as an EMBEDDED device (like Sonos/AVRs) and uses a
// non-standard serviceId spelling; both broke the original upnpTube. Kotlin additions over the Node
// fake (also listed in PARITY.md "Adaptations"): SetVolume stores DesiredVolume into state.volume,
// SetMute stores DesiredMute into state.mute, GetMute answers CurrentMute 1 or 0. Every other
// action answers an empty u:<Action>Response, as the Node fake does. Plan 02-15 adds the track metadata
// echo: SetAVTransportURI keeps the (unescaped) CurrentURIMetaData in state.currentMetaData and
// GetPositionInfo answers it as TrackMetaData, or state.trackMetaData when a test sets one (a speaker
// that rewrites or drops the metadata, or answers NOT_IMPLEMENTED).

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList

/** One SOAP request as the fake received it; [contentLength] is the header, [bytes] what actually arrived. */
data class FakeCall(
    val service: String,
    val action: String,
    val body: String,
    val contentLength: Long,
    val bytes: Int,
)

/** Mutable renderer state; tests poke it directly (for example [trackDuration], [reportedUri]). */
class FakeState {
    @Volatile var transport: String = "STOPPED"

    @Volatile var volume: Int = 30

    @Volatile var mute: Boolean = false

    @Volatile var currentUri: String = ""

    @Volatile var nextUri: String = ""

    @Volatile var trackDuration: String? = null

    @Volatile var relTime: String? = null

    @Volatile var reportedUri: String? = null

    /** The DIDL of the last SetAVTransportURI, unescaped once as a speaker would read it. */
    @Volatile var currentMetaData: String = ""

    /** What GetPositionInfo answers as TrackMetaData instead of [currentMetaData], when not null. */
    @Volatile var trackMetaData: String? = null
}

class FakeRenderer(
    private val sink: String = "http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*",
    private val neverPlay: Boolean = false,
    private val bareFaultOn: Set<String> = emptySet(),
    private val noGapless: Boolean = false,
) : AutoCloseable {
    val calls: MutableList<FakeCall> = CopyOnWriteArrayList()
    val state = FakeState()

    private var server: EmbeddedServer<*, *>? = null

    @Volatile private var port: Int = 0

    /** http://127.0.0.1:<port>/desc.xml, valid after [start]. */
    val location: String get() = "http://127.0.0.1:$port/desc.xml"

    fun start() {
        check(server == null) { "already started" }
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
        port = runBlocking { s.engine.resolvedConnectors().first().port }
        server = s
    }

    override fun close() {
        server?.stop(50, 500)
        server = null
    }

    /** Simulate the renderer reaching the end of the track and playing on into the queued one. */
    fun advance() {
        state.currentUri = state.nextUri
        state.nextUri = ""
        state.transport = "PLAYING"
    }

    private fun io.ktor.server.application.Application.routes() {
        routing {
            get("/desc.xml") { call.respondText(description(), ContentType.Text.Xml) }
            get("/scpd/{svc}.xml") {
                val svc = call.parameters["svc"] ?: ""
                if (svc in ACTIONS) call.respondText(scpd(svc), ContentType.Text.Xml) else call.respondText("", status = HttpStatusCode.NotFound)
            }
            post("/ctl/{svc}") { control(call.parameters["svc"] ?: "") }
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.control(svc: String) {
        val raw = call.receive<ByteArray>()
        val body = String(raw, Charsets.UTF_8)
        val header = call.request.headers["SOAPACTION"] ?: ""
        val action = header.replace("\"", "").substringAfter('#', "")
        val declared = call.request.headers["Content-Length"]?.toLongOrNull() ?: -1L
        calls.add(FakeCall(svc, action, body, declared, raw.size))

        // Some real renderers answer errors with a bare 500 and no UPnPError body.
        if (action in bareFaultOn) {
            call.respondText("<html>Internal Error</html>", ContentType.Text.Html, HttpStatusCode.InternalServerError)
            return
        }
        if (action == "SetNextAVTransportURI" && noGapless) {
            call.respondText(
                """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>""" +
                    """<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>401</errorCode>""" +
                    """<errorDescription>Invalid Action</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>""",
                ContentType.Text.Xml,
                HttpStatusCode.InternalServerError,
            )
            return
        }

        var out = ""
        when (action) {
            "SetAVTransportURI" -> {
                state.currentUri = unescape(tag(body, "CurrentURI"))
                state.currentMetaData = unescape(tag(body, "CurrentURIMetaData"))
            }
            "SetNextAVTransportURI" -> state.nextUri = unescape(tag(body, "NextURI"))
            "Play" -> state.transport = if (neverPlay) "STOPPED" else "PLAYING"
            "Stop" -> state.transport = "STOPPED"
            "Pause" -> state.transport = "PAUSED_PLAYBACK"
            "SetVolume" -> tag(body, "DesiredVolume").toIntOrNull()?.let { state.volume = it }
            "SetMute" -> state.mute = tag(body, "DesiredMute").let { it == "1" || it.equals("true", ignoreCase = true) }
            "GetTransportInfo" ->
                out = "<CurrentTransportState>${state.transport}</CurrentTransportState>" +
                    "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"
            "GetPositionInfo" ->
                out = "<TrackDuration>${state.trackDuration ?: "0:03:30"}</TrackDuration>" +
                    "<TrackURI>${xmlText(state.reportedUri ?: state.currentUri)}</TrackURI>" +
                    "<RelTime>${state.relTime ?: "0:01:05"}</RelTime>" +
                    "<TrackMetaData>${xmlText(state.trackMetaData ?: state.currentMetaData)}</TrackMetaData>"
            "GetMediaInfo" -> out = "<CurrentURI>${xmlText(state.currentUri)}</CurrentURI>"
            "GetVolume" -> out = "<CurrentVolume>${state.volume}</CurrentVolume>"
            "GetMute" -> out = "<CurrentMute>${if (state.mute) 1 else 0}</CurrentMute>"
            "GetProtocolInfo" -> out = "<Source></Source><Sink>$sink</Sink>"
        }
        call.respondText(
            """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                """<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$svc:1">$out</u:${action}Response></s:Body></s:Envelope>""",
            ContentType.Text.Xml,
        )
    }

    private fun tag(body: String, name: String): String =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1) ?: ""

    private fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun xmlText(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun description(): String {
        fun svc(s: String) =
            "<service><serviceType>urn:schemas-upnp-org:service:$s:1</serviceType>" +
                "<serviceId>urn:schemas-upnp-org:serviceId:$s</serviceId>" +
                "<SCPDURL>/scpd/$s.xml</SCPDURL><controlURL>/ctl/$s</controlURL><eventSubURL>/evt/$s</eventSubURL></service>"
        return """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>""" +
            "<deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType><friendlyName>Living Room</friendlyName>" +
            "<manufacturer>Acme</manufacturer><modelName>Box</modelName><UDN>uuid:root</UDN>" +
            "<deviceList><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>" +
            "<friendlyName>Living Room Speaker</friendlyName><manufacturer>Acme</manufacturer><modelName>Speaker 1</modelName>" +
            "<UDN>uuid:fake-renderer</UDN><serviceList>${svc("AVTransport")}${svc("RenderingControl")}${svc("ConnectionManager")}</serviceList>" +
            "</device></deviceList></device></root>"
    }

    private fun scpd(svc: String): String =
        """<?xml version="1.0"?><scpd xmlns="urn:schemas-upnp-org:service-1-0"><actionList>""" +
            ACTIONS.getValue(svc).entries.joinToString("") { (a, args) ->
                "<action><name>$a</name><argumentList>" +
                    args.joinToString("") { (n, d) -> "<argument><name>$n</name><direction>$d</direction></argument>" } +
                    "</argumentList></action>"
            } + "</actionList></scpd>"

    companion object {
        private val ACTIONS: Map<String, Map<String, List<Pair<String, String>>>> = mapOf(
            "AVTransport" to mapOf(
                "SetAVTransportURI" to listOf("InstanceID" to "in", "CurrentURI" to "in", "CurrentURIMetaData" to "in"),
                "SetNextAVTransportURI" to listOf("InstanceID" to "in", "NextURI" to "in", "NextURIMetaData" to "in"),
                "GetMediaInfo" to listOf("InstanceID" to "in", "CurrentURI" to "out"),
                "Play" to listOf("InstanceID" to "in", "Speed" to "in"),
                "Pause" to listOf("InstanceID" to "in"),
                "Stop" to listOf("InstanceID" to "in"),
                "Seek" to listOf("InstanceID" to "in", "Unit" to "in", "Target" to "in"),
                "GetTransportInfo" to listOf(
                    "InstanceID" to "in",
                    "CurrentTransportState" to "out",
                    "CurrentTransportStatus" to "out",
                    "CurrentSpeed" to "out",
                ),
                "GetPositionInfo" to listOf(
                    "InstanceID" to "in",
                    "TrackDuration" to "out",
                    "TrackURI" to "out",
                    "RelTime" to "out",
                    "TrackMetaData" to "out",
                ),
            ),
            "RenderingControl" to mapOf(
                "GetVolume" to listOf("InstanceID" to "in", "Channel" to "in", "CurrentVolume" to "out"),
                "SetVolume" to listOf("InstanceID" to "in", "Channel" to "in", "DesiredVolume" to "in"),
                "GetMute" to listOf("InstanceID" to "in", "Channel" to "in", "CurrentMute" to "out"),
                "SetMute" to listOf("InstanceID" to "in", "Channel" to "in", "DesiredMute" to "in"),
            ),
            "ConnectionManager" to mapOf(
                "GetProtocolInfo" to listOf("Source" to "out", "Sink" to "out"),
            ),
        )
    }
}
