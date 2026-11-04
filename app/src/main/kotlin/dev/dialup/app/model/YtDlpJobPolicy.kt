// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

/** What the daily yt-dlp job does on one run; [wire] is the `action` field of the `ytdlp-job` event. */
enum class YtDlpJobAction(val wire: String) {
    /** The bridge switch is off: no background traffic. */
    SKIP_OFF("off"),

    /** Something is cast: leave it to the next period. */
    POSTPONE_CASTING("casting"),

    /** The service runs: its updater (cast gate, record) does the update. */
    IN_BRIDGE("in-bridge"),

    /** No service: a standalone updater on the same record does the update. */
    OFFLINE("offline"),
}

/**
 * Picks the updater for one job run and skips the obvious cases. The record and the casting gate inside YtDlpUpdater
 * decide again, so this is only the first filter. [serviceUp] false means nothing is cast. Pure (no android.*).
 */
fun ytdlpJobAction(enabled: Boolean, serviceUp: Boolean, casting: Int): YtDlpJobAction = when {
    !enabled -> YtDlpJobAction.SKIP_OFF
    !serviceUp -> YtDlpJobAction.OFFLINE
    casting > 0 -> YtDlpJobAction.POSTPONE_CASTING
    else -> YtDlpJobAction.IN_BRIDGE
}
