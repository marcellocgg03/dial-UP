package dev.t2u.renderer

// A seek sent before the speaker has ever played (plan 03-17, PLAY-04 and PLAY-06), GPL-3.0-only.
//
// Scenario copied from the device log (Lamp, format direct, 2026-10-21 22:01-22:03, "Sun On Replay", 156 s): the track
// loads and the Lamp is still TRANSITIONING; two seconds later the phone sends seekTo 143, near the end of the file, and
// the Lamp stays TRANSITIONING for 12+ s before it plays at the target. The start watchdog (no PLAYING within
// startTimeoutMs of the load) fired in the middle of that seek, said "Renderer did not start direct - trying mp4",
// forgot the learned format and reloaded the song, which then played from the start.
//
// The rig is the plain RendererHarness with a SonosMimic whose speaker is slow to start (SonosMimic.holdStartWhile) and a
// clock the test moves by hand. Every test is a block-bodied function: JUnit silently skips a test whose expression body
// returns a value. No Node counterpart (PARITY.md adaptation 24).

import dev.t2u.lounge.PlayerStatus
import dev.t2u.lounge.Video
import dev.t2u.upnp.FakeRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"
private const val B = "BBBBBBBBBBB"

/** The length of the track of the device log. */
private const val TRACK_S = 156.0

internal class SeekBeforeFirstPlayTest {
    private class TestClock {
        private val ms = AtomicLong(1_700_000_000_000)

        fun now(): Long = ms.get()

        fun advance(deltaMs: Long) {
            ms.addAndGet(deltaMs)
        }
    }

    private class Rig(val fake: FakeRenderer, val mimic: SonosMimic, val h: RendererHarness, val clock: TestClock) {
        val r: Renderer get() = h.renderer

        fun advance(deltaMs: Long) {
            clock.advance(deltaMs)
            mimic.refresh()
        }

        /** [steps] steps of [stepMs] of virtual time, each long enough in real time for the 50 ms poll to run. */
        suspend fun tick(steps: Int, stepMs: Long = 1000) {
            repeat(steps) {
                advance(stepMs)
                delay(120)
            }
        }
    }

    private fun config(dir: File, outputFormat: String = "auto") = Config(
        dataDir = dir,
        outputFormat = outputFormat,
        pollIntervalMs = 50,
        startTimeoutMs = 5000,
        queueSettleTimeoutMs = 5000,
    )

