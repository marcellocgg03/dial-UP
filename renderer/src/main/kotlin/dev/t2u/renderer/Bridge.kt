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
import dev.t2u.lounge.InnerTubeEndpoints
import dev.t2u.lounge.InnerTubePlaylistRequestHandler
import dev.t2u.lounge.LoungeEndpoints
import dev.t2u.lounge.Player
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.ReceiverDeps
import dev.t2u.lounge.ReceiverFactory
import dev.t2u.lounge.YouTubeCastReceiver
import dev.t2u.media.FileBuilder
import dev.t2u.media.MediaRequest
import dev.t2u.media.MediaServer
import dev.t2u.media.PipeLauncher
import dev.t2u.media.SourceDownloader
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
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
 *
 * The last three are injectable so tests can stand in for YouTube and googlevideo, and default to production: [innerTubeEndpoints]
 * is www.youtube.com, [upstreamPolicy] proxies googlevideo hosts over https only (T-03-31), and [sourceDownloader] (wired in
 * plan 03-11 on Android) is what the file formats mp4 and flac download their source with; without it, or without [launcher]
 * or [bufferDir], file builds are unavailable.
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
    val innerTubeEndpoints: InnerTubeEndpoints = InnerTubeEndpoints(),
    val sourceDownloader: SourceDownloader? = null,
    val upstreamPolicy: UpstreamPolicy = UpstreamPolicy.GOOGLEVIDEO,
    /** Wall clock in ms: it keeps counting while the CPU sleeps, unlike delay and nanoTime (RESEARCH Pitfall 3). Tests inject one. */
    val clock: () -> Long = System::currentTimeMillis,
    /** How often the bridge runs [Bridge.housekeeping] by itself while the CPU is awake. */
    val housekeepingIntervalMs: Long = HOUSEKEEPING_INTERVAL_MS,
    /**
     * What the phone's Wi-Fi is right now; a null result means there is no Wi-Fi at this moment. No supplier means the bridge
     * assumes it is present, as in JVM tests. The app passes a function that reads the system (plan 05-19).
     */
    val currentNetwork: (() -> BridgeNetwork?)? = null,
    /** Tests only: builds the SSDP responder for a network. Null builds the production responder. */
    val responderFactory: ((BridgeNetwork, SsdpEvents, DialAccessPolicy) -> SsdpResponder)? = null,
)

/** One denial per source per minute in the log, so a scanner on the LAN cannot flood it. */
private const val DROP_LOG_WINDOW_MS = 60_000L

/**
 * D-06: how often the bridge re-announces (LAN mode), probes its own SSDP listener and searches for speakers. Five minutes
 * is six times inside the advertised max-age of 1800 s (SsdpMessage CACHE-CONTROL); there is no jitter because one host
 * sends it.
 */
const val HOUSEKEEPING_INTERVAL_MS = 300_000L

/** A receiver younger than this is kept in the move list even while it is still "stopped" (its start has not run yet). */
private const val TRACK_GRACE_MS = 120_000L

/** How long a housekeeping run waits for its own listener to hear the probe datagram. */
private const val PROBE_TIMEOUT_MS = 2000L

class Bridge(net: BridgeNetwork, private val deps: BridgeDeps) {
    /** Where the bridge listens now; [rebindNetwork] replaces it, so every reader takes the current value (plan 05-07). */
    @Volatile private var net: BridgeNetwork = net

    /** Set by [networkLost], cleared by [rebindNetwork]: housekeeping has nothing to do on a dead network. */
    @Volatile private var networkDown = false

    /** Every receiver this bridge built, so a move can reach them; stopped ones are dropped when a new one is built. */
    private val receiverList = java.util.concurrent.CopyOnWriteArrayList<TrackedReceiver>()

    private class TrackedReceiver(val receiver: YouTubeCastReceiver, val createdAt: Long = System.currentTimeMillis())

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

    /** The access policy the responder and the receivers share; housekeeping reads it to decide whether to announce. */
    @Volatile private var accessPolicy: DialAccessPolicy? = null

    /** Serialises the network housekeeping, so two triggers never probe and reopen sockets at the same time. */
    private val netLock = Mutex()

