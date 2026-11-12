package dev.t2u.renderer

// PLAY-07: Pause and Play pressed in the Sonos app are picked up by polling and mirrored to the senders, and
// the app never answers with a command of its own (plan 03-09). GPL-3.0-only.
//
// Every test is a block-bodied function: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.Video
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"

internal class ExternalControlTest {
    private fun <T> withFake(fake: FakeRenderer = FakeRenderer(), block: suspend (FakeRenderer) -> T): T {
        fake.start()
        try {
            return runBlocking { block(fake) }
        } finally {
            fake.close()
        }
    }

    private fun cfg(startTimeoutMs: Long = 5000): (File) -> Config =
        { Config(dataDir = it, outputFormat = "adts", pollIntervalMs = 50, startTimeoutMs = startTimeoutMs) }

    private fun FakeRenderer.count(action: String): Int = calls.count { it.action == action }

    /** No volume command is ever sent by a poll. */
    private fun FakeRenderer.assertNoVolumeTraffic() {
        assertEquals(0, count("SetVolume"))
        assertEquals(0, count("SetMute"))
    }

    @Test
    @DisplayName("Pause pressed in the Sonos app is mirrored to the sender without a command back")
    fun externalPauseIsMirrored() {
        withFake { fake ->
            RendererHarness(fake, config = cfg()).use { h ->
                val r = h.renderer
                r.init()
                assertTrue(r.play(Video(A), 0.0))
                val seen = CopyOnWriteArrayList<Pair<PlayerStatus, Double>>()
                r.addStateListener { seen.add(it.current.status to it.current.position) }
                val plays = fake.count("Play")

                fake.state.transport = "PAUSED_PLAYBACK"
                waitUntil { seen.isNotEmpty() } // the status is set just before the senders are told

                assertEquals(PlayerStatus.PAUSED, r.status)
                assertEquals(PlayerStatus.PAUSED to 65.0, seen.last(), "the sender sees PAUSED at the speaker's position")
                assertEquals(0, fake.count("Pause"), "the app did not send Pause")
                assertEquals(plays, fake.count("Play"))
                fake.assertNoVolumeTraffic()
            }
        }
    }

    @Test
    @DisplayName("Play pressed in the Sonos app is mirrored too, three pause and play cycles in a row")
    fun externalPlayIsMirroredRepeatedly() {
        withFake { fake ->
            RendererHarness(fake, config = cfg()).use { h ->
                val r = h.renderer
                r.init()
                assertTrue(r.play(Video(A), 0.0))
                val seen = CopyOnWriteArrayList<PlayerStatus>()
                r.addStateListener { seen.add(it.current.status) }
                val plays = fake.count("Play")

                repeat(3) { cycle ->
                    fake.state.transport = "PAUSED_PLAYBACK"
                    waitUntil { seen.size == cycle * 2 + 1 }
                    assertEquals(PlayerStatus.PAUSED, r.status)
                    fake.state.transport = "PLAYING"
                    waitUntil { seen.size == cycle * 2 + 2 }
                    assertEquals(PlayerStatus.PLAYING, r.status)
                }

                assertEquals(
                    listOf(PlayerStatus.PAUSED, PlayerStatus.PLAYING, PlayerStatus.PAUSED, PlayerStatus.PLAYING, PlayerStatus.PAUSED, PlayerStatus.PLAYING),
                    seen.toList(),
                )
                assertEquals(0, fake.count("Pause"))
                assertEquals(plays, fake.count("Play"))
                fake.assertNoVolumeTraffic()
            }
        }
    }

    @Test
    @DisplayName("Play pressed in the Sonos app after the sender paused is mirrored as playing")
    fun externalPlayAfterASenderPause() {
        withFake { fake ->
            RendererHarness(fake, config = cfg()).use { h ->
                val r = h.renderer
                r.init()
                assertTrue(r.play(Video(A), 0.0))
                assertTrue(r.pause())
                assertEquals(PlayerStatus.PAUSED, r.status)
                val plays = fake.count("Play")

                fake.state.transport = "PLAYING"
                waitUntil { r.status == PlayerStatus.PLAYING }

                assertEquals(PlayerStatus.PLAYING, r.status)
                assertEquals(plays, fake.count("Play"), "the app did not send Play")
                assertEquals(1, fake.count("Pause"), "only the sender's own Pause")
                fake.assertNoVolumeTraffic()
            }
        }
    }

