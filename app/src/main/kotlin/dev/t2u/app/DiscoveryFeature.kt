// Owner: Plan 01-04 (SSDP responder and DIAL servers).
// Phase 2 (plan 02-01): the access gate (SVC-09, D-01) is wired in here only so plan 02-06 can measure on the phone
// whether YouTube still lists phone-only receivers (D-03). This class is removed in plan 02-13.
package dev.t2u.app

import android.os.Build
import dev.t2u.dial.DialAccessMode
import dev.t2u.dial.DialAccessPolicy
import dev.t2u.dial.DialConfig
import dev.t2u.dial.DialRequest
import dev.t2u.dial.DialServer
import dev.t2u.dial.DialVariant
import dev.t2u.dial.LocationHost
import dev.t2u.dial.spikeVariants
import dev.t2u.upnp.ListenSpec
import dev.t2u.upnp.SsdpDevice
import dev.t2u.upnp.SsdpEvents
import dev.t2u.upnp.SsdpMessage
import dev.t2u.upnp.SsdpResponder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Makes the phone discoverable as a DIAL receiver and logs every step. Runs the four diagnostic
 * variants (A-D) at once on one [SsdpResponder]; every datagram and request is written to the
 * evidence log with its source, a self flag and the USER-AGENT (RESEARCH Open Question 2).
 *
 * Diagnostic mode (join mode, app state, skip-next-rebind) is persisted in variant-state.json so a
 * process restart keeps it, and is changed from adb with `variant`; `reannounce` re-sends NOTIFY alive.
 */
class DiscoveryFeature : SpikeFeature {
    override val name = "discovery"
    override val commands: Set<String> = setOf("variant", "reannounce")

    private class Running(
        val responder: SsdpResponder,
        val servers: List<Pair<DialVariant, DialServer>>,
        val devices: List<SsdpDevice>,
        val wifiIp: String,
        val iface: String,
    )

    private data class VariantSettings(val join: String, val appState: String, val skipNextRebind: Boolean)

    private val lock = Any()
    private var host: SpikeHost? = null
    private var monitor: NetworkMonitor? = null
    private var rebindJob: Job? = null
    private var variants: List<DialVariant> = emptyList()
    private var dialSettings: Settings? = null
    private var accessPolicy: DialAccessPolicy? = null

    @Volatile private var running: Running? = null

    @Volatile private var settings = VariantSettings(DEFAULT_JOIN, DEFAULT_APP_STATE, false)

    /** Last Wi-Fi address we were (or tried to be) bound to; used for the rebind log. */
    @Volatile private var lastIp: String? = null
    private val bindResults = ConcurrentHashMap<String, String>()

    private val ssdpRx = AtomicInteger()
    private val ssdpRxSelf = AtomicInteger()
    private val replies = AtomicInteger()
    private val httpReqs = AtomicInteger()
    private val launches = AtomicInteger()
    private val denied = AtomicInteger()
    private val ssdpDenied = AtomicInteger()

    /** Last time a denial was logged per source IP (one event per source per minute, T-02-05). */
    private val deniedLogged = ConcurrentHashMap<String, Long>()
    private val ssdpDeniedLogged = ConcurrentHashMap<String, Long>()

    // ----- SpikeFeature --------------------------------------------------------------------------

    override fun start(host: SpikeHost) {
        this.host = host
        val appSettings = AppSettings.of(host.context)
        dialSettings = appSettings
        // mode and phone addresses are read per request, so a change applies to the next request
        accessPolicy = DialAccessPolicy(
            mode = { appSettings.dialAccess.value },
            phoneAddresses = { setOfNotNull(running?.wifiIp, lastIp, "127.0.0.1", "::1") },
        )
        host.scope.launch {
            appSettings.dialAccess.drop(1).collect { mode ->
                host.evidence.event("settings", mapOf("dialAccess" to mode.wire, "source" to "change"))
                if (mode == DialAccessMode.LAN) running?.responder?.announce()
            }
        }
        settings = readSettings(host)
        // UUIDs of A, B, D are random per service start (Node parity) but stay fixed across restarts of the servers
        variants = spikeVariants(System.getProperty("os.version").orEmpty(), Build.VERSION.RELEASE) { UUID.randomUUID().toString() }
        val mon = NetworkMonitor(host.context, host.evidence) { reason -> scheduleRebind(reason) }
        monitor = mon
        mon.seed(host.wifiIpv4()?.hostAddress)
        mon.start()
        host.scope.launch {
            synchronized(lock) {
                startAll(host)
                running?.responder?.announce()
            }
        }
    }

