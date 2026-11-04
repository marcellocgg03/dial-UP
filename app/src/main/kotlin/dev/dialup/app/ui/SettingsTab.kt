// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dialup.app.AppModel
import dev.dialup.app.ShareLogActivity
import dev.dialup.app.ThemeMode
import dev.dialup.app.model.CACHE_STEPS
import dev.dialup.app.model.SettingsPresenter
import dev.dialup.app.model.SettingsUi
import dev.dialup.app.model.UpdateUi
import dev.dialup.app.model.updateMessage

private val FORMATS = listOf(
    "auto" to "Automatic",
    "direct" to "Direct",
    "mp4" to "MP4",
    "adts" to "AAC stream (adts)",
    "flac" to "FLAC",
    "mp3" to "MP3",
)

private val THEMES = listOf(
    ThemeMode.SYSTEM to "System",
    ThemeMode.LIGHT to "Light",
    ThemeMode.DARK to "Dark",
)

/**
 * The Settings tab (GUI-08): every playback and maintenance setting, each saved as soon as it is changed. The log switch with its
 * Share log and Clear log buttons comes last.
 */
@Composable
fun SettingsTab(presenter: SettingsPresenter, model: AppModel, modifier: Modifier = Modifier) {
    val ui by presenter.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(presenter) {
        presenter.refresh()
        presenter.onVisible()
        onPauseOrDispose { presenter.onHidden() }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SwitchRow(
            title = "LAN access",
            support = "Any device on the LAN can access the bridge running through this app and cast to the speakers",
            checked = ui.lanAccess,
            onChange = { presenter.setLanAccess(it) },
            tag = "setting-phone-only",
        )
        HorizontalDivider()
        SwitchRow(
            title = "Clear the renderer when a cast ends",
            support = "When a cast is ended or the phone disconnects, empty the renderer's queue",
            checked = ui.clearOnCastEnd,
            onChange = { presenter.setClearOnCastEnd(it) },
            tag = "setting-clear-on-end",
        )
        HorizontalDivider()
        SwitchRow(
            title = "Gapless track changes on renderers that support it",
            support = null,
            checked = ui.gapless,
            onChange = { presenter.setGapless(it) },
            tag = "setting-gapless",
        )
        HorizontalDivider()
        CacheSection(ui, presenter)
        HorizontalDivider()
        ChoiceRow(
            title = "Forced audio format",
            support = "A renderer that cannot play a forced format shows an error on its card instead of trying another format.",
            current = FORMATS.firstOrNull { it.first == ui.outputFormat }?.second ?: ui.outputFormat,
            options = FORMATS.map { it.second },
            onPick = { i -> presenter.setOutputFormat(FORMATS[i].first) },
            tag = "setting-format",
            optionTag = { "format-option-${FORMATS[it].first}" },
        )
        HorizontalDivider()
        HiddenSection(model)
        HorizontalDivider()
        YtDlpSection(ui, presenter)
        HorizontalDivider()
        ChoiceRow(
            title = "Theme",
            support = "System follows the phone's light or dark setting",
            current = THEMES.first { it.first == ui.theme }.second,
            options = THEMES.map { it.second },
            onPick = { i -> presenter.setThemeMode(THEMES[i].first) },
            tag = "setting-theme",
            optionTag = { "theme-option-${THEMES[it].first.wire}" },
        )
        HorizontalDivider()
        LogSection(ui, presenter)
    }
}

private val CHANNELS = listOf("stable", "nightly")

