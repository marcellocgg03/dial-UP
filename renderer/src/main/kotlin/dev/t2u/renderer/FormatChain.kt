package dev.t2u.renderer

// Kotlin original, port of the format selection in tube2upnp src/renderer.js and src/formats.js, GPL-3.0-only.
//
// Pure functions, no I/O: which stream format a speaker is given, and which one comes next when it does not
// start. The renderer owns the state (the learned format, the 12 s clock); this file only decides.

import dev.t2u.media.FALLBACK_CHAIN
import dev.t2u.media.mimeOf
import dev.t2u.upnp.SinkProtocol
import dev.t2u.upnp.sinkAccepts
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The per-speaker store key that remembers the format that worked (renderer.js `tube2upnp.format`). */
const val LEARNED_FORMAT_KEY = "tube2upnp.format"

/**
 * The per-speaker store key that remembers whether the speaker takes SetNextAVTransportURI (plan 04-06, D-08). Kotlin original:
 * Node keeps `#gaplessSupported` in memory only, so every restart costs a failing call per speaker. The value is
 * `{"supported": <bool>, "model": "<manufacturer>|<modelName>|<modelNumber>", "at": <epoch ms>}`.
 */
const val LEARNED_GAPLESS_KEY = "tube2upnp.gapless"

/** A stored "no" is trusted this long (7 days); after that the speaker is asked once more, since a firmware update can add support (D-13). */
const val GAPLESS_REPROBE_MS = 7L * 24 * 60 * 60 * 1000

/** The identity a stored answer belongs to: the three description fields joined with `|`. */
fun speakerModel(manufacturer: String, modelName: String, modelNumber: String): String =
    listOf(manufacturer, modelName, modelNumber).joinToString("|")

/** The value stored under [LEARNED_GAPLESS_KEY]. */
fun gaplessMemory(supported: Boolean, model: String, now: Long): JsonObject = buildJsonObject {
    put("supported", supported)
    put("model", model)
    put("at", now)
}

/** The model a stored value was written for, or null when [stored] is not an object with a string `model` (T-04-17). */
fun gaplessMemoryModel(stored: JsonElement?): String? =
    ((stored as? JsonObject)?.get("model") as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * What a stored value says about gapless support, or null for "unknown": anything of the wrong shape (T-03-19 discipline, T-04-17),
 * a value written for another [model], and a stored false older than [GAPLESS_REPROBE_MS] (D-13: asked again once). A stored true
 * is trusted whatever its age; a contradiction removes or flips it (see the renderer).
 */
fun readGaplessMemory(stored: JsonElement?, model: String, now: Long): Boolean? {
    val obj = stored as? JsonObject ?: return null
    val supported = (obj["supported"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
    val storedModel = gaplessMemoryModel(obj) ?: return null
    val at = (obj["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
    if (storedModel != model) return null
    if (!supported && now - at >= GAPLESS_REPROBE_MS) return null
    return supported
}

/**
 * May [format] be tried on a speaker with this sink list? mp3 always counts (the last resort that every
 * speaker takes, whatever it lists); the others need the speaker's list to accept their MIME type, and a
 * speaker that lists nothing is tried anyway ("unknown means try").
 */
fun formatAccepted(protocols: List<SinkProtocol>?, format: String, sourceMime: String): Boolean {
    if (format == "mp3") return true
    // adts copies AAC frames, so a source in another codec (Opus in WebM) cannot take it
    if (format == "adts" && NON_AAC_SOURCE.containsMatchIn(sourceMime)) return false
    return sinkAccepts(protocols, mimeOf(format, sourceMime)) != false
}

private val NON_AAC_SOURCE = Regex("webm|opus|ogg|vorbis", RegexOption.IGNORE_CASE)

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
