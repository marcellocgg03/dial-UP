package dev.t2u.renderer

// The renderers saved by IP address (manual.json), GPL-3.0-only: adding one by address, probing the saved ones, watching
// whether they still answer, following one that moved, describing and removing them. Extracted from Bridge, bodies
// unchanged. Bridge stays the composition root: it owns the registry, the SSDP client and the flags, and this class reads
// them through [ManualSpeakerHost] at the moment of use, so a component a rebind replaced is never held stale.

import dev.t2u.upnp.RENDERER_ST
import dev.t2u.upnp.Ssdp
import dev.t2u.upnp.fetchDescription
import dev.t2u.upnp.parseIpv4Literal
import dev.t2u.upnp.udnFromUsn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** What [ManualSpeakerCoordinator] reads from the bridge that owns it. Every member is read when used, never cached. */
internal interface ManualSpeakerHost {
    val registry: RendererRegistry?
    val ssdp: Ssdp?
    val started: AtomicBoolean
    val stopped: AtomicBoolean
    val networkDown: Boolean
    val handlerScope: CoroutineScope

    fun emit(kind: String, vararg fields: Pair<String, Any?>)

    fun failure(where: String, t: Throwable)
}

internal class ManualSpeakerCoordinator(private val host: ManualSpeakerHost, private val deps: BridgeDeps) {
    private val registry: RendererRegistry? get() = host.registry
    private val ssdp: Ssdp? get() = host.ssdp
    private val started: AtomicBoolean get() = host.started
    private val stopped: AtomicBoolean get() = host.stopped
    private val networkDown: Boolean get() = host.networkDown
    private val handlerScope: CoroutineScope get() = host.handlerScope

    private fun emit(kind: String, vararg fields: Pair<String, Any?>) = host.emit(kind, *fields)

    private fun failure(where: String, t: Throwable) = host.failure(where, t)

    /** The last search reply from each source address: its UDN and when it came. [addManual] reads it; capped, cleared on stop. */
    private class SourceReply(val udn: String, val at: Long)

    private val replySources = ConcurrentHashMap<String, SourceReply>()

    /**
     * Records a reply from [address]. A full table evicts its oldest entry whose address is not a saved manual address (a chatty
     * LAN must not make a saved speaker look silent); when every entry is a saved address nothing is
     * evicted and the table stays at most [MAX_MANUAL] over the cap. [Bridge.noteReply] forwards here so a test can drive it.
     */
    fun noteReply(address: String, udn: String) {
        if (replySources.size >= MANUAL_REPLY_CAP && !replySources.containsKey(address)) {
            val saved = deps.manual?.entries?.value.orEmpty().map { it.ip }.toSet()
            replySources.entries.filter { it.key !in saved }.minByOrNull { it.value.at }?.let { replySources.remove(it.key) }
        }
        replySources[address] = SourceReply(udn, System.currentTimeMillis())
    }

    /** When [address] last replied, for tests; null when the table holds nothing for it. */
    fun replySourceAt(address: String): Long? = replySources[address]?.at

    /** Forgets every recorded reply (the bridge stopped). */
    fun clearReplies() = replySources.clear()

    /** See [Bridge.addManual], which forwards here. */
    suspend fun addManual(ip: InetAddress): ManualAddResult {
        val outcome = probeManual(ip)
        saveManual(ip, outcome.result)
        emit("manual-add", "ip" to ip.hostAddress, "result" to outcome.result.javaClass.simpleName, "via" to outcome.via)
        return outcome.result
    }

    /** What [probeManual] came to and which probe path produced the answer: `ssdp` (unicast search) or `http` (description). */
    private class ProbeOutcome(val result: ManualAddResult, val via: String)

