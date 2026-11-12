// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import dev.dialup.app.AppSettingsData
import dev.dialup.app.DEFAULT_NAME_PREFIX
import dev.dialup.app.DEFAULT_THEME
import dev.dialup.app.Failures
import dev.dialup.app.MAX_NAME_PREFIX_LENGTH
import dev.dialup.app.ThemeMode
import dev.t2u.dial.DialAccessMode
import dev.t2u.media.CacheInfo
import dev.t2u.media.YTDLP_CHANNELS
import dev.t2u.media.YTDLP_STATUS_CASTING
import dev.t2u.media.YtDlpCheck
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

    /** The name prefix changed: the running speakers take their new names at once. The default does nothing. */
    suspend fun refreshNames() {}

    /**
     * Empties the event log. Unlike the other calls it works with the bridge off too (the files are deleted directly then);
     * true when cleared.
     */
    suspend fun clearLog(): Boolean
    suspend fun cacheInfo(): CacheInfo?
    suspend fun updateNow(): YtDlpUpdateOutcome?

    /** Asks whether a newer yt-dlp is published without installing it; allowed while casting. Null when there is no updater. */
    suspend fun checkYtdlpUpdate(): YtDlpCheck? = null

    /**
     * Stops the bridge, installs the yt-dlp update and starts the bridge again, which ends every cast. Only call it once the
     * author agreed. A bridge that is off simply updates. Null when there is no updater.
     */
    suspend fun updateStoppingBridge(): YtDlpUpdateOutcome? = updateNow()
    suspend fun ytdlpVersion(): String?

    /** The restart-relevant settings the running bridge was started with; null while there is no bridge. */
    fun runningWith(): RestartFields?

    /** Restarts the bridge; [source] ([RESTART_SOURCE] or [AUTO_RESTART_SOURCE]) lands in the `settings` evidence events. */
    suspend fun restart(source: String): Boolean
}

private val NOT_CASTING: StateFlow<Int> = MutableStateFlow(0)

/** The settings the tab changes, and whether the running bridge needs a restart to pick the change up. */
enum class SettingKey(val needsRestart: Boolean) {
    PHONE_ONLY(false),
    CLEAR_ON_CAST_END(false),
    SHOW_AUDIO_FORMAT(false),
    NAME_PREFIX(false),
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
    data class Done(val outcome: YtDlpUpdateOutcome, val restartedBridge: Boolean = false) : UpdateUi

    /** The bridge is off, so there is no updater to ask. */
    data object Unavailable : UpdateUi
}

/** An update is available while a renderer is being cast to: the dialog asks before the cast is ended for it. */
data class UpdatePrompt(val current: String?, val latest: String)

/** Pause between two reads while the new bridge has not answered yet (CastFeature fills its updater and bridge after it is published). */
internal const val USAGE_RETRY_MS = 2_000L

/** Most reads after a bridge comes up before the tab gives up (about 30 s in all). */
internal const val USAGE_ATTEMPTS = 15

/** How often the cache usage is re-read while the Settings tab is on screen, so files added by a cast show up. */
internal const val USAGE_POLL_MS = 5_000L

private const val BUSY_STATUS = "BUSY"

private const val CHECK_FAILED_STATUS = "CHECK_FAILED"

/** The one line the result of an Update now press becomes. The arrow is U+2192. */
fun updateMessage(o: YtDlpUpdateOutcome): String = when {
    o.status == YTDLP_STATUS_CASTING -> "A renderer is being cast to; update yt-dlp when the cast ends"
    o.status == CHECK_FAILED_STATUS -> "Could not check for a newer yt-dlp; try again in a moment"
    o.status == BUSY_STATUS -> "An update or a lookup is running; try again in a minute"
    !o.ok -> "Update failed: ${o.error ?: o.status}"
    o.before != null && o.after != null && o.before != o.after -> "Updated ${o.before} \u2192 ${o.after}"
    else -> "Already up to date (${o.after ?: o.before ?: "unknown version"})"
}

/** What the Settings tab shows. */
data class SettingsUi(
    val phoneOnly: Boolean = true,
    val clearOnCastEnd: Boolean = false,
    val showAudioFormat: Boolean = false,
    /** The text before each speaker's own name in the cast menu; empty is allowed. */
    val namePrefix: String = DEFAULT_NAME_PREFIX,
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
    val outputFormat: String = "streaming",
    val bridgeRunning: Boolean = false,
    /** The installed yt-dlp version; "-" only until it has been read, or when it cannot be read. */
    val ytdlpVersion: String = "-",
    val ytdlpChannel: String = "stable",
    val update: UpdateUi = UpdateUi.Idle,
    /** Set when Update now found a newer yt-dlp while a renderer is being cast to; the tab asks whether to end the cast for it. */
    val updatePrompt: UpdatePrompt? = null,
    /** A one-line result of the last action (cleared cache and so on); null when there is none. */
    val message: String? = null,
    /** The one-line result of the last Clear log, shown under the log buttons; null when there is none. */
    val logMessage: String? = null,
) {
    /** The LAN server mode switch: on when any device on the LAN may cast; the inverse of [phoneOnly]. */
    val lanAccess: Boolean get() = !phoneOnly
}

/** Shown under the log buttons after a successful Clear log. */
const val LOG_CLEARED = "Log cleared"

/** "Cleared 3 files" / "Cleared 1 file". */
fun clearedMessage(count: Int): String = "Cleared $count ${if (count == 1) "file" else "files"}"

