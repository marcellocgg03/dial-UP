// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import dev.dialup.app.AppSettingsData
import dev.dialup.app.DEFAULT_THEME
import dev.dialup.app.ThemeMode
import dev.t2u.dial.DialAccessMode
import dev.t2u.media.CacheInfo
import dev.t2u.media.YTDLP_CHANNELS
import dev.t2u.media.YTDLP_STATUS_CASTING
import dev.t2u.media.YtDlpUpdateOutcome
import dev.dialup.app.SETTINGS_OUTPUT_FORMATS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
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

/**
 * What the Settings tab asks of the running bridge. Every call except [clearLog] is safe with the bridge off and answers null or
 * false then.
 */
interface BridgePort {
    /**
     * The identity of the running bridge (its CastFeature in the app), null while there is none; every restart gives a new
     * value, also when the bridge is up before and after.
     */
    val instance: StateFlow<Any?>

    /** How many renderers are being cast to now; 0 while there is no bridge. Update now is not offered while it is above 0. */
    val casting: StateFlow<Int> get() = NOT_CASTING

    /** Whether there is a running bridge now. */
    val running: Boolean get() = instance.value != null

    suspend fun clearCache(): Int?

    /** Empties the event log. Unlike the other calls it works with the bridge off too (the files are deleted directly then); true when cleared. */
    suspend fun clearLog(): Boolean
    suspend fun cacheInfo(): CacheInfo?
    suspend fun updateNow(): YtDlpUpdateOutcome?
    suspend fun ytdlpVersion(): String?

    /** The restart-relevant settings the running bridge was started with; null while there is no bridge (PD-6). */
    fun runningWith(): RestartFields?

    /** Restarts the bridge; [source] ([RESTART_SOURCE] or [AUTO_RESTART_SOURCE]) lands in the `settings` evidence events. */
    suspend fun restart(source: String): Boolean
}

private val NOT_CASTING: StateFlow<Int> = MutableStateFlow(0)

/** The settings the tab changes, and whether the running bridge needs a restart to pick the change up (D-09). */
enum class SettingKey(val needsRestart: Boolean) {
    PHONE_ONLY(false),
    CLEAR_ON_CAST_END(false),
    LOG(false),
    GAPLESS(true),
    CACHE(false),
    FORMAT(true),
    CHANNEL(false),
    THEME(false),
}

/** The cache sizes offered, in MiB (0 keeps only the file just built). */
val CACHE_STEPS: List<Int> = listOf(0, 64, 128, 256, 512, 1024, 2048, 4096)

/** Shown under the cache size when it is 0 (the pre-work todo, resolved as keep 0 with a warning). */
const val CACHE_ZERO_WARNING =
    "With 0 MB new tracks are temporary and removed after playback; existing cached tracks remain until cleared"

/** Where the yt-dlp Update now button stands. */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Running : UpdateUi
    data class Done(val outcome: YtDlpUpdateOutcome) : UpdateUi

    /** The bridge is off, so there is no updater to ask. */
    data object Unavailable : UpdateUi
}

/** Pause between two reads while the new bridge has not answered yet (CastFeature fills its updater and bridge after it is published). */
internal const val USAGE_RETRY_MS = 2_000L

/** Most reads after a bridge comes up before the tab gives up (about 30 s in all). */
internal const val USAGE_ATTEMPTS = 15

/** How often the cache usage is re-read while the Settings tab is on screen, so files added by a cast show up. */
internal const val USAGE_POLL_MS = 5_000L

private const val BUSY_STATUS = "BUSY"

/** The one line the result of an Update now press becomes. The arrow is U+2192. */
fun updateMessage(o: YtDlpUpdateOutcome): String = when {
    o.status == YTDLP_STATUS_CASTING -> "A renderer is being cast to; update yt-dlp when the cast ends"
    o.status == BUSY_STATUS -> "An update or a lookup is running; try again in a minute"
    !o.ok -> "Update failed: ${o.error ?: o.status}"
    o.before != null && o.after != null && o.before != o.after -> "Updated ${o.before} \u2192 ${o.after}"
    else -> "Already up to date (${o.after ?: o.before ?: "unknown version"})"
}

/** What the Settings tab shows. */
data class SettingsUi(
    val phoneOnly: Boolean = true,
    val clearOnCastEnd: Boolean = false,
    /** The app's look, applied at once. */
    val theme: ThemeMode = DEFAULT_THEME,
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
    /** The installed yt-dlp version; "-" while the bridge is off or the version cannot be read. */
    val ytdlpVersion: String = "-",
    val ytdlpChannel: String = "stable",
    val update: UpdateUi = UpdateUi.Idle,
    /** True while a renderer is being cast to: Update now is greyed out then (UPD-02). */
    val updateBlockedByCast: Boolean = false,
    /** A one-line result of the last action (cleared cache and so on); null when there is none. */
    val message: String? = null,
    /** The one-line result of the last Clear log, shown under the log buttons; null when there is none. */
    val logMessage: String? = null,
) {
    /** The LAN access switch: on when any device on the LAN may cast; the inverse of [phoneOnly]. */
    val lanAccess: Boolean get() = !phoneOnly
}

