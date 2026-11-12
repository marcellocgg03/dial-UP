package dev.t2u.renderer

// The sender is told "buffering" while the speaker is still starting after a seek (plan 03-20, PLAY-06), GPL-3.0-only.
//
// Device session D (Lamp, format direct, 2026-10-22 06:00-06:08): a phone-driven seek made the Lamp spend 5.8 and 9.6 s
// TRANSITIONING, and a Play after a paused seek 8.6 s, while the app told the phone PLAYING within 0.25 s. The phone's
// bar ran ahead for those seconds and was snapped back by the drift check. Tests here read the sender's state code from
// the message the lounge would send (OutgoingMessage.onStateChange): 3 is buffering, 1 is playing.
//
// The rig is the plain RendererHarness with a SonosMimic in slow-seek mode (no HTTP side) and a clock the test moves by
// hand. No Node counterpart (PARITY.md adaptation 27). Every test is a block-bodied function: JUnit silently skips a test
// whose expression body returns a value.

import dev.t2u.lounge.OutgoingMessage
import dev.t2u.lounge.PlayerState
import dev.t2u.lounge.PlayerStateEvent
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.Video
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"

/** The length of the track in every test, and the seek target of the device log (scaled). */
private const val TRACK_S = 300L
private const val TARGET_S = 125.0

/** What the sender's bar shows: the state code of an onStateChange (1 playing, 2 paused, 3 buffering) and its currentTime. */
private data class Told(val code: Int, val position: Double)

internal class SeekBufferingTest {
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

        private fun code(s: PlayerState): Int = OutgoingMessage.onStateChange(null, s).payload["state"] as Int

