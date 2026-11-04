// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

/** GUI-14: how long the Renderers list may look for renderers, in milliseconds, before the Refresh button appears. */
const val REFRESH_AFTER_MS = 8_000L

/** GUI-14: the least time between two searches that the app coming to the foreground starts, in milliseconds. */
const val FOREGROUND_SEARCH_GAP_MS = 10_000L

/** What the empty Renderers list shows under a switched-on bridge. */
enum class EmptyPhase { SPINNER, SPINNER_WITH_REFRESH }

/** The spinner alone until [lookingForMs] reaches [showRefreshAfterMs], then the spinner with the Refresh button. */
fun emptyPhase(lookingForMs: Long, showRefreshAfterMs: Long = REFRESH_AFTER_MS): EmptyPhase =
    if (lookingForMs >= showRefreshAfterMs) EmptyPhase.SPINNER_WITH_REFRESH else EmptyPhase.SPINNER

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
