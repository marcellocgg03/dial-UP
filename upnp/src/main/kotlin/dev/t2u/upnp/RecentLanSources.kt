package dev.t2u.upnp

import java.net.InetAddress

/**
 * The private LAN addresses that talked to the DIAL receivers lately: an SSDP M-SEARCH or a DIAL HTTP request. A lounge sender
 * that connects does not say where it is, but the device usually searched for the speaker moments before, so a recent source
 * from another device is the sign that a connecting sender is a guest on this LAN rather than this phone's own app.
 *
 * Only private LAN addresses that pass [accept] are kept ([accept] is the access policy's "not this phone, not loopback" test,
 * so the phone's own addresses are never stored). An entry older than [windowMs] counts for nothing. At most [maxEntries] are
 * kept; a new address at the limit drops the oldest. Thread-safe; the clock is injectable.
 */
class RecentLanSources(
    private val accept: (String) -> Boolean,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val seen = LinkedHashMap<String, Long>()

    /** Remembers [remote] (an address literal as the HTTP engine or the datagram reports it) when it qualifies. Never throws. */
    fun note(remote: String) {
        val address = literal(remote) ?: return
        if (!isPrivateLanAddress(address)) return
        val key = address.hostAddress.orEmpty()
        if (!accept(key)) return
        record(key)
    }

    /** Same as [note] for a datagram source. */
    fun note(address: InetAddress) = note(address.hostAddress.orEmpty())

    /** The most recently seen qualifying address within the window, or null when there is none. */
    fun mostRecent(): String? = synchronized(seen) {
        val now = clockMs()
        seen.values.removeAll { now - it >= windowMs }
        seen.entries.filter { accept(it.key) }.maxByOrNull { it.value }?.key
    }

    /** How many addresses are held (for tests and the size cap). */
    fun size(): Int = synchronized(seen) { seen.size }

    private fun record(key: String) = synchronized(seen) {
        val now = clockMs()
        seen.remove(key)
        if (seen.size >= maxEntries) {
            seen.values.removeAll { now - it >= windowMs }
            while (seen.size >= maxEntries) seen.remove(seen.keys.first())
        }
        seen[key] = now
    }

    /** An IP literal only: a host name would mean a DNS lookup, and none of these sources is ever a name. */
    private fun literal(raw: String): InetAddress? {
        val s = raw.trim().removePrefix("[").removeSuffix("]")
        parseIpv4Literal(s)?.let { return it }
        if (!s.contains(':') || !s.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }) return null
        return try {
            InetAddress.getByName(s)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 3 * 60 * 1000L
        const val DEFAULT_MAX_ENTRIES = 64
    }
}