    /** Runs the shutdown off the calling thread: sockets must not be touched on the main thread. */
    override fun stop() {
        runCatching { monitor?.stop() }
        monitor = null
        rebindJob?.cancel()
        val t = Thread({ synchronized(lock) { stopAll() } }, "t2u-discovery-stop").apply { isDaemon = true }
        t.start()
        t.join(8000)
    }

    override fun handle(cmd: String, args: Map<String, String>): CommandReply = when (cmd) {
        "reannounce" -> {
            val r = running
            r?.responder?.announce()
            host?.evidence?.event("variant", mapOf("reannounce" to true, "running" to (r != null)))
            CommandReply(true, """{"ok":true}""")
        }
        "variant" -> handleVariant(args)
        else -> CommandReply(false, """{"error":"unknown command"}""")
    }

    override fun state(): JsonObject {
        val r = running
        val s = settings
        return buildJsonObject {
            put("running", r != null)
            put("wifiIp", r?.wifiIp)
            put("iface", r?.iface)
            put("join", s.join)
            put("appState", s.appState)
            put("skipNextRebind", s.skipNextRebind)
            put("ssdpRx", ssdpRx.get())
            put("ssdpRxSelf", ssdpRxSelf.get())
            put("replies", replies.get())
            put("httpReqs", httpReqs.get())
            put("launches", launches.get())
            put("dialAccess", dialSettings?.dialAccess?.value?.wire)
            put("denied", denied.get())
            put("ssdpDenied", ssdpDenied.get())
            put(
                "variants",
                JsonArray(
                    (r?.servers.orEmpty()).zip(r?.devices.orEmpty()).map { (pair, device) ->
                        val (variant, server) = pair
                        buildJsonObject {
                            put("id", variant.id)
                            put("name", variant.name)
                            put("port", server.resolvedPort())
                            put("uuid", device.uuid)
                            put("server", device.serverHeader)
                            put("location", device.location)
                        }
                    },
                ),
            )
            put("bind", JsonObject(bindResults.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        }
    }

    // ----- commands ------------------------------------------------------------------------------

    private fun handleVariant(args: Map<String, String>): CommandReply {
        val h = host ?: return CommandReply(false, """{"error":"not started"}""")
        var next = settings
        for ((key, value) in args) {
            when (key) {
                "join" -> {
                    if (value !in JOIN_MODES) return error("join must be one of ${JOIN_MODES.joinToString("|")}")
                    next = next.copy(join = value)
                }
                "appState" -> {
                    if (value !in APP_STATES) return error("appState must be one of ${APP_STATES.joinToString("|")}")
                    next = next.copy(appState = value)
                }
                "skipNextRebind" -> {
                    if (value != "1" && value != "0") return error("skipNextRebind must be 1 or 0")
                    next = next.copy(skipNextRebind = value == "1")
                }
                else -> return error("unknown argument $key")
            }
        }
        if (args.isEmpty()) return CommandReply(true, state().toString())

        synchronized(lock) {
            val joinOrAppChanged = next.join != settings.join || next.appState != settings.appState
            settings = next
            writeSettings(h, next)
            if (joinOrAppChanged || running == null) {
                stopAll()
                startAll(h)
                running?.responder?.announce()
            }
        }
        h.evidence.event("variant", mapOf("join" to next.join, "appState" to next.appState, "skipNextRebind" to next.skipNextRebind))
        return CommandReply(true, state().toString())
    }

    private fun error(message: String) = CommandReply(false, buildJsonObject { put("error", message) }.toString())

    // ----- lifecycle -----------------------------------------------------------------------------

    private fun resolve(h: LocationHost, wifiIp: String): String = when (h) {
        LocationHost.WifiIp -> wifiIp
        is LocationHost.Fixed -> h.host
    }

    private fun startAll(host: SpikeHost) {
        val ip = host.wifiIpv4()
        val ifaceName = host.wifiInterfaceName()
        if (ip == null || ifaceName == null) {
            lastIp = null
            host.evidence.event("net", mapOf("wifi" to false))
            return
        }
        val wifiIp = ip.hostAddress!!
        lastIp = wifiIp
        val nif = NetworkInterface.getByName(ifaceName)
        val s = settings
        val policy = checkNotNull(accessPolicy) { "access policy not initialised" }

        val servers = mutableListOf<Pair<DialVariant, DialServer>>()
        val devices = mutableListOf<SsdpDevice>()
        for (v in variants) {
            val server = DialServer(
                DialConfig(v.name, v.uuid, resolve(v.bindHost, wifiIp), v.port, appState = s.appState, accessPolicy = policy::allowsHttp),
            ) { onHttp(v.name, it) }
            try {
                server.start()
            } catch (t: Throwable) {
                bindResults["dial:${v.id}"] = "error:${t.javaClass.simpleName}: ${t.message}"
                host.evidence.event(
                    "feature-error",
                    mapOf("where" to "dial-start:${v.id}", "error" to t.javaClass.name, "message" to t.message),
                )
                continue
            }
            bindResults["dial:${v.id}"] = "ok:${server.resolvedPort()}"
            servers.add(v to server)
            devices.add(
                SsdpDevice(
                    v.name,
                    v.uuid,
                    v.serverHeader,
                    "http://${resolve(v.locationHost, wifiIp)}:${server.resolvedPort()}/ytcr/ssdp/device-desc.xml",
                ),
            )
        }

        val responder = SsdpResponder(
            listenSpecs(s.join, ip, nif),
            ip,
            nif,
            ssdpEvents(wifiIp),
            replyPolicy = policy::allowsSsdp,
            announcePolicy = policy::announceAllowed,
        )
        devices.forEach { responder.register(it) }
        responder.start()
        running = Running(responder, servers, devices, wifiIp, ifaceName)
    }

    private fun listenSpecs(join: String, ip: Inet4Address, nif: NetworkInterface?): List<ListenSpec> {
        val explicit = ListenSpec("explicit", null, 1900, nif, false)
        val defaultRoute = ListenSpec("default-route", null, 1900, null, true)
        val bindIp = ListenSpec("bind-ip", ip, 1900, nif, false)
        return when (join) {
            "default-route" -> listOf(defaultRoute)
            "bind-ip" -> listOf(bindIp)
            "all" -> listOf(explicit, defaultRoute, bindIp)
            else -> listOf(explicit)
        }
    }

    private fun stopAll() {
        val r = running
        running = null
        if (r != null) {
            runCatching { r.responder.stop() }
            for ((_, server) in r.servers) runCatching { server.stop() }
        }
    }

    // ----- rebind on Wi-Fi change ----------------------------------------------------------------

    private fun scheduleRebind(reason: String) {
        val h = host ?: return
        rebindJob?.cancel()
        rebindJob = h.scope.launch {
            delay(REBIND_DEBOUNCE_MS)
            rebind(h, reason)
        }
    }

    private fun rebind(h: SpikeHost, reason: String) {
        synchronized(lock) {
            if (settings.skipNextRebind) {
                settings = settings.copy(skipNextRebind = false)
                writeSettings(h, settings)
                h.evidence.event("net", mapOf("rebind" to false, "skipped" to true, "reason" to reason))
                return
            }
            val oldIp = lastIp
            stopAll()
            startAll(h)
            running?.responder?.announce()
            h.evidence.event("net", mapOf("rebind" to true, "reason" to reason, "oldIp" to oldIp, "newIp" to lastIp))
        }
    }

    // ----- persistence ---------------------------------------------------------------------------

    private fun settingsFile(h: SpikeHost) = File(h.spikeDir, "variant-state.json")

    private fun readSettings(h: SpikeHost): VariantSettings {
        val defaults = VariantSettings(DEFAULT_JOIN, DEFAULT_APP_STATE, false)
        return try {
            val f = settingsFile(h)
            if (!f.exists()) return defaults
            val o = Json.parseToJsonElement(f.readText()).jsonObject
            VariantSettings(
                join = o["join"]?.jsonPrimitive?.contentOrNull?.takeIf { it in JOIN_MODES } ?: DEFAULT_JOIN,
                appState = o["appState"]?.jsonPrimitive?.contentOrNull?.takeIf { it in APP_STATES } ?: DEFAULT_APP_STATE,
                skipNextRebind = o["skipNextRebind"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        } catch (t: Throwable) {
            h.evidence.event("feature-error", mapOf("where" to "variant-state:read", "error" to t.javaClass.name, "message" to t.message))
            defaults
        }
    }

    private fun writeSettings(h: SpikeHost, s: VariantSettings) {
        try {
            val json = buildJsonObject {
                put("join", s.join)
                put("appState", s.appState)
                put("skipNextRebind", s.skipNextRebind)
            }.toString()
            val f = settingsFile(h)
            val tmp = File(f.parentFile, "variant-state.json.tmp")
            f.parentFile?.mkdirs()
            tmp.writeText(json)
            if (!tmp.renameTo(f)) f.writeText(json)
        } catch (t: Throwable) {
            h.evidence.event("feature-error", mapOf("where" to "variant-state:write", "error" to t.javaClass.name, "message" to t.message))
        }
    }

    // ----- evidence mapping ----------------------------------------------------------------------

    private fun ssdpEvents(wifiIp: String) = object : SsdpEvents {
        override fun onBind(specId: String, ok: Boolean, boundPort: Int, error: String?) {
            bindResults[specId] = if (ok) "ok:$boundPort" else "error:$error"
            host?.evidence?.event("ssdp-bind", mapOf("sock" to specId, "ok" to ok, "port" to boundPort, "error" to error))
        }

        override fun onReceive(specId: String, src: InetSocketAddress, msg: SsdpMessage, raw: String) {
            ssdpRx.incrementAndGet()
            val self = isSelf(src.address, wifiIp)
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
            if (reason == "access") {
                ssdpDenied.incrementAndGet()
                if (!firstInWindow(ssdpDeniedLogged, src.address.hostAddress.orEmpty())) return
            }
            host?.evidence?.event("ssdp-drop", mapOf("src" to "${src.address.hostAddress}:${src.port}", "reason" to reason))
        }

        override fun onError(where: String, t: Throwable) {
            host?.evidence?.event("feature-error", mapOf("where" to "ssdp:$where", "error" to t.javaClass.name, "message" to t.message))
        }
    }

    private fun isSelf(addr: InetAddress, wifiIp: String): Boolean = addr.hostAddress == wifiIp

    /** True when [key] has not been logged in the last [DENIED_LOG_WINDOW_NS]; marks it logged. */
    private fun firstInWindow(logged: ConcurrentHashMap<String, Long>, key: String): Boolean {
        val now = System.nanoTime()
        if (logged.size > 1024) logged.clear()
        var first = false
        logged.compute(key) { _, last ->
            if (last == null || now - last >= DENIED_LOG_WINDOW_NS) {
                first = true
                now
            } else {
                last
            }
        }
        return first
    }

    private fun onHttp(device: String, req: DialRequest) {
        httpReqs.incrementAndGet()
        if (req.status == 403) {
            denied.incrementAndGet()
            if (firstInWindow(deniedLogged, req.remoteHost)) {
                host?.evidence?.event(
                    "dial-denied",
                    mapOf(
                        "device" to device,
                        "src" to "${req.remoteHost}:${req.remotePort}",
                        "method" to req.method,
                        "uri" to req.uri,
                        "ua" to req.headers["User-Agent"],
                    ),
                )
            }
            return
        }
        if (req.method == "POST" && req.uri.substringBefore('?').endsWith("/apps/YouTube")) launches.incrementAndGet()
        // the phone's own apps reach a receiver either through its Wi-Fi address or through loopback (variant D)
        val self = req.remoteHost == running?.wifiIp || req.remoteHost == lastIp ||
            req.remoteHost.startsWith("127.") || req.remoteHost == "::1"
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

    private companion object {
        const val DEFAULT_JOIN = "explicit"
        const val DEFAULT_APP_STATE = "running"
        const val REBIND_DEBOUNCE_MS = 2000L
        const val DENIED_LOG_WINDOW_NS = 60_000_000_000L
        val JOIN_MODES = listOf("explicit", "default-route", "bind-ip", "all")
        val APP_STATES = listOf("running", "stopped")
    }
}
