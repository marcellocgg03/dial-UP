// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import kotlinx.coroutines.CompletableDeferred
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
    fun `a restart with nothing playing runs at once`() {
        assertEquals(RestartStep.RESTART_NOW, restartStep(0))
    }

    @Test
    fun `a restart while anything plays asks first`() {
        assertEquals(RestartStep.CONFIRM_FIRST, restartStep(1))
        assertEquals(RestartStep.CONFIRM_FIRST, restartStep(3))
    }

    @Test
    fun `the restart switches off, waits for the stop, then switches on`() = runTest {
        val calls = mutableListOf<String>()
        val ok = restartSequence(
            setEnabled = { calls += "set:$it" },
            awaitStopped = { calls += "await"; true },
        )
        assertEquals(listOf("set:false", "await", "set:true"), calls)
        assertTrue(ok)
    }

    @Test
    fun `a timeout while waiting for the stop still switches on and reports false`() = runTest {
        val calls = mutableListOf<String>()
        val ok = restartSequence(
            setEnabled = { calls += "set:$it" },
            awaitStopped = { calls += "await"; false },
        )
        assertEquals(listOf("set:false", "await", "set:true"), calls)
        assertFalse(ok)
    }

    @Test
    fun `a cancelled restart still switches on`() = runTest {
        val calls = mutableListOf<Boolean>()
        val job = launch {
            restartSequence(
                setEnabled = { calls += it },
                awaitStopped = { awaitCancellation() },
            )
        }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertEquals(listOf(false, true), calls)
    }

    @Test
    fun `a restart started on another scope outlives its caller`() = runTest {
        val calls = mutableListOf<Boolean>()
        val flight = SingleFlight<Boolean>()
        val deferred = flight.launch(backgroundScope) {
            restartSequence(
                setEnabled = { calls += it },
                awaitStopped = { delay(5_000); true },
            )
        }
        val caller = launch { deferred.await() }
        runCurrent()
        caller.cancel()
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(deferred.isCompleted)
        assertEquals(listOf(false, true), calls)
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
