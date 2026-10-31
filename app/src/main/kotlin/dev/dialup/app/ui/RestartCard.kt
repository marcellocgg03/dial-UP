// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dialup.app.AppModel
import dev.dialup.app.model.RestartPrompt
import dev.dialup.app.model.confirmText

/**
 * The thin card that offers a bridge restart after a setting that needs one (GUI-09, D-09). It goes away by itself; the
 * confirmation for a restart during a cast is a dialog and so is shown here even after the card has gone (D-03).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RestartCard(model: AppModel, prompt: RestartPrompt, clearOnCastEnd: Boolean, modifier: Modifier = Modifier) {
    val visible by prompt.visible.collectAsStateWithLifecycle()
    val pending by prompt.confirmPending.collectAsStateWithLifecycle()
    val casting by model.casting.collectAsStateWithLifecycle()

    if (visible) {
        Card(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("restart-card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Restart the bridge to apply this change",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { prompt.requestRestart(model.casting.value) }, modifier = Modifier.testTag("btn-restart")) {
                    Text("Restart")
                }
                IconButton(onClick = prompt::dismiss, modifier = Modifier.testTag("btn-restart-close")) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
        }
    }

    if (pending) {
        // a dialog is its own window, so the root's testTagsAsResourceId does not reach it
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = prompt::cancelRestart,
            title = { Text("Restart the bridge?") },
            text = {
                Text(
                    text = confirmText(casting, clearOnCastEnd).replace("Switch the bridge off?", "Restart the bridge?"),
                    modifier = Modifier.testTag("confirm-restart"),
                )
            },
            confirmButton = {
                TextButton(onClick = prompt::confirmRestart, modifier = Modifier.testTag("confirm-restart-yes")) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = prompt::cancelRestart, modifier = Modifier.testTag("confirm-restart-no")) { Text("No") }
            },
        )
    }
}
