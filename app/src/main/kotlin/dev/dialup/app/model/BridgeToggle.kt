// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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

/** What the header switch draws: [checked] is its position, [usable] whether it reacts to taps. */
data class ToggleUi(val checked: Boolean, val usable: Boolean)

/**
 * The switch shows the persisted state ([enabled]) and is greyed out without the battery exemption ([exempt]). Flipping
 * it never opens the exemption request; that is the battery banner's Grant button. A grant only makes the switch usable
 * and never flips it (author's decision 2026-11-04); the start itself stays behind [startDecision] and the service gate.
 */
fun toggleUi(enabled: Boolean, exempt: Boolean): ToggleUi = ToggleUi(checked = enabled, usable = exempt)

/**
 * The confirmation shown before the bridge is switched off while [casting] speakers are loading, playing
 * or paused. It says what happens to them: cleared when the clear-on-cast-end setting is on, otherwise they
 * keep their track loaded (D-03).
 */
fun confirmText(casting: Int, clearOnCastEnd: Boolean): String {
    val one = casting == 1
    val who = if (one) "1 renderer is" else "$casting renderers are"
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

/** What a start path does: start the service, wait for Wi-Fi, or nothing. */
enum class StartDecision { START, WAIT_FOR_WIFI, NONE }

/**
 * What a start path (app open, boot, the switch, the deferred start) does. A start without a network would be stopped at
 * once by the idle rule (plan 07.1-01), so the caller arms NetworkResume instead and the bridge starts when Wi-Fi comes
 * (RESEARCH Pitfall 3). [bindable] is whether the bridge can bind: a Wi-Fi client address or the phone's own hotspot.
 */
fun startDecision(enabled: Boolean, exempt: Boolean, running: Boolean, bindable: Boolean): StartDecision = when {
    !shouldStartService(enabled, exempt, running) -> StartDecision.NONE
    !bindable -> StartDecision.WAIT_FOR_WIFI
    else -> StartDecision.START
}

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
 * Suspends until [flow] holds null, which is the thing being waited for: true as soon as it does, false when [timeoutMs] ran
 * out first. (`first { it == null }` itself returns null, so its result cannot tell success from a timeout.)
 */
suspend fun <T : Any> awaitNull(flow: StateFlow<T?>, timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
    flow.first { it == null }
    true
} == true

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
