// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.content.Context

/**
 * Release-build side of the diagnostics seam: release builds ship no diagnostics, no adb command channel and no
 * start-up lookup against YouTube (CR-02, D-06). The debug source set has an object of the same name that provides them.
 */
object DebugFeatures {
    fun all(): List<SpikeFeature> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun warmUpVideoId(context: Context): String? = null
}
