// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.content.Context

/**
 * Debug-build side of the diagnostics seam. The release source set has an object of the same name whose functions
 * return nothing, so a release APK carries no adb diagnostics (CR-02, D-06).
 */
object DebugFeatures {
    /** Debug-only features started next to the cast feature. */
    fun all(): List<SpikeFeature> = emptyList()

    /** Video id for the start-up warm-up resolve; null when there is none. */
    @Suppress("UNUSED_PARAMETER")
    fun warmUpVideoId(context: Context): String? = null
}
