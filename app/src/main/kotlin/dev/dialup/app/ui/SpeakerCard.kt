// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dialup.app.model.CardButtons
import dev.dialup.app.model.PlayPause
import dev.dialup.app.model.buttonsFor
import dev.dialup.app.model.hideOffered
import dev.dialup.app.model.playPauseAction
import dev.dialup.app.model.positionAt
import dev.dialup.app.model.progressFraction
import dev.dialup.app.model.progressText
import dev.dialup.app.model.statusLabel
import dev.dialup.app.model.trackLines
import dev.t2u.lounge.PlayerStatus
import dev.t2u.renderer.SpeakerAction
import dev.t2u.renderer.SpeakerUi
import kotlinx.coroutines.delay

/** How faint a card is drawn while its speaker is offline (D-07). */
private const val OFFLINE_ALPHA = 0.5f

/**
 * One speaker as a Material 3 card (GUI-01): art, name, state word, and what it plays. The card has no loudness control of
 * any kind (notes, T-02-36).
 */
@Composable
fun SpeakerCard(
    speaker: SpeakerUi,
    modifier: Modifier = Modifier,
    buttons: CardButtons = buttonsFor(speaker, busy = false),
    onAction: (SpeakerAction) -> Unit = {},
    onHide: () -> Unit = {},
    onRename: () -> Unit = {},
) {
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
    // D-07: a speaker that does not answer is drawn greyed; buttonsFor has already switched every control off
    ElevatedCard(modifier.fillMaxWidth().alpha(if (speaker.online) 1f else OFFLINE_ALPHA).testTag("speaker-card-$udn")) {
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
            // GUI-03: the overflow menu: Rename on every card. Hide is the only way off the list (PD-3): offered while the speaker answers and on the greyed card of a saved one.
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("btn-menu-$udn")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // GUI rename: on every card, answering or not (the name is kept per UDN)
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                        modifier = Modifier.testTag("menu-rename-$udn"),
                    )
                    if (hideOffered(speaker)) {
                        DropdownMenuItem(
                            text = { Text("Hide from cast menus") },
                            onClick = {
                                menuOpen = false
                                onHide()
                            },
                            modifier = Modifier.testTag("menu-hide-$udn"),
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = { onAction(SpeakerAction.PREVIOUS) }, enabled = buttons.previous, modifier = Modifier.testTag("btn-prev-$udn")) {
                Icon(SkipPreviousIcon, contentDescription = "Previous")
            }
            val pause = buttons.playPause == PlayPause.PAUSE
            IconButton(
                onClick = { playPauseAction(buttons.playPause)?.let(onAction) },
                enabled = buttons.playPause != PlayPause.DISABLED,
                modifier = Modifier.testTag("btn-playpause-$udn"),
            ) {
                Icon(if (pause) PauseIcon else Icons.Filled.PlayArrow, contentDescription = if (pause) "Pause" else "Play")
            }
            IconButton(onClick = { onAction(SpeakerAction.STOP) }, enabled = buttons.stop, modifier = Modifier.testTag("btn-stop-$udn")) {
                Icon(StopIcon, contentDescription = "Stop")
            }
            IconButton(onClick = { onAction(SpeakerAction.NEXT) }, enabled = buttons.next, modifier = Modifier.testTag("btn-next-$udn")) {
                Icon(SkipNextIcon, contentDescription = "Next")
            }
            IconButton(onClick = { onAction(SpeakerAction.CLEAR) }, enabled = buttons.clear, modifier = Modifier.testTag("btn-clear-$udn")) {
                Icon(ClearAllIcon, contentDescription = "Clear renderer")
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
