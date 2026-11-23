// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import dev.t2u.renderer.BridgeNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RebindApplierTest {
    private val newAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
    private val network = BridgeNetwork(newAddress, null, emptyList())

    /** A target that records every call in order and answers from the fields the test sets. */
    private class FakeTarget : RebindTarget {
        val calls = mutableListOf<String>()
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        var bridge = true
        var wifi: String? = "192.168.1.20"
        var startResult = true
        var validated = true
        var network: BridgeNetwork? = null
        var bridgeAfterWait = true
        var startGate: CompletableDeferred<Unit>? = null
        var startFinished = false
        var networkUp: Boolean? = null
        var rebindArgument: BridgeNetwork? = null

        /** Which read throws (`wifiIp`, `awaitValidated` or `currentNetwork`), and with what. */
        var throwFrom: String? = null
        var thrown: Throwable = IllegalStateException("boom")
        var startThrew = false
        var resets = 0

        private fun maybeThrow(where: String) {
            if (throwFrom == where) throw thrown
        }

        override val hasBridge: Boolean get() = bridge
        override var lastIp: String? = "192.168.1.10"
        override fun wifiIp(): String? {
            maybeThrow("wifiIp")
            return wifi
        }
        override fun markNetworkUp(up: Boolean) {
            calls += "networkUp=$up"
            networkUp = up
        }

        override suspend fun startGuarded(reason: String): Boolean {
            calls += "start($reason)"
            startGate?.await()
            if (startResult) lastIp = wifi
            startFinished = true
            return startResult
        }

        override suspend fun stopKeepingSpeakers() {
            calls += "stop"
        }

        override fun scheduleStartRetry() {
            calls += "retry"
        }

        override fun resetBindFailures() {
            calls += "resetBindFailures"
        }

        override val lastStartThrew: Boolean get() = startThrew

        override fun failedAttempt(retryReason: String) {
            calls += "failed($retryReason)"
        }

        override fun resetFailures() {
            resets++
        }

        override suspend fun awaitValidated(): Boolean {
            calls += "awaitValidated"
            maybeThrow("awaitValidated")
            return validated
        }

        override fun currentNetwork(): BridgeNetwork? {
            calls += "currentNetwork"
            maybeThrow("currentNetwork")
            return network
        }

        override fun inPlaceRebind(): (suspend (BridgeNetwork) -> Unit)? {
            calls += "inPlaceRebind"
            if (!bridgeAfterWait) return null
            return { n ->
                calls += "rebind"
                rebindArgument = n
            }
        }

        override fun event(kind: String, fields: Map<String, Any?>) {
            calls += "event"
            events += kind to fields
        }
    }

    private fun only(t: FakeTarget): Map<String, Any?> {
        assertEquals(1, t.events.size, "one event: ${t.events}")
        assertEquals("net", t.events[0].first)
        return t.events[0].second
    }

    @Test
    fun lostWithoutAddressKeepsTheBridgeFrozen() = runTest {
        val t = FakeTarget().apply { wifi = null }
        RebindApplier(t).apply("lost")
        assertEquals(false, t.networkUp)
        assertEquals(
            mapOf("rebind" to false, "mode" to "lost-kept", "reason" to "lost", "oldIp" to "192.168.1.10"),
            only(t),
        )
        assertEquals(listOf("networkUp=false", "event"), t.calls)
    }

    @Test
    fun startSuccessWritesOldAndNewIp() = runTest {
        val t = FakeTarget().apply { bridge = false }
        RebindApplier(t).apply("available")
        assertEquals(
            mapOf("rebind" to true, "mode" to "start", "reason" to "available", "oldIp" to "192.168.1.10", "newIp" to "192.168.1.20"),
            only(t),
        )
        assertEquals(listOf("start(available)", "event"), t.calls)
    }

    @Test
    fun startFailureWritesStartFailedAndSchedulesARetry() = runTest {
        val t = FakeTarget().apply { bridge = false; startResult = false }
        RebindApplier(t).apply("available")
        assertEquals(
            mapOf("rebind" to false, "mode" to "start-failed", "reason" to "available", "oldIp" to "192.168.1.10"),
            only(t),
        )
        assertEquals(listOf("start(available)", "event", "retry"), t.calls)
    }

    @Test
    fun restartStopsWithoutClearingThenStarts() = runTest {
        val t = FakeTarget()
        RebindApplier(t).apply("config")
        assertEquals(
            mapOf("rebind" to true, "mode" to "restart", "reason" to "config", "oldIp" to "192.168.1.10", "newIp" to "192.168.1.20"),
            only(t),
        )
        assertEquals(listOf("stop", "start(config)", "event"), t.calls)
    }

    @Test
    fun restartFailureWritesRestartFailedAndSchedulesARetry() = runTest {
        val t = FakeTarget().apply { startResult = false }
        RebindApplier(t).apply("config")
        assertEquals(
            mapOf("rebind" to false, "mode" to "restart-failed", "reason" to "config", "oldIp" to "192.168.1.10"),
            only(t),
        )
        assertEquals(listOf("stop", "start(config)", "event", "retry"), t.calls)
    }

    @Test
    fun retryWithoutBridgeAndWithoutWifiResetsFailuresAndRetriesLater() = runTest {
        val t = FakeTarget().apply { bridge = false; wifi = null }
        RebindApplier(t).apply("retry")
        assertEquals(listOf("resetBindFailures", "retry"), t.calls)
        assertTrue(t.events.isEmpty())
    }

    @Test
    fun retryWithABridgeDoesNothing() = runTest {
        val t = FakeTarget()
        RebindApplier(t).apply("retry")
        assertTrue(t.calls.isEmpty(), "calls: ${t.calls}")
        assertTrue(t.events.isEmpty())
    }

    @Test
    fun retryWithoutBridgeButWithWifiStartsIt() = runTest {
        val t = FakeTarget().apply { bridge = false }
        RebindApplier(t).apply("retry")
        assertEquals(listOf("start(retry)", "event"), t.calls)
        assertEquals("start", only(t)["mode"])
    }

    @Test
    fun inPlaceWaitsForValidationThenMovesTheBridge() = runTest {
        val t = FakeTarget().apply { network = this@RebindApplierTest.network }
        RebindApplier(t).apply("available")
        val event = only(t)
        assertEquals(true, event["rebind"])
        assertEquals("in-place", event["mode"])
        assertEquals("available", event["reason"])
        assertEquals("192.168.1.10", event["oldIp"])
        assertEquals("192.168.1.20", event["newIp"])
        assertEquals(true, event["validated"])
        assertNotNull(event["waitedMs"] as? Long, "waitedMs is a Long")
        assertEquals("192.168.1.20", t.lastIp)
        assertEquals(true, t.networkUp)
        assertEquals(network, t.rebindArgument)
        assertEquals(
            listOf("awaitValidated", "currentNetwork", "inPlaceRebind", "rebind", "networkUp=true", "event"),
            t.calls,
        )
    }

    @Test
    fun inPlaceReportsAValidationTimeout() = runTest {
        val t = FakeTarget().apply { network = this@RebindApplierTest.network; validated = false }
        RebindApplier(t).apply("linkPropertiesChanged")
        assertEquals(false, only(t)["validated"])
        assertEquals("in-place", only(t)["mode"])
    }

    @Test
    fun inPlaceKeepsTheBridgeFrozenWhenTheNetworkIsGoneAfterTheWait() = runTest {
        val t = FakeTarget().apply { network = null; validated = false }
        RebindApplier(t).apply("available")
        val event = only(t)
        assertEquals(false, event["rebind"])
        assertEquals("lost-kept", event["mode"])
        assertEquals("available", event["reason"])
        assertEquals("192.168.1.10", event["oldIp"])
        assertEquals(false, event["validated"])
        assertNotNull(event["waitedMs"] as? Long)
        assertEquals(false, t.networkUp)
        assertEquals("192.168.1.10", t.lastIp, "the last IP is not touched")
        assertFalse("rebind" in t.calls)
    }

    @Test
    fun inPlaceKeepsTheBridgeFrozenWhenTheBridgeIsGoneAfterTheWait() = runTest {
        val t = FakeTarget().apply { network = this@RebindApplierTest.network; bridgeAfterWait = false }
        RebindApplier(t).apply("available")
        assertEquals("lost-kept", only(t)["mode"])
        assertEquals(false, t.networkUp)
        assertFalse("rebind" in t.calls)
    }

    @Test
    fun aStartInFlightIsNotCutShortByCancellation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val t = FakeTarget().apply { bridge = false; startGate = gate }
        val job = launch { RebindApplier(t).apply("available") }
        runCurrent()
        assertEquals(listOf("start(available)"), t.calls)
        job.cancel()
        gate.complete(Unit)
        runCurrent()
        assertTrue(t.startFinished, "the guarded start ran to its end")
    }

    @Test
    fun aThrowingNetworkReadIsLoggedAndRetriedWithTheSameReason() = runTest {
        val t = FakeTarget().apply {
            throwFrom = "currentNetwork"
            thrown = java.net.SocketException("no such interface")
        }
        RebindApplier(t).apply("available")
        assertEquals(
            mapOf(
                "rebind" to false,
                "mode" to "failed",
                "reason" to "available",
                "oldIp" to "192.168.1.10",
                "error" to "java.net.SocketException",
            ),
            only(t),
        )
        assertEquals("failed(available)", t.calls.last())
        assertFalse("retry" in t.calls, "the retry is the target's, with the original reason: ${t.calls}")
    }

    @Test
    fun theEventNeverCarriesTheExceptionMessage() = runTest {
        val t = FakeTarget().apply { throwFrom = "currentNetwork"; thrown = IllegalStateException("/data/secret/path") }
        RebindApplier(t).apply("available")
        assertFalse(only(t).values.any { it.toString().contains("secret") }, "events: ${t.events}")
    }

    @Test
    fun aThrowingWifiReadIsRetriedWithTheSameReason() = runTest {
        val t = FakeTarget().apply { throwFrom = "wifiIp"; thrown = IllegalStateException("wifi") }
        RebindApplier(t).apply("linkPropertiesChanged")
        val event = only(t)
        assertEquals("failed", event["mode"])
        assertEquals(false, event["rebind"])
        assertEquals("java.lang.IllegalStateException", event["error"])
        assertEquals(listOf("event", "failed(linkPropertiesChanged)"), t.calls)
    }

    @Test
    fun aThrowingValidationWaitIsRetriedWithTheSameReason() = runTest {
        val t = FakeTarget().apply { throwFrom = "awaitValidated" }
        RebindApplier(t).apply("available")
        assertEquals("failed", only(t)["mode"])
        assertEquals("failed(available)", t.calls.last())
    }

    @Test
    fun aStartThatThrewCountsAsAFailedAttempt() = runTest {
        val t = FakeTarget().apply { bridge = false; startResult = false; startThrew = true }
        RebindApplier(t).apply("available")
        assertEquals("start-failed", only(t)["mode"])
        assertEquals(listOf("start(available)", "event", "failed(retry)"), t.calls)
        assertEquals(0, t.resets)
    }

    @Test
    fun aRestartThatThrewCountsAsAFailedAttempt() = runTest {
        val t = FakeTarget().apply { startResult = false; startThrew = true }
        RebindApplier(t).apply("config")
        assertEquals("restart-failed", only(t)["mode"])
        assertEquals(listOf("stop", "start(config)", "event", "failed(retry)"), t.calls)
    }

    @Test
    fun aStartWithoutWifiIsNotAFailure() = runTest {
        val t = FakeTarget().apply { bridge = false; startResult = false; startThrew = false }
        RebindApplier(t).apply("available")
        assertEquals(listOf("start(available)", "event", "retry"), t.calls)
        assertEquals(1, t.resets)
    }

    @Test
    fun aSuccessfulMoveEndsTheRunOfFailures() = runTest {
        val t = FakeTarget().apply { network = this@RebindApplierTest.network }
        RebindApplier(t).apply("available")
        assertEquals(1, t.resets)
    }

    @Test
    fun aSuccessfulStartEndsTheRunOfFailures() = runTest {
        val t = FakeTarget().apply { bridge = false }
        RebindApplier(t).apply("available")
        assertEquals(1, t.resets)
    }

    @Test
    fun noWifiEndsTheRunOfFailures() = runTest {
        val t = FakeTarget().apply { wifi = null }
        RebindApplier(t).apply("lost")
        assertEquals(1, t.resets)
    }

    @Test
    fun aCancellationIsRethrownAndNotCounted() = runTest {
        val t = FakeTarget().apply { throwFrom = "awaitValidated"; thrown = CancellationException("cancelled") }
        assertFailsWith<CancellationException> { RebindApplier(t).apply("available") }
        assertFalse(t.calls.any { it.startsWith("failed(") }, "calls: ${t.calls}")
        assertTrue(t.events.isEmpty(), "no event for a cancellation: ${t.events}")
    }
}
