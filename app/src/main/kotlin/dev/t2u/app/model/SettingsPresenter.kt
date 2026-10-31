// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.model

import dev.t2u.app.AppSettingsData
import dev.t2u.dial.DialAccessMode
import dev.t2u.media.CacheInfo
import dev.t2u.media.YtDlpUpdateOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

/** What the Settings tab shows. */
data class SettingsUi(
    val phoneOnly: Boolean = true,
    val clearOnCastEnd: Boolean = false,
    /** A one-line result of the last action (cleared cache and so on); null when there is none. */
    val message: String? = null,
)

/**
 * The Settings tab's brain (plan 07-10, GUI-08): shows the effective value of each setting and writes the author's change to
 * settings.json at once. Pure: no Android types, so it is tested with fakes.
 */
class SettingsPresenter(
    scope: CoroutineScope,
    private val settings: SettingsPort,
    private val bridge: BridgePort,
) {
    private val ui = MutableStateFlow(SettingsUi())

    val state: StateFlow<SettingsUi> = ui.asStateFlow()

    init {
        scope.launch {
            settings.data.collect { d ->
                ui.update { it.copy(phoneOnly = d.dialAccess == DialAccessMode.PHONE, clearOnCastEnd = d.clearOnCastEnd) }
            }
        }
    }

    /** On: only this phone may cast (the safe default). Off: the whole network. Applies to the next request, no restart. */
    fun setPhoneOnly(on: Boolean) {
        settings.update { it.copy(dialAccess = if (on) DialAccessMode.PHONE else DialAccessMode.LAN) }
    }

    /** Empty the speaker's queue when the last phone disconnects or the bridge stops. Read live by the bridge. */
    fun setClearOnCastEnd(on: Boolean) {
        settings.update { it.copy(clearOnCastEnd = on) }
    }
}
