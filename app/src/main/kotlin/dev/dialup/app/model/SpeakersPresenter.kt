// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import dev.t2u.lounge.PlayerStatus
import dev.t2u.renderer.SpeakerAction
import dev.t2u.renderer.SpeakerUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the play/pause button does now: it is one button that shows [PLAY] or [PAUSE], or is greyed out. */
enum class PlayPause { PLAY, PAUSE, DISABLED }

/** Which of a card's buttons can be tapped (plan 07-08, GUI-02). There is no volume button (T-02-36). */
data class CardButtons(val playPause: PlayPause, val stop: Boolean, val previous: Boolean, val next: Boolean, val clear: Boolean)

private val NO_BUTTONS = CardButtons(PlayPause.DISABLED, stop = false, previous = false, next = false, clear = false)

/**
 * Enables a button only when its action can do something (RESEARCH Pattern 5): pause while playing; play while paused, or
 * while stopped with something to play; stop while loading, playing or paused; previous and next from the queue's navigation
 * info; clear whenever the speaker is online. An offline speaker, or one whose last command is still running ([busy], Pitfall 9),
 * has every button off.
 */
fun buttonsFor(s: SpeakerUi, busy: Boolean): CardButtons {
    if (!s.online || busy) return NO_BUTTONS
    val playPause = when (s.status) {
        PlayerStatus.PLAYING -> PlayPause.PAUSE
        PlayerStatus.PAUSED -> PlayPause.PLAY
        PlayerStatus.STOPPED -> if (s.queueHasCurrent || s.track != null) PlayPause.PLAY else PlayPause.DISABLED
        PlayerStatus.IDLE, PlayerStatus.LOADING -> PlayPause.DISABLED
    }
    val stop = s.status == PlayerStatus.PLAYING || s.status == PlayerStatus.PAUSED || s.status == PlayerStatus.LOADING
    return CardButtons(playPause, stop, previous = s.hasPrevious, next = s.hasNext, clear = true)
}

/** The action behind the play/pause button, or null when it is disabled. */
fun playPauseAction(p: PlayPause): SpeakerAction? = when (p) {
    PlayPause.PLAY -> SpeakerAction.PLAY
    PlayPause.PAUSE -> SpeakerAction.PAUSE
    PlayPause.DISABLED -> null
}

/**
 * Whether the saved manual speakers are drawn as greyed cards (D-07, review WR-04). They are listed whenever there is no bridge:
 * the switch is off, or the service runs without one (no Wi-Fi, a failed start, the start retry, a reload restart). Once a
 * bridge exists its own list already merges the offline manual entries (plan 07-14), so drawing them again would duplicate them.
 * [serviceUp] is part of the signature so the rule reads as "service state, bridge state" but does not change the answer.
 */
fun savedManualVisible(serviceUp: Boolean, bridgeUp: Boolean): Boolean = !bridgeUp

/**
 * Sends card taps to [command] and keeps one command in flight per speaker (RESEARCH Pitfall 9: the Player has no mutex). A press
 * for a speaker already in [busy] is dropped, so a double tap or a tap during a slow SOAP call sends one command; another speaker's
 * press runs in parallel. A command that throws still frees its card. Pure: no Android types.
 */
class SpeakersPresenter(
    private val scope: CoroutineScope,
    private val command: suspend (String, SpeakerAction) -> Boolean,
) {
    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** The speakers whose last card command has not returned yet. */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    fun press(udn: String, action: SpeakerAction) {
        var claimed = false
        _busy.update { claimed = udn !in it; if (claimed) it + udn else it }
        if (!claimed) return
        scope.launch {
            try {
                command(udn, action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a card tap never crashes the app; the speaker's own state shows what happened
            } finally {
                _busy.update { it - udn }
            }
        }
    }
}