@Composable
private fun YtDlpSection(ui: SettingsUi, presenter: SettingsPresenter) {
    val running = ui.update == UpdateUi.Running
    ListItem(
        headlineContent = { Text("yt-dlp") },
        supportingContent = {
            Column {
                Text("Version ${ui.ytdlpVersion}", modifier = Modifier.testTag("ytdlp-version"))
                OutlinedButton(
                    onClick = presenter::updateNow,
                    enabled = !running,
                    modifier = Modifier.padding(top = 8.dp).testTag("btn-update-ytdlp"),
                ) { Text("Update now") }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                (ui.update as? UpdateUi.Done)?.let {
                    Text(updateMessage(it.outcome), modifier = Modifier.padding(top = 8.dp).testTag("update-result"))
                }
            }
        },
        trailingContent = {
            Choice(
                current = ui.ytdlpChannel.replaceFirstChar { it.uppercase() },
                options = CHANNELS.map { it.replaceFirstChar { c -> c.uppercase() } },
                onPick = { i -> presenter.setYtdlpChannel(CHANNELS[i]) },
                tag = "setting-channel",
                optionTag = { "channel-option-${CHANNELS[it]}" },
            )
        },
    )
}

@Composable
private fun CacheSection(ui: SettingsUi, presenter: SettingsPresenter) {
    val steps = if (ui.cacheCustom) listOf(ui.cacheMb) + CACHE_STEPS else CACHE_STEPS
    val usage = ui.cacheUsage
    ListItem(
        headlineContent = { Text("Cache size") },
        supportingContent = {
            Column {
                Text(
                    if (usage != null) {
                        "${usage.files} ${if (usage.files == 1) "file" else "files"}, ${megabytes(usage.bytes)}"
                    } else {
                        "Reading the cache"
                    },
                    modifier = Modifier.testTag("cache-usage"),
                )
                if (ui.cacheCustom) Text("Custom value from config.json")
                ui.cacheWarning?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cache-warning"))
                }
                ui.message?.let { Text(it, modifier = Modifier.testTag("cache-message")) }
                OutlinedButton(
                    onClick = presenter::clearCache,
                    modifier = Modifier.padding(top = 8.dp).testTag("btn-clear-cache"),
                ) { Text("Clear cache") }
            }
        },
        trailingContent = {
            Choice(
                current = "${ui.cacheMb} MB",
                options = steps.map { "$it MB" },
                onPick = { i -> if (steps[i] in CACHE_STEPS) presenter.setCacheMb(steps[i]) },
                tag = "setting-cache",
                optionTag = { "cache-option-${steps[it]}" },
            )
        },
    )
}

@Composable
private fun LogSection(ui: SettingsUi, presenter: SettingsPresenter) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text("Write the event log") },
        supportingContent = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(context, ShareLogActivity::class.java)) },
                        modifier = Modifier.testTag("btn-share-log"),
                    ) { Text("Share log") }
                    OutlinedButton(
                        onClick = presenter::clearLog,
                        modifier = Modifier.testTag("btn-clear-log"),
                    ) { Text("Clear log") }
                }
                ui.logMessage?.let { Text(it, modifier = Modifier.padding(top = 8.dp).testTag("log-message")) }
            }
        },
        trailingContent = {
            Switch(checked = ui.logEnabled, onCheckedChange = { presenter.setLogEnabled(it) }, modifier = Modifier.testTag("setting-log"))
        },
    )
}

private fun megabytes(bytes: Long): String = "${(bytes + 524_288L) / 1_048_576L} MB"

@Composable
internal fun SwitchRow(title: String, support: String?, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = support?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag)) },
    )
}

@Composable
internal fun ChoiceRow(
    title: String,
    support: String?,
    current: String,
    options: List<String>,
    onPick: (Int) -> Unit,
    tag: String,
    optionTag: (Int) -> String,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = support?.let { { Text(it) } },
        trailingContent = { Choice(current, options, onPick, tag, optionTag) },
    )
}

/** A button showing the [current] choice that opens a menu of [options]. */
@Composable
internal fun Choice(current: String, options: List<String>, onPick: (Int) -> Unit, tag: String, optionTag: (Int) -> String) {
    var open by remember { mutableStateOf(false) }
    Column {
        Button(onClick = { open = true }, modifier = Modifier.testTag(tag)) { Text(current) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onPick(i)
                    },
                    modifier = Modifier.testTag(optionTag(i)),
                )
            }
        }
    }
}
