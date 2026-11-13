package dev.t2u.media

// Keeps yt-dlp current in the background: the port of resolver.js scheduleYtdlpUpdates. Pure JVM, no android
// imports, so the schedule and the failure handling run in unit tests. GPL-3.0-only.

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The update channels youtubedl-android knows; the single source of truth (anything else is treated as stable). */
val YTDLP_CHANNELS: Set<String> = setOf("stable", "nightly")

const val YTDLP_DEFAULT_CHANNEL: String = "stable"

/** The first update follows the start by this long: the warm-up resolve and the payload extraction go first. */
const val YTDLP_UPDATE_START_DELAY_MS: Long = 30_000L

const val YTDLP_UPDATE_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

/** After a failed attempt (offline phone, GitHub down) the next try comes sooner than the daily one. */
const val YTDLP_UPDATE_RETRY_MS: Long = 60L * 60 * 1000

/** The status of an Update now that was refused because a renderer is being cast to (author's decision, 2026-11-04). */
const val YTDLP_STATUS_CASTING: String = "CASTING"

private const val ERROR_LIMIT = 300

/**
 * What a look-ahead found: the installed [current] version, the newest published [latest] on [channel], and whether [latest]
 * is newer ([available]). [available] is null when [latest] could not be read.
 */
data class YtDlpCheck(val current: String?, val latest: String?, val channel: String, val available: Boolean?)

/**
 * Whether [latest] is a newer yt-dlp version than [current]. Versions are dotted numbers (2026.11.01, or 2026.11.07.021234
 * for nightly builds) compared part by part; anything that does not parse counts as newer when it differs. An unknown
 * [current] is older than any [latest].
 */
fun ytdlpNewer(latest: String, current: String?): Boolean {
    if (current == null) return true
    val a = latest.trim().split('.').map { it.toLongOrNull() }
    val b = current.trim().split('.').map { it.toLongOrNull() }
    if (a.any { it == null } || b.any { it == null }) return latest.trim() != current.trim()
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrNull(i) ?: 0L
        val y = b.getOrNull(i) ?: 0L
        if (x != y) return x > y
    }
    return false
}

/**
 * Whether [version] is a nightly build: nightly tags carry a six-digit time part after the date (`2026.11.07.021234`), stable
 * tags are the plain date (`2026.11.01`). An unreadable or missing version is not taken for a nightly.
 */
fun isNightlyVersion(version: String?): Boolean {
    val parts = version?.trim()?.split('.') ?: return false
    return parts.size >= 4 && parts[3].length == 6 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } }
}

/**
 * Whether installing [latest] from [channel] changes anything: it does when it is newer ([ytdlpNewer]), and also when the
 * channel is not the one the installed [current] build came from. Leaving nightly for stable looks like a downgrade by
 * number, but it is the author's choice of channel, so it counts as available.
 */
fun ytdlpAvailable(latest: String, current: String?, channel: String): Boolean {
    if (ytdlpNewer(latest, current)) return true
    return current != null && latest.trim() != current.trim() && isNightlyVersion(current) != (channel == "nightly")
}

/** What one update attempt did. [before] and [after] are yt-dlp versions (null when unreadable); [error] is redacted. */
data class YtDlpUpdateOutcome(
    val ok: Boolean,
    val trigger: String,
    val channel: String,
    val before: String?,
    val after: String?,
    val status: String,
    val ms: Long,
    val error: String? = null,
) {
    /** The evidence fields of the `ytdlp-update` event; the first five are those of the adb diagnostic. */
    fun toEvent(): Map<String, Any?> = linkedMapOf(
        "before" to before,
        "status" to status,
        "after" to after,
        "channel" to channel,
        "ms" to ms,
        "ok" to ok,
        "trigger" to trigger,
        "error" to error,
    )
}

/**
 * Runs `gateway.update` [startDelayMs] after [run] begins and then every [intervalMs] ([retryMs] after a failure), on
 * its own dispatcher and its own coroutine. It takes no lock the cast path uses, catches every Throwable except
 * cancellation, and a throwing [onEvent] sink cannot end the loop. [channel] is read before every attempt, so an edited
 * config takes effect at the next one; a value outside [YTDLP_CHANNELS] (or a throw) means [YTDLP_DEFAULT_CHANNEL].
 *
 * The rule: no update of any kind runs while something is cast to ([casting]); no automatic one runs while a
 * lookup or file build is in flight ([busy]) either, and there is no maximum wait: a postponed update is re-checked every
 * [busyWaitMs] until the way is clear. Automatic updates also follow the persisted [record]: none sooner than
 * [YTDLP_DUE_SLACK_MS] short of a day after a success and none sooner than [retryMs] after an attempt, so a restart or a
 * Wi-Fi flap never re-updates. Update now ([updateNow]) is refused while casting (status [YTDLP_STATUS_CASTING]).
 */
