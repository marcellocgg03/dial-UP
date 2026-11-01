package dev.dialup.app

// Plan 02-13: the product feature. Opening the app starts the Bridge (the index.js port) inside the connectedDevice
// foreground service: every speaker found on the Wi-Fi becomes a cast target in YouTube and YouTube Music.
// GPL-3.0-only.

import android.os.SystemClock
import dev.dialup.app.model.BridgeStatus
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
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
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
 *
 * The optional `files/config.json` (see :renderer ConfigFile.kt for the keys) is read each time the bridge starts; what
 * was applied is logged as a `config` event and shown under "config" by `cast-state`, and `reload-config` restarts the
 * bridge (without clearing the speakers) so an edited file takes effect without reinstalling.
 *
 * A Wi-Fi change does not restart the bridge (plan 05-10, SVC-04): every address change freezes the renderers at once
 * ([Bridge.networkLost]); after a 2 s debounce [rebindAction] decides, and a new address waits up to 20 s for a validated
 * Wi-Fi network and then moves the running bridge in place ([Bridge.rebindNetwork]), so receivers, lounge sessions and
 * the playing track survive. Only `reload-config` still restarts it. A start that throws, or finds no Wi-Fi, is logged
 * (`feature-error where=start-bridge`) and retried every 30 s while no bridge exists. The rebind is always on (GUI-10).
 */
class CastFeature : SpikeFeature, CastCountSource {
    override val name = "cast"
    override val commands: Set<String> = setOf("reannounce", "cast-state", "reload-config", "housekeeping")

    private var host: SpikeHost? = null
    private var monitor: NetworkMonitor? = null
    private var rebindJob: Job? = null
    private var retryJob: Job? = null
    private var countJob: Job? = null
    private var updaterJob: Job? = null
    @Volatile private var updater: YtDlpUpdater? = null
    private val settingsState = MutableStateFlow<Settings?>(null)

    private var settings: Settings?
        get() = settingsState.value
        set(value) {
            settingsState.value = value
        }

    /** The current bridge, as a flow so the status UI follows a rebuilt bridge (Wi-Fi start, reload-config) without reopening (plan 07-03). */
    private val bridgeState = MutableStateFlow<Bridge?>(null)

    private var bridge: Bridge?
        get() = bridgeState.value
        set(value) {
            bridgeState.value = value
        }

    /** The running bridge's speakers with their live state; empty while there is no bridge. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val speakers: Flow<List<SpeakerUi>> = bridgeState.flatMapLatest { it?.speakers ?: flowOf(emptyList()) }

    /** The bridge now, for the controls that act on one speaker (plan 07-08); null while there is none. */
    fun currentBridge(): Bridge? = bridgeState.value

    /**
     * GUI-08: empties the running bridge's cache of every file no stream pins (T-07-21) and logs `cache-clear` with the
     * count. Null while there is no bridge. Blocking file work: call off the main thread.
     */
    fun clearCache(): Int? {
        val b = bridge ?: return null
        val cleared = b.clearCache()
        host?.evidence?.event("cache-clear", mapOf("cleared" to cleared, "source" to "ui"))
        return cleared
    }

    /** GUI-08: files, bytes and cap of the running bridge's cache; null without a bridge or without a file builder. */
    fun cacheInfo(): CacheInfo? = bridge?.cacheInfo()

    /** GUI-07: one yt-dlp update now on the chosen channel; BUSY while a scheduled update or a lookup runs. Null without an updater. */
    suspend fun updateNow(): YtDlpUpdateOutcome? = updater?.updateNow()

    /** GUI-07: the installed yt-dlp version; null when it cannot be read or the service is stopped. */
    suspend fun ytdlpVersion(): String? = updater?.version()

    /** The current bridge's resolver; the yt-dlp updater waits while it has a lookup running (RESEARCH Pitfall 4). */
    @Volatile private var resolver: CachingResolver? = null

    /** What the last bridge start applied from files/config.json (counts and switches only, never pattern text). */
    @Volatile private var configSummaryJson: JsonObject? = null

    /** Last Wi-Fi address the bridge was (or tried to be) bound to; used for the rebind log. */
    private val lastIpState = MutableStateFlow<String?>(null)

    private var lastIp: String?
        get() = lastIpState.value
        set(value) {
            lastIpState.value = value
        }

