// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import dev.t2u.dial.DialAccessMode

/** The bridge state the cast feature reports: running or not, and who may cast to it. */
data class BridgeStatus(val running: Boolean, val accessMode: DialAccessMode) {
    companion object {
        val OFF = BridgeStatus(false, DialAccessMode.PHONE)
    }
}

/** The only text the header shows under its title. */
const val WAITING_FOR_WIFI = "Waiting for Wi-Fi"

/**
 * True when the switch is on and the exemption is held, so the bridge would run, but the phone has no Wi-Fi or hotspot address
 * ([wifiPresent] false). The service stops itself on Wi-Fi loss, so this does not depend on the service.
 */
fun waitingForWifi(enabled: Boolean, exempt: Boolean, wifiPresent: Boolean): Boolean = enabled && exempt && !wifiPresent

/**
 * The header's only subtitle: [WAITING_FOR_WIFI] when [waitingForWifi], else "" (no subtitle). "Running", the access
 * mode and the other status texts are not shown any more: the switch and the battery banner
 * say the rest.
 */
fun statusLine(enabled: Boolean, exempt: Boolean, wifiPresent: Boolean): String =
    if (waitingForWifi(enabled, exempt, wifiPresent)) WAITING_FOR_WIFI else ""
