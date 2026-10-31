// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.model

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
