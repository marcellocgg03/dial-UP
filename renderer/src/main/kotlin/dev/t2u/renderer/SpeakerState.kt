// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
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

/** What a speaker card's buttons can ask of a speaker (plan 07-08, GUI-02). There is deliberately no volume action (T-02-36). */
enum class SpeakerAction { PLAY, PAUSE, STOP, PREVIOUS, NEXT, CLEAR }

/**
 * Runs a card [action] on this speaker. A card command is a local command (no sender id): it goes through the same [Renderer]
 * player methods as the phone's commands, so the YouTube sender sees it as a normal state change. It never resets the player
 * (that path is the gated [Renderer.doClear] of the clear-when-cast-ends setting) and never changes the loudness. CLEAR is a
 * stop followed by [Renderer.clearSpeaker] whether or not that setting is on; that call leaves a speaker playing another
 * source alone (T-03-26). Returns whether the speaker did what was asked.
 */
suspend fun Renderer.perform(action: SpeakerAction): Boolean = when (action) {
    SpeakerAction.PLAY -> resume()
    SpeakerAction.PAUSE -> pause()
    SpeakerAction.STOP -> stop()
    SpeakerAction.PREVIOUS -> previous()
    SpeakerAction.NEXT -> next()
    SpeakerAction.CLEAR -> {
        stop()
        clearSpeaker()
        true
    }
}