    /** Wall-clock time the last run started; null before the first. Guards against a timer tick and an alarm tick doubling up. */
    @Volatile private var lastHousekeepingAt: Long? = null

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
            // File builds (mp4, flac) need ffmpeg, a place to write and a way to download the source. The builder keeps its
            // finished files in <bufferDir>/files across starts and prunes them to config.json `cacheMb` (D-02, D-12); it
            // owns that directory and deletes in it, so it gets a sub-directory of the buffer directory, never the buffer
            // directory itself (RESEARCH Pitfall 7). The media server owns the builder's lifetime.
            val launcher = deps.launcher
            val bufferDir = deps.bufferDir
            val downloader = deps.sourceDownloader
            val files = if (launcher != null && bufferDir != null && downloader != null) {
                FileBuilder(
                    File(bufferDir, "files"),
                    launcher,
                    deps.resolver,
                    downloader,
                    flacBits = base.flacBits,
                    ffmpegLogLevel = base.ffmpegLogLevel,
                    // read by the cache at every prune, so the deferred cache slider needs no restart
                    cacheLimitBytes = { base.cacheMb.toLong() * 1_048_576L },
                    onEvent = { fields -> emit("filebuild", *fields.map { it.key to it.value }.toTypedArray()) },
                )
            } else {
                null
            }
            val media = MediaServer(
                wifi,
                base.mediaPort,
                launcher,
                deps.onMediaRequest,
                bufferDir,
                deps.resolver,
                upstream = OkHttpUpstreamFetcher(deps.http, deps.upstreamPolicy),
                files = files,
                mp3Bitrate = base.mp3Bitrate,
                ffmpegLogLevel = base.ffmpegLogLevel,
            )
            media.start()
            mediaServer = media
            val config = base.copy(mediaPort = media.resolvedPort())
            effective = config

            val policy = DialAccessPolicy(deps.accessMode, phoneAddresses = { setOf(net.wifiIp.hostAddress.orEmpty(), "127.0.0.1", "::1") })
            accessPolicy = policy

            val resp = newResponder(net, policy)
            resp.start()
            responder = resp

            // reads the field, so a receiver that outlives a move advertises on the responder of the new address
            val advertiser = object : DialAdvertiser {
                override fun advertise(device: SsdpDevice) {
                    responder?.register(device)
                    responder?.announce()
                }

                override fun withdraw(uuid: String) {
                    responder?.withdraw(uuid)
                }
            }

            val ports = PortRegistry(config.dataDir, config.receiverBasePort) { line -> emit("ports", "warn" to line) }.load()
            val receivers = ReceiverFactory { player, options ->
                // a receiver built after a move binds the current address
                val receiver = YouTubeCastReceiver(
                    player,
                    options,
                    ReceiverDeps(
                        bindHost = net.wifiIp.hostAddress.orEmpty(),
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
                track(receiver)
                receiver
            }
            val factory = RendererFactory { udn, location, address, maxAge, port ->
                Renderer(
                    udn,
                    location,
                    address,
                    maxAge,
                    port,
                    RendererDeps(
                        config,
                        deps.resolver,
                        media,
                        deps.http,
                        receivers,
                        deps.scope,
                        deps.events,
                        // one handler per renderer: each speaker keeps its own visitorData and recently-asked ring
                        playlistRequestHandlerFactory = { InnerTubePlaylistRequestHandler(deps.http, deps.innerTubeEndpoints, deps.events) },
                    ),
                )
            }
            val reg = RendererRegistry(config, ports, factory, deps.events)
            registry = reg

            val client = Ssdp(listOf(net.wifiIp), clientEvents(reg), net.ssdpSearchTarget)
            client.start()
            ssdp = client

            handlerScope.launch { countFlow(reg).collect { _castCount.value = it } }
            // reads the field, so a later replacement of the client is followed (plan 05-07)
            reg.launchSchedules(handlerScope) { ssdp?.search(RENDERER_ST, 3) }
            handlerScope.launch {
                while (true) {
                    delay(deps.housekeepingIntervalMs)
                    housekeeping("timer")
                }
            }
            emit("bridge", "event" to "started", "wifi" to wifi, "mediaPort" to config.mediaPort)
        } catch (t: Throwable) {
            failure("start", t)
            runBlocking { stop() }
            throw t
        }
    }

    // ------------------------------------------------------------------------------------------ SSDP wiring

    private fun newResponder(network: BridgeNetwork, policy: DialAccessPolicy) = SsdpResponder(
        network.ssdpListen,
        network.wifiIp,
        network.iface,
        responderEvents(),
        replyPolicy = policy::allowsSsdp,
        announcePolicy = policy::announceAllowed,
        notifyTarget = network.notifyTarget,
    )

    /**
     * Remembers a receiver so [rebindNetwork] can move it. A stopped receiver is forgotten when the next one is built, unless
     * it is young: a receiver that was just built is "stopped" too until its start runs.
     */
    private fun track(receiver: YouTubeCastReceiver) {
        val now = System.currentTimeMillis()
        receiverList.removeIf { it.receiver.status == "stopped" && now - it.createdAt > TRACK_GRACE_MS }
        receiverList.add(TrackedReceiver(receiver))
    }

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

