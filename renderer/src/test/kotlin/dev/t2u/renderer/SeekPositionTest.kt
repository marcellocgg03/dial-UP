package dev.t2u.renderer

// The seekbar after a native Seek on a speaker that takes seconds to settle (plan 03-15, PLAY-06), GPL-3.0-only.
//
// Scenario copied from the device log (Lamp, format direct, session C, 2026-10-21 20:59): the phone sends seekTo 125,
// the Lamp accepts the Seek and spends about 5 s TRANSITIONING with RelTime 0:00:01 before it plays on at 2:05. The
// player used to read the speaker right after the Seek and tell the phone "currentTime 1.0". The rig is the plain
// RendererHarness with a SonosMimic in slow-seek mode (no HTTP side) and a clock the test moves by hand.
//
// No Node counterpart: renderer.test.js has no test for a speaker that reports a transient position after a Seek
// (PARITY.md adaptation 23). Every test is a block-bodied function: JUnit silently skips a test whose expression body
// returns a value.

import dev.t2u.lounge.Client
import dev.t2u.lounge.Playlist
import dev.t2u.lounge.PlayerStateEvent
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.PlaylistRequestHandler
import dev.t2u.lounge.PreviousNextVideos
import dev.t2u.lounge.Video
import dev.t2u.lounge.VideoContext
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"
private const val B = "BBBBBBBBBBB"

/** The length of the track in every test, and the seek target of the device log (2:05 of 3:23, scaled). */
private const val TRACK_S = 140L
private const val TARGET_S = 125.0

internal class SeekPositionTest {
    /** Epoch milliseconds that only move when a test says so. */
    private class TestClock {
        private val ms = AtomicLong(1_700_000_000_000)

        fun now(): Long = ms.get()

        fun advance(deltaMs: Long) {
            ms.addAndGet(deltaMs)
        }
    }

    private class Rig(
        val fake: FakeRenderer,
        val mimic: SonosMimic,
        val h: RendererHarness,
        val clock: TestClock,
        val events: CopyOnWriteArrayList<PlayerStateEvent>,
    ) {
        val r: Renderer get() = h.renderer

        /** Moves the clock and brings the speaker up to it. */
        fun advance(deltaMs: Long) {
            clock.advance(deltaMs)
            mimic.refresh()
        }

        /** What YouTubeApp.handlePlayerStateEvent would turn into an onStateChange: the status or the position moved. */
        fun sentToPhone(from: Int = 0): List<PlayerStateEvent> =
            events.drop(from).filter { it.previous == null || it.previous!!.status != it.current.status || it.previous!!.position != it.current.position }
    }

    private class QueueHandler : PlaylistRequestHandler {
        override suspend fun getPreviousNextVideos(target: Video, playlist: Playlist): PreviousNextVideos? {
            if (target.id != A) return null
            return PreviousNextVideos(null, Video(B, Client.YT, VideoContext(playlistId = "PL", index = 1)))
        }
    }

    private fun queuePayload(): JsonObject = buildJsonObject {
        put("listId", "PL")
        put("videoIds", "$A,$B")
        put("videoId", A)
        put("currentIndex", "0")
        put("currentTime", "0")
    }

    private fun config(dir: File) = Config(
        dataDir = dir,
        outputFormat = "direct",
        pollIntervalMs = 50,
        startTimeoutMs = 5000,
        nameSuffix = "",
        queueSettleTimeoutMs = 5000,
    )

    /** A renderer playing [A] on a mimic whose Seek takes [transitionMs] to settle; the speaker is already seen PLAYING. */
    private fun <T> rig(
        transitionMs: Long = 5000,
        endAtSeconds: Long? = null,
        queue: Boolean = false,
        block: suspend Rig.() -> T,
    ): T {
        val fake = FakeRenderer()
        fake.start()
        fake.state.trackDuration = "0:02:20"
        val mimic = SonosMimic(fake, fetchStream = false)
        val clock = TestClock()
        mimic.slowSeek(transitionMs, "0:00:01", endAtSeconds, clock::now)
        val handler = QueueHandler()
        val h = RendererHarness(
            fake,
            config = ::config,
            playlistRequestHandlerFactory = if (queue) ({ handler }) else null,
            duration = TRACK_S.toDouble(),
            clock = clock::now,
        )
        try {
            return runBlocking {
                val events = CopyOnWriteArrayList<PlayerStateEvent>()
                val rig = Rig(fake, mimic, h, clock, events)
                rig.r.init()
                rig.r.addStateListener { events.add(it) }
                if (queue) {
                    rig.r.queue.updateByMessage("setPlaylist", queuePayload(), Client.YT)
                    assertTrue(rig.r.play(rig.r.queue.current!!, 0.0))
                } else {
                    assertTrue(rig.r.play(Video(A), 0.0))
                }
                delay(250) // a few polls: the renderer has seen the speaker PLAYING
                rig.block()
            }
        } finally {
            h.close()
            mimic.close()
            fake.close()
        }
    }

