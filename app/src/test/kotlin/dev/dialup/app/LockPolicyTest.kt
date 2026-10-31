package dev.dialup.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockPolicyTest {
    private var now = 1_000_000L
    private val policy = LockPolicy(clock = { now })

    @Test
    fun `a new policy does not hold`() {
        assertFalse(policy.shouldHold())
    }

    @Test
    fun `idle counts on a new policy do not start a grace`() {
        assertFalse(policy.onCasting(0))
        now += 1_000
        assertFalse(policy.shouldHold())
    }

    @Test
    fun `casting holds`() {
        assertTrue(policy.onCasting(1))
        now += 10 * 60_000
        assertTrue(policy.shouldHold())
    }

    @Test
    fun `holds for 59 s after the count drops to 0 and not at 60 s`() {
        policy.onCasting(1)
        assertTrue(policy.onCasting(0))
        now += 59_000
        assertTrue(policy.shouldHold())
        now += 1_000
        assertFalse(policy.shouldHold())
    }

    @Test
    fun `the grace restarts at the last drop to 0`() {
        policy.onCasting(1)
        policy.onCasting(0) // t
        now += 30_000
        assertTrue(policy.onCasting(2)) // t+30
        now += 10_000
        policy.onCasting(0) // t+40
        now += 59_000 // t+99
        assertTrue(policy.shouldHold())
        now += 1_000 // t+100
        assertFalse(policy.shouldHold())
    }

    @Test
    fun `repeated idle counts do not move the start of the grace`() {
        policy.onCasting(1)
        policy.onCasting(0)
        now += 30_000
        policy.onCasting(0)
        now += 29_000
        policy.onCasting(0)
        assertTrue(policy.shouldHold())
        now += 1_000
        assertFalse(policy.shouldHold())
    }

    @Test
    fun `constants`() {
        assertEquals(60_000L, PLAY_LOCK_GRACE_MS)
        assertEquals(600_000L, WAKE_LOCK_TIMEOUT_MS)
    }
}
