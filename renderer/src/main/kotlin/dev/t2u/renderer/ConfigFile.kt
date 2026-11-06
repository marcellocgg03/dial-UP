package dev.t2u.renderer

// Kotlin original, GPL-3.0-only: the optional config.json (DISC-04 name filter, SVC-07 forced format
// and debug switches). Pure JVM; reading the file from app storage is plan 03-11.

import dev.t2u.media.YTDLP_CHANNELS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/** A config.json larger than this is not read (a config is a few hundred bytes). */
const val MAX_CONFIG_FILE_BYTES = 64 * 1024

/**
 * The outcome of reading config.json: the config to use, value-free warnings, and whether a file existed.
 * [guiKeys] names (never values) the settings the app's settings.json put on top of the file; the app fills it in, the
 * file reader leaves it empty.
 */
data class ConfigFileResult(
    val config: Config,
    val warnings: List<String>,
    val found: Boolean,
    val guiKeys: List<String> = emptyList(),
)

/** Reads [file] (missing: defaults, no warning; over [MAX_CONFIG_FILE_BYTES] or unreadable: defaults, one warning). */
fun loadConfigFile(file: File, base: Config): ConfigFileResult {
    if (!file.isFile) return ConfigFileResult(base, emptyList(), false)
    val bytes = try {
        file.inputStream().use { it.readNBytes(MAX_CONFIG_FILE_BYTES + 1) }
    } catch (_: java.io.IOException) {
        return ConfigFileResult(base, listOf("config.json ignored: could not be read"), true)
    }
    if (bytes.size > MAX_CONFIG_FILE_BYTES) {
        return ConfigFileResult(base, listOf("config.json ignored: larger than ${MAX_CONFIG_FILE_BYTES / 1024} KiB"), true)
    }
    return parseConfigFile(String(bytes, Charsets.UTF_8), base)
}

private val KNOWN_KEYS = setOf(
    "include", "exclude", "outputFormat", "startTimeoutMs", "pollIntervalMs",
    "mp3Bitrate", "ytdlpVerbose", "ytdlpChannel", "ffmpegLogLevel",
    "logLevel", "prefetch", "gapless", "cacheMb",
)

/**
 * Overlays the valid values of a config.json [text] onto [base]. Tolerant: whatever the text says, the
 * result is a usable config (it passes [Config.validate] when [base] does) and the problems are
 * returned as warnings that name keys and indexes only.
 *
 * The keys config.json may carry (all optional; an absent file or key leaves the default):
 *
 * ```
 * {
 *   "include": "living|kitchen",        // string or array of strings: case-insensitive regexes on the speaker's
 *   "exclude": ["lamp", "^bath"],       //   friendly name; a speaker appears if it matches an include (or none is
 *                                       //   set) and matches no exclude
 *   "outputFormat": "auto",             // auto | direct | mp4 | adts | mp3
 *   "startTimeoutMs": 12000,            // integer 1000..120000
 *   "pollIntervalMs": 2000,             // integer 200..30000
 *   "mp3Bitrate": "192k",               // "64k" .. "320k"
 *   "ytdlpVerbose": true,               // boolean
 *   "ytdlpChannel": "stable",           // stable | nightly: the yt-dlp release the daily background update installs
 *   "ffmpegLogLevel": "error",          // quiet panic fatal error warning info verbose debug
 *   "logLevel": "info",                 // off | info | debug: what the log file and logcat get (debug adds SSDP packets, raw lounge chunks, lounge noops and media GETs)
 *   "prefetch": true,                   // boolean: prepare the next track while the current one plays
 *   "gapless": true,                    // boolean: queue the prepared track on the speaker; no effect when prefetch is false
 *   "cacheMb": 256                      // integer 0..32768: size cap of the converted and downloaded track cache
 * }
 * ```
 *
 * Types are strict (a numeric string is not a number). `nameSuffix` and `nameTemplate` are deliberately
 * not readable (D-04: the " (app)" suffix has no setting); they, and any unknown key, are ignored and
 * listed by name in one warning. Warnings name keys and indexes, never values.
 */
