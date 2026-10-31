// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for Tube2U[PnP] for Android.
package dev.t2u.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.t2u.app.model.positionAt
import dev.t2u.app.model.progressFraction
import dev.t2u.app.model.progressText
import dev.t2u.app.model.statusLabel
import dev.t2u.app.model.trackLines
import dev.t2u.lounge.PlayerStatus
import dev.t2u.renderer.SpeakerAction
import dev.t2u.renderer.SpeakerUi
import kotlinx.coroutines.delay

/**
 * One speaker as a Material 3 card (GUI-01): art, name, state word, and what it plays. The card has no loudness control of
 * any kind (notes, T-02-36).
 */
@Composable
fun SpeakerCard(speaker: SpeakerUi, modifier: Modifier = Modifier, onAction: (SpeakerAction) -> Unit = {}) {
    val udn = speaker.udn
    val playing = speaker.online && speaker.status == PlayerStatus.PLAYING
    // One tick a second, only while this speaker plays and the card is composed (T-07-08). The clock is the renderer's epoch clock.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(speaker.status, speaker.position, speaker.online) {
        nowMs = System.currentTimeMillis()
        while (playing) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
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
        if (speaker.online && (speaker.status == PlayerStatus.PLAYING || speaker.status == PlayerStatus.PAUSED)) {
            val pause = speaker.status == PlayerStatus.PLAYING
            IconButton(
                onClick = { onAction(if (pause) SpeakerAction.PAUSE else SpeakerAction.PLAY) },
                modifier = Modifier.testTag("btn-playpause-$udn"),
            ) {
                Icon(
                    if (pause) PauseIcon else Icons.Filled.PlayArrow,
                    contentDescription = if (pause) "Pause" else "Play",
                )
            }
        }
        val position = speaker.position
        val showProgress = speaker.online && (speaker.status == PlayerStatus.PLAYING || speaker.status == PlayerStatus.PAUSED)
        if (speaker.online && speaker.status == PlayerStatus.LOADING) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp))
        } else if (showProgress && position != null) {
            val at = positionAt(position, nowMs, playing)
            val fraction = progressFraction(at, position.durationSec)
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    progressText(at, position.durationSec),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("progress-$udn"),
                )
            }
        }
        speaker.error?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).testTag("error-$udn"),
            )
        }
    }
}