/** Shown under the log buttons after a successful Clear log. */
const val LOG_CLEARED = "Log cleared"

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
    val restart = RestartPrompt(scope, visibleMs) { source -> bridge.restart(source) }

    init {
        scope.launch {
            settings.data.collectLatest { d ->
                ui.update {
                    it.copy(phoneOnly = d.dialAccess == DialAccessMode.PHONE, clearOnCastEnd = d.clearOnCastEnd, theme = d.theme)
                }
                val e = settings.effective()
                ui.update {
                    it.copy(
                        logEnabled = e.logEnabled,
                        gapless = e.gapless,
                        cacheMb = e.cacheMb,
                        cacheCustom = e.cacheMb !in CACHE_STEPS,
                        cacheWarning = if (e.cacheMb == 0) CACHE_ZERO_WARNING else null,
                        outputFormat = e.outputFormat,
                        ytdlpChannel = e.ytdlpChannel,
                    )
                }
            }
        }
        scope.launch {
            bridge.casting.collect { n -> ui.update { it.copy(updateBlockedByCast = n > 0) } }
        }
        scope.launch {
            bridge.instance.collectLatest { inst ->
                val up = inst != null
                ui.update {
                    it.copy(
                        bridgeRunning = up,
                        update = if (it.update == UpdateUi.Unavailable) UpdateUi.Idle else it.update,
                    )
                }
                // The cache and yt-dlp answer with the bridge off too (the bridge port falls back to the files on disk).
                refreshUntilAnswered()
            }
        }
    }

    /** Reads until the cache info and the version both answer, at most [USAGE_ATTEMPTS] reads [USAGE_RETRY_MS] apart. */
    private suspend fun refreshUntilAnswered() {
        var reads = 0
        while (true) {
            reads++
            if (refreshUsage() || reads >= USAGE_ATTEMPTS) return
            delay(USAGE_RETRY_MS)
        }
    }

    /**
     * Re-reads the cache usage and the yt-dlp version. A bridge or file-system fault keeps the last values (review IN-04): this
     * runs in coroutines whose exceptions would reach the composition scope's handler and end the process. Cancellation propagates.
     * True only when both answers came back non-null.
     */
    private suspend fun refreshUsage(): Boolean {
        try {
            val info = bridge.cacheInfo()
            val version = bridge.ytdlpVersion()
            ui.update { it.copy(cacheUsage = info ?: it.cacheUsage, ytdlpVersion = version ?: it.ytdlpVersion) }
            return info != null && version != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // keep what the screen shows; the next refresh tries again
            return false
        }
    }

    private var pollJob: Job? = null

    /**
     * Called by the tab when it comes on screen: re-reads the cache usage every [USAGE_POLL_MS] until [onHidden]. One loop at a
     * time; it never calls the bridge while the bridge is off. The yt-dlp version is not polled (only Update now changes it).
     */
    fun onVisible() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(USAGE_POLL_MS)
                try {
                    val info = bridge.cacheInfo()
                    ui.update { it.copy(cacheUsage = info ?: it.cacheUsage) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // keep what the screen shows; the next poll tries again
                }
            }
        }
    }

    /** Called by the tab when it leaves the screen or the app pauses: stops the poll. */
    fun onHidden() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Re-reads what the running bridge knows (cache usage); the tab calls it whenever it comes on screen. */
    fun refresh() {
        ui.update { it.copy(message = null, logMessage = null) }
        scope.launch { if (bridge.running) refreshUsage() }
    }

    /**
     * Called on every app open (PD-7; the Activity decides what an open is). When the running bridge was started with other
     * restart-relevant settings than the saved ones and the switch is on, it restarts the bridge once with nothing cast
     * ([RestartPrompt.restartNow]) or, with something cast ([casting] above 0), shows the restart card until nothing is cast
     * ([RestartPrompt.showUntilCastEnds]). Nothing pending, no bridge or the switch off: nothing. A failure is dropped; an open
     * must never crash the app. [casting] is read at decision time.
     */
    fun onAppOpened(casting: () -> Int) {
        scope.launch {
            try {
                val saved = restartFields(settings.effective())
                when (autoRestartOnOpen(bridge.runningWith(), saved, settings.data.value.bridgeEnabled, casting())) {
                    AutoRestart.RESTART -> restart.restartNow()
                    AutoRestart.SHOW_CARD -> restart.showUntilCastEnds()
                    AutoRestart.NONE -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // nothing is applied; the author's own restart card still works
            }
        }
    }

    private fun changed(key: SettingKey): Boolean {
        restart.onSettingChanged(key, bridge.running)
        return key.needsRestart
    }

    /** On: only this phone may cast (the safe default). Off: the whole network. Applies to the next request, no restart. */
    fun setPhoneOnly(on: Boolean): Boolean {
        settings.update { it.copy(dialAccess = if (on) DialAccessMode.PHONE else DialAccessMode.LAN) }
        return changed(SettingKey.PHONE_ONLY)
    }

    /**
     * The Settings row is shown from the LAN side (author's wording 2026-11-01): on means any device on the LAN may cast. Same
     * setting and same default as [setPhoneOnly], no restart.
     */
    fun setLanAccess(on: Boolean): Boolean = setPhoneOnly(!on)

    /** Empty the speaker's queue when the last phone disconnects or the bridge stops. Read live by the bridge. */
    fun setClearOnCastEnd(on: Boolean): Boolean {
        settings.update { it.copy(clearOnCastEnd = on) }
        return changed(SettingKey.CLEAR_ON_CAST_END)
    }

    /**
     * System, Light or Dark. UI-only: the screen recolours at once and the bridge never reads it, so no restart. The
     * parameter is the enum, so there is no out-of-list value to refuse (T-07-23 by type).
     */
    fun setThemeMode(mode: ThemeMode): Boolean {
        settings.update { it.copy(theme = mode) }
        return changed(SettingKey.THEME)
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

    /** One of [SETTINGS_OUTPUT_FORMATS]; anything else is ignored. The bridge reads it at start, so the restart card shows. */
    fun setOutputFormat(format: String): Boolean {
        if (format !in SETTINGS_OUTPUT_FORMATS) return false
        settings.update { it.copy(outputFormat = format) }
        return changed(SettingKey.FORMAT)
    }

    /** Empties the cache of every file no stream uses, with the bridge on or off. */
    fun clearCache() {
        scope.launch {
            try {
                val n = bridge.clearCache()
                if (n != null) ui.update { it.copy(message = clearedMessage(n)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // one line naming the action and the exception class: no stack trace, no path (review IN-04)
                ui.update { it.copy(message = "Could not clear the cache (${e.javaClass.simpleName})") }
            }
            refreshUsage()
        }
    }

    /** Empties the event log, with the bridge on or off. The result is one line under the log buttons. */
    fun clearLog() {
        scope.launch {
            try {
                val done = bridge.clearLog()
                ui.update { it.copy(logMessage = if (done) LOG_CLEARED else "Could not clear the log") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // one line naming the action and the exception class: no stack trace, no path (review IN-04)
                ui.update { it.copy(logMessage = "Could not clear the log (${e.javaClass.simpleName})") }
            }
        }
    }

    /** stable or nightly; anything else is ignored. The updater reads it at each attempt, so no restart. */
    fun setYtdlpChannel(channel: String): Boolean {
        if (channel !in YTDLP_CHANNELS) return false
        settings.update { it.copy(ytdlpChannel = channel) }
        return changed(SettingKey.CHANNEL)
    }

    /** Runs one yt-dlp update now, with the bridge on or off. One at a time. */
    fun updateNow() {
        var start = false
        ui.update {
            if (it.updateBlockedByCast) {
                // Refused here without asking the bridge; the updater refuses a press that races the cast start as well.
                it.copy(
                    update = UpdateUi.Done(
                        YtDlpUpdateOutcome(
                            ok = false, trigger = "ui", channel = it.ytdlpChannel, before = null, after = null,
                            status = YTDLP_STATUS_CASTING, ms = 0,
                        ),
                    ),
                )
            } else if (it.update != UpdateUi.Running) {
                start = true
                it.copy(update = UpdateUi.Running)
            } else {
                it
            }
        }
        if (!start) return
        scope.launch {
            val outcome = try {
                bridge.updateNow() ?: YtDlpUpdateOutcome(
                    ok = false, trigger = "ui", channel = ui.value.ytdlpChannel, before = null, after = null,
                    status = "UNAVAILABLE", ms = 0, error = "yt-dlp is not available",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // end as a failed outcome so the button is usable again instead of stuck on Running (review IN-04)
                YtDlpUpdateOutcome(
                    ok = false, trigger = "ui", channel = ui.value.ytdlpChannel, before = null, after = null,
                    status = "ERROR", ms = 0, error = e.javaClass.simpleName,
                )
            }
            ui.update { it.copy(update = UpdateUi.Done(outcome)) }
            refreshUsage()
        }
    }
}
