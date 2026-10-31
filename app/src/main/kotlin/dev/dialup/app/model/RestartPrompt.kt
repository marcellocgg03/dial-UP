// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The thin "Restart the bridge to apply this change" card and the question behind its button (plan 07-10, D-09, D-03).
 *
 * The card shows after a setting that needs a restart while the bridge runs, and hides itself after [visibleMs]. Hiding only
 * hides: the timer never runs [action]. Pressing Restart goes through [restartStep]: with nothing loading, playing or paused the
 * action runs at once, otherwise [confirmPending] asks first and [confirmRestart] runs it. The action runs once at a time (T-07-24).
 */
class RestartPrompt(
    private val scope: CoroutineScope,
    private val visibleMs: Long = 6_000L,
    private val action: suspend () -> Boolean,
) {
    private val card = MutableStateFlow(false)
    private val pending = MutableStateFlow(false)
    private var timer: Job? = null
    private var running = false

    /** True while the card is on screen. */
    val visible: StateFlow<Boolean> = card.asStateFlow()

    /** True while the confirmation for a restart during a cast is open. */
    val confirmPending: StateFlow<Boolean> = pending.asStateFlow()

    /** Shows the card for [visibleMs] when [key] needs a restart and the bridge is [bridgeRunning]; otherwise does nothing. */
    @Synchronized
    fun onSettingChanged(key: SettingKey, bridgeRunning: Boolean) {
        if (!key.needsRestart || !bridgeRunning) return
        timer?.cancel()
        card.value = true
        timer = scope.launch {
            delay(visibleMs)
            card.value = false
        }
    }

    /** Hides the card (its close icon). Runs nothing. */
    @Synchronized
    fun dismiss() {
        timer?.cancel()
        timer = null
        card.value = false
    }

    /** The card's Restart button: [casting] speakers are loading, playing or paused right now. */
    fun requestRestart(casting: Int) {
        dismiss()
        when (restartStep(casting)) {
            RestartStep.RESTART_NOW -> run()
            RestartStep.CONFIRM_FIRST -> pending.value = true
        }
    }

    /** The confirmation's Yes: runs the restart once. Ignored when nothing is pending. */
    fun confirmRestart() {
        if (!pending.compareAndSet(expect = true, update = false)) return
        run()
    }

    /** The confirmation's No. */
    fun cancelRestart() {
        pending.value = false
    }

    @Synchronized
    private fun run() {
        if (running) return
        running = true
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the bridge control reports its own failures; a failed restart must not take the screen down
            } finally {
                synchronized(this@RestartPrompt) { running = false }
            }
        }
    }
}
