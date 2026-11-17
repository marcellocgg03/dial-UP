// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * The one question a new install asks at first launch: the yt-dlp inside the app is old and YouTube refuses its streams, so
 * casting works only after it is updated, which downloads code. Nothing is downloaded without this answer. Not dismissible by
 * tapping outside, so the answer is a choice, not an accident.
 */
@Composable
fun YtDlpConsentDialog(onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("dialog-ytdlp-consent"),
        onDismissRequest = {},
        title = { Text("Update yt-dlp?") },
        text = {
            Text("This is needed to be able to cast. If you decline now, you can update in Settings later")
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }, modifier = Modifier.testTag("btn-ytdlp-consent-yes")) { Text("Update") }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }, modifier = Modifier.testTag("btn-ytdlp-consent-no")) { Text("Not now") }
        },
    )
}
