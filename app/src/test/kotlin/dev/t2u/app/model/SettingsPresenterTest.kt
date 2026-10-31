// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.model

import dev.t2u.app.AppSettingsData
import dev.t2u.dial.DialAccessMode
import dev.t2u.media.CacheInfo
import dev.t2u.media.YtDlpUpdateOutcome
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

    fun setRunning(on: Boolean) {
        flow.value = on
    }

    override suspend fun clearCache(): Int? {
        clearCalls++
        return cleared.also { if (it != null) info = info?.copy(files = 0, bytes = 0) }
    }

    override suspend fun cacheInfo(): CacheInfo? = info

    override suspend fun updateNow(): YtDlpUpdateOutcome? {
        updateCalls++
        gate?.await()
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
}
