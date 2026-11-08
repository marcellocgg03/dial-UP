// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import dev.t2u.lounge.PlayerStatus
import dev.t2u.renderer.NowPlaying
import dev.t2u.renderer.SpeakerPosition
import dev.t2u.renderer.SpeakerUi
import java.util.Locale

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

/** The loaded format, not a queued next track or the selected playback mode. */
fun audioFormatLabel(speaker: SpeakerUi, enabled: Boolean): String? {
    if (!enabled || !speaker.online) return null
    if (speaker.status != PlayerStatus.PLAYING && speaker.status != PlayerStatus.PAUSED &&
        !(speaker.status == PlayerStatus.STOPPED && speaker.queueHasCurrent)
    ) return null
    val track = speaker.track ?: return null
    val format = when (track.format) {
        "adts" -> "AAC (ADTS)"
        "mp3" -> "MP3"
        "mp4" -> "MP4 (cached)"
        "direct" -> when (track.sourceMime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
            "audio/mp4", "video/mp4" -> "Original audio (MP4)"
            "audio/webm", "video/webm" -> "Original audio (WebM)"
            "audio/ogg", "application/ogg" -> "Original audio (Ogg)"
            "audio/mpeg" -> "Original audio (MP3)"
            else -> "Original audio"
        }
        else -> return null
    }
    return "Audio: $format"
}

/**
 * Where the track is at [nowMs], from the last reported [p]: the report plus the elapsed time while [playing], clamped to the
 * duration when there is one. A clock before the report gives the reported position (no extra SOAP calls).
 */
fun positionAt(p: SpeakerPosition, nowMs: Long, playing: Boolean): Double {
    if (!playing || nowMs <= p.atMs) return p.positionSec
    val moved = p.positionSec + (nowMs - p.atMs) / 1000.0
    return if (p.durationSec > 0.0) minOf(moved, p.durationSec) else moved
}

/** "1:05 / 3:42", "1:02:03 / 1:06:40", or just "1:05" when the duration is unknown. */
fun progressText(positionSec: Double, durationSec: Double): String =
    if (durationSec > 0.0) "${clock(positionSec)} / ${clock(durationSec)}" else clock(positionSec)

/** The bar fill 0..1, or null when there is no duration to measure against. */
fun progressFraction(positionSec: Double, durationSec: Double): Float? =
    if (durationSec > 0.0) (positionSec / durationSec).toFloat().coerceIn(0f, 1f) else null

private fun clock(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}
