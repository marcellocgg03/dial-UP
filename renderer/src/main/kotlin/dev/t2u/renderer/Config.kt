package dev.t2u.renderer

// Port of tube2upnp src/config.js (Phase 2 defaults; config.json is Phase 3 SVC-07), GPL-3.0-only.

import java.io.File

/** Name shown in the YouTube cast menu (CAST-07). Placeholders: {name} {manufacturer} {model}. */
const val DEFAULT_NAME_TEMPLATE = "🔊 {name}"

/**
 * D-04: while the Docker server runs next to this app, entries carry a hard-coded suffix
 * ("🔊 Living Room Speaker (app)") so the two sets of receivers can be told apart in the cast menu.
 * There is deliberately no naming setting. D-04: remove at parity (delete this constant, the
 * `nameSuffix` default and its use in [receiverName] callers).
 */
const val DOCKER_COEXISTENCE_SUFFIX = " (app)"

/** Output formats that exist in Phase 2: the two ffmpeg pipe formats (mp4/flac/direct/auto come later). */
private val PHASE2_OUTPUT_FORMATS = setOf("adts", "mp3")

/**
 * Runtime configuration with the Node defaults. In Phase 2 this is built in code; reading it from
 * config.json is Phase 3 (SVC-07). RESEARCH Open Question 3 / A1: the Docker server runs on another
 * host, so the Node ports (DIAL 3000+, media 8000) do not collide.
 *
 * @property excludeServers case-insensitive SERVER-header substrings of devices that are never speakers
 *   (RESEARCH Open Question 5: the QuickShare NOTIFY flood). This is not the DISC-04 include/exclude
 *   filter, which is Phase 3.
 * @property startTimeoutMs keeps the Node 12 s: it is measured from Play, after the resolve, so the
 *   cold yt-dlp start does not count (RESEARCH A8; plan 02-14 records the real first-cast timing).
 */
data class Config(
    val dataDir: File,
    val receiverBasePort: Int = 3000,
    val mediaPort: Int = 8000,
    val advertiseIp: String? = null,
    val discoveryIntervalS: Int = 300,
    val pruneIntervalS: Int = 60,
    val defaultMaxAge: Int = 1800,
    val nameTemplate: String = DEFAULT_NAME_TEMPLATE,
    val nameSuffix: String = DOCKER_COEXISTENCE_SUFFIX,
    val outputFormat: String = "adts",
    val pollIntervalMs: Long = 2000,
    val startTimeoutMs: Long = 12000,
    val autoplayOnConnect: Boolean = true,
    val excludeServers: List<String> = listOf("QuickShareUPnP"),
) {
    /** Throws [IllegalArgumentException] naming the offending setting. */
    fun validate() {
        require(outputFormat in PHASE2_OUTPUT_FORMATS) {
            "outputFormat \"$outputFormat\" is not available in Phase 2 (only ${PHASE2_OUTPUT_FORMATS.joinToString(" and ")})"
        }
        require(receiverBasePort in 1..65535) { "receiverBasePort $receiverBasePort is not a port" }
        require(mediaPort in 1..65535) { "mediaPort $mediaPort is not a port" }
        require(pollIntervalMs > 0) { "pollIntervalMs must be positive" }
        require(startTimeoutMs > 0) { "startTimeoutMs must be positive" }
    }
}

/**
 * The cast-menu name of a receiver: [template] with the first `{name}`, `{manufacturer}` and `{model}`
 * replaced literally (as renderer.js does with String.replace, in that order), trimmed, then [suffix]
 * appended. The friendly name arrives from parseDescription, which substitutes "Unknown renderer" for
 * an empty one, so the result is never just the emoji and the suffix.
 */
fun receiverName(template: String, suffix: String, friendlyName: String, manufacturer: String, model: String): String =
    template
        .replaceFirst("{name}", friendlyName)
        .replaceFirst("{manufacturer}", manufacturer)
        .replaceFirst("{model}", model)
        .trim() + suffix
