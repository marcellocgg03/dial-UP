package dev.dialup.app

// The product feature. Opening the app starts the Bridge (the index.js port) inside the connectedDevice
// foreground service: every speaker found on the Wi-Fi becomes a cast target in YouTube and YouTube Music.
// GPL-3.0-only.

import android.os.SystemClock
import dev.dialup.app.model.BridgeStatus
import dev.dialup.app.model.RestartFields
import dev.t2u.dial.DialRequest
import dev.t2u.dial.redactLaunchBody
import dev.t2u.media.CacheInfo
import dev.t2u.media.CachingResolver
import dev.t2u.media.MediaRequest
import dev.t2u.media.YtDlpUpdateOutcome
import dev.t2u.media.YtDlpUpdater
import dev.t2u.media.YtDlpSourceDownloader
import dev.t2u.renderer.Bridge
import dev.t2u.renderer.BridgeDeps
import dev.t2u.renderer.BridgeNetwork
import dev.t2u.renderer.SpeakerUi
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.ListenSpec
import dev.t2u.upnp.lanHttpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
 * Every OkHttp client in the app derives from `lanHttpClient()`; nothing here builds one directly.
 *
 * The optional `files/config.json` (see :renderer ConfigFile.kt for the keys) is read each time the bridge starts; what
 * was applied is logged as a `config` event and shown under "config" by `cast-state`, and `reload-config` restarts the
 * bridge (without clearing the speakers) so an edited file takes effect without reinstalling.
 *
 * A Wi-Fi blip pauses renderer polling at once, then after a 2 s debounce the bridge moves in place and resyncs playback
 * when Wi-Fi returns. A continuous outage beyond [dev.dialup.app.model.WIFI_REBIND_WINDOW_MS] stops the bridge/service
 * without sending Stop or Clear to speakers; Wi-Fi return starts a fresh bridge. Only `reload-config` otherwise restarts it.
 * A start that throws, or finds no Wi-Fi, is logged
 * (`feature-error where=start-bridge`) and retried every 30 s while no bridge exists. The rebind is always on.
 */
class CastFeature : SpikeFeature, CastCountSource, NetworkStateSource {
    override val name = "cast"
    override val commands: Set<String> = setOf("reannounce", "cast-state", "reload-config", "housekeeping")

    private var host: SpikeHost? = null
    private var monitor: NetworkMonitor? = null
    private var rebindJob: Job? = null
    private var retryJob: Job? = null

    /**
     * The last start failed on a busy port; with [consecutiveBindFailures] it picks the retry delay. Read and written under
     * the lifecycle lock.
     */
    @Volatile private var lastStartBindFailure = false
    private var consecutiveBindFailures = 0
    private var countJob: Job? = null
    private var updaterJob: Job? = null
    @Volatile private var networkLossExpired = false
    @Volatile private var updater: YtDlpUpdater? = null
    private val settingsState = MutableStateFlow<Settings?>(null)

    private var settings: Settings?
        get() = settingsState.value
        set(value) {
            settingsState.value = value
        }

    /** The current bridge, as a flow so the status UI follows a rebuilt bridge (Wi-Fi start, reload-config) without reopening. */
    private val bridgeState = MutableStateFlow<Bridge?>(null)

    private var bridge: Bridge?
        get() = bridgeState.value
        set(value) {
            bridgeState.value = value
        }

    /** The running bridge's speakers with their live state; empty while there is no bridge. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val speakers: Flow<List<SpeakerUi>> = bridgeState.flatMapLatest { it?.speakers ?: flowOf(emptyList()) }

    /** True while the bridge is inside a multicast renderer-search reply window. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val searching: Flow<Boolean> = bridgeState.flatMapLatest { it?.searching ?: flowOf(false) }

    /** The bridge now, for the controls that act on one speaker; null while there is none. */
    fun currentBridge(): Bridge? = bridgeState.value

    /** What the running bridge read at its start (gapless, forced format); set with [bridge], cleared with it. */
    @Volatile private var startedWith: RestartFields? = null

