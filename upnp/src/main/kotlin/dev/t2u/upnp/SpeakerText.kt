package dev.t2u.upnp

// Original Kotlin code, GPL-3.0-only (Node tube2upnp has no counterpart: it shows a speaker's text as it comes).
//
// Text a speaker sends is whatever the device on the LAN says: a fault description, an error text, a name. Before
// it is stored, shown, put in an error or embedded in a note it is cleaned and bounded here, in the module below
// every consumer (renderer and app both reach it).

/** Longest speaker-originated text kept, the ellipsis of a cut text included. */
const val MAX_SPEAKER_TEXT = 200

/**
 * Cleans text that a speaker sent: every control character becomes a space and the text is trimmed. A text longer
 * than [max] is cut so that the result, with the ellipsis "…" (U+2026) appended, is at most [max] UTF-16 units;
 * a surrogate pair is never split. Applying it to its own output changes nothing, so a second application at a
 * later point is harmless.
 *
 * For text a speaker sends (fault text, error text, names). The cast-menu name keeps its own rule
 * (`cleanDisplayName`, above this module) and the evidence log keeps its own 2048-character cap.
 */
fun cleanSpeakerText(raw: String, max: Int = MAX_SPEAKER_TEXT): String {
    val cleaned = buildString(raw.length) {
        for (c in raw) append(if (Character.isISOControl(c)) ' ' else c)
    }.trim()
    if (cleaned.length <= max) return cleaned
    var cut = (max - 1).coerceAtLeast(0)
    if (cut > 0 && Character.isHighSurrogate(cleaned[cut - 1])) cut -= 1
    return cleaned.substring(0, cut).trimEnd() + "…"
}

/** [cleanSpeakerText] of an exception's message, or of its class simple name when it has none (or only blanks), followed by its cause's. */
fun speakerFailureText(e: Throwable): String {
    val own = cleanSpeakerText(e.message ?: "").ifEmpty { cleanSpeakerText(e.javaClass.simpleName) }
    val cause = e.cause?.takeIf { it !== e } ?: return own
    val why = cleanSpeakerText(cause.message ?: "").ifEmpty { cleanSpeakerText(cause.javaClass.simpleName) }
    return cleanSpeakerText("$own: $why")
}
