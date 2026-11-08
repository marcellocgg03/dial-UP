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
import dev.t2u.media.CacheInfo
import dev.t2u.media.FileBuilder
import dev.t2u.media.MediaRequest
import dev.t2u.media.MediaServer
import dev.t2u.media.PipeLauncher
import dev.t2u.media.SourceDownloader
import dev.t2u.media.VideoResolver
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.ListenSpec
import dev.t2u.upnp.ProbeResult
import dev.t2u.upnp.RENDERER_ST
import dev.t2u.upnp.Ssdp
import dev.t2u.upnp.SsdpClientEvents
import dev.t2u.upnp.SsdpConstants
import dev.t2u.upnp.SsdpDevice
import dev.t2u.upnp.SsdpEvents
import dev.t2u.upnp.SsdpMessage
import dev.t2u.upnp.SsdpResponder
import dev.t2u.upnp.fetchDescription
import dev.t2u.upnp.parseIpv4Literal
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
import kotlinx.coroutines.coroutineScope
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
import java.net.URI
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
 * plan 03-11 on Android) is what the file format mp4 downloads their source with; without it, or without [launcher]
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
    /** Wall clock in ms: it keeps counting while the CPU sleeps, unlike delay and nanoTime. Tests inject one. */
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
    /**
     * GUI-08: whether a cast that ends empties the speaker, read at clear time so the setting applies live (D-09). Null
     * means the fixed [Config.clearOnCastEnd] decides; the app passes a supplier over its settings.
     */
    val clearOnCastEnd: (() -> Boolean)? = null,
    /**
     * GUI-08: the cache cap in MiB, read by the file cache at every prune so a size changed in the app applies without a
     * restart (D-09). Null, or a supplier that throws, means [Config.cacheMb] decides.
     */
    val cacheMb: (() -> Int)? = null,
    /**
     * GUI-03: the speakers the author hid. Null means nothing is ever hidden (and [Bridge.hide] does nothing); the app passes
     * its process-level store, which also lives while the bridge is off.
     */
    val hidden: HiddenSpeakers? = null,
    /** GUI-04: the UDP port a speaker added by address is searched at. 1900 on a real network; tests point it at the fake speaker. */
    val manualSsdpPort: Int = MANUAL_SSDP_PORT,
    /** GUI-04: how long [Bridge.addManual] waits for the speaker's answer and its description. */
    val manualAnswerTimeoutMs: Long = MANUAL_ANSWER_TIMEOUT_MS,
    /**
     * GUI-04, D-06: the speakers added by address. A successful [Bridge.addManual] saves the speaker here and [Bridge.start]
     * probes every saved entry, so its card returns at every start. Null saves nothing and probes nothing.
     */
    val manual: ManualSpeakers? = null,
    /**
     * GUI-04, D-07: how often every saved manual speaker is asked again. A speaker that missed two rounds in a row is taken out of
     * the registry (no longer a cast target) and comes back when it answers. Tests shorten it.
     */
    val manualProbeIntervalMs: Long = MANUAL_PROBE_INTERVAL_MS,
    /**
     * GUI-04, D-06, plan 07-17: where [Bridge.addManual] fetches the device description of the typed address when the unicast
     * search got no reply (a Sonos speaker ignores it). The argument is the typed IPv4 literal; the result must keep that host
     * ([descriptionUrlAllowed]) or nothing is requested. Tests point it at the fake speaker.
     */
    val manualDescriptionUrl: (String) -> String = ::sonosDescriptionUrl,
    /** Plan 07-17: how long [Bridge.addManual] waits for a reply to the unicast search before it falls back to the description. */
    val manualSsdpGraceMs: Long = MANUAL_SSDP_GRACE_MS,
    /**
     * GUI rename (261101-dcg): the names the author gave their renderers, by UDN. Process-level in the app, so it also lives
     * while the bridge is off. Null renames nothing ([Bridge.rename] answers false).
     */
    val names: RendererNames? = null,
    /**
     * WR-05: the waits between the attempts to rebind the media server on a new address. A busy address is tried again after
     * each delay (so [Bridge.rebindNetwork] makes up to one more attempt than there are delays); an empty list tries once.
     */
    val mediaRebindBackoffMs: List<Long> = MEDIA_REBIND_BACKOFF_MS,
)

/** One denial per source per minute in the log, so a scanner on the LAN cannot flood it. */
private const val DROP_LOG_WINDOW_MS = 60_000L

/**
 * D-06: how often the bridge re-announces (LAN mode), probes its own SSDP listener and searches for speakers. Five minutes
 * is six times inside the advertised max-age of 1800 s (SsdpMessage CACHE-CONTROL); there is no jitter because one host
 * sends it.
 */
const val HOUSEKEEPING_INTERVAL_MS = 300_000L

/** SSDP M-SEARCH advertises MX=3, so speakers may defer their replies for up to three seconds. */
const val SSDP_SEARCH_REPLY_WINDOW_MS = 3_000L

/**
 * WR-05: the waits between the media-server rebind attempts after a Wi-Fi move. The old Wi-Fi address can still be held for a
 * second or two (TIME_WAIT, a socket the system has not released), so a single failed bind is retried before it is given up;
 * a server that is still down after the last wait is rebound by the next housekeeping pass.
 */
val MEDIA_REBIND_BACKOFF_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L)

/** How many sources the per-address reply record holds (GUI-04, T-07-32); the oldest goes when a new source arrives at the cap. */
private const val MANUAL_REPLY_CAP = 64

/** How often [Bridge.addManual] looks for the answer. */
private const val MANUAL_POLL_MS = 100L

/** Plan 07-17: the shortest time the description fetch of an add is given, whatever is left of the probe budget. */
private const val MANUAL_DESCRIBE_MIN_MS = 1_000L

/** Plan 07-18: the timeout of one saved-speaker liveness fetch (T-07-50). */
private const val MANUAL_LIVENESS_FETCH_MS = 3_000L

/** A receiver younger than this is kept in the move list even while it is still "stopped" (its start has not run yet). */
private const val TRACK_GRACE_MS = 120_000L

/** How long a housekeeping run waits for its own listener to hear the probe datagram. */
private const val PROBE_TIMEOUT_MS = 2000L