class YtDlpUpdater(
    private val gateway: YtDlpGateway,
    private val channel: () -> String,
    private val onEvent: (Map<String, Any?>) -> Unit,
    private val startDelayMs: Long = YTDLP_UPDATE_START_DELAY_MS,
    private val intervalMs: Long = YTDLP_UPDATE_INTERVAL_MS,
    private val retryMs: Long = YTDLP_UPDATE_RETRY_MS,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Monotonic clock that keeps counting in deep sleep (the app passes SystemClock.elapsedRealtime); null = virtual. */
    private val elapsedMs: (() -> Long)? = null,
    /** Longest single wait of [run]; the deadline is re-checked after each, so a long sleep cannot hide it. */
    private val maxPauseMs: Long = Long.MAX_VALUE,
    /** True while a yt-dlp lookup runs: a due update waits for it. */
    private val busy: () -> Boolean = { false },
    private val busyWaitMs: Long = 60_000L,
    /** True while any renderer is being cast to: nothing updates then, however long it lasts. */
    private val casting: () -> Boolean = { false },
    /** The persisted last success and last attempt; null = no persistence, every deadline updates (as before). */
    private val record: YtDlpRecordStore? = null,
    /** Wall clock (the record's clock); the app passes the system one. */
    private val wallMs: () -> Long = { System.currentTimeMillis() },
    /** False while the author has switched the automatic update off: the loop and [updateIfDue] do nothing; [updateNow] is unaffected. */
    private val autoEnabled: () -> Boolean = { true },
    /** How often the loop looks at [autoEnabled] again while it is off. */
    private val offRecheckMs: Long = 15 * 60_000L,
) {
    /** One attempt; never throws except for cancellation. The outcome was also delivered to the event sink. */
    suspend fun updateOnce(trigger: String): YtDlpUpdateOutcome {
        val outcome = withContext(dispatcher) {
            val chosen = chosenChannel()
            val before = runCatching { gateway.version() }.getOrNull()
            val t0 = System.nanoTime()
            try {
                val status = gateway.update(chosen)
                val ms = (System.nanoTime() - t0) / 1_000_000
                val after = runCatching { gateway.version() }.getOrNull()
                YtDlpUpdateOutcome(true, trigger, chosen, before, after, status, ms)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val ms = (System.nanoTime() - t0) / 1_000_000
                val text = (t::class.java.simpleName.ifEmpty { "Throwable" }) + ": " + Resolver.redactUrls(t.message.orEmpty())
                YtDlpUpdateOutcome(false, trigger, chosen, before, before, "FAILED", ms, text.take(ERROR_LIMIT))
            }
        }
        deliver(outcome.toEvent())
        return outcome
    }

    /**
     * Asks whether a newer yt-dlp is published on the chosen channel, installing nothing. It is allowed while casting, and it
     * never touches the record or [dueMutex]. Never throws except for cancellation; [YtDlpCheck.available] is null when the
     * newest version could not be read.
     */
    suspend fun check(): YtDlpCheck = withContext(dispatcher) {
        val chosen = chosenChannel()
        val current = runCatching { gateway.version() }.getOrNull()
        val latest = try {
            gateway.latestVersion(chosen)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        YtDlpCheck(current, latest, chosen, latest?.let { ytdlpAvailable(it, current, chosen) })
    }

    /**
     * One update on the chosen channel because the author asked for it. It refuses with status
     * [YTDLP_STATUS_CASTING], without touching the gateway or the record, while a renderer is being cast to; then with
     * status BUSY while a lookup runs ([busy]) or a scheduled update holds [dueMutex], so two updates never
     * overlap. A successful one writes the record and moves the next scheduled run a full [intervalMs] away once [run]
     * has begun. The outcome (a refusal included) was also delivered to the event sink.
     */
    suspend fun updateNow(trigger: String = "manual"): YtDlpUpdateOutcome {
        if (isCasting()) {
            val outcome = YtDlpUpdateOutcome(false, trigger, chosenChannel(), null, null, YTDLP_STATUS_CASTING, 0)
            deliver(outcome.toEvent())
            return outcome
        }
        if (isBusy() || !dueMutex.tryLock()) {
            val outcome = YtDlpUpdateOutcome(false, trigger, chosenChannel(), null, null, "BUSY", 0)
            deliver(outcome.toEvent())
            return outcome
        }
        try {
            val outcome = updateOnce(trigger)
            if (outcome.ok) {
                val wall = wall()
                saveRecord((loadRecord() ?: YtDlpRecord()).copy(lastSuccessWallMs = wall, lastAttemptWallMs = wall))
                if (nextDueAt != null) nextDueAt = now() + intervalMs
            }
            return outcome
        } finally {
            dueMutex.unlock()
        }
    }

    /**
     * For the daily job: one update with [trigger], but only when it is due by the record and nothing is cast or in
     * flight. Null (and a SKIPPED event) when the record says it is not due; a POSTPONED outcome (and event, no gateway
     * call) while casting or busy; a BUSY outcome while a scheduled or manual update holds [dueMutex]. After a success it
     * moves [run]'s next deadline a full [intervalMs] away.
     */
    suspend fun updateIfDue(trigger: String): YtDlpUpdateOutcome? {
        if (!dueMutex.tryLock()) {
            val outcome = YtDlpUpdateOutcome(false, trigger, chosenChannel(), null, null, "BUSY", 0)
            deliver(outcome.toEvent())
            return outcome
        }
        try {
            if (!isAutoEnabled()) {
                deliver(skippedEvent(trigger, "off"))
                return null
            }
            val rec = loadRecord()
            if (rec != null) {
                val reason = notDueReason(rec, wall())
                if (reason != null) {
                    deliver(skippedEvent(trigger, reason))
                    return null
                }
            }
            val why = postponeReason()
            if (why != null) {
                val outcome = YtDlpUpdateOutcome(false, trigger, chosenChannel(), null, null, "POSTPONED", 0)
                deliver(LinkedHashMap(outcome.toEvent()).apply { put("reason", why) })
                return outcome
            }
            val outcome = attempt(trigger, rec)
            if (outcome.ok && nextDueAt != null) nextDueAt = now() + intervalMs
            return outcome
        } finally {
            dueMutex.unlock()
        }
    }

    /** The installed yt-dlp version, read on the updater's own dispatcher; null when it cannot be read. */
    suspend fun version(): String? = withContext(dispatcher) {
        try {
            gateway.version()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /** Guards the due run: the loop and an alarm tick ([runIfDue]) for one deadline run one update. */
    private val dueMutex = Mutex()

    /** Virtual clock for when no [elapsedMs] is given: advanced by every pause this class makes. */
    private var virtualNow = 0L

    /** The next deadline on [now]'s clock; null until [run] has begun. */
    @Volatile private var nextDueAt: Long? = null

    private var firstRun = true

    /** POSTPONED was announced for the current deadline already. */
    private var postponedAnnounced = false

    private fun now(): Long = elapsedMs?.invoke() ?: virtualNow

    private suspend fun doPause(ms: Long) {
        pause(ms)
        virtualNow += ms
    }

    private fun isBusy(): Boolean = try {
        busy()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    private fun isAutoEnabled(): Boolean = try {
        autoEnabled()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        true
    }

    /** SKIPPED "off" was announced for the current stretch of the switch being off. */
    private var offAnnounced = false

    private fun isCasting(): Boolean = try {
        casting()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    /** Why an update must wait now ("casting" before "busy"), or null when the way is clear. */
    private fun postponeReason(): String? = when {
        isCasting() -> "casting"
        isBusy() -> "busy"
        else -> null
    }

    private fun wall(): Long = try {
        wallMs()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        System.currentTimeMillis()
    }

    /** The persisted record; null without a store, the empty record when the store throws. */
    private fun loadRecord(): YtDlpRecord? {
        val store = record ?: return null
        return try {
            store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            YtDlpRecord()
        }
    }

    private fun saveRecord(r: YtDlpRecord) {
        val store = record ?: return
        try {
            store.save(r)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // a failing record file must not stop an update
        }
    }

    /** null when an update is due by [rec]; else "recent" (a success is too fresh) or "retry-wait" (an attempt is). */
    private fun notDueReason(rec: YtDlpRecord, nowWall: Long): String? {
        if (ytdlpDue(rec, nowWall, intervalMs, YTDLP_DUE_SLACK_MS, retryMs)) return null
        val success = rec.lastSuccessWallMs?.takeIf { it <= nowWall }
        return if (success != null && nowWall - success < intervalMs - YTDLP_DUE_SLACK_MS) "recent" else "retry-wait"
    }

    /** Wall-clock time left until [rec] makes an update due. */
    private fun msUntilDue(rec: YtDlpRecord, nowWall: Long): Long {
        val success = rec.lastSuccessWallMs?.takeIf { it <= nowWall }
        val attempt = rec.lastAttemptWallMs?.takeIf { it <= nowWall }
        val successLeft = if (success == null) 0L else (intervalMs - YTDLP_DUE_SLACK_MS) - (nowWall - success)
        val attemptLeft = if (attempt == null) 0L else retryMs - (nowWall - attempt)
        return maxOf(successLeft, attemptLeft, 0L)
    }

    private fun skippedEvent(trigger: String, reason: String): Map<String, Any?> =
        linkedMapOf("status" to "SKIPPED", "trigger" to trigger, "channel" to chosenChannel(), "reason" to reason)

    /** Saves the attempt time, updates, and saves the success time when it worked (a failure keeps the old success). */
    private suspend fun attempt(trigger: String, loaded: YtDlpRecord?): YtDlpUpdateOutcome {
        val base = loaded ?: loadRecord() ?: YtDlpRecord()
        val tried = base.copy(lastAttemptWallMs = wall())
        saveRecord(tried)
        val outcome = updateOnce(trigger)
        if (outcome.ok) saveRecord(tried.copy(lastSuccessWallMs = wall()))
        return outcome
    }

    /**
     * Sets the first deadline [startDelayMs] away, then updates at every deadline forever; it only ends by cancellation.
     * The wait is cut into pauses of at most [maxPauseMs] and the deadline is compared with the clock after each one, so
     * with an elapsed clock a deadline that passed in deep sleep is caught at the next wake.
     */
    suspend fun run() {
        nextDueAt = now() + startDelayMs
        while (true) {
            while (true) {
                val due = nextDueAt ?: break
                val remaining = due - now()
                if (remaining <= 0) break
                doPause(minOf(remaining, maxPauseMs))
            }
            dueRun()
        }
    }

    /**
     * For the housekeeping alarm: null unless [run] has begun and its deadline has passed, else performs the due run, so
     * a deadline that passed while the coroutine timers stalled in Doze is not missed. The loop and this share one mutex
     * and one deadline, so they never update twice for the same deadline.
     */
    suspend fun runIfDue(): YtDlpUpdateOutcome? {
        val due = nextDueAt ?: return null
        if (now() < due) return null
        return dueRun()
    }

    /**
     * Under the mutex, re-checks that the deadline has passed. When the record says an update is not due it announces
     * SKIPPED once and moves the deadline to when it will be (at least [busyWaitMs] away); while something is cast or in
     * flight it announces POSTPONED once per deadline and re-checks after [busyWaitMs], with no maximum wait; otherwise it
     * saves the attempt, updates, saves the success and sets the next deadline.
     */
    private suspend fun dueRun(): YtDlpUpdateOutcome? = dueMutex.withLock {
        val due = nextDueAt ?: return@withLock null
        if (now() < due) return@withLock null
        val trigger = if (firstRun) "start" else "scheduled"
        if (!isAutoEnabled()) {
            if (!offAnnounced) {
                offAnnounced = true
                deliver(skippedEvent(trigger, "off"))
            }
            nextDueAt = now() + offRecheckMs
            return@withLock null
        }
        offAnnounced = false
        val rec = loadRecord()
        if (rec != null) {
            val nowWall = wall()
            val reason = notDueReason(rec, nowWall)
            if (reason != null) {
                deliver(skippedEvent(trigger, reason))
                nextDueAt = now() + maxOf(busyWaitMs, msUntilDue(rec, nowWall))
                firstRun = false
                postponedAnnounced = false
                return@withLock null
            }
        }
        val why = postponeReason()
        if (why != null) {
            if (!postponedAnnounced) {
                postponedAnnounced = true
                deliver(linkedMapOf("status" to "POSTPONED", "trigger" to trigger, "channel" to chosenChannel(), "reason" to why))
            }
            nextDueAt = now() + busyWaitMs
            return@withLock null
        }
        postponedAnnounced = false
        val outcome = attempt(trigger, rec)
        firstRun = false
        nextDueAt = now() + (if (outcome.ok) intervalMs else minOf(retryMs, intervalMs))
        outcome
    }

    private fun chosenChannel(): String = try {
        channel().takeIf { it in YTDLP_CHANNELS } ?: YTDLP_DEFAULT_CHANNEL
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        YTDLP_DEFAULT_CHANNEL
    }

    private fun deliver(event: Map<String, Any?>) {
        try {
            onEvent(event)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // a broken evidence sink must not end the loop
        }
    }
}
