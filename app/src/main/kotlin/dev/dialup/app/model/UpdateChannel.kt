// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

/**
 * Which releases the app update check follows. STABLE ignores the nightly builds; NIGHTLY follows the newest release of
 * any kind (a stable release newer than the newest nightly wins). App-only: not a config.json override.
 */
enum class UpdateChannel(val wire: String) {
    STABLE("stable"),
    NIGHTLY("nightly"),
    ;

    companion object {
        /** The channel for a wire name, or null for anything else (case-sensitive). */
        fun fromWire(s: String?): UpdateChannel? = entries.firstOrNull { it.wire == s }
    }
}
