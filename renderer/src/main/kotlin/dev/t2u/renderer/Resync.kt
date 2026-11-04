// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// WIFI-02 (plan 07.1-02): what a renderer does with its speaker once the network is back. The speaker is read first and
// this pure function turns that one reading into an action, so the rule is one table that a plain test can pin.

/** What the TrackURI of a GetPositionInfo reply says about the stream the speaker holds. */
enum class UriKind {
    /** The URL is one this renderer served (its current or one of its last own addresses). */
    OURS,

    /** The URL points somewhere else: another source or app took the speaker. */
    FOREIGN,

    /** The reply carried no TrackURI. */
    EMPTY,

    /** The call failed, so nothing is known. */
    UNREAD,
}

/** The outcome of [resyncAction]. */
enum class ResyncAction {
    /** The renderer holds no track in a state worth re-syncing: only polling restarts. */
    NOTHING,

    /** The speaker still plays our stream on the same address: send nothing, resume polling. */
    KEEP,

    /** Re-set the track at the position the speaker itself reports (it still plays or is paused on our stream). */
    RESET_AT_SPEAKER,

    /** Re-set the track at the last polled position (the speaker's own RelTime cannot be trusted or read). */
    RESET_AT_LAST_POLLED,

    /** Another source took the speaker: send nothing and tell the sender the player stopped. */
    RELEASE_FOREIGN,

    /** The speaker is unreadable or still TRANSITIONING: read it again after one poll interval. */
    RETRY,
}

/** How many times the speaker is read after a network return before the last polled position is used. */
internal const val RESYNC_READ_ATTEMPTS = 3

/**
 * How long after a KEEP a mid-track stop is treated as the buffer running dry instead of a real stop. After a Wi-Fi loss
 * Android tears down the phone's sockets, so a speaker that still reads PLAYING may only be draining its buffer.
 */
internal const val RESYNC_WATCH_MS = 120_000L

/**
 * Decides what to do after the network returned, from one reading of the speaker.
 *
 * A speaker that is STOPPED (or has no media) is never re-set at its own RelTime: a Sonos that ran dry during the outage
 * reports 0:00, which would restart the track from the beginning (RESEARCH Pitfall 1). Those cases use the last polled
 * position. A FOREIGN stream wins over every readable transport state, so a source that took the speaker and stopped it is
 * not taken over again.
 *
 * @param holdsTrack the renderer has a current track and its status is PLAYING, PAUSED or LOADING.
 * @param ipChanged the phone's address differs from the one the stream URL was built with.
 * @param state the TransportState word, or null when it could not be read.
 * @param uri what the TrackURI says.
 * @param attempt the 1-based number of this reading.
 * @param maxAttempts how many readings are made before giving up on an unreadable speaker.
 */
fun resyncAction(
    holdsTrack: Boolean,
    ipChanged: Boolean,
    state: String?,
    uri: UriKind,
    attempt: Int,
    maxAttempts: Int = RESYNC_READ_ATTEMPTS,
): ResyncAction {
    if (!holdsTrack) return ResyncAction.NOTHING
    if (uri == UriKind.FOREIGN && state != null) return ResyncAction.RELEASE_FOREIGN
    if (state == null || state == "TRANSITIONING") {
        return if (attempt < maxAttempts) ResyncAction.RETRY else ResyncAction.RESET_AT_LAST_POLLED
    }
    if ((state == "PLAYING" || state == "PAUSED_PLAYBACK") && uri == UriKind.OURS) {
        return if (ipChanged) ResyncAction.RESET_AT_SPEAKER else ResyncAction.KEEP
    }
    return ResyncAction.RESET_AT_LAST_POLLED
}
