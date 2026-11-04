// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dialup.app.AppModel
import dev.t2u.renderer.HiddenSpeaker

/**
 * GUI-13: the "Hidden renderers" section of the Settings tab. Lists the renderers the author hid, each with Restore (GUI-03)
 * and, for a renderer saved by address, Delete. It reads the process-level stores, so it is right while the bridge is off too.
 * It used to be a collapsed section under the Renderers list.
 */
@Composable
fun HiddenSection(model: AppModel) {
    val hidden by model.hidden.collectAsStateWithLifecycle()
    val manual by model.manual.collectAsStateWithLifecycle()
    val names by model.names.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text("Hidden renderers", modifier = Modifier.testTag("section-hidden")) },
        supportingContent = {
            if (hidden.isEmpty()) {
                Text("No renderer is hidden. Hide one from its card's menu.", modifier = Modifier.testTag("hidden-empty"))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (speaker in hidden) {
                        HiddenRow(
                            speaker,
                            shownName = names[speaker.udn] ?: speaker.name,
                            onRestore = { model.unhide(speaker.udn) },
                            // a hidden renderer saved by address can be deleted from here too (quick 261101-oib)
                            onDelete = if (manual.any { it.udn == speaker.udn }) ({ model.deleteManual(speaker.udn) }) else null,
                        )
                    }
                }
            }
        },
    )
}

/** One hidden speaker: its name and address, and a Restore button (GUI-03). */
@Composable
private fun HiddenRow(speaker: HiddenSpeaker, shownName: String, onRestore: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).testTag("hidden-row-${speaker.udn}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(shownName.ifEmpty { speaker.udn }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (speaker.address.isNotEmpty()) Text(speaker.address, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onRestore, modifier = Modifier.testTag("btn-restore-${speaker.udn}")) { Text("Restore") }
        if (onDelete != null) {
            TextButton(onClick = onDelete, modifier = Modifier.testTag("btn-delete-hidden-${speaker.udn}")) { Text("Delete") }
        }
    }
}
