package dev.t2u.renderer

// Contract between the renderer (port of renderer.js) and the discovery registry
// (port of the index.js onAlive/prune logic). GPL-3.0-only.

/**
 * One speaker, as the registry sees it. All members mirror renderer.js.
 *
 * Stale rule ([isStale], renderer.js `isStale`): `now - lastSeen > maxAge * 1000 * 1.5`. Many devices
 * re-announce exactly at max-age, hence the grace factor. [now] and [lastSeen] are epoch milliseconds.
 *
 * [init] (renderer.js `init`, index.js `onAlive`): fetch the description, verify AVTransport and start
 * the cast receiver. It returns false to skip this device for good (no AVTransport, filtered out) and
 * throws to have the registry retry later. [destroy] stops everything and must be safe to call after
 * a failed [init].
 */
interface ManagedRenderer {
    val udn: String
    val location: String

    /** IP address of the speaker, from the SSDP datagram that announced it. */
    val address: String

    /** The DIAL port this renderer's receiver listens on. */
    val port: Int

    /** The cast-menu name; null until [init] has read the description. */
    val name: String?

    /** Epoch milliseconds of the last SSDP alive/refresh. */
    val lastSeen: Long

    /** Record an SSDP re-announcement with the (possibly new) max-age in seconds. */
    fun refresh(maxAge: Int)

    fun isStale(now: Long): Boolean

    suspend fun init(): Boolean

    suspend fun destroy()

    /**
     * Empties the speaker's transport URI when it still holds a stream this app loaded. Bounded,
     * never throws and does nothing once destroyed. The default does nothing; the registry calls it only
     * on an explicit service Stop, before [destroy].
     */
    suspend fun clearSpeaker() {}

    /**
     * Brings the speaker's cast receiver back when it stopped itself (a lounge session failed for good) and its
     * time has come. Returns null when nothing is due, true when a new receiver is running and false when an attempt
     * failed. The registry calls it for every running speaker on its retry schedule. The default does nothing.
     */
    suspend fun reviveIfDue(now: Long): Boolean? = null

    /**
     * The phone moved to [host]: the renderer builds stream URLs with it from now on, still
     * recognises URLs with its earlier addresses as its own, and re-sets the loaded track on the speaker from the new
     * address. Returns without waiting for the re-set. Called by the bridge after its listeners moved. The default does nothing.
     */
    suspend fun onNetworkChanged(host: String) {}

    /**
     * The phone lost its network: no speaker poll runs until [onNetworkChanged] (what the speaker does with a broken
     * stream must not be mirrored to the phone). The default does nothing.
     */
    suspend fun onNetworkLost() {}
}

/** Creates the renderer for a newly seen speaker; the registry owns the port claim. */
fun interface RendererFactory {
    fun create(udn: String, location: String, address: String, maxAge: Int, port: Int): ManagedRenderer
}
