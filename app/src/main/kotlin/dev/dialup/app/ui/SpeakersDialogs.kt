// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.dialup.app.AppModel
import dev.dialup.app.model.hideEndsCast
import dev.t2u.renderer.SpeakerUi

/**
 * The dialogs of the Renderers tab, in the order the tab always showed them: add by address, the hide confirmation, the refresh
 * confirmation and rename. Each one clears its own flag in [ui] when it no longer applies: the hide confirmation when that card no
 * longer casts, the rename dialog when its card is gone.
 */
@Composable
internal fun SpeakersDialogs(model: AppModel, ui: SpeakersUiState) {
    if (ui.adding) AddSpeakerDialog(model, onDismiss = { ui.adding = false })
    ui.confirmingHide?.let { udn ->
        val card = ui.listing.live.firstOrNull { it.udn == udn }
        if (card == null || !hideEndsCast(card)) {
            ui.confirmingHide = null
        } else {
            HideConfirmDialog(card, onDismiss = { ui.confirmingHide = null }, onConfirm = {
                ui.confirmingHide = null
                model.hide(card)
            })
        }
    }
    if (ui.confirmingRefresh) {
        RefreshConfirmDialog(onDismiss = { ui.confirmingRefresh = false }, onConfirm = {
            ui.confirmingRefresh = false
            model.rediscoverRenderers()
        })
    }
    ui.renaming?.let { udn ->
        // the card on the list now: a running one, or the greyed card of a saved renderer while the bridge's list is not shown
        val card = (ui.listing.live + ui.listing.saved).firstOrNull { it.udn == udn }
        if (card == null) {
            ui.renaming = null
        } else {
            RenameDialog(
                current = card.name,
                custom = udn in ui.names,
                onSave = { model.rename(udn, it) },
                onReset = { model.rename(udn, null) },
                onDismiss = { ui.renaming = null },
            )
        }
    }
}

/** Asks before Hide ends the cast [card] is playing. */
@Composable
internal fun HideConfirmDialog(card: SpeakerUi, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hide renderer?") },
        text = { Text("${card.name} is casting. The current cast will disconnect.") },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("btn-hide-confirm")) { Text("Hide") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("btn-hide-cancel")) { Text("Cancel") }
        },
        modifier = Modifier.testTag("dialog-hide"),
    )
}

/** Asks before Refresh empties the list while a cast is running. */
@Composable
internal fun RefreshConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Refresh renderers?") },
        text = { Text("Current casts will disconnect.") },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("btn-refresh-confirm")) { Text("Refresh") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("btn-refresh-cancel")) { Text("Cancel") }
        },
        modifier = Modifier.testTag("dialog-refresh"),
    )
}
