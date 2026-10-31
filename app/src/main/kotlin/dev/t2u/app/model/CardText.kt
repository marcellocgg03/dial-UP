// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.model

import dev.t2u.lounge.PlayerStatus
import dev.t2u.renderer.NowPlaying

/** The word a speaker card shows for the speaker's state (GUI-01). An offline speaker reads Offline whatever it last did. */
fun statusLabel(status: PlayerStatus, online: Boolean): String {
    if (!online) return "Offline"
    return when (status) {
        PlayerStatus.PLAYING -> "Playing"
        PlayerStatus.PAUSED -> "Paused"
        PlayerStatus.LOADING -> "Loading"
        PlayerStatus.STOPPED -> "Stopped"
        PlayerStatus.IDLE -> "Idle"
    }
}

/** The title line and the optional artist line of a card. A blank title shows the video id; a blank artist drops the line. */
fun trackLines(track: NowPlaying): Pair<String, String?> {
    val title = track.title.ifBlank { track.videoId }
    val artist = track.artist.takeIf { it.isNotBlank() }
    return title to artist
}
