// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dialup.app.AppModel
import dev.dialup.app.model.SpeakersPresenter
import dev.dialup.app.model.addRefusal
import dev.dialup.app.model.buttonsFor
import dev.dialup.app.model.ListNote
import dev.dialup.app.model.rendererListing
import dev.t2u.renderer.HiddenSpeaker

/**
 * The Renderers list (GUI-01): one card per renderer the running bridge can cast to. It follows the Bridge enable switch
 * ([rendererListing]): with the switch off only the renderers saved by address are listed, greyed, and the list says the bridge
 * is off. Each item is a [SpeakerCard]. Below the cards a collapsed Hidden section lists
 * the speakers the author hid, with Restore (GUI-03); it is there while the bridge is off too. Rename on a card opens a dialog
 * for that renderer's name (matched by UDN). While the running bridge's list is not shown (switch off, or the
 * service waiting for Wi-Fi) the saved manual speakers are listed as greyed cards that can be hidden (D-07, PD-3).
 */
@Composable
fun SpeakersTab(model: AppModel, modifier: Modifier = Modifier) {
    val speakers by model.speakers.collectAsStateWithLifecycle()
    val running by model.serviceRunning.collectAsStateWithLifecycle()
    val bridgeUp by model.bridgeUp.collectAsStateWithLifecycle()
    val hidden by model.hidden.collectAsStateWithLifecycle()
    val manual by model.manual.collectAsStateWithLifecycle()
    val names by model.names.collectAsStateWithLifecycle()
    // the UDN being renamed; the dialog goes when no card with that UDN is on the list any more
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var hiddenExpanded by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val presenter = remember(model, scope) { SpeakersPresenter(scope) { udn, action -> model.controlNow(udn, action) } }
    val busy by presenter.busy.collectAsStateWithLifecycle()
    val settings by model.settings.data.collectAsStateWithLifecycle()
    val listing = rendererListing(
        switchOn = settings.bridgeEnabled,
        serviceUp = running,
        bridgeUp = bridgeUp,
        live = speakers,
        saved = manual,
        hidden = hidden.mapTo(HashSet()) { it.udn },
        names = names,
    )
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "add-speaker") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { adding = true }, enabled = bridgeUp, modifier = Modifier.testTag("btn-add-speaker")) {
                    Text("Add renderer")
                }
                addRefusal(running, bridgeUp)?.let { why ->
                    Text(why, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("add-needs-bridge"))
                }
            }
        }
        listing.note?.let { note ->
            item(key = "empty-note") {
                val (text, tag) = when (note) {
                    ListNote.BRIDGE_OFF -> "The bridge is off" to "bridge-off"
                    ListNote.LOOKING -> "Looking for renderers on the Wi-Fi" to "speakers-empty"
                }
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp).testTag(tag))
            }
        }
        items(listing.live, key = { it.udn }) { speaker ->
            SpeakerCard(
                speaker,
                buttons = buttonsFor(speaker, speaker.udn in busy),
                onAction = { presenter.press(speaker.udn, it) },
                onHide = { model.hide(speaker) },
                onRename = { renaming = speaker.udn },
                onDelete = { model.deleteManual(speaker.udn) },
            )
        }
        // D-07: the saved manual speakers stay listed, greyed, and can be hidden whenever the bridge's list is not shown (a hidden one is skipped)
        items(listing.saved, key = { "manual-${it.udn}" }) { card ->
            SpeakerCard(card, buttons = buttonsFor(card, busy = false), onHide = { model.hide(card) }, onRename = { renaming = card.udn }, onDelete = { model.deleteManual(card.udn) })
        }
        if (hidden.isNotEmpty()) {
            item(key = "hidden-header") {
                Text(
                    (if (hiddenExpanded) "Hidden (${hidden.size}) ▴" else "Hidden (${hidden.size}) ▾"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { hiddenExpanded = !hiddenExpanded }
                        .padding(vertical = 12.dp)
                        .testTag("section-hidden"),
                )
            }
            if (hiddenExpanded) {
                items(hidden, key = { "hidden-${it.udn}" }) { speaker ->
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
    }
    if (adding) AddSpeakerDialog(model, onDismiss = { adding = false })
    renaming?.let { udn ->
        // the card on the list now: a running one, or the greyed card of a saved renderer while the bridge's list is not shown
        val card = (listing.live + listing.saved).firstOrNull { it.udn == udn }
        if (card == null) {
            renaming = null
        } else {
            RenameDialog(
                current = card.name,
                custom = udn in names,
                onSave = { model.rename(udn, it) },
                onReset = { model.rename(udn, null) },
                onDismiss = { renaming = null },
            )
        }
    }
}

/** One hidden speaker: its name and address, and a Restore button (GUI-03). */
@Composable
private fun HiddenRow(speaker: HiddenSpeaker, shownName: String, onRestore: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().testTag("hidden-row-${speaker.udn}"),
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
