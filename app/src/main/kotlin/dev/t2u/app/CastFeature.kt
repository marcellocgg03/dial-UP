package dev.t2u.app

// Plan 02-13: the product feature. Opening the app starts the Bridge (the index.js port) inside the connectedDevice
// foreground service: every speaker found on the Wi-Fi becomes a cast target in YouTube and YouTube Music.
// GPL-3.0-only.

import dev.t2u.dial.DialRequest
import dev.t2u.dial.redactLaunchBody
import dev.t2u.media.CachingResolver
import dev.t2u.media.MediaRequest
import dev.t2u.renderer.Bridge
import dev.t2u.renderer.BridgeDeps
import dev.t2u.renderer.BridgeNetwork
import dev.t2u.renderer.Config
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.ListenSpec
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the [Bridge] on the Wi-Fi address and keeps it there. Android-only glue: the Wi-Fi address and interface, the
 * native payloads, yt-dlp and ffmpeg, the settings file, evidence logging and the notification count. Everything else
 * is in :renderer and below, tested on the JVM.
 *
 * Every OkHttp client in the app derives from `lanHttpClient()` (SVC-05); nothing here builds one directly.
 */
class CastFeature : SpikeFeature, CastCountSource {
    override val name = "cast"
    override val commands: Set<String> = setOf("reannounce", "cast-state")

    private val lifecycle = Mutex()
    private var host: SpikeHost? = null
    private var monitor: NetworkMonitor? = null
    private var rebindJob: Job? = null
    private var countJob: Job? = null
    private var settings: Settings? = null

    @Volatile private var bridge: Bridge? = null

    /** Last Wi-Fi address the bridge was (or tried to be) bound to; used for the rebind log. */
    @Volatile private var lastIp: String? = null

    private val warmed = AtomicBoolean(false)
    private val denied = AtomicInteger()

    /** Last time a DIAL denial was logged per source IP (one event per source per minute). */
    private val deniedLogged = ConcurrentHashMap<String, Long>()

    private val count = MutableStateFlow(CastCount(0, 0))

    /** Derived from the bridge's player states, so CastCount(0, 0) until the bridge exists (D-06). */
    override val castCount: StateFlow<CastCount> = count.asStateFlow()

    // ----- SpikeFeature --------------------------------------------------------------------------

    override fun start(host: SpikeHost) {
        this.host = host
        val appSettings = AppSettings.of(host.context)
        settings = appSettings
        host.scope.launch {
            appSettings.dialAccess.drop(1).collect { mode ->
                host.evidence.event("settings", mapOf("dialAccess" to mode.wire, "source" to "change"))
                bridge?.onAccessModeChanged(mode)
            }
        }
        val mon = NetworkMonitor(host.context, host.evidence) { reason -> scheduleRebind(reason) }
        monitor = mon
        mon.seed(host.wifiIpv4()?.hostAddress)
        mon.start()
        host.scope.launch { lifecycle.withLock { startBridge(host) } }
    }

    /** Stop in the notification ends here: every receiver says byebye and goodbye before the service goes. */
    override fun stop() {
        runCatching { monitor?.stop() }
        monitor = null
        rebindJob?.cancel()
        // sockets are never touched on the main thread
        val t = Thread({ runBlocking { lifecycle.withLock { stopBridge() } } }, "t2u-cast-stop").apply { isDaemon = true }
        t.start()
        t.join(10_000)
    }

    override fun handle(cmd: String, args: Map<String, String>): CommandReply = when (cmd) {
        "reannounce" -> {
            val b = bridge
            b?.reannounce()
            host?.evidence?.event("reannounce", mapOf("running" to (b != null)))
            CommandReply(true, """{"ok":true}""")
        }
        "cast-state" -> CommandReply(true, state().toString())
        else -> CommandReply(false, """{"error":"unknown command"}""")
    }

    override fun state(): JsonObject {
        val base = bridge?.state() ?: JsonObject(mapOf("running" to JsonPrimitive(false)))
        return JsonObject(
            base + mapOf(
                "dialAccess" to JsonPrimitive(settings?.dialAccess?.value?.wire),
                "denied" to JsonPrimitive(denied.get()),
            ),
        )
    }

    // ----- lifecycle -----------------------------------------------------------------------------

