// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.renderer

import dev.t2u.lounge.PlayerStatus

/** Where the speaker is in the track: [positionSec] of [durationSec], as of [atMs] on the renderer's clock. */
data class SpeakerPosition(val positionSec: Double, val durationSec: Double, val atMs: Long)

/**
 * The immutable UI snapshot of one speaker (plan 07-03, GUI-01): what the Speakers list shows and nothing else. It never
 * carries stream URLs, lounge tokens or pairing codes (T-07-01). [online] and [manual] are for manually added speakers
 * (plans 07-12 to 07-14); a speaker found by discovery is online and not manual.
 */
data class SpeakerUi(
    val udn: String,
    val name: String,
    val address: String,
    val status: PlayerStatus,
    val track: NowPlaying?,
    val error: String?,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val queueHasCurrent: Boolean,
    val position: SpeakerPosition?,
    val online: Boolean = true,
    val manual: Boolean = false,
)
