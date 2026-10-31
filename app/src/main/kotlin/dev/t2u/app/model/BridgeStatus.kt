// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.model

import dev.t2u.dial.DialAccessMode

/** What the header says about the bridge: running or not, the Wi-Fi address when known, and who may cast to it. */
data class BridgeStatus(val running: Boolean, val wifiIp: String?, val accessMode: DialAccessMode) {
    companion object {
        val OFF = BridgeStatus(false, null, DialAccessMode.PHONE)
    }
}

/** The one header line: "Bridge off", or "Running", the Wi-Fi address when known, and the access mode, joined by " · ". */
fun statusLine(s: BridgeStatus): String {
    if (!s.running) return "Bridge off"
    val parts = mutableListOf("Running")
    s.wifiIp?.let { parts += "Wi-Fi $it" }
    parts += if (s.accessMode == DialAccessMode.LAN) "Whole network" else "This phone only"
    return parts.joinToString(" · ")
}

/**
 * The header line with the reason when the bridge is not running (D-05): "Bridge off" when the switch is off,
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
