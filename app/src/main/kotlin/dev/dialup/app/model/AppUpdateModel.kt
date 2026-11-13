// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import dev.dialup.app.Failures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** The longest an automatic check waits after a successful one (the budget of 60 requests an hour is shared with the yt-dlp updater). */
const val AUTO_CHECK_INTERVAL_MS: Long = 12L * 60 * 60 * 1000

/** The shortest an automatic check waits after a failed attempt (rate limit or no connection). */
const val AUTO_RETRY_MS: Long = 60L * 60 * 1000

/** What one request for the releases list came to. */
sealed interface FetchResult {
    data class Body(val text: String) : FetchResult
    data object RateLimited : FetchResult
    data class Http(val code: Int) : FetchResult
    data object Offline : FetchResult
    data object TooLarge : FetchResult
}

/** What a check concluded. */
sealed interface CheckOutcome {
    data class Newer(val release: Release) : CheckOutcome
    data class UpToDate(val newestTag: String?) : CheckOutcome
    data object RateLimited : CheckOutcome
    data object Offline : CheckOutcome
    data object Failed : CheckOutcome
}

/**
 * What the app remembers of its checks, in wall-clock milliseconds: the last check that got a list, the last attempt of any
 * kind, and the newest release that list held (null when it was empty).
 */
data class UpdateRecord(
    val lastCheckWallMs: Long? = null,
    val lastAttemptWallMs: Long? = null,
    val latestTag: String? = null,
    val latestUrl: String? = null,
    /** The channel the newest release was read for (wire name); null is a record from before channels, i.e. stable. */
    val channel: String? = null,
)

