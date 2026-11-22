// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The one rule shared by the bridge's bind and every start gate: the first Wi-Fi client network that has an IPv4 address
 * (its address and interface name), else the phone's own tether (hotspot) interface, else null to null. A Wi-Fi network without
 * an IPv4 address never hides a later one that has an address, nor the hotspot.
 *
 * It may run on the main thread: it makes a few ConnectivityManager binder calls (allNetworks, capabilities, link properties)
 * and, only when there is no Wi-Fi client address, one NetworkInterface enumeration. The resume receiver, BOOT_COMPLETED
 * and onResume run it on the main thread because they must decide synchronously. The UI's continuous presence flow runs it on Dispatchers.IO instead.
 */
// ConnectivityManager.allNetworks is deprecated from API 31 but is the only synchronous way to list networks at minSdk 26;
// the replacement is a NetworkCallback (registerNetworkCallback), which cannot answer on demand.
@Suppress("DEPRECATION")
fun bindableLink(context: Context): Pair<Inet4Address?, String?> {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null to null
    // lazy: the binder calls stop at the first network with an address
    val wifi = cm.allNetworks.asSequence().mapNotNull { network ->
        val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
        val props = cm.getLinkProperties(network) ?: return@mapNotNull null
        val v4 = props.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()
        WifiLink(v4, props.interfaceName)
    }
    // no Wi-Fi client address (the phone may be the hotspot provider): bind to the tether interface instead
    return pickBindable(wifi) {
        selectTether(tetherCandidates())?.let { (InetAddress.getByName(it.ipv4) as? Inet4Address) to it.name }
    }
}

/** One Wi-Fi client network as bindableLink sees it: its first IPv4 address (null when it has none) and its interface name. */
data class WifiLink(val ipv4: Inet4Address?, val ifName: String?)

/** The choice rule, pure so the JVM can pin it: the first link with an IPv4 address, else the tether answer, else null to null. */
fun pickBindable(
    wifi: Sequence<WifiLink>,
    tether: () -> Pair<Inet4Address?, String?>?,
): Pair<Inet4Address?, String?> {
    val first = wifi.firstOrNull { it.ipv4 != null }
    if (first != null) return first.ipv4 to first.ifName
    return tether() ?: (null to null)
}

/** True when the bridge can bind now: a Wi-Fi client IPv4 address or the phone's own hotspot. */
fun hasBindableNetwork(context: Context): Boolean = bindableLink(context).first != null