    /** The header's state (plan 07-03, D-05): whether a bridge exists, the Wi-Fi address it is bound to, and who may cast. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val status: Flow<BridgeStatus> = settingsState.flatMapLatest { s ->
        if (s == null) {
            flowOf(BridgeStatus.OFF)
        } else {
            combine(bridgeState, lastIpState, s.dialAccess) { b, ip, mode -> BridgeStatus(b != null, if (b != null) ip else null, mode) }
        }
    }

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
        // GUI-08 / D-09: the log toggle acts on the running service at once. The level is the one a restart would give
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
        val mon = NetworkMonitor(host.context, host.evidence) { reason -> onNetworkChange(reason) }
        monitor = mon
        mon.seed(host.wifiIpv4()?.hostAddress)
        mon.start()
        host.scope.launch { withBridgeLock(host) { if (!startBridgeGuarded(host, "start")) scheduleStartRetry(host) } }
        HousekeepingAlarm.schedule(host.context)
        // Started here and not in startBridge, which runs again on every Wi-Fi rebind and would stack updaters. The
        // updater has its own coroutine and dispatcher: the cast path never waits for it and it never waits for a cast.
        // The deadline runs on the elapsed-time clock (time the CPU slept counts), is re-checked at least every 15 minutes
        // and at every housekeeping alarm (plan 05-11, RESEARCH Pitfall 3).
        val u = YtDlpUpdater(
            gateway = AndroidYtDlpGateway(host.context, host.evidence),
            channel = { loadAppConfig(host.context.filesDir).config.ytdlpChannel },
            onEvent = { host.evidence.event("ytdlp-update", it) },
            elapsedMs = { SystemClock.elapsedRealtime() },
            maxPauseMs = UPDATE_RECHECK_MS,
            busy = { (resolver?.inFlightCount() ?: 0) > 0 },
        )
        updater = u
        updaterJob = host.scope.launch { u.run() }
    }

    /**
     * Every explicit stop ends here: the header switch (plan 07-04 persists it first), the notification Stop and adb stop.
     * D-03: playback ends, so every speaker playing our stream gets a Stop; it is emptied as well (CAST-06) only when the
     * "clear the speaker when a cast ends" setting is on (GUI-08). Then every receiver says byebye and goodbye before the
     * service goes. The stop or clear is bounded to about 4 s and runs in parallel across speakers, so the worst case
     * stays short.
     */
    override fun stop() {
        host?.let { HousekeepingAlarm.cancel(it.context) }
        updaterJob?.cancel()
        updaterJob = null
        updater = null
        runCatching { monitor?.stop() }
        monitor = null
        rebindJob?.cancel()
        retryJob?.cancel()
        // The shutdown runs on the process scope, never on the calling (main) thread, and never waits here (review WR-02).
        // The token is registered before this returns, so a start on a new service instance sees the stop in flight (CR-01 a).
        val h = host
        val clear = settings?.data?.value?.clearOnCastEnd == true
        val token = BridgeLifecycle.beginStop()
        BridgeLifecycle.scope.launch {
            val started = System.nanoTime()
            try {
                BridgeLifecycle.mutex.withLock { stopBridge(clearSpeakers = clear, stopSpeakers = true) }
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

    /**
     * Every start path (first start, retry, rebind): waits for a stop in flight, also one begun by another service
     * instance, then takes the process-wide lifecycle lock (CR-01 a, D-1). A wait that runs out is logged and the start
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
     * One housekeeping pass (plan 05-11): the bridge's search, retry, re-announce and probe. Run by the in-process timer
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
     * [startBridge] that logs a failure as `feature-error` instead of throwing (plan 05-19, review WR-01). True only when
     * a bridge exists afterwards, so a start that found no Wi-Fi is false too. Call with the lifecycle lock held.
     */
    private suspend fun startBridgeGuarded(h: SpikeHost, reason: String): Boolean {
        runGuarded("start-bridge", { where, t -> h.evidence.event("feature-error", featureErrorFields(where, t) + ("reason" to reason)) }) {
            startBridge(h)
        }
        return bridge != null
    }

    /**
     * Looks for a bridge again after [START_RETRY_MS]. Its own job, so a retry that fails can schedule the next one
     * without cancelling itself; [scheduleRebind] and [stop] cancel it.
     */
    private fun scheduleStartRetry(h: SpikeHost) {
        h.evidence.event("net", mapOf("retry" to true, "inMs" to START_RETRY_MS))
        retryJob = h.scope.launch {
            delay(START_RETRY_MS)
            withBridgeLock(h) { applyRebind(h, "retry") }
        }
    }

    /** Call with the lifecycle lock held, off the main thread. */
    private suspend fun startBridge(h: SpikeHost) {
        val network = bridgeNetwork(h)
        if (network == null) {
            lastIp = null
            h.evidence.event("net", mapOf("wifi" to false))
            return
        }
        lastIp = network.wifiIp.hostAddress
        val appSettings = checkNotNull(settings) { "settings not initialised" }
        val payloads = h.payloads()
        val cacheDir = File(h.context.cacheDir, "yt-dlp").also { it.mkdirs() }
        val loaded = loadAppConfig(h.context.filesDir)
        // D-11: reload-config and every Wi-Fi rebind pass through here, so a changed logLevel applies before the config event
        h.evidence.setLevel(logLevelOf(loaded.config.logLevel))
        configSummaryJson = configSummary(loaded)
        h.evidence.event("config", configEvidenceFields(loaded))
        loaded.warnings.take(MAX_CONFIG_WARNING_EVENTS).forEach { h.evidence.event("config-warning", mapOf("text" to it)) }
        // one gateway serves the resolver and the downloader that builds the mp4/flac source files
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
                // GUI-08 / D-09: read at clear time, so switching the setting applies to the running bridge
                clearOnCastEnd = { appSettings.data.value.clearOnCastEnd },
                // GUI-08 / D-09: the cache cap read at every prune: the app's value when set, else config.json or the default
                cacheMb = { appSettings.data.value.cacheMb ?: loaded.config.cacheMb },
                // GUI-03 / D-08: the hidden speakers, kept across bridges and readable while the bridge is off
                hidden = SpeakerStores.of(h.context).hidden,
                // GUI-04 / D-06: the speakers added by address, saved on a successful add and probed at every start
                manual = SpeakerStores.of(h.context).manual,
                serverHeader = "Linux/${System.getProperty("os.version").orEmpty()} UPnP/1.1 famium/0.0.1",
                events = EventSink { kind, fields -> h.evidence.event(kind, fields) },
                scope = h.scope,
                bufferDir = File(h.context.cacheDir, "media-buffer").also { it.mkdirs() },
                onMediaRequest = { onMediaRequest(h, it) },
                onDialRequest = { onDialRequest(h, it) },
                sourceDownloader = YtDlpSourceDownloader(gateway, cacheDir.absolutePath),
                // plan 05-18's housekeeping reads this to skip while there is no Wi-Fi and to refuse a repair onto a
                // changed address; a supplier that throws counts as no Wi-Fi
                currentNetwork = { runCatching { bridgeNetwork(h) }.getOrNull() },
            ),
        )
        b.start()
        bridge = b
        countJob = h.scope.launch { b.castCount.collect { count.value = CastCount(it.casting, it.total) } }
        scheduleWarmUp(h, b)
    }

    /**
     * Call with the lifecycle lock held. [stopSpeakers]: stop every playing speaker first (D-03). [clearSpeakers]: also empty
     * every speaker's transport URI (CAST-06, only with the GUI-08 setting on). Neither: the speakers are not touched.
     */
    private suspend fun stopBridge(clearSpeakers: Boolean, stopSpeakers: Boolean = false) {
        val b = bridge
        bridge = null
        countJob?.cancel()
        countJob = null
        try {
            b?.stop(clearSpeakers, stopSpeakers)
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

    /**
     * Called by [NetworkMonitor] on every Wi-Fi address change. The renderers freeze at once, before the speaker's broken
     * stream can be mirrored to the phone; the move itself waits for the debounce.
     */
    private fun onNetworkChange(reason: String) {
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
        val oldIp = lastIp
        when (rebindAction(reason, bridge != null, h.wifiIpv4()?.hostAddress)) {
            RebindAction.MARK_LOST_ONLY ->
                // no Wi-Fi address now: the bridge stays, frozen, and the next address change moves it
                h.evidence.event("net", mapOf("rebind" to false, "mode" to "lost-kept", "reason" to reason, "oldIp" to oldIp))

            RebindAction.START -> {
                // a move is never cut in half by a newer event; that one waits for the lock instead
                val ok = withContext(NonCancellable) { startBridgeGuarded(h, reason) }
                if (ok) {
                    h.evidence.event("net", mapOf("rebind" to true, "mode" to "start", "reason" to reason, "oldIp" to oldIp, "newIp" to lastIp))
                } else {
                    h.evidence.event("net", mapOf("rebind" to false, "mode" to "start-failed", "reason" to reason, "oldIp" to oldIp))
                    scheduleStartRetry(h)
                }
            }

            RebindAction.RESTART -> {
                val ok = withContext(NonCancellable) {
                    stopBridge(clearSpeakers = false)
                    startBridgeGuarded(h, reason)
                }
                if (ok) {
                    h.evidence.event("net", mapOf("rebind" to true, "mode" to "restart", "reason" to reason, "oldIp" to oldIp, "newIp" to lastIp))
                } else {
                    h.evidence.event("net", mapOf("rebind" to false, "mode" to "restart-failed", "reason" to reason, "oldIp" to oldIp))
                    scheduleStartRetry(h)
                }
            }

            // only the retry reason gets here: a bridge exists (done) or there is still no Wi-Fi (look again later)
            RebindAction.NOTHING -> if (bridge == null) scheduleStartRetry(h)

            RebindAction.REBIND_IN_PLACE -> {
                val started = System.nanoTime()
                val validated = monitor?.awaitValidated(VALIDATED_WAIT_MS) ?: false
                val waitedMs = (System.nanoTime() - started) / 1_000_000L
                // the network may be gone again after the wait: then the bridge stays frozen until the next address
                val network = bridgeNetwork(h)
                val b = bridge
                if (network == null || b == null) {
                    h.evidence.event(
                        "net",
                        mapOf("rebind" to false, "mode" to "lost-kept", "reason" to reason, "oldIp" to oldIp, "validated" to validated, "waitedMs" to waitedMs),
                    )
                    return
                }
                withContext(NonCancellable) { b.rebindNetwork(network) }
                lastIp = network.wifiIp.hostAddress
                h.evidence.event(
                    "net",
                    mapOf(
                        "rebind" to true,
                        "mode" to "in-place",
                        "reason" to reason,
                        "oldIp" to oldIp,
                        "newIp" to lastIp,
                        "validated" to validated,
                        "waitedMs" to waitedMs,
                    ),
                )
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
