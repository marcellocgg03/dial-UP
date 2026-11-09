// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

/**
 * How long the bridge must stay out of a multicast search window before the empty list counts as settled. The start-up
 * sequence searches twice, 5 s apart, and each search holds its reply window for 3 s, so there is a gap of about 2 s
 * between the two that must not flicker the spinner off.
 */
const val SEARCH_IDLE_GRACE_MS = 2_500L

/** The least time between two searches that the app coming to the foreground starts, in milliseconds. */
const val FOREGROUND_SEARCH_GAP_MS = 10_000L

/** What the empty Renderers list shows under a switched-on bridge. */
enum class EmptyPhase { SPINNER, IDLE_WITH_REFRESH }

/**
 * The spinner while the bridge is [searching] (inside a multicast reply window) and until the bridge has been idle for
 * [SEARCH_IDLE_GRACE_MS] ([idleSettled]); after that nothing was found and the list offers the Refresh button.
 */
fun emptyPhase(searching: Boolean, idleSettled: Boolean): EmptyPhase =
    if (searching || !idleSettled) EmptyPhase.SPINNER else EmptyPhase.IDLE_WITH_REFRESH

/**
 * Lets one call through per [minGapMs] of [clock] time. A clock that went back (the last grant lies in the future) grants,
 * so a stuck stamp never blocks searches for good.
 */
class SearchThrottle(private val clock: () -> Long, private val minGapMs: Long) {
    private var lastGrantAt: Long? = null

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        val last = lastGrantAt
        if (last == null || now < last || now - last >= minGapMs) {
            lastGrantAt = now
            return true
        }
        return false
    }
}
