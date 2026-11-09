package dev.dialup.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.Inet4Address

/**
 * Logs every Wi-Fi network event as a `net` evidence line and calls [onChange] (with the event name)
 * whenever the phone's current Wi-Fi IPv4 address (a [WifiAddressTracker] over all Wi-Fi networks, so the loss of a
 * network that is not the current one changes nothing) differs from the last one seen, including going
 * to none and coming back (without this, matrix row 6 would measure the spike and not Android).
 * [seed] sets the address the owner is currently bound to so the initial callbacks cause no rebind.
 */
class NetworkMonitor(
    private val context: Context,
    private val evidence: EvidenceLog,
    private val tetherProbe: () -> String? = { null },
    private val onChange: (String) -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private val tracker = WifiAddressTracker()
    private var registered = false
    private var tetherTimer: java.util.Timer? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val props = cm?.getLinkProperties(network)
            // link properties usually arrive in onLinkPropertiesChanged right after; judge then if absent
            val changed = props != null && update(network.networkHandle, ipv4(props))
            log("available", network, props)
            if (changed) onChange("available")
        }

        override fun onLost(network: Network) {
            val changed = synchronized(lock) { tracker.onLost(network.networkHandle) }
            log("lost", network, null)
            if (changed) onChange("lost")
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val changed = update(network.networkHandle, ipv4(linkProperties))
            log("linkPropertiesChanged", network, linkProperties)
            if (changed) onChange("linkPropertiesChanged")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // not logged: fires constantly with signal strength changes
        }
    }

    fun seed(ip: String?) {
        synchronized(lock) { tracker.seed(ip) }
    }

    /** The Wi-Fi client address, or the tether address the probe found; null when the phone has neither. */
    fun currentIp(): String? = synchronized(lock) { tracker.currentIp }

    fun start() {
        val manager = cm ?: return
        if (registered) return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        manager.registerNetworkCallback(request, callback)
        registered = true
        // a hotspot starting or stopping raises no Wi-Fi network callback, so look for the tether address now and then
        tetherTimer = java.util.Timer("tether-probe", true).also { timer ->
            timer.schedule(object : java.util.TimerTask() {
                override fun run() {
                    val changed = runCatching { synchronized(lock) { tracker.onFallback(tetherProbe()) } }.getOrDefault(false)
                    if (changed) {
                        evidence.event("net", mapOf("event" to "tether", "tracked" to synchronized(lock) { tracker.currentIp }))
                        onChange("tether")
                    }
                }
            }, TETHER_POLL_MS, TETHER_POLL_MS)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        tetherTimer?.cancel()
        tetherTimer = null
        runCatching { cm?.unregisterNetworkCallback(callback) }
    }

    /**
     * True as soon as a Wi-Fi network has NET_CAPABILITY_VALIDATED (checked every 500 ms), false at [timeoutMs]. A LAN
     * without internet never validates, so the caller moves anyway after the timeout.
     */
    suspend fun awaitValidated(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val manager = cm ?: return@withContext false
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var validated = wifiValidated(manager)
        while (!validated && System.nanoTime() < deadline) {
            delay(VALIDATED_POLL_MS)
            validated = wifiValidated(manager)
        }
        validated
    }

    private fun wifiValidated(manager: ConnectivityManager): Boolean = manager.allNetworks.any { network ->
        val caps = manager.getNetworkCapabilities(network)
        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun ipv4(props: LinkProperties): String? =
        props.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress

    private fun log(event: String, network: Network, props: LinkProperties?) {
        evidence.event(
            "net",
            mapOf(
                "event" to event,
                "network" to network.networkHandle,
                "iface" to props?.interfaceName,
                "ipv4" to props?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.map { it.hostAddress },
                "tracked" to synchronized(lock) { tracker.tracked },
            ),
        )
    }

    private fun update(handle: Long, ip: String?): Boolean = synchronized(lock) { tracker.onReport(handle, ip) }

    private companion object {
        const val VALIDATED_POLL_MS = 500L
        const val TETHER_POLL_MS = 5_000L
    }
}