/** The record in [text]; an unreadable text gives an empty record. Never throws. */
fun parseUpdateRecord(text: String): UpdateRecord {
    val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return UpdateRecord()
    fun long(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    return UpdateRecord(long("lastCheckWallMs"), long("lastAttemptWallMs"), str("latestTag"), str("latestUrl"), str("channel"))
}

/** The JSON text of [r]; null fields are left out. */
fun updateRecordJson(r: UpdateRecord): String {
    val o = buildMap<String, kotlinx.serialization.json.JsonElement> {
        r.lastCheckWallMs?.let { put("lastCheckWallMs", JsonPrimitive(it)) }
        r.lastAttemptWallMs?.let { put("lastAttemptWallMs", JsonPrimitive(it)) }
        r.latestTag?.let { put("latestTag", JsonPrimitive(it)) }
        r.latestUrl?.let { put("latestUrl", JsonPrimitive(it)) }
        r.channel?.let { put("channel", JsonPrimitive(it)) }
    }
    return JsonObject(o).toString()
}

/**
 * True when an automatic check may run now: never checked, 12 hours since the last good check, or (after a failed attempt)
 * one hour since that attempt. A wall clock that went back counts as due.
 */
fun autoCheckDue(record: UpdateRecord, nowWallMs: Long, channel: UpdateChannel = UpdateChannel.STABLE): Boolean {
    // a record read for the other channel says nothing about this one: check at once (its failed attempts are dropped, see run)
    if (!record.isFor(channel)) return true
    val check = record.lastCheckWallMs
    val attempt = record.lastAttemptWallMs
    if (attempt != null && (check == null || attempt > check)) {
        return nowWallMs < attempt || nowWallMs - attempt >= AUTO_RETRY_MS
    }
    if (check == null) return true
    return nowWallMs < check || nowWallMs - check >= AUTO_CHECK_INTERVAL_MS
}

/** True when the newest release in this record was read for [channel] (a record without a channel is stable). */
fun UpdateRecord.isFor(channel: UpdateChannel): Boolean = (this.channel ?: UpdateChannel.STABLE.wire) == channel.wire

/** The conclusion of [result] against the running version [currentVersion]. */
fun outcomeOf(result: FetchResult, currentVersion: String, channel: UpdateChannel = UpdateChannel.STABLE): CheckOutcome = when (result) {
    is FetchResult.RateLimited -> CheckOutcome.RateLimited
    is FetchResult.Offline -> CheckOutcome.Offline
    is FetchResult.Http, is FetchResult.TooLarge -> CheckOutcome.Failed
    is FetchResult.Body -> {
        val isList = runCatching { Json.parseToJsonElement(result.text) is JsonArray }.getOrDefault(false)
        val current = SemVer.parse(currentVersion)
        if (!isList || current == null) {
            CheckOutcome.Failed
        } else {
            val newest = newestRelease(result.text, channel)
            when {
                newest == null -> CheckOutcome.UpToDate(null)
                newest.version > current -> CheckOutcome.Newer(newest)
                else -> CheckOutcome.UpToDate(newest.tag)
            }
        }
    }
}

/** The newer release the Speakers screen offers: its tag and a page that [safeReleaseUrl] vouches for. */
data class UpdateCard(val tag: String, val url: String)

/** The card to show, or null: auto-check off, nothing recorded, the recorded release not newer, or a version that is not semantic. */
fun cardRelease(autoEnabled: Boolean, record: UpdateRecord, currentVersion: String, channel: UpdateChannel = UpdateChannel.STABLE): UpdateCard? {
    if (!autoEnabled || !record.isFor(channel)) return null
    val tag = record.latestTag ?: return null
    val latest = SemVer.parse(tag) ?: return null
    val current = SemVer.parse(currentVersion) ?: return null
    if (latest <= current) return null
    return UpdateCard(tag, safeReleaseUrl(record.latestUrl))
}

/** The one-line result a manual check shows. */
fun appUpdateMessage(o: CheckOutcome, currentVersion: String): String = when (o) {
    is CheckOutcome.Newer -> "DialUP ${o.release.tag} is available"
    is CheckOutcome.UpToDate -> "DialUP is up to date ($currentVersion)"
    is CheckOutcome.RateLimited -> "GitHub is limiting requests from this network; try again later"
    is CheckOutcome.Offline -> "Could not check for updates: no connection"
    is CheckOutcome.Failed -> "Could not check for updates"
}

/** What the screens read: a check in progress, the result of the last manual check, and the record. */
data class AppUpdateUi(
    val checking: Boolean = false,
    val outcome: CheckOutcome? = null,
    val record: UpdateRecord = UpdateRecord(),
)

/**
 * Holds the update check. One request runs at a time: a second caller while one is in flight gets that request's result.
 * [checkNow] always runs and sets [AppUpdateUi.outcome]; [autoCheck] runs only when enabled and due and stays silent
 * (it updates the record, never the outcome). Pure: the request, the record file and the clock are passed in.
 */
class AppUpdateModel(
    private val currentVersion: String,
    private val fetch: suspend () -> FetchResult,
    private val load: () -> UpdateRecord,
    private val save: (UpdateRecord) -> Unit,
    private val wallMs: () -> Long,
    private val channel: () -> UpdateChannel = { UpdateChannel.STABLE },
) {
    private val _state = MutableStateFlow(AppUpdateUi(record = load()))
    val state: StateFlow<AppUpdateUi> = _state.asStateFlow()

    private val lock = Any()
    private var inflight: CompletableDeferred<CheckOutcome>? = null

    /** Checks now, whatever the throttle and the automatic-check setting say. */
    suspend fun checkNow(): CheckOutcome {
        val o = flight()
        _state.update { it.copy(outcome = o) }
        return o
    }

    /** An automatic check: null (no request) when [enabled] is false or the last attempt is too recent. */
    suspend fun autoCheck(enabled: Boolean): CheckOutcome? {
        if (!enabled) return null
        if (!autoCheckDue(_state.value.record, wallMs(), channel())) return null
        return flight()
    }

    private suspend fun flight(): CheckOutcome {
        val mine = CompletableDeferred<CheckOutcome>()
        val existing = synchronized(lock) {
            inflight ?: run { inflight = mine; null }
        }
        if (existing != null) return existing.await()
        _state.update { it.copy(checking = true) }
        try {
            val outcome = run()
            mine.complete(outcome)
            return outcome
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            throw t
        } finally {
            synchronized(lock) { inflight = null }
            _state.update { it.copy(checking = false) }
        }
    }

    private suspend fun run(): CheckOutcome {
        val result = fetch()
        val ch = channel()
        val outcome = outcomeOf(result, currentVersion, ch)
        val now = wallMs()
        val old = _state.value.record
        val record = when (outcome) {
            is CheckOutcome.Newer -> UpdateRecord(now, now, outcome.release.tag, safeReleaseUrl(outcome.release.url), ch.wire)
            is CheckOutcome.UpToDate -> {
                // The list is good; remember the newest tag (if any) so the card can compare it later.
                val newest = (result as? FetchResult.Body)?.let { newestRelease(it.text, ch) }
                UpdateRecord(now, now, newest?.tag, newest?.let { safeReleaseUrl(it.url) }, ch.wire)
            }
            // a failure keeps the old record, unless it was read for the other channel: that one's release must not be shown here
            else -> if (old.isFor(ch)) old.copy(lastAttemptWallMs = now) else UpdateRecord(null, now, null, null, ch.wire)
        }
        _state.update { it.copy(record = record) }
        try {
            save(record)
        } catch (e: Exception) {
            Failures.report("app-update:save-record", e)
            // The record is a convenience; a failed write only means the next start checks again.
        }
        return outcome
    }
}
