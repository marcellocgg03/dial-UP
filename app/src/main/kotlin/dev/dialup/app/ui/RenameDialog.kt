// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.t2u.renderer.rendererNameProblem

/**
 * Rename renderer (quick 261101-dcg, PD-4): a text field prefilled with the [current] shown name. The name replaces the whole
 * shown name exactly as typed, trimmed; Save is on only for a usable name ([rendererNameProblem]: not blank, at most 63
 * characters, no line breaks or tabs); a blank field saves as Reset name (the default name). Reset name is offered only when the renderer has a user name ([custom]). The content sets
 * [testTagsAsResourceId] itself because a dialog is its own window.
 */
@Composable
fun RenameDialog(
    current: String,
    custom: Boolean,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    // a blank field means "use the default name": Save resets instead of reporting a problem
    val blank = text.isBlank()
    val problem = if (blank) null else rendererNameProblem(text)
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("dialog-rename"),
        onDismissRequest = onDismiss,
        title = { Text("Rename renderer") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Name") },
                singleLine = true,
                isError = problem != null,
                supportingText = {
                    if (problem != null) {
                        Text(problem, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("rename-problem"))
                    } else if (blank) {
                        Text("Leave empty to use the default name")
                    } else {
                        Text("Shown on this card and in the YouTube and YouTube Music cast menus; they may take a moment to update")
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("field-rename"),
            )
        },
        confirmButton = {
            TextButton(
                enabled = problem == null,
                onClick = {
                    if (blank) onReset() else onSave(text)
                    onDismiss()
                },
                modifier = Modifier.testTag("btn-rename-save"),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (custom) {
                    TextButton(
                        onClick = {
                            onReset()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("btn-rename-reset"),
                    ) { Text("Reset name") }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("btn-rename-cancel")) { Text("Cancel") }
            }
        },
    )
}
