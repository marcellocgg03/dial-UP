package dev.dialup.app

// Guards for the places where a failure must be logged and survived instead of ending the casting.
// Pure Kotlin (no android imports) so it is tested on the JVM. GPL-3.0-only.

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** How long CastFeature waits before it tries to start a bridge again after a start that threw or found no Wi-Fi. */
const val START_RETRY_MS = 30_000L

/** Longest exception message that goes into the evidence log (it can carry paths, URLs or device names). */
private const val MAX_MESSAGE_CHARS = 200

/** The fields of a `feature-error` event: where it happened, the exception class and the message cut to 200 characters. */
fun featureErrorFields(where: String, t: Throwable): Map<String, Any?> =
    mapOf("where" to where, "error" to t.javaClass.name, "message" to t.message?.take(MAX_MESSAGE_CHARS))

/**
 * Runs [block]; true when it completed. A failure is passed to [report] (which may itself fail: that is dropped, logging
 * must not end the guard) and gives false. [CancellationException] is never swallowed.
 */
suspend fun runGuarded(where: String, report: (String, Throwable) -> Unit, block: suspend () -> Unit): Boolean {
    try {
        block()
        return true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        try {
            report(where, t)
        } catch (_: Throwable) {
            // dropped on purpose
        }
        return false
    }
}

/**
 * A [CoroutineExceptionHandler] for the service scope: an exception that escapes a launched coroutine is reported as
 * `uncaught` instead of ending the process. Cancellation never reaches a handler; a failing [report] is dropped.
 * Nothing global is installed; the handler is passed to the scope explicitly.
 */
fun loggingExceptionHandler(report: (String, Throwable) -> Unit): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, t ->
        if (t !is CancellationException) {
            try {
                report("uncaught", t)
            } catch (_: Throwable) {
                // dropped on purpose
            }
        }
    }

/**
 * Like `launch { block() }`, but [finish] runs exactly once when the job completes for any reason: normal end, failure,
 * cancellation, or a scope that was already cancelled so [block] never started. It exists for `goAsync()` receivers,
 * whose `PendingResult.finish()` and wake-lock release must happen once. An exception in [block] is not swallowed: it
 * reaches the scope's handler as with a plain `launch`. A failing [finish] is dropped.
 */
fun CoroutineScope.launchFinishing(finish: () -> Unit, block: suspend () -> Unit): Job {
    val done = AtomicBoolean(false)
    val once = {
        if (done.compareAndSet(false, true)) {
            try {
                finish()
            } catch (_: Throwable) {
                // dropped on purpose
            }
        }
    }
    val job = launch { block() }
    job.invokeOnCompletion { once() }
    return job
}
