package dev.t2u.app

// Plan 05-19: guards for the places where a failure must be logged and survived instead of ending the casting.
// Pure Kotlin (no android imports) so it is tested on the JVM. GPL-3.0-only.

import kotlin.coroutines.cancellation.CancellationException

/** How long CastFeature waits before it tries to start a bridge again after a start that threw or found no Wi-Fi. */
const val START_RETRY_MS = 30_000L

/** Longest exception message that goes into the evidence log (D-11: it can carry paths, URLs or device names). */
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
