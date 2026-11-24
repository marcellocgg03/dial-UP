// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

/** One system lock (multicast, Wi-Fi or wake) as [HeldLocks] sees it; the service wraps the Android locks, tests use fakes. */
internal interface HeldLock {
    val name: String
    val mode: String
    val isHeld: Boolean

    fun acquire()

    fun release()
}

/**
 * The service's three locks behind one monitor and a released flag, so a stop can never be followed by a re-acquire.
 *
 * [apply] holds the Wi-Fi lock and the partial wake lock while `hold` (a speaker is casting or the grace is running) and
 * releases both otherwise; the wake lock's [HeldLock.acquire] carries its timeout and is re-armed on every call while
 * held (reference counting is off), so a stuck loop cannot keep the CPU awake for ever. Only transitions are logged.
 * [releaseAll] sets the released flag and releases whatever is held. Both are synchronized on this holder, so an [apply]
 * from a loop body that was already past its `isActive` check when the service scope was cancelled either runs before
 * the release (and is undone by it) or after it (and acquires nothing).
 */
internal class HeldLocks(
    private val mcast: HeldLock,
    private val wifi: HeldLock,
    private val wake: HeldLock,
    private val event: (String, Map<String, Any?>) -> Unit,
) {
    private var released = false

    @Synchronized
    fun apply(hold: Boolean, reason: String) {
        if (released) return
        if (hold) {
            if (!wifi.isHeld) {
                wifi.acquire()
                event("lock", mapOf("name" to wifi.name, "mode" to wifi.mode, "isHeld" to wifi.isHeld, "reason" to "casting"))
            }
            val wakeWasHeld = wake.isHeld
            wake.acquire()
            if (!wakeWasHeld) {
                event("lock", mapOf("name" to wake.name, "mode" to wake.mode, "isHeld" to wake.isHeld, "reason" to "casting"))
            }
        } else if (wifi.isHeld || wake.isHeld) {
            releaseOne(wifi, "idle-grace")
            releaseOne(wake, "idle-grace")
        }
    }

    @Synchronized
    fun releaseAll() {
        released = true
        releaseOne(mcast, "service-stop")
        releaseOne(wifi, "service-stop")
        releaseOne(wake, "service-stop")
    }

    private fun releaseOne(lock: HeldLock, reason: String) {
        try {
            if (lock.isHeld) lock.release()
        } catch (t: Throwable) {
            event("feature-error", featureErrorFields("release:${lock.name}", t))
        }
        event("lock", mapOf("name" to lock.name, "isHeld" to lock.isHeld, "reason" to reason))
    }
}
