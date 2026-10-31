// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app

import android.content.Context
import dev.t2u.app.model.BridgePort
import dev.t2u.app.model.EffectiveSettings
import dev.t2u.app.model.SettingsPort
import dev.t2u.app.model.SettingsPresenter
import dev.t2u.media.CacheInfo
import dev.t2u.media.YtDlpUpdateOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * The Android side of the Settings tab (plan 07-10): [SettingsPort] over [AppSettings] and config.json, and [BridgePort] over the
 * running [CastFeature] and [BridgeControl]. The presenter stays free of Android types.
 */
class SettingsModel(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val model = AppModel.of(app)
    private val settings = AppSettings.of(app)

    private val settingsPort = object : SettingsPort {
        override val data: StateFlow<AppSettingsData> get() = settings.data

        override fun update(change: (AppSettingsData) -> AppSettingsData) = settings.update(change)

        override suspend fun effective(): EffectiveSettings = withContext(Dispatchers.IO) {
            // The file may not be rewritten yet when this runs right after update(): lay the current data on top again.
            val c = applySettings(loadAppConfig(app.filesDir).config, settings.data.value)
            EffectiveSettings(
                logEnabled = c.logLevel != "off",
                gapless = c.gapless,
                cacheMb = c.cacheMb,
                outputFormat = c.outputFormat,
                ytdlpChannel = c.ytdlpChannel,
            )
        }
    }

    private val bridgePort = object : BridgePort {
        // Up means the service runs and has built its cast feature; the cast calls below answer null before that.
        override val running: StateFlow<Boolean> =
            model.cast.map { it != null }.stateIn(scope, SharingStarted.Eagerly, model.cast.value != null)

        override suspend fun clearCache(): Int? = withContext(Dispatchers.IO) { model.cast.value?.clearCache() }

        override suspend fun cacheInfo(): CacheInfo? = withContext(Dispatchers.IO) { model.cast.value?.cacheInfo() }

        override suspend fun updateNow(): YtDlpUpdateOutcome? = model.cast.value?.updateNow()

        override suspend fun ytdlpVersion(): String? = model.cast.value?.ytdlpVersion()

        override suspend fun restart(): Boolean = BridgeControl.restart(app)
    }

    fun presenter(): SettingsPresenter = SettingsPresenter(scope, settingsPort, bridgePort)
}
