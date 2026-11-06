package dev.t2u.renderer

// Port of the renderer.test.js seek and mid-video titles (PLAY-06) plus the Kotlin additions of plan 03-09,
// driven against the upnp FakeRenderer. GPL-3.0-only.
//
// Adaptations from the Node tests (plan 03-12 copies these into PARITY.md "Adaptations"):
//  * The original ported tests pin outputFormat; the auto-mode tests advertise the formats they need.
//  * The Node fake rejected Seek through bareFaultOn; the Kotlin fake can switch the fault off mid-test.
//  * A restart offset is clamped to one second before the end of the track (T-03-30); Node does not clamp.
//  * A native Seek on a file that itself starts at an offset is sent relative to that file; Node sends the
//    absolute target (which a file that starts at the offset would overshoot).
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val A = "AAAAAAAAAAA"

internal class SeekFallbackTest {
    private fun <T> withFake(fake: FakeRenderer = FakeRenderer(), block: suspend (FakeRenderer) -> T): T {
        fake.start()
        try {
            return runBlocking { block(fake) }
        } finally {
            fake.close()
        }
    }

    private fun pinned(format: String): (File) -> Config =
        { Config(dataDir = it, outputFormat = format, pollIntervalMs = 50, startTimeoutMs = 2000, nameSuffix = "") }

    private fun FakeRenderer.count(action: String): Int = calls.count { it.action == action }

    private fun FakeRenderer.seekTargets(): List<String> =
        calls.filter { it.action == "Seek" }.map { Regex("<Target>(.*?)</Target>").find(it.body)?.groupValues?.get(1) ?: "" }

