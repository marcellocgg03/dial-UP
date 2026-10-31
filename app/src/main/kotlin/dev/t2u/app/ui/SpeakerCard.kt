// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.t2u.app.model.statusLabel
import dev.t2u.app.model.trackLines
import dev.t2u.renderer.SpeakerUi

/**
 * One speaker as a Material 3 card (GUI-01): art, name, state word, and what it plays. The card has no loudness control of
 * any kind (notes, T-02-36).
 */
@Composable
fun SpeakerCard(speaker: SpeakerUi, modifier: Modifier = Modifier) {
    val udn = speaker.udn
    ElevatedCard(modifier.fillMaxWidth().testTag("speaker-card-$udn")) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TrackArt(speaker.track?.thumbnail, Modifier.size(64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(speaker.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    statusLabel(speaker.status, speaker.online),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("status-$udn"),
                )
                speaker.track?.let { track ->
                    val (title, artist) = trackLines(track)
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (artist != null) Text(artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
