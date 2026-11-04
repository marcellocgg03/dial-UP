package dev.t2u.renderer

// WR-06 (release review, plan 08-05): a start that fails must leave the bridge not running without blocking a dispatcher
// thread, and a housekeeping run that failed must not count as done. Same loopback rig as the other whole-bridge tests.
// GPL-3.0-only.

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeStartTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() {
        h.close()
    }

    private fun running(bridge: Bridge) = bridge.state()["running"].toString()

    @Test
    fun aRejectedConfigLeavesTheBridgeNotRunning() = h.run {
        val bridge = h.newBridge(config = h.testConfig().copy(mediaPort = 70000))
        assertFailsWith<IllegalArgumentException> { bridge.start() }
        assertEquals("false", running(bridge), "a start that threw is not running")
        assertTrue(
            h.events.any { it.first == "bridge-error" && it.second["where"] == "start" },
            "the failure is logged with where = start: ${h.events.takeLast(10)}",
        )
    }

    @Test
    fun theSuspendingStartNeverBlocksASingleThreadedDispatcher() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = executor.asCoroutineDispatcher()
            runBlocking {
                withTimeout(30_000) {
                    val bad = h.newBridge(config = h.testConfig().copy(mediaPort = 70000))
                    withContext(dispatcher) {
                        assertFailsWith<IllegalArgumentException> { bad.startSuspending() }
                    }
                    assertEquals("false", running(bad))

                    val good = h.newBridge()
                    withContext(dispatcher) { good.startSuspending() }
                    assertEquals("true", running(good))
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aFailedHousekeepingRunIsNotCountedAsDone() = h.run {
        val now = AtomicLong(1_000_000)
        val bridge = h.newBridge(clock = { now.get() })
        bridge.start()

        h.accessModeFails = true
        val marker = h.events.size
        assertFalse(bridge.housekeeping("alarm"), "a run that threw is not a run")
        assertTrue(
            h.events.drop(marker).any { it.first == "bridge-error" && it.second["where"] == "housekeeping" },
            "the failure is logged: ${h.events.drop(marker)}",
        )

        h.accessModeFails = false
        assertTrue(bridge.housekeeping("alarm"), "the same instant is not 'recent' after a failed run")
    }

    @Test
    fun twoTriggersTogetherRunOnce() = h.run {
        val now = AtomicLong(1_000_000)
        val bridge = h.newBridge(clock = { now.get() })
        bridge.start()

        val marker = h.events.size
        val results = listOf("timer", "alarm").map { t -> CoroutineScope(h.scope.coroutineContext).async { bridge.housekeeping(t) } }.awaitAll()
        assertEquals(1, results.count { it }, "exactly one of two simultaneous triggers runs: $results")
        assertTrue(
            h.events.drop(marker).any { it.first == "housekeeping" && it.second["skipped"] == "recent" },
            "the other one is skipped as recent: ${h.events.drop(marker)}",
        )
    }
}
