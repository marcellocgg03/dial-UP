// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import dev.dialup.app.AppModel
import dev.t2u.lounge.PlayerStatus
import dev.t2u.renderer.ManualSpeaker
import dev.t2u.renderer.SpeakerAction
import dev.t2u.renderer.SpeakerUi
import dev.t2u.renderer.savedSpeakerCards
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
 * Enables a button only when its action can do something: pause while playing; play while paused, or
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
 * Whether a card offers Hide (GUI-03, PD-3): when the renderer answers, or when it is saved by address, so its greyed card can be
 * hidden too. An offline card that is not saved has nothing to hide.
 */
fun hideOffered(s: SpeakerUi): Boolean = s.online || s.manual

/**
 * Whether a card offers Delete (quick 261101-oib): only for a renderer added by address (saved in manual.json), whether it answers
 * or not. A discovered-only renderer comes back at the next search, so there is nothing to delete; Hide is what keeps it away.
 */
fun deleteOffered(s: SpeakerUi): Boolean = s.manual

/**
 * Whether the saved manual speakers are drawn as greyed cards (D-07, review WR-04). They are drawn whenever the running bridge's
 * own list is not shown: the switch is off (also while the old service is still stopping), or the service or the bridge is not up
 * (no Wi-Fi, a failed start, the start retry, a reload restart). With a bridge up under an enabled switch its own list already
 * merges the offline manual entries (plan 07-14), so drawing them again would duplicate them.
 */
fun savedManualVisible(switchOn: Boolean, serviceUp: Boolean, bridgeUp: Boolean): Boolean = !(switchOn && serviceUp && bridgeUp)

/**
 * What the empty Renderers list shows: [BRIDGE_OFF] "The bridge is off", [LOOKING] the spinner (the bridge is starting or
 * searching), [WAITING_FOR_WIFI] "Waiting for Wi-Fi".
 */
enum class ListNote { BRIDGE_OFF, LOOKING, WAITING_FOR_WIFI }

/** The Renderers list as drawn: the running bridge's cards [live], the greyed [saved] cards, and the [note] when [live] is empty. */
data class RendererListing(val live: List<SpeakerUi>, val saved: List<SpeakerUi>, val note: ListNote?)

/**
 * The one rule behind the Renderers list (author's request of 2026-11-01: with the Bridge enable switch off, no auto-discovered
 * renderer is listed, only the ones saved by address, greyed). The running bridge's list ([live]) is shown only when the switch
 * is on and the service and the bridge are both up, and then [saved] is not drawn (the bridge's list already merges it). In every
 * other case [live] is empty and [saved] are the greyed saved cards ([savedSpeakerCards]: hidden UDNs are skipped, user [names]
 * applied), so Hide and Restore are unchanged and a saved renderer that is also discovered shows once. The note is null while
 * live cards are shown; otherwise "waiting for Wi-Fi" when [waitingForWifi], else "looking" (the spinner) when the switch is on,
 * else "bridge off". [switchOn] is the switch as the bridge sees it: on and the battery exemption held. The DIAL targets go
 * with the bridge stop (Bridge.stop, registry.removeAll), not with this rule.
 */
fun rendererListing(
    switchOn: Boolean,
    serviceUp: Boolean,
    bridgeUp: Boolean,
    waitingForWifi: Boolean,
    live: List<SpeakerUi>,
    saved: List<ManualSpeaker>,
    hidden: Set<String>,
    names: Map<String, String>,
): RendererListing {
    val showLive = !savedManualVisible(switchOn, serviceUp, bridgeUp)
    val shownLive = if (showLive) live else emptyList()
    val shownSaved = if (showLive) emptyList() else savedSpeakerCards(saved, hidden, names)
    val note = when {
        shownLive.isNotEmpty() -> null
        waitingForWifi -> ListNote.WAITING_FOR_WIFI
        switchOn -> ListNote.LOOKING
        else -> ListNote.BRIDGE_OFF
    }
    return RendererListing(shownLive, shownSaved, note)
}

/**
 * Why Add speaker cannot work now, or null when it can (review WR-04): the switch is off ([serviceUp] false), or the service runs
 * but has no bridge yet ([serviceUp] true, [bridgeUp] false: waiting for Wi-Fi, a failed start or a restart), or a bridge exists. [waitingForWifi] (the switch is on and the phone has no Wi-Fi) says the bridge waits, also with the
 * service stopped.
 */
fun addRefusal(serviceUp: Boolean, bridgeUp: Boolean, waitingForWifi: Boolean): String? = when {
    bridgeUp -> null
    waitingForWifi || serviceUp -> AppModel.BRIDGE_WAITING_ADD
    else -> AppModel.BRIDGE_OFF_ADD
}

/**
 * Sends card taps to [command] and keeps one command in flight per speaker (the Player has no mutex). A press
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
