// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import dev.t2u.dial.DialAccessMode

/** What the bridge card says about the bridge: running or not, and who may cast to it. */
data class BridgeStatus(val running: Boolean, val accessMode: DialAccessMode) {
    companion object {
        val OFF = BridgeStatus(false, DialAccessMode.PHONE)
    }
}

/** The one bridge card line: "Bridge off", or "Running" and the access mode, joined by " · ". */
fun statusLine(s: BridgeStatus): String {
    if (!s.running) return "Bridge off"
    val mode = if (s.accessMode == DialAccessMode.LAN) "Whole network" else "This phone only"
    return "Running · $mode"
}

/**
 * The bridge card line with the reason when the bridge is not running (D-05): "Bridge off" when the switch is off,
 * "Waiting for the battery exemption" when it is on but the exemption is missing, "Starting" before the service
 * is up, "Waiting for Wi-Fi" when the service is up but the bridge is not, otherwise the running text.
 */
fun statusLine(s: BridgeStatus, enabled: Boolean, exempt: Boolean, serviceUp: Boolean): String = when {
    !enabled -> "Bridge off"
    !exempt -> "Waiting for the battery exemption"
    !serviceUp -> "Starting"
    !s.running -> "Waiting for Wi-Fi"
    else -> statusLine(s)
}
