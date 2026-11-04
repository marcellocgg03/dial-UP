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

/** The notification text while a renderer is being cast to and the phone has no Wi-Fi: the bridge waits for it to return. */
const val NOTIFICATION_WAITING_TEXT = "Waiting for Wi-Fi"

/**
 * What the one notification on the one channel says: the waiting line while something is being cast to and the phone has
 * no network, the cast count otherwise. An idle bridge without a network is stopping, so it keeps the count text.
 */
fun notificationText(c: CastCount, waiting: Boolean): String = if (waiting && c.casting > 0) NOTIFICATION_WAITING_TEXT else notificationText(c)

/**
 * Implemented by a feature that binds to a network. [networkUp] is false while it has none (after its own debounce and
 * rebind decision). A service with no such feature counts as up.
 */
interface NetworkStateSource {
    val networkUp: StateFlow<Boolean>

    /**
     * True while the phone has a Wi-Fi client or hotspot address according to the network callbacks, whatever the bridge's
     * rebind state; the Wi-Fi-loss watcher keys its window on it.
     */
    val wifiPresent: StateFlow<Boolean>
}

/**
 * Implemented by every feature that knows about speakers (the cast feature of plan 02-13). The count
 * is derived from feature state, never incremented or decremented by events.
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