    /**
     * The restart-relevant settings the running bridge was started with, or null while there is no bridge. They live in
     * memory for exactly as long as that bridge runs, so a process kill after a change leaves nothing pending: the next bridge
     * starts from settings.json. A Wi-Fi rebind keeps them because the bridge moves in place and the config is not reloaded.
     */
    fun runningWith(): RestartFields? = if (bridge != null) startedWith else null

    /**
     * Empties the running bridge's cache of every file no stream pins (a file a stream pins is kept) and logs `cache-clear` with the
     * count. Null while there is no bridge. Blocking file work: call off the main thread.
     */
    fun clearCache(): Int? {
        val b = bridge ?: return null
        val cleared = b.clearCache()
        host?.evidence?.event("cache-clear", mapOf("cleared" to cleared, "source" to "ui"))
        return cleared
    }

    /** Files, bytes and cap of the running bridge's cache; null without a bridge or without a file builder. */
    fun cacheInfo(): CacheInfo? = bridge?.cacheInfo()

    /** One yt-dlp update now on the chosen channel; BUSY while a scheduled update or a lookup runs. Null without an updater. */
    suspend fun updateNow(): YtDlpUpdateOutcome? = updater?.updateNow()

    /**
     * The daily job's entry point while the service runs: one update when the record says it is due and
     * nothing is cast or looked up. Null when the record says "recent"; a CASTING or BUSY style outcome postpones it.
     * Null without an updater.
     */
    suspend fun ytdlpUpdateIfDue(trigger: String): YtDlpUpdateOutcome? = updater?.updateIfDue(trigger)

    /** The installed yt-dlp version; null when it cannot be read or the service is stopped. */
    suspend fun ytdlpVersion(): String? = updater?.version()

    /** The current bridge's resolver; the yt-dlp updater waits while it has a lookup or, through the bridge, a download running. */
    @Volatile private var resolver: CachingResolver? = null

    /** What the last bridge start applied from files/config.json (counts and switches only, never pattern text). */
    @Volatile private var configSummaryJson: JsonObject? = null

    private val networkUpState = MutableStateFlow(true)

    /** False while the phone has no Wi-Fi or hotspot address to bind to; the notification goes quiet then. */
    override val networkUp: StateFlow<Boolean> = networkUpState.asStateFlow()

    private val wifiPresentState = MutableStateFlow(true)

    /** True while the network callbacks report a Wi-Fi client or hotspot address, whatever the rebind is doing. */
    override val wifiPresent: StateFlow<Boolean> = wifiPresentState.asStateFlow()

    /** Last Wi-Fi address the bridge was (or tried to be) bound to; used for the rebind log and the DIAL self check. */
    @Volatile private var lastIp: String? = null

    /** The bridge card's state: whether a bridge exists and who may cast. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val status: Flow<BridgeStatus> = settingsState.flatMapLatest { s ->
        if (s == null) {
            flowOf(BridgeStatus.OFF)
        } else {
            combine(bridgeState, s.dialAccess) { b, mode -> BridgeStatus(b != null, mode) }
        }
    }

    private val warmed = AtomicBoolean(false)
    private val denied = AtomicInteger()

    /** Last time a DIAL denial was logged per source IP (one event per source per minute). */
    private val deniedLogged = ConcurrentHashMap<String, Long>()

    private val count = MutableStateFlow(CastCount(0, 0))

    private val networkLossCount = MutableStateFlow(CastCount(0, 0))