    @Test
    @DisplayName("Play pressed in the Sonos app for a stream loaded while paused counts as its first Play")
    fun externalPlayForAStreamLoadedWhilePaused() {
        withFake { fake ->
            RendererHarness(fake, config = cfg()).use { h ->
                val r = h.renderer
                r.init()
                assertTrue(r.play(Video(A), 0.0))
                assertTrue(r.pause())
                assertTrue(r.seek(50.0)) // restart-seek while paused: loaded, Play deferred
                assertEquals(1, fake.count("Play"))

                fake.state.transport = "PLAYING" // Play pressed in the Sonos app
                waitUntil { r.status == PlayerStatus.PLAYING }
                assertEquals(PlayerStatus.PLAYING, r.status)
                delay(200)
                assertEquals(1, fake.count("Play"), "the app sent no Play of its own")

                // The sender's later Play must not be a duplicate first Play of the same stream.
                assertEquals(false, r.resume(), "already playing")
                assertEquals(1, fake.count("Play"))
                assertTrue(r.pause())
                assertTrue(r.resume())
                assertEquals(2, fake.count("Play"), "an ordinary resume")
                assertTrue(h.notes("Play sent for the stream loaded while paused").isEmpty(), "never treated as a first Play")
                assertEquals(2, fake.count("SetAVTransportURI"))
                fake.assertNoVolumeTraffic()
            }
        }
    }

    @Test
    @DisplayName("a pause pressed in the Sonos app during the start window is not a start failure")
    fun externalPauseDuringTheStartWindowIsNotAFailure() {
        withFake { fake ->
            RendererHarness(fake, config = cfg(startTimeoutMs = 300)).use { h ->
                val r = h.renderer
                r.init()
                assertTrue(r.play(Video(A), 0.0))
                delay(150) // the speaker was seen PLAYING

                fake.state.transport = "PAUSED_PLAYBACK"
                waitUntil { r.status == PlayerStatus.PAUSED }
                delay(900) // three times the start timeout

                assertEquals(PlayerStatus.PAUSED, r.status)
                assertNull(r.lastError)
                assertEquals(1, fake.count("SetAVTransportURI"), "no reload")
                assertEquals(1, fake.count("Play"))
                fake.assertNoVolumeTraffic()
            }
        }
    }

    @Test
    @DisplayName("a speaker that keeps reporting PAUSED_PLAYBACK produces one PAUSED event")
    fun aSteadyPauseIsReportedOnce() {
        withFake { fake ->
            RendererHarness(fake, config = cfg()).use { h ->
                val r = h.renderer
                r.init()
                assertTrue(r.play(Video(A), 0.0))
                val seen = CopyOnWriteArrayList<PlayerStatus>()
                r.addStateListener { seen.add(it.current.status) }

                fake.state.transport = "PAUSED_PLAYBACK"
                waitUntil { r.status == PlayerStatus.PAUSED }
                delay(400) // eight polls with the same answer

                assertEquals(1, seen.count { it == PlayerStatus.PAUSED })
                assertEquals(1, seen.size, "nothing else was emitted")
                assertEquals(0, fake.count("Pause"))
            }
        }
    }

    @Test
    @DisplayName("a poll that read the speaker before the sender's own Pause does not flip the state back")
    fun aPollInFlightDuringOurOwnPauseDoesNotFlipTheState() {
        withFake { fake ->
            RendererHarness(fake, config = cfg()).use { h ->
                val r = h.renderer
                r.init()
                assertTrue(r.play(Video(A), 0.0))
                delay(150) // the speaker was seen PLAYING
                val seen = CopyOnWriteArrayList<PlayerStatus>()
                r.addStateListener { seen.add(it.current.status) }

                // Hold the next position read: the poll that issues it has already read PLAYING.
                val armed = java.util.concurrent.atomic.AtomicBoolean(true)
                fake.onAction = { action ->
                    if (action == "GetPositionInfo" && armed.compareAndSet(true, false)) Thread.sleep(400)
                }
                waitUntil { !armed.get() }
                assertTrue(r.pause())
                delay(900) // the held poll finishes and several more run

                assertEquals(PlayerStatus.PAUSED, r.status)
                assertEquals(listOf(PlayerStatus.PAUSED), seen.toList(), "the senders were told about the Pause once")
                assertEquals(1, fake.count("Pause"))
                assertEquals(1, fake.count("Play"))
            }
        }
    }
}
