// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BridgeToggleTest {
    @Test
    fun `turning on while off starts`() {
        assertEquals(ToggleAction.START, toggleAction(requestedOn = true, enabled = false, casting = 0))
    }

    @Test
    fun `turning off while nothing plays stops at once`() {
        assertEquals(ToggleAction.STOP, toggleAction(requestedOn = false, enabled = true, casting = 0))
    }

    @Test
    fun `turning off while a speaker plays asks first`() {
        assertEquals(ToggleAction.CONFIRM_STOP, toggleAction(requestedOn = false, enabled = true, casting = 2))
        assertEquals(ToggleAction.CONFIRM_STOP, toggleAction(requestedOn = false, enabled = true, casting = 1))
    }

    @Test
    fun `a request that changes nothing does nothing`() {
        for (casting in listOf(0, 1, 3)) {
            assertEquals(ToggleAction.NOTHING, toggleAction(requestedOn = true, enabled = true, casting = casting))
            assertEquals(ToggleAction.NOTHING, toggleAction(requestedOn = false, enabled = false, casting = casting))
        }
    }

    @Test
    fun `the switch is unusable without the exemption and keeps showing the persisted state`() {
        assertEquals(ToggleUi(checked = true, usable = false), toggleUi(enabled = true, exempt = false))
        assertEquals(ToggleUi(checked = false, usable = false), toggleUi(enabled = false, exempt = false))
    }

    @Test
    fun `the switch is usable with the exemption and shows the persisted state`() {
        assertEquals(ToggleUi(checked = true, usable = true), toggleUi(enabled = true, exempt = true))
        assertEquals(ToggleUi(checked = false, usable = true), toggleUi(enabled = false, exempt = true))
    }

    @Test
    fun `the confirm text names the speakers and says they keep their track`() {
        val text = confirmText(2, clearOnCastEnd = false)
        assertTrue("2 renderers" in text, text)
        assertTrue("keep their track loaded" in text, text)
    }

    @Test
    fun `the confirm text says the speakers are cleared when clear on cast end is on`() {
        val text = confirmText(2, clearOnCastEnd = true)
        assertTrue("2 renderers" in text, text)
        assertTrue("cleared" in text, text)
        assertFalse("keep their track loaded" in text, text)
    }

    @Test
    fun `one speaker uses the singular`() {
        val text = confirmText(1, clearOnCastEnd = false)
        assertTrue("1 renderer " in text, text)
        assertFalse("1 renderers" in text, text)
        assertTrue("keeps its track loaded" in text, text)
        assertTrue("is cleared" in confirmText(1, clearOnCastEnd = true))
    }

    @Test
    fun `the service starts only when enabled, exempt and not yet running`() {
        assertTrue(shouldStartService(enabled = true, exempt = true, running = false))
        assertFalse(shouldStartService(enabled = false, exempt = true, running = false))
        assertFalse(shouldStartService(enabled = true, exempt = false, running = false))
        assertFalse(shouldStartService(enabled = true, exempt = true, running = true))
        assertFalse(shouldStartService(enabled = false, exempt = false, running = false))
    }

    @Test
    fun `a start decision starts only with the switch, the exemption, no service and a bindable network`() {
        assertEquals(StartDecision.START, startDecision(enabled = true, exempt = true, running = false, bindable = true))
        assertEquals(StartDecision.WAIT_FOR_WIFI, startDecision(enabled = true, exempt = true, running = false, bindable = false))
    }

    @Test
    fun `a start decision is none when the switch is off, the exemption is missing or a service runs`() {
        for (bindable in listOf(true, false)) {
            assertEquals(StartDecision.NONE, startDecision(enabled = false, exempt = true, running = false, bindable = bindable))
            assertEquals(StartDecision.NONE, startDecision(enabled = true, exempt = false, running = false, bindable = bindable))
            assertEquals(StartDecision.NONE, startDecision(enabled = true, exempt = true, running = true, bindable = bindable))
            assertEquals(StartDecision.NONE, startDecision(enabled = false, exempt = false, running = true, bindable = bindable))
        }
    }

    @Test
    fun startWantedIsTheSwitchWithoutMaintenance() {
        assertTrue(startWanted(bridgeEnabled = true, maintenance = false))
        assertFalse(startWanted(bridgeEnabled = true, maintenance = true))
        assertFalse(startWanted(bridgeEnabled = false, maintenance = false))
        assertFalse(startWanted(bridgeEnabled = false, maintenance = true))
    }

    @Test
    fun `a restart with nothing playing runs at once`() {
        assertEquals(RestartStep.RESTART_NOW, restartStep(0))
    }

    @Test
    fun `a restart while anything plays asks first`() {
        assertEquals(RestartStep.CONFIRM_FIRST, restartStep(1))
        assertEquals(RestartStep.CONFIRM_FIRST, restartStep(3))
    }

    @Test
    fun `the restart stops, waits for the stop, then resumes`() = runTest {
        val calls = mutableListOf<String>()
        val ok = restartSequence(
            stop = { calls += "stop" },
            awaitStopped = { calls += "await"; true },
            resume = { calls += "resume" },
        )
        assertEquals(listOf("stop", "await", "resume"), calls)
        assertTrue(ok)
    }

    @Test
    fun `a timeout while waiting for the stop still resumes and reports false`() = runTest {
        val calls = mutableListOf<String>()
        val ok = restartSequence(
            stop = { calls += "stop" },
            awaitStopped = { calls += "await"; false },
            resume = { calls += "resume" },
        )
        assertEquals(listOf("stop", "await", "resume"), calls)
        assertFalse(ok)
    }

    @Test
    fun `a cancelled restart still resumes`() = runTest {
        val calls = mutableListOf<String>()
        val job = launch {
            restartSequence(
                stop = { calls += "stop" },
                awaitStopped = { awaitCancellation() },
                resume = { calls += "resume" },
            )
        }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertEquals(listOf("stop", "resume"), calls)
        assertTrue(job.isCancelled, "the cancellation is rethrown after the resume")
    }

    @Test
    fun `a restart started on another scope outlives its caller`() = runTest {
        val calls = mutableListOf<String>()
        val flight = SingleFlight<Boolean>()
        val deferred = flight.launch(backgroundScope) {
            restartSequence(
                stop = { calls += "stop" },
                awaitStopped = { delay(5_000); true },
                resume = { calls += "resume" },
            )
        }
        val caller = launch { deferred.await() }
        runCurrent()
        caller.cancel()
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(deferred.isCompleted)
        assertEquals(listOf("stop", "resume"), calls)
    }

    /** The shape BridgeControl gives StoppedWork: stop, wait, run the body only when the bridge stopped, resume. */
    private class FakeBridge(private val stops: Boolean = true) {
        val calls = mutableListOf<String>()

        suspend fun around(body: suspend () -> Unit): Boolean = restartSequence(
            stop = { calls += "stop" },
            awaitStopped = { delay(100); if (stops) body(); stops },
            resume = { calls += "resume" },
        )
    }

    @Test
    fun aSecondCallerJoinsTheFirstAndGetsItsResult() = runTest {
        val fake = FakeBridge()
        val work = StoppedWork(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var ranB = false
        val a = async { work.run(fake::around) { gate.await(); "A" } }
        runCurrent()
        assertTrue(work.active)
        val b = async { work.run(fake::around) { ranB = true; "B" } }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("A", a.await())
        assertEquals("A", b.await())
        assertFalse(ranB, "the joiner's own block never runs")
        assertEquals(listOf("stop", "resume"), fake.calls, "the bridge is stopped once")
        assertFalse(work.active)
    }

    @Test
    fun whenTheBridgeDidNotStopBothCallersGetTheSameFailure() = runTest {
        val fake = FakeBridge(stops = false)
        val work = StoppedWork(backgroundScope)
        var ran = 0
        val a = async { runCatching { work.run(fake::around) { ran++; "A" } } }
        runCurrent()
        val b = async { runCatching { work.run(fake::around) { ran++; "B" } } }
        advanceUntilIdle()
        for (r in listOf(a.await(), b.await())) {
            val e = r.exceptionOrNull()
            assertTrue(e is IllegalStateException, "got $e")
            assertEquals("the bridge did not stop", e.message)
        }
        assertEquals(0, ran)
        assertEquals(listOf("stop", "resume"), fake.calls)
    }

    @Test
    fun aBlockThatThrowsReachesEveryCallerAndTheBridgeStillResumes() = runTest {
        val fake = FakeBridge()
        val work = StoppedWork(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val a = async { runCatching { work.run<String>(fake::around) { gate.await(); throw IllegalArgumentException("boom") } } }
        runCurrent()
        val b = async { runCatching { work.run(fake::around) { "B" } } }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        for (r in listOf(a.await(), b.await())) {
            val e = r.exceptionOrNull()
            assertTrue(e is IllegalArgumentException, "got $e")
            assertEquals("boom", e.message)
        }
        assertEquals(listOf("stop", "resume"), fake.calls)
    }

    @Test
    fun aCallAfterTheFlightEndedRunsItsOwnBlock() = runTest {
        val fake = FakeBridge()
        val work = StoppedWork(backgroundScope)
        assertEquals("A", work.run(fake::around) { "A" })
        assertFalse(work.active)
        assertEquals("B", work.run(fake::around) { "B" })
        assertEquals(listOf("stop", "resume", "stop", "resume"), fake.calls)
    }

    @Test
    fun `a second restart while one runs joins it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val flight = SingleFlight<Boolean>()
        val first = flight.launch(backgroundScope) { gate.await(); true }
        val second = flight.launch(backgroundScope) { false }
        runCurrent()
        assertTrue(first === second)
        gate.complete(Unit)
        runCurrent()
        assertTrue(first.await())
        val third = flight.launch(backgroundScope) { false }
        assertFalse(third === first)
    }

    @Test
    fun `start after the old service stops`() = runTest {
        var starts = 0
        val started = startWhenStopped(
            awaitGone = { delay(300); true },
            stillWanted = { true },
            start = { starts++ },
        )
        assertTrue(started)
        assertEquals(1, starts)
    }

    @Test
    fun `switched off again meanwhile gives no start`() = runTest {
        var starts = 0
        val started = startWhenStopped(awaitGone = { true }, stillWanted = { false }, start = { starts++ })
        assertFalse(started)
        assertEquals(0, starts)
    }

    @Test
    fun `the old service never went away gives no start`() = runTest {
        var starts = 0
        val started = startWhenStopped(awaitGone = { false }, stillWanted = { true }, start = { starts++ })
        assertFalse(started)
        assertEquals(0, starts)
    }

    @Test
    fun `awaitNull is true once the flow holds null, also when it already does`() = runTest {
        val gone = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        assertTrue(awaitNull(gone, 1_000))

        val running = kotlinx.coroutines.flow.MutableStateFlow<String?>("service")
        val result = CompletableDeferred<Boolean>()
        launch { result.complete(awaitNull(running, 1_000)) }
        runCurrent()
        assertFalse(result.isCompleted, "still waiting while the service runs")
        running.value = null
        runCurrent()
        assertTrue(result.await())
    }

    @Test
    fun `awaitNull is false when the flow never holds null in time`() = runTest {
        val running = kotlinx.coroutines.flow.MutableStateFlow<String?>("service")
        val result = CompletableDeferred<Boolean>()
        launch { result.complete(awaitNull(running, 1_000)) }
        advanceTimeBy(1_001)
        runCurrent()
        assertFalse(result.await())
    }
}
