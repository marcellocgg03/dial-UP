package dev.dialup.app

import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeldLocksTest {
    private class FakeLock(
        override val name: String,
        override val mode: String,
        held: Boolean = false,
        private val releaseThrows: Boolean = false,
    ) : HeldLock {
        @Volatile
        private var heldNow = held
        val acquires = AtomicInteger()
        val releases = AtomicInteger()
        override val isHeld: Boolean get() = heldNow

        override fun acquire() {
            acquires.incrementAndGet()
            heldNow = true
        }

        override fun release() {
            releases.incrementAndGet()
            if (releaseThrows) throw IllegalStateException("under-locked")
            heldNow = false
        }
    }

    private val events = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Map<String, Any?>>>())
    private val mcast = FakeLock("t2u:mcast", "multicast", held = true)
    private val wifi = FakeLock("t2u:wifi", "FULL_LOW_LATENCY")
    private val wake = FakeLock("t2u:cpu", "PARTIAL_WAKE_LOCK")
    private val locks = HeldLocks(mcast, wifi, wake) { kind, fields -> events.add(kind to fields) }

    @Test
    fun applyTrueAcquiresWifiAndWake() {
        locks.apply(true, "casting")
        assertTrue(wifi.isHeld)
        assertTrue(wake.isHeld)
        assertEquals(1, wifi.acquires.get())
        assertEquals(1, wake.acquires.get())
        val lockEvents = events.filter { it.first == "lock" }.map { it.second }
        assertEquals(listOf("t2u:wifi", "t2u:cpu"), lockEvents.map { it["name"] })
        assertEquals(listOf("casting", "casting"), lockEvents.map { it["reason"] })
        assertEquals("FULL_LOW_LATENCY", lockEvents[0]["mode"])
        assertEquals(true, lockEvents[0]["isHeld"])
        // a second apply re-arms the wake lock but logs no new transition
        locks.apply(true, "tick")
        assertEquals(1, wifi.acquires.get())
        assertEquals(2, wake.acquires.get())
        assertEquals(2, events.count { it.first == "lock" })
    }

    @Test
    fun releaseAllReleasesEveryLock() {
        locks.apply(true, "casting")
        events.clear()
        locks.releaseAll()
        assertFalse(mcast.isHeld)
        assertFalse(wifi.isHeld)
        assertFalse(wake.isHeld)
        val lockEvents = events.filter { it.first == "lock" }.map { it.second }
        assertEquals(listOf("t2u:mcast", "t2u:wifi", "t2u:cpu"), lockEvents.map { it["name"] })
        assertTrue(lockEvents.all { it["reason"] == "service-stop" && it["isHeld"] == false })
    }

    @Test
    fun anApplyAfterTheReleaseAcquiresNothing() {
        locks.releaseAll()
        events.clear()
        locks.apply(true, "tick")
        assertEquals(0, wifi.acquires.get())
        assertEquals(0, wake.acquires.get())
        assertFalse(wifi.isHeld)
        assertFalse(wake.isHeld)
        assertTrue(events.isEmpty(), "no event after the release: $events")
    }

    @Test
    fun concurrentApplyAndReleaseNeverEndHeld() {
        repeat(1000) {
            val m = FakeLock("t2u:mcast", "multicast", held = true)
            val w = FakeLock("t2u:wifi", "FULL_LOW_LATENCY")
            val c = FakeLock("t2u:cpu", "PARTIAL_WAKE_LOCK")
            val holder = HeldLocks(m, w, c) { _, _ -> }
            val start = java.util.concurrent.CountDownLatch(1)
            val applier =
                thread {
                    start.await()
                    repeat(50) { holder.apply(true, "tick") }
                }
            val releaser =
                thread {
                    start.await()
                    holder.releaseAll()
                }
            start.countDown()
            applier.join()
            releaser.join()
            assertFalse(m.isHeld || w.isHeld || c.isHeld, "round $it ended with a held lock")
        }
    }

    @Test
    fun applyFalseReleasesWithTheIdleGraceReason() {
        locks.apply(true, "casting")
        events.clear()
        locks.apply(false, "tick")
        assertFalse(wifi.isHeld)
        assertFalse(wake.isHeld)
        assertTrue(mcast.isHeld, "the multicast lock follows the service, not the cast count")
        val lockEvents = events.filter { it.first == "lock" }.map { it.second }
        assertEquals(listOf("t2u:wifi", "t2u:cpu"), lockEvents.map { it["name"] })
        assertTrue(lockEvents.all { it["reason"] == "idle-grace" && it["isHeld"] == false })
    }

    @Test
    fun aFailingReleaseIsAFeatureErrorAndTheLockEventStillFollows() {
        val bad = FakeLock("t2u:wifi", "FULL_LOW_LATENCY", held = true, releaseThrows = true)
        val holder = HeldLocks(mcast, bad, wake) { kind, fields -> events.add(kind to fields) }
        holder.releaseAll()
        val error = events.first { it.first == "feature-error" }.second
        assertEquals("release:t2u:wifi", error["where"])
        assertEquals("java.lang.IllegalStateException", error["error"])
        assertTrue(events.any { it.first == "lock" && it.second["name"] == "t2u:wifi" && it.second["reason"] == "service-stop" })
    }
}
