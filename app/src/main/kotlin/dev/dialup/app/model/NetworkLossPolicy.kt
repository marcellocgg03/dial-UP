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

/** A Wi-Fi outage this short is treated as a blip: keep the bridge available to rebind and resync. */
const val WIFI_REBIND_WINDOW_MS = 5_000L

/**
 * The whole decision for a given moment of a Wi-Fi outage. A phone with a Wi-Fi client or hotspot address continues;
 * otherwise the bridge remains available for [windowMs] so a blip can rebind, then stops whether or not a cast is active.
 */
fun lossAction(wifiPresent: Boolean, casting: Int, downForMs: Long, windowMs: Long = WIFI_REBIND_WINDOW_MS): LossAction = when {
    wifiPresent -> LossAction.CONTINUE
    downForMs.coerceAtLeast(0L) < windowMs -> LossAction.WAIT
    casting <= 0 -> LossAction.STOP_IDLE
    else -> LossAction.STOP_EXPIRED
}

/**
 * Turns Wi-Fi presence and the active-cast count into a stop decision. The outage window is keyed on system-level
 * presence, never on the bridge's own network state, and is shared by idle and casting states so a cast-count change
 * cannot restart or prematurely end a blip recovery. [onStop] runs once after a continuous outage and [run] returns.
 */
class NetworkLossWatcher(
    private val wifiPresent: Flow<Boolean>,
    private val casting: Flow<Int>,
    private val clock: () -> Long,
    private val windowMs: Long = WIFI_REBIND_WINDOW_MS,
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
            val wait = windowMs - (clock() - since)
            if (wait > 0) delay(wait)
            emit((if (isCasting) LossAction.STOP_EXPIRED else LossAction.STOP_IDLE) to (clock() - since))
        }.first()
        onStop(decision.first, decision.second)
    }
}