fun parseConfigFile(text: String, base: Config): ConfigFileResult {
    val root = try {
        Json.parseToJsonElement(text)
    } catch (_: Exception) {
        return ConfigFileResult(base, listOf("config.json ignored: not valid JSON"), true)
    }
    if (root !is JsonObject) {
        return ConfigFileResult(base, listOf("config.json ignored: not a JSON object"), true)
    }
    val warnings = mutableListOf<String>()
    var config = base

    val include = patternList(root["include"], "include", warnings)
    val exclude = patternList(root["exclude"], "exclude", warnings)
    if (include != null || exclude != null) {
        config = config.copy(nameFilter = NameFilter(include ?: base.nameFilter.include, exclude ?: base.nameFilter.exclude))
    }
    string(root, "outputFormat", warnings, "must be one of ${OUTPUT_FORMATS.joinToString(", ")}") { it in OUTPUT_FORMATS }
        ?.let { config = config.copy(outputFormat = it) }
    integer(root, "startTimeoutMs", 1000L..120000L, warnings)?.let { config = config.copy(startTimeoutMs = it) }
    integer(root, "pollIntervalMs", 200L..30000L, warnings)?.let { config = config.copy(pollIntervalMs = it) }
    string(root, "mp3Bitrate", warnings, "must be 64k to 320k", ::isValidMp3Bitrate)?.let { config = config.copy(mp3Bitrate = it) }
    root["ytdlpVerbose"]?.let { el ->
        val v = (el as? JsonPrimitive)?.takeIf { !it.isString && el !is JsonNull }?.booleanOrNull
        if (v != null) config = config.copy(ytdlpVerbose = v) else warnings += "ytdlpVerbose ignored: must be true or false"
    }
    string(root, "ytdlpChannel", warnings, "must be stable or nightly") { it in YTDLP_CHANNELS }
        ?.let { config = config.copy(ytdlpChannel = it) }
    string(root, "ffmpegLogLevel", warnings, "must be one of ${FFMPEG_LOG_LEVELS.joinToString(", ")}") { it in FFMPEG_LOG_LEVELS }
        ?.let { config = config.copy(ffmpegLogLevel = it) }
    string(root, "logLevel", warnings, "must be off, info or debug") { it in LOG_LEVELS }
        ?.let { config = config.copy(logLevel = it) }
    root["prefetch"]?.let { el ->
        val v = (el as? JsonPrimitive)?.takeIf { !it.isString && el !is JsonNull }?.booleanOrNull
        if (v != null) config = config.copy(prefetch = v) else warnings += "prefetch ignored: must be true or false"
    }
    root["gapless"]?.let { el ->
        val v = (el as? JsonPrimitive)?.takeIf { !it.isString && el !is JsonNull }?.booleanOrNull
        if (v != null) config = config.copy(gapless = v) else warnings += "gapless ignored: must be true or false"
    }
    integer(root, "cacheMb", 0L..32768L, warnings)?.let { config = config.copy(cacheMb = it.toInt()) }

    val ignored = root.keys.filter { it !in KNOWN_KEYS }
    if (ignored.isNotEmpty()) warnings += "ignored keys: ${ignored.joinToString(", ") { safeKey(it) }}"
    return ConfigFileResult(config, warnings, true)
}

/** Key names come from the file, so keep them short and printable before they go into a warning. */
private fun safeKey(key: String): String =
    key.take(40).map { if (it.isISOControl()) '?' else it }.joinToString("")

private fun string(root: JsonObject, key: String, warnings: MutableList<String>, rule: String, ok: (String) -> Boolean): String? {
    val el = root[key] ?: return null
    val s = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (s != null && ok(s)) return s
    warnings += "$key ignored: $rule"
    return null
}

private fun integer(root: JsonObject, key: String, range: LongRange, warnings: MutableList<String>): Long? {
    val el = root[key] ?: return null
    val v = (el as? JsonPrimitive)?.takeIf { !it.isString && el !is JsonNull }?.longOrNull
    if (v != null && v in range) return v
    warnings += "$key ignored: must be a whole number from ${range.first} to ${range.last}"
    return null
}

/** A string or an array of strings; invalid regexes and non-strings are skipped with a warning. Null when absent or unusable. */
private fun patternList(el: JsonElement?, key: String, warnings: MutableList<String>): List<String>? {
    if (el == null) return null
    val items: List<JsonElement> = when (el) {
        is JsonArray -> el
        is JsonPrimitive -> listOf(el)
        else -> {
            warnings += "$key ignored: must be a string or an array of strings"
            return null
        }
    }
    val out = mutableListOf<String>()
    items.forEachIndexed { i, item ->
        val s = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
        val where = if (el is JsonArray) "$key[$i]" else key
        when {
            s == null -> warnings += "$where skipped: not a string"
            NameFilter.compileOrNull(s) == null -> warnings += "$where skipped: not a valid regular expression"
            else -> out += s
        }
    }
    return out
}