    /** Call with [lifecycle] held, off the main thread. */
    private suspend fun startBridge(h: SpikeHost) {
        val ip = h.wifiIpv4()
        val ifaceName = h.wifiInterfaceName()
        if (ip == null || ifaceName == null) {
            lastIp = null
            h.evidence.event("net", mapOf("wifi" to false))
            return
        }
        lastIp = ip.hostAddress
        val appSettings = checkNotNull(settings) { "settings not initialised" }
        val payloads = h.payloads()
        val cacheDir = File(h.context.cacheDir, "yt-dlp").also { it.mkdirs() }
        val resolver = CachingResolver(
            AndroidYtDlpGateway(h.context, h.evidence),
            cacheDir = cacheDir.absolutePath,
            onEvent = { h.evidence.event("resolve", it) },
        )
        val nif = NetworkInterface.getByName(ifaceName)
        val b = Bridge(
            BridgeNetwork(ip, nif, listOf(ListenSpec("explicit", null, 1900, nif, false))),
            BridgeDeps(
                config = Config(dataDir = File(h.context.filesDir, "cast")),
                resolver = resolver,
                launcher = FfmpegRunner(payloads, h.context.cacheDir, h.evidence),
                http = lanHttpClient(),
                accessMode = { appSettings.dialAccess.value },
                serverHeader = "Linux/${System.getProperty("os.version").orEmpty()} UPnP/1.1 famium/0.0.1",
                events = EventSink { kind, fields -> h.evidence.event(kind, fields) },
                scope = h.scope,
                bufferDir = File(h.context.cacheDir, "media-buffer").also { it.mkdirs() },
                onMediaRequest = { onMediaRequest(h, it) },
                onDialRequest = { onDialRequest(h, it) },
            ),
        )
        b.start()
        bridge = b
        countJob = h.scope.launch { b.castCount.collect { count.value = CastCount(it.casting, it.total) } }
        scheduleWarmUp(h, b)
    }

    /** Call with [lifecycle] held. */
    private suspend fun stopBridge() {
        val b = bridge
        bridge = null
        countJob?.cancel()
        countJob = null
        try {
            b?.stop()
        } finally {
            count.value = CastCount(0, 0)
        }
    }

    /**
     * RESEARCH Pitfall 4: the first yt-dlp run pays Python and QuickJS start-up, so one resolve runs once the receivers
     * are advertised (first speaker up, or 15 s) and its duration is logged as `warmup`.
     */
    private fun scheduleWarmUp(h: SpikeHost, b: Bridge) {
        if (!warmed.compareAndSet(false, true)) return
        h.scope.launch {
            withTimeoutOrNull(WARMUP_WAIT_MS) { b.castCount.first { it.total > 0 } }
            val id = try {
                h.context.assets.open("spike/video-set.txt").bufferedReader().useLines { lines ->
                    lines.map { it.trim() }.first { it.isNotEmpty() && !it.startsWith("#") }
                }
            } catch (e: Exception) {
                h.evidence.event("warmup", mapOf("ok" to false, "error" to e.javaClass.simpleName))
                return@launch
            }
            b.warmUp(id)
        }
    }

    // ----- rebind on Wi-Fi change ----------------------------------------------------------------

    private fun scheduleRebind(reason: String) {
        val h = host ?: return
        rebindJob?.cancel()
        rebindJob = h.scope.launch {
            delay(REBIND_DEBOUNCE_MS)
            lifecycle.withLock {
                val oldIp = lastIp
                stopBridge()
                startBridge(h)
                h.evidence.event("net", mapOf("rebind" to true, "reason" to reason, "oldIp" to oldIp, "newIp" to lastIp))
            }
        }
    }

    // ----- evidence mapping ----------------------------------------------------------------------

    private fun onMediaRequest(h: SpikeHost, req: MediaRequest) {
        val self = req.remoteHost == lastIp || req.remoteHost.startsWith("127.") || req.remoteHost == "::1"
        h.evidence.event(
            "http-req",
            mapOf(
                "server" to "media",
                "method" to req.method,
                "uri" to req.uri,
                "httpVersion" to req.httpVersion,
                "src" to "${req.remoteHost}:${req.remotePort}",
                "self" to self,
                "range" to req.range,
                "ua" to req.userAgent,
                "status" to req.status,
                "bytes" to req.bytes,
                "pipeMode" to req.pipeMode,
                "label" to req.label,
                "note" to req.note,
            ),
        )
    }

    private fun onDialRequest(h: SpikeHost, req: DialRequest) {
        if (req.status == 403) {
            denied.incrementAndGet()
            if (firstInWindow(req.remoteHost)) {
                h.evidence.event(
                    "dial-denied",
                    mapOf(
                        "src" to "${req.remoteHost}:${req.remotePort}",
                        "method" to req.method,
                        "uri" to req.uri,
                        "ua" to req.headers["User-Agent"],
                    ),
                )
            }
            return
        }
        h.evidence.event(
            "http-req",
            mapOf(
                "server" to "dial",
                "method" to req.method,
                "uri" to req.uri,
                "httpVersion" to req.httpVersion,
                "src" to "${req.remoteHost}:${req.remotePort}",
                "status" to req.status,
                "ua" to req.headers["User-Agent"],
                "host" to req.headers["Host"],
                "origin" to req.headers["Origin"],
                "contentType" to req.headers["Content-Type"],
                // the pairing code is a secret for the lifetime of the pairing
                "body" to redactLaunchBody(req.body),
            ),
        )
    }

    /** True when [key] has not been logged in the last minute; marks it logged. */
    private fun firstInWindow(key: String): Boolean {
        val now = System.nanoTime()
        if (deniedLogged.size > 1024) deniedLogged.clear()
        var first = false
        deniedLogged.compute(key) { _, last ->
            if (last == null || now - last >= DENIED_LOG_WINDOW_NS) {
                first = true
                now
            } else {
                last
            }
        }
        return first
    }

    private companion object {
        const val REBIND_DEBOUNCE_MS = 2000L
        const val WARMUP_WAIT_MS = 15_000L
        const val DENIED_LOG_WINDOW_NS = 60_000_000_000L
    }
}
