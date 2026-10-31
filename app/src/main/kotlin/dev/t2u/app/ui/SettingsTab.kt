// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.t2u.app.model.SettingsPresenter

/** The Settings tab (GUI-08): every playback and maintenance setting, each saved as soon as it is changed. */
@Composable
fun SettingsTab(presenter: SettingsPresenter, modifier: Modifier = Modifier) {
    val ui by presenter.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SwitchRow(
            title = "Only this phone may cast to the speakers",
            support = "Off: any device on the Wi-Fi may cast to them",
            checked = ui.phoneOnly,
            onChange = presenter::setPhoneOnly,
            tag = "setting-phone-only",
        )
        HorizontalDivider()
        SwitchRow(
            title = "Clear the speaker when a cast ends",
            support = "When the last phone disconnects or the bridge stops, empty the speaker's queue. Off: the speaker keeps the " +
                "track. Nothing is cleared after a crash or a force stop.",
            checked = ui.clearOnCastEnd,
            onChange = presenter::setClearOnCastEnd,
            tag = "setting-clear-on-end",
        )
    }
}

@Composable
internal fun SwitchRow(title: String, support: String?, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = support?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag)) },
    )
}
