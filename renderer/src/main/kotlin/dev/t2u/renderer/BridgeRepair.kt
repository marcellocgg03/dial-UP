package dev.t2u.renderer

// Housekeeping and component repair of Bridge (the responder, the SSDP client, the media server and the DIAL servers),
// GPL-3.0-only. Extracted from Bridge, bodies unchanged. Bridge stays the composition root: it owns the components, the
// started/stopped flags, networkDown and [netLock]; this class reads and replaces the components through
// [BridgeRepairHost] at the moment of use and takes the same Mutex instance, so each repair runs under the lock it ran
// under before.

import dev.t2u.dial.DialAccessPolicy
import dev.t2u.lounge.YouTubeCastReceiver
import dev.t2u.media.MediaServer
import dev.t2u.upnp.ProbeResult
import dev.t2u.upnp.Ssdp
import dev.t2u.upnp.SsdpClientEvents
import dev.t2u.upnp.SsdpDevice
import dev.t2u.upnp.SsdpResponder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** What [BridgeRepair] reads from and replaces in the bridge that owns it. Every member is read when used, never cached. */
internal interface BridgeRepairHost {
    val stopped: AtomicBoolean
    val networkDown: Boolean
    val net: BridgeNetwork
    val registry: RendererRegistry?
    val accessPolicy: DialAccessPolicy?
    val mediaServer: MediaServer?

    /** Every receiver the bridge built and still tracks, for the DIAL repair. */
    val receivers: List<YouTubeCastReceiver>

    /** What the receivers advertised, by uuid. */
    val advertisedDevices: Map<String, SsdpDevice>

    /** The SSDP responder; a repair stops it, and a rebuilt one is assigned. */
    var responder: SsdpResponder?

    /** The SSDP client; a repair stops it, and a rebuilt one is assigned. */
    var ssdp: Ssdp?

    fun buildResponder(network: BridgeNetwork, policy: DialAccessPolicy): SsdpResponder

    fun clientEvents(reg: RendererRegistry): SsdpClientEvents

    suspend fun multicastSearch(trigger: String, client: Ssdp): Boolean

    suspend fun step(name: String, phase: String, block: suspend () -> Unit)

    fun emit(kind: String, vararg fields: Pair<String, Any?>)

    fun failure(where: String, t: Throwable)
}

internal class BridgeRepair(private val host: BridgeRepairHost, private val deps: BridgeDeps, private val netLock: Mutex) {
    private val stopped: AtomicBoolean get() = host.stopped
    private val networkDown: Boolean get() = host.networkDown
    private val net: BridgeNetwork get() = host.net
    private val registry: RendererRegistry? get() = host.registry
    private val accessPolicy: DialAccessPolicy? get() = host.accessPolicy
    private val mediaServer: MediaServer? get() = host.mediaServer
    private val advertisedDevices: Map<String, SsdpDevice> get() = host.advertisedDevices
    private var responder: SsdpResponder?
        get() = host.responder
        set(value) {
            host.responder = value
        }
    private var ssdp: Ssdp?
        get() = host.ssdp
        set(value) {
            host.ssdp = value
        }

    private fun buildResponder(network: BridgeNetwork, policy: DialAccessPolicy) = host.buildResponder(network, policy)

    private fun clientEvents(reg: RendererRegistry) = host.clientEvents(reg)

    private suspend fun multicastSearch(trigger: String, client: Ssdp) = host.multicastSearch(trigger, client)

    private suspend fun step(name: String, phase: String, block: suspend () -> Unit) = host.step(name, phase, block)

    private fun emit(kind: String, vararg fields: Pair<String, Any?>) = host.emit(kind, *fields)

    private fun failure(where: String, t: Throwable) = host.failure(where, t)

    // Evidence for the device session: did anything reach the app after the network came back? M-SEARCH
    // datagrams (the own liveness probe excluded) and DIAL HTTP requests counted since the last housekeeping event that took
    // them, and when the last of each arrived. Counts and times only: no address, name or payload.
    private val msearchRx = AtomicLong(0)
    private val dialRx = AtomicLong(0)

    @Volatile private var lastMsearchAt: Long? = null

    @Volatile private var lastDialAt: Long? = null

    /** An M-SEARCH from the network reached the responder (the own liveness probe is not counted by the caller). */
    fun noteMsearch() {
        msearchRx.incrementAndGet()
        lastMsearchAt = deps.clock()
    }

    /** A DIAL HTTP request reached a receiver. */
    fun noteDial() {
        dialRx.incrementAndGet()
        lastDialAt = deps.clock()
    }

    /**
     * The inbound evidence fields for a housekeeping event: M-SEARCH and DIAL request counts since the previous call (which
     * resets them) and how long ago the last of each arrived (null: never since the bridge started).
     */
    fun inboundEvidence(): Array<Pair<String, Any?>> {
        val now = deps.clock()
        return arrayOf(
            "msearchRx" to msearchRx.getAndSet(0),
            "dialRx" to dialRx.getAndSet(0),
            "lastMsearchAgoMs" to lastMsearchAt?.let { now - it },
            "lastDialAgoMs" to lastDialAt?.let { now - it },
        )
    }

    /** False only when a supplier is set and reports no Wi-Fi right now; a supplier that throws counts as present. */
    fun wifiPresent(): Boolean {
        val supplier = deps.currentNetwork ?: return true
        return try {
            supplier() != null
        } catch (_: Exception) {
            true
        }
    }

    suspend fun runHousekeeping(trigger: String): Boolean {
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
        // the registry defers its first prune after a long sleep until a search ran
        if (searched) registry?.searched()
        if (accessPolicy?.announceAllowed() == true) {
            withContext(Dispatchers.IO) { responder?.announce() }
            emit("reannounce", "mode" to "lan", "trigger" to trigger, "devices" to registry?.renderers().orEmpty().size)
        } else {
            emit("reannounce", "mode" to "phone", "skipped" to "phone-only", "trigger" to trigger)
        }
        // speakers whose setup failed or whose receiver stopped itself: the timer may have slept through their time
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
     * Proves the responder's own listener still receives and repairs what reopening cannot heal. The
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
     * it (never in phone-only mode). Skipped when the system address differs from the bridge's. The caller
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

    /**
     * Rebinds a media server that is running but not listening (a move whose bind kept failing) on the bridge's own
     * address. Does nothing when there is no media server or it is listening; skipped when the system address differs from the
     * bridge's (that is the rebind's job). The caller holds [netLock]. Returns true when a rebind was attempted.
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
        val missing = host.receivers.filter { it.dialMissing() }
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
}
