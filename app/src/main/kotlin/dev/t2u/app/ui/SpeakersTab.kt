// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.t2u.app.AppModel
import dev.t2u.renderer.SpeakerUi

/**
 * The Speakers list (GUI-01): one card per speaker the running bridge can cast to. With the service down it says so instead of
 * showing a list that no longer follows anything. Plan 07-06 replaces the card body with SpeakerCard.
 */
@Composable
fun SpeakersTab(model: AppModel, modifier: Modifier = Modifier) {
    val speakers by model.speakers.collectAsStateWithLifecycle()
    val running by model.serviceRunning.collectAsStateWithLifecycle()
    when {
        !running -> Message("The bridge is off", "bridge-off", modifier)
        speakers.isEmpty() -> Message("Looking for speakers on the Wi-Fi", "speakers-empty", modifier)
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(speakers, key = { it.udn }) { SpeakerRow(it) }
        }
    }
}

@Composable
private fun Message(text: String, tag: String, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun SpeakerRow(speaker: SpeakerUi) {
    Card(Modifier.fillMaxWidth().testTag("speaker-card-${speaker.udn}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(speaker.name, style = MaterialTheme.typography.titleMedium)
            Text(speaker.status.name, style = MaterialTheme.typography.labelMedium)
            speaker.track?.let {
                Text(it.title, style = MaterialTheme.typography.bodyLarge)
                if (it.artist.isNotEmpty()) Text(it.artist, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
