// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.t2u.app.AppModel
import dev.t2u.app.model.SpeakersPresenter
import dev.t2u.app.model.buttonsFor

/**
 * The Speakers list (GUI-01): one card per speaker the running bridge can cast to. With the service down it says so instead of
 * showing a list that no longer follows anything. Each item is a [SpeakerCard].
 */
@Composable
fun SpeakersTab(model: AppModel, modifier: Modifier = Modifier) {
    val speakers by model.speakers.collectAsStateWithLifecycle()
    val running by model.serviceRunning.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val presenter = remember(model, scope) { SpeakersPresenter(scope) { udn, action -> model.controlNow(udn, action) } }
    val busy by presenter.busy.collectAsStateWithLifecycle()
    when {
        !running -> Message("The bridge is off", "bridge-off", modifier)
        speakers.isEmpty() -> Message("Looking for speakers on the Wi-Fi", "speakers-empty", modifier)
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(speakers, key = { it.udn }) { speaker ->
                SpeakerCard(
                    speaker,
                    buttons = buttonsFor(speaker, speaker.udn in busy),
                    onAction = { presenter.press(speaker.udn, it) },
                )
            }
        }
    }
}

@Composable
private fun Message(text: String, tag: String, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(tag))
    }
}
