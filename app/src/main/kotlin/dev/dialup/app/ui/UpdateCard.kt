// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dialup.app.model.AppUpdateModel
import dev.dialup.app.model.CheckOutcome
import dev.dialup.app.model.UpdateChannel
import dev.dialup.app.model.appUpdateMessage
import dev.dialup.app.model.cardRelease
import dev.dialup.app.model.safeReleaseUrl
import kotlinx.coroutines.launch

/**
 * The card on the Renderers tab that says a newer DialUP release exists. It shows only while automatic checks are on
 * and the recorded release is newer than [currentVersion]; it has no dismiss button, it goes when the app is updated or the
 * switch is turned off. The link opens only a page that passed [safeReleaseUrl].
 */
@Composable
fun UpdateCard(model: AppUpdateModel, autoEnabled: Boolean, currentVersion: String, channel: UpdateChannel = UpdateChannel.STABLE) {
    val state by model.state.collectAsStateWithLifecycle()
    val card = cardRelease(autoEnabled, state.record, currentVersion, channel) ?: return
    val uri = LocalUriHandler.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("update-card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "DialUP ${card.tag} is available",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).testTag("update-card-text"),
            )
            Button(
                onClick = { runCatching { uri.openUri(safeReleaseUrl(card.url)) } },
                modifier = Modifier.testTag("btn-open-release"),
            ) { Text("Open release page") }
        }
    }
}

/**
 * The App updates section of Settings: the version, the automatic-check switch and Check now, which works with the
 * switch off and ignores the throttle. A newer release found by hand gets a link, again only through [safeReleaseUrl].
 */
@Composable
fun AppUpdatesSection(
    model: AppUpdateModel,
    autoEnabled: Boolean,
    currentVersion: String,
    onAutoChange: (Boolean) -> Unit,
    channel: UpdateChannel = UpdateChannel.STABLE,
    onChannelChange: (UpdateChannel) -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val outcome = state.outcome
    ListItem(
        headlineContent = { Text("App updates") },
        supportingContent = {
            Column {
                Text("Version $currentVersion", modifier = Modifier.testTag("app-version"))
                Text("Checks GitHub for a newer DialUP release when the app is opened")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Channel", modifier = Modifier.weight(1f))
                    Choice(
                        current = channel.wire.replaceFirstChar { it.uppercase() },
                        options = UpdateChannel.entries.map { it.wire.replaceFirstChar { c -> c.uppercase() } },
                        onPick = { i -> onChannelChange(UpdateChannel.entries[i]) },
                        tag = "setting-update-channel",
                        optionTag = { "update-channel-option-${UpdateChannel.entries[it].wire}" },
                    )
                }
                OutlinedButton(
                    onClick = { scope.launch { model.checkNow() } },
                    enabled = !state.checking,
                    modifier = Modifier.padding(top = 8.dp).testTag("btn-check-update"),
                ) { Text("Check now") }
                if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                if (outcome != null) {
                    Text(
                        appUpdateMessage(outcome, currentVersion),
                        modifier = Modifier.padding(top = 8.dp).testTag("update-check-result"),
                    )
                }
                if (outcome is CheckOutcome.Newer) {
                    TextButton(
                        onClick = { runCatching { uri.openUri(safeReleaseUrl(outcome.release.url)) } },
                        modifier = Modifier.testTag("btn-open-release-settings"),
                    ) { Text("Open release page") }
                }
            }
        },
        trailingContent = {
            Switch(checked = autoEnabled, onCheckedChange = onAutoChange, modifier = Modifier.testTag("setting-update-check"))
        },
    )
}
