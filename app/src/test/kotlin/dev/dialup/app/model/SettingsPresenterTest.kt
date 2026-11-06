// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import dev.dialup.app.AppSettingsData
import dev.dialup.app.ThemeMode
import dev.t2u.dial.DialAccessMode
import dev.t2u.media.CacheInfo
import dev.t2u.media.YtDlpUpdateOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A settings store in memory: [effective] follows [data] over the config.json values in [base]. */
class FakeSettingsPort(
    var base: EffectiveSettings = EffectiveSettings(
        logEnabled = true, gapless = true, cacheMb = 256, outputFormat = "auto", ytdlpChannel = "stable",
    ),
) : SettingsPort {
    private val flow = MutableStateFlow(AppSettingsData())
    override val data: StateFlow<AppSettingsData> get() = flow
    var updates = 0

    override fun update(change: (AppSettingsData) -> AppSettingsData) {
        updates++
        flow.value = change(flow.value)
    }

    override suspend fun effective(): EffectiveSettings {
        val d = flow.value
        return EffectiveSettings(
            logEnabled = d.logEnabled ?: base.logEnabled,
            gapless = d.gapless ?: base.gapless,
            cacheMb = d.cacheMb ?: base.cacheMb,
            outputFormat = d.outputFormat ?: base.outputFormat,
            ytdlpChannel = d.ytdlpChannel ?: base.ytdlpChannel,
        )
    }
}

