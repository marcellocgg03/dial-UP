// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkLossWatcherTest {
    private data class Stop(val action: LossAction, val downMs: Long, val at: Long)

    private class Rig(scope: TestScope, present: Boolean, casting: Int) {
        val present = MutableStateFlow(present)
        val casting = MutableStateFlow(casting)
        val stops = mutableListOf<Stop>()

        init {
            scope.launch {
                NetworkLossWatcher(this@Rig.present, this@Rig.casting, { scope.testScheduler.currentTime }) { action, down ->
                    stops += Stop(action, down, scope.testScheduler.currentTime)
                }.run()
            }
            scope.testScheduler.runCurrent()
        }
    }

    /** Moves virtual time to [t] and lets everything due at [t] run. */
    private fun TestScope.at(t: Long) {
        val d = t - testScheduler.currentTime
        if (d > 0) advanceTimeBy(d)
        runCurrent()
    }

    @Test
    fun `present for ten minutes never stops`() = runTest {
        val rig = Rig(this, present = true, casting = 1)
        at(600_000)
        assertTrue(rig.stops.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test
    fun `idle loss waits for the blip window`() = runTest {
        val rig = Rig(this, present = true, casting = 0)
        at(10_000)
        rig.present.value = false
        runCurrent()
        assertTrue(rig.stops.isEmpty())
        at(14_999)
        assertTrue(rig.stops.isEmpty())
        at(15_000)
        assertEquals(listOf(Stop(LossAction.STOP_IDLE, 5_000, 15_000)), rig.stops)
    }

    @Test
    fun `a cast waits exactly the blip window`() = runTest {
        val rig = Rig(this, present = false, casting = 1)
        at(4_999)
        assertTrue(rig.stops.isEmpty())
        at(5_000)
        assertEquals(listOf(Stop(LossAction.STOP_EXPIRED, 5_000, 5_000)), rig.stops)
    }

    @Test
    fun `it fires once after the blip window and ignores later changes`() = runTest {
        val rig = Rig(this, present = false, casting = 0)
        at(5_000)
        assertEquals(1, rig.stops.size)
        rig.present.value = true
        runCurrent()
        rig.present.value = false
        rig.casting.value = 1
        at(1_000_000)
        assertEquals(1, rig.stops.size)
    }

    @Test
    fun `a cast ending during the outage keeps the original blip deadline`() = runTest {
        val rig = Rig(this, present = false, casting = 1)
        at(3_000)
        rig.casting.value = 0
        runCurrent()
        assertTrue(rig.stops.isEmpty())
        at(4_999)
        assertTrue(rig.stops.isEmpty())
        at(5_000)
        assertEquals(listOf(Stop(LossAction.STOP_IDLE, 5_000, 5_000)), rig.stops)
    }

    @Test
    fun `a return before the blip window is not cut off`() = runTest {
        val rig = Rig(this, present = false, casting = 1)
        at(4_000)
        rig.present.value = true
        at(600_000)
        assertTrue(rig.stops.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a flap starts a new window`() = runTest {
        val rig = Rig(this, present = true, casting = 1)
        at(1_000)
        rig.present.value = false
        at(4_000)
        rig.present.value = true
        at(6_000)
        rig.present.value = false
        at(10_999)
        assertTrue(rig.stops.isEmpty())
        at(11_000)
        assertEquals(listOf(Stop(LossAction.STOP_EXPIRED, 5_000, 11_000)), rig.stops)
    }

    @Test
    fun `a change of the cast count during the outage does not move the expiry`() = runTest {
        val rig = Rig(this, present = false, casting = 1)
        at(2_000)
        rig.casting.value = 2
        at(4_999)
        assertTrue(rig.stops.isEmpty())
        at(5_000)
        assertEquals(listOf(Stop(LossAction.STOP_EXPIRED, 5_000, 5_000)), rig.stops)
    }
}
