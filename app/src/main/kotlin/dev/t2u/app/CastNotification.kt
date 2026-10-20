package dev.t2u.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * How many speakers are being cast to (D-06). "Casting" means a speaker whose player status is
 * loading, playing or paused; `total` is every speaker that currently has a receiver.
 */
data class CastCount(val casting: Int, val total: Int)

/** The persistent notification text (D-06, SVC-01). */
fun notificationText(c: CastCount): String = "${c.casting} of ${c.total} speakers being cast to"

/**
 * Implemented by every feature that knows about speakers (the cast feature of plan 02-13). The count
 * is derived from feature state, never incremented or decremented by events (RESEARCH Pitfall 11).
 */
interface CastCountSource {
    val castCount: StateFlow<CastCount>
}

/**
 * Sums casting and total over every source. An empty list gives [CastCount] (0, 0) so the
 * notification reads "0 of 0 speakers being cast to" before the cast feature exists. Equal
 * consecutive sums are dropped so the notification is only re-posted when its text can change.
 */
fun sumCastCounts(sources: List<CastCountSource>): Flow<CastCount> {
    if (sources.isEmpty()) return flowOf(CastCount(0, 0))
    return combine(sources.map { it.castCount }) { counts ->
        CastCount(counts.sumOf { it.casting }, counts.sumOf { it.total })
    }.distinctUntilChanged()
}
