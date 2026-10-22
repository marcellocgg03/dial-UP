package dev.t2u.renderer

// The phone's seekbar corrects itself when the speaker's position drifts from it (plan 03-18, PLAY-06 and PLAY-07), GPL-3.0-only.
//
// The phone counts its seekbar on from the last position it was sent, and the app sends a position only when something
// changes. Anything that moves the speaker's position without a command from the phone (a seek or scrub in the Sonos app, a
// stale reading, a stream restart) therefore left the bar wrong until the next event (device session C, 2026-10-21). The poll
// now compares the speaker's RelTime with where the phone's count has got to and sends one corrected position when they
// differ by more than the tolerance on two consecutive polls. It must send nothing otherwise: a track that plays on
// uninterrupted costs no extra messages.
//
// The rig is the plain RendererHarness with a SonosMimic that plays on by itself (SonosMimic.followClock) and a clock the
// test moves by hand. No Node counterpart (PARITY.md adaptation 25). Every test is a block-bodied function: JUnit silently
// skips a test whose expression body returns a value.

import dev.t2u.lounge.PlayerStateEvent
import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.Video
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"
private const val B = "BBBBBBBBBBB"

/** The length of the track in every test: long enough that 3 minutes of play never reach its end. */
private const val TRACK_S = 300L

/** The Renderer polls every 50 ms of real time (the config below); the clock only moves when a test says so. */
private const val POLL_MS = 50L

internal class PositionDriftTest {
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

