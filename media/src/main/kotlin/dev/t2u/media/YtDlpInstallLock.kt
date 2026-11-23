package dev.t2u.media

// One yt-dlp install at a time in the process. Pure JVM, no android imports. GPL-3.0-only.

/**
 * Wraps the library's install call only. A second install while one runs fails at once with an [IllegalStateException]
 * instead of waiting: the updater turns any throw into a FAILED outcome, so a refused install is recorded as a failed
 * attempt and never as a success (a returned status would be recorded as one). It never waits and is never held across the
 * postponed wait for a lookup (adaptation 43): that wait happens before the install starts. It is not reentrant.
 *
 * Instances exist so tests do not share process state; the app uses [PROCESS] from every updater (the in-bridge one, the
 * offline one of Settings and the daily job), which all run in one process.
 */
class YtDlpInstallLock {
    fun <T> install(block: () -> T): T = block()

    companion object {
        /** The one instance the app installs through. */
        val PROCESS = YtDlpInstallLock()
    }
}
