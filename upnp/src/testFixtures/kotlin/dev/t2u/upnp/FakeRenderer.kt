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
//
// Plan 03-26 makes the fake usable as a standalone speaker on the LAN (FakeRendererMain.kt): it can bind a
// private address and a fixed port, carry its own friendly name and UDN, decide per stream mime whether Play
// ever reaches PLAYING (playMimes, startDelayMs) and report what it is asked to do without printing a URL (log).
// Every one of those parameters defaults to the behaviour the JVM tests have always had.

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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    bareFaultOn: Set<String> = emptySet(),
    private val noGapless: Boolean = false,
    // Plan 03-26 (LAN runner): every parameter below defaults to the behaviour the JVM tests have always had.
    private val bindHost: String = "127.0.0.1",
    private val bindPort: Int = 0,
    private val friendlyName: String? = null,
    private val udn: String? = null,
    /** Null: every stream plays (the old behaviour). Otherwise Play reaches PLAYING only for a mime listed here (aliases count). */
    private val playMimes: Set<String>? = null,
    /** How long Play takes to reach PLAYING; 0 means at once, as before. */
    private val startDelayMs: Long = 0,
    /** One short line per control call that matters to a start-failure test; never a URL or a DIDL body. Null: silent. */
    private val log: ((String) -> Unit)? = null,
) : AutoCloseable {
    val calls: MutableList<FakeCall> = CopyOnWriteArrayList()

    /** Actions answered with a bare 500; a test may change it mid-run (a speaker that rejects Seek, then accepts it). */
    @Volatile var bareFaultOn: Set<String> = bareFaultOn
    val state = FakeState()

    /**
     * Runs for every SOAP action after the fake updated [state] and before it answers, so a test speaker built on
     * this fake (the G4 Sonos mimic) can override the state it reports or react to Play and Pause. Null by default.
     */
    @Volatile var onAction: ((action: String) -> Unit)? = null

    /**
     * Runs for every SOAP action as soon as the fake has recorded the call in [calls] and before it changes [state] or answers,
     * so a test can hold one request in flight without blocking a server thread (plan 03-21: a clear that lands while a
     * SetAVTransportURI or a Play is on its way). Null by default.
     */
    @Volatile var beforeAction: (suspend (action: String) -> Unit)? = null

    private var server: EmbeddedServer<*, *>? = null

    @Volatile private var port: Int = 0

    /** Bumped by every Play, Stop, Pause and SetAVTransportURI so a delayed PLAYING of an older Play is dropped. */
    private val playGeneration = AtomicInteger()
    private val transportLock = Any()
    private var timer: ScheduledExecutorService? = null

    /** http://<bindHost>:<port>/desc.xml, valid after [start]. */
    val location: String get() = "http://$bindHost:$port/desc.xml"

    fun start() {
        check(server == null) { "already started" }
        val s = embeddedServer(CIO, serverConfig { module { routes() } }) {
            reuseAddress = true
            connectors.add(
                EngineConnectorBuilder().apply {
                    host = bindHost
                    this.port = bindPort
                },
            )
        }
        s.start(wait = false)
        timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fake-renderer-timer").apply { isDaemon = true } }
        port = runBlocking { s.engine.resolvedConnectors().first().port }
        server = s
    }

    override fun close() {
        timer?.shutdownNow()
        timer = null
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
        beforeAction?.invoke(action)

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
                playGeneration.incrementAndGet()
                log?.invoke("SetAVTransportURI mime=${mimeOfDidl(state.currentMetaData) ?: "unknown"}")
            }
            "SetNextAVTransportURI" -> state.nextUri = unescape(tag(body, "NextURI"))
            "Play" -> play()
            "Stop" -> synchronized(transportLock) {
                playGeneration.incrementAndGet()
                state.transport = "STOPPED"
                log?.invoke("Stop")
            }
            // A real speaker that takes a REL_TIME Seek reports a RelTime at the target afterwards (plan 03-16).
            "Seek" -> {
                val target = Regex("^(\\d+):(\\d+):(\\d+)").find(tag(body, "Target"))
                if (target != null) {
                    val (h, min, sec) = target.destructured
                    state.relTime = "%d:%02d:%02d".format(h.toInt(), min.toInt(), sec.toInt())
                }
                log?.invoke(if (target != null) "Seek to ${state.relTime}" else "Seek")
            }
            "Pause" -> synchronized(transportLock) {
                playGeneration.incrementAndGet()
                state.transport = "PAUSED_PLAYBACK"
            }
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
        onAction?.let { hook -> runCatching { hook(action) } }
        call.respondText(
            """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
                """<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$svc:1">$out</u:${action}Response></s:Body></s:Envelope>""",
            ContentType.Text.Xml,
        )
    }

    /**
     * Play: neverPlay wins (STOPPED, as before); a stream whose mime is not listed in [playMimes] (or whose mime
     * cannot be read) is accepted and then sits in TRANSITIONING for ever, like a speaker that cannot decode it;
     * anything else reaches PLAYING at once, or after [startDelayMs].
     */
    private fun play() {
        val mime = mimeOfDidl(state.currentMetaData)
        val listed = playMimes
        val playable = listed == null || (mime != null && mimeIsListed(mime, listed))
        synchronized(transportLock) {
            val generation = playGeneration.incrementAndGet()
            val startedAt = System.nanoTime()
            fun reached() {
                state.transport = "PLAYING"
                log?.invoke("PLAYING reached ${(System.nanoTime() - startedAt) / 1_000_000} ms after Play")
            }
            when {
                neverPlay -> {
                    state.transport = "STOPPED"
                    log?.invoke("Play mime=${mime ?: "unknown"}")
                }
                !playable -> {
                    state.transport = "TRANSITIONING"
                    log?.invoke("Play mime=${mime ?: "unknown"} (not in play-mimes, stays TRANSITIONING)")
                }
                startDelayMs <= 0 -> {
                    log?.invoke("Play mime=${mime ?: "unknown"}")
                    reached()
                }
                else -> {
                    state.transport = "TRANSITIONING"
                    log?.invoke("Play mime=${mime ?: "unknown"}")
                    timer?.schedule(
                        {
                            synchronized(transportLock) {
                                if (playGeneration.get() == generation && state.transport == "TRANSITIONING") reached()
                            }
                        },
                        startDelayMs,
                        TimeUnit.MILLISECONDS,
                    )
                }
            }
        }
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
        val rootName = friendlyName?.let(::xmlText) ?: "Living Room"
        val rendererName = friendlyName?.let(::xmlText) ?: "Living Room Speaker"
        val rootUdn = udn?.let { "uuid:$it-root" } ?: "uuid:root"
        val rendererUdn = udn?.let { "uuid:$it" } ?: "uuid:fake-renderer"
        return """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>""" +
            "<deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType><friendlyName>$rootName</friendlyName>" +
            "<manufacturer>Acme</manufacturer><modelName>Box</modelName><UDN>$rootUdn</UDN>" +
            "<deviceList><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>" +
            "<friendlyName>$rendererName</friendlyName><manufacturer>Acme</manufacturer><modelName>Speaker 1</modelName>" +
            "<UDN>$rendererUdn</UDN><serviceList>${svc("AVTransport")}${svc("RenderingControl")}${svc("ConnectionManager")}</serviceList>" +
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

private val PROTOCOL_INFO = Regex("""protocolInfo\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
private val MIME_SHAPE = Regex("^[a-z0-9][a-z0-9.+_-]*/[a-z0-9][a-z0-9.+_-]*$")

private fun unescapeXml(s: String): String =
    s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

private fun firstHttpGetMime(text: String): String? {
    for (m in PROTOCOL_INFO.findAll(text)) {
        val parts = m.groupValues[1].split(':')
        if (parts.size < 3 || !parts[0].trim().equals("http-get", ignoreCase = true)) continue
        val mime = parts[2].trim().lowercase()
        if (MIME_SHAPE.matches(mime)) return mime
    }
    return null
}

/**
 * The mime of the stream a DIDL-Lite describes: the content format of the first `res protocolInfo` whose protocol is
 * http-get, lower case, or null when there is none, it is `*`, or it is not shaped like a mime type. A SetAVTransportURI
 * body carries the DIDL escaped (once, or twice by a careless sender), so the text is unescaped up to twice more if
 * nothing is found. The result is safe to print: it matches `type/subtype` over a small alphabet.
 */
fun mimeOfDidl(didl: String): String? {
    var text = didl
    repeat(3) {
        firstHttpGetMime(text)?.let { return it }
        val next = unescapeXml(text)
        if (next == text) return null
        text = next
    }
    return firstHttpGetMime(text)
}

/** Does [listed] name [mime] or one of its spellings (audio/flac and audio/x-flac, audio/mpeg and audio/mp3, ...)? */
fun mimeIsListed(mime: String, listed: Set<String>): Boolean {
    val m = mime.trim().lowercase()
    val spellings = MIME_ALIASES.entries
        .filter { (canonical, aliases) -> m == canonical || m in aliases }
        .flatMap { (canonical, aliases) -> listOf(canonical) + aliases }
        .toSet() + m
    return listed.any { it.trim().lowercase() in spellings }
}
