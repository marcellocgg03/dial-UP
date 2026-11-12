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

/** The source the restart card's button gives the restart (the `settings` evidence event says it). */
const val RESTART_SOURCE = "restart"

/** The source of a restart the app made on its own when it was opened (T-dcg-06): the event log tells it from the author's. */
const val AUTO_RESTART_SOURCE = "auto-restart"

/** How long the info card after a name change stays. */
const val NOTICE_MS = 10_000L

/**
 * The settings a running bridge reads only at its start: the [SettingKey] entries with `needsRestart` (gapless and the forced
 * output format). Keep the two in step.
 */
data class RestartFields(val gapless: Boolean, val outputFormat: String)

/** The restart-relevant part of [e]. */
fun restartFields(e: EffectiveSettings): RestartFields = RestartFields(gapless = e.gapless, outputFormat = e.outputFormat)

/** What opening the app does about a restart that is still owed. */
enum class AutoRestart { NONE, RESTART, SHOW_CARD }

/**
 * The rule for an app open. "Pending" is derived, never stored: a bridge runs ([running] is the
 * restart fields it was started with, null without a bridge) and they differ from the [saved] ones. A bridge started after the
 * change read the new values, so nothing is pending for it, and a restart makes the two equal, so it does not repeat. Never
 * a silent restart while anything is loading, playing or paused ([casting] above 0): the restart card is shown instead. With the
 * switch off ([switchedOn] false) nothing happens.
 */
fun autoRestartOnOpen(running: RestartFields?, saved: RestartFields, switchedOn: Boolean, casting: Int): AutoRestart = when {
    running == null || !switchedOn || running == saved -> AutoRestart.NONE
    casting > 0 -> AutoRestart.SHOW_CARD
    else -> AutoRestart.RESTART
}

/**
 * The thin "Restart the bridge to apply this change" card and the question behind its button.
 *
 * The card shows after a setting that needs a restart while the bridge runs, and hides itself after [visibleMs]. Hiding only
 * hides: the timer never runs [action]. Pressing Restart goes through [restartStep]: with nothing loading, playing or paused the
 * action runs at once, otherwise [confirmPending] asks first and [confirmRestart] runs it. The action runs once at a time, so a
 * double tap cannot restart twice.
 * The action gets the source of the restart ([RESTART_SOURCE], or [AUTO_RESTART_SOURCE] for [restartNow]).
 *
 * Opening the app with a restart owed: [restartNow] runs the action at once, and [showUntilCastEnds]
 * keeps the card up with no timer until [onCastingChanged] says nothing is cast any more.
 */
class RestartPrompt(
    private val scope: CoroutineScope,
    private val visibleMs: Long = 6_000L,
    private val action: suspend (source: String) -> Boolean,
) {
    private val card = MutableStateFlow(false)
    private val pending = MutableStateFlow(false)
    private val names = MutableStateFlow(false)
    private val noticeCard = MutableStateFlow(false)
    private var noticeTimer: Job? = null
    private var timer: Job? = null
    private var running = false

    /** True while the card was shown by [showUntilCastEnds] and has no timer. */
    private var sticky = false

    /** True while the card is on screen. */
    val visible: StateFlow<Boolean> = card.asStateFlow()

    /** True while the confirmation for a restart during a cast is open. */
    val confirmPending: StateFlow<Boolean> = pending.asStateFlow()

    /**
     * True from a rename or prefix change until the next restart: the YouTube apps keep the old names until they are cleared
     * from recents, which this app cannot do, so the card says so.
     */
    val namesChanged: StateFlow<Boolean> = names.asStateFlow()

    /** True while the info card is shown: a new name was applied live and only the YouTube apps are left to refresh. */
    val notice: StateFlow<Boolean> = noticeCard.asStateFlow()

    /** Hides the info card (its close icon). */
    @Synchronized
    fun dismissNotice() {
        noticeTimer?.cancel()
        noticeTimer = null
        noticeCard.value = false
    }

    /**
     * A new prefix applied live with nothing cast: shows the info card (no button) for [NOTICE_MS], which says to clear the
     * YouTube apps from recents.
     */
    @Synchronized
    fun showNamesNotice() {
        noticeTimer?.cancel()
        noticeCard.value = true
        noticeTimer = scope.launch {
            delay(NOTICE_MS)
            noticeCard.value = false
        }
    }

    /** Shows the card for [visibleMs] when [key] needs a restart and the bridge is [bridgeRunning]; otherwise does nothing. */
    @Synchronized
    fun onSettingChanged(key: SettingKey, bridgeRunning: Boolean) {
        if (!key.needsRestart || !bridgeRunning) return
        if (key == SettingKey.NAME_PREFIX || key == SettingKey.RENAME) names.value = true
        timer?.cancel()
        card.value = true
        if (sticky) {
            timer = null
            return
        }
        timer = scope.launch {
            delay(visibleMs)
            card.value = false
        }
    }

    /** Shows the card with no timer; it stays until [onCastingChanged] reports nothing cast, or [dismiss]. */
    @Synchronized
    fun showUntilCastEnds() {
        timer?.cancel()
        timer = null
        sticky = true
        card.value = true
    }

    /** Hides a card that [showUntilCastEnds] showed once [casting] is 0; a card shown after a setting change is left alone. */
    @Synchronized
    fun onCastingChanged(casting: Int) {
        if (sticky && casting == 0) {
            sticky = false
            card.value = false
        }
    }

    /** Hides the card (its close icon). Runs nothing. */
    @Synchronized
    fun dismiss() {
        timer?.cancel()
        timer = null
        sticky = false
        card.value = false
    }

    /** Restarts now, with no question: the app was opened with nothing cast. Hides the card; runs once at a time. */
    fun restartNow() {
        dismiss()
        run(AUTO_RESTART_SOURCE)
    }

    /** The card's Restart button: [casting] speakers are loading, playing or paused right now. */
    fun requestRestart(casting: Int) {
        dismiss()
        when (restartStep(casting)) {
            RestartStep.RESTART_NOW -> run(RESTART_SOURCE)
            RestartStep.CONFIRM_FIRST -> pending.value = true
        }
    }

    /** The confirmation's Yes: runs the restart once. Ignored when nothing is pending. */
    fun confirmRestart() {
        if (!pending.compareAndSet(expect = true, update = false)) return
        run(RESTART_SOURCE)
    }

    /** The confirmation's No. */
    fun cancelRestart() {
        pending.value = false
    }

    @Synchronized
    private fun run(source: String) {
        if (running) return
        running = true
        names.value = false
        scope.launch {
            try {
                action(source)
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
