// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/** What the bridge service does about a Wi-Fi loss; [wire] is the word the `net-pause` evidence event carries. */
enum class LossAction(val wire: String) {
    CONTINUE("continue"),
    WAIT("wait"),
    STOP_IDLE("idle"),
    STOP_EXPIRED("expired"),
}

/**
 * How long a cast waits for Wi-Fi to come back before the service stops (WIFI-02). The timed outage test of plan 07.1-11
 * tunes this value.
 */
const val WIFI_LOSS_WINDOW_MS = 120_000L

/**
 * How long a bridge with nothing being cast waits after Wi-Fi is gone before the service stops. Zero: the network
 * callbacks already are the grace (WIFI-01). The timed outage test of plan 07.1-11 may raise it.
 */
const val IDLE_STOP_DELAY_MS = 0L

/**
 * The whole decision for a given moment of a Wi-Fi outage. A phone with a Wi-Fi client or hotspot address continues;
 * without one and with nothing being cast the service stops at once; with a cast it waits [windowMs] and then stops.
 */
fun lossAction(wifiPresent: Boolean, casting: Int, downForMs: Long, windowMs: Long = WIFI_LOSS_WINDOW_MS): LossAction = when {
    wifiPresent -> LossAction.CONTINUE
    casting <= 0 -> LossAction.STOP_IDLE
    downForMs.coerceAtLeast(0L) >= windowMs -> LossAction.STOP_EXPIRED
    else -> LossAction.WAIT
}

/**
 * Turns two flows (is there a Wi-Fi client or hotspot address, how many renderers are being cast to) into a single stop
 * decision. The window is keyed on system-level presence, never on the bridge's own network state, so a return shortly
 * before the window ends whose rebind is still running is not cut off (RESEARCH Pitfall 2). The start of the outage is
 * kept across changes of the cast count, so a count change during an outage does not restart the window. A cast that
 * ends during the outage turns the wait into the idle stop at once (RESEARCH Pitfall 8). [onStop] runs once, with the
 * action and how long the network had been gone, and then [run] returns.
 */
class NetworkLossWatcher(
    private val wifiPresent: Flow<Boolean>,
    private val casting: Flow<Int>,
    private val clock: () -> Long,
    private val windowMs: Long = WIFI_LOSS_WINDOW_MS,
    private val idleStopDelayMs: Long = IDLE_STOP_DELAY_MS,
    private val onStop: suspend (LossAction, Long) -> Unit,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run() {
        var lostAt: Long? = null
        val decision = combine(wifiPresent.distinctUntilChanged(), casting.map { it > 0 }.distinctUntilChanged()) { present, isCasting ->
            present to isCasting
        }.transformLatest { (present, isCasting) ->
            if (present) {
                lostAt = null
                return@transformLatest
            }
            val since = lostAt ?: clock().also { lostAt = it }
            if (!isCasting) {
                val wait = idleStopDelayMs - (clock() - since)
                if (wait > 0) delay(wait)
                emit(LossAction.STOP_IDLE to (clock() - since))
            } else {
                val wait = windowMs - (clock() - since)
                if (wait > 0) delay(wait)
                emit(LossAction.STOP_EXPIRED to (clock() - since))
            }
        }.first()
        onStop(decision.first, decision.second)
    }
}
