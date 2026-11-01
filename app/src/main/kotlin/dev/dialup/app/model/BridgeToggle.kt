// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** What the header switch does when the author flips it. */
enum class ToggleAction { START, STOP, CONFIRM_STOP, NOTHING }

/**
 * The answer to a flip of the switch (D-02, D-03). Turning on starts; turning off stops at once when
 * nothing is loading, playing or paused ([casting] is 0) and asks first otherwise. A flip to the state
 * the switch already holds does nothing.
 */
fun toggleAction(requestedOn: Boolean, enabled: Boolean, casting: Int): ToggleAction = when {
    requestedOn == enabled -> ToggleAction.NOTHING
    requestedOn -> ToggleAction.START
    casting > 0 -> ToggleAction.CONFIRM_STOP
    else -> ToggleAction.STOP
}

/**
 * The confirmation shown before the bridge is switched off while [casting] speakers are loading, playing
 * or paused. It says what happens to them: cleared when the clear-on-cast-end setting is on, otherwise they
 * keep their track loaded (D-03).
 */
fun confirmText(casting: Int, clearOnCastEnd: Boolean): String {
    val one = casting == 1
    val who = if (one) "1 speaker is" else "$casting speakers are"
    val outcome = when {
        clearOnCastEnd && one -> "It is cleared when the bridge stops."
        clearOnCastEnd -> "They are cleared when the bridge stops."
        one -> "It keeps its track loaded."
        else -> "They keep their track loaded."
    }
    return "$who being cast to. Switch the bridge off? $outcome"
}

/**
 * Whether a start of the service may be issued now: the switch is on, the battery exemption is held and the
 * service is not already running (D-04, T-07-09, T-07-11).
 */
fun shouldStartService(enabled: Boolean, exempt: Boolean, running: Boolean): Boolean = enabled && exempt && !running

/** What an in-app restart of the bridge does first. */
enum class RestartStep { RESTART_NOW, CONFIRM_FIRST }

/** A restart cuts playback like a switch-off does, so it asks first while anything is loading, playing or paused (D-03, T-07-42). */
fun restartStep(casting: Int): RestartStep = if (casting > 0) RestartStep.CONFIRM_FIRST else RestartStep.RESTART_NOW

/**
 * Off, wait for the service to be gone, then on. Switching on is guaranteed: it runs when the wait timed out and also when
 * the caller is cancelled during the wait (the screen closed mid-restart), under [NonCancellable], so a restart never leaves
 * the bridge off by its own doing (review CR-03). Returns whether the old service stopped in time; a cancellation during the
 * wait is rethrown after the switch-on.
 */
suspend fun restartSequence(setEnabled: (Boolean) -> Unit, awaitStopped: suspend () -> Boolean): Boolean {
    setEnabled(false)
    var stopped = false
    try {
        stopped = awaitStopped()
    } finally {
        withContext(NonCancellable) { setEnabled(true) }
    }
    return stopped
}

/**
 * Single flight: [launch] starts [block] on the given scope and returns its [Deferred]; while that one is still active a
 * further [launch] returns it instead of starting a second. An awaiting caller that is cancelled does not cancel the work,
 * which belongs to the scope.
 */
class SingleFlight<T> {
    private var current: Deferred<T>? = null

    fun launch(scope: CoroutineScope, block: suspend () -> T): Deferred<T> = synchronized(this) {
        current?.takeIf { it.isActive }
            ?: scope.async { block() }.also { current = it }
    }
}

/**
 * The deferred start behind a quick off-then-on flip (review WR-01): waits for the old service to be gone ([awaitGone], which
 * also covers the process-wide stop), then starts only when the switch is still wanted ([stillWanted] re-reads the persisted
 * switch, the battery gate and that no service runs, D-04). Returns whether [start] ran; false when the old service never went
 * away or the switch was turned off again meanwhile.
 */
suspend fun startWhenStopped(awaitGone: suspend () -> Boolean, stillWanted: () -> Boolean, start: () -> Unit): Boolean {
    if (!awaitGone()) return false
    if (!stillWanted()) return false
    start()
    return true
}
