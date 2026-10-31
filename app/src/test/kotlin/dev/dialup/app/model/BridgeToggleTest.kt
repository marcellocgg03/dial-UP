// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `the confirm text names the speakers and says they keep their track`() {
        val text = confirmText(2, clearOnCastEnd = false)
        assertTrue("2 speakers" in text, text)
        assertTrue("keep their track loaded" in text, text)
    }

    @Test
    fun `the confirm text says the speakers are cleared when clear on cast end is on`() {
        val text = confirmText(2, clearOnCastEnd = true)
        assertTrue("2 speakers" in text, text)
        assertTrue("cleared" in text, text)
        assertFalse("keep their track loaded" in text, text)
    }

    @Test
    fun `one speaker uses the singular`() {
        val text = confirmText(1, clearOnCastEnd = false)
        assertTrue("1 speaker " in text, text)
        assertFalse("1 speakers" in text, text)
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
}
