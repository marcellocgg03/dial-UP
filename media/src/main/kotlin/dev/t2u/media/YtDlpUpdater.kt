package dev.t2u.media

// Keeps yt-dlp current in the background: the port of resolver.js scheduleYtdlpUpdates. Pure JVM, no android
// imports, so the schedule and the failure handling run in unit tests. GPL-3.0-only.

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

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

/** One yt-dlp update may take this long (Node: `execFileP(cmd, args, { timeout: 5 * 60 * 1000 })`, resolver.js:131); then it fails. */
const val YTDLP_UPDATE_TIMEOUT_MS: Long = 5 * 60_000L

private const val ERROR_LIMIT = 300

/** How long the version is waited for after a timed out update: the installed files may be half replaced. */
private const val VERSION_REREAD_TIMEOUT_MS = 10_000L

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
 *
 * The install is bounded by [updateTimeoutMs] ([YTDLP_UPDATE_TIMEOUT_MS], as in Node), counted from the moment it starts:
 * the postponed wait comes before and is never part of it. A timeout ends FAILED and the installed version is read again,
 * because the library may have half replaced its files. The schedule lock ([dueMutex]) is held for decisions and
 * bookkeeping only, never across an update: one update at a time per updater runs through a private claim (BUSY while it is
 * taken), and one install at a time per process through [YtDlpInstallLock].
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
    /** The longest one install may take, counted from the moment it starts; then the attempt ends FAILED. */
    private val updateTimeoutMs: Long = YTDLP_UPDATE_TIMEOUT_MS,
) {
    /** One attempt; never throws except for cancellation. The outcome was also delivered to the event sink. */
    suspend fun updateOnce(trigger: String): YtDlpUpdateOutcome {
        val chosen = chosenChannel()
        val before = withContext(dispatcher) { runCatching { gateway.version() }.getOrNull() }
        val t0 = System.nanoTime()
        // The install runs detached, so the bound holds even for a library call that ignores a thread interrupt (it is
        // abandoned on a timeout; YtDlpInstallLock turns any overlap with the next attempt into a quick failure).
        val install = updateScope.async { gateway.update(chosen) }
        val outcome = try {
            val status = withTimeoutOrNull(updateTimeoutMs) { install.await() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (status != null) {
                val after = withContext(dispatcher) { runCatching { gateway.version() }.getOrNull() }
                YtDlpUpdateOutcome(true, trigger, chosen, before, after, status, ms)
            } else {
                install.cancel()
                val after = rereadVersion()
                val text = "TimeoutException: the yt-dlp update timed out after ${timeoutText(updateTimeoutMs)}"
                YtDlpUpdateOutcome(false, trigger, chosen, before, after, "FAILED", ms, text.take(ERROR_LIMIT))
            }
        } catch (e: CancellationException) {
            install.cancel()
            throw e
        } catch (t: Throwable) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            val text = (t::class.java.simpleName.ifEmpty { "Throwable" }) + ": " + Resolver.redactUrls(t.message.orEmpty())
            YtDlpUpdateOutcome(false, trigger, chosen, before, before, "FAILED", ms, text.take(ERROR_LIMIT))
        }
        deliver(outcome.toEvent())
        return outcome
    }

    /**
     * The installed version after a timed out update, read detached on [Dispatchers.IO] (the updater's own dispatcher may be
     * held by the abandoned install) and bounded; null when it does not answer in time or cannot be read.
     */
    private suspend fun rereadVersion(): String? {
        val read = updateScope.async(Dispatchers.IO) { runCatching { gateway.version() }.getOrNull() }
        return try {
            withTimeoutOrNull(VERSION_REREAD_TIMEOUT_MS) { read.await() }
        } finally {
            read.cancel()
        }
    }

    private fun timeoutText(ms: Long): String = if (ms >= 1000L && ms % 1000L == 0L) "${ms / 1000} s" else "$ms ms"

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
     * status BUSY while a lookup runs ([busy]) or another update of this updater is in flight (the claim), so two updates
     * never overlap. A successful one writes the record and moves the next scheduled run a full [intervalMs] away once
     * [run] has begun. The outcome (a refusal included) was also delivered to the event sink.
     */
    suspend fun updateNow(trigger: String = "manual"): YtDlpUpdateOutcome {
        if (isCasting()) {
            val outcome = YtDlpUpdateOutcome(false, trigger, chosenChannel(), null, null, YTDLP_STATUS_CASTING, 0)
            deliver(outcome.toEvent())
            return outcome
        }
        if (isBusy() || !updating.compareAndSet(false, true)) return busyOutcome(trigger)
        try {
            val outcome = updateOnce(trigger)
            if (outcome.ok) {
                withContext(NonCancellable) {
                    dueMutex.withLock {
                        val wall = wall()
                        saveRecord((loadRecord() ?: YtDlpRecord()).copy(lastSuccessWallMs = wall, lastAttemptWallMs = wall))
                        if (nextDueAt != null) nextDueAt = now() + intervalMs
                    }
                }
            }
            return outcome
        } finally {
            updating.set(false)
        }
    }

    private fun busyOutcome(trigger: String): YtDlpUpdateOutcome {
        val outcome = YtDlpUpdateOutcome(false, trigger, chosenChannel(), null, null, "BUSY", 0)
        deliver(outcome.toEvent())
        return outcome
    }

    /**
     * For the daily job: one update with [trigger], but only when it is due by the record and nothing is cast or in
     * flight. Null (and a SKIPPED event) when the record says it is not due; a POSTPONED outcome (and event, no gateway
     * call) while casting or busy; a BUSY outcome while another update of this updater is in flight. The schedule lock is
     * held for the decisions only. After a success it moves [run]'s next deadline a full [intervalMs] away.
     */
    suspend fun updateIfDue(trigger: String): YtDlpUpdateOutcome? {
        val tried = dueMutex.withLock {
            if (updating.get()) return busyOutcome(trigger)
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
            beginAttempt(rec) ?: return busyOutcome(trigger)
        }
        val outcome = finishAttempt(trigger, tried)
        if (outcome.ok) {
            withContext(NonCancellable) { dueMutex.withLock { if (nextDueAt != null) nextDueAt = now() + intervalMs } }
        }
        return outcome
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

    /** Guards the schedule decisions, held briefly and never across an update: the loop and an alarm tick ([runIfDue]) for one deadline decide once. */
    private val dueMutex = Mutex()

    /** An update of this updater is in flight (from the decision to the end of the install); a second one is answered BUSY. */
    private val updating = AtomicBoolean(false)

    /** Detached from every caller, so the 5 minute bound holds for an install that ignores a thread interrupt. */
    private val updateScope = CoroutineScope(SupervisorJob() + dispatcher)

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
        false // unreadable setting: no download without the user's consent
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

    /**
     * Under [dueMutex]: takes the update claim and saves the attempt time. Returns the saved record, or null when an update
     * of this updater is in flight already (nothing was saved).
     */
    private fun beginAttempt(loaded: YtDlpRecord?): YtDlpRecord? {
        if (!updating.compareAndSet(false, true)) return null
        try {
            val base = loaded ?: loadRecord() ?: YtDlpRecord()
            val tried = base.copy(lastAttemptWallMs = wall())
            saveRecord(tried)
            return tried
        } catch (t: Throwable) {
            updating.set(false)
            throw t
        }
    }

    /** Outside [dueMutex]: updates, saves the success time when it worked (a failure keeps the old success), frees the claim. */
    private suspend fun finishAttempt(trigger: String, tried: YtDlpRecord): YtDlpUpdateOutcome {
        try {
            val outcome = updateOnce(trigger)
            if (outcome.ok) {
                withContext(NonCancellable) { dueMutex.withLock { saveRecord(tried.copy(lastSuccessWallMs = wall())) } }
            }
            return outcome
        } finally {
            updating.set(false)
        }
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
     * flight it announces POSTPONED once per deadline and re-checks after [busyWaitMs], with no maximum wait, and so it does
     * while another update of this updater runs (no queueing on the mutex); otherwise it saves the attempt, leaves the mutex,
     * updates, and takes it again to save the success and set the next deadline.
     */
    private suspend fun dueRun(): YtDlpUpdateOutcome? {
        val trigger: String
        val tried = dueMutex.withLock {
            val due = nextDueAt ?: return null
            if (now() < due) return null
            trigger = if (firstRun) "start" else "scheduled"
            if (!isAutoEnabled()) {
                if (!offAnnounced) {
                    offAnnounced = true
                    deliver(skippedEvent(trigger, "off"))
                }
                nextDueAt = now() + offRecheckMs
                return null
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
                    return null
                }
            }
            val why = postponeReason()
            if (why != null) {
                if (!postponedAnnounced) {
                    postponedAnnounced = true
                    deliver(linkedMapOf("status" to "POSTPONED", "trigger" to trigger, "channel" to chosenChannel(), "reason" to why))
                }
                nextDueAt = now() + busyWaitMs
                return null
            }
            val begun = beginAttempt(rec)
            if (begun == null) {
                nextDueAt = now() + busyWaitMs
                return null
            }
            postponedAnnounced = false
            begun
        }
        val outcome = finishAttempt(trigger, tried)
        withContext(NonCancellable) {
            dueMutex.withLock {
                firstRun = false
                nextDueAt = now() + (if (outcome.ok) intervalMs else minOf(retryMs, intervalMs))
            }
        }
        return outcome
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
