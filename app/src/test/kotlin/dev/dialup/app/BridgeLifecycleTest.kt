// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeLifecycleTest {
    @Test
    fun aStartWaitsForAStopInFlight() = runTest {
        val core = LifecycleCore()
        val token = core.beginStop()
        assertTrue(core.stopping.value)
        var result: Boolean? = null
        launch { result = core.awaitIdle(5_000) }
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(null, result, "the waiter must stay suspended while the stop runs")
        token.complete()
        runCurrent()
        assertEquals(true, result)
        assertFalse(core.stopping.value)
    }

    @Test
    fun awaitIdleTimesOut() = runTest {
        val core = LifecycleCore()
        core.beginStop()
        var result: Boolean? = null
        launch { result = core.awaitIdle(200) }
        runCurrent()
        advanceTimeBy(199)
        assertEquals(null, result)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(false, result)
        assertTrue(core.stopping.value, "a timed-out wait does not complete the stop")
    }

    @Test
    fun completeIsIdempotentAndStopsNest() = runTest {
        val core = LifecycleCore()
        val first = core.beginStop()
        val second = core.beginStop()
        first.complete()
        first.complete()
        assertTrue(core.stopping.value, "the second stop is still running")
        second.complete()
        assertFalse(core.stopping.value)
        assertTrue(core.awaitIdle(10))
    }

    @Test
    fun aStartAfterAStopIsOrderedThroughTheMutex() = runTest {
        val core = LifecycleCore()
        val log = mutableListOf<String>()
        val token = core.beginStop()
        launch {
            core.mutex.withLock {
                log += "stop-begin@$currentTime"
                delay(500)
                log += "stop-end@$currentTime"
            }
            token.complete()
        }
        runCurrent()
        launch {
            core.awaitIdle(5_000)
            core.mutex.withLock { log += "start@$currentTime" }
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("stop-begin@0", "stop-end@500", "start@500"), log)
    }
}