    @Test
    @DisplayName("auto direct seeks past one second by loading ADTS at the target")
    fun autoDirectSeekReloadsAdtsAtTarget() {
        withFake(FakeRenderer(sink = "http-get:*:audio/mp4:*,http-get:*:audio/aac:*,http-get:*:audio/mpeg:*")) { fake ->
            RendererHarness(fake, config = pinned("auto"), duration = 1000.0).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertTrue(h.renderer.doSeek(600.0))
                assertEquals(listOf("direct" to 0, "adts" to 600), h.registered.map { it.format to it.startAt })
                assertEquals(listOf("direct" to 0, "adts" to 600), h.prepared.map { it.format to it.startAt })
                assertEquals(2, fake.count("SetAVTransportURI"))
                assertEquals(0, fake.count("Seek"))
                assertTrue(fake.state.currentUri.endsWith(".aac"), fake.state.currentUri)
                assertTrue(fake.state.currentMetaData.contains("audio/aac:"), fake.state.currentMetaData)
            }
        }
    }

    @Test
    @DisplayName("auto direct seeks at or below one second use native Seek")
    fun autoDirectShortSeeksAreNative() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("auto"), duration = 1000.0).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertTrue(h.renderer.doSeek(0.5))
                assertTrue(h.renderer.doSeek(1.0))
                assertEquals(listOf("00:00:00", "00:00:01"), fake.seekTargets())
                assertEquals(listOf("direct" to 0), h.registered.map { it.format to it.startAt })
                assertEquals(1, fake.count("SetAVTransportURI"))
            }
        }
    }

    @Test
    @DisplayName("auto direct seek uses MP3 when the speaker does not accept AAC")
    fun autoDirectSeekReloadsMp3WithoutAac() {
        withFake(FakeRenderer(sink = "http-get:*:audio/mp4:*,http-get:*:audio/mpeg:*")) { fake ->
            RendererHarness(fake, config = pinned("auto"), duration = 1000.0).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertTrue(h.renderer.doSeek(600.9))
                assertEquals(listOf("direct" to 0, "mp3" to 600), h.registered.map { it.format to it.startAt })
                assertEquals(2, fake.count("SetAVTransportURI"))
                assertEquals(0, fake.count("Seek"))
                assertTrue(fake.state.currentUri.endsWith(".mp3"), fake.state.currentUri)
                assertTrue(fake.state.currentMetaData.contains("audio/mpeg:"), fake.state.currentMetaData)
            }
        }
    }

    @Test
    @DisplayName("an auto mid-track start prepares ADTS at the requested offset")
    fun autoMidTrackStartUsesAdtsAtOffset() {
        withFake(FakeRenderer(sink = "http-get:*:audio/mp4:*,http-get:*:audio/aac:*,http-get:*:audio/mpeg:*")) { fake ->
            RendererHarness(fake, config = pinned("auto"), duration = 1000.0).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 300.0))
                assertEquals(listOf("adts" to 300), h.prepared.map { it.format to it.startAt })
                assertEquals(listOf("adts" to 300), h.registered.map { it.format to it.startAt })
                assertEquals(1, fake.count("SetAVTransportURI"))
                assertEquals(0, fake.count("Seek"))
                assertTrue(fake.state.currentUri.endsWith(".aac"), fake.state.currentUri)
            }
        }
    }

    @Test
    @DisplayName("forced direct keeps the native Seek for a mid-track target")
    fun forcedDirectStillUsesNativeSeek() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("direct"), duration = 1000.0).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertTrue(h.renderer.doSeek(600.0))
                assertEquals(listOf("00:10:00"), fake.seekTargets())
                assertEquals(listOf("direct" to 0), h.registered.map { it.format to it.startAt })
                assertEquals(1, fake.count("SetAVTransportURI"))
            }
        }
    }

    @Test
    @DisplayName("MP4: if the renderer rejects Seek, a new MP4 starting at the offset is sent")
    fun mp4RejectedSeekLoadsAFileAtTheOffset() {
        withFake(FakeRenderer(bareFaultOn = setOf("Seek"))) { fake ->
            RendererHarness(fake, config = pinned("mp4")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertTrue(h.renderer.doSeek(80.0))
                assertEquals(listOf("mp4" to 0, "mp4" to 80), h.registered.map { it.format to it.startAt })
                assertEquals(80.0 + 65.0, h.renderer.doGetPosition(), "the offset plus the speaker's own RelTime")
            }
        }
    }

    @Test
    @DisplayName("a speaker that accepts Seek gets one native Seek on mp4 and nothing is rebuilt")
    fun mp4NativeSeekSendsOneSeek() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("mp4")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertTrue(h.renderer.doSeek(83.0))
                assertEquals(listOf("00:01:23"), fake.seekTargets())
                assertEquals(1, h.registered.size, "nothing new registered")
                assertEquals(1, fake.count("SetAVTransportURI"))
            }
        }
    }

    @Test
    @DisplayName("seeking a non-seekable stream (mp3/adts) restarts it at the offset")
    fun nonSeekableStreamRestartsAtTheOffset() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("mp3")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertTrue(h.renderer.doSeek(95.7))
                assertEquals(listOf("mp3" to 0, "mp3" to 95), h.registered.map { it.format to it.startAt })
                assertEquals(95.0 + 65.0, h.renderer.doGetPosition())
                assertEquals(210.0, h.renderer.doGetDuration())
                assertEquals(0, fake.count("Seek"), "no AVTransport Seek on a non-seekable stream")
            }
        }
    }

    @Test
    @DisplayName("casting mid-video with a non-seekable format starts the stream at that position")
    fun castingMidVideoWithANonSeekableFormat() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 36.0))
                assertEquals(36, h.registered[0].startAt)
                assertTrue(fake.calls.first { it.action == "SetAVTransportURI" }.body.contains("duration=&quot;0:02:54.000&quot;"))
            }
        }
    }

    @Test
    @DisplayName("direct has no restart fallback: a rejected Seek is a failed seek")
    fun directRejectedSeekFails() {
        withFake(FakeRenderer(bareFaultOn = setOf("Seek"))) { fake ->
            RendererHarness(fake, config = pinned("direct")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertFalse(h.renderer.doSeek(50.0))
                assertEquals(1, h.registered.size, "nothing new registered")
                assertEquals(1, fake.count("SetAVTransportURI"))
                assertTrue(h.notes("Renderer rejected Seek").isNotEmpty(), "the failure is noted")
            }
        }
    }

    @Test
    @DisplayName("a restart offset is clamped to one second before the end of the track")
    fun restartOffsetIsClamped() {
        withFake(FakeRenderer(bareFaultOn = setOf("Seek"))) { fake ->
            RendererHarness(fake, config = pinned("adts")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertTrue(h.renderer.doSeek(500.0))
                assertEquals(209, h.registered.last().startAt)
            }
            RendererHarness(fake, config = pinned("mp4")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertTrue(h.renderer.doSeek(210.0))
                assertEquals(209, h.registered.last().startAt)
            }
        }
    }

    @Test
    @DisplayName("the clamp does nothing when the track length is unknown")
    fun clampIsSkippedWithoutALength() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("adts"), duration = 0.0).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertTrue(h.renderer.doSeek(500.0))
                assertEquals(500, h.registered.last().startAt)
            }
        }
    }

    @Test
    @DisplayName("a native Seek on a file that starts at an offset is relative to that file")
    fun nativeSeekOnAnOffsetFileIsRelative() {
        withFake(FakeRenderer(bareFaultOn = setOf("Seek"))) { fake ->
            RendererHarness(fake, config = pinned("mp4")).use { h ->
                h.renderer.init()
                assertTrue(h.renderer.doPlay(Video(A), 0.0))
                assertTrue(h.renderer.doSeek(80.0))
                assertEquals(80, h.registered.last().startAt)

                fake.bareFaultOn = emptySet() // the speaker takes a Seek from now on
                assertTrue(h.renderer.doSeek(120.0))
                assertEquals(listOf("00:01:20", "00:00:40"), fake.seekTargets(), "the second target is 120 - 80")
                assertEquals(2, h.registered.size, "no new file")
                assertEquals(80.0 + 40.0, h.renderer.doGetPosition(), "the sender-facing position stays absolute: the offset plus the speaker's RelTime after the Seek")

                assertTrue(h.renderer.doSeek(30.0)) // before the start of the offset file
                assertEquals(1, fake.seekTargets().count { it == "00:00:40" }, "no native Seek for a target before the file")
                assertEquals(listOf(0, 80, 30), h.registered.map { it.startAt })
            }
        }
    }

    @Test
    @DisplayName("a rejected-Seek seek on a paused player loads the new file without a Play")
    fun pausedFallbackSeekStaysPaused() {
        withFake(FakeRenderer(bareFaultOn = setOf("Seek"))) { fake ->
            RendererHarness(fake, config = pinned("mp4")).use { h ->
                val r = h.renderer
                r.init()
                assertTrue(r.play(Video(A), 0.0))
                assertTrue(r.pause())

                assertTrue(r.seek(50.0))

                assertEquals(2, fake.count("SetAVTransportURI"))
                assertEquals(1, fake.count("Play"), "no Play reached the speaker")
                assertEquals(PlayerStatus.PAUSED, r.status)
                assertEquals(listOf(0, 50), h.registered.map { it.startAt })
                delay(300)
                assertEquals(PlayerStatus.PAUSED, r.status)
                assertEquals(1, fake.count("Play"))

                assertTrue(r.resume())
                assertEquals(2, fake.count("Play"), "exactly one more Play")
                assertEquals(PlayerStatus.PLAYING, r.status)
            }
        }
    }

    @Test
    @DisplayName("a native Seek on a paused mp4 stream sends one Seek and nothing else")
    fun pausedNativeSeekSendsOnlySeek() {
        withFake { fake ->
            RendererHarness(fake, config = pinned("mp4")).use { h ->
                val r = h.renderer
                r.init()
                assertTrue(r.play(Video(A), 0.0))
                assertTrue(r.pause())

                assertTrue(r.seek(50.0))

                assertEquals(1, fake.count("Seek"))
                assertEquals(1, fake.count("Play"))
                assertEquals(1, fake.count("SetAVTransportURI"))
                assertEquals(PlayerStatus.PAUSED, r.status)
            }
        }
    }
}