    /**
     * The periodic safety net for discovery (D-06, Phase 1 carry-over d). In LAN mode it sends ssdp:alive for every
     * receiver again; in phone-only mode it sends nothing (the receivers must stay invisible to the LAN) and logs why.
     * In both modes it proves that the responder's own listener still receives, by sending itself one probe, and on
     * silence it reopens the listen sockets (a socket-level repair, never a bridge rebind) and probes once more.
     * Returns true when the run happened; false when the bridge is not running or the run failed (also logged).
     *
     * It skips (touching nothing) while the network is marked down (rebind switch on) and while the system reports no Wi-Fi
     * ([BridgeDeps.currentNetwork]; this works with the rebind switch off, where [networkLost] is never called): probing
     * and reopening sockets during an outage the bridge was not told about is what broke Session E step 5.
     */
    suspend fun housekeeping(trigger: String): Boolean {
        if (!started.get() || stopped.get()) return false
        if (networkDown) {
            emit("housekeeping", "trigger" to trigger, "skipped" to "network-down")
            return false
        }
        if (!wifiPresent()) {
            emit("housekeeping", "trigger" to trigger, "skipped" to "no-wifi")
            return false
        }
        return try {
            // a timer tick and an alarm tick (plan 05-11) must not double up; the adb trigger is a person asking
            val now = deps.clock()
            val last = lastHousekeepingAt
            if (trigger != "adb" && last != null && now - last < deps.housekeepingIntervalMs / 2) {
                emit("housekeeping", "trigger" to trigger, "skipped" to "recent", "ranAgoMs" to now - last)
                return false
            }
            lastHousekeepingAt = now
            netLock.withLock { runHousekeeping(trigger) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failure("housekeeping", t)
            false
        }
    }

    /** False only when a supplier is set and reports no Wi-Fi right now; a supplier that throws counts as present. */
    private fun wifiPresent(): Boolean {
        val supplier = deps.currentNetwork ?: return true
        return try {
            supplier() != null
        } catch (_: Exception) {
            true
        }
    }

    private suspend fun runHousekeeping(trigger: String): Boolean {
        if (stopped.get()) return false
        val resp = responder ?: return false
        val t0 = System.nanoTime()
        // refresh the speakers too: the in-process discovery timer may have slept through the last interval
        val client = ssdp
        val searched = client != null && try {
            withContext(Dispatchers.IO) { client.search(RENDERER_ST, 3) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failure("housekeeping-search", t)
            false
        }
        // the registry defers its first prune after a long sleep until a search ran (plan 05-04)
        if (searched) registry?.searched()
        if (accessPolicy?.announceAllowed() == true) {
            withContext(Dispatchers.IO) { resp.announce() }
            emit("reannounce", "mode" to "lan", "trigger" to trigger, "devices" to registry?.renderers().orEmpty().size)
        } else {
            emit("reannounce", "mode" to "phone", "skipped" to "phone-only", "trigger" to trigger)
        }
        // speakers whose setup failed or whose receiver stopped itself: the timer may have slept through their time (D-05)
        val retried = try {
            registry?.retryDue() ?: 0
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failure("housekeeping-retry", t)
            0
        }
        if (networkDown || !wifiPresent()) {
            emit("ssdp-probe", "skipped" to if (networkDown) "network-down" else "no-wifi", "trigger" to trigger)
            return true
        }
        val ok = withContext(Dispatchers.IO) { resp.selfProbe(PROBE_TIMEOUT_MS) }
        emit("ssdp-probe", "ok" to ok, "trigger" to trigger)
        if (!ok) {
            val restarted = withContext(Dispatchers.IO) { resp.restartListeners() }
            val again = withContext(Dispatchers.IO) { resp.selfProbe(PROBE_TIMEOUT_MS) }
            emit("ssdp-probe", "ok" to again, "restarted" to restarted, "trigger" to trigger)
        }
        emit("housekeeping", "trigger" to trigger, "ran" to true, "searched" to searched, "retried" to retried, "ms" to (System.nanoTime() - t0) / 1_000_000)
        return true
    }

    // ------------------------------------------------------------------------------------------ network move

    /**
     * The phone lost its Wi-Fi: marks the network down and stops nothing. Housekeeping skips (network-down) until the next
     * [rebindNetwork]. Ignored unless the bridge is running.
     */
    suspend fun networkLost() {
        netLock.withLock {
            if (!started.get() || stopped.get()) return
            networkDown = true
            emit("bridge", "event" to "network-lost")
            // polls pause on every renderer: what a broken stream does on the speaker is not mirrored to the phone (plan 05-08)
            for (r in registry?.renderers().orEmpty()) step("renderer", "network-lost") { r.onNetworkLost() }
        }
    }

    /**
     * Moves the running bridge to [newNet] in place (SVC-04, RESEARCH Pattern 3): a new SSDP responder and client on the new
     * address, every running receiver's DIAL server moved by [YouTubeCastReceiver.rebindDial]. Renderers, players and lounge
     * sessions stay, so the phone's cast session survives, and nothing is cleared on any speaker. Each step is isolated, so
     * one failure leaves the rest moved and logged. A move onto the same address runs the same path. Does nothing unless
     * the bridge is running; never retries (D-05) and is only called on network events (D-06).
     */
    suspend fun rebindNetwork(newNet: BridgeNetwork) {
        netLock.withLock {
            if (!started.get() || stopped.get()) return
            val oldIp = net.wifiIp.hostAddress.orEmpty()
            val newIp = newNet.wifiIp.hostAddress.orEmpty()
            val policy = accessPolicy
            val reg = registry
            val oldResponder = responder
            val oldClient = ssdp

            // the media server first: URLs built after the move must point at a server that answers; tokens are kept
            step("media", "rebind") { withContext(Dispatchers.IO) { mediaServer?.rebindHost(newIp) } }
            step("responder-stop", "rebind") { withContext(Dispatchers.IO) { oldResponder?.stop() } }
            // the old listeners are closed before the new ones open, so a same-address move never fights itself for the socket
            net = newNet
            if (policy != null) {
                step("responder-start", "rebind") {
                    val fresh = newResponder(newNet, policy)
                    withContext(Dispatchers.IO) { fresh.start() }
                    responder = fresh
                }
            }
            step("ssdp-client", "rebind") {
                withContext(Dispatchers.IO) { oldClient?.stop() }
                if (reg != null) {
                    val fresh = Ssdp(listOf(newNet.wifiIp), clientEvents(reg), newNet.ssdpSearchTarget)
                    withContext(Dispatchers.IO) { fresh.start() }
                    ssdp = fresh
                }
            }
            var moved = 0
            for (tracked in receiverList.toList()) {
                val receiver = tracked.receiver
                val running = receiver.status == "running"
                step("dial", "rebind") { receiver.rebindDial(newIp) }
                if (running) moved++
            }
            // the renderers learn the new address (they re-set the loaded track in their own scopes, one slow speaker holds up no other)
            for (r in registry?.renderers().orEmpty()) step("renderer", "rebind") { r.onNetworkChanged(newIp) }
            step("search", "rebind") {
                withContext(Dispatchers.IO) { ssdp?.search(RENDERER_ST, 3) }
                registry?.searched()
            }
            networkDown = false
            emit("bridge", "event" to "rebound", "oldIp" to oldIp, "newIp" to newIp, "receivers" to moved)
        }
    }

    /** Tests only: the current SSDP client, so a test can break it the way a failed rebind step or bind does. */
    internal fun ssdpClientForTest(): Ssdp? = ssdp

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
                            // The learned SetNextAVTransportURI support: true, false, or JSON null while unknown (D-15).
                            put("gapless", (r as? Renderer)?.gaplessSupport)
                        }
                    },
                ),
            )
            put(
                "cache",
                mediaServer?.cacheInfo()?.let { c ->
                    buildJsonObject {
                        put("files", c.files)
                        put("bytes", c.bytes)
                        put("limitBytes", c.limitBytes)
                    }
                } ?: JsonNull,
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
     *
     * With [clearSpeakers] every speaker this app loaded is emptied first (CAST-06; in parallel, each bounded, see
     * [RendererRegistry.removeAll]). Clear only on an explicit service Stop: a Wi-Fi rebind (the bridge comes
     * straight back), a start that failed, a goodbye and a stale speaker leave the speaker alone.
     */
    suspend fun stop(clearSpeakers: Boolean = false) {
        if (!stopped.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            step("handlers") { handlerJob.cancelAndJoin() }
            step("receivers") { registry?.removeAll(clearSpeakers) }
            step("ssdp-client") { withContext(Dispatchers.IO) { ssdp?.stop() } }
            step("ssdp-responder") { withContext(Dispatchers.IO) { responder?.stop() } }
            step("media") { withContext(Dispatchers.IO) { mediaServer?.stop() } }
            _castCount.value = BridgeCount(0, 0)
            emit("bridge", "event" to "stopped")
        }
    }

    private suspend fun step(name: String, phase: String = "stop", block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            // a cancelled move is not a failed step; stop() keeps its old behaviour (every step is tried, then the next)
            if (phase != "stop") throw e
            failure("$phase:$name", e)
        } catch (t: Throwable) {
            failure("$phase:$name", t)
        }
    }
}
