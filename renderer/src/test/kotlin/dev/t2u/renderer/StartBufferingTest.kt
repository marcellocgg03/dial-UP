package dev.t2u.renderer

// The sender is told "buffering" while a track just sent to the speaker has not started playing, GPL-3.0-only.
//
// The first cast of a session can take half a minute to produce a stream (a refused URL, then a fresh extraction): the
// speaker is silent, and the sender's bar used to run on as if it played, then jump back when the drift check caught up.
// The start uses the same hold as a Seek. Every test is a block-bodied function: JUnit silently skips a test whose
// expression body returns a value.

import dev.t2u.lounge.OutgoingMessage
import dev.t2u.lounge.PlayerStateEvent
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.Video
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"

internal class StartBufferingTest {
    private fun config(startTimeoutMs: Long = 60_000): (File) -> Config =
        { Config(dataDir = it, pollIntervalMs = 50, startTimeoutMs = startTimeoutMs, outputFormat = "adts", prefetch = false) }

    private fun code(e: PlayerStateEvent): Int = OutgoingMessage.onStateChange(null, e.current).payload["state"] as Int

    @Test
    @DisplayName("a track the speaker has not started is shown as buffering, and as playing at the speaker's position once it plays")
    fun aSilentSpeakerIsShownAsBufferingUntilItPlays() {
        val fake = FakeRenderer(neverPlay = true)
        fake.start()
        try {
            RendererHarness(fake, config = config()).use { h ->
                runBlocking {
                    val events = CopyOnWriteArrayList<PlayerStateEvent>()
                    h.renderer.addStateListener { events.add(it) }
                    h.renderer.init()
                    assertTrue(h.renderer.play(Video(A), 12.0))
                    waitUntil { events.isNotEmpty() }
                    // the speaker is silent: every state the sender got so far says buffering, at the position it asked for
                    Thread.sleep(600)
                    assertTrue(events.all { code(it) == 3 }, "states: ${events.map { code(it) }}")
                    assertEquals(12.0, events.last().current.position)
                    // the app's own card says the same: loading, not playing
                    assertEquals(PlayerStatus.LOADING, h.renderer.ui.value.status)
                    // the speaker starts: the sender is told playing
                    fake.state.transport = "PLAYING"
                    waitUntil { events.any { code(it) == 1 } }
                    assertEquals(PlayerStatus.PLAYING, h.renderer.status)
                    waitUntil { h.renderer.ui.value.status == PlayerStatus.PLAYING }
                }
            }
        } finally {
            fake.close()
        }
    }

    @Test
    @DisplayName("a pause pressed while the track is still loading drops the buffering hold")
    fun aPauseDuringTheStartDropsTheHold() {
        val fake = FakeRenderer(neverPlay = true)
        fake.start()
        try {
            RendererHarness(fake, config = config()).use { h ->
                runBlocking {
                    val events = CopyOnWriteArrayList<PlayerStateEvent>()
                    h.renderer.addStateListener { events.add(it) }
                    h.renderer.init()
                    assertTrue(h.renderer.play(Video(A), 0.0))
                    waitUntil { events.isNotEmpty() }
                    assertTrue(h.renderer.pause())
                    waitUntil { events.any { it.current.status == PlayerStatus.PAUSED } }
                    assertEquals(PlayerStatus.PAUSED, h.renderer.status)
                }
            }
        } finally {
            fake.close()
        }
    }
}