    private fun storedFormat(dir: File): String? = runBlocking {
        val v = RendererDataStore(dir, "fake-renderer").get(LEARNED_FORMAT_KEY)
        (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }

    private fun seedFormat(dir: File, value: String) = runBlocking {
        RendererDataStore(dir, "fake-renderer").set(LEARNED_FORMAT_KEY, JsonPrimitive(value))
    }

    /**
     * A renderer in auto mode with "direct" remembered for the speaker, playing [A] on a mimic that is slow to start
     * (see [setup]) and takes [transitionMs] to settle a Seek. [setup] runs before the Play.
     */
    private fun <T> rig(
        transitionMs: Long = 13_000,
        setup: (SonosMimic, FakeRenderer) -> Unit = { mimic, _ -> mimic.holdStartWhile { true } },
        block: suspend Rig.() -> T,
    ): T {
        val fake = FakeRenderer()
        fake.start()
        fake.state.trackDuration = "0:02:36"
        val mimic = SonosMimic(fake, fetchStream = false)
        val clock = TestClock()
        mimic.laggingSeek(lagMs = 0, transitionMs = transitionMs, staleRelTime = "0:00:00", clock = clock::now)
        setup(mimic, fake)
        val dir = Files.createTempDirectory("renderer-").toFile().also { it.deleteOnExit() }
        seedFormat(dir, "direct")
        val h = RendererHarness(fake, config = { config(it) }, dataDir = dir, duration = TRACK_S, clock = clock::now)
        try {
            return runBlocking {
                val rig = Rig(fake, mimic, h, clock)
                rig.r.init()
                assertTrue(rig.r.play(Video(A), 0.0))
                delay(250) // a few polls: the speaker is TRANSITIONING
                rig.block()
            }
        } finally {
            h.close()
            mimic.close()
            fake.close()
        }
    }

    private fun Rig.fellBack(): Boolean = h.notes("Renderer did not start").isNotEmpty()

    @Test
    @DisplayName("a seek sent before the speaker has played does not make the start watchdog give up on the format (Lamp, 22:01)")
    fun anEarlySeekDoesNotCondemnTheFormat() {
        rig {
            advance(2000) // the log: seekTo 143 came two seconds after the load
            assertTrue(r.seek(1.0))

            tick(13) // the speaker spends 13 s TRANSITIONING after the native Seek
            tick(2)

            assertTrue(!fellBack(), "no format fallback: ${h.notes("Renderer did not start").map { it["message"] }}")
            assertEquals(listOf("direct"), h.formats(), "no second load")
            assertEquals(1, fake.calls.count { it.action == "SetAVTransportURI" })
            assertEquals("direct", storedFormat(h.dataDir), "the learned format is kept")
            assertEquals("PLAYING", fake.state.transport)
            val position = r.getPosition()
            assertTrue(position in 1.0..5.0, "the speaker plays at the seek target: $position")
            assertEquals(PlayerStatus.PLAYING, r.status)
        }
    }

    @Test
    @DisplayName("a speaker that never starts and takes no seek still falls back to the next format after the start timeout")
    fun aGenuineStartFailureStillFallsBack() {
        rig {
            tick(8)
            waitUntil { h.formats().size >= 2 }

            assertTrue(fellBack(), "Renderer did not start direct - trying mp3")
            assertEquals(listOf("direct", "mp3"), h.formats().take(2))
        }
    }

    @Test
    @DisplayName("an early seek on direct reloads at the target, not at the start of the song")
    fun aReloadAtAPositionPlaysFromThatPosition() {
        // direct never starts; an early seek reloads mp3 from the requested offset.
        rig(
            transitionMs = Long.MAX_VALUE / 4,
            setup = { mimic, fake -> mimic.holdStartWhile { !fake.state.currentMetaData.contains("audio/mpeg:") } },
        ) {
            advance(2000)
            assertTrue(r.seek(145.0))

            assertEquals(listOf("direct", "mp3"), h.formats())

            tick(3)
            assertEquals(0, fake.calls.count { it.action == "Seek" }, "the direct stream is not natively sought")
            assertEquals(145, h.registered[1].startAt, "the MP3 stream starts at the requested position")
            val position = r.getPosition()
            assertTrue(position in 145.0..150.0, "the position told to the phone is the speaker's: $position")
        }
    }

    @Test
    @DisplayName("repeated seeks cannot keep a dead speaker waiting for ever: the hard cap ends the start wait")
    fun repeatedSeeksCannotKeepADeadSpeakerWaitingForever() {
        rig(transitionMs = Long.MAX_VALUE / 4) {
            repeat(4) {
                assertTrue(r.seek(1.0))
                tick(3)
            }
            assertTrue(!fellBack(), "12 s of seeks every 3 s: each one restarts the clock")

            repeat(6) {
                r.seek(1.0)
                tick(3)
            }
            waitUntil { h.formats().size >= 2 }
            assertTrue(fellBack(), "past the cap from the first start the format is given up on")
        }
    }

    @Test
    @DisplayName("a seek sent while the speaker is still starting and refused is sent again once the speaker plays")
    fun aSeekAfterPlayRefusedWhileStartingIsAppliedWhenTheSpeakerPlays() {
        val fake = FakeRenderer()
        fake.start()
        fake.state.trackDuration = "0:02:36"
        // A speaker that is TRANSITIONING after Play and refuses a Seek until it plays.
        fake.onAction = { action ->
            if (action == "Play") {
                fake.state.transport = "TRANSITIONING"
                fake.state.relTime = "0:00:00"
                fake.bareFaultOn = setOf("Seek")
            }
        }
        val clock = TestClock()
        val h = RendererHarness(fake, config = { config(it, "mp4") }, duration = TRACK_S, clock = clock::now)
        try {
            runBlocking {
                h.renderer.init()
                val playing = async(Dispatchers.Default) { h.renderer.play(Video(A), 145.0) }
                var steps = 0
                while (!playing.isCompleted && steps++ < 40) {
                    clock.advance(1000)
                    delay(100)
                }
                assertTrue(playing.await(), "the load finished after the 8 s wait for PLAYING")
                assertEquals(1, fake.calls.count { it.action == "Seek" }, "the one Seek during the start was refused")

                fake.bareFaultOn = emptySet()
                fake.state.transport = "PLAYING"
                waitUntil { fake.calls.count { it.action == "Seek" } >= 2 }

                val seeks = fake.calls.filter { it.action == "Seek" }
                assertEquals(2, seeks.size, "the Seek is sent again once the speaker plays")
                assertTrue(seeks.last().body.contains("<Target>00:02:25</Target>"), seeks.last().body)
                assertEquals("0:02:25", fake.state.relTime)
                val position = h.renderer.getPosition()
                assertTrue(position in 145.0..150.0, "$position")
            }
        } finally {
            h.close()
            fake.close()
        }
    }

    @Test
    @DisplayName("a start failure does not make the app forget a format that has already played on this speaker")
    fun afterAFallbackTheWorkingFormatIsNotForgotten() {
        val fake = FakeRenderer()
        fake.start()
        val plays = AtomicInteger()
        // The first Play (track A) starts; every later one never does.
        fake.onAction = { action -> if (action == "Play" && plays.incrementAndGet() > 1) fake.state.transport = "STOPPED" }
        val h = RendererHarness(fake)
        try {
            runBlocking {
                h.renderer.init()
                assertTrue(h.renderer.play(Video(A), 0.0))
                waitUntil { storedFormat(h.dataDir) == "direct" }
                assertEquals("direct", storedFormat(h.dataDir), "direct played and is remembered")

                h.renderer.play(Video(B), 0.0)
                waitUntil { h.renderer.status == PlayerStatus.STOPPED }

                assertTrue(h.notes("Renderer did not start").isNotEmpty(), "track B hit the start failure")
                assertTrue(h.formats().size >= 3, "the walk went on: ${h.formats()}")
                assertEquals("direct", storedFormat(h.dataDir), "the format that played is still remembered")
            }
        } finally {
            h.close()
            fake.close()
        }
    }
}
