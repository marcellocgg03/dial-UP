package dev.t2u.renderer

// Kotlin original (plan 04-07, D-11), GPL-3.0-only. Node stops the playlist at the first track that cannot be played.
//
// Pure functions and constants, no I/O: whether the reason a track could not be prepared will ever change. The renderer
// owns the retry and skip state; this file only classifies the message.

/**
 * The phrases yt-dlp prints (case-insensitive) when YouTube says a video cannot be played and the reason will not change by
 * asking again: removed, private, unavailable, age or members restricted, not yet live (D-11).
 */
private val PERMANENT_REASONS = listOf(
    "video unavailable",
    "private video",
    "this video has been removed",
    "this video is not available",
    "sign in to confirm your age",
    "members-only",
    "join this channel",
    "this live event will begin",
    "premieres in",
)

/**
 * True when [message] carries one of YouTube's reasons that will not change (D-11): such a track is skipped at once, never
 * retried. Anything else (null, a network error, a 403, a timeout, a crash, a failed download or build) is unclear and is
 * retried first. Only a fixed phrase list is looked for; nothing else is parsed out of the message.
 */
fun isPermanentlyUnavailable(message: String?): Boolean {
    if (message == null) return false
    val lower = message.lowercase()
    return PERMANENT_REASONS.any { lower.contains(it) }
}

/**
 * How many tracks in a row may fail before the player stops instead of skipping again (D-11, T-04-20). A queue of broken
 * tracks, or an endless chain of unplayable autoplay videos, therefore cannot loop for ever.
 */
const val MAX_UNPLAYABLE_IN_A_ROW = 5
