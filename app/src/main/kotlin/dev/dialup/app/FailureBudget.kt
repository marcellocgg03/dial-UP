// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

/**
 * Failed starts or moves in a row before the bridge gives up. With the existing back-off (2 s twice after a bind failure, else
 * [START_RETRY_MS]) that is about a minute for a busy port and about two minutes otherwise.
 */
const val BRIDGE_FAILURE_LIMIT = 5

/** What to do after one more failed attempt. */
enum class FailureStep {
    /** Try again later. */
    RETRY,

    /** The limit was just reached: report once and stop retrying. */
    GIVE_UP,

    /** The limit was reached before: say nothing more. */
    ALREADY_GAVE_UP,
}

/**
 * Counts the failed starts and moves of one bridge run. [fail] says what to do next; [reset] ends the run of failures (a success,
 * or an outcome with no Wi-Fi, which is not a failure). Pure and thread-safe: the retry job and the first start both reach it.
 */
internal class FailureBudget(private val limit: Int = BRIDGE_FAILURE_LIMIT) {
    init {
        require(limit >= 1) { "the failure limit must be at least 1, was $limit" }
    }

    private var count = 0
    private var gaveUp = false

    /** The failed attempts in the current run; stays at the limit after the give-up. */
    val attempts: Int
        @Synchronized get() = count

    /** Counts one failed attempt: [FailureStep.RETRY] below the limit, [FailureStep.GIVE_UP] exactly once at it, then [FailureStep.ALREADY_GAVE_UP]. */
    @Synchronized
    fun fail(): FailureStep {
        if (gaveUp) return FailureStep.ALREADY_GAVE_UP
        count++
        if (count < limit) return FailureStep.RETRY
        gaveUp = true
        return FailureStep.GIVE_UP
    }

    /** Ends the run of failures, and clears the gave-up state. */
    @Synchronized
    fun reset() {
        count = 0
        gaveUp = false
    }
}
