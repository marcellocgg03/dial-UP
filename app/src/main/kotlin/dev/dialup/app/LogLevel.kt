// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

/**
 * Event log level (decisions D-08, D-09). Pure JVM so the classifier runs in a unit test.
 *
 * INFO is the default: discovery, DIAL launches, lounge messages, playback, net and retry events.
 * DEBUG adds the per-packet noise. Measured volumes: about 0.15 MB/h idle at
 * info; about 1 MB/h would remain if lounge noops were kept at info, which is why they are debug.
 *
 * tools/cast-timeline.py counts successful media GETs from `http-req` events, so its media GET
 * counts need level debug.
 */
enum class LogLevel { OFF, INFO, DEBUG }

/** "off" and "debug" (exact, lowercase) select those levels; anything else is INFO. */
fun logLevelOf(text: String?): LogLevel = when (text) {
    "off" -> LogLevel.OFF
    "debug" -> LogLevel.DEBUG
    else -> LogLevel.INFO
}

/** Kinds that exist only to show packets and raw chunks. */
val DEBUG_KINDS: Set<String> = setOf("ssdp-rx", "ssdp-tx", "ssdp-drop", "ssdp-bind", "lounge-raw", "lounge-http")

/** The level an event belongs to; an unknown kind is INFO so a new event is never silently dropped. */
fun levelOf(kind: String, fields: Map<String, Any?>): LogLevel {
    if (kind in DEBUG_KINDS) return LogLevel.DEBUG
    return when (kind) {
        "lounge" -> if (fields["name"] == "noop") LogLevel.DEBUG else LogLevel.INFO
        "lounge-app" ->
            if (fields["type"] == "notHandled" && fields["name"] == "noop") LogLevel.DEBUG else LogLevel.INFO
        "http-req" -> {
            val status = (fields["status"] as? Number)?.toInt()
            val ok = status != null && status in 200..299
            val dialPost = fields["server"] == "dial" && fields["method"] == "POST"
            if (ok && !dialPost) LogLevel.DEBUG else LogLevel.INFO
        }
        else -> LogLevel.INFO
    }
}

fun shouldWrite(configured: LogLevel, kind: String, fields: Map<String, Any?>): Boolean =
    configured != LogLevel.OFF && levelOf(kind, fields).ordinal <= configured.ordinal
