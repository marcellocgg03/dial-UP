// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dialup.app.AppModel
import dev.dialup.app.BatteryGate
import dev.dialup.app.model.EmptyPhase
import dev.dialup.app.model.SEARCH_IDLE_GRACE_MS
import dev.dialup.app.model.SpeakersPresenter
import dev.dialup.app.model.emptyPhase
import dev.dialup.app.model.buttonsFor
import dev.dialup.app.model.ListNote
import dev.dialup.app.model.rendererListing
import dev.dialup.app.model.waitingForWifi
import kotlinx.coroutines.delay

/**
 * The Renderers list (GUI-01): one card per renderer the running bridge can cast to. It follows the Bridge enable switch
 * ([rendererListing]): with the switch off only the renderers saved by address are listed, greyed, and the list says the bridge
 * is off. Each item is a [SpeakerCard]. The hidden renderers are managed in the Settings
 * tab (GUI-13, [HiddenRenderersScreen]), not here. Rename on a card opens a dialog
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
    var adding by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val presenter = remember(model, scope) { SpeakersPresenter(scope) { udn, action -> model.controlNow(udn, action) } }
    val busy by presenter.busy.collectAsStateWithLifecycle()
    val settings by model.settings.data.collectAsStateWithLifecycle()
    val wifiPresent by model.wifiPresent.collectAsStateWithLifecycle()
    // the exemption dialog returns without a reliable result, so read it again whenever the app comes back (as Header does)
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(BatteryGate.isOpen(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = BatteryGate.isOpen(context) }
    val waiting = waitingForWifi(settings.bridgeEnabled, exempt, wifiPresent)
    val listing = rendererListing(
        // a greyed-out switch cannot run the bridge, so without the exemption the list says the bridge is off
        switchOn = settings.bridgeEnabled && exempt,
        serviceUp = running,
        bridgeUp = bridgeUp,
        waitingForWifi = waiting,
        live = speakers,
        saved = manual,
        hidden = hidden.mapTo(HashSet()) { it.udn },
        names = names,
    )
    // GUI-14: the spinner shows while the bridge is inside a multicast search window (Bridge.searching) and, at the start, until
    // the search is first seen; once it has been idle for SEARCH_IDLE_GRACE_MS with nothing found, the Refresh button shows instead
    val looking = listing.note == ListNote.LOOKING
    val searching by model.searching.collectAsStateWithLifecycle()
    var idleSettled by remember { mutableStateOf(false) }
    LaunchedEffect(looking, searching) {
        idleSettled = false
        if (looking && !searching) {
            delay(SEARCH_IDLE_GRACE_MS)
            idleSettled = true
        }
    }
    val phase = emptyPhase(searching, idleSettled)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "add-speaker") {
            OutlinedButton(onClick = { adding = true }, enabled = bridgeUp, modifier = Modifier.testTag("btn-add-speaker")) {
                Text("Add renderer by address")
            }
        }
        listing.note?.let { note ->
            item(key = "empty-note") {
                when (note) {
                    ListNote.LOOKING ->
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (phase == EmptyPhase.SPINNER) {
                                CircularProgressIndicator(Modifier.testTag("speakers-spinner"))
                            } else {
                                OutlinedButton(
                                    onClick = { model.refreshRenderers("ui") },
                                    modifier = Modifier.testTag("btn-refresh"),
                                ) { Text("Refresh") }
                            }
                        }
                }
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
                showAudioFormat = settings.showAudioFormat,
            )
        }
        // D-07: the saved manual speakers stay listed, greyed, and can be hidden whenever the bridge's list is not shown (a hidden one is skipped)
        items(listing.saved, key = { "manual-${it.udn}" }) { card ->
            SpeakerCard(card, buttons = buttonsFor(card, busy = false), onHide = { model.hide(card) }, onRename = { renaming = card.udn }, onDelete = { model.deleteManual(card.udn) })
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