    /** A speaker that was added or was already listed is remembered; every other outcome leaves nothing behind. */
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
     * Asks every saved speaker directly, as the first add did. Each address passes [parseIpv4Literal] and
     * [manualTargetAllowed] again, so a damaged or edited file cannot aim the bridge at a public address. The replies
     * reach the registry like any search reply; a speaker that does not answer simply has no card until it does.
     */
    fun probeSavedSpeakers(client: Ssdp, reg: RendererRegistry) {
        val saved = deps.manual?.entries?.value.orEmpty()
        if (saved.isEmpty()) return
        handlerScope.launch {
            // Besides the unicast search, each saved speaker's description is fetched, because a Sonos ignores the search
            for (entry in saved.take(MAX_MANUAL)) {
                launch { sendManualProbe(client, entry) }
                launch { describeSaved(entry, reg) }
            }
        }
    }

    /** One unicast search to a saved speaker, after the same two address checks as the first add. Never throws. */
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
     * Once per [BridgeDeps.manualProbeIntervalMs] decides for every saved speaker whether it
     * answered since the previous round (any search reply or NOTIFY from its address counts, see [noteReply]); a speaker that
     * missed two rounds in a row is removed from the registry, so it stops being a cast target, and a later answer sets it up
     * again through the registry's ordinary alive path. Then every saved speaker is asked again, which is also what keeps a
     * speaker that only answers unicast from being pruned after 45 minutes. A round is skipped while the network is down
     * (nothing could have answered). The client is read from the field, so a rebind's replacement is followed. [since] is when
     * the start-up probe went out. At most [MAX_MANUAL] datagrams per round.
     *
     * A saved speaker whose UDN is running in the registry at another address than the saved one has
     * moved (new subnet or DHCP lease) and discovery already found it there. Its saved address is replaced by the live one
     * (`manual-moved`, manual.json rewritten, keyed by UDN) at the start of the round, and the old address gets no verdict,
     * because it would never answer and would drop the live renderer. When the live address is not a private LAN address or the
     * store refuses it, nothing is adopted but the entry is still not judged. A UDN that is not running keeps the old
     * behaviour: two missed rounds, then `manual-offline`.
     */
    fun watchManualSpeakers(reg: RendererRegistry, since: Long) {
        val store = deps.manual ?: return
        handlerScope.launch {
            val liveness = HashMap<String, ManualLiveness>()
            val offline = HashSet<String>()
            var last = since
            while (true) {
                delay(deps.manualProbeIntervalMs)
                if (networkDown) continue
                // Entries whose renderer is running at another address are moved to it (or at least not judged) first
                val movedAway = adoptMovedSpeakers(reg, store)
                val saved = store.entries.value
                val udns = saved.map { it.udn }.toSet()
                liveness.keys.retainAll(udns)
                offline.retainAll(udns)
                for (udn in movedAway) liveness.remove(udn)
                // The description fetches finish before the verdicts, so an HTTP answer in this round counts
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
     * For every saved speaker whose UDN runs in [reg] at an address other than the saved one, saves the live address
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

    /** See [Bridge.removeManual], which forwards here. */
    suspend fun removeManual(udn: String): Boolean {
        val store = deps.manual ?: return false
        if (!store.remove(udn)) return false
        // Nothing of the renderer stays behind; a hidden one is un-hidden, its user name is dropped
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
            // A speaker that already has a card needs no probe; it also may not answer a unicast search at all
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
     * The HTTP fallback for a speaker that ignores a unicast search (a Sonos speaker). Fetches the device
     * description at [BridgeDeps.manualDescriptionUrl] for [ip], only when [descriptionUrlAllowed] accepts the URL, and
     * feeds it to the registry as the alive headers an SSDP reply would have carried: `LOCATION` is the URL just fetched, so the
     * registry's host-equals-sender, private LAN, hidden and excluded-server guards apply unchanged. The `SERVER` header
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
     * The liveness signal for a saved manual speaker that never answers a unicast search (a Sonos
     * speaker). Fetches the speaker's description (the saved `location` when [descriptionUrlAllowed] holds for the re-validated
     * saved address, otherwise [BridgeDeps.manualDescriptionUrl]) and counts it as an answer only when the document's UDN
     * equals the saved one, letter case ignored because a Sonos serves it in upper case and the saved one is lower case;
     * the registry then refreshes the running renderer or sets it up. A failure is one `manual-probe` event
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
}
