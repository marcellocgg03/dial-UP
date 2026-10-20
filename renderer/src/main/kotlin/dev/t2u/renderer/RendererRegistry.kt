package dev.t2u.renderer

// Port of tube2upnp src/index.js (discovery lifecycle), GPL-3.0-only.
//
// One cast target per MediaRenderer UDN. The registry owns the maps index.js keeps at module level
// (renderers, skipped, pending) and the port claim; it creates speakers through a [RendererFactory]
// so it is tested with fakes and wired to the real Renderer by the composition root (plan 02-13).
//
// Concurrency: the maps are guarded by one Mutex that is never held across init() or destroy(), and
// the pending set guarantees one init per UDN however many alives arrive at once.

import dev.t2u.upnp.EventSink
import dev.t2u.upnp.NoEvents
import dev.t2u.upnp.isPrivateLanAddress
import dev.t2u.upnp.maxAge
import dev.t2u.upnp.udnFromUsn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.InetSocketAddress

private val RENDERER_TYPE_RE = Regex("urn:schemas-upnp-org:device:MediaRenderer:\\d")

/** Bound on the "already logged" memory so a flood of made-up UDNs cannot grow it without limit (T-02-41). */
private const val MAX_REMEMBERED_GUARD_HITS = 1024

class RendererRegistry(
    private val config: Config,
    private val ports: PortRegistry,
    private val factory: RendererFactory,
    private val events: EventSink = NoEvents,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** index.js RETRY_FAILED_MS: a device whose init threw is tried again after this long. */
        const val RETRY_FAILED_MS = 5 * 60 * 1000L
    }

    private class Skip(val location: String, val until: Long)

    private val lock = Mutex()
    private val running = LinkedHashMap<String, ManagedRenderer>()
    private val skipped = HashMap<String, Skip>()
    private val pending = HashSet<String>()
    private val guardLogged = HashSet<String>()

    private val _renderersFlow = MutableStateFlow<List<ManagedRenderer>>(emptyList())
    private val _count = MutableStateFlow(0)

    /** The running renderers, updated on every add and remove (plan 02-13 derives the D-06 cast count from it). */
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
     * (T-02-39: otherwise any device could make the app fetch an arbitrary URL). Names are refused
     * without a lookup. Returns null when the location is acceptable, else the reason.
     */
    private fun locationProblem(location: String, src: InetSocketAddress): String? {
        val srcAddress = src.address ?: return "no source address"
        val host = location.toHttpUrlOrNull()?.host ?: return "location is not a URL"
        val named = if (host.contains(':')) {
            // a name cannot contain ':', so this is an IPv6 literal and no lookup happens
            try { InetAddress.getByName(host) } catch (_: Exception) { null }
        } else if (host.split('.').let { p -> p.size == 4 && p.all { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } } }) {
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

        val age = maxAge(headers["CACHECONTROL"], config.defaultMaxAge)

        val existing = lock.withLock { running[udn] }
        if (existing != null) {
            if (existing.location == location) {
                existing.refresh(age)
                return
            }
        }

        val go = lock.withLock {
            val skip = skipped[udn]
            if (skip != null && skip.location == location && skip.until > clock()) return@withLock false
            pending.add(udn)
        }
        if (!go) return

        var port: Int? = null
        var renderer: ManagedRenderer? = null
        try {
            port = ports.claim(udn)
            renderer = factory.create(udn, location, src.address.hostAddress.orEmpty(), age, port)
            if (renderer.init()) {
                lock.withLock {
                    running[udn] = renderer
                    skipped.remove(udn)
                    publish()
                }
                ports.remember(udn, port)
                log("added", udn, renderer.name, location, port)
            } else {
                ports.release(port)
                lock.withLock { skipped[udn] = Skip(location, Long.MAX_VALUE) }
                log("skipped", udn, renderer.name, location, reason = "not a usable renderer")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            withContext(NonCancellable) {
                runCatching { renderer?.destroy() }
                if (port != null) ports.release(port)
                lock.withLock { skipped[udn] = Skip(location, clock() + RETRY_FAILED_MS) }
            }
            log("failed", udn, renderer?.name, location, port, reason = e.message ?: e.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) { lock.withLock { pending.remove(udn) } }
        }
    }
}
