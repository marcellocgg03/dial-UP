// Owner: Plan 01-04 (SSDP responder and DIAL servers).
package dev.t2u.app

import dev.t2u.dial.DialConfig
import dev.t2u.dial.DialRequest
import dev.t2u.dial.DialServer
import dev.t2u.upnp.ListenSpec
import dev.t2u.upnp.SsdpDevice
import dev.t2u.upnp.SsdpEvents
import dev.t2u.upnp.SsdpMessage
import dev.t2u.upnp.SsdpResponder
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Makes the phone discoverable as a DIAL receiver and logs every step: one SsdpResponder plus DIAL
 * servers, every datagram and request written to the evidence log with its source, a self flag and
 * the USER-AGENT (RESEARCH Open Question 2).
 */
class DiscoveryFeature : SpikeFeature {
    override val name = "discovery"
    override val commands: Set<String> = emptySet()

    private val lock = Any()
    private var host: SpikeHost? = null
    private var responder: SsdpResponder? = null
    private val servers = mutableListOf<DialServer>()
    private val devices = mutableListOf<SsdpDevice>()
    private val bindResults = LinkedHashMap<String, String>()
    private var wifiIp: String? = null
    private var iface: String? = null

    private val ssdpRx = AtomicInteger()
    private val ssdpRxSelf = AtomicInteger()
    private val replies = AtomicInteger()
    private val httpReqs = AtomicInteger()

    override fun start(host: SpikeHost) {
        this.host = host
        host.scope.launch { synchronized(lock) { startAll(host) } }
    }

    /** Runs the shutdown off the calling thread: sockets must not be touched on the main thread. */
    override fun stop() {
        val t = Thread({ synchronized(lock) { stopAll() } }, "t2u-discovery-stop").apply { isDaemon = true }
        t.start()
        t.join(8000)
    }

    override fun handle(cmd: String, args: Map<String, String>) = CommandReply(false, """{"error":"unknown command"}""")

