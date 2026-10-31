// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.t2u.app.AppModel
import dev.t2u.renderer.ManualAddResult
import kotlinx.coroutines.launch

/**
 * What the dialog says about the outcome of an add (GUI-04, D-06). [typed] is the address as the author typed it; a null
 * [result] is text that is not a private LAN IPv4 address, which was never sent anywhere (T-07-29).
 */
internal fun manualResultText(result: ManualAddResult?, typed: String): String = when (result) {
    null -> "Enter the speaker's IPv4 address on this Wi-Fi, for example 192.168.1.40"
    is ManualAddResult.Added -> "Added ${result.name}"
    is ManualAddResult.AlreadyListed -> "${result.name} is already listed"
    is ManualAddResult.Hidden -> "That speaker is hidden; restore it from Hidden"
    ManualAddResult.NoAnswer -> "No answer from $typed"
    is ManualAddResult.NotUsable -> result.reason
    ManualAddResult.Refused -> "That is not an address on this Wi-Fi"
}

/**
 * GUI-04, D-06: type a speaker's IPv4 address; the speaker is probed and gets a card only if it answers. Nothing is saved
 * from here (plan 07-13). The content sets [testTagsAsResourceId] itself because a dialog is its own window.
 */
@Composable
fun AddSpeakerDialog(model: AppModel, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("dialog-add"),
        onDismissRequest = onDismiss,
        title = { Text("Add speaker") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        message = null
                    },
                    label = { Text("IPv4 address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text(
                            "The speaker's IPv4 address on this Wi-Fi, for example 192.168.1.40. It is saved under this address; " +
                                "if the speaker gets a new address, add it again.",
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("field-ip"),
                )
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Asking the speaker", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("add-result")) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    val typed = text.trim()
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            message = manualResultText(model.addManual(typed), typed)
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.testTag("btn-add-confirm"),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("btn-add-close")) { Text("Close") }
        },
    )
}
