// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import dev.dialup.app.AppModel
import dev.t2u.lounge.PairingCode
import kotlinx.coroutines.delay

/** How long a TV code is shown before the dialog asks for a new one (the original receiver refreshes every five minutes). */
internal const val PAIRING_REFRESH_MS = 300_000L

/** A TV code the way YouTube prints it: groups of three digits. Anything that is not all digits is shown as it came. */
internal fun formatPairingCode(code: String): String =
    if (code.length > 3 && code.all { it in '0'..'9' }) code.chunked(3).joinToString(" ") else code

/**
 * Shows the TV codes of speaker [speaker] for the YouTube apps' Link with TV code screen, one for each app, and asks for new ones
 * every five minutes while it is open or when Refresh is pressed. The codes go to nobody but this screen: they are neither logged
 * nor saved. The content sets [testTagsAsResourceId] itself because a dialog is its own window.
 */
@Composable
internal fun PairDialog(model: AppModel, udn: String, speaker: String, onDismiss: () -> Unit) {
    var codes by remember { mutableStateOf<List<PairingCode>?>(null) }
    // bumped by Refresh; each change runs a new request
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        codes = null
        codes = model.pairingCodes(udn)
        // a new code before the shown one runs out, until the dialog closes (this effect is cancelled with it)
        delay(PAIRING_REFRESH_MS)
        round++
    }
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("dialog-pair"),
        onDismissRequest = onDismiss,
        title = { Text("Pair with TV code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "In YouTube or YouTube Music open Settings, then Connected TVs or Link with TV code. Type the code listed under " +
                        "that app's name: each code works only in its own app. It adds $speaker to the cast list.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val shown = codes
                when {
                    shown == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Getting the codes", style = MaterialTheme.typography.bodyMedium)
                    }
                    shown.isEmpty() -> Text(
                        "No code yet. $speaker must be running and the phone online.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("pair-none"),
                    )
                    else -> shown.forEach { c ->
                        Column {
                            Text(c.app, style = MaterialTheme.typography.labelMedium)
                            SelectionContainer {
                                Text(
                                    formatPairingCode(c.code),
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.testTag("pair-code-${c.app}"),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { round++ }, enabled = codes != null, modifier = Modifier.testTag("btn-pair-refresh")) { Text("New code") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("btn-pair-close")) { Text("Close") }
        },
    )
}
