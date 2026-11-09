// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.upnp

// Field lookups shared by the parsers of hand-editable state files (hidden.json, manual.json, names.json) and the
// InnerTube /next response. Pure JVM (no android.*).

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The value of [key] when it is a JSON string (an empty string stays empty). Strings only: a number, boolean, JSON null,
 * object, array or a missing key gives null, so a hand-edited file with a number where a name belongs is ignored
 * rather than coerced.
 */
fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The value of [key] when it is a JSON object; anything else, or a missing key, gives null. */
fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject
