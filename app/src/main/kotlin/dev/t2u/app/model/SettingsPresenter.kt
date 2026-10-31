// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.model

import dev.t2u.app.AppSettingsData
import dev.t2u.dial.DialAccessMode
import dev.t2u.media.CacheInfo
import dev.t2u.media.YtDlpUpdateOutcome
import dev.t2u.renderer.OUTPUT_FORMATS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** The saved app settings the Settings tab reads and writes (settings.json behind it). */
interface SettingsPort {
    val data: StateFlow<AppSettingsData>
    fun update(change: (AppSettingsData) -> AppSettingsData)

    /** What is in force now: the app's setting when set, else config.json, else the default. */
    suspend fun effective(): EffectiveSettings
}

/** The settings whose value can come from config.json or the built-in default as well as from the app. */
data class EffectiveSettings(
    val logEnabled: Boolean,
    val gapless: Boolean,
    val cacheMb: Int,
    val outputFormat: String,
    val ytdlpChannel: String,
)

/** What the Settings tab asks of the running bridge. Every call is safe with the bridge off and answers null or false then. */
interface BridgePort {
    val running: StateFlow<Boolean>
    suspend fun clearCache(): Int?
    suspend fun cacheInfo(): CacheInfo?
    suspend fun updateNow(): YtDlpUpdateOutcome?
    suspend fun ytdlpVersion(): String?
    suspend fun restart(): Boolean
}

/** The settings the tab changes, and whether the running bridge needs a restart to pick the change up (D-09, RESEARCH A2). */
enum class SettingKey(val needsRestart: Boolean) {
    PHONE_ONLY(false),
    CLEAR_ON_CAST_END(false),
    LOG(false),
    GAPLESS(true),
    CACHE(false),
    FORMAT(true),
    CHANNEL(false),
}

/** The cache sizes offered, in MiB (0 keeps only the file just built). */
val CACHE_STEPS: List<Int> = listOf(0, 64, 128, 256, 512, 1024, 2048, 4096)

/** Shown under the cache size when it is 0 (the pre-work todo, resolved as keep 0 with a warning). */
const val CACHE_ZERO_WARNING =
    "With 0 MB only the file just built is kept: a prepared next track in MP4 or FLAC may be rebuilt and gapless changes can suffer"

/** What the Settings tab shows. */
data class SettingsUi(
    val phoneOnly: Boolean = true,
    val clearOnCastEnd: Boolean = false,
    val logEnabled: Boolean = true,
    val gapless: Boolean = true,
    /** The effective cache size in MiB; one of [CACHE_STEPS], or a custom value when [cacheCustom]. */
    val cacheMb: Int = 256,
    val cacheCustom: Boolean = false,
    val cacheWarning: String? = null,
    /** Files, bytes and cap of the running bridge's cache; null while the bridge is off. */
    val cacheUsage: CacheInfo? = null,
    val outputFormat: String = "auto",
    val bridgeRunning: Boolean = false,
    /** A one-line result of the last action (cleared cache and so on); null when there is none. */
    val message: String? = null,
)

/** "Cleared 3 files" / "Cleared 1 file". */
fun clearedMessage(count: Int): String = "Cleared $count ${if (count == 1) "file" else "files"}"

/**
 * The Settings tab's brain (plan 07-10, GUI-08): shows the effective value of each setting and writes the author's change to
 * settings.json at once. Pure: no Android types, so it is tested with fakes. It offers only enumerated values (T-07-23).
 */
class SettingsPresenter(
    private val scope: CoroutineScope,
    private val settings: SettingsPort,
    private val bridge: BridgePort,
    visibleMs: Long = 6_000L,
) {
    private val ui = MutableStateFlow(SettingsUi())

    val state: StateFlow<SettingsUi> = ui.asStateFlow()

    /** The restart card and its confirmation; its action restarts the bridge. */
    val restart = RestartPrompt(scope, visibleMs) { bridge.restart() }

    init {
        scope.launch {
            settings.data.collectLatest { d ->
                ui.update { it.copy(phoneOnly = d.dialAccess == DialAccessMode.PHONE, clearOnCastEnd = d.clearOnCastEnd) }
                val e = settings.effective()
                ui.update {
                    it.copy(
                        logEnabled = e.logEnabled,
                        gapless = e.gapless,
                        cacheMb = e.cacheMb,
                        cacheCustom = e.cacheMb !in CACHE_STEPS,
                        cacheWarning = if (e.cacheMb == 0) CACHE_ZERO_WARNING else null,
                        outputFormat = e.outputFormat,
                    )
                }
            }
        }
        scope.launch {
            bridge.running.collectLatest { up ->
                ui.update { it.copy(bridgeRunning = up, cacheUsage = if (up) it.cacheUsage else null) }
                if (up) refreshUsage()
            }
        }
    }

    private suspend fun refreshUsage() {
        val info = bridge.cacheInfo()
        ui.update { if (it.bridgeRunning) it.copy(cacheUsage = info) else it }
    }

    /** Re-reads what the running bridge knows (cache usage); the tab calls it whenever it comes on screen. */
    fun refresh() {
        ui.update { it.copy(message = null) }
        scope.launch { if (bridge.running.value) refreshUsage() }
    }

    private fun changed(key: SettingKey): Boolean {
        restart.onSettingChanged(key, bridge.running.value)
        return key.needsRestart
    }

    /** On: only this phone may cast (the safe default). Off: the whole network. Applies to the next request, no restart. */
    fun setPhoneOnly(on: Boolean): Boolean {
        settings.update { it.copy(dialAccess = if (on) DialAccessMode.PHONE else DialAccessMode.LAN) }
        return changed(SettingKey.PHONE_ONLY)
    }

    /** Empty the speaker's queue when the last phone disconnects or the bridge stops. Read live by the bridge. */
    fun setClearOnCastEnd(on: Boolean): Boolean {
        settings.update { it.copy(clearOnCastEnd = on) }
        return changed(SettingKey.CLEAR_ON_CAST_END)
    }

    /** The one global log switch; applies to the running bridge at once. Returns whether a restart is needed. */
    fun setLogEnabled(on: Boolean): Boolean {
        settings.update { it.copy(logEnabled = on) }
        return changed(SettingKey.LOG)
    }

    /** Gapless track changes; the bridge reads it at start, so the restart card shows. Returns whether a restart is needed. */
    fun setGapless(on: Boolean): Boolean {
        settings.update { it.copy(gapless = on) }
        return changed(SettingKey.GAPLESS)
    }

    /** One of [CACHE_STEPS]; anything else is ignored. Applies live to the next prune. */
    fun setCacheMb(mb: Int): Boolean {
        if (mb !in CACHE_STEPS) return false
        settings.update { it.copy(cacheMb = mb) }
        return changed(SettingKey.CACHE)
    }

    /** One of [OUTPUT_FORMATS]; anything else is ignored. The bridge reads it at start, so the restart card shows. */
    fun setOutputFormat(format: String): Boolean {
        if (format !in OUTPUT_FORMATS) return false
        settings.update { it.copy(outputFormat = format) }
        return changed(SettingKey.FORMAT)
    }

    /** Empties the cache of every file no stream uses. Does nothing while the bridge is off. */
    fun clearCache() {
        if (!bridge.running.value) return
        scope.launch {
            val n = bridge.clearCache()
            if (n != null) ui.update { it.copy(message = clearedMessage(n)) }
            refreshUsage()
        }
    }
}
