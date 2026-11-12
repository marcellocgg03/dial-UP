package dev.t2u.renderer

// Port of tube2upnp src/index.js (composition root), GPL-3.0-only.
//
// Wires the pieces into one running bridge: the media server, the SSDP responder (the DIAL side: answers the phone's
// searches) and the SSDP client (the speaker side: finds MediaRenderers), the renderer registry, and one Renderer per
// speaker whose cast receiver is the YouTubeCastReceiver. Pure JVM: the Android glue (Wi-Fi address, payloads, settings,
// notification) lives in :app and hands everything in through [BridgeNetwork] and [BridgeDeps].
//
// One DialAccessPolicy feeds both the DIAL servers (through the receiver) and the SSDP responder, so the
// access mode is read on every request by both. Everything that listens does so on the Wi-Fi IPv4 address only.

import dev.t2u.dial.DialAccessMode
import dev.t2u.dial.DialAccessPolicy
import dev.t2u.lounge.DialAdvertiser
import dev.t2u.lounge.InnerTubePlaylistRequestHandler
import dev.t2u.lounge.Player
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.ReceiverDeps
import dev.t2u.lounge.ReceiverFactory
import dev.t2u.lounge.YouTubeCastReceiver
import dev.t2u.media.CacheInfo
import dev.t2u.media.FileBuilder
import dev.t2u.media.MediaServer
import dev.t2u.upnp.RENDERER_ST
import dev.t2u.upnp.Ssdp
import dev.t2u.upnp.SsdpClientEvents
import dev.t2u.upnp.SsdpDevice
import dev.t2u.upnp.SsdpEvents
import dev.t2u.upnp.SsdpMessage
import dev.t2u.upnp.SsdpResponder
import dev.t2u.upnp.udnFromUsn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class Bridge(net: BridgeNetwork, private val deps: BridgeDeps) {
    /** Where the bridge listens now; [rebindNetwork] replaces it, so every reader takes the current value. */
    @Volatile private var net: BridgeNetwork = net

    /** Set by [networkLost], cleared by [rebindNetwork]: housekeeping has nothing to do on a dead network. */
    @Volatile private var networkDown = false

    /** Every receiver this bridge built, so a move can reach them; stopped ones are dropped when a new one is built. */
    private val receiverList = CopyOnWriteArrayList<TrackedReceiver>()

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

    /**
     * Serialises everything that changes the sockets: the probe and repair step of housekeeping, [networkLost] and
     * [rebindNetwork]. A housekeeping pass holds it only around that step, never through its search and retries, so a
     * network drop is acknowledged at once.
     */
    private val netLock = Mutex()

    /** Serialises whole housekeeping passes, so two triggers never run one after the other's half-finished state. */
    private val housekeepingLock = Mutex()

    /** What the repair and rebind classes read and replace; every member goes to the field at the moment of use. */
    private val repairHost = object : NetworkRebinderHost {
        override val started get() = this@Bridge.started
        override val stopped get() = this@Bridge.stopped
        override var networkDown: Boolean
            get() = this@Bridge.networkDown
            set(value) {
                this@Bridge.networkDown = value
            }
        override var net: BridgeNetwork
            get() = this@Bridge.net
            set(value) {
                this@Bridge.net = value
            }
        override val registry get() = this@Bridge.registry
        override val accessPolicy get() = this@Bridge.accessPolicy
        override val mediaServer get() = this@Bridge.mediaServer
        override val receivers get() = receiverList.map { it.receiver }
        override val advertisedDevices get() = this@Bridge.advertisedDevices
        override var responder: SsdpResponder?
            get() = this@Bridge.responder
            set(value) {
                this@Bridge.responder = value
            }
        override var ssdp: Ssdp?
            get() = this@Bridge.ssdp
            set(value) {
                this@Bridge.ssdp = value
            }

        override fun buildResponder(network: BridgeNetwork, policy: DialAccessPolicy) = this@Bridge.buildResponder(network, policy)

        override fun clientEvents(reg: RendererRegistry): SsdpClientEvents = this@Bridge.clientEvents(reg)

        override suspend fun multicastSearch(trigger: String, client: Ssdp) = this@Bridge.multicastSearch(trigger, client)

        override suspend fun multicastSearch(trigger: String) = this@Bridge.multicastSearch(trigger)

        override suspend fun step(name: String, phase: String, block: suspend () -> Unit) = this@Bridge.step(name, phase, block)

        override fun emit(kind: String, vararg fields: Pair<String, Any?>) = this@Bridge.emit(kind, *fields)

        override fun failure(where: String, t: Throwable) = this@Bridge.failure(where, t)
    }

    /** Housekeeping's probe and repair step; it runs under [netLock], the same Mutex [networkLost] and [rebindNetwork] take. */
    private val repair = BridgeRepair(repairHost, deps, netLock)

    /** Losing and regaining the network; it takes the same [netLock] as [repair]. */
    private val rebinder = NetworkRebinder(repairHost, deps, netLock)

    /**
     * Wall-clock time the last run that completed started; null before the first. A run that failed or was cancelled stamps
     * nothing, so the next trigger runs instead of being skipped as recent. Guards against a timer tick and an alarm
     * tick doubling up.
     * It is the wall clock (not nanoTime) because sleep time must count; a clock that moved backwards counts as "not recent".
     */
    @Volatile private var lastHousekeepingAt: Long? = null

    /** What the manual-speaker coordinator reads from this bridge; every member reads the field at the moment of use. */
    private val manualHost = object : ManualSpeakerHost {
        override val registry get() = this@Bridge.registry
        override val ssdp get() = this@Bridge.ssdp
        override val started get() = this@Bridge.started
        override val stopped get() = this@Bridge.stopped
        override val networkDown get() = this@Bridge.networkDown
        override val handlerScope get() = this@Bridge.handlerScope

        override fun emit(kind: String, vararg fields: Pair<String, Any?>) = this@Bridge.emit(kind, *fields)

        override fun failure(where: String, t: Throwable) = this@Bridge.failure(where, t)
    }

    /** Adding, probing, watching and removing the renderers saved by address. */
    private val manual = ManualSpeakerCoordinator(manualHost, deps)

    /**
     * What the receivers advertised, by uuid. The bridge owns this list so a responder built later (a housekeeping repair
     * after a failed rebind step or a deaf responder) has something to serve. A rebind does not copy it: the receivers
     * re-advertise their moved locations. A repair copies it onto the new responder.
     */
    private val advertisedDevices = ConcurrentHashMap<String, SsdpDevice>()

    private val dropLogged = ConcurrentHashMap<String, Long>()

    /**
     * Records a reply from [address] in the manual-speaker coordinator's table (see [ManualSpeakerCoordinator.noteReply]).
     * Internal so a test can drive it.
     */
    internal fun noteReply(address: String, udn: String) = manual.noteReply(address, udn)

    /** When [address] last replied, for tests; null when the table holds nothing for it. */
    internal fun replySourceAt(address: String): Long? = manual.replySourceAt(address)

    private val _castCount = MutableStateFlow(BridgeCount(0, 0))

    /** Derived from the renderers' actual player status; used to keep the Wi-Fi-loss grace period active. */
    val castCount: StateFlow<BridgeCount> = _castCount.asStateFlow()

    private val _visibleCastCount = MutableStateFlow(BridgeCount(0, 0))

    /** Derived from card-visible state; a brief network outage retains playback state while polling is paused. */
    val visibleCastCount: StateFlow<BridgeCount> = _visibleCastCount.asStateFlow()

    private val _speakers = MutableStateFlow<List<SpeakerUi>>(emptyList())

    /**
     * One entry per speaker the bridge can cast to (a device without AVTransport or one the name filter excludes is never in
     * the registry), each following its [Renderer.ui]. Empty until the first speaker is up and again after [stop].
     */
    val speakers: StateFlow<List<SpeakerUi>> = _speakers.asStateFlow()

    private val searchStateLock = Any()
    private var activeSearchWindows = 0
    private val _searching = MutableStateFlow(false)

    /** True while at least one multicast renderer search is inside its SSDP reply window. */
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private fun beginSearchWindow() = synchronized(searchStateLock) {
        activeSearchWindows++
        _searching.value = true
    }

    private fun finishSearchWindow() = synchronized(searchStateLock) {
        if (activeSearchWindows > 0) activeSearchWindows--
        _searching.value = activeSearchWindows > 0
    }

    /** Sends one multicast M-SEARCH and keeps [searching] true through its advertised reply window. */
    private suspend fun multicastSearch(
        trigger: String,
        client: Ssdp? = ssdp,
        markRegistrySearched: Boolean = true,
    ): Boolean {
        if (!started.get() || stopped.get() || networkDown || client == null || !client.isBound()) return false

        beginSearchWindow()
        var windowScheduled = false
        try {
            withContext(Dispatchers.IO) { client.search(RENDERER_ST, 3) }
            if (markRegistrySearched) registry?.searched()
            emit("search", "trigger" to trigger, "sent" to true)

            val window = handlerScope.launch(start = CoroutineStart.LAZY) {
                delay(SSDP_SEARCH_REPLY_WINDOW_MS)
            }
            window.invokeOnCompletion { finishSearchWindow() }
            windowScheduled = true
            window.start()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failure("search:$trigger", t)
            return false
        } finally {
            if (!windowScheduled) finishSearchWindow()
        }
    }

    /** The running renderer with this UDN, for the card controls of the status UI; null when there is none. */
    fun renderer(udn: String): Renderer? = registry?.renderers().orEmpty().filterIsInstance<Renderer>().firstOrNull { it.udn == udn }

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

    /** The cache cap in MiB now: the app's supplier when it answers, else config.json (a throwing supplier counts as none). */
    private fun cacheCapMb(base: Config): Int = try {
        deps.cacheMb?.invoke() ?: base.cacheMb
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        base.cacheMb
    }

    /**
     * Deletes every finished file in the cache that no stream pins and returns how many went; 0 when this
     * bridge has no file builder. A `cache` event records the count.
     */
    fun clearCache(): Int {
        val cleared = mediaServer?.clearCache() ?: 0
        emit("cache", "cleared" to cleared)
        return cleared
    }

    /** Files, bytes and cap of the cache now; null when this bridge has no file builder. */
    fun cacheInfo(): CacheInfo? = mediaServer?.cacheInfo()

    /** How many file builds and source downloads run now (0 without a file builder); the yt-dlp updater waits while it is above 0. */
    fun activeBuilds(): Int = mediaServer?.activeBuilds() ?: 0

    /** Whether a cast that ends empties the speaker now: the app's supplier when it answers, else the fixed config flag. */
    private fun clearOnCastEndNow(): Boolean = try {
        deps.clearOnCastEnd?.invoke() ?: (effective ?: deps.config).clearOnCastEnd
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        (effective ?: deps.config).clearOnCastEnd
    }

    /**
     * Drops the speaker [udn] as a DIAL target at once and keeps it out until [unhide]. The speaker joins the
     * hidden set first, so every guard (alive, retry, a setup in flight) refuses it before the receiver goes; then a playing
     * speaker is stopped (and emptied only when clear-when-cast-ends is on) and its receiver stopped, which sends
     * ssdp:byebye, closes its DIAL port and ends the lounge session. The other speakers are not touched. A saved renderer that
     * does not answer (no running speaker, but an entry in manual.json) is hidden too: there is nothing to stop, its greyed
     * card leaves the list, and its probes go on while the registry's hidden guard refuses their answers. False when there is
     * neither a running nor a saved speaker with that UDN, or no hidden store.
     */
    suspend fun hide(udn: String): Boolean {
        val reg = registry ?: return false
        val store = deps.hidden ?: return false
        if (stopped.get()) return false
        val speaker = reg.renderers().firstOrNull { it.udn == udn }
        if (speaker == null) {
            val entry = deps.manual?.entries?.value?.firstOrNull { it.udn == udn } ?: return false
            val shown = deps.names?.get(udn) ?: entry.name
            if (!store.add(HiddenSpeaker(udn, shown, entry.ip))) return false
            emit("hidden", "udn" to udn, "name" to shown)
            return true
        }
        if (!store.add(HiddenSpeaker(udn, speaker.name ?: udn, speaker.address))) return false
        reg.hide(udn, stop = true, clear = clearOnCastEndNow())
        emit("hidden", "udn" to udn, "name" to speaker.name)
        return true
    }

    /**
     * Rename: [name] is the author's name for the renderer [udn]; null goes back to the name the
     * renderer described itself with. A name goes through [RendererNames.set], so an unusable one (blank, over 63 characters, a
     * control character) answers false and changes nothing. Afterwards, while the bridge runs, the running renderer with that UDN
     * (if any) shows the new name on its card and serves it as its DIAL friendly name at once, without a restart. Emits `renamed`.
     * The renderer stays matched by its UDN everywhere. False without a names store.
     */
    suspend fun rename(udn: String, name: String?): Boolean {
        val store = deps.names ?: return false
        if (name == null) store.remove(udn) else if (!store.set(udn, name)) return false
        if (started.get() && !stopped.get()) renderer(udn)?.rename(store.get(udn))
        emit("renamed", "udn" to udn, "name" to store.get(udn), "reset" to (name == null))
        return true
    }

    /**
     * The prefix of the speaker names changed: every running renderer builds its name again and takes it the way a GUI rename
     * does, at once and without a restart, so a running cast keeps playing. A name the author gave a speaker is kept.
     */
    suspend fun refreshNames() {
        if (!started.get() || stopped.get()) return
        registry?.renderers().orEmpty().filterIsInstance<Renderer>().forEach { it.refreshName() }
        emit("names-refreshed")
    }

    /**
     * Brings the hidden speaker [udn] back. It leaves the hidden set, then the bridge searches for speakers at once so
     * the speaker returns without waiting for the next periodic search. False when it was not hidden (or there is no store).
     */
    suspend fun unhide(udn: String): Boolean {
        val store = deps.hidden ?: return false
        if (!store.remove(udn)) return false
        emit("unhidden", "udn" to udn)
        if (started.get() && !stopped.get()) {
            multicastSearch("unhide")
        }
        return true
    }

    /**
     * One MediaRenderer search on demand, from the Refresh button or the app coming to the foreground. [trigger] names
     * who asked (`ui`, `foreground`). It goes through the running bridge's SSDP client and never opens or reopens sockets; the
     * periodic schedule and the prune are separate and unchanged. Sends nothing, and answers false, when the bridge is not
     * running or its network is marked down (emits `search` with `skipped`). True when the search was sent.
     */
    suspend fun searchNow(trigger: String): Boolean {
        if (!started.get() || stopped.get()) return false
        if (networkDown) {
            emit("search", "trigger" to trigger, "skipped" to "network-down")
            return false
        }
        // the field is read once, at the moment of use; a rebind may replace it
        val client = ssdp
        if (client == null) {
            emit("search", "trigger" to trigger, "skipped" to "no-client")
            return false
        }
        return multicastSearch(trigger, client)
    }

    /**
     * The Refresh button when nothing is cast to: forgets every listed renderer and then
     * searches from scratch, as at the first discovery, so the list is rebuilt from what answers now. Removing a renderer ends
     * its cast like a service stop does: playing speakers are stopped, and emptied when clear-when-cast-ends is on. Callers ask
     * the author first while anything is cast to. Hidden renderers stay hidden. True when the search
     * was sent.
     */
    suspend fun rediscover(trigger: String): Boolean {
        if (!started.get() || stopped.get()) return false
        // Nothing is forgotten unless a search can follow: with the network down or no bound client the list would be left
        // empty with nothing to refill it, so the skipped search is logged and the speakers stay as they are.
        val client = ssdp
        if (networkDown || client == null || !client.isBound()) return searchNow(trigger)
        registry?.removeAll(clearSpeakers = clearOnCastEndNow(), stopSpeakers = true)
        val sent = searchNow(trigger)
        // a speaker whose setup failed is otherwise held back for minutes, and its search replies are ignored meanwhile
        if (sent) handlerScope.launch { registry?.retryFailedNow() }
        return sent
    }

    /**
     * Adds the speaker at [ip] by asking it directly. One unicast M-SEARCH goes to [ip] on
     * [BridgeDeps.manualSsdpPort]; the reply reaches the registry like any search reply (so its guards apply: the LOCATION host
     * must equal the sender and be private LAN, excluded servers and hidden speakers are refused), and the card exists only
     * when that setup succeeds. Nothing is sent unless [manualTargetAllowed] accepts [ip]. Only a reply whose source
     * address is [ip] counts. A Sonos speaker ignores a unicast M-SEARCH, so when no reply came within
     * [BridgeDeps.manualSsdpGraceMs] the bridge fetches the device description from the typed address (one GET to
     * [BridgeDeps.manualDescriptionUrl], host must equal [ip]) and hands it to the registry as synthetic alive headers, so the
     * same guards apply. Waits at most [BridgeDeps.manualAnswerTimeoutMs] in total. A speaker the bridge already lists is
     * reported at once. An Added result is saved, every other result saves nothing.
     */
    suspend fun addManual(ip: InetAddress): ManualAddResult = manual.addManual(ip)

    /**
     * Forgets the saved manual speaker [udn]: it leaves manual.json and the list, and when it runs it is taken out
     * of the registry (byebye, DIAL server closed); its hidden and renamed marks go too. A speaker that multicast discovery
     * also finds comes back at the next search as an ordinary discovered speaker without the manual mark; hiding it is what
     * keeps it away; a search goes out at once so that speaker is listed again without waiting for the periodic one.
     * False when it was not saved.
     * The app calls this from Delete on the card of a renderer saved by address; BridgeManualTest covers it.
     */
    suspend fun removeManual(udn: String): Boolean {
        if (!manual.removeManual(udn)) return false
        searchNow("delete")
        return true
    }

    /**
     * Blocking form of [startSuspending], for tests and callers that are not in a coroutine. The app calls [startSuspending]:
     * a blocking start inside a coroutine would hold its dispatcher thread through the bind and the engine start.
     */
    fun start() {
        runBlocking { startSuspending() }
    }

    /**
     * Starts the media server, the SSDP responder and client and the discovery schedules. The body runs on [Dispatchers.IO]
     * because the bind and the engine start block. Throws after undoing a failed start, and a start that threw (a rejected
     * config included) leaves the bridge not running.
     */
    suspend fun startSuspending() {
        check(started.compareAndSet(false, true)) { "already started" }
        try {
            withContext(Dispatchers.IO) { startBody() }
        } catch (t: Throwable) {
            // a cancelled start is not a failure to report; it still has to be undone
            if (t !is CancellationException) failure("start", t)
            stop()
            throw t
        }
    }

    private suspend fun startBody() {
        val base = deps.config
        // mediaPort 0 means "any free port" (tests); the Node range check applies to the real port below
        base.copy(mediaPort = base.mediaPort.coerceAtLeast(1)).validate()
        val wifi = net.wifiIp.hostAddress.orEmpty()
        val media = startMediaServer(base, wifi)
        val config = base.copy(mediaPort = media.resolvedPort())
        effective = config

        val policy = DialAccessPolicy(deps.accessMode, phoneAddresses = { setOf(net.wifiIp.hostAddress.orEmpty(), "127.0.0.1", "::1") })
        accessPolicy = policy

        responder = buildResponder(net, policy)

        val reg = buildRegistry(config, media, policy)
        startDiscovery(reg)
        launchWatchers(reg)
        emit("bridge", "event" to "started", "wifi" to wifi, "mediaPort" to config.mediaPort)
    }

    /** Builds and starts the media server for [base] on [wifi] and keeps it in [mediaServer]. */
    private suspend fun startMediaServer(base: Config, wifi: String): MediaServer {
        // File builds (mp4) need ffmpeg, a place to write and a way to download the source. The builder keeps its
        // finished files in <bufferDir>/files across starts and prunes them to config.json `cacheMb`; it
        // owns that directory and deletes in it, so it gets a sub-directory of the buffer directory, never the buffer
        // directory itself. The media server owns the builder's lifetime.
        val launcher = deps.launcher
        val bufferDir = deps.bufferDir
        val downloader = deps.sourceDownloader
        val files = if (launcher != null && bufferDir != null && downloader != null) {
            FileBuilder(
                File(bufferDir, "files"),
                launcher,
                deps.resolver,
                downloader,
                ffmpegLogLevel = base.ffmpegLogLevel,
                // read by the cache at every prune, so the deferred cache slider needs no restart
                cacheLimitBytes = { cacheCapMb(base).toLong() * 1_048_576L },
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
        media.startSuspending()
        mediaServer = media
        return media
    }

    /** Builds the registry over the saved ports and the receiver and renderer factories, and keeps it in [registry]. */
    private fun buildRegistry(config: Config, media: MediaServer, policy: DialAccessPolicy): RendererRegistry {
        val ports = PortRegistry(config.dataDir, config.receiverBasePort) { line -> emit("ports", "warn" to line) }.load()
        val receivers = receiverFactory(policy)
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
                    clearOnCastEnd = deps.clearOnCastEnd ?: { config.clearOnCastEnd },
                    nameTemplate = deps.namePrefix?.let { prefix -> { nameTemplateFor(prefix()) } } ?: { config.nameTemplate },
                    userName = { deps.names?.get(it) },
                    // one handler per renderer: each speaker keeps its own visitorData and recently-asked ring
                    playlistRequestHandlerFactory = { InnerTubePlaylistRequestHandler(deps.http, deps.innerTubeEndpoints, deps.events) },
                ),
            )
        }
        val reg = RendererRegistry(config, ports, factory, deps.events, hidden = { deps.hidden?.contains(it) == true })
        registry = reg
        return reg
    }

    /** The factory that builds each speaker's cast receiver, advertising through the current responder. */
    private fun receiverFactory(policy: DialAccessPolicy): ReceiverFactory {
        // reads the field, so a receiver that outlives a move advertises on the responder of the new address
        val advertiser = object : DialAdvertiser {
            override fun advertise(device: SsdpDevice) {
                advertisedDevices[device.uuid] = device
                responder?.register(device)
                responder?.announce()
            }

            override fun withdraw(uuid: String) {
                advertisedDevices.remove(uuid)
                responder?.withdraw(uuid)
            }
        }
        return ReceiverFactory { player, options ->
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
                    onDialRequest = { request ->
                        repair.noteDial()
                        deps.onDialRequest(request)
                    },
                ),
            )
            track(receiver)
            receiver
        }
    }

    /** Starts the SSDP client over [reg], keeps it in [ssdp], and probes and watches the saved manual speakers. */
    private fun startDiscovery(reg: RendererRegistry) {
        val client = Ssdp(listOf(net.wifiIp), clientEvents(reg), net.ssdpSearchTarget)
        client.start()
        ssdp = client
        val probesSentAt = System.currentTimeMillis()
        manual.probeSavedSpeakers(client, reg)
        manual.watchManualSpeakers(reg, probesSentAt)
    }

    /** Starts the count and speaker flows, the discovery schedules and the housekeeping timer on the handler scope. */
    private fun launchWatchers(reg: RendererRegistry) {
        handlerScope.launch { countFlow(reg).collect { _castCount.value = it } }
        handlerScope.launch { visibleCountFlow(reg).collect { _visibleCastCount.value = it } }
        handlerScope.launch { speakersFlow(reg).collect { _speakers.value = it } }
        // reads the field, so a later replacement of the client is followed
        reg.launchSchedules(handlerScope) { multicastSearch("schedule", markRegistrySearched = false) }
        handlerScope.launch {
            while (true) {
                delay(deps.housekeepingIntervalMs)
                housekeeping("timer")
            }
        }
    }

    // ------------------------------------------------------------------------------------------ SSDP wiring

    /** Builds and starts a responder for [network]: through the test factory when one is set, else the production one. */
    private fun buildResponder(network: BridgeNetwork, policy: DialAccessPolicy): SsdpResponder {
        val resp = deps.responderFactory?.invoke(network, responderEvents(), policy) ?: newResponder(network, policy)
        resp.start()
        return resp
    }

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
            udnFromUsn(headers["USN"])?.let { manual.noteReply(src.address.hostAddress.orEmpty(), it) }
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
     * a second socket. Datagrams are not logged one by one: a NOTIFY flood would drown the log.
     */
    private fun responderEvents() = object : SsdpEvents {
        override fun onBind(specId: String, ok: Boolean, boundPort: Int, error: String?) =
            emit("ssdp-bind", "sock" to specId, "ok" to ok, "port" to boundPort, "error" to error)

        override fun onReceive(specId: String, src: InetSocketAddress, msg: SsdpMessage, raw: String) {
            // the responder's own probe (ST urn:dev-t2u:probe:...) is not a search from the network
            if (msg.startLine.uppercase().startsWith("M-SEARCH") && msg.headers["ST"]?.startsWith("urn:dev-t2u:probe:") != true) {
                repair.noteMsearch()
            }
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

        override fun onError(where: String, t: Throwable) {
            if (isExpectedOffline(where, networkDown)) return
            failure("ssdp:$where", t)
        }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun visibleCountFlow(reg: RendererRegistry): Flow<BridgeCount> = reg.renderersFlow.flatMapLatest { list ->
        val renderers = list.filterIsInstance<Renderer>()
        if (renderers.isEmpty()) {
            flowOf(BridgeCount(0, 0))
        } else {
            combine(renderers.map { it.ui }) { speakers ->
                BridgeCount(
                    speakers.count {
                        it.status == PlayerStatus.LOADING || it.status == PlayerStatus.PLAYING || it.status == PlayerStatus.PAUSED
                    },
                    speakers.size,
                )
            }
        }
    }.distinctUntilChanged()

    /**
     * The status UI's list: every renderer's [Renderer.ui] combined, rebuilt whenever a speaker joins or leaves the registry.
     * With a manual store a running speaker whose UDN is saved is marked manual, and a saved speaker that is not
     * running (it does not answer) is listed after them as an offline card: idle, no track, controls off. A hidden saved speaker
     * is not listed: the registry refuses it, and it must not come back as a greyed card.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun speakersFlow(reg: RendererRegistry): Flow<List<SpeakerUi>> {
        val running = reg.renderersFlow.flatMapLatest { list ->
            val renderers = list.filterIsInstance<Renderer>()
            if (renderers.isEmpty()) flowOf(emptyList()) else combine(renderers.map { it.ui }) { it.toList() }
        }
        val saved = deps.manual?.entries ?: return running.distinctUntilChanged()
        val hidden = deps.hidden?.entries ?: flowOf(emptyList())
        val names = deps.names?.entries ?: flowOf(emptyMap())
        return combine(running, saved, hidden, names) { live, entries, hiddenEntries, namesMap ->
            val savedUdns = entries.mapTo(HashSet()) { it.udn }
            val liveUdns = live.mapTo(HashSet()) { it.udn }
            val hiddenUdns = hiddenEntries.mapTo(HashSet()) { it.udn }
            live.map { if (it.udn in savedUdns) it.copy(manual = true) else it } +
                savedSpeakerCards(entries.filter { it.udn !in liveUdns }, hiddenUdns, namesMap)
        }.distinctUntilChanged()
    }

    // ------------------------------------------------------------------------------------------ commands

    /** `lan` makes the receivers visible to the whole network right away; phone-only needs no action (the policy is read per request). */
    fun onAccessModeChanged(mode: DialAccessMode) {
        if (mode == DialAccessMode.LAN) responder?.announce()
    }

    /** Sends ssdp:alive for every receiver again (a no-op in phone mode, where announcing is not allowed). */
    fun reannounce() {
        responder?.announce()
    }

    /**
     * The periodic safety net for discovery. In LAN mode it sends ssdp:alive for every
     * receiver again; in phone-only mode it sends nothing (the receivers must stay invisible to the LAN) and logs why.
     * In both modes it proves that the responder's own listener still receives, by sending itself one probe, and on
     * silence it reopens the listen sockets (a socket-level repair, never a bridge rebind) and probes once more.
     * Returns true when the run happened; false when the bridge is not running or the run failed (also logged). Only a run that
     * completed counts as done: a failed one does not make the next trigger "recent".
     *
     * It skips (touching nothing) while the network is marked down ([networkLost]) and while the system reports no Wi-Fi
     * ([BridgeDeps.currentNetwork]; this still matters when [networkLost] has not been called yet): probing and reopening
     * sockets during an outage the bridge was not told about is what broke Session E step 5.
     */
    suspend fun housekeeping(trigger: String): Boolean {
        if (!started.get() || stopped.get()) return false
        if (networkDown) {
            emit("housekeeping", "trigger" to trigger, "skipped" to "network-down", *repair.inboundEvidence())
            return false
        }
        if (!repair.wifiPresent()) {
            emit("housekeeping", "trigger" to trigger, "skipped" to "no-wifi", *repair.inboundEvidence())
            return false
        }
        return try {
            // a timer tick and an alarm tick must not double up; the adb trigger is a person asking
            // the recent check sits inside the lock so two simultaneous triggers run once
            housekeepingLock.withLock {
                val now = deps.clock()
                val last = lastHousekeepingAt
                // a negative elapsed time means the wall clock moved back: that is not "recent"
                val elapsed = if (last != null) now - last else null
                if (trigger != "adb" && elapsed != null && elapsed >= 0 && elapsed < deps.housekeepingIntervalMs / 2) {
                    emit("housekeeping", "trigger" to trigger, "skipped" to "recent", "ranAgoMs" to elapsed)
                    false
                } else {
                    val ran = repair.runHousekeeping(trigger)
                    // stamped only by a run that completed, from the time it started; a failed or cancelled run stamps nothing
                    if (ran) lastHousekeepingAt = now
                    ran
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failure("housekeeping", t)
            false
        }
    }

    // ------------------------------------------------------------------------------------------ network move

    /**
     * The phone lost its Wi-Fi: marks the network down and stops nothing. Housekeeping skips (network-down) until the next
     * [rebindNetwork]. Ignored unless the bridge is running.
     */
    suspend fun networkLost() = rebinder.networkLost()

    /**
     * Moves the running bridge to [newNet] in place: a new SSDP responder and client on the new
     * address, every running receiver's DIAL server moved by [YouTubeCastReceiver.rebindDial]. Renderers, players and lounge
     * sessions stay, so the phone's cast session survives, and nothing is cleared on any speaker. When the address is
     * unchanged, the media listener stays in place to avoid interrupting active streams with an unnecessary server restart.
     * Each step is isolated, so one failure leaves the rest moved and logged. Does nothing unless the bridge is running and is
     * only called on network events. It does not retry with one exception: a changed-address media bind is tried
     * again after [BridgeDeps.mediaRebindBackoffMs], and a media server that is still down afterwards is rebound by
     * housekeeping ([repairMedia]).
     */
    suspend fun rebindNetwork(newNet: BridgeNetwork) = rebinder.rebindNetwork(newNet)

    /** Tests only: the current SSDP client, so a test can break it the way a failed rebind step or bind does. */
    internal fun ssdpClientForTest(): Ssdp? = ssdp

    /** The port each responder listen socket bound, by spec id. */
    fun ssdpPorts(): Map<String, Int> = responder?.boundPorts().orEmpty()

    /**
     * One resolve so the first real cast does not pay the cold start of yt-dlp on top of the handshake.
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
                            // The learned SetNextAVTransportURI support: true, false, or JSON null while unknown.
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
                        put("temporaryFiles", c.temporaryFiles)
                        put("temporaryBytes", c.temporaryBytes)
                    }
                } ?: JsonNull,
            )
            put("casting", castCount.value.casting)
            put("total", castCount.value.total)
        }
    }

    // ------------------------------------------------------------------------------------------ stop

    /**
     * Removes everything this bridge put on the network: in-flight setups are cancelled, every receiver
     * is destroyed (byebye, DIAL port closed, loungeScreenDisconnected), then the SSDP client, the responder and the media
     * server stop. Each step is isolated, so one failure never skips the rest. Safe to call twice.
     *
     * With [clearSpeakers] every speaker this app loaded is emptied first (in parallel, each bounded, see
     * [RendererRegistry.removeAll]). Clear only on an explicit service Stop: a Wi-Fi rebind (the bridge comes
     * straight back), a start that failed, a goodbye and a stale speaker leave the speaker alone.
     *
     * Switching the bridge off ends playback. With [stopSpeakers] every speaker that is playing gets a Stop and keeps
     * its track loaded; only [clearSpeakers] (the setting to empty speakers when a cast ends) also empties it. Neither flag:
     * the speakers are not touched (the reload-config restart, where the bridge comes straight back).
     */
    suspend fun stop(clearSpeakers: Boolean = false, stopSpeakers: Boolean = false) {
        if (!stopped.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            step("handlers") { handlerJob.cancelAndJoin() }
            step("receivers") { registry?.removeAll(clearSpeakers, stopSpeakers) }
            step("ssdp-client") { withContext(Dispatchers.IO) { ssdp?.stop() } }
            step("ssdp-responder") { withContext(Dispatchers.IO) { responder?.stop() } }
            step("media") { withContext(Dispatchers.IO) { mediaServer?.stop() } }
            _castCount.value = BridgeCount(0, 0)
            _visibleCastCount.value = BridgeCount(0, 0)
            _speakers.value = emptyList()
            synchronized(searchStateLock) {
                activeSearchWindows = 0
                _searching.value = false
            }
            manual.clearReplies()
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

/** A notice (alive or byebye) that cannot leave while the network is known to be down is expected, not a bridge-error. */
internal fun isExpectedOffline(where: String, networkDown: Boolean): Boolean = where == "notify" && networkDown