/** A bridge you can script: calls are counted and the answers are plain fields. */
class FakeBridgePort(
    running: Boolean = true,
    /** What the running bridge was started with; null means no bridge. */
    var started: RestartFields? = RestartFields(gapless = true, outputFormat = "auto"),
) : BridgePort {
    private val flow = MutableStateFlow<Any?>(if (running) Any() else null)
    val castingFlow = MutableStateFlow(0)
    override val casting: StateFlow<Int> get() = castingFlow
    override val instance: StateFlow<Any?> get() = flow
    var cleared: Int? = 3
    var info: CacheInfo? = CacheInfo(files = 2, bytes = 5_000_000, limitBytes = 256L * 1024 * 1024)
    var outcome: YtDlpUpdateOutcome? = null
    var version: String? = "2026.08.19"
    var restartResult = true
    var clearCalls = 0
    var logClears = 0
    var logResult = true
    var updateCalls = 0
    var restartCalls = 0
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** When set, clearCache, clearLog, cacheInfo and updateNow throw it (a disk or bridge fault). */
    var failWith: Throwable? = null
    var clearGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    var infoCalls = 0

    fun setRunning(on: Boolean) {
        flow.value = if (on) (flow.value ?: Any()) else null
    }

    /** A restart: a fresh bridge object, the bridge is never off during the swap. */
    fun restartBridge() {
        flow.value = Any()
    }

    override suspend fun clearCache(): Int? {
        clearCalls++
        clearGate?.await()
        failWith?.let { throw it }
        return cleared.also { if (it != null) info = info?.copy(files = 0, bytes = 0) }
    }

    override suspend fun clearLog(): Boolean {
        logClears++
        failWith?.let { throw it }
        return logResult
    }

    override suspend fun cacheInfo(): CacheInfo? {
        infoCalls++
        failWith?.let { throw it }
        return info
    }

    override suspend fun updateNow(): YtDlpUpdateOutcome? {
        updateCalls++
        gate?.await()
        failWith?.let { throw it }
        return outcome
    }

    override suspend fun ytdlpVersion(): String? = version

    val restartSources = mutableListOf<String>()

    override fun runningWith(): RestartFields? = started

    override suspend fun restart(source: String): Boolean {
        restartCalls++
        restartSources.add(source)
        return restartResult
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPresenterTest {
    private fun TestScope.presenter(
        settings: FakeSettingsPort = FakeSettingsPort(),
        bridge: FakeBridgePort = FakeBridgePort(),
    ): SettingsPresenter = SettingsPresenter(backgroundScope, settings, bridge).also { runCurrent() }

    @Test
    fun `phone-only switch writes the dial access mode`() = runTest {
        val settings = FakeSettingsPort()
        val p = presenter(settings)
        assertTrue(p.state.value.phoneOnly)

        p.setPhoneOnly(false)
        runCurrent()
        assertEquals(DialAccessMode.LAN, settings.data.value.dialAccess)
        assertFalse(p.state.value.phoneOnly)

        p.setPhoneOnly(true)
        runCurrent()
        assertEquals(DialAccessMode.PHONE, settings.data.value.dialAccess)
        assertTrue(p.state.value.phoneOnly)
    }

    @Test
    fun `clear when a cast ends defaults off and saves`() = runTest {
        val settings = FakeSettingsPort()
        val p = presenter(settings)
        assertFalse(p.state.value.clearOnCastEnd)

        p.setClearOnCastEnd(true)
        runCurrent()
        assertTrue(settings.data.value.clearOnCastEnd)
        assertTrue(p.state.value.clearOnCastEnd)
        assertNull(p.state.value.message)
    }

    @Test
    fun `the cache steps are the stepped choice`() {
        assertEquals(listOf(0, 64, 128, 256, 512, 1024, 2048, 4096), CACHE_STEPS)
    }

    @Test
    fun `cache size 256 selects the step with no warning`() = runTest {
        val p = presenter()
        val ui = p.state.value
        assertEquals(256, ui.cacheMb)
        assertFalse(ui.cacheCustom)
        assertNull(ui.cacheWarning)
    }

    @Test
    fun `cache size 0 selects 0 and warns`() = runTest {
        val p = presenter(FakeSettingsPort(base().copy(cacheMb = 0)))
        assertEquals(0, p.state.value.cacheMb)
        assertFalse(p.state.value.cacheCustom)
        val w = p.state.value.cacheWarning
        assertTrue(w != null && "new tracks are temporary" in w, w)
    }

    @Test
    fun `a config json cache size outside the steps shows as custom`() = runTest {
        val p = presenter(FakeSettingsPort(base().copy(cacheMb = 300)))
        assertEquals(300, p.state.value.cacheMb)
        assertTrue(p.state.value.cacheCustom)
        assertNull(p.state.value.cacheWarning)
    }

    @Test
    fun `choosing a cache size stores it, clears the warning and asks for no restart`() = runTest {
        val settings = FakeSettingsPort(base().copy(cacheMb = 0))
        val p = presenter(settings)
        assertTrue(p.state.value.cacheWarning != null)

        assertFalse(p.setCacheMb(512))
        runCurrent()
        assertEquals(512, settings.data.value.cacheMb)
        assertEquals(512, p.state.value.cacheMb)
        assertNull(p.state.value.cacheWarning)
        assertFalse(p.restart.visible.value)
    }

    @Test
    fun `a cache size outside the steps is ignored`() = runTest {
        val settings = FakeSettingsPort()
        val p = presenter(settings)
        assertFalse(p.setCacheMb(300))
        assertNull(settings.data.value.cacheMb)
        assertEquals(0, settings.updates)
    }

    @Test
    fun `gapless and forced format store the value and need a restart`() = runTest {
        val settings = FakeSettingsPort()
        val p = presenter(settings)

        assertTrue(p.setGapless(false))
        runCurrent()
        assertEquals(false, settings.data.value.gapless)
        assertFalse(p.state.value.gapless)
        assertTrue(p.restart.visible.value)

        assertTrue(p.setOutputFormat("mp4"))
        runCurrent()
        assertEquals("mp4", settings.data.value.outputFormat)
        assertEquals("mp4", p.state.value.outputFormat)
    }

    @Test
    fun `the log switch stores the value and needs no restart`() = runTest {
        val settings = FakeSettingsPort()
        val p = presenter(settings)
        assertFalse(p.setLogEnabled(false))
        runCurrent()
        assertEquals(false, settings.data.value.logEnabled)
        assertFalse(p.state.value.logEnabled)
        assertFalse(p.restart.visible.value)
    }

    @Test
    fun `the theme defaults to system, saves the choice and needs no restart`() = runTest {
        val settings = FakeSettingsPort()
        val p = presenter(settings)
        assertEquals(ThemeMode.SYSTEM, p.state.value.theme)
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)) {
            assertFalse(p.setThemeMode(mode))
            runCurrent()
            assertEquals(mode, settings.data.value.theme)
            assertEquals(mode, p.state.value.theme)
            assertFalse(p.restart.visible.value)
        }
    }

    @Test
    fun `a saved theme shows as the current choice`() = runTest {
        val settings = FakeSettingsPort().also { it.update { d -> d.copy(theme = ThemeMode.LIGHT) } }
        val p = presenter(settings)
        assertEquals(ThemeMode.LIGHT, p.state.value.theme)
    }

    @Test
    fun `the theme key needs no restart`() {
        assertFalse(SettingKey.THEME.needsRestart)
    }

    @Test
    fun `a format outside the list is ignored`() = runTest {
        val settings = FakeSettingsPort()
        val p = presenter(settings)
        assertFalse(p.setOutputFormat("wav"))
        assertFalse(p.setOutputFormat("mp3"))
        assertNull(settings.data.value.outputFormat)
        assertEquals(0, settings.updates)
        assertFalse(p.restart.visible.value)
    }

    @Test
    fun `a change that needs a restart shows no card while the bridge is off`() = runTest {
        val p = presenter(bridge = FakeBridgePort(running = false))
        assertTrue(p.setGapless(false))
        runCurrent()
        assertFalse(p.restart.visible.value)
        assertFalse(p.state.value.gapless)
    }

    @Test
    fun `clear cache with the bridge running reports the count and refreshes the usage`() = runTest {
        val bridge = FakeBridgePort()
        val p = presenter(bridge = bridge)
        assertEquals(2, p.state.value.cacheUsage?.files)

        p.clearCache()
        runCurrent()
        assertEquals("Cleared 3 files", p.state.value.message)
        assertEquals(0, p.state.value.cacheUsage?.files)
        assertEquals(1, bridge.clearCalls)
    }

    @Test
    fun `clear cache says one file in the singular`() = runTest {
        val bridge = FakeBridgePort().also { it.cleared = 1 }
        val p = presenter(bridge = bridge)
        p.clearCache()
        runCurrent()
        assertEquals("Cleared 1 file", p.state.value.message)
    }

    @Test
    fun `clear cache works with the bridge off`() = runTest {
        val bridge = FakeBridgePort(running = false)
        val p = presenter(bridge = bridge)
        runCurrent()
        assertFalse(p.state.value.bridgeRunning)
        assertEquals(2, p.state.value.cacheUsage?.files)

        p.clearCache()
        runCurrent()
        assertEquals(1, bridge.clearCalls)
        assertEquals("Cleared 3 files", p.state.value.message)
    }

    @Test
    fun `the usage appears when the bridge comes up`() = runTest {
        val bridge = FakeBridgePort(running = false)
        val p = presenter(bridge = bridge)
        bridge.setRunning(true)
        runCurrent()
        assertTrue(p.state.value.bridgeRunning)
        assertEquals(2, p.state.value.cacheUsage?.files)
    }

    private fun base() = EffectiveSettings(
        logEnabled = true, gapless = true, cacheMb = 256, outputFormat = "auto", ytdlpChannel = "stable",
    )

    private fun outcome(ok: Boolean = true, before: String? = "2026.08.19", after: String? = "2026.10.20", status: String = "DONE", error: String? = null) =
        YtDlpUpdateOutcome(ok, "ui", "stable", before, after, status, 10, error)

    @Test
    fun `the update starts idle, runs, then shows the outcome`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val bridge = FakeBridgePort().also { it.gate = gate; it.outcome = outcome() }
        val p = presenter(bridge = bridge)
        assertEquals(UpdateUi.Idle, p.state.value.update)

        p.updateNow()
        runCurrent()
        assertEquals(UpdateUi.Running, p.state.value.update)

        gate.complete(Unit)
        runCurrent()
        assertEquals(UpdateUi.Done(outcome()), p.state.value.update)
    }

    @Test
    fun `a second update while one runs is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val bridge = FakeBridgePort().also { it.gate = gate; it.outcome = outcome() }
        val p = presenter(bridge = bridge)
        p.updateNow()
        runCurrent()
        p.updateNow()
        runCurrent()
        assertEquals(1, bridge.updateCalls)
    }

    @Test
    fun `the update runs with the bridge off`() = runTest {
        val bridge = FakeBridgePort(running = false).also { it.outcome = outcome() }
        val p = presenter(bridge = bridge)
        runCurrent()
        assertEquals(UpdateUi.Idle, p.state.value.update)
        p.updateNow()
        runCurrent()
        assertEquals(1, bridge.updateCalls)
        assertTrue(p.state.value.update is UpdateUi.Done)
    }

    @Test
    fun `update now is blocked while a renderer is being cast to`() = runTest {
        val bridge = FakeBridgePort().also { it.outcome = outcome() }
        val p = presenter(bridge = bridge)
        assertFalse(p.state.value.updateBlockedByCast)

        bridge.castingFlow.value = 1
        runCurrent()
        assertTrue(p.state.value.updateBlockedByCast)

        p.updateNow()
        runCurrent()
        assertEquals(0, bridge.updateCalls)
        val done = p.state.value.update as UpdateUi.Done
        assertEquals("CASTING", done.outcome.status)
        assertFalse(done.outcome.ok)
        assertEquals("A renderer is being cast to; update yt-dlp when the cast ends", updateMessage(done.outcome))

        bridge.castingFlow.value = 0
        runCurrent()
        assertFalse(p.state.value.updateBlockedByCast)
        p.updateNow()
        runCurrent()
        assertEquals(1, bridge.updateCalls)
    }

    @Test
    fun `a bridge port that does not report casting never blocks update now`() = runTest {
        val bridge = FakeBridgePort().also { it.outcome = outcome() }
        val p = presenter(bridge = bridge)
        assertFalse(p.state.value.updateBlockedByCast)
        p.updateNow()
        runCurrent()
        assertEquals(1, bridge.updateCalls)
    }

    @Test
    fun `the update message says what happened`() {
        assertEquals("Updated 2026.08.19 \u2192 2026.10.20", updateMessage(outcome()))
        assertEquals("Already up to date (2026.10.20)", updateMessage(outcome(before = "2026.10.20", after = "2026.10.20")))
        assertEquals(
            "An update or a lookup is running; try again in a minute",
            updateMessage(outcome(ok = false, status = "BUSY", before = null, after = null)),
        )
        assertEquals("Update failed: no network", updateMessage(outcome(ok = false, status = "FAILED", error = "no network")))
    }

    @Test
    fun `choosing the nightly channel stores it and needs no restart, master is ignored`() = runTest {
        val settings = FakeSettingsPort()
        val p = presenter(settings)
        assertEquals("stable", p.state.value.ytdlpChannel)

        assertFalse(p.setYtdlpChannel("nightly"))
        runCurrent()
        assertEquals("nightly", settings.data.value.ytdlpChannel)
        assertEquals("nightly", p.state.value.ytdlpChannel)
        assertFalse(p.restart.visible.value)

        assertFalse(p.setYtdlpChannel("master"))
        assertEquals("nightly", settings.data.value.ytdlpChannel)
        assertEquals(1, settings.updates)
    }

    @Test
    fun `the version is the bridge's while running and a dash while off`() = runTest {
        val bridge = FakeBridgePort()
        val p = presenter(bridge = bridge)
        assertEquals("2026.08.19", p.state.value.ytdlpVersion)

        bridge.setRunning(false)
        runCurrent()
        assertEquals("2026.08.19", p.state.value.ytdlpVersion)

        val off = presenter(bridge = FakeBridgePort(running = false))
        runCurrent()
        assertEquals("2026.08.19", off.state.value.ytdlpVersion)
    }

    @Test
    fun `the version is read again after an update`() = runTest {
        val bridge = FakeBridgePort().also { it.outcome = outcome() }
        val p = presenter(bridge = bridge)
        bridge.version = "2026.10.20"
        p.updateNow()
        runCurrent()
        assertEquals("2026.10.20", p.state.value.ytdlpVersion)
    }

    @Test
    fun `clear log says Log cleared with the bridge on or off`() = runTest {
        for (running in listOf(true, false)) {
            val bridge = FakeBridgePort(running = running)
            val p = presenter(bridge = bridge)
            p.clearLog()
            runCurrent()
            assertEquals(1, bridge.logClears)
            assertEquals(LOG_CLEARED, p.state.value.logMessage)
            assertEquals("Log cleared", p.state.value.logMessage)
            assertNull(p.state.value.message)
        }
    }

    @Test
    fun `a clear log that did not finish says so`() = runTest {
        val bridge = FakeBridgePort().also { it.logResult = false }
        val p = presenter(bridge = bridge)
        p.clearLog()
        runCurrent()
        assertEquals("Could not clear the log", p.state.value.logMessage)
    }

    @Test
    fun `a failing clear log names the exception class only`() = runTest {
        val bridge = FakeBridgePort()
        val p = presenter(bridge = bridge)
        bridge.failWith = java.io.IOException("disk full /data/secret/path")
        p.clearLog()
        runCurrent()
        val msg = p.state.value.logMessage
        assertEquals("Could not clear the log (IOException)", msg)
        assertFalse(msg!!.contains("/data"), "no path in the message")
    }

    @Test
    fun `refresh drops the log message`() = runTest {
        val p = presenter()
        p.clearLog()
        runCurrent()
        assertEquals(LOG_CLEARED, p.state.value.logMessage)
        p.refresh()
        runCurrent()
        assertNull(p.state.value.logMessage)
    }

    @Test
    fun `the LAN access switch is the inverse of phone only`() = runTest {
        val settings = FakeSettingsPort()
        val p = presenter(settings)
        assertFalse(p.state.value.lanAccess)
        assertTrue(p.state.value.phoneOnly)

        assertFalse(p.setLanAccess(true), "no restart")
        runCurrent()
        assertEquals(DialAccessMode.LAN, settings.data.value.dialAccess)
        assertTrue(p.state.value.lanAccess)
        assertFalse(p.state.value.phoneOnly)
        assertFalse(p.restart.visible.value)

        p.setLanAccess(false)
        runCurrent()
        assertEquals(DialAccessMode.PHONE, settings.data.value.dialAccess)
        assertFalse(p.state.value.lanAccess)
    }

    @Test
    fun `a failing clear cache shows a message`() = runTest {
        val bridge = FakeBridgePort()
        val p = presenter(bridge = bridge)
        bridge.failWith = java.io.IOException("disk full /data/secret/path")

        p.clearCache()
        runCurrent()

        val msg = p.state.value.message
        assertEquals("Could not clear the cache (IOException)", msg)
        assertFalse(msg!!.contains("/data"), "no path in the message")
    }

    @Test
    fun `a failing update ends as failed, not running`() = runTest {
        val bridge = FakeBridgePort()
        val p = presenter(bridge = bridge)
        bridge.failWith = java.io.IOException("boom")

        p.updateNow()
        runCurrent()

        val u = p.state.value.update
        assertTrue(u is UpdateUi.Done, "update must end Done, was $u")
        u as UpdateUi.Done
        assertFalse(u.outcome.ok)
        assertEquals("ERROR", u.outcome.status)
        assertEquals("IOException", u.outcome.error)
    }

    @Test
    fun `a failing usage refresh keeps the last values`() = runTest {
        val bridge = FakeBridgePort()
        val p = presenter(bridge = bridge)
        val usage = p.state.value.cacheUsage
        val version = p.state.value.ytdlpVersion
        assertEquals("2026.08.19", version)
        bridge.failWith = java.io.IOException("boom")

        p.refresh()
        runCurrent()

        assertEquals(usage, p.state.value.cacheUsage)
        assertEquals(version, p.state.value.ytdlpVersion)
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        val bridge = FakeBridgePort()
        bridge.clearGate = CompletableDeferred()
        val job = kotlinx.coroutines.Job()
        val scope = kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val p = SettingsPresenter(scope, FakeSettingsPort(), bridge)
        runCurrent()

        p.clearCache()
        runCurrent()
        assertEquals(1, bridge.clearCalls)
        job.cancel()
        runCurrent()

        assertNull(p.state.value.message, "a cancelled action writes no message")
    }

    // ------------------------------------------------------------------------------------------ quick 261101-dcg: restart on open

    @Test
    fun `opening the app with a pending restart and nothing cast restarts the bridge once`() = runTest {
        val settings = FakeSettingsPort()
        val bridge = FakeBridgePort()
        val p = presenter(settings, bridge)
        p.setGapless(false)
        runCurrent()

        p.onAppOpened { 0 }
        runCurrent()

        assertEquals(1, bridge.restartCalls)
        assertEquals(listOf("auto-restart"), bridge.restartSources)
    }

    @Test
    fun `opening the app with a pending restart while a renderer plays keeps the card up`() = runTest {
        val settings = FakeSettingsPort()
        val bridge = FakeBridgePort()
        val p = presenter(settings, bridge)
        p.setGapless(false)
        runCurrent()
        // the six-second card of the change itself has gone
        advanceTimeBy(7_000)
        runCurrent()
        assertFalse(p.restart.visible.value)

        p.onAppOpened { 1 }
        runCurrent()

        assertEquals(0, bridge.restartCalls)
        assertTrue(p.restart.visible.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(p.restart.visible.value)
        p.restart.onCastingChanged(0)
        assertFalse(p.restart.visible.value)
        runCurrent()
        assertEquals(0, bridge.restartCalls)
    }

    @Test
    fun `opening the app with nothing pending, no bridge or the switch off does nothing`() = runTest {
        // nothing pending: the running bridge already has what is saved
        val a = FakeBridgePort()
        presenter(FakeSettingsPort(), a).onAppOpened { 0 }
        runCurrent()
        assertEquals(0, a.restartCalls)

        // no bridge: runningWith is null although the port still reports running
        val noBridge = FakeBridgePort(started = null)
        val p2 = presenter(FakeSettingsPort(), noBridge)
        p2.setGapless(false)
        runCurrent()
        p2.onAppOpened { 0 }
        runCurrent()
        assertEquals(0, noBridge.restartCalls)

        // the switch off
        val off = FakeSettingsPort()
        off.update { it.copy(bridgeEnabled = false) }
        val b3 = FakeBridgePort()
        val p3 = presenter(off, b3)
        p3.setGapless(false)
        runCurrent()
        p3.onAppOpened { 0 }
        runCurrent()
        assertEquals(0, b3.restartCalls)

        advanceTimeBy(7_000)
        runCurrent()
        assertFalse(p2.restart.visible.value)
        assertFalse(p3.restart.visible.value)
    }

    @Test
    fun `a bridge started after the change needs no restart on open`() = runTest {
        // the change was saved, then the process was killed: the new process read settings.json when it started the bridge
        val settings = FakeSettingsPort()
        settings.update { it.copy(gapless = false) }
        val bridge = FakeBridgePort(started = RestartFields(gapless = false, outputFormat = "auto"))
        val p = presenter(settings, bridge)

        p.onAppOpened { 0 }
        runCurrent()

        assertEquals(0, bridge.restartCalls)
        assertFalse(p.restart.visible.value)
    }

    // ------------------------------------------------------------------------------------------ quick 261102-i9h: refresh after a restart

    @Test
    fun `a bridge restart that never shows as off still refreshes the usage and the version`() = runTest {
        val bridge = FakeBridgePort()
        val p = presenter(bridge = bridge)
        assertEquals(2, p.state.value.cacheUsage?.files)
        assertEquals("2026.08.19", p.state.value.ytdlpVersion)
        assertTrue(bridge.running)

        bridge.info = CacheInfo(files = 0, bytes = 0, limitBytes = 256L * 1024 * 1024)
        bridge.version = "2026.10.20"
        bridge.restartBridge()
        assertTrue(bridge.running)
        runCurrent()

        assertTrue(bridge.running)
        assertTrue(p.state.value.bridgeRunning)
        assertEquals(0, p.state.value.cacheUsage?.files)
        assertEquals("2026.10.20", p.state.value.ytdlpVersion)
    }

    @Test
    fun `a first read that answers null is retried until the bridge answers`() = runTest {
        val bridge = FakeBridgePort(running = false).also { it.info = null; it.version = null }
        val p = presenter(bridge = bridge)

        bridge.setRunning(true)
        runCurrent()
        assertTrue(p.state.value.bridgeRunning)
        assertNull(p.state.value.cacheUsage)
        assertEquals("-", p.state.value.ytdlpVersion)

        bridge.info = CacheInfo(files = 2, bytes = 5_000_000, limitBytes = 256L * 1024 * 1024)
        bridge.version = "2026.08.19"
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(2, p.state.value.cacheUsage?.files)
        assertEquals("2026.08.19", p.state.value.ytdlpVersion)
    }

    @Test
    fun `the retry stops after the attempt limit`() = runTest {
        val bridge = FakeBridgePort(running = false).also { it.info = null; it.version = null }
        val p = presenter(bridge = bridge)
        runCurrent()
        // the read for the bridge-off start was cancelled by the new instance below after its first attempt
        val first = bridge.infoCalls

        bridge.setRunning(true)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(first + USAGE_ATTEMPTS, bridge.infoCalls)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(first + USAGE_ATTEMPTS, bridge.infoCalls)
        assertNull(p.state.value.cacheUsage)
    }

    @Test
    fun `a bridge change cancels the pending retries and starts one fresh read`() = runTest {
        val bridge = FakeBridgePort(running = false).also { it.info = null; it.version = null }
        val p = presenter(bridge = bridge)
        runCurrent()
        val first = bridge.infoCalls

        bridge.setRunning(true)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(first + 2, bridge.infoCalls)

        bridge.setRunning(false)
        runCurrent()
        assertEquals(first + 3, bridge.infoCalls)
        assertNull(p.state.value.cacheUsage)
    }

    // ------------------------------------------------------------------------------------------ quick 261102-i9h: poll while visible

    @Test
    fun `the cache usage is polled while the tab is visible`() = runTest {
        val bridge = FakeBridgePort()
        val p = presenter(bridge = bridge)
        p.onVisible()
        bridge.info = CacheInfo(files = 5, bytes = 1, limitBytes = 256L * 1024 * 1024)
        advanceTimeBy(USAGE_POLL_MS + 1)
        runCurrent()
        assertEquals(5, p.state.value.cacheUsage?.files)
    }

    @Test
    fun `the poll stops when the tab is hidden`() = runTest {
        val bridge = FakeBridgePort()
        val p = presenter(bridge = bridge)
        p.onVisible()
        bridge.info = CacheInfo(files = 5, bytes = 1, limitBytes = 256L * 1024 * 1024)
        advanceTimeBy(USAGE_POLL_MS + 1)
        runCurrent()
        assertEquals(5, p.state.value.cacheUsage?.files)

        p.onHidden()
        bridge.info = CacheInfo(files = 9, bytes = 1, limitBytes = 256L * 1024 * 1024)
        advanceTimeBy(3 * USAGE_POLL_MS)
        runCurrent()
        assertEquals(5, p.state.value.cacheUsage?.files)
    }

    @Test
    fun `calling onVisible twice runs one poll loop only`() = runTest {
        val bridge = FakeBridgePort()
        val p = presenter(bridge = bridge)
        val before = bridge.infoCalls
        p.onVisible()
        p.onVisible()
        advanceTimeBy(USAGE_POLL_MS + 1)
        runCurrent()
        assertEquals(before + 1, bridge.infoCalls)
    }

    @Test
    fun `the poll reads the cache usage while the bridge is off`() = runTest {
        val bridge = FakeBridgePort(running = false)
        val p = presenter(bridge = bridge)
        runCurrent()
        val before = bridge.infoCalls
        p.onVisible()
        advanceTimeBy(USAGE_POLL_MS + 1)
        runCurrent()
        assertEquals(before + 1, bridge.infoCalls)
    }
}
