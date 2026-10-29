package dev.t2u.app

// Plan 05-19: guards for the places where a failure must be logged and survived instead of ending the casting.
// Pure Kotlin (no android imports) so it is tested on the JVM. GPL-3.0-only.

import kotlin.coroutines.cancellation.CancellationException

/** How long CastFeature waits before it tries to start a bridge again after a start that threw or found no Wi-Fi. */
const val START_RETRY_MS = 30_000L

/** Red-commit seam (plan 05-19 task 1): replaced in the implementation commit. */
fun featureErrorFields(where: String, t: Throwable): Map<String, Any?> = emptyMap()

/** Red-commit seam (plan 05-19 task 1): replaced in the implementation commit. */
suspend fun runGuarded(where: String, report: (String, Throwable) -> Unit, block: suspend () -> Unit): Boolean {
    block()
    return true
}
