// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.t2u.app.AppModel
import dev.t2u.app.BatteryGate
import dev.t2u.app.BridgeControl
import dev.t2u.app.model.ToggleAction
import dev.t2u.app.model.confirmText
import dev.t2u.app.model.statusLine
import dev.t2u.app.model.toggleAction

/**
 * The app bar: the name, one line saying whether the bridge runs and who may cast to it, and the one switch
 * that turns the whole bridge off and on (D-02). Turning it off while anything plays asks first (D-03).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun Header(model: AppModel) {
    val context = LocalContext.current
    val status by model.status.collectAsStateWithLifecycle()
    val settings by model.settings.data.collectAsStateWithLifecycle()
    val serviceUp by model.serviceRunning.collectAsStateWithLifecycle()
    // the exemption dialog returns without a reliable result, so read it again whenever the app comes back
    var exempt by remember { mutableStateOf(BatteryGate.isOpen(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = BatteryGate.isOpen(context) }
    var confirming by remember { mutableStateOf(0) }

    TopAppBar(
        title = {
            Column {
                Text("DialUP")
                Text(
                    text = statusLine(status, settings.bridgeEnabled, exempt, serviceUp),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("status-line"),
                )
            }
        },
        actions = {
            Switch(
                checked = settings.bridgeEnabled,
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
        },
    )

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
