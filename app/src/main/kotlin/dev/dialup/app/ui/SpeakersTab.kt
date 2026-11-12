// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.dialup.app.AppModel
import dev.dialup.app.model.EmptyPhase
import dev.dialup.app.model.ListNote
import dev.dialup.app.model.buttonsFor
import dev.dialup.app.model.hideEndsCast

/**
 * The Renderers list: one card per renderer the running bridge can cast to. It follows the Bridge enable switch
 * ([rendererListing]): with the switch off only the renderers saved by address are listed, greyed, and the list says the bridge
 * is off. Each item is a [SpeakerCard]. The hidden renderers are managed in the Settings
 * tab ([HiddenRenderersScreen]), not here. Rename on a card opens a dialog
 * for that renderer's name (matched by UDN). While the running bridge's list is not shown (switch off, or the
 * service waiting for Wi-Fi) the saved manual speakers are listed as greyed cards that can be hidden.
 */
@Composable
fun SpeakersTab(model: AppModel, modifier: Modifier = Modifier, onRenamed: () -> Unit = {}, onRestart: () -> Unit = {}) {
    val ui = rememberSpeakersUiState(model)
    val listing = ui.listing
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "add-speaker") { SpeakersActionRow(model, ui) }
        listing.note?.let { note -> item(key = "empty-note") { EmptyNote(note, ui.phase) } }
        items(listing.live, key = { it.udn }) { speaker ->
            SpeakerCard(
                speaker,
                buttons = buttonsFor(speaker, speaker.udn in ui.busy),
                onAction = { ui.presenter.press(speaker.udn, it) },
                onHide = { if (hideEndsCast(speaker)) ui.confirmingHide = speaker.udn else model.hide(speaker) },
                onRename = { ui.renaming = speaker.udn },
                onDelete = { model.deleteManual(speaker.udn) },
                pinned = speaker.udn in ui.pinned,
                onTogglePin = { model.setPinned(speaker, speaker.udn !in ui.pinned) },
                showAudioFormat = ui.showAudioFormat,
            )
        }
        // the saved manual speakers stay listed, greyed, and can be hidden whenever the bridge's list is not shown
        // (a hidden one is skipped)
        items(listing.saved, key = { "manual-${it.udn}" }) { card ->
            SpeakerCard(
                card,
                buttons = buttonsFor(card, busy = false),
                onHide = { model.hide(card) },
                onRename = { ui.renaming = card.udn },
                onDelete = { model.deleteManual(card.udn) },
                pinned = card.udn in ui.pinned,
                onTogglePin = { model.setPinned(card, card.udn !in ui.pinned) },
            )
        }
    }
    SpeakersDialogs(model, ui, onRenamed, onRestart)
}

/** The add and refresh buttons above the list. */
@Composable
private fun SpeakersActionRow(model: AppModel, ui: SpeakersUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { ui.adding = true }, enabled = ui.bridgeUp, modifier = Modifier.testTag("btn-add-speaker")) {
            Text("Add renderer by address")
        }
        // the list is emptied and rebuilt from a fresh search, as at the first discovery (the spinner shows). Every
        // listed speaker is stopped, so while casting the button asks first. Shown exactly when the spinner is not: the spinner covers
        // the search window and the settle time after it, so no search starts on top of another.
        if (!ui.spinnerShown) {
            OutlinedButton(
                onClick = { if (ui.castingNow > 0) ui.confirmingRefresh = true else model.rediscoverRenderers() },
                enabled = ui.bridgeUp,
                modifier = Modifier.testTag("btn-refresh-list"),
            ) { Text("Refresh") }
        }
    }
}

/** The note shown in place of an empty list: the spinner while looking. The Refresh button is the one beside Add. */
@Composable
private fun EmptyNote(note: ListNote, phase: EmptyPhase) {
    when (note) {
        ListNote.LOOKING ->
            if (phase == EmptyPhase.SPINNER) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(Modifier.testTag("speakers-spinner"))
                }
            }
    }
}
