// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

/** GUI-14: how long the Renderers list may look for renderers, in milliseconds, before the Refresh button appears. */
const val REFRESH_AFTER_MS = 8_000L

/** What the empty Renderers list shows under a switched-on bridge. */
enum class EmptyPhase { SPINNER, SPINNER_WITH_REFRESH }

/** The spinner alone until [lookingForMs] reaches [showRefreshAfterMs], then the spinner with the Refresh button. */
fun emptyPhase(lookingForMs: Long, showRefreshAfterMs: Long = REFRESH_AFTER_MS): EmptyPhase =
    if (lookingForMs >= showRefreshAfterMs) EmptyPhase.SPINNER_WITH_REFRESH else EmptyPhase.SPINNER