    override fun state(): JsonObject = buildJsonObject {
        put("running", responder != null)
        put("wifiIp", wifiIp)
        put("iface", iface)
        put("join", "explicit")
        put("ssdpRx", ssdpRx.get())
        put("ssdpRxSelf", ssdpRxSelf.get())
        put("replies", replies.get())
        put("httpReqs", httpReqs.get())
        put("devices", JsonArray(devices.zip(servers).map { (d, s) ->
            buildJsonObject {
                put("name", d.name)
                put("port", s.resolvedPort())
                put("uuid", d.uuid)
            }
        }))
        put("bind", JsonObject(bindResults.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
    }

    // ----- lifecycle -----------------------------------------------------------------------------

    private fun startAll(host: SpikeHost) {
        val ip = host.wifiIpv4()
        val ifaceName = host.wifiInterfaceName()
        if (ip == null || ifaceName == null) {
            host.evidence.event("net", mapOf("wifi" to false))
            return
        }
        wifiIp = ip.hostAddress
        iface = ifaceName
        val nif = NetworkInterface.getByName(ifaceName)

        val uuid = UUID.randomUUID().toString()
        val dial = DialServer(DialConfig("T2U-SPIKE A base", uuid, ip.hostAddress!!, 3100)) { onHttp("T2U-SPIKE A base", it) }
        try {
            dial.start()
        } catch (t: Throwable) {
            host.evidence.event("feature-error", mapOf("where" to "dial-start", "error" to t.javaClass.name, "message" to t.message))
            return
        }
        servers.add(dial)
        val location = "http://${ip.hostAddress}:${dial.resolvedPort()}/ytcr/ssdp/device-desc.xml"
        val device = SsdpDevice("T2U-SPIKE A base", uuid, "Linux/${System.getProperty("os.version")} UPnP/1.1 famium/0.0.1", location)
        devices.add(device)

        val r = SsdpResponder(
            listOf(ListenSpec("explicit", null, 1900, nif, false)),
            ip,
            nif,
            ssdpEvents(),
        )
        r.register(device)
        r.start()
        responder = r
    }

    private fun stopAll() {
        runCatching { responder?.stop() }
        responder = null
        for (s in servers) runCatching { s.stop() }
        servers.clear()
        devices.clear()
    }

    // ----- evidence mapping ----------------------------------------------------------------------

    private fun isSelf(addr: InetAddress): Boolean = addr.hostAddress == wifiIp

    private fun ssdpEvents() = object : SsdpEvents {
        override fun onBind(specId: String, ok: Boolean, boundPort: Int, error: String?) {
            bindResults[specId] = if (ok) "ok:$boundPort" else "error:$error"
            host?.evidence?.event("ssdp-bind", mapOf("sock" to specId, "ok" to ok, "port" to boundPort, "error" to error))
        }

        override fun onReceive(specId: String, src: InetSocketAddress, msg: SsdpMessage, raw: String) {
            ssdpRx.incrementAndGet()
            val self = isSelf(src.address)
            if (self) ssdpRxSelf.incrementAndGet()
            val h = msg.headers
            val isSearch = msg.startLine.uppercase().startsWith("M-SEARCH")
            val rawLogged = if (isSearch) {
                raw
            } else {
                // NOTIFY floods are cut to start line plus NT, NTS, USN, SERVER to keep the log small
                listOf(msg.startLine, "NT: ${h["NT"].orEmpty()}", "NTS: ${h["NTS"].orEmpty()}", "USN: ${h["USN"].orEmpty()}", "SERVER: ${h["SERVER"].orEmpty()}")
                    .joinToString("\n")
            }
            host?.evidence?.event(
                "ssdp-rx",
                mapOf(
                    "sock" to specId,
                    "src" to "${src.address.hostAddress}:${src.port}",
                    "self" to self,
                    "start" to msg.startLine,
                    "ST" to h["ST"],
                    "MAN" to h["MAN"],
                    "MX" to h["MX"],
                    "USER-AGENT" to h["USERAGENT"],
                    "HOST" to h["HOST"],
                    "X-T2U-PROBE" to h["XTUPROBE"],
                    "raw" to rawLogged,
                ),
            )
        }

        override fun onReply(dest: InetSocketAddress, st: String, usn: String, deviceName: String) {
            replies.incrementAndGet()
            host?.evidence?.event(
                "ssdp-tx",
                mapOf("dest" to "${dest.address.hostAddress}:${dest.port}", "st" to st, "usn" to usn, "device" to deviceName),
            )
        }

        override fun onNotify(nts: String, nt: String, deviceName: String) {
            host?.evidence?.event("ssdp-tx", mapOf("dest" to "239.255.255.250:1900", "nts" to nts, "nt" to nt, "device" to deviceName))
        }

        override fun onDrop(src: InetSocketAddress, reason: String) {
            host?.evidence?.event("ssdp-drop", mapOf("src" to "${src.address.hostAddress}:${src.port}", "reason" to reason))
        }

        override fun onError(where: String, t: Throwable) {
            host?.evidence?.event("feature-error", mapOf("where" to "ssdp:$where", "error" to t.javaClass.name, "message" to t.message))
        }
    }

    private fun onHttp(device: String, req: DialRequest) {
        httpReqs.incrementAndGet()
        val self = req.remoteHost == wifiIp
        host?.evidence?.event(
            "http-req",
            mapOf(
                "device" to device,
                "method" to req.method,
                "uri" to req.uri,
                "httpVersion" to req.httpVersion,
                "src" to "${req.remoteHost}:${req.remotePort}",
                "self" to self,
                "status" to req.status,
                "ua" to req.headers["User-Agent"],
                "host" to req.headers["Host"],
                "origin" to req.headers["Origin"],
                "contentType" to req.headers["Content-Type"],
                "body" to req.body,
            ),
        )
    }
}