/**
 * D-9: a device description's UDN (`uuid:RINCON_..._MR` on a real Sonos, upper case) in the form `udnFromUsn` gives a USN's UDN,
 * which is the key the registry, replySources, hidden.json and manual.json all use: the `uuid:` prefix removed in any letter
 * case, trimmed and lower-cased.
 */
internal fun descriptionUdn(raw: String): String {
    val trimmed = raw.trim()
    val bare = if (trimmed.startsWith("uuid:", ignoreCase = true)) trimmed.substring("uuid:".length) else trimmed
    return bare.trim().lowercase()
}

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

    /**
     * Serialises everything that changes the sockets: the probe and repair step of housekeeping, [networkLost] and
     * [rebindNetwork]. A housekeeping pass holds it only around that step, never through its search and retries, so a
     * network drop is acknowledged at once (review WR-06).
     */
    private val netLock = Mutex()

    /** Serialises whole housekeeping passes, so two triggers never run one after the other's half-finished state. */
    private val housekeepingLock = Mutex()

    /**
     * Wall-clock time the last run that completed started; null before the first. A run that failed or was cancelled stamps
     * nothing, so the next trigger runs instead of being skipped as recent (WR-06). Guards against a timer tick and an alarm
     * tick doubling up.
     * It is the wall clock (not nanoTime) because sleep time must count; a clock that moved backwards counts as "not recent".
     */
    @Volatile private var lastHousekeepingAt: Long? = null

    /**
     * What the receivers advertised, by uuid. The bridge owns this list so a responder built later (a housekeeping repair
     * after a failed rebind step or a deaf responder) has something to serve. A rebind does not copy it: the receivers
     * re-advertise their moved locations. A repair copies it onto the new responder.
     */
    private val advertisedDevices = ConcurrentHashMap<String, SsdpDevice>()

    // Evidence for the device session (plan 05-18, W1): did anything reach the app after the network came back? M-SEARCH
    // datagrams (the own liveness probe excluded) and DIAL HTTP requests counted since the last housekeeping event that took
    // them, and when the last of each arrived. Counts and times only: no address, name or payload.
    private val msearchRx = java.util.concurrent.atomic.AtomicLong(0)
    private val dialRx = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile private var lastMsearchAt: Long? = null

    @Volatile private var lastDialAt: Long? = null

    private val dropLogged = ConcurrentHashMap<String, Long>()

    /** The last search reply from each source address: its UDN and when it came. [addManual] reads it; capped, cleared on stop. */
    private class SourceReply(val udn: String, val at: Long)

    private val replySources = ConcurrentHashMap<String, SourceReply>()

    /**
     * Records a reply from [address]. A full table evicts its oldest entry whose address is not a saved manual address (plan
     * 07-18, WR-03: a chatty LAN must not make a saved speaker look silent); when every entry is a saved address nothing is
     * evicted and the table stays at most [MAX_MANUAL] over the cap. Internal so a test can drive it.
     */
    internal fun noteReply(address: String, udn: String) {
        if (replySources.size >= MANUAL_REPLY_CAP && !replySources.containsKey(address)) {
            val saved = deps.manual?.entries?.value.orEmpty().map { it.ip }.toSet()
            replySources.entries.filter { it.key !in saved }.minByOrNull { it.value.at }?.let { replySources.remove(it.key) }
        }
        replySources[address] = SourceReply(udn, System.currentTimeMillis())
    }

    /** When [address] last replied, for tests; null when the table holds nothing for it. */
    internal fun replySourceAt(address: String): Long? = replySources[address]?.at

    private val _castCount = MutableStateFlow(BridgeCount(0, 0))

    /** Derived from the renderers' actual player status; used to keep the Wi-Fi-loss grace period active. */
    val castCount: StateFlow<BridgeCount> = _castCount.asStateFlow()

    private val _visibleCastCount = MutableStateFlow(BridgeCount(0, 0))

    /** Derived from card-visible state; a brief network outage retains playback state while polling is paused. */
    val visibleCastCount: StateFlow<BridgeCount> = _visibleCastCount.asStateFlow()

    private val _speakers = MutableStateFlow<List<SpeakerUi>>(emptyList())

    /**
     * One entry per speaker the bridge can cast to (a device without AVTransport or one the name filter excludes is never in
     * the registry), each following its [Renderer.ui]. Empty until the first speaker is up and again after [stop] (plan 07-03).
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

    /** The running renderer with this UDN, for the card controls of the status UI; null when there is none (plan 07-03). */
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
     * GUI-08: deletes every finished file in the cache that no stream pins (T-07-21) and returns how many went; 0 when this
     * bridge has no file builder. A `cache` event records the count.
     */
    fun clearCache(): Int {
        val cleared = mediaServer?.clearCache() ?: 0
        emit("cache", "cleared" to cleared)
        return cleared
    }

    /** GUI-08: files, bytes and cap of the cache now; null when this bridge has no file builder. */
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
     * GUI-03, D-08: drops the speaker [udn] as a DIAL target at once and keeps it out until [unhide]. The speaker joins the
     * hidden set first, so every guard (alive, retry, a setup in flight) refuses it before the receiver goes; then a playing
     * speaker is stopped (and emptied only when clear-when-cast-ends is on) and its receiver stopped, which sends
     * ssdp:byebye, closes its DIAL port and ends the lounge session. The other speakers are not touched. A saved renderer that
     * does not answer (no running speaker, but an entry in manual.json) is hidden too (PD-3): there is nothing to stop, its greyed
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
     * GUI rename (261101-dcg, PD-4, PD-5): [name] is the author's name for the renderer [udn]; null goes back to the name the
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
     * GUI-03: brings the hidden speaker [udn] back. It leaves the hidden set, then the bridge searches for speakers at once so
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
     * GUI-14: one MediaRenderer search on demand, from the Refresh button or the app coming to the foreground. [trigger] names
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
        return searchNow(trigger)
    }

    /**
     * GUI-04, D-06: adds the speaker at [ip] by asking it directly. One unicast M-SEARCH goes to [ip] on
     * [BridgeDeps.manualSsdpPort]; the reply reaches the registry like any search reply (so its guards apply: the LOCATION host
     * must equal the sender and be private LAN, excluded servers and hidden speakers are refused), and the card exists only
     * when that setup succeeds. Nothing is sent unless [manualTargetAllowed] accepts [ip] (T-07-29). Only a reply whose source
     * address is [ip] counts (T-07-30). A Sonos speaker ignores a unicast M-SEARCH, so when no reply came within
     * [BridgeDeps.manualSsdpGraceMs] the bridge fetches the device description from the typed address (plan 07-17: one GET to
     * [BridgeDeps.manualDescriptionUrl], host must equal [ip]) and hands it to the registry as synthetic alive headers, so the
     * same guards apply. Waits at most [BridgeDeps.manualAnswerTimeoutMs] in total. A speaker the bridge already lists is
     * reported at once. Added and AlreadyListed results are saved (plan 07-13), every other result saves nothing.
     */
    suspend fun addManual(ip: InetAddress): ManualAddResult {
        val outcome = probeManual(ip)
        saveManual(ip, outcome.result)
        emit("manual-add", "ip" to ip.hostAddress, "result" to outcome.result.javaClass.simpleName, "via" to outcome.via)
        return outcome.result
    }

    /** What [probeManual] came to and which probe path produced the answer: `ssdp` (unicast search) or `http` (description). */
    private class ProbeOutcome(val result: ManualAddResult, val via: String)

    /** D-06: a speaker that was added or was already listed is remembered; every other outcome leaves nothing behind. */
    private suspend fun saveManual(ip: InetAddress, result: ManualAddResult) {
        val store = deps.manual ?: return
        val udn = when (result) {
            is ManualAddResult.Added -> result.udn
            is ManualAddResult.AlreadyListed -> result.udn
            else -> return
        }
        val running = registry?.renderers()?.firstOrNull { it.udn == udn } ?: return
        store.add(ManualSpeaker(ip.hostAddress.orEmpty(), udn, running.name ?: udn, running.location))
    }

    /**
     * D-06: asks every saved speaker directly, as the first add did. Each address passes [parseIpv4Literal] and
     * [manualTargetAllowed] again (T-07-31), so a damaged or edited file cannot aim the bridge at a public address. The replies
     * reach the registry like any search reply; a speaker that does not answer simply has no card until it does.
     */
    private fun probeSavedSpeakers(client: Ssdp, reg: RendererRegistry) {
        val saved = deps.manual?.entries?.value.orEmpty()
        if (saved.isEmpty()) return
        handlerScope.launch {
            // plan 07-18: besides the unicast search, each saved speaker's description is fetched, because a Sonos ignores the search
            for (entry in saved.take(MAX_MANUAL)) {
                launch { sendManualProbe(client, entry) }
                launch { describeSaved(entry, reg) }
            }
        }
    }

    /** One unicast search to a saved speaker, after the same two address checks as the first add (T-07-31). Never throws. */
    private suspend fun sendManualProbe(client: Ssdp, entry: ManualSpeaker) {
        val ip = parseIpv4Literal(entry.ip)
        if (ip == null || !manualTargetAllowed(ip)) {
            emit("manual-probe", "ip" to entry.ip, "result" to "Refused")
            return
        }
        try {
            withContext(Dispatchers.IO) { client.searchAt(InetSocketAddress(ip, deps.manualSsdpPort), RENDERER_ST, 1) }
            emit("manual-probe", "ip" to entry.ip, "result" to "Sent")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failure("manual-probe", t)
        }
    }

    /**
     * GUI-04, D-07: once per [BridgeDeps.manualProbeIntervalMs] decides for every saved speaker whether it
     * answered since the previous round (any search reply or NOTIFY from its address counts, see [noteReply]); a speaker that
     * missed two rounds in a row is removed from the registry, so it stops being a cast target, and a later answer sets it up
     * again through the registry's ordinary alive path. Then every saved speaker is asked again, which is also what keeps a
     * speaker that only answers unicast from being pruned after 45 minutes. A round is skipped while the network is down
     * (nothing could have answered). The client is read from the field, so a rebind's replacement is followed. [since] is when
     * the start-up probe went out. At most [MAX_MANUAL] datagrams per round (T-07-33).
     *
     * Quick 261102-dmv, D-11: a saved speaker whose UDN is running in the registry at another address than the saved one has
     * moved (new subnet or DHCP lease) and discovery already found it there. Its saved address is replaced by the live one
     * (`manual-moved`, manual.json rewritten, keyed by UDN) at the start of the round, and the old address gets no verdict,
     * because it would never answer and would drop the live renderer. When the live address is not a private LAN address or the
     * store refuses it, nothing is adopted but the entry is still not judged. A UDN that is not running keeps the old
     * behaviour: two missed rounds, then `manual-offline`.
     */
    private fun watchManualSpeakers(reg: RendererRegistry, since: Long) {
        val store = deps.manual ?: return
        handlerScope.launch {
            val liveness = HashMap<String, ManualLiveness>()
            val offline = HashSet<String>()
            var last = since
            while (true) {
                delay(deps.manualProbeIntervalMs)
                if (networkDown) continue
                // D-11: entries whose renderer is running at another address are moved to it (or at least not judged) first
                val movedAway = adoptMovedSpeakers(reg, store)
                val saved = store.entries.value
                val udns = saved.map { it.udn }.toSet()
                liveness.keys.retainAll(udns)
                offline.retainAll(udns)
                for (udn in movedAway) liveness.remove(udn)
                // plan 07-18: the description fetches finish before the verdicts, so an HTTP answer in this round counts
                coroutineScope { for (entry in saved.take(MAX_MANUAL)) launch { describeSaved(entry, reg) } }
                // taken after the fetches: an answer from this round must not count again in the next one
                val roundAt = System.currentTimeMillis()
                for (entry in saved) {
                    if (entry.udn in movedAway) continue
                    val replied = (replySources[entry.ip]?.at ?: Long.MIN_VALUE) >= last
                    when (liveness.getOrPut(entry.udn) { ManualLiveness() }.round(replied)) {
                        ManualVerdict.ONLINE -> if (offline.remove(entry.udn)) {
                            emit("manual-online", "udn" to entry.udn, "ip" to entry.ip)
                        }
                        ManualVerdict.MISSED -> {}
                        ManualVerdict.OFFLINE -> {
                            if (reg.isRunning(entry.udn)) reg.remove(entry.udn, "offline", "no answer to 2 probes at ${entry.ip}")
                            if (offline.add(entry.udn)) emit("manual-offline", "udn" to entry.udn, "ip" to entry.ip)
                        }
                    }
                }
                last = roundAt
                val client = ssdp ?: continue
                for (entry in saved) sendManualProbe(client, entry)
            }
        }
    }

    /**
     * D-11: for every saved speaker whose UDN runs in [reg] at an address other than the saved one, saves the live address
     * (and the live description URL when it belongs to that address, else the default one) in place of the stale one and logs
     * `manual-moved`. Returns the UDNs of all such speakers, adopted or not, so the caller skips their liveness verdict: the
     * saved address is wrong, so its silence says nothing about the renderer. A live address that fails [manualTargetAllowed]
     * or a store that refuses the entry leaves the saved entry as it was.
     */
    private suspend fun adoptMovedSpeakers(reg: RendererRegistry, store: ManualSpeakers): Set<String> {
        val moved = HashSet<String>()
        val running = reg.renderers()
        for (entry in store.entries.value) {
            val live = running.firstOrNull { it.udn == entry.udn } ?: continue
            if (live.address == entry.ip) continue
            moved.add(entry.udn)
            val ip = parseIpv4Literal(live.address)
            if (ip == null || !manualTargetAllowed(ip)) continue
            val location = live.location.takeIf { descriptionUrlAllowed(it, ip) } ?: deps.manualDescriptionUrl(live.address)
            if (!store.add(entry.copy(ip = live.address, location = location))) continue
            emit("manual-moved", "udn" to entry.udn, "from" to entry.ip, "to" to live.address)
        }
        return moved
    }

    /**
     * GUI-04, D-07: forgets the saved manual speaker [udn]: it leaves manual.json and the list, and when it runs it is taken out
     * of the registry (byebye, DIAL server closed); its hidden and renamed marks go too (quick 261101-oib). A speaker that multicast discovery also finds comes back at the next search as
     * an ordinary discovered speaker without the manual mark; hiding it is what keeps it away. False when it was not saved.
     * The app calls this from Delete on the card of a renderer saved by address (quick 261101-oib); BridgeManualTest covers it.
     */
    suspend fun removeManual(udn: String): Boolean {
        val store = deps.manual ?: return false
        if (!store.remove(udn)) return false
        // quick 261101-oib: nothing of the renderer stays behind; a hidden one is un-hidden, its user name is dropped
        deps.hidden?.remove(udn)
        deps.names?.remove(udn)
        if (started.get() && !stopped.get()) registry?.remove(udn, "manual-removed", null)
        emit("manual-removed", "udn" to udn)
        return true
    }

    /**
     * The probe behind [addManual]: SSDP first (one unicast search, the reply reaches the registry through [clientEvents]), then,
     * when nothing replied within [BridgeDeps.manualSsdpGraceMs], the HTTP description of the typed address ([describeManual]).
     * Both paths end in the same loop over [replySources] and the registry, so Hidden, Added and NotUsable are decided in one place.
     */
    private suspend fun probeManual(ip: InetAddress): ProbeOutcome {
        if (!manualTargetAllowed(ip)) return ProbeOutcome(ManualAddResult.Refused, "ssdp")
        val reg = registry
        val client = ssdp
        if (!started.get() || stopped.get() || reg == null || client == null) {
            return ProbeOutcome(ManualAddResult.NotUsable("The bridge is not running"), "ssdp")
        }
        val host = ip.hostAddress.orEmpty()
        val listedBefore = reg.renderers().firstOrNull { it.address == host }
        if (listedBefore != null && deps.hidden?.contains(listedBefore.udn) != true) {
            // review CR-02: a speaker that already has a card needs no probe; it also may not answer a unicast search at all
            return ProbeOutcome(ManualAddResult.AlreadyListed(listedBefore.udn, listedBefore.name ?: listedBefore.udn), "listed")
        }
        // an answer from before this probe must not count
        replySources.remove(host)
        try {
            withContext(Dispatchers.IO) { client.searchAt(InetSocketAddress(ip, deps.manualSsdpPort), RENDERER_ST, 1) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failure("manual-add-search", t)
        }
        val startedAt = System.nanoTime()
        val deadline = startedAt + deps.manualAnswerTimeoutMs * 1_000_000L
        val graceEnd = startedAt + minOf(deps.manualSsdpGraceMs, deps.manualAnswerTimeoutMs) * 1_000_000L
        var via = "ssdp"
        var fallbackTried = false
        while (true) {
            val reply = replySources[host]
            if (reply != null) {
                if (deps.hidden?.contains(reply.udn) == true) return ProbeOutcome(ManualAddResult.Hidden(reply.udn), via)
                val running = reg.renderers().firstOrNull { it.udn == reply.udn }
                if (running != null) {
                    val name = running.name ?: running.udn
                    return ProbeOutcome(ManualAddResult.Added(running.udn, name), via)
                }
            }
            if (stopped.get()) return ProbeOutcome(ManualAddResult.NotUsable("The bridge stopped"), via)
            if (!fallbackTried && reply == null && System.nanoTime() >= graceEnd) {
                fallbackTried = true
                via = "http"
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                describeManual(reg, ip, remainingMs.coerceAtLeast(MANUAL_DESCRIBE_MIN_MS))?.let { return ProbeOutcome(it, via) }
                continue
            }
            if (System.nanoTime() >= deadline) break
            delay(MANUAL_POLL_MS)
        }
        val result = if (replySources.containsKey(host)) {
            ManualAddResult.NotUsable(
                "It answered, but it is not a renderer that can play audio here: no AVTransport, filtered out by name, " +
                    "or its description names another address",
            )
        } else {
            ManualAddResult.NoAnswer
        }
        return ProbeOutcome(result, via)
    }

    /**
     * Plan 07-17, D-06: the HTTP fallback for a speaker that ignores a unicast search (a Sonos speaker). Fetches the device
     * description at [BridgeDeps.manualDescriptionUrl] for [ip], only when [descriptionUrlAllowed] accepts the URL (T-07-45), and
     * feeds it to the registry as the alive headers an SSDP reply would have carried: `LOCATION` is the URL just fetched, so the
     * registry's host-equals-sender, private LAN, hidden and excluded-server guards apply unchanged (T-07-46). The `SERVER` header
     * is left empty because [fetchDescription] does not expose response headers; a device that is not a MediaRenderer is stopped
     * here or by the registry's setup. Returns a final result when the description decides it (not a speaker), otherwise null and
     * the caller's loop reads the registry. Never throws except for cancellation.
     */
    private suspend fun describeManual(reg: RendererRegistry, ip: InetAddress, timeoutMs: Long): ManualAddResult? {
        val host = ip.hostAddress.orEmpty()
        val url = deps.manualDescriptionUrl(host)
        if (!descriptionUrlAllowed(url, ip)) {
            emit("manual-add-describe", "ip" to host, "result" to "Refused")
            return null
        }
        val desc = try {
            fetchDescription(deps.http, url, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // it served a document, but not a device description
            emit("manual-add-describe", "ip" to host, "result" to "NotADevice")
            return ManualAddResult.NotUsable("It answered, but its description is not a UPnP device description")
        } catch (t: Throwable) {
            failure("manual-add-describe", t)
            return null
        }
        val udn = descriptionUdn(desc.udn)
        if (udn.isEmpty() || !desc.services.containsKey("urn:upnp-org:serviceId:AVTransport")) {
            emit("manual-add-describe", "ip" to host, "result" to "NotARenderer")
            return ManualAddResult.NotUsable("It answered, but it is not a renderer that can play audio here: no AVTransport")
        }
        feedDescription(reg, ip, url, udn, "manual-add-describe")
        return null
    }

    /**
     * Hands a fetched description to the registry as the alive headers an SSDP reply would have carried (`LOCATION` is the URL
     * just fetched, so the registry's own guards apply) and records the answer in [replySources]. Shared by the add-by-IP
     * fallback ([describeManual]) and the saved-speaker liveness fetch ([describeSaved]). Never throws except for cancellation.
     */
    private suspend fun feedDescription(reg: RendererRegistry, ip: InetAddress, url: String, udn: String, eventKind: String) {
        val host = ip.hostAddress.orEmpty()
        val headers = mapOf(
            "ST" to RENDERER_ST,
            "USN" to "uuid:$udn::$RENDERER_ST",
            "LOCATION" to url,
            "CACHECONTROL" to "max-age=1800",
            "SERVER" to "",
        )
        val port = URI(url).port.let { if (it == -1) 80 else it }
        udnFromUsn(headers["USN"])?.let { noteReply(host, it) }
        try {
            reg.onAlive(headers, InetSocketAddress(ip, port))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failure(eventKind, t)
        }
    }

    /**
     * Plan 07-18, WR-03, D-07: the liveness signal for a saved manual speaker that never answers a unicast search (a Sonos
     * speaker). Fetches the speaker's description (the saved `location` when [descriptionUrlAllowed] holds for the re-validated
     * saved address, otherwise [BridgeDeps.manualDescriptionUrl], T-07-48) and counts it as an answer only when the document's UDN
     * equals the saved one, letter case ignored because a Sonos serves it in upper case and the saved one is lower case
     * (T-07-49, D-9); the registry then refreshes the running renderer or sets it up. A failure is one `manual-probe` event
     * and means "no answer". Never throws except for cancellation.
     */
    private suspend fun describeSaved(entry: ManualSpeaker, reg: RendererRegistry): Boolean {
        val ip = parseIpv4Literal(entry.ip)
        if (ip == null || !manualTargetAllowed(ip)) {
            emit("manual-probe", "ip" to entry.ip, "result" to "Refused", "via" to "http")
            return false
        }
        val url = entry.location.takeIf { descriptionUrlAllowed(it, ip) } ?: deps.manualDescriptionUrl(entry.ip)
        if (!descriptionUrlAllowed(url, ip)) {
            emit("manual-probe", "ip" to entry.ip, "result" to "Refused", "via" to "http")
            return false
        }
        val desc = try {
            fetchDescription(deps.http, url, MANUAL_LIVENESS_FETCH_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            emit("manual-probe", "ip" to entry.ip, "result" to t.javaClass.simpleName, "via" to "http")
            return false
        }
        val udn = descriptionUdn(desc.udn)
        if (!udn.equals(entry.udn, ignoreCase = true)) {
            emit("manual-probe", "ip" to entry.ip, "result" to "OtherDevice", "via" to "http")
            return false
        }
        feedDescription(reg, ip, url, udn, "manual-probe")
        emit("manual-probe", "ip" to entry.ip, "result" to "Answered", "via" to "http")
        return true
    }

    /**
     * Blocking form of [startSuspending], for tests and callers that are not in a coroutine. The app calls [startSuspending]
     * (WR-06): a blocking start inside a coroutine would hold its dispatcher thread through the bind and the engine start.
     */
    fun start() {
        runBlocking { startSuspending() }
    }

    /**
     * Starts the media server, the SSDP responder and client and the discovery schedules. The body runs on [Dispatchers.IO]
     * because the bind and the engine start block. Throws after undoing a failed start, and a start that threw (a rejected
     * config included) leaves the bridge not running (WR-06).
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

    private fun startBody() {
        val base = deps.config
        // mediaPort 0 means "any free port" (tests); the Node range check applies to the real port below
        base.copy(mediaPort = base.mediaPort.coerceAtLeast(1)).validate()
        val wifi = net.wifiIp.hostAddress.orEmpty()
        // File builds (mp4) need ffmpeg, a place to write and a way to download the source. The builder keeps its
        // finished files in <bufferDir>/files across starts and prunes them to config.json `cacheMb` (D-02, D-12); it
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
        media.start()
        mediaServer = media
        val config = base.copy(mediaPort = media.resolvedPort())
        effective = config

        val policy = DialAccessPolicy(deps.accessMode, phoneAddresses = { setOf(net.wifiIp.hostAddress.orEmpty(), "127.0.0.1", "::1") })
        accessPolicy = policy

        responder = buildResponder(net, policy)

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
                    onDialRequest = { request ->
                        dialRx.incrementAndGet()
                        lastDialAt = deps.clock()
                        deps.onDialRequest(request)
                    },
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
                    clearOnCastEnd = deps.clearOnCastEnd ?: { config.clearOnCastEnd },
                    userName = { deps.names?.get(it) },
                    // one handler per renderer: each speaker keeps its own visitorData and recently-asked ring
                    playlistRequestHandlerFactory = { InnerTubePlaylistRequestHandler(deps.http, deps.innerTubeEndpoints, deps.events) },
                ),
            )
        }
        val reg = RendererRegistry(config, ports, factory, deps.events, hidden = { deps.hidden?.contains(it) == true })
        registry = reg

        val client = Ssdp(listOf(net.wifiIp), clientEvents(reg), net.ssdpSearchTarget)
        client.start()
        ssdp = client
        val probesSentAt = System.currentTimeMillis()
        probeSavedSpeakers(client, reg)
        watchManualSpeakers(reg, probesSentAt)

        handlerScope.launch { countFlow(reg).collect { _castCount.value = it } }
        handlerScope.launch { visibleCountFlow(reg).collect { _visibleCastCount.value = it } }
        handlerScope.launch { speakersFlow(reg).collect { _speakers.value = it } }
        // reads the field, so a later replacement of the client is followed (plan 05-07)
        reg.launchSchedules(handlerScope) { multicastSearch("schedule", markRegistrySearched = false) }
        handlerScope.launch {
            while (true) {
                delay(deps.housekeepingIntervalMs)
                housekeeping("timer")
            }
        }
        emit("bridge", "event" to "started", "wifi" to wifi, "mediaPort" to config.mediaPort)
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
            udnFromUsn(headers["USN"])?.let { noteReply(src.address.hostAddress.orEmpty(), it) }
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
                msearchRx.incrementAndGet()
                lastMsearchAt = deps.clock()
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun visibleCountFlow(reg: RendererRegistry): Flow<BridgeCount> = reg.renderersFlow.flatMapLatest { list ->
        val renderers = list.filterIsInstance<Renderer>()
        if (renderers.isEmpty()) {
            flowOf(BridgeCount(0, 0))
        } else {
            combine(renderers.map { it.ui }) { speakers ->
                BridgeCount(
                    speakers.count { it.status == PlayerStatus.LOADING || it.status == PlayerStatus.PLAYING || it.status == PlayerStatus.PAUSED },
                    speakers.size,
                )
            }
        }
    }.distinctUntilChanged()

    /**
     * The status UI's list: every renderer's [Renderer.ui] combined, rebuilt whenever a speaker joins or leaves the registry.
     * With a manual store (GUI-04, D-07) a running speaker whose UDN is saved is marked manual, and a saved speaker that is not
     * running (it does not answer) is listed after them as an offline card: idle, no track, controls off. A hidden saved speaker
     * is not listed (PD-3): the registry refuses it, and it must not come back as a greyed card.
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
     * Returns true when the run happened; false when the bridge is not running or the run failed (also logged). Only a run that
     * completed counts as done: a failed one does not make the next trigger "recent" (WR-06).
     *
     * It skips (touching nothing) while the network is marked down ([networkLost]) and while the system reports no Wi-Fi
     * ([BridgeDeps.currentNetwork]; this still matters when [networkLost] has not been called yet): probing and reopening
     * sockets during an outage the bridge was not told about is what broke Session E step 5.
     */
    suspend fun housekeeping(trigger: String): Boolean {
        if (!started.get() || stopped.get()) return false
        if (networkDown) {
            emit("housekeeping", "trigger" to trigger, "skipped" to "network-down", *inboundEvidence())
            return false
        }
        if (!wifiPresent()) {
            emit("housekeeping", "trigger" to trigger, "skipped" to "no-wifi", *inboundEvidence())
            return false
        }
        return try {
            // a timer tick and an alarm tick (plan 05-11) must not double up; the adb trigger is a person asking
            // the recent check sits inside the lock so two simultaneous triggers run once (WR-06)
            housekeepingLock.withLock {
                val now = deps.clock()
                val last = lastHousekeepingAt
                // a negative elapsed time means the wall clock moved back: that is not "recent" (review WR-07)
                val elapsed = if (last != null) now - last else null
                if (trigger != "adb" && elapsed != null && elapsed >= 0 && elapsed < deps.housekeepingIntervalMs / 2) {
                    emit("housekeeping", "trigger" to trigger, "skipped" to "recent", "ranAgoMs" to elapsed)
                    false
                } else {
                    val ran = runHousekeeping(trigger)
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

    /**
     * The inbound evidence fields for a housekeeping event: M-SEARCH and DIAL request counts since the previous call (which
     * resets them) and how long ago the last of each arrived (null: never since the bridge started).
     */
    private fun inboundEvidence(): Array<Pair<String, Any?>> {
        val now = deps.clock()
        return arrayOf(
            "msearchRx" to msearchRx.getAndSet(0),
            "dialRx" to dialRx.getAndSet(0),
            "lastMsearchAgoMs" to lastMsearchAt?.let { now - it },
            "lastDialAgoMs" to lastDialAt?.let { now - it },
        )
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
        val t0 = System.nanoTime()
        // refresh the speakers too: the in-process discovery timer may have slept through the last interval
        var clientFailed = false
        val searched = try {
            // the field is read at the moment of use; a client a rebind just stopped answers a search with nothing (no-op)
            val client = ssdp
            if (client == null) {
                clientFailed = true
                false
            } else {
                val sent = multicastSearch("housekeeping", client)
                if (!sent) clientFailed = true
                sent
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failure("housekeeping-search", t)
            clientFailed = true
            false
        }
        // the registry defers its first prune after a long sleep until a search ran (plan 05-04)
        if (searched) registry?.searched()
        if (accessPolicy?.announceAllowed() == true) {
            withContext(Dispatchers.IO) { responder?.announce() }
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
        // the network lock is taken only around the probe and repair step; the system is asked first, outside the lock
        val repaired = if (!wifiPresent()) {
            emit("ssdp-probe", "skipped" to "no-wifi", "trigger" to trigger)
            0
        } else {
            netLock.withLock { probeAndRepair(trigger, clientFailed) }
        }
        emit(
            "housekeeping",
            "trigger" to trigger, "ran" to true, "searched" to searched, "retried" to retried, "repaired" to repaired,
            "ms" to (System.nanoTime() - t0) / 1_000_000,
            *inboundEvidence(),
        )
        return true
    }

    /**
     * Proves the responder's own listener still receives and repairs what reopening cannot heal (D-06, review WR-03). The
     * caller holds [netLock]. Order: probe; on failure reopen the sockets ([SsdpResponder.restartListeners]) and probe again;
     * when that fails too, or the responder is missing, rebuild it ([repairResponder]). A client that could not search is
     * rebuilt too ([repairClient]). At most one of each per pass. Returns how many repairs were attempted.
     */
    private suspend fun probeAndRepair(trigger: String, clientFailed: Boolean): Int {
        // the network may have gone down while this pass was waiting for the lock
        if (networkDown || !wifiPresent()) {
            emit("ssdp-probe", "skipped" to if (networkDown) "network-down" else "no-wifi", "trigger" to trigger)
            return 0
        }
        var repairs = 0
        var rebuild = false
        val resp = responder
        if (resp == null) {
            rebuild = true
        } else {
            val first = withContext(Dispatchers.IO) { resp.probe(PROBE_TIMEOUT_MS) }
            emitProbe(first, trigger)
            if (first != ProbeResult.OK) {
                val restarted = withContext(Dispatchers.IO) { resp.restartListeners() }
                val again = withContext(Dispatchers.IO) { resp.probe(PROBE_TIMEOUT_MS) }
                emitProbe(again, trigger, "restarted" to restarted)
                rebuild = again != ProbeResult.OK
            }
        }
        if (rebuild && repairResponder(trigger)) repairs++
        if (clientFailed && repairClient(trigger)) repairs++
        if (repairMedia(trigger)) repairs++
        if (repairDial(trigger)) repairs++
        return repairs
    }

    /** One `ssdp-probe` event: ok, any [extra] fields, the trigger, and (only on failure) the fixed reason code. */
    private fun emitProbe(result: ProbeResult, trigger: String, vararg extra: Pair<String, Any?>) {
        val fields = ArrayList<Pair<String, Any?>>()
        fields.add("ok" to (result == ProbeResult.OK))
        fields.addAll(extra)
        fields.add("trigger" to trigger)
        if (result != ProbeResult.OK) fields.add("reason" to result.wire)
        emit("ssdp-probe", *fields.toTypedArray())
    }

    /** True when the system says the phone's Wi-Fi address is not the one the bridge is bound to: that is the rebind's job. */
    private fun addressChanged(): Boolean {
        val current = try {
            deps.currentNetwork?.invoke()
        } catch (_: Exception) {
            null
        }
        return current != null && current.wifiIp != net.wifiIp
    }

    /**
     * Replaces a responder that reopening could not heal (or that a failed rebind step left missing) with a new one on the
     * bridge's own address, serving every device the receivers advertised. Announces again only when the access policy allows
     * it (never in phone-only mode, T-05-49). Skipped when the system address differs from the bridge's (T-05-50). The caller
     * holds [netLock]. Returns false when nothing was attempted.
     */
    private suspend fun repairResponder(trigger: String): Boolean {
        if (addressChanged()) {
            emit("ssdp-repair", "what" to "responder", "skipped" to "address-changed", "trigger" to trigger)
            return false
        }
        val policy = accessPolicy ?: return false
        val network = net
        step("responder-stop", "repair") {
            try {
                withContext(Dispatchers.IO) { responder?.stop() }
            } finally {
                responder = null
            }
        }
        var fresh: SsdpResponder? = null
        var error: String? = null
        try {
            val built = withContext(Dispatchers.IO) { buildResponder(network, policy) }
            fresh = built
            for (device in advertisedDevices.values) built.register(device)
            responder = built
            if (policy.announceAllowed()) withContext(Dispatchers.IO) { built.announce() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failure("repair:responder", t)
            error = t.javaClass.simpleName
            runCatching { fresh?.stop() }
            if (responder === fresh) responder = null
            fresh = null
        }
        val result = fresh?.let { f -> withContext(Dispatchers.IO) { f.probe(PROBE_TIMEOUT_MS) } }
        val fields = ArrayList<Pair<String, Any?>>()
        fields.add("what" to "responder")
        fields.add("ok" to (result == ProbeResult.OK))
        fields.add("trigger" to trigger)
        if (error != null) fields.add("error" to error)
        emit("ssdp-repair", *fields.toTypedArray())
        if (result != null) emitProbe(result, trigger, "repaired" to true)
        return true
    }

    /**
     * Replaces an SSDP client that has no search socket (a failed bind leaves one that looks alive and never searches) and
     * searches through the new one at once. Skipped when the system address differs from the bridge's. The caller holds
     * [netLock]. Returns false when nothing was attempted.
     */
    private suspend fun repairClient(trigger: String): Boolean {
        val reg = registry ?: return false
        if (addressChanged()) {
            emit("ssdp-repair", "what" to "client", "skipped" to "address-changed", "trigger" to trigger)
            return false
        }
        val network = net
        step("ssdp-client-stop", "repair") {
            try {
                withContext(Dispatchers.IO) { ssdp?.stop() }
            } finally {
                ssdp = null
            }
        }
        var ok = false
        var error: String? = null
        try {
            val fresh = Ssdp(listOf(network.wifiIp), clientEvents(reg), network.ssdpSearchTarget)
            withContext(Dispatchers.IO) { fresh.start() }
            if (fresh.isBound()) {
                ssdp = fresh
                ok = multicastSearch("repair", fresh)
            } else {
                withContext(Dispatchers.IO) { fresh.stop() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failure("repair:client", t)
            error = t.javaClass.simpleName
        }
        val fields = ArrayList<Pair<String, Any?>>()
        fields.add("what" to "client")
        fields.add("ok" to ok)
        fields.add("trigger" to trigger)
        if (error != null) fields.add("error" to error)
        emit("ssdp-repair", *fields.toTypedArray())
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
            for (tracked in receiverList.toList()) tracked.receiver.onNetworkLost()
            // polls pause on every renderer: what a broken stream does on the speaker is not mirrored to the phone (plan 05-08)
            for (r in registry?.renderers().orEmpty()) step("renderer", "network-lost") { r.onNetworkLost() }
        }
    }

    /**
     * Moves the running bridge to [newNet] in place (SVC-04): a new SSDP responder and client on the new
     * address, every running receiver's DIAL server moved by [YouTubeCastReceiver.rebindDial]. Renderers, players and lounge
     * sessions stay, so the phone's cast session survives, and nothing is cleared on any speaker. When the address is
     * unchanged, the media listener stays in place to avoid interrupting active streams with an unnecessary server restart.
     * Each step is isolated, so one failure leaves the rest moved and logged. Does nothing unless the bridge is running and is
     * only called on network events (D-06). It does not retry (D-05) with one exception: a changed-address media bind is tried
     * again after [BridgeDeps.mediaRebindBackoffMs] (WR-05), and a media server that is still down afterwards is rebound by
     * housekeeping ([repairMedia]).
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

            // Same-address returns need no listener restart; stopping its HTTP engine would break active stream connections.
            if (oldIp != newIp || mediaServer?.isListening() == false) {
                step("media", "rebind") { rebindMedia(newIp) }
            }
            // a stopped responder is never left installed: the field is cleared as soon as the old one is stopped, and only a
            // replacement that started is assigned (review WR-03); housekeeping rebuilds what a failed step leaves missing
            step("responder-stop", "rebind") {
                try {
                    withContext(Dispatchers.IO) { oldResponder?.stop() }
                } finally {
                    responder = null
                }
            }
            // the old listeners are closed before the new ones open, so a same-address move never fights itself for the socket
            net = newNet
            if (policy != null) {
                step("responder-start", "rebind") {
                    responder = withContext(Dispatchers.IO) { buildResponder(newNet, policy) }
                }
            }
            step("ssdp-client", "rebind") {
                try {
                    withContext(Dispatchers.IO) { oldClient?.stop() }
                } finally {
                    ssdp = null
                }
                if (reg != null) {
                    val fresh = Ssdp(listOf(newNet.wifiIp), clientEvents(reg), newNet.ssdpSearchTarget)
                    withContext(Dispatchers.IO) { fresh.start() }
                    // start() reports a failed bind through onError and does not throw: a deaf client is not installed
                    if (fresh.isBound()) ssdp = fresh else withContext(Dispatchers.IO) { fresh.stop() }
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
            for (tracked in receiverList.toList()) tracked.receiver.onNetworkReturned()
            networkDown = false
            step("search", "rebind") { multicastSearch("rebind") }
            emit("bridge", "event" to "rebound", "oldIp" to oldIp, "newIp" to newIp, "receivers" to moved)
        }
    }

    /**
     * WR-05: moves the media server to [host], trying again after each [BridgeDeps.mediaRebindBackoffMs] delay when the bind
     * fails. One `media-rebind` event per attempt. The last failure is rethrown, so [step] logs it as before. The caller holds
     * [netLock], which a retry holds for the length of the waits.
     */
    private suspend fun rebindMedia(host: String) {
        val media = mediaServer ?: return
        val waits = deps.mediaRebindBackoffMs
        var attempt = 0
        while (true) {
            attempt++
            try {
                withContext(Dispatchers.IO) { media.rebindHost(host) }
                emit("media-rebind", "attempt" to attempt, "ok" to true)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                emit("media-rebind", "attempt" to attempt, "ok" to false, "error" to t.javaClass.simpleName)
                if (attempt > waits.size || stopped.get()) throw t
                delay(waits[attempt - 1])
                if (stopped.get()) return
            }
        }
    }

    /**
     * WR-05: rebinds a media server that is running but not listening (a move whose bind kept failing) on the bridge's own
     * address. Does nothing when there is no media server or it is listening; skipped when the system address differs from the
     * bridge's (T-05-50: that is the rebind's job). The caller holds [netLock]. Returns true when a rebind was attempted.
     */
    private suspend fun repairMedia(trigger: String): Boolean {
        val media = mediaServer ?: return false
        if (media.isListening()) return false
        if (addressChanged()) {
            emit("media-repair", "skipped" to "address-changed", "trigger" to trigger)
            return false
        }
        val host = net.wifiIp.hostAddress.orEmpty()
        var error: String? = null
        try {
            withContext(Dispatchers.IO) { media.rebindHost(host) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failure("repair:media", t)
            error = t.javaClass.simpleName
        }
        val fields = ArrayList<Pair<String, Any?>>()
        fields.add("ok" to (error == null))
        fields.add("trigger" to trigger)
        if (error != null) fields.add("error" to error)
        emit("media-repair", *fields.toTypedArray())
        return true
    }

    /**
     * Sibling of [repairMedia]: a running receiver whose DIAL server a failed move left missing is bound again on the
     * bridge's own address (same port, uuid and lounge session; [YouTubeCastReceiver.rebindDial]). Skipped when the system
     * address differs from the bridge's (that is the rebind's job). The caller holds [netLock]. Returns true when a rebind
     * was attempted for at least one receiver.
     */
    private suspend fun repairDial(trigger: String): Boolean {
        val missing = receiverList.map { it.receiver }.filter { it.dialMissing() }
        if (missing.isEmpty()) return false
        if (addressChanged()) {
            emit("dial-repair", "skipped" to "address-changed", "trigger" to trigger)
            return false
        }
        val host = net.wifiIp.hostAddress.orEmpty()
        for (receiver in missing) {
            var error: String? = null
            try {
                receiver.rebindDial(host)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failure("repair:dial", t)
                error = t.javaClass.simpleName
            }
            val fields = ArrayList<Pair<String, Any?>>()
            fields.add("ok" to (error == null))
            fields.add("trigger" to trigger)
            if (error != null) fields.add("error" to error)
            emit("dial-repair", *fields.toTypedArray())
        }
        return true
    }

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
     * Removes everything this bridge put on the network (D-06, Pitfall 12): in-flight setups are cancelled, every receiver
     * is destroyed (byebye, DIAL port closed, loungeScreenDisconnected), then the SSDP client, the responder and the media
     * server stop. Each step is isolated, so one failure never skips the rest. Safe to call twice.
     *
     * With [clearSpeakers] every speaker this app loaded is emptied first (CAST-06; in parallel, each bounded, see
     * [RendererRegistry.removeAll]). Clear only on an explicit service Stop: a Wi-Fi rebind (the bridge comes
     * straight back), a start that failed, a goodbye and a stale speaker leave the speaker alone.
     *
     * D-03: switching the bridge off ends playback. With [stopSpeakers] every speaker that is playing gets a Stop and keeps
     * its track loaded; only [clearSpeakers] (the GUI-08 setting) also empties it. Neither flag: the speakers are not
     * touched (the reload-config restart, where the bridge comes straight back).
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
            replySources.clear()
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
