package dev.t2u.renderer

// Port of tube2upnp src/index.js (discovery lifecycle), GPL-3.0-only.
//
// One cast target per MediaRenderer UDN. The registry owns the maps index.js keeps at module level
// (renderers, skipped, pending) and the port claim; it creates speakers through a [RendererFactory]
// so it is tested with fakes and wired to the real Renderer by the composition root.
//
// Concurrency: the maps are guarded by one Mutex that is never held across init() or destroy(), and
// the pending set guarantees one init per UDN however many alives arrive at once.

import dev.t2u.lounge.Player
import dev.t2u.upnp.EventSink
import dev.t2u.upnp.NoEvents
import dev.t2u.upnp.isPrivateLanAddress
import dev.t2u.upnp.maxAge
import dev.t2u.upnp.udnFromUsn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.coroutines.ContinuationInterceptor

private val RENDERER_TYPE_RE = Regex("urn:schemas-upnp-org:device:MediaRenderer:\\d")

/** Bound on the "already logged" memory so a flood of made-up UDNs cannot grow it without limit. */
private const val MAX_REMEMBERED_GUARD_HITS = 1024

class RendererRegistry(
    private val config: Config,
    private val ports: PortRegistry,
    private val factory: RendererFactory,
    private val events: EventSink = NoEvents,
    private val clock: () -> Long = System::currentTimeMillis,
    private val clearBudgetMs: Long = CLEAR_BUDGET_MS,
    /** How long [removeAll] and the other removals wait for one speaker's [ManagedRenderer.destroy]. */
    private val destroyBudgetMs: Long = DESTROY_BUDGET_MS,
    /** True for a speaker the author hid. Read from any thread; a hidden speaker is never set up, retried or kept. */
    private val hidden: (String) -> Boolean = { false },
) {
    companion object {
        /**
         * index.js RETRY_FAILED_MS: a device whose init threw is tried again after this long. A device that answered but is
         * not a usable renderer is held back for this long too and looked at again by the first alive after it; the timer
         * never retries it.
         */
        const val RETRY_FAILED_MS = 5 * 60 * 1000L

        /** How often the schedule looks for retries that are due (an attempt is at most about this late). */
        const val RETRY_CHECK_MS = 10_000L

        /** Bound on one [ManagedRenderer.destroy] inside a removal; a destroy that runs longer finishes in the background. */
        const val DESTROY_BUDGET_MS = 2000L

        /** The skipped-speaker memory never holds more than this many entries; the earliest retry time goes first. */
        const val MAX_SKIPPED = 256

        /** How many due setups [retryDue] runs at once. */
        internal const val RETRY_PARALLEL = 8
    }

    /**
     * A device that is not set up. [until] is the epoch ms its hold ends. [src], [maxAge] and [attempts] let [retryDue] set
     * a failed device up again without a new alive; [attempts] counts the timer retries already made. A device that is not
     * a usable renderer has no [src], so only a later alive looks at it again.
     *
     * [until] is wall-clock time and the clock can move backwards (NTP, carrier time). No legitimate wait is longer
     * than [RETRY_FAILED_MS], so an [until] further ahead than that is not waiting any more (see [isWaiting]).
     */
    private class Skip(
        val location: String,
        val until: Long,
        val src: InetSocketAddress? = null,
        val maxAge: Int = 0,
        val attempts: Int = 0,
    )

    /** True while [skip] still holds its speaker back at time [now]; a due time more than one retry period ahead counts as due. */
    private fun isWaiting(skip: Skip, now: Long): Boolean =
        skip.until > now && skip.until - now <= RETRY_FAILED_MS

    private val lock = Mutex()

    /** Destroys that outlive their budget keep running here; nothing ever cancels it. */
    private val destroyScope = CoroutineScope(SupervisorJob())
    private val running = LinkedHashMap<String, ManagedRenderer>()
    private val skipped = HashMap<String, Skip>()
    private val pending = HashSet<String>()
    private val guardLogged = HashSet<String>()

    /** Epoch ms (of [clock]) of the last MediaRenderer search, from the schedule or from Bridge.housekeeping. */
    @Volatile private var lastSearchAt: Long = clock()

    private val _renderersFlow = MutableStateFlow<List<ManagedRenderer>>(emptyList())
    private val _count = MutableStateFlow(0)

    /** The running renderers, updated on every add and remove (the cast count is derived from it). */
    val renderersFlow: StateFlow<List<ManagedRenderer>> = _renderersFlow.asStateFlow()
    val count: StateFlow<Int> = _count.asStateFlow()

    fun renderers(): List<ManagedRenderer> = _renderersFlow.value

    /** Call with [lock] held. */
    private fun publish() {
        _renderersFlow.value = running.values.toList()
        _count.value = running.size
    }

    private fun log(event: String, udn: String, name: String? = null, location: String? = null, port: Int? = null, reason: String? = null) {
        val fields = LinkedHashMap<String, Any?>()
        fields["event"] = event
        fields["udn"] = udn
        fields["name"] = name
        fields["location"] = location
        fields["port"] = port
        if (reason != null) fields["reason"] = reason
        try {
            events.event("discovery", fields)
        } catch (_: Exception) {
            // an event sink must not throw; if it does, discovery goes on
        }
    }

    /** One `retry` event per attempt: [reason] failed (a setup) or terminated (a receiver), [outcome] added, failed, skipped or running. */
    private fun logRetry(
        reason: String,
        udn: String,
        name: String?,
        location: String?,
        port: Int?,
        attempt: Int?,
        outcome: String,
        error: String? = null,
    ) {
        val fields = LinkedHashMap<String, Any?>()
        fields["reason"] = reason
        fields["udn"] = udn
        fields["name"] = name
        fields["location"] = location
        fields["port"] = port
        if (attempt != null) fields["attempt"] = attempt
        fields["outcome"] = outcome
        if (error != null) fields["error"] = error
        try {
            events.event("retry", fields)
        } catch (_: Exception) {
            // an event sink must not throw
        }
    }

    /** Call with [lock] held. Keeps [skipped] within [MAX_SKIPPED]: when a new UDN would exceed it, the earliest retry goes. */
    private fun putSkip(udn: String, skip: Skip) {
        if (skipped.size >= MAX_SKIPPED && !skipped.containsKey(udn)) {
            val oldest = skipped.minByOrNull { it.value.until }?.key
            if (oldest != null) skipped.remove(oldest)
        }
        skipped[udn] = skip
    }

    /** One `skipped` event per UDN and reason, however many datagrams repeat it. */
    private suspend fun logGuardOnce(udn: String, reason: String, location: String) {
        val first = lock.withLock {
            if (guardLogged.size >= MAX_REMEMBERED_GUARD_HITS) guardLogged.clear()
            guardLogged.add("$udn|$reason")
        }
        if (first) log("skipped", udn, location = location, reason = reason)
    }

    /**
     * The LOCATION must name the host that sent the datagram, and it must be a private LAN address
     * (otherwise any device could make the app fetch an arbitrary URL). Names are refused
     * without a lookup. Returns null when the location is acceptable, else the reason.
     */
    private fun locationProblem(location: String, src: InetSocketAddress): String? {
        val srcAddress = src.address ?: return "no source address"
        val host = location.toHttpUrlOrNull()?.host ?: return "location is not a URL"
        val named = if (host.contains(':')) {
            // a name cannot contain ':', so this is an IPv6 literal and no lookup happens
            try { InetAddress.getByName(host) } catch (_: Exception) { null }
        } else if (host.split('.').let { p ->
            p.size == 4 && p.all { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } }
        }) {
            // a dotted quad of decimals: getByName parses it as a literal without a lookup
            try { InetAddress.getByName(host) } catch (_: Exception) { null }
        } else {
            null
        } ?: return "location host is not an IP address"
        if (named != srcAddress) return "location host differs from the sender"
        if (!isPrivateLanAddress(named)) return "location is not a private LAN address"
        return null
    }

    /**
     * index.js onAlive. [headers] are SSDP headers keyed as parseMessage keys them (ST, NT, USN, LOCATION,
     * CACHECONTROL, SERVER, USERAGENT); [src] is the datagram source.
     */
    suspend fun onAlive(headers: Map<String, String>, src: InetSocketAddress) {
        val type = headers["ST"] ?: headers["NT"] ?: ""
        if (!RENDERER_TYPE_RE.containsMatchIn(type)) return

        val udn = udnFromUsn(headers["USN"])
        val location = headers["LOCATION"]
        if (udn == null || location.isNullOrEmpty()) return

        // Guards first: a refused datagram must not refresh, recreate or fetch anything.
        val problem = locationProblem(location, src)
        if (problem != null) {
            logGuardOnce(udn, problem, location)
            return
        }
        val server = (headers["SERVER"].orEmpty() + " " + headers["USERAGENT"].orEmpty()).lowercase()
        val excluded = config.excludeServers.firstOrNull { it.isNotEmpty() && server.contains(it.lowercase()) }
        if (excluded != null) {
            logGuardOnce(udn, "excluded server $excluded", location)
            return
        }
        if (hidden(udn)) {
            logGuardOnce(udn, "hidden", location)
            return
        }

        val age = maxAge(headers["CACHECONTROL"], config.defaultMaxAge)

        val existing = lock.withLock { running[udn] }
        if (existing != null) {
            if (existing.location == location) {
                existing.refresh(age)
                return
            }
            // Same device, new description URL (rebooted, DHCP change, random port): rebuild.
            removeRenderer(udn, "moved", "now at $location")
        }

        val go = lock.withLock {
            val skip = skipped[udn]
            if (skip != null && skip.location == location && isWaiting(skip, clock())) return@withLock false
            pending.add(udn)
        }
        if (!go) return

        setUp(udn, location, src, age, null)
    }

    /**
     * The setup of one speaker, with [udn] already in [pending] (the caller added it under the lock); it is removed
     * here. [retryAttempt] is null for an alive-driven setup and the attempt number for a timer retry, which also
     * writes one `retry` event next to the usual discovery event.
     */
    private suspend fun setUp(udn: String, location: String, src: InetSocketAddress, age: Int, retryAttempt: Int?) {
        var port: Int? = null
        var holdsPort = false
        var renderer: ManagedRenderer? = null
        var registered = false
        try {
            port = ports.claim(udn)
            holdsPort = true
            renderer = factory.create(udn, location, src.address.hostAddress.orEmpty(), age, port)
            if (renderer.init()) {
                // The author may have hidden the speaker while it was coming up: checked under the lock that registers it.
                lock.withLock {
                    if (!hidden(udn)) {
                        running[udn] = renderer
                        skipped.remove(udn)
                        publish()
                        registered = true
                    }
                }
                if (registered) {
                    ports.remember(udn, port)
                    log("added", udn, renderer.name, location, port)
                    if (retryAttempt != null) logRetry("failed", udn, renderer.name, location, port, retryAttempt, "added")
                } else {
                    withContext(NonCancellable) {
                        runCatching { renderer.destroy() }
                        ports.release(port)
                    }
                    holdsPort = false
                    log("skipped", udn, renderer.name, location, reason = "hidden")
                    if (retryAttempt != null) logRetry("failed", udn, renderer.name, location, null, retryAttempt, "skipped")
                }
            } else {
                ports.release(port)
                holdsPort = false
                lock.withLock { putSkip(udn, Skip(location, clock() + RETRY_FAILED_MS)) }
                log("skipped", udn, renderer.name, location, reason = "not a usable renderer")
                if (retryAttempt != null) logRetry("failed", udn, renderer.name, location, null, retryAttempt, "skipped")
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                // A cancelled setup is not a failure: no skip entry is written or changed. A renderer that never got
                // into [running] is destroyed and its port freed; a registered one owns its port and is left alone.
                if (!registered) {
                    withContext(NonCancellable) {
                        runCatching { renderer?.destroy() }
                        if (holdsPort && port != null) ports.release(port)
                    }
                }
                throw e
            }
            withContext(NonCancellable) {
                runCatching { renderer?.destroy() }
                if (port != null) ports.release(port)
                lock.withLock { putSkip(udn, Skip(location, clock() + RETRY_FAILED_MS, src, age, retryAttempt ?: 0)) }
            }
            val message = e.message ?: e.javaClass.simpleName
            log("failed", udn, renderer?.name, location, port, reason = message)
            if (retryAttempt != null) logRetry("failed", udn, renderer?.name, location, port, retryAttempt, "failed", message)
        } finally {
            withContext(NonCancellable) { lock.withLock { pending.remove(udn) } }
        }
    }

    /**
     * Sets up again every speaker whose setup threw and whose 5 minutes are over, without waiting for a new
     * alive, and asks every running speaker to revive a receiver that stopped itself ([ManagedRenderer.reviveIfDue]).
     * A device that was refused (not a usable renderer), one an alive is setting up right now and one that already
     * runs are left alone. Called by the schedule every [RETRY_CHECK_MS] and by Bridge.housekeeping. Returns the
     * number of setup attempts made; receiver revivals are logged but not counted. A cancelled pass releases every
     * reservation it made, destroys any half-built renderer and does not change any skip entry, so the next pass
     * retries the same speakers at the same attempt number.
     */
    suspend fun retryDue(): Int {
        val due = lock.withLock {
            val now = clock()
            val picked = skipped.entries
                .filter { (udn, skip) ->
                    !isWaiting(skip, now) && skip.src != null &&
                        udn !in pending && udn !in running && !hidden(udn)
                }
                .map { (udn, skip) -> Triple(udn, skip, skip.attempts + 1) }
            picked.forEach { pending.add(it.first) }
            picked
        }
        if (due.isNotEmpty()) {
            val gate = Semaphore(RETRY_PARALLEL)
            coroutineScope {
                due.map { (udn, skip, attempt) ->
                    // ATOMIC: the body starts even when the scope is already cancelled, so the finally below always runs
                    async(start = CoroutineStart.ATOMIC) {
                        var entered = false
                        try {
                            gate.withPermit {
                                entered = true
                                val src = checkNotNull(skip.src) { "a skipped entry picked for retry always records its source" }
                                setUp(udn, skip.location, src, skip.maxAge, attempt)
                            }
                        } finally {
                            // setUp's own finally released the reservation when it was entered; otherwise do it here
                            if (!entered) withContext(NonCancellable) { lock.withLock { pending.remove(udn) } }
                        }
                    }
                }.awaitAll()
            }
        }
        reviveReceivers()
        return due.size
    }

    private suspend fun reviveReceivers() {
        val live = lock.withLock { running.values.toList() }
        for (r in live) {
            val outcome: Boolean? = try {
                r.reviveIfDue(clock())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logRetry("terminated", r.udn, r.name, r.location, r.port, null, "failed", e.message ?: e.javaClass.simpleName)
                continue
            }
            if (outcome != null) logRetry("terminated", r.udn, r.name, r.location, r.port, null, if (outcome) "running" else "failed")
        }
    }

    /**
     * index.js removeRenderer: take it out of the list, destroy it (bounded by `destroyBudgetMs`, see [destroyBounded]),
     * free its port (which stays remembered), log [event].
     */
    private suspend fun removeRenderer(udn: String, event: String = "removed", reason: String? = null) {
        val r = lock.withLock { running.remove(udn)?.also { publish() } } ?: return
        destroyBounded(r)
        log(event, udn, r.name, r.location, r.port, reason)
    }

    /**
     * Runs [ManagedRenderer.destroy] as a job of the registry's own scope and waits for it at most `destroyBudgetMs`.
     * The receiver's shutdown runs under NonCancellable (the lounge sessions are ended even when the caller is cancelled), so
     * a time limit cannot cut it; instead the removal stops waiting and the job finishes in the background. The port goes
     * back to the allocator when the destroy has really ended, never at the timeout, so a port whose DIAL listener may still
     * be open is not handed out.
     */
    private suspend fun destroyBounded(r: ManagedRenderer) {
        // The caller's dispatcher, so a test dispatcher keeps the job in its own (virtual) time; the Job is the registry's own.
        val job = destroyScope.launch(currentCoroutineContext()[ContinuationInterceptor] ?: Dispatchers.Default) {
            try {
                r.destroy()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // a renderer that fails to stop is gone from the list all the same
            } finally {
                ports.release(r.port)
            }
        }
        if (withTimeoutOrNull(destroyBudgetMs) { job.join() } == null) {
            log(
                "destroy-slow",
                r.udn,
                r.name,
                r.location,
                r.port,
                "the speaker did not finish stopping in $destroyBudgetMs ms; it ends in the background",
            )
        }
    }

    /**
     * Takes the running speaker [udn] out of the registry for the bridge (an offline or removed manual speaker):
     * it is destroyed (ssdp:byebye, DIAL server closed, the lounge session ended), its port freed, and [event] logged. Nothing
     * is sent to the speaker. Does nothing when no such speaker runs.
     */
    suspend fun remove(udn: String, event: String, reason: String?) = removeRenderer(udn, event, reason)

    /** Whether the speaker [udn] is in the registry now. */
    fun isRunning(udn: String): Boolean = _renderersFlow.value.any { it.udn == udn }

    /**
     * Takes the running speaker [udn] out of the cast menus. The caller has already put it in the hidden set, so
     * every guard refuses it from now on. With [clear] the speaker is first emptied (bounded by `clearBudgetMs`), else with
     * [stop] a playing speaker is told to Stop (bounded the same way); then the renderer is destroyed (ssdp:byebye, DIAL
     * server closed, the lounge session ended) and its port freed. Nothing else is touched: the other speakers keep playing.
     * False when no such speaker is running.
     */
    suspend fun hide(udn: String, stop: Boolean, clear: Boolean): Boolean {
        lock.withLock { running[udn] } ?: return false
        if (clear) clearOne(udn) else if (stop) stopOne(udn)
        removeRenderer(udn, "hidden")
        return true
    }

    /** index.js onByebye: a speaker that says goodbye is removed; an unknown UDN does nothing. */
    suspend fun onByebye(headers: Map<String, String>) {
        val udn = udnFromUsn(headers["USN"]) ?: return
        removeRenderer(udn)
    }

    /**
     * Records that a MediaRenderer search just ran, so the next prune knows the speakers had a chance to answer.
     * The schedule calls it after each of its searches; Bridge.housekeeping calls it after its own.
     */
    fun searched() {
        lastSearchAt = clock()
    }

    /** index.js prune: removes every renderer that has not been seen for maxAge x 1.5. */
    suspend fun prune() {
        val now = clock()
        val stale = lock.withLock { running.filterValues { it.isStale(now) }.mapValues { it.value.lastSeen } }
        for ((udn, lastSeen) in stale) {
            removeRenderer(udn, "stale", "not seen for ${(now - lastSeen) / 1000} s")
        }
    }

    /**
     * Shutdown (index.js shutdown): stop every receiver so they leave the cast menu. With [clearSpeakers] each
     * speaker is first emptied through [ManagedRenderer.clearSpeaker], bounded by `clearBudgetMs`, in
     * parallel with the others, and before its own destroy (a destroyed renderer no longer clears). A clear that
     * hangs or throws never keeps a speaker from being destroyed. Clear only on an explicit service Stop: a
     * rebind, a goodbye and a stale speaker leave the speaker alone ([onByebye] and [prune] never clear).
     *
     * With [stopSpeakers] and no [clearSpeakers], each speaker that is playing is told to Stop (switching the bridge
     * off ends playback) and keeps its track loaded; [stopOne] is bounded the same way. A speaker on another source gets no
     * Stop ([Player.stop] reaches doStop's another-source check).
     */
    suspend fun removeAll(clearSpeakers: Boolean = false, stopSpeakers: Boolean = false) {
        val udns = lock.withLock { running.keys.toList() }
        coroutineScope {
            udns.map { udn ->
                async {
                    if (clearSpeakers) clearOne(udn) else if (stopSpeakers) stopOne(udn)
                    runCatching { removeRenderer(udn) }
                }
            }.awaitAll()
        }
    }

    private suspend fun stopOne(udn: String) {
        val r = lock.withLock { running[udn] } ?: return
        val player = r as? Player ?: return
        try {
            if (withTimeoutOrNull(clearBudgetMs) { player.stop() } == null) {
                log("stop-timeout", udn, r.name, r.location, r.port, "the speaker did not finish stopping in $clearBudgetMs ms")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("stop-failed", udn, r.name, r.location, r.port, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun clearOne(udn: String) {
        val r = lock.withLock { running[udn] } ?: return
        try {
            if (withTimeoutOrNull(clearBudgetMs) { r.clearSpeaker() } == null) {
                // clearSpeaker returns Unit, so null means the budget ran out
                log("clear-timeout", udn, r.name, r.location, r.port, "the speaker did not finish clearing in $clearBudgetMs ms")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("clear-failed", udn, r.name, r.location, r.port, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * index.js main: [search] at start, 5 s later (catches devices that drop the first packet) and every
     * discoveryIntervalS; [prune] every pruneIntervalS. Cancel the returned job to stop all of it.
     */
    fun launchSchedules(scope: CoroutineScope, search: suspend () -> Boolean): Job = scope.launch {
        suspend fun safe(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("failed", "-", reason = "schedule: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        // every search the schedule starts counts as a fresh chance for the speakers to answer
        suspend fun searchNow() {
            var sent = false
            safe { sent = search() }
            if (sent) searched()
        }
        searchNow()
        launch {
            delay(5_000)
            searchNow()
        }
        launch {
            while (true) {
                delay(config.discoveryIntervalS * 1000L)
                searchNow()
            }
        }
        launch {
            while (true) {
                delay(RETRY_CHECK_MS)
                safe { retryDue() }
            }
        }
        launch {
            while (true) {
                delay(config.pruneIntervalS * 1000L)
                // After a sleep the phone's timers fire at once and every speaker looks stale although it simply could
                // not announce itself (wall-clock deltas after sleep). Search first, prune on the next round.
                val sinceSearch = clock() - lastSearchAt
                if (sinceSearch > config.discoveryIntervalS * 1000L * 2) {
                    log("prune-deferred", "-", reason = "last search ${sinceSearch / 1000} s ago")
                    searchNow()
                } else {
                    safe { prune() }
                }
            }
        }
    }
}
