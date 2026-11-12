// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dialup.app.AppModel
import dev.dialup.app.ThemeMode
import dev.t2u.renderer.HiddenSpeaker

/**
 * The hidden renderers, on their own screen (opened from the Hidden renderers row in Settings). One card per renderer the
 * author hid, laid out like the cards on the Renderers tab, with Restore and, for a renderer saved by address, Delete. It
 * reads the process-level stores, so it is right while the bridge is off too.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HiddenRenderersScreen(model: AppModel, theme: ThemeMode, onBack: () -> Unit) {
    val hidden by model.hidden.collectAsStateWithLifecycle()
    val manual by model.manual.collectAsStateWithLifecycle()
    val names by model.names.collectAsStateWithLifecycle()
    DialUpTheme(theme) {
        Scaffold(
            modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
            topBar = {
                TopAppBar(
                    title = { Text("Hidden renderers", modifier = Modifier.testTag("section-hidden")) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("btn-hidden-back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            if (hidden.isEmpty()) {
                Text(
                    "No renderer is hidden. Hide one from its card's menu.",
                    modifier = Modifier.padding(padding).padding(16.dp).testTag("hidden-empty"),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(hidden, key = { it.udn }) { speaker ->
                        HiddenCard(
                            speaker,
                            shownName = names[speaker.udn] ?: speaker.name,
                            onRestore = { model.unhide(speaker.udn) },
                            // a hidden renderer saved by address can be deleted from here too
                            onDelete = if (manual.any { it.udn == speaker.udn }) ({ model.deleteManual(speaker.udn) }) else null,
                        )
                    }
                }
            }
        }
    }
}

/** One hidden renderer as a card: art square, name and address, Restore and (for a saved-by-address one) Delete. */
@Composable
private fun HiddenCard(speaker: HiddenSpeaker, shownName: String, onRestore: () -> Unit, onDelete: (() -> Unit)?) {
    ElevatedCard(Modifier.fillMaxWidth().testTag("hidden-row-${speaker.udn}")) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackArt(null, Modifier.size(64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    shownName.ifEmpty { speaker.udn },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (speaker.address.isNotEmpty()) Text(speaker.address, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onRestore, modifier = Modifier.testTag("btn-restore-${speaker.udn}")) { Text("Restore") }
            if (onDelete != null) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.testTag("btn-delete-hidden-${speaker.udn}")) { Text("Delete") }
            }
        }
    }
}
