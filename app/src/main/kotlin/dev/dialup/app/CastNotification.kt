package dev.dialup.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * How many renderers are being cast to. "Casting" means a speaker whose player status is
 * loading, playing or paused; `total` is every speaker that currently has a receiver.
 */
data class CastCount(val casting: Int, val total: Int)

/** A user-visible cast notification is present only while Wi-Fi is available and a renderer is actively casting. */
fun shouldShowCastNotification(c: CastCount, wifiAvailable: Boolean = true): Boolean = wifiAvailable && c.casting > 0

/** The cast notification text. */
fun notificationText(c: CastCount): String = "${c.casting} ${if (c.casting == 1) "renderer" else "renderers"} being cast to"

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
 * Implemented by every feature that knows about speakers (the cast feature). The count
 * is derived from feature state, never incremented or decremented by events.
 */
interface CastCountSource {
    val castCount: StateFlow<CastCount>

    /** Active playback count used only to decide how long the service waits for Wi-Fi after a disconnect. */
    val networkLossCastCount: StateFlow<CastCount> get() = castCount
}

/**
 * Sums casting and total over every source. An empty list gives [CastCount] (0, 0) so the
 * notification reads "0 renderers being cast to" before the cast feature exists. Equal
 * consecutive sums are dropped so the notification is only re-posted when its text can change.
 */
fun sumCastCounts(sources: List<CastCountSource>): Flow<CastCount> {
    return sumCounts(sources.map { it.castCount })
}

/** Sums actual playback counts independently of whether a cast notification is currently shown. */
fun sumNetworkLossCastCounts(sources: List<CastCountSource>): Flow<CastCount> =
    sumCounts(sources.map { it.networkLossCastCount })

private fun sumCounts(counts: List<StateFlow<CastCount>>): Flow<CastCount> {
    if (counts.isEmpty()) return flowOf(CastCount(0, 0))
    return combine(counts) { values ->
        CastCount(values.sumOf { it.casting }, values.sumOf { it.total })
    }.distinctUntilChanged()
}
