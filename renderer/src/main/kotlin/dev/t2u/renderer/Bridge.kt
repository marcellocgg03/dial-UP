package dev.t2u.renderer

// Port of tube2upnp src/index.js (composition root), GPL-3.0-only.
//
// Wires the pieces into one running bridge: the media server, the SSDP responder (the DIAL side: answers the phone's
// searches) and the SSDP client (the speaker side: finds MediaRenderers), the renderer registry, and one Renderer per
// speaker whose cast receiver is the YouTubeCastReceiver. Pure JVM: the Android glue (Wi-Fi address, payloads, settings,
// notification) lives in :app and hands everything in through [BridgeNetwork] and [BridgeDeps].
//
// One DialAccessPolicy (D-01, SVC-09) feeds both the DIAL servers (through the receiver) and the SSDP responder, so the
// access mode is read on every request by both. Everything that listens does so on the Wi-Fi IPv4 address only (PLAY-03).

import dev.t2u.dial.DialAccessMode
import dev.t2u.dial.DialAccessPolicy
import dev.t2u.dial.DialRequest
import dev.t2u.lounge.DialAdvertiser
import dev.t2u.lounge.LoungeEndpoints
import dev.t2u.lounge.Player
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.ReceiverDeps
import dev.t2u.lounge.ReceiverFactory
import dev.t2u.lounge.YouTubeCastReceiver
import dev.t2u.media.MediaRequest
import dev.t2u.media.MediaServer
import dev.t2u.media.PipeLauncher
import dev.t2u.media.VideoResolver
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.ListenSpec
import dev.t2u.upnp.RENDERER_ST
import dev.t2u.upnp.Ssdp
import dev.t2u.upnp.SsdpClientEvents
import dev.t2u.upnp.SsdpConstants
import dev.t2u.upnp.SsdpDevice
import dev.t2u.upnp.SsdpEvents
import dev.t2u.upnp.SsdpMessage
import dev.t2u.upnp.SsdpResponder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the bridge listens and searches. [wifiIp] is the only address anything binds to (PLAY-03); [iface] is its
 * interface (null in JVM tests); [ssdpListen] are the responder's multicast listen sockets (port 1900 on the phone).
 * [ssdpSearchTarget] and [notifyTarget] default to the SSDP group; tests point them at unicast sockets.
 */
data class BridgeNetwork(
    val wifiIp: InetAddress,
    val iface: NetworkInterface?,
    val ssdpListen: List<ListenSpec>,
    val ssdpSearchTarget: InetSocketAddress = InetSocketAddress(SsdpConstants.GROUP, SsdpConstants.PORT),
    val notifyTarget: InetSocketAddress? = null,
)

/** D-06: how many speakers have a player that is loading, playing or paused, out of all speakers with a receiver. */
data class BridgeCount(val casting: Int, val total: Int)

/**
 * Everything the bridge needs from the app around it. [accessMode] is read per request (D-01). [http] must come from
 * `lanHttpClient()` (SVC-05); every client the bridge hands on is derived from it. [scope] is the service scope.
 * [launcher] runs ffmpeg for the media server (null: streams answer 501, as in JVM tests).
 */
data class BridgeDeps(
    val config: Config,
    val resolver: VideoResolver,
    val launcher: PipeLauncher?,
    val http: OkHttpClient,
    val accessMode: () -> DialAccessMode,
    val serverHeader: String,
    val events: EventSink,
    val scope: CoroutineScope,
    val bufferDir: File?,
    val loungeEndpoints: LoungeEndpoints = LoungeEndpoints(),
    val onMediaRequest: (MediaRequest) -> Unit = {},
    val onDialRequest: (DialRequest) -> Unit = {},
)

/** One denial per source per minute in the log, so a scanner on the LAN cannot flood it. */
private const val DROP_LOG_WINDOW_MS = 60_000L

class Bridge(private val net: BridgeNetwork, private val deps: BridgeDeps) {
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    // Children of the service scope that are cancelled (and awaited) first on stop, so a speaker that is mid-setup
    // when Stop arrives cannot come up after the receivers were removed.
    private val handlerJob = SupervisorJob(deps.scope.coroutineContext[Job])
    private val handlerScope = CoroutineScope(deps.scope.coroutineContext + handlerJob)

    @Volatile private var mediaServer: MediaServer? = null

    @Volatile private var responder: SsdpResponder? = null

    @Volatile private var ssdp: Ssdp? = null

    @Volatile private var registry: RendererRegistry? = null

    @Volatile private var effective: Config? = null

    private val dropLogged = ConcurrentHashMap<String, Long>()

    private val _castCount = MutableStateFlow(BridgeCount(0, 0))

    /** Derived from the renderers' player status on every change, never counted by events (RESEARCH Pitfall 11). */
    val castCount: StateFlow<BridgeCount> = _castCount.asStateFlow()

    private fun emit(kind: String, vararg fields: Pair<String, Any?>) {
        try {
            deps.events.event(kind, mapOf(*fields))
        } catch (_: Exception) {
            // an event sink must not break the bridge
        }
    }

