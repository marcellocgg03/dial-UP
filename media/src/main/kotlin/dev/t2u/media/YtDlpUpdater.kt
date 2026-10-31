package dev.t2u.media

// Keeps yt-dlp current in the background (PLAY-02): the port of resolver.js scheduleYtdlpUpdates. Pure JVM, no android
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

private const val ERROR_LIMIT = 300

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
    /** True while a yt-dlp lookup runs: a due update waits for it (RESEARCH Pitfall 4). */
    private val busy: () -> Boolean = { false },
    private val busyWaitMs: Long = 60_000L,
    private val busyMaxWaitMs: Long = 600_000L,
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
     * GUI-07: one update on the chosen channel because the author asked for it. It refuses with status BUSY, without
     * touching the gateway, while a lookup runs ([busy]) or a scheduled update holds [dueMutex] (T-07-19), so two updates
     * never overlap. A successful one moves the next scheduled run a full [intervalMs] away once [run] has begun.
     * The outcome (BUSY included) was also delivered to the event sink.
     */
    suspend fun updateNow(trigger: String = "manual"): YtDlpUpdateOutcome {
        if (isBusy() || !dueMutex.tryLock()) {
            val outcome = YtDlpUpdateOutcome(false, trigger, chosenChannel(), null, null, "BUSY", 0)
            deliver(outcome.toEvent())
            return outcome
        }
        try {
            val outcome = updateOnce(trigger)
            if (outcome.ok && nextDueAt != null) nextDueAt = now() + intervalMs
            return outcome
        } finally {
            dueMutex.unlock()
        }
    }

    /** GUI-07: the installed yt-dlp version, read on the updater's own dispatcher; null when it cannot be read. */
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

    /**
     * Sets the first deadline [startDelayMs] away, then updates at every deadline forever; it only ends by cancellation.
     * The wait is cut into pauses of at most [maxPauseMs] and the deadline is compared with the clock after each one, so
     * with an elapsed clock a deadline that passed in deep sleep is caught at the next wake (RESEARCH Pitfall 3).
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
     * Under the mutex, re-checks that the deadline has passed, waits while a lookup runs (at most [busyMaxWaitMs], in
     * steps of [busyWaitMs], announced once as status POSTPONED), updates and sets the next deadline.
     */
    private suspend fun dueRun(): YtDlpUpdateOutcome? = dueMutex.withLock {
        val due = nextDueAt ?: return@withLock null
        if (now() < due) return@withLock null
        var waited = 0L
        var announced = false
        while (waited < busyMaxWaitMs && isBusy()) {
            if (!announced) {
                announced = true
                deliver(linkedMapOf("status" to "POSTPONED", "trigger" to (if (firstRun) "start" else "scheduled"), "channel" to chosenChannel(), "waitMaxMs" to busyMaxWaitMs))
            }
            val step = minOf(busyWaitMs, busyMaxWaitMs - waited)
            doPause(step)
            waited += step
        }
        val outcome = updateOnce(if (firstRun) "start" else "scheduled")
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
