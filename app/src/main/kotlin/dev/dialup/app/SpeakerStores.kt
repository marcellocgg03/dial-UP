// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.content.Context
import dev.t2u.renderer.HiddenSpeakers
import dev.t2u.renderer.ManualSpeakers
import dev.t2u.renderer.RendererNames
import java.io.File

/**
 * Process-level speaker stores: they outlive any one bridge, so every bridge the cast feature builds shares them
 * and the Speakers tab can read and change them while the bridge is off. The files sit next to ports.json in `<filesDir>/cast`,
 * the Config dataDir.
 */
object SpeakerStores {
    class Instance(val hidden: HiddenSpeakers, val manual: ManualSpeakers, val names: RendererNames)

    @Volatile private var instance: Instance? = null

    fun of(context: Context): Instance {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val dir = File(context.applicationContext.filesDir, "cast")
            val hidden = HiddenSpeakers(dir) { line ->
                ServiceHub.service?.evidence?.event("feature-error", mapOf("where" to "hidden", "message" to line))
            }.load()
            // The speakers added by address, probed at every bridge start
            val manual = ManualSpeakers(dir) { line ->
                ServiceHub.service?.evidence?.event("feature-error", mapOf("where" to "manual", "message" to line))
            }.load()
            // GUI rename (261101-dcg): the names the author gave the renderers, by UDN
            val names = RendererNames(dir) { line ->
                ServiceHub.service?.evidence?.event("feature-error", mapOf("where" to "names", "message" to line))
            }.load()
            return Instance(hidden, manual, names).also { instance = it }
        }
    }
}