    private fun failure(where: String, t: Throwable) {
        emit("bridge-error", "where" to where, "error" to t.javaClass.name, "message" to t.message)
    }

    /** Starts the media server, the SSDP responder and client and the discovery schedules. Throws after undoing a failed start. */
    fun start() {
        check(started.compareAndSet(false, true)) { "already started" }
        val base = deps.config
        // mediaPort 0 means "any free port" (tests); the Node range check applies to the real port below
        base.copy(mediaPort = base.mediaPort.coerceAtLeast(1)).validate()
        try {
            val wifi = net.wifiIp.hostAddress.orEmpty()
            val media = MediaServer(wifi, base.mediaPort, deps.launcher, deps.onMediaRequest, deps.bufferDir, deps.resolver)
            media.start()
            mediaServer = media
            val config = base.copy(mediaPort = media.resolvedPort())
            effective = config

            val policy = DialAccessPolicy(deps.accessMode, phoneAddresses = { setOf(wifi, "127.0.0.1", "::1") })

            val resp = SsdpResponder(
                net.ssdpListen,
                net.wifiIp,
                net.iface,
                responderEvents(),
                replyPolicy = policy::allowsSsdp,
                announcePolicy = policy::announceAllowed,
                notifyTarget = net.notifyTarget,
            )
            resp.start()
            responder = resp

            val advertiser = object : DialAdvertiser {
                override fun advertise(device: SsdpDevice) {
                    resp.register(device)
                    resp.announce()
                }

                override fun withdraw(uuid: String) {
                    resp.withdraw(uuid)
                }
            }

            val ports = PortRegistry(config.dataDir, config.receiverBasePort) { line -> emit("ports", "warn" to line) }.load()
            val receivers = ReceiverFactory { player, options ->
                YouTubeCastReceiver(
                    player,
                    options,
                    ReceiverDeps(
                        bindHost = wifi,
                        accessPolicy = policy::allowsHttp,
                        advertiser = advertiser,
                        serverHeader = deps.serverHeader,
                        http = deps.http.newBuilder().build(),
                        endpoints = deps.loungeEndpoints,
                        sink = deps.events,
                        scope = deps.scope,
                        onDialRequest = deps.onDialRequest,
                    ),
                )
            }
            val factory = RendererFactory { udn, location, address, maxAge, port ->
                Renderer(
                    udn,
                    location,
                    address,
                    maxAge,
                    port,
                    RendererDeps(config, deps.resolver, media, deps.http, receivers, deps.scope, deps.events),
                )
            }
            val reg = RendererRegistry(config, ports, factory, deps.events)
            registry = reg

            val client = Ssdp(listOf(net.wifiIp), clientEvents(reg), net.ssdpSearchTarget)
            client.start()
            ssdp = client

            handlerScope.launch { countFlow(reg).collect { _castCount.value = it } }
            reg.launchSchedules(handlerScope) { client.search(RENDERER_ST, 3) }
            emit("bridge", "event" to "started", "wifi" to wifi, "mediaPort" to config.mediaPort)
        } catch (t: Throwable) {
            failure("start", t)
            runBlocking { stop() }
            throw t
        }
    }

    // ------------------------------------------------------------------------------------------ SSDP wiring

    /** The SSDP client's alive and byebye go to the registry on the bridge's own scope (index.js ssdp.on). */
    private fun clientEvents(reg: RendererRegistry) = object : SsdpClientEvents {
        override fun onAlive(headers: Map<String, String>, src: InetSocketAddress) {
            if (stopped.get()) return
            handlerScope.launch {
                try {
                    reg.onAlive(headers, src)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure("alive handler", e)
                }
            }
        }

        override fun onByebye(headers: Map<String, String>, src: InetSocketAddress) {
            if (stopped.get()) return
            handlerScope.launch {
                try {
                    reg.onByebye(headers)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure("byebye handler", e)
                }
            }
        }

        override fun onError(where: String, t: Throwable) = failure("ssdp-client:$where", t)
    }

    /**
     * The responder's port-1900 listener already sees every NOTIFY, so those go to the SSDP client's onNotify instead of
     * a second socket (RESEARCH Pattern 7). Datagrams are not logged one by one: a NOTIFY flood would drown the log.
     */
    private fun responderEvents() = object : SsdpEvents {
        override fun onBind(specId: String, ok: Boolean, boundPort: Int, error: String?) =
            emit("ssdp-bind", "sock" to specId, "ok" to ok, "port" to boundPort, "error" to error)

        override fun onReceive(specId: String, src: InetSocketAddress, msg: SsdpMessage, raw: String) {
            if (!msg.startLine.uppercase().startsWith("NOTIFY")) return
            try {
                ssdp?.onNotify(src, msg)
            } catch (t: Throwable) {
                failure("notify", t)
            }
        }

        override fun onReply(dest: InetSocketAddress, st: String, usn: String, deviceName: String) =
            emit("ssdp-tx", "dest" to "${dest.address.hostAddress}:${dest.port}", "st" to st, "usn" to usn, "device" to deviceName)

        override fun onNotify(nts: String, nt: String, deviceName: String) =
            emit("ssdp-tx", "dest" to "notify", "nts" to nts, "nt" to nt, "device" to deviceName)

        override fun onDrop(src: InetSocketAddress, reason: String) {
            if (reason == "access" && !firstInWindow(src.address.hostAddress.orEmpty())) return
            emit("ssdp-drop", "src" to "${src.address.hostAddress}:${src.port}", "reason" to reason)
        }

        override fun onError(where: String, t: Throwable) = failure("ssdp:$where", t)
    }

