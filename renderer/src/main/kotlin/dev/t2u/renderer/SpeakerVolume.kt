// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]); the standard calls are renderer.js's.
package dev.t2u.renderer

import dev.t2u.lounge.Volume

/**
 * The one way this app reads and changes volume and mute: a speaker that [coordinatesGroup] is asked for the group's
 * (Sonos `GroupRenderingControl`), every other one for its own (standard UPnP `RenderingControl`).
 */
internal class SpeakerVolume(private val soap: SoapTransport, private val coordinatesGroup: () -> Boolean) {
    /** True when the speaker took the level. */
    suspend fun set(volume: Volume): Boolean {
        val mute = if (volume.muted) 1 else 0
        if (coordinatesGroup()) {
            // Sonos spreads the group level over the members by the ratios of the last snapshot: take them as they are now
            soap.safeCall(GROUP_RENDERING_CONTROL, "SnapshotGroupVolume", "InstanceID" to 0)
            val levelSet = soap.ok(GROUP_RENDERING_CONTROL, "SetGroupVolume", "InstanceID" to 0, "DesiredVolume" to volume.level)
            soap.safeCall(GROUP_RENDERING_CONTROL, "SetGroupMute", "InstanceID" to 0, "DesiredMute" to mute)
            return levelSet
        }
        val levelSet = soap.ok(RENDERING_CONTROL, "SetVolume", "InstanceID" to 0, "Channel" to "Master", "DesiredVolume" to volume.level)
        // SetMute is optional in RenderingControl; do not fail the whole call if it is absent.
        soap.safeCall(RENDERING_CONTROL, "SetMute", "InstanceID" to 0, "Channel" to "Master", "DesiredMute" to mute)
        return levelSet
    }

    /** The level (50 when the speaker does not answer) and mute. */
    suspend fun get(): Volume {
        val group = coordinatesGroup()
        val level = if (group) {
            soap.safeCall(GROUP_RENDERING_CONTROL, "GetGroupVolume", "InstanceID" to 0)
        } else {
            soap.safeCall(RENDERING_CONTROL, "GetVolume", "InstanceID" to 0, "Channel" to "Master")
        }
        val mute = if (group) {
            soap.safeCall(GROUP_RENDERING_CONTROL, "GetGroupMute", "InstanceID" to 0)
        } else {
            soap.safeCall(RENDERING_CONTROL, "GetMute", "InstanceID" to 0, "Channel" to "Master")
        }
        return Volume(
            level = if (level != null) level["CurrentVolume"]?.trim()?.toIntOrNull() ?: 0 else 50,
            muted = mute?.get("CurrentMute")?.trim()?.lowercase() in listOf("1", "true"),
        )
    }
}
