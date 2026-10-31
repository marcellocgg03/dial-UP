// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RestartPromptTest {
    @Test
    fun `a setting that needs a restart shows the card and it goes away after six seconds`() = runTest {
        var runs = 0
        val prompt = RestartPrompt(backgroundScope) { runs++; true }
        prompt.onSettingChanged(SettingKey.GAPLESS, bridgeRunning = true)
        assertTrue(prompt.visible.value)

        advanceTimeBy(5_900)
        runCurrent()
        assertTrue(prompt.visible.value)
        advanceTimeBy(200)
        runCurrent()
        assertFalse(prompt.visible.value)
        assertEquals(0, runs, "the timeout never restarts anything")
    }

    @Test
    fun `live settings and a stopped bridge never show the card`() = runTest {
        val prompt = RestartPrompt(backgroundScope) { true }
        prompt.onSettingChanged(SettingKey.CACHE, bridgeRunning = true)
        assertFalse(prompt.visible.value)
        prompt.onSettingChanged(SettingKey.GAPLESS, bridgeRunning = false)
        assertFalse(prompt.visible.value)
    }

    @Test
    fun `a second change starts the six seconds again`() = runTest {
        val prompt = RestartPrompt(backgroundScope) { true }
        prompt.onSettingChanged(SettingKey.GAPLESS, true)
        advanceTimeBy(4_000)
        prompt.onSettingChanged(SettingKey.FORMAT, true)
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(prompt.visible.value)
        advanceTimeBy(2_100)
        runCurrent()
        assertFalse(prompt.visible.value)
    }

    @Test
    fun `dismiss hides the card and runs nothing`() = runTest {
        var runs = 0
        val prompt = RestartPrompt(backgroundScope) { runs++; true }
        prompt.onSettingChanged(SettingKey.FORMAT, true)
        prompt.dismiss()
        assertFalse(prompt.visible.value)
        runCurrent()
        assertEquals(0, runs)
    }

    @Test
    fun `restart with nothing casting hides the card and runs the action once at once`() = runTest {
        var runs = 0
        val prompt = RestartPrompt(backgroundScope) { runs++; true }
        prompt.onSettingChanged(SettingKey.GAPLESS, true)

        prompt.requestRestart(casting = 0)
        runCurrent()
        assertFalse(prompt.visible.value)
        assertFalse(prompt.confirmPending.value)
        assertEquals(1, runs)
    }

    @Test
    fun `restart while something plays asks first and runs nothing`() = runTest {
        var runs = 0
        val prompt = RestartPrompt(backgroundScope) { runs++; true }
        prompt.onSettingChanged(SettingKey.GAPLESS, true)

        prompt.requestRestart(casting = 2)
        runCurrent()
        assertTrue(prompt.confirmPending.value)
        assertFalse(prompt.visible.value)
        assertEquals(0, runs)
    }

    @Test
    fun `confirming runs the action once and a second confirm while it runs is ignored`() = runTest {
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val prompt = RestartPrompt(backgroundScope) { runs++; gate.await(); true }
        prompt.requestRestart(casting = 1)

        prompt.confirmRestart()
        prompt.confirmRestart()
        runCurrent()
        assertFalse(prompt.confirmPending.value)
        assertEquals(1, runs)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, runs)
    }

    @Test
    fun `cancelling clears the question and runs nothing`() = runTest {
        var runs = 0
        val prompt = RestartPrompt(backgroundScope) { runs++; true }
        prompt.requestRestart(casting = 3)
        prompt.cancelRestart()
        runCurrent()
        assertFalse(prompt.confirmPending.value)
        assertEquals(0, runs)
    }

    @Test
    fun `the card timing out leaves a pending question alone`() = runTest {
        var runs = 0
        val prompt = RestartPrompt(backgroundScope) { runs++; true }
        prompt.requestRestart(casting = 2)
        prompt.onSettingChanged(SettingKey.GAPLESS, true)

        advanceTimeBy(6_100)
        runCurrent()
        assertFalse(prompt.visible.value)
        assertTrue(prompt.confirmPending.value)
        assertEquals(0, runs)
    }

    @Test
    fun `a failing action does not leave the prompt stuck`() = runTest {
        var runs = 0
        val prompt = RestartPrompt(backgroundScope) { runs++; error("boom") }
        prompt.requestRestart(casting = 0)
        runCurrent()
        prompt.requestRestart(casting = 0)
        runCurrent()
        assertEquals(2, runs)
    }
}