    private fun firstInWindow(key: String): Boolean {
        val now = System.currentTimeMillis()
        if (dropLogged.size > 1024) dropLogged.clear()
        var first = false
        dropLogged.compute(key) { _, last ->
            if (last == null || now - last >= DROP_LOG_WINDOW_MS) {
                first = true
                now
            } else {
                last
            }
        }
        return first
    }

    // ------------------------------------------------------------------------------------------ cast count

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun countFlow(reg: RendererRegistry): Flow<BridgeCount> = reg.renderersFlow.flatMapLatest { list ->
        val players = list.filterIsInstance<Player>()
        when {
            list.isEmpty() -> flowOf(BridgeCount(0, 0))
            players.isEmpty() -> flowOf(BridgeCount(0, list.size))
            else -> combine(players.map { it.statusFlow }) { statuses ->
                BridgeCount(
                    statuses.count { it == PlayerStatus.LOADING || it == PlayerStatus.PLAYING || it == PlayerStatus.PAUSED },
                    list.size,
                )
            }
        }
    }.distinctUntilChanged()

    // ------------------------------------------------------------------------------------------ commands

    /** D-01: lan makes the receivers visible to the whole network right away; phone-only needs no action (the policy is read per request). */
    fun onAccessModeChanged(mode: DialAccessMode) {
        if (mode == DialAccessMode.LAN) responder?.announce()
    }

    /** Sends ssdp:alive for every receiver again (a no-op in phone mode, where announcing is not allowed). */
    fun reannounce() {
        responder?.announce()
    }

    /** The port each responder listen socket bound, by spec id. */
    fun ssdpPorts(): Map<String, Int> = responder?.boundPorts().orEmpty()

    /**
     * One resolve so the first real cast does not pay the cold start of yt-dlp on top of the handshake (RESEARCH Pitfall 4).
     * Returns the duration in ms, or -1 when the resolve failed (also logged). Never throws except on cancellation.
     */
    suspend fun warmUp(videoId: String): Long {
        val t0 = System.nanoTime()
        return try {
            deps.resolver.resolve(videoId)
            val ms = (System.nanoTime() - t0) / 1_000_000
            emit("warmup", "ok" to true, "ms" to ms)
            ms
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            emit("warmup", "ok" to false, "ms" to ms, "error" to e.javaClass.simpleName, "message" to e.message?.take(200))
            -1
        }
    }

    /** What the device session reads: media base, access mode and one entry per running speaker. */
    fun state(): JsonObject {
        val config = effective
        return buildJsonObject {
            put("running", started.get() && !stopped.get())
            put("wifiIp", net.wifiIp.hostAddress)
            put("mediaBase", config?.let { "http://${net.wifiIp.hostAddress}:${it.mediaPort}" })
            put("accessMode", deps.accessMode().wire)
            put("ssdp", JsonObject(ssdpPorts().mapValues { JsonPrimitive(it.value) }))
            put(
                "renderers",
                JsonArray(
                    (registry?.renderers().orEmpty()).map { r ->
                        buildJsonObject {
                            put("name", r.name)
                            put("udn", r.udn)
                            put("address", r.address)
                            put("port", r.port)
                            put("status", (r as? Player)?.status?.name)
                            put("lastError", (r as? Renderer)?.lastError)
                        }
                    },
                ),
            )
            put("casting", castCount.value.casting)
            put("total", castCount.value.total)
        }
    }

    // ------------------------------------------------------------------------------------------ stop

    /**
     * Removes everything this bridge put on the network (D-06, Pitfall 12): in-flight setups are cancelled, every receiver
     * is destroyed (byebye, DIAL port closed, loungeScreenDisconnected), then the SSDP client, the responder and the media
     * server stop. Each step is isolated, so one failure never skips the rest. Safe to call twice.
     */
    suspend fun stop(clearSpeakers: Boolean = false) {
        if (!stopped.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            step("handlers") { handlerJob.cancelAndJoin() }
            step("receivers") { registry?.removeAll() }
            step("ssdp-client") { withContext(Dispatchers.IO) { ssdp?.stop() } }
            step("ssdp-responder") { withContext(Dispatchers.IO) { responder?.stop() } }
            step("media") { withContext(Dispatchers.IO) { mediaServer?.stop() } }
            _castCount.value = BridgeCount(0, 0)
            emit("bridge", "event" to "stopped")
        }
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            failure("stop:$name", t)
        }
    }
}
