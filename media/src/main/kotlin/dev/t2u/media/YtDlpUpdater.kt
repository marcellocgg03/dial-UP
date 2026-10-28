package dev.t2u.media

// Keeps yt-dlp current in the background (PLAY-02): the port of resolver.js scheduleYtdlpUpdates. Pure JVM, no android
// imports, so the schedule and the failure handling run in unit tests. GPL-3.0-only.

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
) {
    /** One attempt; never throws except for cancellation. The outcome was also delivered to the event sink. */
    suspend fun updateOnce(trigger: String): YtDlpUpdateOutcome {
        val outcome = withContext(dispatcher) {
            val chosen = try {
                channel().takeIf { it in YTDLP_CHANNELS } ?: YTDLP_DEFAULT_CHANNEL
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                YTDLP_DEFAULT_CHANNEL
            }
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
        try {
            onEvent(outcome.toEvent())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // a broken evidence sink must not end the loop
        }
        return outcome
    }

    /** Waits, then updates forever; it only ends by cancellation. */
    suspend fun run() {
        pause(startDelayMs)
        var trigger = "start"
        while (true) {
            val outcome = updateOnce(trigger)
            trigger = "scheduled"
            pause(if (outcome.ok) intervalMs else minOf(retryMs, intervalMs))
        }
    }
}
