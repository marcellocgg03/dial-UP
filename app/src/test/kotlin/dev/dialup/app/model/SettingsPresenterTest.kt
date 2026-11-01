// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import dev.dialup.app.AppSettingsData
import dev.t2u.dial.DialAccessMode
import dev.t2u.media.CacheInfo
import dev.t2u.media.YtDlpUpdateOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
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
class FakeBridgePort(running: Boolean = true) : BridgePort {
    private val flow = MutableStateFlow(running)
    override val running: StateFlow<Boolean> get() = flow
    var cleared: Int? = 3
    var info: CacheInfo? = CacheInfo(files = 2, bytes = 5_000_000, limitBytes = 256L * 1024 * 1024)
    var outcome: YtDlpUpdateOutcome? = null
    var version: String? = "2026.08.19"
    var restartResult = true
    var clearCalls = 0
    var updateCalls = 0
    var restartCalls = 0
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** When set, clearCache, cacheInfo and updateNow throw it (a disk or bridge fault). */
    var failWith: Throwable? = null
    var clearGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun setRunning(on: Boolean) {
        flow.value = on
    }

    override suspend fun clearCache(): Int? {
        clearCalls++
        clearGate?.await()
        failWith?.let { throw it }
        return cleared.also { if (it != null) info = info?.copy(files = 0, bytes = 0) }
    }

    override suspend fun cacheInfo(): CacheInfo? {
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

    override suspend fun restart(): Boolean {
        restartCalls++
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
        assertTrue(w != null && "only the file just built is kept" in w, w)
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

        assertTrue(p.setOutputFormat("mp3"))
        runCurrent()
        assertEquals("mp3", settings.data.value.outputFormat)
        assertEquals("mp3", p.state.value.outputFormat)
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
    fun `a format outside the list is ignored`() = runTest {
        val settings = FakeSettingsPort()
        val p = presenter(settings)
        assertFalse(p.setOutputFormat("wav"))
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
    fun `clear cache with the bridge off is disabled and does not call the bridge`() = runTest {
        val bridge = FakeBridgePort(running = false)
        val p = presenter(bridge = bridge)
        assertFalse(p.state.value.bridgeRunning)
        assertNull(p.state.value.cacheUsage)

        p.clearCache()
        runCurrent()
        assertEquals(0, bridge.clearCalls)
        assertNull(p.state.value.message)
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
    fun `with the bridge off the update is unavailable and the bridge is not called`() = runTest {
        val bridge = FakeBridgePort(running = false)
        val p = presenter(bridge = bridge)
        assertEquals(UpdateUi.Unavailable, p.state.value.update)
        p.updateNow()
        runCurrent()
        assertEquals(0, bridge.updateCalls)
        assertEquals(UpdateUi.Unavailable, p.state.value.update)
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
        assertEquals("-", p.state.value.ytdlpVersion)

        val off = presenter(bridge = FakeBridgePort(running = false))
        assertEquals("-", off.state.value.ytdlpVersion)
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
    fun `the bundled components text names what an app update brings`() {
        assertTrue("Python 3.12.11" in BUNDLED_COMPONENTS && "ffmpeg 7.1.1" in BUNDLED_COMPONENTS && "QuickJS" in BUNDLED_COMPONENTS)
        assertTrue("app update" in BUNDLED_COMPONENTS)
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
}
