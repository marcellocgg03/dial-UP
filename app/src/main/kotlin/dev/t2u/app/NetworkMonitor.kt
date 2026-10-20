package dev.t2u.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address

/**
 * Logs every Wi-Fi network event as a `net` evidence line and calls [onChange] (with the event name)
 * whenever the Wi-Fi IPv4 address differs from the last one seen, including going to none and coming
 * back (RESEARCH Pitfall 9: without this, matrix row 6 would measure the spike and not Android).
 * [seed] sets the address the owner is currently bound to so the initial callbacks cause no rebind.
 */
class NetworkMonitor(
    private val context: Context,
    private val evidence: EvidenceLog,
    private val onChange: (String) -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var lastIp: String? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val props = cm?.getLinkProperties(network)
            log("available", network, props)
            // link properties usually arrive in onLinkPropertiesChanged right after; judge then if absent
            if (props != null) evaluate("available", ipv4(props))
        }

        override fun onLost(network: Network) {
            log("lost", network, null)
            evaluate("lost", null)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            log("linkPropertiesChanged", network, linkProperties)
            evaluate("linkPropertiesChanged", ipv4(linkProperties))
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // not logged: fires constantly with signal strength changes
        }
    }

    fun seed(ip: String?) {
        synchronized(lock) { lastIp = ip }
    }

    fun start() {
        val manager = cm ?: return
        if (registered) return
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        manager.registerNetworkCallback(request, callback)
        registered = true
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { cm?.unregisterNetworkCallback(callback) }
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
            ),
        )
    }

    private fun evaluate(event: String, ip: String?) {
        val changed = synchronized(lock) {
            if (ip == lastIp) false else { lastIp = ip; true }
        }
        if (changed) onChange(event)
    }
}
