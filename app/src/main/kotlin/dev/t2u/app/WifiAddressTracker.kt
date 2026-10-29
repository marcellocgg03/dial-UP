package dev.t2u.app

// Plan 05-16 (review WR-04): per-network Wi-Fi address tracking, pure Kotlin so the JVM tests cover it. GPL-3.0-only.

/**
 * Remembers the IPv4 address each known Wi-Fi network reported and derives the phone's current Wi-Fi address from them:
 * the address of the most recently reported network that has one, or null when there is none.
 *
 * Why per network: NetworkMonitor is registered for every Wi-Fi network. During a make-before-break handover (the new
 * network appears, then the old one is lost) or when a second network kept for validation goes away, "a network was
 * lost" does not mean "the phone has no address". Treating it that way froze the renderers and rebound the bridge for
 * nothing (review WR-04). Here a loss only changes the answer when the lost network was the one supplying it.
 *
 * "Changed" means different from the address this tracker last reported (initially [initial], later [seed]). Not
 * thread-safe: the monitor calls it under its own lock.
 */
class WifiAddressTracker(initial: String? = null) {
    // insertion order = report order, most recently reported last; a network without an IPv4 keeps its entry (null)
    private val networks = LinkedHashMap<Long, String?>()
    private var reported: String? = initial

    /** The address the tracker last reported (what the owner is bound to). */
    val currentIp: String? get() = reported

    /** How many Wi-Fi networks are known right now. */
    val tracked: Int get() = networks.size

    /** Sets the address the owner is bound to without touching the known networks. */
    fun seed(ip: String?) {
        reported = ip
    }

    /** A network reported [ip] (null: no IPv4). Returns true when the current address changed. */
    fun onReport(handle: Long, ip: String?): Boolean {
        networks.remove(handle)
        networks[handle] = ip
        return refresh()
    }

    /** A network went away. Ignores a handle never seen (returns false); otherwise true when the current address changed. */
    fun onLost(handle: Long): Boolean {
        if (!networks.containsKey(handle)) return false
        networks.remove(handle)
        return refresh()
    }

    private fun refresh(): Boolean {
        val now = networks.values.lastOrNull { it != null }
        if (now == reported) return false
        reported = now
        return true
    }
}
