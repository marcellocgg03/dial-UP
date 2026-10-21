package dev.t2u.renderer

// Kotlin original, port of the format selection in tube2upnp src/renderer.js and src/formats.js, GPL-3.0-only.
//
// Pure functions, no I/O: which stream format a speaker is given, and which one comes next when it does not
// start. The renderer owns the state (the learned format, the 12 s clock); this file only decides.

import dev.t2u.media.FALLBACK_CHAIN
import dev.t2u.media.mimeOf
import dev.t2u.upnp.SinkProtocol
import dev.t2u.upnp.sinkAccepts

/** The per-speaker store key that remembers the format that worked (renderer.js `tube2upnp.format`). */
const val LEARNED_FORMAT_KEY = "tube2upnp.format"

/**
 * May [format] be tried on a speaker with this sink list? mp3 always counts (the last resort that every
 * speaker takes, whatever it lists); the others need the speaker's list to accept their MIME type, and a
 * speaker that lists nothing is tried anyway ("unknown means try").
 */
fun formatAccepted(protocols: List<SinkProtocol>?, format: String, sourceMime: String): Boolean =
    format == "mp3" || sinkAccepts(protocols, mimeOf(format, sourceMime)) != false

/**
 * The format for the next start: a forced [mode] (anything but "auto") wins; in auto mode the [learned]
 * format when it is a chain member, else the first chain entry the speaker accepts. A stored value outside
 * the chain is ignored (T-03-19).
 */
fun chooseFormat(mode: String, learned: String?, protocols: List<SinkProtocol>?, sourceMime: String): String {
    if (mode != "auto") return mode
    if (learned != null && learned in FALLBACK_CHAIN) return learned
    return FALLBACK_CHAIN.firstOrNull { formatAccepted(protocols, it, sourceMime) } ?: "mp3"
}

/** The first chain entry after [after] that the speaker accepts, or null at the end of the chain. */
fun nextFormat(after: String, protocols: List<SinkProtocol>?, sourceMime: String): String? {
    val i = FALLBACK_CHAIN.indexOf(after)
    if (i < 0) return null
    return FALLBACK_CHAIN.drop(i + 1).firstOrNull { formatAccepted(protocols, it, sourceMime) }
}