        /** What YouTubeApp.handlePlayerStateEvent would turn into an onStateChange: the state code or the position moved. */
        fun sentToPhone(from: Int = 0): List<Told> =
            events.drop(from)
                .filter { it.previous == null || code(it.previous!!) != code(it.current) || it.previous!!.position != it.current.position }
                .map { Told(code(it.current), it.current.position) }
    }

    private fun config(dir: File) = Config(
        dataDir = dir,
        outputFormat = "direct",
        pollIntervalMs = 50,
        startTimeoutMs = 5000,
        queueSettleTimeoutMs = 5000,
    )

    /** A renderer playing [A] on a mimic whose Seek takes [transitionMs] to settle; the speaker is already seen PLAYING. */
    private fun <T> rig(transitionMs: Long, block: suspend Rig.() -> T): T {
        val fake = FakeRenderer()
        fake.start()
        fake.state.trackDuration = "0:05:00"
        val mimic = SonosMimic(fake, fetchStream = false)
        val clock = TestClock()
        mimic.slowSeek(transitionMs, "0:00:01", null, clock::now)
        val h = RendererHarness(fake, config = ::config, duration = TRACK_S.toDouble(), clock = clock::now)
        try {
            return runBlocking {
                val events = CopyOnWriteArrayList<PlayerStateEvent>()
                val rig = Rig(fake, mimic, h, clock, events)
                rig.r.init()
                rig.r.addStateListener { events.add(it) }
                assertTrue(rig.r.play(Video(A), 0.0))
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
    @DisplayName("a seek on a speaker that takes 8 s to start tells the phone buffering until it plays, then playing at the target")
    fun aSeekOnASlowStartingSpeakerReportsBufferingUntilItPlays() {
        rig(transitionMs = 8000) {
            events.clear()
            assertTrue(r.seek(TARGET_S))

            val afterSeek = sentToPhone()
            assertTrue(afterSeek.isNotEmpty(), "the phone is told something about the seek")
            assertTrue(afterSeek.all { it.code == 3 && it.position == TARGET_S }, "buffering at the target right after the Seek, not playing: $afterSeek")

            advance(4000)
            delay(300) // polls read TRANSITIONING
            assertTrue(sentToPhone().all { it.code == 3 }, "still buffering 4 s into the transition: ${sentToPhone()}")

            advance(4100) // 8.1 s: the speaker plays at the target
            waitUntil { sentToPhone().any { it.code == 1 } }
            val playing = sentToPhone().firstOrNull { it.code == 1 }
            assertTrue(playing != null, "the phone is told playing once the speaker plays: ${sentToPhone()}")
            assertEquals(TARGET_S, playing.position, "playing is told at the target")
            assertEquals(PlayerStatus.PLAYING, r.status)
        }
    }

    @Test
    @DisplayName("a Play right after a paused seek tells the phone buffering while the speaker is TRANSITIONING, then playing")
    fun playAfterAPausedSeekReportsBufferingUntilItPlays() {
        rig(transitionMs = 8600) {
            assertTrue(r.pause())
            assertTrue(r.seek(TARGET_S))
            assertEquals(PlayerStatus.PAUSED, r.status)
            val paused = sentToPhone().last()
            assertEquals(2, paused.code, "a paused seek shows paused, not buffering: ${sentToPhone()}")
            events.clear()

            assertTrue(r.resume())
            val afterPlay = sentToPhone()
            assertTrue(afterPlay.isNotEmpty() && afterPlay.all { it.code == 3 }, "buffering after Play while the speaker is TRANSITIONING, not playing: $afterPlay")

            advance(4000)
            delay(300)
            assertTrue(sentToPhone().all { it.code == 3 }, "still buffering 4 s in: ${sentToPhone()}")

            advance(4700) // 8.7 s: PLAYING at the target
            waitUntil { sentToPhone().any { it.code == 1 } }
            val playing = sentToPhone().firstOrNull { it.code == 1 }
            assertTrue(playing != null, "playing is told once the speaker plays: ${sentToPhone()}")
            assertEquals(TARGET_S, playing.position)
        }
    }

    @Test
    @DisplayName("a paused seek that had settled, then a Play that leaves the speaker TRANSITIONING, is shown as buffering until it plays")
    fun playAfterASettledPausedSeekReportsBufferingUntilItPlays() {
        rig(transitionMs = 1000) {
            assertTrue(r.pause())
            assertTrue(r.seek(TARGET_S))
            advance(1500) // the Lamp has taken the paused seek: PAUSED_PLAYBACK at the target
            delay(300) // the poll settles the Seek
            events.clear()

            // The Lamp of device session D: Play after a paused seek leaves it TRANSITIONING for seconds.
            val mimicHook = fake.onAction
            fake.onAction = { action ->
                mimicHook?.invoke(action)
                if (action == "Play") fake.state.transport = "TRANSITIONING"
            }
            assertTrue(r.resume())
            delay(300) // polls read TRANSITIONING
            val afterPlay = sentToPhone()
            assertTrue(afterPlay.isNotEmpty() && afterPlay.all { it.code == 3 }, "buffering after Play while the speaker is TRANSITIONING: $afterPlay")

            fake.state.transport = "PLAYING"
            fake.state.relTime = SonosMimic.hms(TARGET_S.toLong())
            waitUntil { sentToPhone().any { it.code == 1 } }
            val playing = sentToPhone().firstOrNull { it.code == 1 }
            assertTrue(playing != null, "playing is told once the speaker plays: ${sentToPhone()}")
            assertEquals(TARGET_S, playing.position)
        }
    }

    @Test
    @DisplayName("a speaker that never leaves TRANSITIONING is told the truth at the 15 s deadline: the hold ends")
    fun aSpeakerThatNeverSettlesIsToldTheTruthAtTheDeadline() {
        rig(transitionMs = Long.MAX_VALUE / 4) {
            events.clear()
            assertTrue(r.seek(TARGET_S))

            advance(60_000)
            waitUntil { h.notes("Speaker did not settle").isNotEmpty() }
            assertTrue(h.notes("Speaker did not settle").isNotEmpty(), "the give-up is noted")
            waitUntil { sentToPhone().lastOrNull()?.let { it.code == 1 && it.position == 1.0 } == true }

            val last = sentToPhone().last()
            assertEquals(1, last.code, "past the deadline the phone is not left on buffering: ${sentToPhone()}")
            assertEquals(1.0, last.position, "and it is told the speaker's own position")
        }
    }

    @Test
    @DisplayName("a speaker that starts within 300 ms is shown as playing again within about one poll")
    fun aFastSeekIsNotShownAsBufferingForLong() {
        rig(transitionMs = 300) {
            events.clear()
            assertTrue(r.seek(TARGET_S))
            advance(300) // PLAYING at the target
            val started = System.currentTimeMillis()
            waitUntil(1000) { sentToPhone().lastOrNull()?.code == 1 }

            assertEquals(1, sentToPhone().last().code, "playing is told: ${sentToPhone()}")
            assertTrue(System.currentTimeMillis() - started < 500, "within about one poll of the speaker playing")
            assertEquals(TARGET_S, sentToPhone().last().position)
        }
    }
}
