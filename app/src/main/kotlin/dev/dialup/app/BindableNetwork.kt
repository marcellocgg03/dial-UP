// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The one rule shared by the bridge's bind and every start gate: the first Wi-Fi client network's IPv4 address and interface
 * name, else the phone's own tether (hotspot) interface, else null to null.
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
    for (network in cm.allNetworks) {
        val caps = cm.getNetworkCapabilities(network) ?: continue
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
        val props = cm.getLinkProperties(network) ?: continue
        val v4 = props.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()
        return v4 to props.interfaceName
    }
    // no Wi-Fi client address (the phone may be the hotspot provider): bind to the tether interface instead
    val tether = selectTether(tetherCandidates()) ?: return null to null
    return (InetAddress.getByName(tether.ipv4) as? Inet4Address) to tether.name
}

/** True when the bridge can bind now: a Wi-Fi client IPv4 address or the phone's own hotspot. */
fun hasBindableNetwork(context: Context): Boolean = bindableLink(context).first != null
