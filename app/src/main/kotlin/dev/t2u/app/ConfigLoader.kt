package dev.t2u.app

// Plan 03-11: the optional files/config.json (SVC-07 debug switches and forced format, DISC-04 name filter) turned
// into the effective renderer Config at every bridge start. Pure JVM (no android imports) so it is unit-tested in WSL.
// GPL-3.0-only.

import dev.t2u.renderer.Config
import dev.t2u.renderer.ConfigFileResult
import dev.t2u.renderer.loadConfigFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException

/** Name of the file inside the app's files directory, next to settings.json. */
const val CONFIG_FILE = "config.json"

/**
 * Reads `<filesDir>/config.json` on top of `Config(dataDir = <filesDir>/cast)`. Whatever the file system looks like,
 * this returns a usable config: a missing file is the plain defaults, a bad one is the defaults plus a value-free
 * warning. It never throws, so a bad file cannot stop the bridge from starting.
 */
fun loadAppConfig(filesDir: File): ConfigFileResult {
    val base = Config(dataDir = File(filesDir, "cast"))
    val file = File(filesDir, CONFIG_FILE)
    return try {
        if (file.exists() && !file.isFile) {
            ConfigFileResult(base, listOf("config.json ignored: not a regular file"), true)
        } else {
            loadConfigFile(file, base)
        }
    } catch (_: IOException) {
        ConfigFileResult(base, listOf("config.json ignored: could not be read"), true)
    } catch (_: SecurityException) {
        ConfigFileResult(base, listOf("config.json ignored: could not be read"), true)
    }
}

/**
 * What was applied, for the evidence log and the cast-state command. Switch values and counts only: the include and
 * exclude patterns are never echoed (T-03-34), and the warnings already name keys, not values.
 */
fun configSummary(result: ConfigFileResult): JsonObject {
    val c = result.config
    return JsonObject(
        mapOf(
            "format" to JsonPrimitive(c.outputFormat),
            "startTimeoutMs" to JsonPrimitive(c.startTimeoutMs),
            "pollIntervalMs" to JsonPrimitive(c.pollIntervalMs),
            "mp3Bitrate" to JsonPrimitive(c.mp3Bitrate),
            "flacBits" to JsonPrimitive(c.flacBits),
            "ytdlpVerbose" to JsonPrimitive(c.ytdlpVerbose),
            "ffmpegLogLevel" to JsonPrimitive(c.ffmpegLogLevel),
            "includeCount" to JsonPrimitive(c.nameFilter.include.size),
            "excludeCount" to JsonPrimitive(c.nameFilter.exclude.size),
            "found" to JsonPrimitive(result.found),
            "warnings" to JsonArray(result.warnings.map { JsonPrimitive(it) }),
        ),
    )
}

/** [configSummary] as plain Kotlin values, the shape the evidence log writes (a JsonPrimitive would print quoted twice). */
fun configEvidenceFields(result: ConfigFileResult): Map<String, Any?> =
    configSummary(result).mapValues { (_, v) -> plain(v) }

private fun plain(e: JsonElement): Any? = when (e) {
    is JsonNull -> null
    is JsonArray -> e.map { plain(it) }
    is JsonObject -> e.mapValues { (_, v) -> plain(v) }
    is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.content
}
