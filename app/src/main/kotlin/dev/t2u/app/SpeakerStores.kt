// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app

import android.content.Context
import dev.t2u.renderer.HiddenSpeakers
import dev.t2u.renderer.ManualSpeakers
import java.io.File

/**
 * Process-level speaker stores (GUI-03, D-08): they outlive any one bridge, so every bridge the cast feature builds shares them
 * and the Speakers tab can read and change them while the bridge is off. The files sit next to ports.json in `<filesDir>/cast`,
 * the Config dataDir.
 */
object SpeakerStores {
    class Instance(val hidden: HiddenSpeakers, val manual: ManualSpeakers)

    @Volatile private var instance: Instance? = null

    fun of(context: Context): Instance {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val dir = File(context.applicationContext.filesDir, "cast")
            val hidden = HiddenSpeakers(dir) { line ->
                ServiceHub.service?.evidence?.event("feature-error", mapOf("where" to "hidden", "message" to line))
            }.load()
            // GUI-04 / D-06: the speakers added by address, probed at every bridge start
            val manual = ManualSpeakers(dir) { line ->
                ServiceHub.service?.evidence?.event("feature-error", mapOf("where" to "manual", "message" to line))
            }.load()
            return Instance(hidden, manual).also { instance = it }
        }
    }
}