/**
 * The Settings tab's brain: shows the effective value of each setting and writes the author's change to
 * settings.json at once. Pure: no Android types, so it is tested with fakes. It offers only enumerated values, so a setting never
 * takes a value outside its list.
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
                    it.copy(
                        phoneOnly = d.dialAccess == DialAccessMode.PHONE,
                        clearOnCastEnd = d.clearOnCastEnd,
                        showAudioFormat = d.showAudioFormat,
                        namePrefix = d.namePrefix,
                        theme = d.theme,
                    )
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
     * Re-reads the cache usage and the yt-dlp version. A bridge or file-system fault keeps the last values: this
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
        scope.launch { readVersion() }
        scope.launch { if (bridge.running) refreshUsage() }
    }

    /** The installed yt-dlp version, which needs no bridge: it is shown from the first moment, not once the bridge is up. */
    private suspend fun readVersion() {
        val version = try {
            bridge.ytdlpVersion()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (version != null) ui.update { it.copy(ytdlpVersion = version) }
    }

    /**
     * Called on every app open (the Activity decides what an open is). When the running bridge was started with other
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
            } catch (e: Exception) {
                Failures.report("settings:auto-restart", e)
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
     * The text before each speaker's own name in the cast menu. Control characters are dropped and the text is cut to
     * [MAX_NAME_PREFIX_LENGTH]; empty is allowed. Applies at once to the running speakers, without a restart, the way a
     * rename does, so a cast keeps playing. Returns the prefix that was saved.
     */
    fun setNamePrefix(text: String): String {
        val clean = text.filter { !it.isISOControl() }.take(MAX_NAME_PREFIX_LENGTH).trim()
        settings.update { it.copy(namePrefix = clean) }
        changed(SettingKey.NAME_PREFIX)
        scope.launch { bridge.refreshNames() }
        return clean
    }

    fun setShowAudioFormat(on: Boolean): Boolean {
        settings.update { it.copy(showAudioFormat = on) }
        return changed(SettingKey.SHOW_AUDIO_FORMAT)
    }

    /**
     * System, Light or Dark. UI-only: the screen recolours at once and the bridge never reads it, so no restart. The
     * parameter is the enum, so there is no out-of-list value to refuse.
     */
    fun setThemeMode(mode: ThemeMode): Boolean {
        settings.update { it.copy(theme = mode) }
        return changed(SettingKey.THEME)
    }

    /**
     * The automatic app-update check. App-only: the bridge never reads it, so there is no [SettingKey] and the restart card is
     * never informed (as when the tab wrote the setting itself).
     */
    fun setUpdateCheckEnabled(on: Boolean) {
        settings.update { it.copy(updateCheckEnabled = on) }
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
                // one line naming the action and the exception class: no stack trace, no path
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
                // one line naming the action and the exception class: no stack trace, no path
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

    /**
     * Update now, with the bridge on or off, casting or not. It first asks whether a newer yt-dlp is published: when it is not,
     * the result says so and nothing is installed. When it is and nothing is cast to, it installs it. When it is and a renderer
     * is being cast to, [UpdatePrompt] is set and nothing happens until [confirmUpdate]: installing ends the cast. A check that
     * could not read the newest version installs nothing while casting and lets the library decide otherwise. One at a time.
     */
    fun updateNow() {
        var start = false
        ui.update {
            if (it.update != UpdateUi.Running && it.updatePrompt == null) {
                start = true
                it.copy(update = UpdateUi.Running)
            } else {
                it
            }
        }
        if (!start) return
        scope.launch {
            val outcome: YtDlpUpdateOutcome? = try {
                val check = bridge.checkYtdlpUpdate()
                val casting = bridge.casting.value > 0
                val latest = check?.latest
                when {
                    check?.available == false -> upToDate(check)
                    check?.available == true && latest != null && casting -> {
                        ui.update { it.copy(update = UpdateUi.Idle, updatePrompt = UpdatePrompt(check.current, latest)) }
                        null
                    }
                    casting -> failed("CHECK_FAILED", "could not check for a newer yt-dlp")
                    else -> runUpdate { bridge.updateNow() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("ERROR", e.javaClass.simpleName)
            }
            if (outcome != null) {
                ui.update { it.copy(update = UpdateUi.Done(outcome)) }
                refreshUsage()
            }
        }
    }

    /** The author agreed to end the cast: stop the bridge, install the update, start the bridge again. */
    fun confirmUpdate() {
        var start = false
        ui.update {
            if (it.updatePrompt != null && it.update != UpdateUi.Running) {
                start = true
                it.copy(updatePrompt = null, update = UpdateUi.Running)
            } else {
                it
            }
        }
        if (!start) return
        scope.launch {
            val outcome = try {
                runUpdate { bridge.updateStoppingBridge() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("ERROR", e.javaClass.simpleName)
            }
            ui.update { it.copy(update = UpdateUi.Done(outcome, restartedBridge = true)) }
            refreshUsage()
        }
    }

    /** The author said no: nothing is stopped or installed. */
    fun dismissUpdatePrompt() {
        ui.update { it.copy(updatePrompt = null) }
    }

    private suspend fun runUpdate(block: suspend () -> YtDlpUpdateOutcome?): YtDlpUpdateOutcome =
        block() ?: YtDlpUpdateOutcome(
            ok = false, trigger = "ui", channel = ui.value.ytdlpChannel, before = null, after = null,
            status = "UNAVAILABLE", ms = 0, error = "yt-dlp is not available",
        )

    // end as a failed outcome so the button is usable again instead of stuck on Running
    private fun failed(status: String, error: String) = YtDlpUpdateOutcome(
        ok = false, trigger = "ui", channel = ui.value.ytdlpChannel, before = null, after = null, status = status, ms = 0, error = error,
    )

    private fun upToDate(check: YtDlpCheck) = YtDlpUpdateOutcome(
        ok = true, trigger = "ui", channel = check.channel, before = check.current, after = check.current,
        status = "ALREADY_UP_TO_DATE", ms = 0,
    )
}
