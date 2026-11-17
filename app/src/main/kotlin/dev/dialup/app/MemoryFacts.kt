// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The memory numbers the event log carries, so a kill the system reports as LOW_MEMORY can be read against what the app held and
 * what the phone had left. Pure JVM: the service reads the numbers from Android and these turn them into log fields.
 */

private const val BYTES_PER_MB = 1024L * 1024L

/** [epochMs] as ISO-8601 with the zone's offset, like the `ts` of every log line. */
fun isoTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))

private fun mb(bytes: Long): Long = bytes / BYTES_PER_MB

private fun kbToMb(kb: Long): Long = kb / 1024L

/**
 * The fields of one `exit-info` event: how the earlier process ended and, when the system recorded it, how much memory it held
 * ([pssKb] and [rssKb], in KiB as ApplicationExitInfo reports them; 0 or negative means not recorded and the field is left out).
 */
fun exitInfoFields(
    reason: Int,
    status: Int,
    timestamp: Long,
    description: String?,
    importance: Int,
    pssKb: Long,
    rssKb: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<String, Any?> = buildMap {
    put("reason", reason)
    put("status", status)
    put("timestamp", timestamp)
    // the same shape as the line's own ts, so the exit can be read against the lines around it
    put("at", isoTime(timestamp, zone))
    put("description", description)
    put("importance", importance)
    if (pssKb > 0) put("pssMb", kbToMb(pssKb))
    if (rssKb > 0) put("rssMb", kbToMb(rssKb))
}

/**
 * The memory fields of a heartbeat: what the phone has left ([availBytes] against its [thresholdBytes] and the system's own low
 * flag [lowMemory]) and what this process holds (the Java heap [javaUsedBytes] of [javaMaxBytes], the native heap
 * [nativeBytes], and the proportional set size [pssKb]).
 */
fun memoryFields(
    availBytes: Long,
    totalBytes: Long,
    thresholdBytes: Long,
    lowMemory: Boolean,
    javaUsedBytes: Long,
    javaMaxBytes: Long,
    nativeBytes: Long,
    pssKb: Long,
): Map<String, Any?> = linkedMapOf(
    "memAvailMb" to mb(availBytes),
    "memTotalMb" to mb(totalBytes),
    "memThresholdMb" to mb(thresholdBytes),
    "memLow" to lowMemory,
    "javaUsedMb" to mb(javaUsedBytes),
    "javaMaxMb" to mb(javaMaxBytes),
    "nativeMb" to mb(nativeBytes),
    "pssMb" to kbToMb(pssKb),
)

/** The name of an `onTrimMemory` level, for the log; an unknown level is its number. */
fun trimLevelName(level: Int): String = when (level) {
    5 -> "RUNNING_MODERATE"
    10 -> "RUNNING_LOW"
    15 -> "RUNNING_CRITICAL"
    20 -> "UI_HIDDEN"
    40 -> "BACKGROUND"
    60 -> "MODERATE"
    80 -> "COMPLETE"
    else -> level.toString()
}
