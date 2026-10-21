package dev.t2u.renderer

// Kotlin original, GPL-3.0-only: the optional config.json (DISC-04 name filter, SVC-07 forced format
// and debug switches). Pure JVM; reading the file from app storage is plan 03-11.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The outcome of reading config.json: the config to use, value-free warnings, and whether a file existed. */
data class ConfigFileResult(val config: Config, val warnings: List<String>, val found: Boolean)

/**
 * Overlays the valid values of a config.json [text] onto [base]. Tolerant: whatever the text says, the
 * result is a usable config and the problems are returned as warnings that name keys and indexes only.
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
    val fmt = root["outputFormat"]
    if (fmt != null) {
        val s = (fmt as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (s != null && s in OUTPUT_FORMATS) {
            config = config.copy(outputFormat = s)
        } else {
            warnings += "outputFormat ignored: must be one of ${OUTPUT_FORMATS.joinToString(", ")}"
        }
    }
    return ConfigFileResult(config, warnings, true)
}

/** A string or an array of strings; invalid regexes and non-strings are skipped with a warning. Null when absent. */
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
