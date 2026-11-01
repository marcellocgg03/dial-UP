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
import dev.dialup.app.model.buttonsFor
import dev.dialup.app.model.savedManualVisible
import dev.t2u.renderer.HiddenSpeaker
import dev.t2u.renderer.SpeakerUi
import dev.t2u.renderer.ManualSpeaker
import dev.t2u.lounge.PlayerStatus

/**
 * The Speakers list (GUI-01): one card per speaker the running bridge can cast to. With the service down it says so instead of
 * showing a list that no longer follows anything. Each item is a [SpeakerCard]. Below the cards a collapsed Hidden section lists
 * the speakers the author hid, with Restore (GUI-03); it is there while the bridge is off too. While there is no bridge (off, or the
 * service waiting for Wi-Fi) the saved manual speakers are listed as greyed cards that can still be removed (D-07).
 */
@Composable
fun SpeakersTab(model: AppModel, modifier: Modifier = Modifier) {
    val speakers by model.speakers.collectAsStateWithLifecycle()
    val running by model.serviceRunning.collectAsStateWithLifecycle()
    val bridgeUp by model.bridgeUp.collectAsStateWithLifecycle()
    val hidden by model.hidden.collectAsStateWithLifecycle()
    val manual by model.manual.collectAsStateWithLifecycle()
    var hiddenExpanded by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val presenter = remember(model, scope) { SpeakersPresenter(scope) { udn, action -> model.controlNow(udn, action) } }
    val busy by presenter.busy.collectAsStateWithLifecycle()
    val noCards = !running || speakers.isEmpty()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "add-speaker") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { adding = true }, enabled = running, modifier = Modifier.testTag("btn-add-speaker")) {
                    Text("Add speaker")
                }
                if (!running) {
                    Text(AppModel.BRIDGE_OFF_ADD, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("add-needs-bridge"))
                }
            }
        }
        if (noCards) {
            item(key = "empty-note") {
                val (text, tag) = if (!running) "The bridge is off" to "bridge-off" else "Looking for speakers on the Wi-Fi" to "speakers-empty"
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp).testTag(tag))
            }
        } else {
            items(speakers, key = { it.udn }) { speaker ->
                SpeakerCard(
                    speaker,
                    buttons = buttonsFor(speaker, speaker.udn in busy),
                    onAction = { presenter.press(speaker.udn, it) },
                    onHide = { model.hide(speaker.udn) },
                    onRemove = { model.removeManual(speaker.udn) },
                )
            }
        }
        if (savedManualVisible(running, bridgeUp)) {
            // D-07: the saved manual speakers stay listed, greyed, and can still be removed whenever there is no bridge
            items(manual, key = { "manual-${it.udn}" }) { saved ->
                val card = offlineCard(saved)
                SpeakerCard(card, buttons = buttonsFor(card, busy = false), onRemove = { model.removeManual(saved.udn) })
            }
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
                    HiddenRow(speaker, onRestore = { model.unhide(speaker.udn) })
                }
            }
        }
    }
    if (adding) AddSpeakerDialog(model, onDismiss = { adding = false })
}

/** A saved manual speaker as the greyed card it is while the bridge is off: idle, no track, every control off (D-07). */
private fun offlineCard(saved: ManualSpeaker) = SpeakerUi(
    udn = saved.udn,
    name = saved.name,
    address = saved.ip,
    status = PlayerStatus.IDLE,
    track = null,
    error = null,
    hasPrevious = false,
    hasNext = false,
    queueHasCurrent = false,
    position = null,
    online = false,
    manual = true,
)

/** One hidden speaker: its name and address, and a Restore button (GUI-03). */
@Composable
private fun HiddenRow(speaker: HiddenSpeaker, onRestore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag("hidden-row-${speaker.udn}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(speaker.name.ifEmpty { speaker.udn }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (speaker.address.isNotEmpty()) Text(speaker.address, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onRestore, modifier = Modifier.testTag("btn-restore-${speaker.udn}")) { Text("Restore") }
    }
}
