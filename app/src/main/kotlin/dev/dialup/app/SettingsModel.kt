// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import android.content.Context
import dev.dialup.app.model.BridgePort
import dev.dialup.app.model.EffectiveSettings
import dev.dialup.app.model.RestartFields
import dev.dialup.app.model.SettingsPort
import dev.dialup.app.model.SettingsPresenter
import dev.t2u.media.CacheInfo
import dev.t2u.media.FileCache
import dev.t2u.media.YtDlpUpdater
import dev.t2u.media.YtDlpUpdateOutcome
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
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

    private fun evidence(): EvidenceLog? = ServiceHub.service?.evidence

    /**
     * The cache directory the bridge's file builder uses (`media-buffer/files`), opened without a bridge. Nothing is pinned
     * here, which is right: with no bridge no stream reads those files. The limit is only reported, never enforced.
     */
    private fun offlineCache(): FileCache {
        val dir = File(mediaBufferDir(app), "files").also { it.mkdirs() }
        val limit = { (settings.data.value.cacheMb ?: loadAppConfig(app.filesDir).config.cacheMb).toLong() * 1_048_576L }
        return FileCache(dir, limit)
    }

    /** One updater for the times there is no bridge: no schedule, nothing to wait for. It also keeps two taps from overlapping. */
    private val offlineUpdater: YtDlpUpdater by lazy {
        val log = evidence() ?: EvidenceLog.shared(eventLogDir(app.filesDir))
        YtDlpUpdater(
            gateway = AndroidYtDlpGateway(app, log),
            channel = { settings.data.value.ytdlpChannel ?: loadAppConfig(app.filesDir).config.ytdlpChannel },
            onEvent = { log.event("ytdlp-update", it) },
            record = YtDlpRecords.of(app),
        )
    }

    private val bridgePort = object : BridgePort {
        // The identity of the cast feature: a restart always shows as a new value, also when the bridge is up before and after.
        // Up means the service runs and has built its cast feature; the cast calls below answer null before that.
        override val instance: StateFlow<Any?> = model.cast

        override suspend fun clearCache(): Int? = withContext(Dispatchers.IO) {
            model.cast.value?.clearCache() ?: offlineCache().clear().also { evidence()?.event("cache-clear", mapOf("cleared" to it, "source" to "ui-offline")) }
        }

        // Not through the service intent: with the bridge off that would run the start gate and log a denied start.
        override suspend fun clearLog(): Boolean = withContext(Dispatchers.IO) {
            val evidence = ServiceHub.service?.evidence
            if (evidence != null) {
                val done = evidence.clear()
                if (done) evidence.event("log-cleared", mapOf("source" to "settings"))
                done
            } else {
                EvidenceLog.shared(eventLogDir(app.filesDir)).clear()
            }
        }

        override suspend fun cacheInfo(): CacheInfo? = withContext(Dispatchers.IO) { model.cast.value?.cacheInfo() ?: offlineCache().info() }

        override suspend fun updateNow(): YtDlpUpdateOutcome? = model.cast.value?.updateNow() ?: offlineUpdater.updateNow()

        override suspend fun ytdlpVersion(): String? = model.cast.value?.ytdlpVersion() ?: offlineUpdater.version()

        override fun runningWith(): RestartFields? = model.cast.value?.runningWith()

        override suspend fun restart(source: String): Boolean = BridgeControl.restart(app, source)
    }

    fun presenter(): SettingsPresenter = SettingsPresenter(scope, settingsPort, bridgePort)
}
