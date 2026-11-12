// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dialup.app.AppModel
import dev.dialup.app.BatteryGate
import dev.dialup.app.model.EmptyPhase
import dev.dialup.app.model.ListNote
import dev.dialup.app.model.RendererListing
import dev.dialup.app.model.SEARCH_IDLE_GRACE_MS
import dev.dialup.app.model.SpeakersPresenter
import dev.dialup.app.model.emptyPhase
import dev.dialup.app.model.rendererListing
import dev.dialup.app.model.waitingForWifi
import kotlinx.coroutines.delay

/**
 * What the Renderers tab shows and the four dialog flags it keeps: [listing] (the one rule in [rendererListing]), the empty-list
 * [phase], the [busy] UDNs and the flags for the add dialog, the rename dialog ([renaming] is a UDN), the hide confirmation
 * ([confirmingHide] is the UDN of a playing speaker) and the refresh confirmation. Built by [rememberSpeakersUiState].
 */
internal class SpeakersUiState(
    val listing: RendererListing,
    val phase: EmptyPhase,
    val bridgeUp: Boolean,
    val searching: Boolean,
    val castingNow: Int,
    val busy: Set<String>,
    val names: Map<String, String>,
    val showAudioFormat: Boolean,
    val presenter: SpeakersPresenter,
    renamingState: MutableState<String?>,
    addingState: MutableState<Boolean>,
    confirmingHideState: MutableState<String?>,
    confirmingRefreshState: MutableState<Boolean>,
    restartAfterRenameState: MutableState<Boolean>,
    renamedNoticeState: MutableState<Boolean>,
) {
    /** True while the empty list shows its spinner; the Refresh button beside Add is shown exactly when this is false. */
    val spinnerShown: Boolean get() = listing.note == ListNote.LOOKING && phase == EmptyPhase.SPINNER

    var renaming: String? by renamingState
    var adding: Boolean by addingState
    var confirmingHide: String? by confirmingHideState
    var confirmingRefresh: Boolean by confirmingRefreshState

    /** The dialog that offers the bridge restart a rename needs (the speaker keeps its old name until then). */
    var restartAfterRename: Boolean by restartAfterRenameState

    /** The dialog that says to clear the YouTube apps from recents after a rename the bridge restarted for by itself. */
    var renamedNotice: Boolean by renamedNoticeState
}

/** Collects the flows the Renderers tab reads, keeps its dialog flags and computes the listing and the empty-list phase. */
@Composable
internal fun rememberSpeakersUiState(model: AppModel): SpeakersUiState {
    val speakers by model.speakers.collectAsStateWithLifecycle()
    val running by model.serviceRunning.collectAsStateWithLifecycle()
    val bridgeUp by model.bridgeUp.collectAsStateWithLifecycle()
    val hidden by model.hidden.collectAsStateWithLifecycle()
    val manual by model.manual.collectAsStateWithLifecycle()
    val names by model.names.collectAsStateWithLifecycle()
    // the UDN being renamed; the dialog goes when no card with that UDN is on the list any more
    val renaming = rememberSaveable { mutableStateOf<String?>(null) }
    val adding = rememberSaveable { mutableStateOf(false) }
    // the UDN of a playing speaker whose Hide is waiting for a yes; the dialog goes when that card is no longer playing
    val confirmingHide = rememberSaveable { mutableStateOf<String?>(null) }
    val castingNow by model.casting.collectAsStateWithLifecycle()
    val confirmingRefresh = rememberSaveable { mutableStateOf(false) }
    val restartAfterRename = rememberSaveable { mutableStateOf(false) }
    val renamedNotice = rememberSaveable { mutableStateOf(false) }
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
    // the spinner shows while the bridge is inside a multicast search window (Bridge.searching) and, at the start, until
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
    return SpeakersUiState(
        listing = listing,
        phase = emptyPhase(searching, idleSettled),
        bridgeUp = bridgeUp,
        searching = searching,
        castingNow = castingNow,
        busy = busy,
        names = names,
        showAudioFormat = settings.showAudioFormat,
        presenter = presenter,
        renamingState = renaming,
        addingState = adding,
        confirmingHideState = confirmingHide,
        confirmingRefreshState = confirmingRefresh,
        restartAfterRenameState = restartAfterRename,
        renamedNoticeState = renamedNotice,
    )
}