    @Test
    @DisplayName("while a Sonos is still seeking the phone is told the seek target, not the speaker's transient 1 s")
    fun reportsTheSeekTargetWhileTheSpeakerIsStillSeeking() {
        rig {
            events.clear()
            assertTrue(r.seek(TARGET_S))

            val playing = events.last { it.current.status == PlayerStatus.PLAYING }
            assertEquals(TARGET_S, playing.current.position, "the PLAYING state after the Seek carries the target")
            assertTrue(events.all { it.current.position >= 120.0 }, "no state between the Seek and the settle is below the target: ${events.map { it.current.position }}")
        }
    }

    @Test
    @DisplayName("once the Sonos has settled the speaker's position is used again and the phone is corrected once")
    fun usesTheSpeakerPositionOnceItHasSettled() {
        rig {
            assertTrue(r.seek(TARGET_S))
            assertEquals(TARGET_S, r.getPosition(), "still seeking: the target")
            val mark = events.size

            advance(5000) // the Lamp starts playing at 2:05; the phone has been counting for those 5 s already
            waitUntil { sentToPhone(mark).isNotEmpty() }
            delay(300)

            val corrections = sentToPhone(mark)
            assertEquals(1, corrections.size, "exactly one corrected position reaches the sender: ${corrections.map { it.current.position }}")
            assertEquals(TARGET_S, corrections[0].current.position)
            assertEquals(PlayerStatus.PLAYING, corrections[0].current.status)

            advance(2000)
            assertEquals(TARGET_S + 2, r.getPosition(), "the speaker's own RelTime")
        }
    }

    @Test
    @DisplayName("a speaker that never settles is dropped after the deadline and its own position is used")
    fun givesUpAfterTheDeadline() {
        rig(transitionMs = Long.MAX_VALUE / 4) {
            assertTrue(r.seek(TARGET_S))
            assertEquals(TARGET_S, r.getPosition(), "inside the deadline the target is held")

            advance(60_000)
            waitUntil { h.notes("Speaker did not settle").isNotEmpty() }

            assertTrue(h.notes("Speaker did not settle").isNotEmpty(), "the give-up is noted")
            assertEquals(1.0, r.getPosition(), "past the deadline the speaker's RelTime is reported, no stuck overlay")
        }
    }

    @Test
    @DisplayName("a seek on a paused player reports the target, stays paused and sends Play only at resume")
    fun pausedSeekStaysPausedAndReportsTheTarget() {
        rig {
            assertTrue(r.pause())
            assertEquals(PlayerStatus.PAUSED, r.status)
            events.clear()

            assertTrue(r.seek(TARGET_S))

            assertEquals(PlayerStatus.PAUSED, r.status)
            val paused = events.last { it.current.status == PlayerStatus.PAUSED }
            assertEquals(TARGET_S, paused.current.position, "the PAUSED state after the Seek carries the target")
            assertEquals(1, fake.calls.count { it.action == "Play" }, "no Play for the seek")

            advance(5000)
            delay(300)
            assertEquals(PlayerStatus.PAUSED, r.status)
            assertEquals(TARGET_S, r.getPosition())
            assertEquals(1, fake.calls.count { it.action == "Play" })

            assertTrue(r.resume())
            assertEquals(2, fake.calls.count { it.action == "Play" }, "exactly one more Play, at resume")
            assertEquals(PlayerStatus.PLAYING, r.status)
        }
    }

    @Test
    @DisplayName("a track end after a seek still advances the queue")
    fun trackEndAfterASeekStillAdvances() {
        rig(endAtSeconds = TRACK_S, queue = true) {
            assertTrue(r.seek(TARGET_S))
            assertEquals(TARGET_S, r.getPosition(), "the phone was told the target")

            advance(5000) // settled at 2:05
            delay(300)
            advance(12_000) // 2:17, inside the last 10 s of a 2:20 track: a poll records it
            delay(300)
            advance(4000) // the speaker plays out and stops
            waitUntil { fake.calls.count { it.action == "SetAVTransportURI" } == 2 && r.status == PlayerStatus.PLAYING }

            assertEquals(2, fake.calls.count { it.action == "SetAVTransportURI" }, "the next track was sent")
            assertEquals(B, r.queue.current?.id)
            assertEquals(PlayerStatus.PLAYING, r.status)
        }
    }
}
