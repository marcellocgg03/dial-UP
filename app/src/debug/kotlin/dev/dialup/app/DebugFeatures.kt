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
    fun all(): List<SpikeFeature> = listOf(ResolverFeature(), MediaFeature())

    /**
     * Video id for the start-up warm-up resolve: the first non-blank, non-comment line of the asset
     * `spike/video-set.txt`, or null when the asset cannot be read.
     */
    fun warmUpVideoId(context: Context): String? =
        try {
            context.assets.open("spike/video-set.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            }
        } catch (_: Exception) {
            null
        }
}
