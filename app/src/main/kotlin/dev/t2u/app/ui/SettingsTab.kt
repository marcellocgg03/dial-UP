// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.t2u.app.model.CACHE_STEPS
import dev.t2u.app.model.SettingsPresenter
import dev.t2u.app.model.SettingsUi

private val FORMATS = listOf(
    "auto" to "Automatic",
    "direct" to "Direct",
    "mp4" to "MP4",
    "adts" to "AAC stream (adts)",
    "flac" to "FLAC",
    "mp3" to "MP3",
)

/** The Settings tab (GUI-08): every playback and maintenance setting, each saved as soon as it is changed. */
@Composable
fun SettingsTab(presenter: SettingsPresenter, modifier: Modifier = Modifier) {
    val ui by presenter.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { presenter.refresh() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SwitchRow(
            title = "Only this phone may cast to the speakers",
            support = "Off: any device on the Wi-Fi may cast to them",
            checked = ui.phoneOnly,
            onChange = { presenter.setPhoneOnly(it) },
            tag = "setting-phone-only",
        )
        HorizontalDivider()
        SwitchRow(
            title = "Clear the speaker when a cast ends",
            support = "When the last phone disconnects or the bridge stops, empty the speaker's queue. Off: the speaker keeps the " +
                "track. Nothing is cleared after a crash or a force stop.",
            checked = ui.clearOnCastEnd,
            onChange = { presenter.setClearOnCastEnd(it) },
            tag = "setting-clear-on-end",
        )
        HorizontalDivider()
        SwitchRow(
            title = "Write the event log",
            support = null,
            checked = ui.logEnabled,
            onChange = { presenter.setLogEnabled(it) },
            tag = "setting-log",
        )
        HorizontalDivider()
        SwitchRow(
            title = "Gapless track changes on speakers that support it",
            support = "Applies after the bridge restarts",
            checked = ui.gapless,
            onChange = { presenter.setGapless(it) },
            tag = "setting-gapless",
        )
        HorizontalDivider()
        CacheSection(ui, presenter)
        HorizontalDivider()
        ChoiceRow(
            title = "Forced audio format",
            support = "Applies after the bridge restarts. A speaker that cannot play a forced format shows an error on its card " +
                "instead of trying another format.",
            current = FORMATS.firstOrNull { it.first == ui.outputFormat }?.second ?: ui.outputFormat,
            options = FORMATS.map { it.second },
            onPick = { i -> presenter.setOutputFormat(FORMATS[i].first) },
            tag = "setting-format",
            optionTag = { "format-option-${FORMATS[it].first}" },
        )
    }
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
                        "${usage.files} ${if (usage.files == 1) "file" else "files"}, ${megabytes(usage.bytes)} of " +
                            "${megabytes(usage.limitBytes)}"
                    } else {
                        "Start the bridge to see the cache usage"
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
                    enabled = ui.bridgeRunning,
                    modifier = Modifier.padding(top = 8.dp).testTag("btn-clear-cache"),
                ) { Text("Clear cache") }
                if (!ui.bridgeRunning) Text("Start the bridge to clear the cache", style = MaterialTheme.typography.bodySmall)
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
