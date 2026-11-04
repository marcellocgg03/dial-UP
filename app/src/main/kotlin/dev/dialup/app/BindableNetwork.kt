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
 * and, only when there is no Wi-Fi client address, one NetworkInterface enumeration. That is the same work
 * [NetworkResumeReceiver]'s hasWifiAddress already does on the main thread, and BOOT_COMPLETED, the resume receiver and
 * onResume must decide synchronously anyway. The UI's continuous presence flow runs it on Dispatchers.IO instead.
 */
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
