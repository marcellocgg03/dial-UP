// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where a caught failure that the user never sees is recorded. A tap, a refresh or a background save that fails must not crash the
 * app, but it must leave a trace in the event log so the author can find it later.
 *
 * Use it for catch sites that would otherwise hide a real failure. Cleanup catches (closing a file, a fallback value for a parse
 * that may fail) keep their current form and do not call it. Recording is all it does: it changes nothing the user sees.
 */
object Failures {
    private const val TAG = "t2u"

    /**
     * Writes a `feature-error` event for [t] (where it happened, the exception class and the message cut to 200 characters) to [log].
     * With no open log the line goes to logcat instead, so a failure is still visible while the service is down. A
     * [CancellationException] is rethrown at once and nothing is written. A failure while logging is dropped: reporting must never
     * end the caller.
     */
    fun report(where: String, t: Throwable, log: EvidenceLog? = EvidenceLog.current()) {
        if (t is CancellationException) throw t
        try {
            if (log != null && !log.isClosed) {
                log.event("feature-error", featureErrorFields(where, t))
            } else {
                // android.util.Log is not available in a plain JVM unit test, hence the guard (as in EvidenceLog.warn)
                runCatching { Log.w(TAG, "$where: ${t.javaClass.name}: ${t.message}") }
            }
        } catch (_: Throwable) {
            // dropped on purpose: logging must never end the caller
        }
    }
}
