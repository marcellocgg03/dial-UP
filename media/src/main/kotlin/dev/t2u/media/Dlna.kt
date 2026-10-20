package dev.t2u.media

/** One output format of formats.js: `mime` and `ext` are null for `direct` (they come from the source). */
data class FormatSpec(val mime: String?, val ext: String?, val seekable: Boolean)

/** Port of the FORMATS table in reference/tube2upnp/src/formats.js. */
val FORMATS: Map<String, FormatSpec> = linkedMapOf(
    "direct" to FormatSpec(null, null, true),
    "mp4" to FormatSpec("audio/mp4", "m4a", true),
    "adts" to FormatSpec("audio/aac", "aac", false),
    "flac" to FormatSpec("audio/flac", "flac", true),
    "mp3" to FormatSpec("audio/mpeg", "mp3", false),
)

private fun spec(format: String): FormatSpec = FORMATS[format] ?: throw IllegalArgumentException("unknown format: $format")

/** DLNA.ORG_PN profile names (formats.js dlnaFeatures); FLAC and direct have none, renderers go by MIME. */
private val PROFILE_NAMES = mapOf(
    "mp4" to "DLNA.ORG_PN=AAC_ISO_320;",
    "adts" to "DLNA.ORG_PN=AAC_ADTS_320;",
    "mp3" to "DLNA.ORG_PN=MP3;",
)

/** contentFeatures.dlna.org value, byte for byte the string formats.js dlnaFeatures builds. */
fun dlnaFeatures(format: String): String {
    val pn = PROFILE_NAMES[format].orEmpty()
    val op = if (spec(format).seekable) "01" else "00"
    val ci = if (format == "direct") 0 else 1
    return "${pn}DLNA.ORG_OP=$op;DLNA.ORG_CI=$ci;DLNA.ORG_FLAGS=01700000000000000000000000000000"
}

/** File extension of a token URL for [format] (mp4 -> m4a, adts -> aac, mp3 -> mp3). */
fun extensionOf(format: String): String = spec(format).ext ?: throw IllegalArgumentException("format $format has no fixed extension")