        /** How many times the speaker was asked for its position (the poll asks once per cycle, a state build once more). */
        fun positionReads(): Int = fake.calls.count { it.action == "GetPositionInfo" }
    }

    private fun config(dir: File) = Config(
        dataDir = dir,
        outputFormat = "direct",
        pollIntervalMs = POLL_MS,
        startTimeoutMs = 5000,
        nameSuffix = "",
        queueSettleTimeoutMs = 5000,
    )

    /** A renderer playing [A] on a mimic that plays on by itself; the speaker is already seen PLAYING at about 0 s. */
    private fun <T> rig(
        lag: Boolean = false,
        block: suspend Rig.() -> T,
    ): T {
        val fake = FakeRenderer()
        fake.start()
        fake.state.trackDuration = "0:05:00"
        val mimic = SonosMimic(fake, fetchStream = false)
        val clock = TestClock()
        mimic.followClock(clock::now)
        if (lag) mimic.laggingSeek(700, 5000, "0:00:20", null, clock::now)
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
    @DisplayName("a seek done in the Sonos app reaches the phone as one corrected position within about two polls")
    fun aSeekDoneInTheSonosAppReachesThePhone() {
        rig {
            advance(20_000)
            delay(300)
            val mark = events.size
            assertEquals(emptyList(), sentToPhone(mark))

            val readsAtJump = positionReads()
            mimic.jumpTo(100.0) // no command from the phone
            waitUntil(timeoutMs = 3000) { sentToPhone(mark).isNotEmpty() }
            val readsUsed = positionReads() - readsAtJump
            delay(200)

            val sent = sentToPhone(mark)
            assertEquals(1, sent.size, "exactly one message reaches the phone: ${sent.map { it.current.position }}")
            assertEquals(PlayerStatus.PLAYING, sent[0].current.status)
            assertEquals(100.0, sent[0].current.position, 1.5, "the speaker's position, not the phone's count")
            assertTrue(readsUsed <= 6, "two confirming polls, the build of the state and slack, not a slow sweep: $readsUsed position reads")
        }
    }

    @Test
    @DisplayName("a scrub backwards in the Sonos app is mirrored to the phone too")
    fun aScrubBackwardsReachesThePhone() {
        rig {
            advance(150_000)
            delay(300)
            val mark = events.size

            mimic.jumpTo(10.0)
            waitUntil(timeoutMs = 3000) { sentToPhone(mark).isNotEmpty() }
            delay(200)

            val sent = sentToPhone(mark)
            assertEquals(1, sent.size, "${sent.map { it.current.position }}")
            assertEquals(10.0, sent[0].current.position, 1.5)
        }
    }

    @Test
    @DisplayName("a 3-minute track whose speaker keeps in step sends no position message after the start")
    fun anUneventfulTrackSendsNoPositionMessages() {
        rig {
            val mark = events.size
            val readsAtStart = positionReads()

            repeat(36) {
                advance(5000) // 3 minutes of virtual time in all
                delay(100) // two polls per step
            }

            assertEquals(emptyList(), sentToPhone(mark).map { it.current.position }, "no extra position message in 3 minutes")
            assertTrue(positionReads() - readsAtStart >= 36, "the poll had its chances: ${positionReads() - readsAtStart} position reads")
            assertEquals(PlayerStatus.PLAYING, r.status)
            assertEquals(180.0, r.getPosition(), 1.5, "the mimic stayed in step with the clock the whole time")
        }
    }

    @Test
    @DisplayName("after one correction further polls that agree send nothing more")
    fun oneCorrectionPerDriftNotOnePerPoll() {
        rig {
            advance(20_000)
            delay(300)
            val mark = events.size

            mimic.jumpTo(100.0)
            waitUntil(timeoutMs = 3000) { sentToPhone(mark).isNotEmpty() }
            repeat(6) {
                advance(2000)
                delay(100)
            }

            val sent = sentToPhone(mark)
            assertEquals(1, sent.size, "one correction for one drift: ${sent.map { it.current.position }}")
        }
    }

    @Test
    @DisplayName("one poll that reads 60 s off while the next reads right sends nothing")
    fun aSingleOddReadingIsIgnored() {
        rig {
            advance(20_000)
            delay(300)
            val mark = events.size

            mimic.misreadNextPoll(60)
            waitUntil(timeoutMs = 3000) { mimic.skewedReads >= 1 }
            assertEquals(1, mimic.skewedReads, "exactly one poll read the odd value")
            val readsAtOdd = positionReads()
            waitUntil(timeoutMs = 3000) { positionReads() - readsAtOdd >= 8 }
            delay(100)

            assertEquals(emptyList(), sentToPhone(mark).map { it.current.position }, "a single odd reading is not acted on")
        }
    }

    @Test
    @DisplayName("no correction while the player is paused, even when the speaker's position moved")
    fun noCheckWhilePaused() {
        rig {
            advance(20_000)
            delay(300)
            assertTrue(r.pause())
            assertEquals(PlayerStatus.PAUSED, r.status)
            val mark = events.size

            mimic.jumpTo(100.0)
            val readsAtJump = positionReads()
            waitUntil(timeoutMs = 3000) { positionReads() - readsAtJump >= 8 }
            delay(100)

            assertEquals(emptyList(), sentToPhone(mark).map { it.current.position }, "paused: the speaker's position is not mirrored")
            assertEquals(100.0, r.getPosition(), 1.5, "the speaker did move")
        }
    }

    @Test
    @DisplayName("no correction while a load is in progress")
    fun noCheckWhileLoading() {
        rig {
            advance(20_000)
            delay(300)
            val gate = CountDownLatch(1)
            h.prepareFailure = {
                gate.await(10, TimeUnit.SECONDS)
                null
            }
            val before = events.size
            val loading = h.scope.launch { r.play(Video(B), 0.0) }
            waitUntil(timeoutMs = 3000) { events.drop(before).any { it.current.status == PlayerStatus.LOADING } }
            assertEquals(PlayerStatus.LOADING, r.status)
            val mark = events.size // after the LOADING state itself

            mimic.jumpTo(100.0)
            delay(500)

            assertEquals(emptyList(), sentToPhone(mark).map { it.current.position }, "loading: nothing is mirrored")
            gate.countDown()
            loading.join()
        }
    }

    @Test
    @DisplayName("no correction while a seek hold is pending, even though the lagging speaker is far from the phone's count")
    fun noCheckWhileASeekHoldIsPending() {
        rig(lag = true) {
            advance(20_000)
            delay(300)
            assertTrue(r.seek(125.0))
            val mark = events.size

            advance(300) // lagging: PLAYING at the old 0:00:20, 105 s from the phone's count
            delay(300)
            advance(500) // 0.8 s: TRANSITIONING at the target
            delay(300)
            advance(3000) // 3.8 s: still TRANSITIONING
            delay(300)

            assertEquals(emptyList(), sentToPhone(mark).map { it.current.position }, "the hold is pending: the drift check leaves it alone")
        }
    }

    @Test
    @DisplayName("a speaker with a jittery RelTime gets at most one correction per minimum gap")
    fun correctionsAreRateLimited() {
        rig {
            advance(20_000)
            delay(300)
            val mark = events.size

            mimic.jitter(listOf(30, 40, 30, 50)) // every poll reads 30 to 50 s ahead of the speaker
            repeat(35) {
                advance(1000) // 35 s of virtual time
                delay(120)
            }

            val sent = sentToPhone(mark)
            assertTrue(sent.isNotEmpty(), "the drift is real, so it is corrected at least once")
            assertTrue(sent.size <= 4, "at most one per ${DRIFT_MIN_GAP_MS / 1000} s of the 35 s: ${sent.size} corrections ${sent.map { it.current.position }}")
        }
    }

    @Test
    @DisplayName("a speaker that names another stream is not believed")
    fun aSpeakerOnAnotherStreamIsIgnored() {
        rig {
            advance(20_000)
            delay(300)
            val mark = events.size

            fake.state.reportedUri = "http://127.0.0.1:9/some/other/stream.mp3"
            mimic.jumpTo(100.0)
            val readsAtJump = positionReads()
            waitUntil(timeoutMs = 3000) { positionReads() - readsAtJump >= 8 }
            delay(100)

            assertEquals(emptyList(), sentToPhone(mark).map { it.current.position }, "another stream's position is not ours")
        }
    }
}
