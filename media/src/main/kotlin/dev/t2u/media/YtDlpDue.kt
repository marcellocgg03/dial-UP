// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * When yt-dlp was last updated and last tried, as wall-clock milliseconds. It is the one due criterion, so a
 * service restart or a Wi-Fi flap cannot start an update storm. A timestamp is null when it never happened.
 */
data class YtDlpRecord(val lastSuccessWallMs: Long? = null, val lastAttemptWallMs: Long? = null)

/**
 * Where the [YtDlpRecord] lives. The app implements it on a small JSON file. [load] never throws (an unreadable file is
 * the empty record, i.e. "due"); a [save] failure must not stop an update.
 */
interface YtDlpRecordStore {
    fun load(): YtDlpRecord
    fun save(record: YtDlpRecord)
}

/**
 * How much earlier than a full day an update may run. Equal to the flex window of the daily job, so a job that fires
 * early in its window still updates: due once 24 h - 6 h = 18 h have passed since the last success.
 */
const val YTDLP_DUE_SLACK_MS: Long = 6L * 60 * 60 * 1000

/**
 * True when an automatic update is due: at least [intervalMs] - [slackMs] since the last success and at least [retryMs]
 * since the last attempt. A timestamp later than [nowWallMs] (the wall clock went back) counts as absent, never as recent.
 */
fun ytdlpDue(
    record: YtDlpRecord,
    nowWallMs: Long,
    intervalMs: Long = YTDLP_UPDATE_INTERVAL_MS,
    slackMs: Long = YTDLP_DUE_SLACK_MS,
    retryMs: Long = YTDLP_UPDATE_RETRY_MS,
): Boolean {
    val success = record.lastSuccessWallMs?.takeIf { it <= nowWallMs }
    val attempt = record.lastAttemptWallMs?.takeIf { it <= nowWallMs }
    val successDue = success == null || nowWallMs - success >= intervalMs - slackMs
    val attemptDue = attempt == null || nowWallMs - attempt >= retryMs
    return successDue && attemptDue
}

private const val KEY_SUCCESS = "lastSuccessWallMs"
private const val KEY_ATTEMPT = "lastAttemptWallMs"

/** Tolerant: anything unreadable gives the empty record, a bad field is dropped alone; never throws. */
fun parseYtDlpRecord(text: String): YtDlpRecord {
    val obj = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return YtDlpRecord()
    fun field(key: String): Long? {
        val p = obj[key] as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.longOrNull?.takeIf { it >= 0 }
    }
    return YtDlpRecord(field(KEY_SUCCESS), field(KEY_ATTEMPT))
}

/** The record as JSON text; null fields are left out. */
fun ytDlpRecordJson(record: YtDlpRecord): String = buildJsonObject {
    record.lastSuccessWallMs?.let { put(KEY_SUCCESS, it) }
    record.lastAttemptWallMs?.let { put(KEY_ATTEMPT, it) }
}.toString()
