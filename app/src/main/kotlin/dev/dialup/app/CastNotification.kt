package dev.dialup.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * How many renderers are being cast to (D-06). "Casting" means a speaker whose player status is
 * loading, playing or paused; `total` is every speaker that currently has a receiver.
 */
data class CastCount(val casting: Int, val total: Int)

/** The persistent notification text (D-06, SVC-01). */
fun notificationText(c: CastCount): String = "${c.casting} ${if (c.casting == 1) "renderer" else "renderers"} being cast to"

/** The notification text while the phone has no Wi-Fi or hotspot address: the bridge waits and nothing can be cast. */
const val NOTIFICATION_NO_NETWORK_TEXT = "No Wi-Fi: waiting for a network"

/** What the notification says: the cast count, or the waiting line while there is no network. */
fun notificationText(c: CastCount, networkUp: Boolean): String = if (networkUp) notificationText(c) else NOTIFICATION_NO_NETWORK_TEXT

/**
 * Implemented by a feature that binds to a network. False while it has none, which puts the notification on the quiet
 * channel (no status bar icon) until the network is back. A service with no such feature counts as up.
 */
interface NetworkStateSource {
    val networkUp: StateFlow<Boolean>
}

/**
 * Implemented by every feature that knows about speakers (the cast feature of plan 02-13). The count
 * is derived from feature state, never incremented or decremented by events (RESEARCH Pitfall 11).
 */
interface CastCountSource {
    val castCount: StateFlow<CastCount>
}

/**
 * Sums casting and total over every source. An empty list gives [CastCount] (0, 0) so the
 * notification reads "0 renderers being cast to" before the cast feature exists. Equal
 * consecutive sums are dropped so the notification is only re-posted when its text can change.
 */
fun sumCastCounts(sources: List<CastCountSource>): Flow<CastCount> {
    if (sources.isEmpty()) return flowOf(CastCount(0, 0))
    return combine(sources.map { it.castCount }) { counts ->
        CastCount(counts.sumOf { it.casting }, counts.sumOf { it.total })
    }.distinctUntilChanged()
}