    /** Derived from the bridge's card-visible state and used only to decide whether to show the cast notification. */
    override val castCount: StateFlow<CastCount> = count.asStateFlow()
    override val networkLossCastCount: StateFlow<CastCount> = networkLossCount.asStateFlow()

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
        // The log toggle acts on the running service at once. The level is the one a restart would give
        // (config.json plus the settings), taken from the value that changed because the file may not be written yet.
        host.scope.launch {
            appSettings.data.map { it.logEnabled }.distinctUntilChanged().drop(1).collect { enabled ->
                val data = appSettings.data.value
                val level = logLevelOf(applySettings(loadAppConfig(host.context.filesDir).config, data).logLevel)
                // an event written after the level dropped to off would be filtered out, so record the change first then
                if (level == LogLevel.OFF) host.evidence.event("settings", mapOf("logEnabled" to enabled, "source" to "change"))
                host.evidence.setLevel(level)
                if (level != LogLevel.OFF) host.evidence.event("settings", mapOf("logEnabled" to enabled, "source" to "change"))
            }
        }
        val mon = NetworkMonitor(host.context, host.evidence, { selectTether(tetherCandidates())?.ipv4 }) { reason ->
            onNetworkChange(reason)
        }
        monitor = mon
        mon.seed(host.wifiIpv4()?.hostAddress)
        wifiPresentState.value = mon.currentIp() != null
        mon.start()
        host.scope.launch { withBridgeLock(host) { if (!startBridgeGuarded(host, "start")) scheduleStartRetry(host) } }
        HousekeepingAlarm.schedule(host.context)
        // Daily yt-dlp update job; a no-op while one with its id is pending.
        YtDlpJob.schedule(host.context)
        // Started here and not in startBridge, which runs again on every Wi-Fi rebind and would stack updaters. The
        // updater has its own coroutine and dispatcher: the cast path never waits for it and it never waits for a cast.
        // The deadline runs on the elapsed-time clock (time the CPU slept counts), is re-checked at least every 15 minutes
        // and at every housekeeping alarm. It treats a lookup or a download as busy. It never
        // updates while a renderer is being cast to (a postponed update is re-checked, never forced) and goes through the
        // persisted record, so a restart or a Wi-Fi flap within 18 hours of an update causes no further one.
        val u = YtDlpUpdater(
            gateway = AndroidYtDlpGateway(host.context, host.evidence),
            channel = { loadAppConfig(host.context.filesDir).config.ytdlpChannel },
            onEvent = { host.evidence.event("ytdlp-update", it) },
            elapsedMs = { SystemClock.elapsedRealtime() },
            maxPauseMs = UPDATE_RECHECK_MS,
            busy = { (resolver?.inFlightCount() ?: 0) > 0 || (bridge?.activeBuilds() ?: 0) > 0 },
            casting = { count.value.casting > 0 },
            record = YtDlpRecords.of(host.context),
            wallMs = { System.currentTimeMillis() },
        )
        updater = u
        updaterJob = host.scope.launch { u.run() }
    }

    /**
     * Every explicit stop ends here: the header switch (BridgeControl persists it first), the notification Stop and adb stop.
     * Explicit stops end playback and may clear speakers according to the setting. A Wi-Fi-loss timeout only stops the local
     * bridge; it leaves playback already accepted by speakers alone.
     */
    override fun stop() {
        val preserveSpeakerPlayback = networkLossExpired
        networkLossExpired = false
        networkUpState.value = true
        wifiPresentState.value = true
        host?.let { HousekeepingAlarm.cancel(it.context) }
        updaterJob?.cancel()
        updaterJob = null
        updater = null
        runCatching { monitor?.stop() }
        monitor = null
        rebindJob?.cancel()
        retryJob?.cancel()
        // The shutdown runs on the process scope, never on the calling (main) thread, and never waits here.
        // The token is registered before this returns, so a start on a new service instance sees the stop in flight.
        val h = host
        val clear = !preserveSpeakerPlayback && settings?.data?.value?.clearOnCastEnd == true
        val token = BridgeLifecycle.beginStop()
        BridgeLifecycle.scope.launch {
            val started = System.nanoTime()
            try {
                BridgeLifecycle.mutex.withLock { stopBridge(clearSpeakers = clear, stopSpeakers = !preserveSpeakerPlayback) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                runCatching { h?.evidence?.event("feature-error", featureErrorFields("stop-bridge", t)) }
            } finally {
                // written before the token completes, so on the log each bridge-stop-done precedes the next start
                runCatching { h?.evidence?.event("bridge-stop-done", mapOf("ms" to (System.nanoTime() - started) / 1_000_000L)) }
                token.complete()
            }
        }
    }

    /** A Wi-Fi-loss timeout stops the local bridge, not playback already accepted by the speaker. */
    fun onNetworkLossExpired() {
        networkLossExpired = true
    }

    /**
     * Every start path (first start, retry, rebind): waits for a stop in flight, also one begun by another service
     * instance, then takes the process-wide lifecycle lock. A wait that runs out is logged and the start
     * goes on: the lock still serialises it and a port collision is handled as a bind failure.
     */
    private suspend fun withBridgeLock(h: SpikeHost, block: suspend () -> Unit) {
        if (!BridgeLifecycle.awaitIdle(STOP_WAIT_MS)) {
            h.evidence.event("bridge-error", mapOf("where" to "stop-wait", "waitedMs" to STOP_WAIT_MS))
        }
        BridgeLifecycle.mutex.withLock { block() }
    }

    override fun handle(cmd: String, args: Map<String, String>): CommandReply = when (cmd) {
        "reannounce" -> {
            val b = bridge
            b?.reannounce()
            host?.evidence?.event("reannounce", mapOf("running" to (b != null)))
            CommandReply(true, """{"ok":true}""")
        }
        "cast-state" -> CommandReply(true, state().toString())
        "reload-config" -> {
            host?.evidence?.event("reload-config", emptyMap())
            scheduleRebind("config")
            CommandReply(true, """{"ok":true,"note":"restarting the bridge; playback is interrupted"}""")
        }
        "housekeeping" -> {
            // trigger adb is never deduped; the reply does not wait for the run
            host?.scope?.launch { housekeeping("adb") }
            CommandReply(true, """{"started":true}""")
        }
        else -> CommandReply(false, """{"error":"unknown command"}""")
    }

    /**
     * One housekeeping pass: the bridge's search, retry, re-announce and probe. Run by the in-process timer
     * of the bridge, by [HousekeepingReceiver] when the alarm wakes the idle phone, and by `cmd housekeeping`.
     */
    suspend fun housekeeping(trigger: String) {
        try {
            bridge?.housekeeping(trigger)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            host?.evidence?.event("feature-error", mapOf("where" to "housekeeping", "error" to t.javaClass.name, "message" to t.message))
        }
        // a yt-dlp update that came due while the timers stalled in Doze runs now; failures never reach the cast path
        updater?.runIfDue()
    }

    override fun state(): JsonObject {
        val base = bridge?.state() ?: JsonObject(mapOf("running" to JsonPrimitive(false)))
        val extra = mapOf(
            "dialAccess" to JsonPrimitive(settings?.dialAccess?.value?.wire),
            "denied" to JsonPrimitive(denied.get()),
        )
        val config = configSummaryJson
        return JsonObject(if (config == null) base + extra else base + extra + ("config" to config))
    }

    // ----- lifecycle -----------------------------------------------------------------------------

    /** The Wi-Fi address and interface as a bridge network; null when there is no Wi-Fi IPv4 or interface. */
    private fun bridgeNetwork(h: SpikeHost): BridgeNetwork? {
        val ip = h.wifiIpv4() ?: return null
        val ifaceName = h.wifiInterfaceName() ?: return null
        val nif = NetworkInterface.getByName(ifaceName)
        return BridgeNetwork(ip, nif, listOf(ListenSpec("explicit", null, 1900, nif, false)))
    }

    /**
     * [startBridge] that logs a failure as `feature-error` instead of throwing. True only when
     * a bridge exists afterwards, so a start that found no Wi-Fi is false too. Call with the lifecycle lock held.
     */
    private suspend fun startBridgeGuarded(h: SpikeHost, reason: String): Boolean {
        lastStartBindFailure = false
        runGuarded("start-bridge", { where, t ->
            if (isBindFailure(t)) {
                // a port the previous bridge still holds: heals on a quick retry, so it is a bridge-error and not a feature-error
                lastStartBindFailure = true
                h.evidence.event("bridge-error", mapOf("where" to "bind", "error" to t.javaClass.name, "reason" to reason))
            } else {
                h.evidence.event("feature-error", featureErrorFields(where, t) + ("reason" to reason))
            }
        }) {
            startBridge(h)
        }
        consecutiveBindFailures = if (lastStartBindFailure) consecutiveBindFailures + 1 else 0
        return bridge != null
    }

    /**
     * Looks for a bridge again after [startRetryDelayMs]: 2 s for the first two bind failures in a row, else
     * [START_RETRY_MS]. Its own job, so a retry that fails can schedule the next one without cancelling itself;
     * [scheduleRebind] and [stop] cancel it.
     */
    private fun scheduleStartRetry(h: SpikeHost) {
        val delayMs = startRetryDelayMs(maxOf(consecutiveBindFailures - 1, 0), lastStartBindFailure)
        h.evidence.event("net", mapOf("retry" to true, "inMs" to delayMs))
        retryJob = h.scope.launch {
            delay(delayMs)
            withBridgeLock(h) { applyRebind(h, "retry") }
        }
    }

    /** Call with the lifecycle lock held, off the main thread. */
    private suspend fun startBridge(h: SpikeHost) {
        val network = bridgeNetwork(h)
        if (network == null) {
            lastIp = null
            networkUpState.value = false
            h.evidence.event("net", mapOf("wifi" to false))
            return
        }
        lastIp = network.wifiIp.hostAddress
        networkUpState.value = true
        val appSettings = checkNotNull(settings) { "settings not initialised" }
        val payloads = h.payloads()
        val cacheDir = File(h.context.cacheDir, "yt-dlp").also { it.mkdirs() }
        val loaded = loadAppConfig(h.context.filesDir)
        // Reload-config and every Wi-Fi rebind pass through here, so a changed logLevel applies before the config event
        h.evidence.setLevel(logLevelOf(loaded.config.logLevel))
        configSummaryJson = configSummary(loaded)
        h.evidence.event("config", configEvidenceFields(loaded))
        loaded.warnings.take(MAX_CONFIG_WARNING_EVENTS).forEach { h.evidence.event("config-warning", mapOf("text" to it)) }
        // one gateway serves the resolver and the downloader that builds the mp4 source files
        val gateway = AndroidYtDlpGateway(h.context, h.evidence)
        val resolver = CachingResolver(
            gateway,
            cacheDir = cacheDir.absolutePath,
            onEvent = { h.evidence.event("resolve", it) },
            verbose = loaded.config.ytdlpVerbose,
        )
        this.resolver = resolver
        val b = Bridge(
            network,
            BridgeDeps(
                config = loaded.config,
                resolver = resolver,
                launcher = FfmpegRunner(payloads, h.context.cacheDir, h.evidence),
                http = lanHttpClient(),
                accessMode = { appSettings.dialAccess.value },
                // Read at clear time, so switching the setting applies to the running bridge
                clearOnCastEnd = { appSettings.data.value.clearOnCastEnd },
                // The cache cap read at every prune: the app's value when set, else config.json or the default
                cacheMb = { appSettings.data.value.cacheMb ?: loaded.config.cacheMb },
                // The hidden speakers, kept across bridges and readable while the bridge is off
                hidden = SpeakerStores.of(h.context).hidden,
                // The speakers added by address, saved on a successful add and probed at every start
                manual = SpeakerStores.of(h.context).manual,
                // The author's renderer names, applied when a renderer is set up and live on a rename
                names = SpeakerStores.of(h.context).names,
                serverHeader =
                    "Linux/${System.getProperty("os.version").orEmpty()} UPnP/1.1 DialUP/${BuildConfigFacts.versionName(h.context) ?: "0"}",
                events = EventSink { kind, fields -> h.evidence.event(kind, fields) },
                scope = h.scope,
                bufferDir = mediaBufferDir(h.context),
                onMediaRequest = { onMediaRequest(h, it) },
                onDialRequest = { onDialRequest(h, it) },
                sourceDownloader = YtDlpSourceDownloader(gateway, cacheDir.absolutePath),
                // Housekeeping reads this to skip while there is no Wi-Fi and to refuse a repair onto a
                // changed address; a supplier that throws counts as no Wi-Fi
                currentNetwork = { runCatching { bridgeNetwork(h) }.getOrNull() },
            ),
        )
        b.startSuspending()
        bridge = b
        startedWith = RestartFields(loaded.config.gapless, loaded.config.outputFormat)
        countJob = h.scope.launch {
            combine(b.visibleCastCount, b.castCount) { visible, active -> visible to active }.collect { (visible, active) ->
                count.value = CastCount(visible.casting, visible.total)
                networkLossCount.value = CastCount(active.casting, active.total)
            }
        }
        scheduleWarmUp(h, b)
    }

    /**
     * Call with the lifecycle lock held. [stopSpeakers]: stop every playing speaker first. [clearSpeakers]: also empty
     * every speaker's transport URI (only with the clear-on-cast-end setting on). Neither: the speakers are not touched.
     */
    private suspend fun stopBridge(clearSpeakers: Boolean, stopSpeakers: Boolean = false) {
        val b = bridge
        bridge = null
        startedWith = null
        countJob?.cancel()
        countJob = null
        try {
            b?.stop(clearSpeakers, stopSpeakers)
        } finally {
            count.value = CastCount(0, 0)
            networkLossCount.value = CastCount(0, 0)
        }
    }

    /**
     * The first yt-dlp run pays Python and QuickJS start-up, so one resolve runs once the receivers
     * are advertised (first speaker up, or 15 s) and its duration is logged as `warmup`.
     */
    private fun scheduleWarmUp(h: SpikeHost, b: Bridge) {
        // Debug builds only: DebugFeatures.warmUpVideoId is null in release, so no coroutine starts and no lookup is made.
        val id = DebugFeatures.warmUpVideoId(h.context) ?: return
        if (!warmed.compareAndSet(false, true)) return
        h.scope.launch {
            withTimeoutOrNull(WARMUP_WAIT_MS) { b.castCount.first { it.total > 0 } }
            b.warmUp(id)
        }
    }

    // ----- rebind on Wi-Fi change ----------------------------------------------------------------

    /**
     * Called by [NetworkMonitor] on every Wi-Fi address change. The renderers freeze at once, before the speaker's broken
     * stream can be mirrored to the phone; the move itself waits for the debounce.
     */
    private fun onNetworkChange(reason: String) {
        wifiPresentState.value = monitor?.currentIp() != null
        val h = host ?: return
        h.scope.launch { bridge?.networkLost() }
        scheduleRebind(reason)
    }

    private fun scheduleRebind(reason: String) {
        val h = host ?: return
        retryJob?.cancel()
        rebindJob?.cancel()
        rebindJob = h.scope.launch {
            delay(REBIND_DEBOUNCE_MS)
            withBridgeLock(h) { applyRebind(h, reason) }
        }
    }

    /** Call with the lifecycle lock held, after the debounce. A burst of events reaches here once, with the last reason. */
    private suspend fun applyRebind(h: SpikeHost, reason: String) {
        RebindApplier(RebindHost(h)).apply(reason)
    }

    /** [RebindTarget] over this feature's bridge and lifecycle members; a private inner class because [CastFeature] is public. */
    private inner class RebindHost(private val h: SpikeHost) : RebindTarget {
        override val hasBridge: Boolean get() = bridge != null
        override var lastIp: String?
            get() = this@CastFeature.lastIp
            set(value) {
                this@CastFeature.lastIp = value
            }

        override fun wifiIp(): String? = h.wifiIpv4()?.hostAddress

        override fun markNetworkUp(up: Boolean) {
            networkUpState.value = up
        }

        override suspend fun startGuarded(reason: String): Boolean = startBridgeGuarded(h, reason)

        override suspend fun stopKeepingSpeakers() = stopBridge(clearSpeakers = false)

        override fun scheduleStartRetry() = scheduleStartRetry(h)

        override fun resetBindFailures() {
            lastStartBindFailure = false
            consecutiveBindFailures = 0
        }

        override suspend fun awaitValidated(): Boolean = monitor?.awaitValidated(VALIDATED_WAIT_MS) ?: false

        override fun currentNetwork(): BridgeNetwork? = bridgeNetwork(h)

        override fun inPlaceRebind(): (suspend (BridgeNetwork) -> Unit)? {
            val b = bridge ?: return null
            return { network -> b.rebindNetwork(network) }
        }

        override fun event(kind: String, fields: Map<String, Any?>) = h.evidence.event(kind, fields)
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
                "upstream" to req.upstream,
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
        const val STOP_WAIT_MS = 15_000L
        const val VALIDATED_WAIT_MS = 20_000L
        const val WARMUP_WAIT_MS = 15_000L
        const val UPDATE_RECHECK_MS = 900_000L
        const val MAX_CONFIG_WARNING_EVENTS = 20
        const val DENIED_LOG_WINDOW_NS = 60_000_000_000L
    }
}
