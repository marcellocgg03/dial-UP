// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dialup.app.AppModel
import dev.dialup.app.BatteryGate
import dev.dialup.app.BridgeControl
import dev.dialup.app.model.ToggleAction
import dev.dialup.app.model.confirmText
import dev.dialup.app.model.statusLine
import dev.dialup.app.model.toggleAction
import dev.dialup.app.model.toggleUi

/**
 * The bridge card at the top of both tabs: its switch turns the whole bridge off and on, and turning
 * it off while anything plays asks first. The only subtitle is "Waiting for Wi-Fi", shown when the
 * switch is on, the exemption is held and the phone has no Wi-Fi or hotspot address; otherwise there is none. The switch is labelled "Enable" and is greyed out while the battery exemption is missing; it
 * opens no request itself, the exemption is granted from the battery banner (MainActivity owns the launcher). A grant
 * only makes the switch usable and never flips it: it keeps showing the persisted state.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Header(model: AppModel) {
    val context = LocalContext.current
    val settings by model.settings.data.collectAsStateWithLifecycle()
    val wifiPresent by model.wifiPresent.collectAsStateWithLifecycle()
    // the exemption dialog returns without a reliable result, so read it again whenever the app comes back
    var exempt by remember { mutableStateOf(BatteryGate.isOpen(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = BatteryGate.isOpen(context) }
    val ui = toggleUi(settings.bridgeEnabled, exempt)
    var confirming by remember { mutableStateOf(0) }

    // the app is edge-to-edge, so the card keeps itself below the status bar
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("bridge-card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enable", style = MaterialTheme.typography.titleMedium)
                val line = statusLine(settings.bridgeEnabled, exempt, wifiPresent)
                if (line.isNotEmpty()) {
                    Text(text = line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("status-line"))
                }
            }
            Switch(
                checked = ui.checked,
                enabled = ui.usable,
                onCheckedChange = { requested ->
                    val casting = model.casting.value
                    when (toggleAction(requested, settings.bridgeEnabled, casting)) {
                        ToggleAction.START -> BridgeControl.setEnabled(context, true, "ui")
                        ToggleAction.STOP -> BridgeControl.setEnabled(context, false, "ui")
                        ToggleAction.CONFIRM_STOP -> confirming = casting
                        ToggleAction.NOTHING -> Unit
                    }
                },
                modifier = Modifier.testTag("toggle-bridge"),
            )
        }
    }

    if (confirming > 0) {
        // a dialog is its own window, so the root's testTagsAsResourceId does not reach it
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { confirming = 0 },
            title = { Text("Switch the bridge off?") },
            text = {
                Text(
                    text = confirmText(confirming, settings.clearOnCastEnd),
                    modifier = Modifier.testTag("confirm-off"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = 0
                        BridgeControl.setEnabled(context, false, "ui")
                    },
                    modifier = Modifier.testTag("confirm-off-yes"),
                ) { Text("Yes") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirming = 0 },
                    modifier = Modifier.testTag("confirm-off-no"),
                ) { Text("No") }
            },
        )
    }
}
