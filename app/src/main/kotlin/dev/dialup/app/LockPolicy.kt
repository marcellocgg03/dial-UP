// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

/** How long the Wi-Fi and wake locks stay held after the last speaker stops casting. */
const val PLAY_LOCK_GRACE_MS = 60_000L

/** Every wake lock acquire carries this timeout, re-armed by the heartbeat. */
const val WAKE_LOCK_TIMEOUT_MS = 600_000L

/**
 * Decides whether the Wi-Fi lock and the partial wake lock should be held. Pure JVM.
 *
 * Held while at least one speaker is casting and for [graceMs] after the count drops to 0, so a
 * track change or a queue advance that passes through a non-casting state does not drop them. The
 * grace starts at the last drop to 0; repeated idle counts do not move it.
 *
 * Finding: no Wi-Fi lock mode has an effect with the screen off on current
 * Android, so the Wi-Fi lock stays for the screen-on benefit and the wake lock plus the battery
 * exemption carry screen-off playback. The multicast lock is not governed by this policy.
 */
class LockPolicy(
    private val graceMs: Long = PLAY_LOCK_GRACE_MS,
    private val clock: () -> Long,
) {
    private var castingNow = false
    private var droppedAtMs: Long? = null

    /** Feeds the current casting count; returns [shouldHold]. */
    @Synchronized
    fun onCasting(casting: Int): Boolean {
        if (casting > 0) {
            castingNow = true
            droppedAtMs = null
        } else if (castingNow) {
            castingNow = false
            droppedAtMs = clock()
        }
        return shouldHold()
    }

    @Synchronized
    fun shouldHold(): Boolean {
        if (castingNow) return true
        val dropped = droppedAtMs ?: return false
        return clock() - dropped < graceMs
    }
}
